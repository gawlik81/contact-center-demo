package com.contactcenter.domain.retention;

import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.retention.dto.RetentionSummaryDto;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementacja {@link RetentionEvaluationService} — wydzielona z pierwotnego
 * {@code RetentionEvaluationJob} (EPIC-29, BE-112), żeby ta sama logika mogła być wołana zarówno
 * przez nocny scheduler ({@code RetentionEvaluationJob}, cienki wrapper), jak i przez
 * {@code RetentionController} (ręczne przeliczenie, rozszerzenie BE-112/BE-118).
 *
 * <p>Patrz Javadoc {@link RetentionEvaluationService} po pełny opis algorytmu partition-aware
 * i kontraktu {@link TenantContext} dla obu ścieżek wejścia.
 *
 * <h2>{@link TenantContext} ThreadLocal — dwie ścieżki, DWA różne wymagania (KLUCZOWE)</h2>
 *
 * <p>Wewnętrzna struktura tej klasy celowo rozdziela "skanowanie" (budowa mapy akumulatorów per
 * tenant przez iterację partycji — {@code scanPartitionAwareCategory}) od "persystencji"
 * (zapis do {@code tenant_retention_pending_summary} + opcjonalny auto-purge — dwie odmiany:
 * {@link #persistAndMaybeAutoPurge} dla wielu tenantów, {@link #persistSummaryAndMaybeAutoPurgeForTenant}
 * jako bezstanowy w sensie kontekstu rdzeń dla jednego tenanta). Skanowanie NIE dotyka
 * {@link TenantContext} w ogóle (zapytania do {@link PartitionScanner} są jawnie cross-tenant,
 * nie wymagają kontekstu). Zarządzanie {@link TenantContext} ({@code setTenantId}/{@code clear()})
 * żyje WYŁĄCZNIE w {@link #persistAndMaybeAutoPurge} i {@link #evaluateCampaignData} — pętlach
 * używanych TYLKO przez {@link #runForAllActiveTenants()} (ścieżka schedulera, wątek bez
 * kontekstu HTTP). {@link #runForTenant(UUID)} woła bezpośrednio bezkontekstowe warianty
 * per-tenant ({@link #persistSummaryAndMaybeAutoPurgeForTenant}, {@link #evaluateCampaignDataForTenant})
 * — zakładają, że {@link TenantContext} jest JUŻ poprawnie ustawiony przez wywołującego (wątek
 * HTTP), i NIGDY nie wołają {@code TenantContext.clear()} — wyczyszczenie kontekstu w trakcie
 * obsługi żądania HTTP wyciekłoby do reszty łańcucha przetwarzania tego żądania (dokładnie ten
 * sam bug co naprawiony w scheduler-owej ścieżce, patrz notatka BE-112 w {@code TASKS-BACKEND.md}
 * o naprawie {@code TenantContext} w wątku schedulera, tylko w przeciwnym kierunku).
 *
 * <h2>BE-128 (EPIC-30) — wiadomości w {@code eligibleRowCount} kategorii CONTACT_INTERACTIONS</h2>
 *
 * <p>{@link #countEligibleMessages(UUID)}, wołane WYŁĄCZNIE z {@link #persistSummaryAndMaybeAutoPurgeForTenant}
 * dla kategorii CONTACT_INTERACTIONS, dolicza wiadomości e-mail kwalifikujące się do usunięcia
 * (osierocone + powiązane z kontaktem, który sam się kwalifikuje) do liczby liczonej przez
 * {@link #scanPartitionAwareCategory} dla {@code contact}/{@code contact_event}/{@code social_message}.
 * Ten dodatkowy krok respektuje TEN SAM prekontrakt {@link TenantContext} co reszta metody — patrz
 * Javadoc {@link #countEligibleMessages(UUID)}.
 *
 * <p><strong>BE-133 (EPIC-30, 2026-10-01):</strong> do 2026-10-01 {@code countEligibleMessages}
 * doliczał TAKŻE wiadomości {@code social_message} (osierocone + powiązane, JOIN/IN-subquery).
 * Po przekonwertowaniu {@code social_message} na tabelę partycjonowaną (DB-065, V100) ten
 * składnik przeniesiony do {@link #scanPartitionAwareCategory} (wpis w {@link #PARTITION_AWARE_TABLES})
 * — pozostawienie OBU ścieżek jednocześnie podwajałoby liczbę wierszy {@code social_message} w
 * {@code eligibleRowCount}. {@code email_message} pozostaje na starej ścieżce (JOIN/IN-subquery) do
 * czasu BE-135 (po DB-067/BE-134).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class RetentionEvaluationServiceImpl implements RetentionEvaluationService {

    /**
     * Kategorie partition-aware → lista tabel wchodzących w skład kategorii.
     *
     * <p><strong>BE-133 (EPIC-30, 2026-10-01):</strong> {@code social_message} dołączona do
     * {@code CONTACT_INTERACTIONS} po przekonwertowaniu na tabelę partycjonowaną (DB-065, V100) —
     * jej wiersze w {@code eligibleRowCount} tej kategorii liczy odtąd WYŁĄCZNIE
     * {@link #scanPartitionAwareCategory} (partycyjnie, tak jak {@code contact}/{@code contact_event}),
     * NIE {@link #countEligibleMessages} (JOIN/IN-subquery) — patrz jego javadoc po pełne
     * uzasadnienie zmiany semantyki z „dokładne" na „konserwatywne przybliżenie granicą partycji".
     */
    private static final Map<RetentionDataCategory, List<String>> PARTITION_AWARE_TABLES = Map.of(
            RetentionDataCategory.CONTACT_INTERACTIONS, List.of("contact", "contact_event", "social_message"),
            RetentionDataCategory.TRANSCRIPTS, List.of("contact_transcription", "contact_ai_summary")
    );

    private final PartitionScanner partitionScanner;
    private final RetentionPolicyService retentionPolicyService;
    private final RetentionPurgeService retentionPurgeService;
    private final TenantService tenantService;
    private final TenantRetentionPendingSummaryRepository summaryRepository;
    private final CampaignArchiveRetentionRepository campaignArchiveRetentionRepository;
    private final EmailMessageService emailMessageService;

    /**
     * Ten sam bezpiecznik wdrożeniowy co {@code RetentionPurgeServiceImpl#deleteMessagesEnabled}
     * (ta sama nazwa właściwości — jedna flaga steruje obiema klasami). Doliczenie wiadomości do
     * {@code eligibleRowCount} (BE-128) nie może wpływać na DECYZJĘ auto-purge, gdy flaga jest
     * {@code false} — {@code purgeContactInteractionsLegacy} (ścieżka domyślna) nic z tych
     * wiadomości nie usuwa, więc odpalanie auto-purge WYŁĄCZNIE z ich powodu byłoby pustym,
     * powtarzającym się co noc przebiegiem (nowy wiersz {@code retention_purge_log}/audytu bez
     * żadnego efektu — CR-BACKEND.md BE128-01). Dashboard/API (pole zapisywane przez
     * {@code summaryRepository.upsert}) NADAL pokazuje pełną liczbę niezależnie od tej flagi —
     * dotyczy wyłącznie decyzji {@code maybeTriggerAutoPurge}.
     */
    @Value("${retention.purge.delete-messages:false}")
    private boolean deleteMessagesEnabled;

    // =========================================================================
    // Ścieżka 1: scheduler — WSZYSCY aktywni tenanci, auto-purge WŁĄCZONY
    // =========================================================================

    @Override
    public void runForAllActiveTenants() {
        log.info("[RetentionEvaluationService] Start liczenia danych do usunięcia (partition-aware, wszyscy tenanci).");

        List<Tenant> activeTenants = tenantService.getActiveTenants();
        log.info("[RetentionEvaluationService] Aktywnych tenantów: {}", activeTenants.size());

        int categoriesProcessed = 0;
        int categoriesSkipped = 0;

        for (Map.Entry<RetentionDataCategory, List<String>> entry : PARTITION_AWARE_TABLES.entrySet()) {
            RetentionDataCategory category = entry.getKey();
            List<String> tableNames = entry.getValue();
            try {
                evaluatePartitionAwareCategory(category, tableNames, activeTenants, true);
                categoriesProcessed++;
            } catch (Exception e) {
                log.error("[RetentionEvaluationService] Błąd liczenia kategorii {}: {}", category, e.getMessage(), e);
                categoriesSkipped++;
            }
        }

        try {
            evaluateCampaignData(activeTenants, true);
            categoriesProcessed++;
        } catch (Exception e) {
            log.error("[RetentionEvaluationService] Błąd liczenia kategorii {}: {}",
                    RetentionDataCategory.CAMPAIGN_DATA, e.getMessage(), e);
            categoriesSkipped++;
        }

        log.info("[RetentionEvaluationService] Zakończono (wszyscy tenanci). Kategorie przetworzone={}, pominięte={}",
                categoriesProcessed, categoriesSkipped);
    }

    // =========================================================================
    // Ścieżka 2: REST (ręczne przeliczenie) — JEDEN tenant, auto-purge WYŁĄCZONY
    // =========================================================================

    @Override
    public List<RetentionSummaryDto> runForTenant(UUID tenantId) {
        log.info("[RetentionEvaluationService] Start ręcznego przeliczenia danych do usunięcia "
                + "(partition-aware, bez auto-purge): tenant={}", tenantId);

        int categoriesProcessed = 0;
        int categoriesSkipped = 0;

        for (Map.Entry<RetentionDataCategory, List<String>> entry : PARTITION_AWARE_TABLES.entrySet()) {
            RetentionDataCategory category = entry.getKey();
            List<String> tableNames = entry.getValue();
            try {
                evaluatePartitionAwareCategoryForTenant(category, tableNames, tenantId);
                categoriesProcessed++;
            } catch (Exception e) {
                log.error("[RetentionEvaluationService] Błąd ręcznego przeliczenia kategorii {} dla tenanta={}: {}",
                        category, tenantId, e.getMessage(), e);
                categoriesSkipped++;
            }
        }

        try {
            evaluateCampaignDataForTenant(tenantId, false);
            categoriesProcessed++;
        } catch (Exception e) {
            log.error("[RetentionEvaluationService] Błąd ręcznego przeliczenia kategorii {} dla tenanta={}: {}",
                    RetentionDataCategory.CAMPAIGN_DATA, tenantId, e.getMessage(), e);
            categoriesSkipped++;
        }

        log.info("[RetentionEvaluationService] Zakończono ręczne przeliczenie: tenant={}, kategorie przetworzone={}, pominięte={}",
                tenantId, categoriesProcessed, categoriesSkipped);

        // Świeżo zapisane wartości odczytane tą samą ścieżką co GET .../summary — syntetyzuje
        // DOKŁADNIE 4 wpisy (w tym RECORDINGS, computed=false, bo ta kategoria jest poza zakresem
        // obu ścieżek ewaluacji) — patrz Javadoc RetentionEvaluationService#runForTenant.
        return retentionPurgeService.getPendingSummary(tenantId);
    }

    // =========================================================================
    // CONTACT_INTERACTIONS / TRANSCRIPTS — partition-aware: skanowanie (bez TenantContext)
    // =========================================================================

    /**
     * Skanuje partycje kategorii partition-aware i akumuluje eligible row count per tenant, dla
     * WSZYSTKICH tenantów obecnych w zeskanowanych partycjach — niezależnie od tego, który
     * konkretny tenant finalnie zostanie z tego wyniku zapisany do summary (to filtruje dopiero
     * krok persystencji wywołany przez {@link #evaluatePartitionAwareCategory}/
     * {@link #evaluatePartitionAwareCategoryForTenant}). Zapytania do {@link PartitionScanner} są
     * jawnie cross-tenant (rola DB ma {@code BYPASSRLS}), więc ta metoda NIE dotyka
     * {@link TenantContext} w ogóle.
     *
     * @return {@link Optional#empty()} gdy brak jakiejkolwiek polityki retencji dla kategorii
     *         (żaden tenant) — wywołujący MUSI pominąć kategorię (log WARN już wykonany tutaj)
     */
    private Optional<Map<UUID, TenantAccumulator>> scanPartitionAwareCategory(
            RetentionDataCategory category, List<String> tableNames) {
        int minRetentionMonths;
        try {
            minRetentionMonths = retentionPolicyService.findMinRetentionMonths(category);
        } catch (ResourceNotFoundException e) {
            log.warn("[RetentionEvaluationService] Brak jakiejkolwiek polityki retencji dla kategorii {} "
                    + "— pomijam tę kategorię w tym przebiegu: {}", category, e.getMessage());
            return Optional.empty();
        }

        LocalDate globalCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(minRetentionMonths);
        log.debug("[RetentionEvaluationService] Kategoria={}, minRetentionMonths={}, globalCutoffDate={}",
                category, minRetentionMonths, globalCutoffDate);

        Map<UUID, TenantAccumulator> accumulators = new HashMap<>();

        for (String tableName : tableNames) {
            List<PartitionScanner.PartitionInfo> partitions = partitionScanner.listPartitions(tableName);

            for (PartitionScanner.PartitionInfo partition : partitions) {
                if (partition.rangeEnd().isAfter(globalCutoffDate)) {
                    // Partycja (i wszystkie nowsze po niej, dzięki sortowaniu rosnącemu) na pewno
                    // za młoda dla KAŻDEGO tenanta — nie ma sensu jej skanować. To jest właśnie
                    // kryterium "nie skanujemy całej tabeli".
                    log.debug("[RetentionEvaluationService] Tabela={}, zatrzymuję skanowanie na partycji={} "
                                    + "(rangeEnd={} > globalCutoffDate={})",
                            tableName, partition.partitionName(), partition.rangeEnd(), globalCutoffDate);
                    break;
                }

                List<PartitionScanner.TenantRowCount> rowCounts =
                        partitionScanner.countRowsByTenant(partition.partitionName());

                for (PartitionScanner.TenantRowCount rowCount : rowCounts) {
                    try {
                        accumulateIfEligibleForTenant(category, partition, rowCount, accumulators);
                    } catch (Exception e) {
                        log.error("[RetentionEvaluationService] Błąd przetwarzania tenanta={} w kategorii={}, partycji={}: {}",
                                rowCount.tenantId(), category, partition.partitionName(), e.getMessage(), e);
                    }
                }
            }
        }

        return Optional.of(accumulators);
    }

    /**
     * Sprawdza, czy partycja kwalifikuje się do usunięcia dla KONKRETNEGO tenanta (który może
     * mieć dłuższą retencję niż globalne minimum wyznaczone w {@link #scanPartitionAwareCategory})
     * i jeśli tak — dodaje jej liczbę wierszy do akumulatora tego tenanta.
     */
    private void accumulateIfEligibleForTenant(RetentionDataCategory category, PartitionScanner.PartitionInfo partition,
                                                PartitionScanner.TenantRowCount rowCount,
                                                Map<UUID, TenantAccumulator> accumulators) {
        int tenantRetentionMonths = retentionPolicyService.getRetentionMonths(rowCount.tenantId(), category);
        LocalDate tenantCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(tenantRetentionMonths);

        // rangeEnd <= tenantCutoffDate (NIE <) — test granicy miesiąca: każdy wiersz partycji ma
        // znacznik czasu < rangeEnd, więc rangeEnd <= tenantCutoffDate gwarantuje że KAŻDY wiersz
        // faktycznie kwalifikuje się wg "< cutoff", tej samej definicji co RetentionPurgeServiceImpl.
        if (!partition.rangeEnd().isAfter(tenantCutoffDate)) {
            accumulators.computeIfAbsent(rowCount.tenantId(), id -> new TenantAccumulator())
                    .add(rowCount.rowCount(), partition.rangeStart());
        }
    }

    // =========================================================================
    // CONTACT_INTERACTIONS / TRANSCRIPTS — persystencja (dwie odmiany, patrz Javadoc klasy)
    // =========================================================================

    private void evaluatePartitionAwareCategory(RetentionDataCategory category, List<String> tableNames,
                                                  List<Tenant> activeTenants, boolean triggerAutoPurge) {
        scanPartitionAwareCategory(category, tableNames)
                .ifPresent(accumulators -> persistAndMaybeAutoPurge(category, activeTenants, accumulators, triggerAutoPurge));
    }

    private void evaluatePartitionAwareCategoryForTenant(RetentionDataCategory category, List<String> tableNames,
                                                          UUID tenantId) {
        scanPartitionAwareCategory(category, tableNames)
                .ifPresent(accumulators ->
                        persistSummaryAndMaybeAutoPurgeForTenant(category, tenantId, accumulators, false));
    }

    /**
     * Zapisuje wynik do {@code tenant_retention_pending_summary} dla KAŻDEGO tenanta z listy
     * (reset do zera dla tych nieobecnych w {@code accumulators}) i wyzwala auto-purge tam, gdzie
     * {@code triggerAutoPurge} i polityka na to pozwalają. Błąd jednego tenanta nie przerywa
     * pozostałych.
     *
     * <p>Wołane WYŁĄCZNIE przez {@link #runForAllActiveTenants()} (ścieżka schedulera). Sama
     * zarządza {@link TenantContext}: ustawia na początku KAŻDEJ iteracji, czyści w
     * {@code finally}. Ustawienie kontekstu PRZED wywołaniem {@link #maybeTriggerAutoPurge}
     * (przez {@link #persistSummaryAndMaybeAutoPurgeForTenant}) jest kluczowe: {@code
     * RetentionPurgeService#purge} woła {@code TenantContext.snapshot()} do propagacji kontekstu
     * do {@code @Async purgeAsync} — bez jawnego ustawienia tutaj snapshot byłby pusty.
     */
    private void persistAndMaybeAutoPurge(RetentionDataCategory category, List<Tenant> activeTenants,
                                           Map<UUID, TenantAccumulator> accumulators, boolean triggerAutoPurge) {
        for (Tenant tenant : activeTenants) {
            UUID tenantId = tenant.getId();
            try {
                // Wątek schedulera nie ma TenantContext (brak JWT/TenantFilter) – ustawiamy
                // jawnie dla tej iteracji, wymagane przez assertSameTenant w summaryRepository.upsert
                // oraz (pośrednio, przez maybeTriggerAutoPurge) w RetentionPurgeLogRepository.insertRunning.
                TenantContext.setTenantId(tenantId);

                persistSummaryAndMaybeAutoPurgeForTenant(category, tenantId, accumulators, triggerAutoPurge);
            } catch (Exception e) {
                log.error("[RetentionEvaluationService] Błąd zapisu summary/auto-purge dla tenanta={}, kategoria={}: {}",
                        tenantId, category, e.getMessage(), e);
            } finally {
                // Wyczyść kontekst po każdej iteracji – wątek puli schedulera jest reużywany
                // między tenantami w obrębie tej pętli oraz między kolejnymi przebiegami jobu.
                TenantContext.clear();
            }
        }
    }

    /**
     * Rdzeń zapisu summary + opcjonalnego auto-purge DLA JEDNEGO TENANTA, WSPÓLNY dla obu ścieżek
     * wejścia. <strong>PREKONTRAKT: {@link TenantContext} musi być już poprawnie ustawiony na
     * {@code tenantId} PRZEZ WYWOŁUJĄCEGO</strong> — ta metoda CELOWO nie ustawia ani nie czyści
     * kontekstu, żeby {@link #runForTenant(UUID)} (wątek HTTP, kontekst już poprawny z żądania)
     * mógł ją wywołać bezpośrednio bez ryzyka wyczyszczenia kontekstu w trakcie obsługi żądania.
     * Wołana z dwóch miejsc:
     * <ul>
     *   <li>{@link #persistAndMaybeAutoPurge} — pętla scheduler-a, TA metoda zarządza kontekstem
     *       WOKÓŁ wywołania tego rdzenia.</li>
     *   <li>{@link #evaluatePartitionAwareCategoryForTenant} — wywołanie bezpośrednie, kontekst
     *       już ustawiony przez wątek HTTP (kontroler), {@code triggerAutoPurge=false} zawsze.</li>
     * </ul>
     */
    private void persistSummaryAndMaybeAutoPurgeForTenant(RetentionDataCategory category, UUID tenantId,
                                                            Map<UUID, TenantAccumulator> accumulators,
                                                            boolean triggerAutoPurge) {
        TenantAccumulator acc = accumulators.get(tenantId);
        long eligibleRowCount = acc != null ? acc.eligibleRowCount : 0L;
        LocalDate oldest = acc != null ? acc.oldestPeriod : null;
        LocalDate newest = acc != null ? acc.newestPeriod : null;

        // BE-128: dla CONTACT_INTERACTIONS dolicz wiadomości (patrz Javadoc countEligibleMessages).
        // Prekontrakt TenantContext identyczny jak dla summaryRepository.upsert poniżej — wołane PO
        // TenantContext.setTenantId w persistAndMaybeAutoPurge (scheduler) albo z już poprawnym
        // kontekstem wątku HTTP (runForTenant), nigdy przed.
        //
        // Błąd liczenia wiadomości NIE może zablokować zapisu już poprawnie policzonego
        // eligibleRowCount kontaktów/zdarzeń (ten sam duch izolacji błędów co reszta tej klasy —
        // patrz np. ErrorIsolation w RetentionEvaluationServiceImplTest) — w najgorszym razie
        // eligibleRowCount jest NIEDOSZACOWANY o wiadomości tego przebiegu, nie utracony całkowicie.
        long messageCount = 0L;
        if (category == RetentionDataCategory.CONTACT_INTERACTIONS) {
            try {
                messageCount = countEligibleMessages(tenantId);
                eligibleRowCount += messageCount;
            } catch (Exception e) {
                log.error("[RetentionEvaluationService] Błąd liczenia wiadomości (BE-128) dla tenanta={}: {}",
                        tenantId, e.getMessage(), e);
            }
        }

        summaryRepository.upsert(tenantId, category, eligibleRowCount, oldest, newest);

        if (triggerAutoPurge) {
            // CR-BACKEND.md BE128-01: gdy deleteMessagesEnabled=false (domyślnie), legacy purge nie
            // usuwa wiadomości — nie odpalaj auto-purge WYŁĄCZNIE z ich powodu (pusty, powtarzający
            // się co noc przebieg bez żadnego efektu). Dashboard (upsert powyżej) nadal pokazuje
            // pełną liczbę niezależnie od tej flagi.
            long triggerRowCount = (category == RetentionDataCategory.CONTACT_INTERACTIONS && !deleteMessagesEnabled)
                    ? eligibleRowCount - messageCount
                    : eligibleRowCount;
            maybeTriggerAutoPurge(tenantId, category, triggerRowCount);
        }
    }

    // =========================================================================
    // BE-128: CONTACT_INTERACTIONS — dolicz wiadomości kwalifikujące się (EPIC-30)
    // =========================================================================

    /**
     * Dolicza do {@code eligibleRowCount} kategorii CONTACT_INTERACTIONS wiadomości e-mail
     * kwalifikujące się do usunięcia (BE-128, dashboard FE-105/badge FE-108): (a) OSIEROCONE
     * ({@link EmailMessageService#countOrphansOlderThan}, reużycie BE-127) i (b) POWIĄZANE z
     * kontaktem, który sam kwalifikuje się do usunięcia ({@link EmailMessageService#countLinkedToContactsOlderThan},
     * nowość BE-128). OBA składniki są DOKŁADNE (nie oszacowanie) — patrz Javadoc
     * {@link RetentionSummaryDto} po pełne uzasadnienie semantyki i kosztu, oraz notatka wykonania
     * BE-128 w {@code TASKS-BACKEND.md} po dowód EXPLAIN ANALYZE (scratch DB, symulacja wielotenantowa).
     *
     * <p><strong>BE-133 (EPIC-30, 2026-10-01) — {@code social_message} NIE jest już liczona tutaj:</strong>
     * do 2026-10-01 ta metoda doliczała też {@code socialMessageService.countOrphansOlderThan}/
     * {@code countLinkedToContactsOlderThan}. Po przekonwertowaniu {@code social_message} na tabelę
     * partycjonowaną (DB-065, V100) ten składnik przeniesiony do {@link #scanPartitionAwareCategory}
     * (wpis {@code "social_message"} w {@link #PARTITION_AWARE_TABLES}) — zostawienie obu ścieżek
     * jednocześnie podwajałoby liczbę wierszy {@code social_message}. Konsekwencja zmiany semantyki:
     * udział {@code social_message} w sumie {@code eligibleRowCount} przestaje być DOKŁADNY (wiersz
     * po wierszu) i staje się KONSERWATYWNYM PRZYBLIŻENIEM granicą całej partycji miesięcznej —
     * dokładnie tym samym trade-offem, jaki {@link RetentionSummaryDto} (BE128-02) już opisuje dla
     * {@code contact}/{@code contact_event}. {@code email_message} zostaje na tej (dokładnej) ścieżce
     * do czasu BE-135 (po DB-067/BE-134 — klucz złożony analogiczny do BE-132/{@code social_message}).
     *
     * <p>Cutoff liczony NIEZALEŻNIE od akumulatora partition-scan tej kategorii — DOKŁADNIE ten sam
     * wzorzec co {@link #evaluateCampaignDataForTenant} ({@link RetentionPolicyService#getRetentionMonths}
     * jest odczytem polityki, nie skanem partycji, więc powtórne wywołanie jest tanie i nie
     * duplikuje kosztownej pracy {@link #scanPartitionAwareCategory}).
     *
     * <p><strong>Prekontrakt:</strong> {@link TenantContext} musi być już ustawiony na
     * {@code tenantId} PRZEZ WYWOŁUJĄCEGO ({@link #persistSummaryAndMaybeAutoPurgeForTenant}) — ta
     * metoda go NIE ustawia/czyści. {@code EmailMessageService} woła {@code assertSameTenant}
     * wewnątrz (regresja BE-112: pusty kontekst → {@code IllegalStateException}), identycznie jak
     * {@code summaryRepository.upsert}.
     */
    private long countEligibleMessages(UUID tenantId) {
        int retentionMonths = retentionPolicyService.getRetentionMonths(
                tenantId, RetentionDataCategory.CONTACT_INTERACTIONS);
        LocalDate cutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(retentionMonths);
        Instant cutoff = cutoffDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        long orphanEmails = emailMessageService.countOrphansOlderThan(tenantId, cutoff);
        long linkedEmails = emailMessageService.countLinkedToContactsOlderThan(tenantId, cutoff);
        long total = orphanEmails + linkedEmails;

        log.debug("[RetentionEvaluationService] Wiadomości e-mail kwalifikujące się (CONTACT_INTERACTIONS): "
                        + "tenant={}, cutoff={}, orphanEmails={}, linkedEmails={}, total={} "
                        + "(social_message liczona partycyjnie przez scanPartitionAwareCategory od BE-133)",
                tenantId, cutoff, orphanEmails, linkedEmails, total);
        return total;
    }

    // =========================================================================
    // CAMPAIGN_DATA — bezpośrednie zapytanie (tabela NIE partycjonowana)
    // =========================================================================

    /**
     * Wołane WYŁĄCZNIE przez {@link #runForAllActiveTenants()} (ścieżka schedulera). Sama
     * zarządza {@link TenantContext} — patrz Javadoc {@link #persistAndMaybeAutoPurge} (ten sam
     * wzorzec).
     */
    private void evaluateCampaignData(List<Tenant> activeTenants, boolean triggerAutoPurge) {
        for (Tenant tenant : activeTenants) {
            UUID tenantId = tenant.getId();
            try {
                // Wątek schedulera nie ma TenantContext (brak JWT/TenantFilter) – ustawiamy
                // jawnie dla tej iteracji, patrz Javadoc RetentionEvaluationService, sekcja
                // "TenantContext ThreadLocal".
                TenantContext.setTenantId(tenantId);

                evaluateCampaignDataForTenant(tenantId, triggerAutoPurge);
            } catch (Exception e) {
                log.error("[RetentionEvaluationService] Błąd liczenia CAMPAIGN_DATA dla tenanta={}: {}",
                        tenantId, e.getMessage(), e);
            } finally {
                // Wyczyść kontekst po każdej iteracji – wątek puli schedulera jest reużywany
                // między tenantami w obrębie tej pętli oraz między kolejnymi przebiegami jobu.
                TenantContext.clear();
            }
        }
    }

    /**
     * Rdzeń liczenia CAMPAIGN_DATA DLA JEDNEGO TENANTA, WSPÓLNY dla obu ścieżek wejścia.
     * <strong>PREKONTRAKT: {@link TenantContext} musi być już poprawnie ustawiony na
     * {@code tenantId} PRZEZ WYWOŁUJĄCEGO</strong> — patrz Javadoc
     * {@link #persistSummaryAndMaybeAutoPurgeForTenant} (ten sam wzorzec/uzasadnienie).
     */
    private void evaluateCampaignDataForTenant(UUID tenantId, boolean triggerAutoPurge) {
        int retentionMonths = retentionPolicyService.getRetentionMonths(
                tenantId, RetentionDataCategory.CAMPAIGN_DATA);
        LocalDate cutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(retentionMonths);
        Instant cutoffInstant = cutoffDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        CampaignArchiveRetentionRepository.EligibleSummary summary =
                campaignArchiveRetentionRepository.countEligible(tenantId, cutoffInstant);

        summaryRepository.upsert(tenantId, RetentionDataCategory.CAMPAIGN_DATA, summary.rowCount(),
                summary.oldestArchivedDate(), summary.newestArchivedDate());

        // Od BE-119, RetentionPurgeServiceImpl obsługuje CAMPAIGN_DATA (delegacja do
        // purge_campaign_contact_archive) — auto-purge wyzwalany identycznie jak dla
        // CONTACT_INTERACTIONS/TRANSCRIPTS, przez ten sam wspólny maybeTriggerAutoPurge, tylko
        // gdy triggerAutoPurge==true (patrz Javadoc RetentionEvaluationService).
        if (triggerAutoPurge) {
            maybeTriggerAutoPurge(tenantId, RetentionDataCategory.CAMPAIGN_DATA, summary.rowCount());
        }
    }

    // =========================================================================
    // Auto-purge
    // =========================================================================

    private void maybeTriggerAutoPurge(UUID tenantId, RetentionDataCategory category, long eligibleRowCount) {
        if (eligibleRowCount <= 0) {
            return;
        }
        if (!isAutoPurgeEnabled(tenantId, category)) {
            return;
        }

        log.info("[RetentionEvaluationService] Auto-purge trigger: tenant={}, category={}, eligibleRowCount={}",
                tenantId, category, eligibleRowCount);
        retentionPurgeService.purge(tenantId, category, PurgeTriggerType.AUTO, null);
    }

    private boolean isAutoPurgeEnabled(UUID tenantId, RetentionDataCategory category) {
        return retentionPolicyService.listPolicies(tenantId).stream()
                .filter(policy -> policy.getDataCategory() == category)
                .findFirst()
                .map(TenantRetentionPolicy::isAutoPurgeEnabled)
                .orElse(false);
    }

    // =========================================================================
    // Akumulator wyniku per tenant
    // =========================================================================

    /** Sumuje {@code eligibleRowCount} i śledzi min/max {@code rangeStart} dla jednego tenanta w obrębie kategorii. */
    private static final class TenantAccumulator {
        private long eligibleRowCount;
        private LocalDate oldestPeriod;
        private LocalDate newestPeriod;

        void add(long rowCount, LocalDate periodStart) {
            eligibleRowCount += rowCount;
            oldestPeriod = (oldestPeriod == null || periodStart.isBefore(oldestPeriod)) ? periodStart : oldestPeriod;
            newestPeriod = (newestPeriod == null || periodStart.isAfter(newestPeriod)) ? periodStart : newestPeriod;
        }
    }
}
