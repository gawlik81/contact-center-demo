package com.contactcenter.domain.gdpr;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Repozytorium RODO (BE-129) — jedyny punkt wywołania natywnych funkcji PostgreSQL
 * {@code anonymize_customer} (DB-062, RODO Art. 17) i {@code export_customer_data}
 * (DB-061, RODO Art. 15/20).
 *
 * <p><strong>Encapsulation:</strong> package-private, dostęp wyłącznie przez {@link GdprServiceImpl}.
 *
 * <p><strong>Rozdział odpowiedzialności:</strong> to repozytorium NIE rzuca wyjątków domenowych
 * ({@code EntityNotFoundException}/{@code ConflictException} należą do warstwy serwisu, zgodnie
 * z konwencją tego projektu — repozytoria zwracają stan, serwisy interpretują go i rzucają). Wynik
 * próby anonimizacji jest niesiony przez {@link AnonymizeAttempt}.
 */
@Slf4j
@Repository
class GdprRepository extends TenantAwareRepository {

    /**
     * Wynik próby anonimizacji/podglądu klienta.
     *
     * @param customerExists     {@code false} gdy klient nie istnieje w tenancie (niezależnie od
     *                           {@code is_deleted} — pozwala dosanityzować klienta zanonimizowanego
     *                           dawniej wyłącznie ścieżką Javy, {@code CustomerRepository#anonymize})
     * @param rejectedInProgress {@code true} gdy odrzucono z powodu rekordu „w toku" (połączenie
     *                           faktycznie trwa) — wyłącznie dla {@code dryRun = false};
     *                           {@code resultJson} jest wtedy {@code null}
     * @param resultJson         surowy JSONB (jako tekst) zwrócony przez {@code anonymize_customer},
     *                           {@code null} gdy {@code customerExists = false} lub
     *                           {@code rejectedInProgress = true}
     */
    record AnonymizeAttempt(boolean customerExists, boolean rejectedInProgress, String resultJson) {
    }

    private static final String SQL_CUSTOMER_EXISTS = """
            SELECT EXISTS (
                SELECT 1 FROM customer
                WHERE customer_id = CAST(:customerId AS uuid) AND tenant_id = CAST(:tenantId AS uuid)
            )
            """;

    /**
     * Rekordy „w toku" (połączenie faktycznie trwa w chwili wywołania) powiązane z klientem:
     * {@code campaign_contact} w statusie {@code DIALING} ({@code ProgressiveDialerServiceImpl} —
     * połączenie już zainicjowane przez telefonię) i {@code scheduled_callback} w statusie
     * {@code PROCESSING} ({@code ScheduledCallbackExecutor} — oddzwonienie właśnie realizowane).
     *
     * <p>Reużywa {@code fn_customer_subject_ids} (D9 = A, DB-061/DB-062) — TEN SAM zbiór podmiotu
     * co anonimizacja, żeby guard i mutacja nigdy się nie rozjechały (most przez
     * {@code campaign_contact_record_id} też obowiązuje tutaj).
     *
     * <p><strong>Decyzja BE-129 (rekordy w toku):</strong> anonimizacja klienta z takim rekordem
     * kończy się jawnym błędem 409 — NIE próbą dokończenia po zakończeniu połączenia (to byłby
     * osobny epik synchronizacji z zdarzeniami telefonii). {@code scheduled_callback} w
     * {@code PENDING}/{@code PROCESSING} i tak trafia do {@code CANCELLED} wewnątrz
     * {@code anonymize_customer} — ten guard chroni wyłącznie rekordy, dla których połączenie jest
     * FAKTYCZNIE w locie w chwili wywołania (nie tylko zakolejkowane/oczekujące).
     *
     * <p><strong>Fix wyścigu TOCTOU (code review BE129-01, 2026-09-24):</strong> pod READ COMMITTED
     * zwykły {@code SELECT} bez blokady wiersza NIE eliminuje wyścigu w obrębie tej samej transakcji —
     * commit obcej transakcji (dialer/executor) między tym guardem a mutacją
     * {@code anonymize_customer} jest widoczny dla KOLEJNEGO polecenia tej samej transakcji.
     * Dlatego obie CTE ({@code locked_cc}/{@code locked_sb}) blokują (`FOR UPDATE OF`) WSZYSTKIE
     * wiersze zbioru podmiotu — celowo BEZ filtra po statusie w klauzuli {@code WHERE} blokady
     * (filtrowanie po statusie PRZED zablokowaniem pozwoliłoby dialerowi/executorowi przeklaimować
     * rekord w oknie między odczytem a blokadą — dokładnie ten sam wyścig, tylko przesunięty o jeden
     * krok). Status jest sprawdzany DOPIERO po uzyskaniu blokady (predykat {@code status = ...}
     * w zewnętrznym {@code EXISTS}), więc odczytana wartość jest zawsze świeża względem commitu.
     * Wzajemne wykluczenie z obiema stronami (empirycznie zweryfikowane na scratch Postgres 16,
     * dwoma równoległymi połączeniami JDBC — patrz raport wykonawcy):
     * <ul>
     *   <li>{@code ProgressiveDialerServiceImpl#fetchNextPendingContact} —
     *       {@code FOR UPDATE SKIP LOCKED} — grzecznie POMIJA wiersz zablokowany przez ten guard
     *       i wybiera inny kontakt kampanii; nie blokuje się.</li>
     *   <li>{@code ScheduledCallbackRepository#updateStatusIfPending} — zwykły
     *       {@code UPDATE ... WHERE status = 'PENDING'} — CZEKA na zwolnienie blokady (commit tej
     *       transakcji), po czym jego {@code WHERE} trafia na już zmieniony przez
     *       {@code anonymize_customer} status (np. {@code CANCELLED}) i aktualizuje 0 wierszy
     *       (bezpieczne wycofanie, zgodne z istniejącym kontraktem metody).</li>
     * </ul>
     * <strong>Ryzyko rezydualne (opisz w raporcie, nie naprawiaj tutaj):</strong> jeśli dialer
     * TRZYMA już blokadę wiersza (jego transakcja {@code initiateDialForAgent} jest
     * {@code @Transactional} i obejmuje wywołanie telefonii), ten guard CZEKA na jej zakończenie —
     * potencjalne opóźnienie żądania anonimizacji o czas trwania inicjacji połączenia. To świadomy
     * koszt poprawności (blokować, nie pomijać), nie da się go usunąć bez zmiany granic transakcji
     * w {@code ProgressiveDialerServiceImpl} (poza zakresem tego repozytorium).
     */
    private static final String SQL_HAS_IN_PROGRESS_RECORDS = """
            WITH subj AS MATERIALIZED (
                SELECT * FROM fn_customer_subject_ids(CAST(:customerId AS uuid), CAST(:tenantId AS uuid))
            ),
            locked_cc AS MATERIALIZED (
                SELECT cc.status
                FROM campaign_contact cc
                JOIN subj ON subj.entity_type = 'CAMPAIGN_CONTACT' AND subj.entity_id = cc.record_id
                WHERE cc.tenant_id = CAST(:tenantId AS uuid)
                FOR UPDATE OF cc
            ),
            locked_sb AS MATERIALIZED (
                SELECT sb.status
                FROM scheduled_callback sb
                JOIN subj ON subj.entity_type = 'SCHEDULED_CALLBACK' AND subj.entity_id = sb.callback_id
                WHERE sb.tenant_id = CAST(:tenantId AS uuid)
                FOR UPDATE OF sb
            )
            SELECT (
                EXISTS (SELECT 1 FROM locked_cc WHERE status = 'DIALING')
                OR EXISTS (SELECT 1 FROM locked_sb WHERE status = 'PROCESSING')
            )
            """;

    private static final String SQL_ANONYMIZE_CUSTOMER = """
            SELECT anonymize_customer(
                CAST(:customerId AS uuid),
                CAST(:tenantId AS uuid),
                CAST(:userId AS uuid),
                CAST(:dryRun AS boolean)
            )::text
            """;

    private static final String SQL_EXPORT_CUSTOMER_DATA = """
            SELECT export_customer_data(CAST(:customerId AS uuid), CAST(:tenantId AS uuid))::text
            """;

    /**
     * Wykonuje (lub — {@code dryRun = true} — wyłącznie podgląda) anonimizację klienta zgodnie
     * z RODO Art. 17, w JEDNEJ transakcji: istnienie klienta → guard rekordów w toku → wywołanie
     * funkcji SQL {@code anonymize_customer} (DB-062).
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta (z {@code TenantContext})
     * @param userId     UUID użytkownika wykonującego operację (audytowany atomowo przez funkcję SQL)
     * @param dryRun     {@code false} = rzeczywista anonimizacja, {@code true} = wyłącznie podgląd.
     *                   Zawsze przekazywane jawnie jako prymityw {@code boolean} (nigdy boxed
     *                   {@code Boolean}) — funkcja SQL odrzuca jawny SQL {@code NULL} z czytelnym
     *                   błędem (DB062-01), ale ta warstwa nie powinna w ogóle dopuścić takiej wartości.
     * @return wynik próby — patrz {@link AnonymizeAttempt}
     */
    @Transactional
    AnonymizeAttempt anonymize(UUID customerId, UUID tenantId, UUID userId, boolean dryRun) {
        // BE129-03 (code review 2026-09-24): świadomie BEZ assertSameTenant(tenantId, customerId) —
        // `tenantId` tutaj ZAWSZE pochodzi z TenantContext.getTenantId() u wołającego
        // (GdprServiceImpl), więc porównanie z TenantContext.getTenantId() w tej metodzie
        // porównywałoby kontekst z samym sobą i nigdy nie mogłoby rzucić. Prawdziwa izolacja
        // tenanta dla tej ścieżki żyje wyłącznie w predykatach `WHERE tenant_id = ...`
        // (`exists()`, `hasInProgressRecords()`) i wewnątrz samej funkcji SQL `anonymize_customer`
        // (niezależnie zweryfikowane w recenzjach DB-061/DB-062).
        setTenantContextInDb(tenantId);

        if (!exists(customerId, tenantId)) {
            log.debug("[GdprRepo] Klient nie istnieje w tenancie: customerId={}, tenant={}", customerId, tenantId);
            return new AnonymizeAttempt(false, false, null);
        }

        if (!dryRun && hasInProgressRecords(customerId, tenantId)) {
            log.warn("[GdprRepo] Anonimizacja odrzucona — rekord klienta w toku (DIALING/PROCESSING): "
                    + "customerId={}, tenant={}", customerId, tenantId);
            return new AnonymizeAttempt(true, true, null);
        }

        String json = (String) em.createNativeQuery(SQL_ANONYMIZE_CUSTOMER)
                .setParameter("customerId", customerId.toString())
                .setParameter("tenantId", tenantId.toString())
                .setParameter("userId", userId.toString())
                .setParameter("dryRun", dryRun)
                .getSingleResult();

        log.info("[GdprRepo] anonymize_customer wykonane: customerId={}, tenant={}, dryRun={}",
                customerId, tenantId, dryRun);
        return new AnonymizeAttempt(true, false, json);
    }

    /**
     * Eksportuje dane klienta (RODO Art. 15/20) przez funkcję SQL {@code export_customer_data}
     * (DB-061). Odczyt — bez efektów ubocznych.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @return surowy JSONB (jako tekst) zgodny z kontraktem DB-061
     */
    @Transactional(readOnly = true)
    String exportCustomerData(UUID customerId, UUID tenantId) {
        // BE129-03: patrz komentarz w anonymize() — ten sam powód, ta sama tautologia usunięta.
        setTenantContextInDb(tenantId);

        return (String) em.createNativeQuery(SQL_EXPORT_CUSTOMER_DATA)
                .setParameter("customerId", customerId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();
    }

    private boolean exists(UUID customerId, UUID tenantId) {
        Object result = em.createNativeQuery(SQL_CUSTOMER_EXISTS)
                .setParameter("customerId", customerId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }

    private boolean hasInProgressRecords(UUID customerId, UUID tenantId) {
        Object result = em.createNativeQuery(SQL_HAS_IN_PROGRESS_RECORDS)
                .setParameter("customerId", customerId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }
}
