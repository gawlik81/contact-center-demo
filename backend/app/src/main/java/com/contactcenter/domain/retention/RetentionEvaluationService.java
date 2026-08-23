package com.contactcenter.domain.retention;

import com.contactcenter.domain.retention.dto.RetentionSummaryDto;
import com.contactcenter.security.TenantContext;

import java.util.List;
import java.util.UUID;

/**
 * Silnik liczący, ile rekordów kwalifikuje się do usunięcia per tenant/kategoria retencji,
 * partition-aware, zapisujący wynik do cache {@code tenant_retention_pending_summary} (DB-047)
 * — wydzielony z {@code RetentionEvaluationJob} (EPIC-29, BE-112) tak, aby ta sama logika mogła
 * być wywoływana zarówno przez nocny scheduler ({@link #runForAllActiveTenants()}), jak i przez
 * REST API ({@link #runForTenant(UUID)}, ręczne przeliczenie na żądanie administratora).
 *
 * <p><strong>Zakres kategorii:</strong>
 * <ul>
 *   <li>{@code CONTACT_INTERACTIONS} ({@code contact} + {@code contact_event}) i
 *       {@code TRANSCRIPTS} ({@code contact_transcription} + {@code contact_ai_summary}) —
 *       liczone partition-aware przez {@link PartitionScanner} (algorytm poniżej).</li>
 *   <li>{@code CAMPAIGN_DATA} ({@code campaign_contact_archive}) — tabela NIE jest
 *       partycjonowana, więc {@link PartitionScanner} się do niej nie stosuje. Liczona
 *       bezpośrednim zapytaniem przez {@link CampaignArchiveRetentionRepository}.</li>
 *   <li>{@code RECORDINGS} jest CAŁKOWICIE poza zakresem tego serwisu (żadnego liczenia, żadnego
 *       wiersza w summary) — obsługiwana wyłącznie przez {@code RecordingRetentionJob} (BE-116).</li>
 * </ul>
 *
 * <p><strong>Algorytm partition-aware (CONTACT_INTERACTIONS, TRANSCRIPTS):</strong>
 * <ol>
 *   <li>Dla każdej tabeli kategorii: {@link PartitionScanner#listPartitions} — partycje
 *       posortowane rosnąco, {@code <tabela>_default} pomijana.</li>
 *   <li>Wyznacz globalny próg zatrzymania: {@code globalCutoffDate = now(UTC) - MIN(retentionMonths)}
 *       po WSZYSTKICH tenantach dla tej kategorii ({@link RetentionPolicyService#findMinRetentionMonths}).
 *       Iteruj partycje od najstarszej; PRZERWIJ gdy {@code partition.rangeEnd() > globalCutoffDate}
 *       — taka partycja jest na pewno za młoda dla KAŻDEGO tenanta (nawet tego z najkrótszą
 *       retencją), więc dalsze (nowsze) partycje też będą za młode. To jest kryterium
 *       "job NIE skanuje całej tabeli".</li>
 *   <li>Dla każdej partycji PRZED progiem: {@link PartitionScanner#countRowsByTenant} — zwraca
 *       wiersze dla WSZYSTKICH tenantów obecnych w tej partycji, niezależnie od tego, który
 *       konkretny tenant finalnie zostanie z tego wyniku zapisany do summary (to filtruje dopiero
 *       krok persystencji, patrz niżej — {@link #runForTenant(UUID)} skanuje te same partycje co
 *       {@link #runForAllActiveTenants()}, ale persystuje wynik WYŁĄCZNIE dla jednego tenanta).</li>
 *   <li>Dla każdego {@code (tenantId, rowCount)}: policz {@code tenantCutoffDate = now(UTC) -
 *       retentionMonths(tenantId, category)} (tenant może mieć DŁUŻSZĄ retencję niż globalne
 *       minimum). Partycja liczy się dla TEGO tenanta tylko gdy
 *       {@code partition.rangeEnd() <= tenantCutoffDate} (nie {@code <}, żeby uniknąć
 *       off-by-one na granicy miesiąca).</li>
 *   <li>Sumuj per {@code (tenantId, category)} po obu tabelach kategorii, zapisz
 *       {@code oldestEligiblePeriod}/{@code newestEligiblePeriod} = MIN/MAX
 *       {@code partition.rangeStart()}.</li>
 *   <li>Upsert do {@code tenant_retention_pending_summary} — tenant nieobecny w wyniku skanowania
 *       dostaje {@code eligibleRowCount=0} (reset do zera, żeby cache nie pokazywał nieaktualnych
 *       wartości po ręcznym purge przez administratora).</li>
 *   <li>Jeśli {@code eligibleRowCount > 0} ORAZ polityka tenanta ma {@code autoPurgeEnabled=true}
 *       ORAZ wywołujący zażyczył sobie auto-purge dla tego przebiegu (patrz różnica między dwiema
 *       metodami tego interfejsu, niżej) — wywołaj {@code retentionPurgeService.purge(tenantId,
 *       category, PurgeTriggerType.AUTO, null)} od razu.</li>
 * </ol>
 *
 * <p><strong>Odporność na błędy:</strong> błąd przy jednym tenancie lub jednej kategorii NIE
 * przerywa przetwarzania pozostałych (log ERROR + kontynuacja). Brak jakiejkolwiek polityki dla
 * kategorii ({@link RetentionPolicyService#findMinRetentionMonths} rzuca
 * {@code ResourceNotFoundException}) powoduje pominięcie TEJ kategorii w danym przebiegu
 * (log WARN), nie przerywa reszty.
 *
 * <h2>{@link TenantContext} — kontrakt RÓŻNY dla dwóch metod tego interfejsu</h2>
 *
 * <p><strong>{@link #runForAllActiveTenants()}</strong> — wołane wyłącznie przez
 * {@code RetentionEvaluationJob} (wątek {@code @Scheduled}, BEZ kontekstu HTTP). Ta metoda SAMA
 * zarządza {@link TenantContext} wewnętrznie: {@code TenantContext.setTenantId(tenantId)} na
 * początku KAŻDEJ iteracji per-tenant, {@code TenantContext.clear()} w {@code finally}
 * obejmującym całą iterację — wymagane, bo {@code assertSameTenant} w repozytoriach zapisu
 * bezwarunkowo czyta {@code TenantContext} z ThreadLocal, a wątek schedulera nigdy nie przechodzi
 * przez {@code TenantFilter}. Auto-purge WŁĄCZONY (jeśli polityka na to pozwala).
 *
 * <p><strong>{@link #runForTenant(UUID)}</strong> — wołane przez {@code RetentionController}
 * (wątek HTTP, {@link TenantContext} JUŻ poprawnie ustawiony przez {@code TenantFilter} z JWT,
 * zweryfikowany przez {@code assertOwnTenant} w kontrolerze PRZED wywołaniem tej metody).
 * <strong>PREKONTRAKT: {@link TenantContext} MUSI być już ustawiony na {@code tenantId} PRZEZ
 * WYWOŁUJĄCEGO.</strong> Ta metoda ANI kod, który wywołuje wewnętrznie, NIGDY nie woła
 * {@code TenantContext.clear()} — wyczyszczenie kontekstu w trakcie obsługi żądania HTTP
 * wyciekłoby do reszty łańcucha filtrów/przetwarzania TEGO SAMEGO żądania (np. logowanie/audyt
 * dalej w łańcuchu), co byłoby nowym bugiem tej samej klasy co ten naprawiony w
 * {@link #runForAllActiveTenants()} (patrz notatka BE-112/BE-118 w {@code TASKS-BACKEND.md}),
 * tylko w przeciwnym kierunku. Auto-purge ZAWSZE WYŁĄCZONY, niezależnie od
 * {@code auto_purge_enabled} jakiejkolwiek polityki — to jest bezpieczna, pozbawiona efektów
 * ubocznych w sensie usuwania danych, akcja administratora klikającego "odśwież liczby"; auto-purge
 * pozostaje WYŁĄCZNIE odpowiedzialnością {@link #runForAllActiveTenants()}.
 */
public interface RetentionEvaluationService {

    /**
     * Liczy dane do usunięcia dla WSZYSTKICH aktywnych tenantów ({@code TenantService#getActiveTenants()})
     * i WSZYSTKICH kategorii w zakresie ({@code CONTACT_INTERACTIONS}, {@code TRANSCRIPTS},
     * {@code CAMPAIGN_DATA}) — wołane wyłącznie przez {@code RetentionEvaluationJob}
     * ({@code @Scheduled}, domyślnie 01:00 UTC). Auto-purge WŁĄCZONY (wywoływany od razu po
     * policzeniu, dla polityk z {@code auto_purge_enabled=true}).
     *
     * <p>Zarządza {@link TenantContext} samodzielnie (ustawia i czyści per iteracja) — patrz
     * Javadoc klasy, sekcja "TenantContext".
     */
    void runForAllActiveTenants();

    /**
     * Liczy dane do usunięcia dla JEDNEGO tenanta i WSZYSTKICH kategorii w zakresie — ręczne
     * przeliczenie dashboardu "dane do usunięcia" na żądanie administratora
     * ({@code POST /api/tenants/{tenantId}/retention/recompute}, rozszerzenie BE-112/BE-118).
     * Auto-purge ZAWSZE WYŁĄCZONY — patrz Javadoc klasy.
     *
     * <p><strong>PREKONTRAKT:</strong> {@link TenantContext} musi być już ustawiony na
     * {@code tenantId} PRZEZ WYWOŁUJĄCEGO (w praktyce: wątek HTTP po przejściu przez
     * {@code TenantFilter} + weryfikacji {@code assertOwnTenant} w kontrolerze). Ta metoda NIGDY
     * nie woła {@code TenantContext.setTenantId}/{@code clear()} — patrz Javadoc klasy.
     *
     * @param tenantId UUID tenanta, dla którego przeliczyć dashboard (już zweryfikowany jako
     *                 własny tenant wywołującego przez kontroler)
     * @return dokładnie 4 wpisy (jeden per {@link RetentionDataCategory}), świeżo przeliczone —
     *         identyczny kontrakt co {@code RetentionPurgeService#getPendingSummary(UUID)}
     *         ({@code GET .../retention/summary}), bo ta metoda faktycznie deleguje do niego po
     *         zapisaniu świeżych wartości do cache
     */
    List<RetentionSummaryDto> runForTenant(UUID tenantId);
}
