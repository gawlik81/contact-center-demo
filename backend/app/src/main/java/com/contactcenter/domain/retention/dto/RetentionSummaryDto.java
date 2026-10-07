package com.contactcenter.domain.retention.dto;

import com.contactcenter.domain.retention.RetentionDataCategory;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Wpis dashboardu „ile danych kwalifikuje się do usunięcia" per kategoria danych
 * (BE-118, {@code GET /api/tenants/{tenantId}/retention/summary}).
 *
 * <p>Endpoint zwraca ZAWSZE dokładnie 4 wpisy tego DTO — jeden per
 * {@link RetentionDataCategory} — nawet jeśli cache {@code tenant_retention_pending_summary}
 * (V083, DB-047) nie ma jeszcze wiersza dla którejś kategorii. W praktyce dotyczy to dziś
 * {@code RECORDINGS}: {@code RetentionEvaluationJob} (BE-112) celowo jej nie liczy — obsługa
 * przez {@code RecordingRetentionJob} to zakres BE-116, jeszcze nieukończony.
 *
 * <p><strong>Dlaczego jawna flaga {@link #computed}, a nie tylko {@code eligibleRowCount == 0}:
 * </strong> {@code COMMENT ON TABLE tenant_retention_pending_summary} (migracja V083)
 * dokumentuje, że brak wiersza dla pary (tenant, kategoria) oznacza "jeszcze nie policzone przez
 * job", a NIE "policzono i wyszło zero". Zlanie tych dwóch stanów w jedno pole
 * {@code eligibleRowCount=0} zmyliłoby dashboard admina (FE-105/FE-103) — administrator mógłby
 * błędnie wnioskować "nie ma nic do wyczyszczenia", podczas gdy job po prostu jeszcze nie
 * przebiegł dla tej kategorii.
 *
 * <p><strong>Wiadomości w {@code eligibleRowCount} CONTACT_INTERACTIONS (BE-133, BE-135):</strong>
 * suma zawiera wiersze {@code contact}/{@code contact_event}/{@code social_message}/{@code email_message}
 * liczone WYŁĄCZNIE partycyjnie ({@code RetentionEvaluationServiceImpl#scanPartitionAwareCategory},
 * wpisy {@code PARTITION_AWARE_TABLES}). Dawne DOKŁADNE liczenie wiadomości e-mail przez JOIN/IN-subquery
 * (BE-128, {@code countEligibleMessages}) zostało usunięte, bo utrzymywanie obu ścieżek podwajałoby
 * wiersze wiadomości. Konsekwencja: udział wiadomości jest KONSERWATYWNYM PRZYBLIŻENIEM granicą partycji
 * miesięcznej (jak {@code contact}/{@code contact_event}, patrz uwaga BE128-02 poniżej), a NIE licznikiem
 * wiersz-po-wierszu. Interpretacja wartości na dashboardzie: orientacyjna.
 *
 * <p>Wynik jest orientacyjnym licznikiem dla administratora — może się różnić od tego, co faktycznie
 * usunie kolejny przebieg purge (BE-126/127), który operuje na stronicowanej liście kandydatów w konkretnym
 * momencie przy współbieżnym ruchu.
 *
 * <p><strong>Uwaga (CR-BACKEND.md BE128-02; zakres skorygowany przez BE-133/BE-135):</strong> cała suma
 * {@code CONTACT_INTERACTIONS} jest odtąd KONSERWATYWNYM przybliżeniem granicą partycji miesięcznej
 * (świadomy trade-off z BE-112/EPIC-29). Nie wpływa to na bezpieczeństwo faktycznego purge (który re-liczy
 * kontakty wierszowo, niezależnie od tego akumulatora), tylko na interpretację wartości wyświetlanej na
 * dashboardzie.
 *
 * <p><strong>Uwaga (CR-BACKEND.md BE128-01, naprawione):</strong> ta liczba (zapisywana do cache i
 * pokazywana adminowi) ZAWSZE zawiera wiadomości, niezależnie od
 * {@code retention.purge.delete-messages}. DECYZJA auto-purge ({@code RetentionEvaluationServiceImpl
 * #persistSummaryAndMaybeAutoPurgeForTenant}) używa jednak osobnej, mniejszej liczby, gdy ta flaga
 * jest {@code false} — bez tego auto-purge dla tenantów z {@code auto_purge_enabled=true} i samymi
 * osieroconymi wiadomościami odpalałby się co noc bez żadnego efektu (legacy purge nic z nich nie
 * usuwa).
 *
 * @param dataCategory         kategoria danych
 * @param eligibleRowCount     liczba rekordów kwalifikujących się do usunięcia wg cache; {@code 0}
 *                             gdy {@code computed=false} (brak jeszcze policzonej wartości)
 * @param oldestEligiblePeriod najstarszy miesiąc objęty wynikiem; {@code null} gdy {@code computed=false}
 * @param newestEligiblePeriod najnowszy miesiąc objęty wynikiem; {@code null} gdy {@code computed=false}
 * @param computedAt           znacznik czasu ostatniego przebiegu {@code RetentionEvaluationJob}
 *                             dla tej pary (tenant, kategoria); {@code null} gdy {@code computed=false}
 * @param computed             {@code false} = "jeszcze nie policzone przez RetentionEvaluationJob"
 *                             (brak wiersza w cache) — odróżnia ten stan od "policzono, zero do usunięcia"
 */
public record RetentionSummaryDto(
        RetentionDataCategory dataCategory,
        long eligibleRowCount,
        LocalDate oldestEligiblePeriod,
        LocalDate newestEligiblePeriod,
        Instant computedAt,
        boolean computed
) {}
