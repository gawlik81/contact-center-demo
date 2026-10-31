package com.contactcenter.domain.gdpr;

import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test współbieżności guardu „rekordów w toku" ({@code GdprRepository#SQL_HAS_IN_PROGRESS_RECORDS}),
 * naprawionego w code review BE129-01 (2026-09-24, patrz {@code CR-BACKEND.md}).
 *
 * <p><strong>Co dowodzi:</strong> dwoma RÓWNOLEGŁYMI połączeniami JDBC do PRAWDZIWEGO PostgreSQL
 * (Testcontainers, pełny łańcuch Flyway — realne {@code campaign_contact}/{@code scheduled_callback}/
 * {@code fn_customer_subject_ids} z V095/V096), że {@code FOR UPDATE} wprowadzone fixem tworzy
 * właściwe wzajemne wykluczenie z OBIEMA stronami wyścigu opisanego w recenzji:
 * <ul>
 *   <li>{@code ProgressiveDialerServiceImpl#fetchNextPendingContact} ({@code FOR UPDATE SKIP LOCKED})
 *       — musi POMINĄĆ wiersz zablokowany przez guard i wybrać inny kontakt, BEZ blokowania się
 *       ({@link #dialerSkipLocked_skipsRowLockedByGuard_doesNotBlock_picksAnotherContact()}).</li>
 *   <li>{@code ScheduledCallbackRepository#updateStatusIfPending} (zwykły
 *       {@code UPDATE ... WHERE status = 'PENDING'}) — musi POCZEKAĆ na zwolnienie blokady guardu,
 *       a po jej zwolnieniu trafić na już zmieniony status i zaktualizować 0 wierszy
 *       ({@link #scheduledCallbackExecutor_blocksOnGuardLock_thenNoOpsAfterStatusChanged()}).</li>
 *   <li>ORYGINALNY scenariusz wyścigu z code review (dialer klaimuje PIERWSZY, guard uruchamia się
 *       W TRAKCIE) — guard musi ZABLOKOWAĆ SIĘ na blokadzie dialera, a po jej zwolnieniu (po commit
 *       dialera) odczytać ŚWIEŻY status {@code DIALING} i poprawnie zgłosić „w toku"
 *       ({@link #guardBlocksOnDialerHeldLock_thenDetectsInProgressAfterDialerCommits()}).</li>
 * </ul>
 *
 * <p><strong>Metoda:</strong> tekst SQL guardu jest wyciągany REFLEKSJĄ z prywatnego pola
 * {@code GdprRepository#SQL_HAS_IN_PROGRESS_RECORDS} (nie kopiowany ręcznie) — gwarancja, że ten test
 * nigdy nie rozjedzie się z produkcyjnym zapytaniem. Zapytania „dialera"/„executora" są SUROWYM SQL
 * skopiowanym z {@code ProgressiveDialerServiceImpl#fetchNextPendingContact}/
 * {@code ScheduledCallbackRepository#updateStatusIfPending} — dokładnie te same predykaty i tryb
 * blokady (SKIP LOCKED vs zwykły UPDATE), nie ich reimplementacja.
 *
 * <p><strong>Świadome uproszczenie:</strong> zamiast wołać całą funkcję SQL {@code anonymize_customer}
 * (wymagałoby to pełnego, poprawnego zestawu FK — użytkownika audytu itd. — niepotrzebnego do
 * udowodnienia SAMEJ semantyki blokady), efekt funkcji na {@code scheduled_callback}/
 * {@code campaign_contact} jest odtworzony wprost z {@code V096__extend_anonymize_customer_gdpr_art17.sql}
 * (linie ok. 413-424 dla {@code scheduled_callback}, 434-448 dla {@code campaign_contact}) — te same
 * predykaty {@code status IN (...)} i docelowe wartości. To dowodzi WŁAŚCIWEJ semantyki blokady, NIE
 * powtarza pokrycia macierzy PII/audytu — to już wyczerpująco zrobione w
 * {@code AnonymizeCustomerExtensionTest} (DB-062) i {@code GdprServiceIntegrationTest} (BE-129).
 */
@DisplayName("GdprRepository – współbieżność guardu rekordów w toku (BE129-01)")
class GdprRepositoryConcurrencyTest {

    private static final long HOLD_MS = 2500;
    private static final long JOIN_TIMEOUT_SECONDS = 15;

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static String guardSqlPositional;
    private static String[] guardSqlParamOrder;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private UUID tenant;

    @BeforeAll
    static void setUpClass() throws Exception {
        dataSource = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(dataSource);

        // Wyciąga DOKŁADNY tekst produkcyjnego zapytania z GdprRepository — test nigdy nie
        // rozjedzie się z tym, co faktycznie wykonuje aplikacja.
        Field field = GdprRepository.class.getDeclaredField("SQL_HAS_IN_PROGRESS_RECORDS");
        field.setAccessible(true);
        String namedParamSql = (String) field.get(null);

        Matcher matcher = Pattern.compile(":customerId|:tenantId").matcher(namedParamSql);
        List<String> order = new ArrayList<>();
        while (matcher.find()) {
            order.add(matcher.group().substring(1));
        }
        guardSqlParamOrder = order.toArray(new String[0]);
        guardSqlPositional = namedParamSql.replaceAll(":customerId|:tenantId", "?");
    }

    @AfterAll
    static void stopClass() {
        dataSource.close();
    }

    @BeforeEach
    void setUp() {
        tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant - BE129-01 concurrency " + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // =========================================================================
    // Scenariusz 1: dialer (FOR UPDATE SKIP LOCKED) kontra guard
    // =========================================================================

    @Test
    @DisplayName("dialer (FOR UPDATE SKIP LOCKED) pomija wiersz zablokowany przez guard i wybiera inny "
            + "kontakt kampanii, BEZ blokowania się")
    void dialerSkipLocked_skipsRowLockedByGuard_doesNotBlock_picksAnotherContact() throws Exception {
        UUID customerId = insertCustomer("Target", "Dialer");
        UUID campaignId = insertCampaign();
        UUID targetRecordId = insertCampaignContact(campaignId, customerId, "PENDING", "+48500000001",
                Instant.now().minusSeconds(120));
        UUID decoyRecordId = insertCampaignContact(campaignId, null, "PENDING", "+48500000002",
                Instant.now().minusSeconds(60));

        CountDownLatch guardLockAcquired = new CountDownLatch(1);

        Future<Boolean> guardResult = executor.submit(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                boolean hasInProgress = executeGuard(conn, customerId, tenant);
                guardLockAcquired.countDown();
                Thread.sleep(HOLD_MS);
                conn.commit();
                return hasInProgress;
            }
        });

        assertThat(guardLockAcquired.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Instant beforeDialer = Instant.now();
        Future<String> dialerResult = executor.submit(() -> fetchNextPendingContactSkipLocked(campaignId, tenant));
        String dialerPickedRecordId = dialerResult.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Duration dialerElapsed = Duration.between(beforeDialer, Instant.now());

        boolean guardHadInProgress = guardResult.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(guardHadInProgress)
                .as("w chwili blokady rekord był jeszcze PENDING, nie DIALING/PROCESSING")
                .isFalse();
        assertThat(dialerElapsed.toMillis())
                .as("SKIP LOCKED nie może czekać na zwolnienie blokady guardu — musi pominąć wiersz natychmiast")
                .isLessThan(HOLD_MS - 500);
        assertThat(dialerPickedRecordId)
                .as("dialer musi pominąć zablokowany target i wybrać decoy")
                .isEqualTo(decoyRecordId.toString());
        assertThat(dialerPickedRecordId).isNotEqualTo(targetRecordId.toString());
    }

    // =========================================================================
    // Scenariusz 2: ScheduledCallbackExecutor (zwykły UPDATE) kontra guard
    // =========================================================================

    @Test
    @DisplayName("ScheduledCallbackExecutor#updateStatusIfPending (zwykły UPDATE) czeka na commit guardu, "
            + "potem trafia na już zmieniony status i aktualizuje 0 wierszy")
    void scheduledCallbackExecutor_blocksOnGuardLock_thenNoOpsAfterStatusChanged() throws Exception {
        UUID customerId = insertCustomer("Target", "Callback");
        UUID callbackId = insertScheduledCallback(customerId, "PENDING");

        CountDownLatch guardLockAcquired = new CountDownLatch(1);

        Future<Boolean> guardResult = executor.submit(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                boolean hasInProgress = executeGuard(conn, customerId, tenant);
                guardLockAcquired.countDown();
                Thread.sleep(HOLD_MS);
                // Stand-in dla anonymize_customer (V096 :413-424) — status PENDING/PROCESSING -> CANCELLED,
                // W TEJ SAMEJ transakcji/połączeniu co guard (już trzyma FOR UPDATE OF sb na tym wierszu).
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE scheduled_callback SET status = 'CANCELLED' "
                                + "WHERE callback_id = ? AND status IN ('PENDING', 'PROCESSING')")) {
                    ps.setObject(1, callbackId);
                    ps.executeUpdate();
                }
                conn.commit();
                return hasInProgress;
            }
        });

        assertThat(guardLockAcquired.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Instant beforeExecutor = Instant.now();
        Future<Integer> executorResult = executor.submit(() -> updateStatusIfPending(callbackId, tenant, "PROCESSING"));
        int updatedRows = executorResult.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Duration executorElapsed = Duration.between(beforeExecutor, Instant.now());

        boolean guardHadInProgress = guardResult.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM scheduled_callback WHERE callback_id = ?", String.class, callbackId);

        assertThat(guardHadInProgress).isFalse();
        assertThat(executorElapsed.toMillis())
                .as("zwykły UPDATE musi CZEKAĆ na zwolnienie blokady guardu (commit), nie ominąć jej")
                .isGreaterThanOrEqualTo(HOLD_MS - 800);
        assertThat(updatedRows)
                .as("po odblokowaniu WHERE status = 'PENDING' nie trafia już w nic — status jest CANCELLED")
                .isZero();
        assertThat(finalStatus).isEqualTo("CANCELLED");
    }

    // =========================================================================
    // Scenariusz 3: ORYGINALNY wyścig z code review — dialer klaimuje PIERWSZY
    // =========================================================================

    @Test
    @DisplayName("dialer klaimuje rekord PIERWSZY (trzyma blokadę); guard uruchomiony W TRAKCIE musi "
            + "zaczekać, a po commit dialera poprawnie wykryć DIALING")
    void guardBlocksOnDialerHeldLock_thenDetectsInProgressAfterDialerCommits() throws Exception {
        UUID customerId = insertCustomer("Target", "RaceOriginal");
        UUID campaignId = insertCampaign();
        UUID targetRecordId = insertCampaignContact(campaignId, customerId, "PENDING", "+48500000003",
                Instant.now().minusSeconds(60));

        CountDownLatch dialerLockAcquired = new CountDownLatch(1);

        Future<Void> dialerFuture = executor.submit((Callable<Void>) () -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                String picked = fetchNextPendingContactSkipLocked(conn, campaignId, tenant);
                assertThat(picked).isEqualTo(targetRecordId.toString());
                dialerLockAcquired.countDown();
                Thread.sleep(HOLD_MS);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE campaign_contact SET status = 'DIALING' WHERE record_id = ?")) {
                    ps.setObject(1, targetRecordId);
                    ps.executeUpdate();
                }
                conn.commit();
                return null;
            }
        });

        assertThat(dialerLockAcquired.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Instant beforeGuard = Instant.now();
        Future<Boolean> guardFuture = executor.submit(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                boolean hasInProgress = executeGuard(conn, customerId, tenant);
                conn.commit();
                return hasInProgress;
            }
        });
        boolean guardHadInProgress = guardFuture.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Duration guardElapsed = Duration.between(beforeGuard, Instant.now());

        dialerFuture.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(guardElapsed.toMillis())
                .as("guard musi ZACZEKAĆ na blokadę dialera (bez FOR UPDATE guard przeszedłby natychmiast "
                        + "ze STARĄ wartością PENDING — dokładnie błąd z code review)")
                .isGreaterThanOrEqualTo(HOLD_MS - 800);
        assertThat(guardHadInProgress)
                .as("po odblokowaniu guard musi zobaczyć ŚWIEŻY status DIALING zacommitowany przez dialera")
                .isTrue();
    }

    // =========================================================================
    // Pomocnicze – wykonanie guardu (SQL wyciągnięty refleksją z GdprRepository)
    // =========================================================================

    private boolean executeGuard(Connection conn, UUID customerId, UUID tenantId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(guardSqlPositional)) {
            for (int i = 0; i < guardSqlParamOrder.length; i++) {
                UUID value = "customerId".equals(guardSqlParamOrder[i]) ? customerId : tenantId;
                ps.setObject(i + 1, value);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    // =========================================================================
    // Pomocnicze – zapytania „konsumentów" (skopiowane 1:1 z produkcyjnych klas)
    // =========================================================================

    /** Kopia {@code ProgressiveDialerServiceImpl#fetchNextPendingContact} (własne, krótkotrwałe połączenie). */
    private String fetchNextPendingContactSkipLocked(UUID campaignId, UUID tenantId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            return fetchNextPendingContactSkipLocked(conn, campaignId, tenantId);
        }
    }

    private String fetchNextPendingContactSkipLocked(Connection conn, UUID campaignId, UUID tenantId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT record_id::text
                FROM campaign_contact
                WHERE campaign_id = ?
                  AND tenant_id = ?
                  AND status IN ('PENDING', 'NO_ANSWER')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
                ORDER BY created_at ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """)) {
            ps.setObject(1, campaignId);
            ps.setObject(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Kopia {@code ScheduledCallbackRepository#updateStatusIfPending} (własne, krótkotrwałe połączenie). */
    private int updateStatusIfPending(UUID callbackId, UUID tenantId, String newStatus) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE scheduled_callback SET status = ?, updated_at = NOW() "
                            + "WHERE callback_id = ? AND tenant_id = ? AND status = 'PENDING'")) {
                ps.setString(1, newStatus);
                ps.setObject(2, callbackId);
                ps.setObject(3, tenantId);
                return ps.executeUpdate();
            }
        }
    }

    // =========================================================================
    // Seedowanie
    // =========================================================================

    private UUID insertCustomer(String firstName, String lastName) {
        UUID customerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source, is_deleted)
                VALUES (?, ?, ?, ?, '[]'::jsonb, '[]'::jsonb, 'MANUAL', false)
                """, customerId, tenant, firstName, lastName);
        return customerId;
    }

    private UUID insertCampaign() {
        UUID campaignId = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, ?)",
                campaignId, tenant, "Kampania - concurrency " + campaignId);
        return campaignId;
    }

    private UUID insertCampaignContact(UUID campaignId, UUID customerId, String status, String phone, Instant createdAt) {
        UUID recordId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone,
                    first_name, last_name, custom_fields, status, attempt_count, created_at)
                VALUES (?, ?, ?, ?, ?, 'Test', 'Test', '{}'::jsonb, ?, 0, ?)
                """, recordId, campaignId, tenant, customerId, phone, status, java.sql.Timestamp.from(createdAt));
        return recordId;
    }

    private UUID insertScheduledCallback(UUID customerId, String status) {
        UUID callbackId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, first_name,
                    last_name, scheduled_at, status, source_type)
                VALUES (?, ?, ?, '+48500000009', 'Target', 'Callback', now() + interval '1 hour', ?, 'AGENT_MANUAL')
                """, callbackId, tenant, customerId, status);
        return callbackId;
    }
}
