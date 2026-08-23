package com.contactcenter.domain.retention;

import com.contactcenter.domain.retention.dto.PurgeResultDto;
import com.contactcenter.domain.retention.dto.RetentionSummaryDto;
import com.contactcenter.security.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Silnik usuwania per-tenant dla kategorii {@code CONTACT_INTERACTIONS}, {@code TRANSCRIPTS}
 * (batchowane, EPIC-29, BE-113) i {@code CAMPAIGN_DATA} (pojedynczy DELETE przez funkcję SQL
 * {@code purge_campaign_contact_archive}, BE-119).
 *
 * <p>Usuwanie odbywa się na poziomie wiersza, per tenant, niezależnie od innych tenantów
 * współdzielących tę samą partycję miesięczną / tabelę — patrz uzasadnienie techniczne w
 * {@link RetentionPurgeServiceImpl}.
 *
 * <p><strong>Poza zakresem tego serwisu:</strong>
 * <ul>
 *   <li>{@code RECORDINGS} — obsługiwane przez {@code RecordingRetentionJob} (BE-116):
 *       zerowanie kolumny {@code recording_url} + usunięcie obiektu S3, nie DELETE wiersza {@code contact}.</li>
 * </ul>
 * Wywołanie {@link #purge} dla tej kategorii rzuca {@link UnsupportedOperationException}.
 *
 * <p>Wywoływane ręcznie (przyszły {@code POST /purge}, BE-118) lub automatycznie (przyszły
 * auto-purge scheduler, BE-112).
 */
public interface RetentionPurgeService {

    /**
     * Inicjuje asynchroniczne usuwanie danych tenanta dla wskazanej kategorii.
     *
     * <p>Wykonanie jest asynchroniczne: metoda zapisuje wiersz {@code RUNNING} do
     * {@code retention_purge_log} i zwraca wygenerowany {@code purgeId} NATYCHMIAST — faktyczne
     * usuwanie odbywa się w tle (wątek puli {@code applicationTaskExecutor}). Status operacji
     * można odpytać później przez {@code purgeId} (przyszły {@code GET .../purge/{purgeId}}, BE-118).
     *
     * @param tenantId          UUID tenanta
     * @param category          kategoria danych — {@code CONTACT_INTERACTIONS}, {@code TRANSCRIPTS} lub {@code CAMPAIGN_DATA}
     * @param triggerType       MANUAL (ręczne wywołanie przez administratora) lub AUTO (scheduler)
     * @param triggeredByUserId UUID użytkownika wywołującego — wymagane dla MANUAL, {@code null} dla AUTO
     * @return UUID operacji purge — do dalszego odpytywania statusu
     * @throws UnsupportedOperationException gdy {@code category} to {@code RECORDINGS}
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy brak skonfigurowanej
     *         polityki retencji dla tenanta/kategorii (nie powinno się zdarzyć po BE-111 seedingu)
     */
    UUID purge(UUID tenantId, RetentionDataCategory category, PurgeTriggerType triggerType, UUID triggeredByUserId);

    /**
     * Faktyczne wykonanie usuwania w tle — wywoływane WYŁĄCZNIE wewnętrznie przez {@link #purge}
     * poprzez self-injected proxy (patrz {@code RetentionPurgeServiceImpl.self}), żeby adnotacja
     * {@code @Async} na implementacji została przechwycona przez Spring AOP (self-invocation przez
     * {@code this.} pomija proxy — metoda musi być częścią interfejsu, żeby wywołanie przez
     * wstrzyknięty do siebie samego bean przeszło przez właściwy proxy).
     *
     * <p><strong>Nie wywołuj tej metody bezpośrednio spoza {@link #purge}</strong> — pomija zapis
     * stanu {@code RUNNING} i propagację {@link TenantContext.Snapshot}.
     *
     * @param purgeId           UUID operacji (wygenerowane i zapisane jako RUNNING przez {@link #purge})
     * @param tenantId          UUID tenanta
     * @param category          kategoria danych
     * @param cutoffDate        granica czasowa wyznaczona przez {@link #purge} na podstawie polityki retencji
     * @param triggeredByUserId UUID użytkownika wywołującego — przekazywane jawnie (nie odczytywane
     *                          z {@code TenantContext} w wątku roboczym) do wpisu audytowego po zakończeniu
     * @param snapshot          snapshot {@code TenantContext} z wątku wywołującego {@link #purge}
     */
    void purgeAsync(UUID purgeId, UUID tenantId, RetentionDataCategory category,
                     LocalDate cutoffDate, UUID triggeredByUserId, TenantContext.Snapshot snapshot);

    // =========================================================================
    // Odczyt (BE-118 — RetentionController)
    // =========================================================================

    /**
     * Zwraca status jednej operacji purge (RUNNING/COMPLETED/FAILED) — przyszły
     * {@code GET /api/tenants/{tenantId}/retention/purge/{purgeId}} (BE-118).
     *
     * <p><strong>404 vs 403:</strong> ta metoda zakłada, że {@code tenantId} to już własny
     * tenant wywołującego (weryfikacja "{tenantId} z URL == JWT tenant" → 403 żyje w
     * {@code RetentionController}, PRZED wywołaniem tej metody). Jeśli {@code purgeId} istnieje,
     * ale należy do INNEGO tenanta niż {@code tenantId} (zgadywanie UUID), zwracamy 404
     * (nie 403) — {@link RetentionPurgeLogRepository#findById} filtruje po {@code tenant_id}
     * w SQL i po prostu nie znajduje wiersza, nieodróżnialnie od "purgeId nigdy nie istniał".
     *
     * @param tenantId UUID tenanta (już zweryfikowany jako własny przez kontroler)
     * @param purgeId  UUID operacji purge
     * @return DTO statusu operacji
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy purgeId nie
     *         istnieje lub należy do innego tenanta
     */
    PurgeResultDto getPurgeStatus(UUID tenantId, UUID purgeId);

    /**
     * Paginowana historia operacji purge tenanta, sortowana malejąco po {@code started_at} —
     * przyszły {@code GET /api/tenants/{tenantId}/retention/history} (BE-118).
     *
     * @param tenantId UUID tenanta (już zweryfikowany jako własny przez kontroler)
     * @param pageable parametry paginacji (page, size); sort ignorowany — zawsze started_at DESC
     * @return strona wyników zmapowana na {@link PurgeResultDto}
     */
    Page<PurgeResultDto> getPurgeHistory(UUID tenantId, Pageable pageable);

    /**
     * Dashboard „ile danych kwalifikuje się do usunięcia" — zwraca ZAWSZE dokładnie 4 wpisy,
     * jeden per {@link RetentionDataCategory}, z jawnym flagowaniem kategorii jeszcze
     * niepoliczonych przez {@code RetentionEvaluationJob} (BE-112) — przyszły
     * {@code GET /api/tenants/{tenantId}/retention/summary} (BE-118). Patrz Javadoc
     * {@link RetentionSummaryDto} po pełne uzasadnienie kontraktu.
     *
     * @param tenantId UUID tenanta (już zweryfikowany jako własny przez kontroler)
     * @return dokładnie 4 wpisy, po jednym per {@link RetentionDataCategory}
     */
    List<RetentionSummaryDto> getPendingSummary(UUID tenantId);
}
