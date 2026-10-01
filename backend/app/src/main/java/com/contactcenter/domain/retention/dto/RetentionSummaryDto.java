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
 * <p><strong>BE-128 (EPIC-30) — {@code eligibleRowCount} kategorii {@code CONTACT_INTERACTIONS}
 * zawiera także wiadomości e-mail, DOKŁADNIE (nie oszacowanie):</strong> oprócz wierszy
 * {@code contact}/{@code contact_event}/{@code social_message} (liczonych partycyjnie,
 * {@code RetentionEvaluationServiceImpl#scanPartitionAwareCategory}) suma zawiera dwa składniki
 * wiadomości e-mail, policzone TYM SAMYM cutoffem co kontakty: wiadomości e-mail OSIEROCONE
 * ({@code contact_id IS NULL}, kryterium BE-127, {@code EmailMessageService#countOrphansOlderThan})
 * oraz wiadomości e-mail POWIĄZANE z kontaktem, który sam kwalifikuje się do usunięcia
 * ({@code contact.started_at < cutoff}, nowość BE-128, {@code EmailMessageService#countLinkedToContactsOlderThan}).
 *
 * <p><strong>BE-133 (EPIC-30, 2026-10-01) — {@code social_message} przeszła na liczenie partycyjne:</strong>
 * do 2026-10-01 ta sekcja opisywała CZTERY składniki wiadomości (e-mail + social, każda
 * osierocona/powiązana), wszystkie DOKŁADNE. Po przekonwertowaniu {@code social_message} na tabelę
 * partycjonowaną (DB-065, V100) jej udział przeszedł na {@code PartitionScanner} (wpis
 * {@code "social_message"} w {@code PARTITION_AWARE_TABLES}, jak {@code contact}/{@code contact_event})
 * — patrz Javadoc {@code RetentionEvaluationServiceImpl#countEligibleMessages}. Konsekwencja: udział
 * {@code social_message} w sumie jest odtąd KONSERWATYWNYM PRZYBLIŻENIEM granicą partycji
 * miesięcznej (jak {@code contact}/{@code contact_event}, patrz uwaga BE128-02 poniżej), NIE już
 * dokładnym licznikiem wiersz-po-wierszu. {@code email_message} pozostaje DOKŁADNA (JOIN/IN-subquery)
 * do czasu BE-135.
 *
 * <p><strong>Dlaczego DOKŁADNE dla e-mail, a nie oszacowanie</strong> (decyzja projektowa BE-128,
 * mimo że ticket dopuszczał oszacowanie/pominięcie jako opcję): liczenie wiadomości powiązanych
 * przez JOIN/IN-subquery do {@code contact} (zamiast partycyjnego skanu jak dla {@code contact}/
 * {@code contact_event}/{@code social_message}) ZWERYFIKOWANO EXPLAIN ANALYZE na scratch DB (notatka
 * wykonania BE-128 w {@code TASKS-BACKEND.md}: 100 tys. kontaktów, 10 tenantów, tabela
 * {@code email_message} ~1,16 mln wierszy, tenant docelowy = ~10% udziału) — planner wybiera
 * {@code Bitmap Index Scan} na istniejącym indeksie {@code uq_email_message_id_header} (migracja
 * V010, {@code tenant_id} jako PIERWSZA kolumna — ŻADNA nowa migracja SQL nie była potrzebna), więc
 * koszt jest ograniczony do wierszy TEGO tenanta, NIE do rozmiaru całej tabeli (nie rośnie z liczbą
 * innych tenantów na platformie/liczbą kontaktów innych tenantów). Gdy tenant jest większością
 * tabeli, planner wybiera Seq Scan — i to jest wtedy i tak najszybsza opcja. Po skonwertowaniu
 * {@code email_message} na tabelę partycjonowaną (DB-067) ten składnik przejdzie również na
 * {@code PartitionScanner} (BE-135, wpis do {@code PARTITION_AWARE_TABLES}) — patrz Javadoc
 * {@code RetentionEvaluationServiceImpl#countEligibleMessages}.
 *
 * <p>Wynik jest wiarygodnym ORIENTACYJNYM licznikiem dla administratora — może się różnić o kilka
 * wierszy od tego, co faktycznie usunie kolejny przebieg purge (BE-126/127), który operuje na
 * stronicowanej liście kandydatów w konkretnym momencie przy współbieżnym ruchu.
 *
 * <p><strong>Uwaga (CR-BACKEND.md BE128-02; zakres skorygowany przez BE-133):</strong> „DOKŁADNE"
 * wyżej dotyczy WYŁĄCZNIE dwóch składników wiadomości e-mail (osierocone + powiązane) — składnik
 * {@code contact}/{@code contact_event}/{@code social_message} (ostatnia od BE-133), do którego są
 * dodawane, jest z definicji KONSERWATYWNYM przybliżeniem (granica całej partycji miesięcznej, nie
 * wiersz-po-wierszu — świadomy trade-off z BE-112/EPIC-29, nie nowość BE-128). Ta jedna liczba
 * miesza więc dokładny licznik (e-mail) z niedoszacowanym (kontakty/zdarzenia/social) — nie wpływa
 * to na bezpieczeństwo faktycznego purge (który re-liczy kontakty wierszowo, niezależnie od tego
 * akumulatora), tylko na interpretację wartości wyświetlanej na dashboardzie.
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
