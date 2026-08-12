package com.contactcenter.domain.retention;

import com.contactcenter.security.TenantContext;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Silnik usuwania Poziom 1 (per-tenant, batchowany) dla kategorii {@code CONTACT_INTERACTIONS}
 * i {@code TRANSCRIPTS} (EPIC-29, BE-113).
 *
 * <p>Usuwanie odbywa się na poziomie wiersza, per tenant, niezależnie od innych tenantów
 * współdzielących tę samą partycję miesięczną — patrz uzasadnienie techniczne w
 * {@link RetentionPurgeServiceImpl}.
 *
 * <p><strong>Poza zakresem tego serwisu:</strong>
 * <ul>
 *   <li>{@code RECORDINGS} — obsługiwane przez {@code RecordingRetentionJob} (BE-116):
 *       zerowanie kolumny {@code recording_url} + usunięcie obiektu S3, nie DELETE wiersza {@code contact}.</li>
 *   <li>{@code CAMPAIGN_DATA} — przyszła integracja z funkcją SQL
 *       {@code purge_campaign_contact_archive} (BE-119).</li>
 * </ul>
 * Wywołanie {@link #purge} dla tych dwóch kategorii rzuca {@link UnsupportedOperationException}.
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
     * @param category          kategoria danych — wyłącznie {@code CONTACT_INTERACTIONS} lub {@code TRANSCRIPTS}
     * @param triggerType       MANUAL (ręczne wywołanie przez administratora) lub AUTO (scheduler)
     * @param triggeredByUserId UUID użytkownika wywołującego — wymagane dla MANUAL, {@code null} dla AUTO
     * @return UUID operacji purge — do dalszego odpytywania statusu
     * @throws UnsupportedOperationException gdy {@code category} to {@code RECORDINGS} lub {@code CAMPAIGN_DATA}
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
}
