package com.contactcenter.domain.plugin;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.infrastructure.persistence.converter.EncryptedStringConverter;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-245 W2 on a real PostgreSQL, under a role WITHOUT BYPASSRLS: the conditional UPDATEs that coordinate
 * {@code health_status} between instances (config-load failure vs circuit). The unit tests prove the calls; only the
 * database proves that the CAS really refuses to overwrite the other cause and that concurrent writers do not
 * double-apply.
 */
@DisplayName("TenantPluginInstallationRepository: CAS health_status (BE-245 W2) na prawdziwej bazie")
class TenantPluginInstallationHealthCasIntegrationTest {

    private static final String ROLE = "cc_be245_cas";

    private static HikariDataSource superuserPool;
    private static HikariDataSource restrictedPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static TenantPluginInstallationRepository repository;
    private static UUID pluginId;

    private UUID tenant;
    private UUID installation;

    @BeforeAll
    static void start() {
        superuserPool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(superuserPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, ROLE);
        restrictedPool = PostgresTestDatabase.pool(ROLE, password, 10);
        pluginId = UUID.randomUUID();
        jdbc.update("INSERT INTO plugin (id, plugin_key, display_name, vendor) VALUES (?, ?, 'cas', 'v')",
                pluginId, "cas-" + pluginId);
        ctx = JpaTestContext.create(restrictedPool, new Class<?>[]{TenantPluginInstallation.class},
                new Class<?>[]{TenantPluginInstallationRepository.class}, c -> {
                    c.getBeanFactory().registerSingleton("encryptedStringConverter", new EncryptedStringConverter(
                            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes())));
                    c.getBeanFactory().registerSingleton("meterRegistry", new SimpleMeterRegistry());
                });
        repository = ctx.getBean(TenantPluginInstallationRepository.class);
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        restrictedPool.close();
        superuserPool.close();
    }

    @BeforeEach
    void setUp() {
        tenant = PostgresTestDatabase.insertTenant(jdbc, "CAS " + UUID.randomUUID());
        installation = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plugin_version (id, plugin_id, tenant_id, version, jar_object_key, checksum_sha256,
                                            manifest_json, sdk_version, status)
                VALUES (?, ?, ?, '1.0.0', 'k', ?, '{}'::jsonb, '1.0', 'VALIDATED')
                """, versionId, pluginId, tenant, "a".repeat(64));
        jdbc.update("""
                INSERT INTO tenant_plugin_installation (id, tenant_id, plugin_version_id, enabled)
                VALUES (?, ?, ?, TRUE)
                """, installation, tenant, versionId);
        TenantContext.setTenantId(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void state(String status, int failures) {
        state(status, null, failures);
    }

    private void state(String status, String reason, int failures) {
        jdbc.update("UPDATE tenant_plugin_installation SET health_status = ?, degraded_reason = ?, "
                + "consecutive_failure_count = ? WHERE id = ?", status, reason, failures, installation);
    }

    /** The (status, reason, counter) triple, e.g. {@code DEGRADED/CONFIG_LOAD_FAILED:0}. */
    private String status() {
        return jdbc.queryForObject("SELECT health_status || '/' || COALESCE(degraded_reason, '-') || ':' "
                + "|| consecutive_failure_count FROM tenant_plugin_installation WHERE id = ?", String.class, installation);
    }

    @Test
    @DisplayName("sanity: rola jest ograniczona (RLS obowiązuje)")
    void roleIsRestricted() {
        assertThat(jdbc.queryForObject("SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname = ?",
                Boolean.class, ROLE)).isFalse();
    }

    @Test
    @DisplayName("V153: świeży wiersz = HEALTHY bez przyczyny; CHECK odrzuca nieznaną wartość")
    void newRowHasNoReasonAndCheckRejectsUnknownValue() {
        assertThat(status()).isEqualTo("HEALTHY/-:0");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> state("DEGRADED", "BOGUS", 0))
                .hasMessageContaining("chk_tenant_plugin_installation_degraded_reason");
    }

    @Test
    @DisplayName("porażka konfiguracji: HEALTHY -> DEGRADED/CONFIG_LOAD_FAILED (licznik nietknięty); powtórka no-op")
    void markIsIdempotent() {
        assertThat(repository.markDegradedByConfigLoad(installation, tenant)).isEqualTo(1);
        assertThat(status()).isEqualTo("DEGRADED/CONFIG_LOAD_FAILED:0");
        assertThat(repository.markDegradedByConfigLoad(installation, tenant)).isZero();
    }

    @Test
    @DisplayName("obwód otwarty: markDegradedByCircuit zapisuje parę w jednym UPDATE i nadpisuje CONFIG_LOAD_FAILED")
    void circuitOpenWritesPairAndOverwritesConfigReason() {
        repository.markDegradedByConfigLoad(installation, tenant);

        assertThat(repository.markDegradedByCircuit(installation, tenant, 5)).isEqualTo(1);

        assertThat(status()).isEqualTo("DEGRADED/CIRCUIT_OPEN:5");
    }

    @Test
    @DisplayName("porażka konfiguracji NIE nadpisuje CIRCUIT_OPEN (CAS tylko z HEALTHY)")
    void configFailureNeverOverwritesCircuitReason() {
        state("DEGRADED", "CIRCUIT_OPEN", 5);

        assertThat(repository.markDegradedByConfigLoad(installation, tenant)).isZero();
        assertThat(status()).isEqualTo("DEGRADED/CIRCUIT_OPEN:5");
    }

    @Test
    @DisplayName("obwód (recovery) nie zdejmuje CONFIG_LOAD_FAILED; zdejmuje go dopiero udane ładowanie -> HEALTHY/NULL")
    void circuitRecoveryDoesNotClearConfigDegradation() {
        repository.markDegradedByConfigLoad(installation, tenant);

        assertThat(repository.clearCircuitDegraded(installation, tenant)).isZero();
        assertThat(status()).isEqualTo("DEGRADED/CONFIG_LOAD_FAILED:0");

        assertThat(repository.clearDegraded(installation, tenant, false)).isEqualTo(1);
        assertThat(status()).isEqualTo("HEALTHY/-:0");
    }

    @Test
    @DisplayName("CIRCUIT_OPEN: nie jest zdjęty przez ścieżkę konfiguracji; zdejmuje go recovery obwodu albo reset")
    void configPathDoesNotTouchCircuitDegradation() {
        state("DEGRADED", "CIRCUIT_OPEN", TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD);

        assertThat(repository.markDegradedByConfigLoad(installation, tenant)).isZero();
        assertThat(repository.clearDegraded(installation, tenant, false)).isZero();
        assertThat(status()).isEqualTo("DEGRADED/CIRCUIT_OPEN:" + TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD);

        // after a circuit reset (admin change + successful load) any cause is cleared, reason included
        assertThat(repository.clearDegraded(installation, tenant, true)).isEqualTo(1);
        assertThat(status()).isEqualTo("HEALTHY/-:0");
    }

    @Test
    @DisplayName("half-open success: clearCircuitDegraded zdejmuje CIRCUIT_OPEN (reason -> NULL) i zeruje zalegający licznik HEALTHY")
    void circuitRecoveryClearsItsOwnDegradationAndStrayCounter() {
        state("DEGRADED", "CIRCUIT_OPEN", 7);
        assertThat(repository.clearCircuitDegraded(installation, tenant)).isEqualTo(1);
        assertThat(status()).isEqualTo("HEALTHY/-:0");

        state("HEALTHY", 3);
        assertThat(repository.clearCircuitDegraded(installation, tenant)).isEqualTo(1);
        assertThat(status()).isEqualTo("HEALTHY/-:0");
        assertThat(repository.clearCircuitDegraded(installation, tenant)).isZero();
    }

    @Test
    @DisplayName("obwód NIE nadpisuje DISABLED_BY_ADMIN (zapis opóźniony po wyłączeniu przez admina)")
    void circuitNeverOverwritesDisabledByAdmin() {
        state("DISABLED_BY_ADMIN", 0);

        assertThat(repository.markDegradedByCircuit(installation, tenant, 5)).isZero();
        assertThat(status()).isEqualTo("DISABLED_BY_ADMIN/-:0");
    }

    @Test
    @DisplayName("enable/disable (updateEnabled) nie zmienia pary (status, reason)")
    void enableDisableKeepPair() {
        state("DEGRADED", "CONFIG_LOAD_FAILED", 0);
        repository.updateEnabled(installation, tenant, false);
        repository.updateEnabled(installation, tenant, true);
        assertThat(status()).isEqualTo("DEGRADED/CONFIG_LOAD_FAILED:0");

        state("HEALTHY", null, 0);
        repository.updateEnabled(installation, tenant, true);
        assertThat(status()).isEqualTo("HEALTHY/-:0");
    }

    @Test
    @DisplayName("reload OK po porażce konfiguracji: HEALTHY/NULL")
    void reloadOkClearsConfigDegradation() {
        repository.markDegradedByConfigLoad(installation, tenant);
        repository.clearDegraded(installation, tenant, false);
        assertThat(status()).isEqualTo("HEALTHY/-:0");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0} reason={1} count={2}")
    @org.junit.jupiter.params.provider.MethodSource("equivalenceRows")
    @DisplayName("równoważność: effectiveDegradedReason() (Java) == predykaty SQL CIRCUIT_DEGRADED/CONFIG_DEGRADED na tych samych wierszach")
    void javaFallbackMatchesSqlPredicates(String status, String reason, int count) {
        state(status, reason, count);
        DegradedReason effective = repository.findByIdAndTenantId(installation, tenant).orElseThrow()
                .effectiveDegradedReason();

        // clearCircuitDegraded matches exactly the rows the Java side reads as CIRCUIT_OPEN; clearDegraded(false)
        // exactly CONFIG_LOAD_FAILED. Each call is attempted on a freshly reset row of the same shape.
        boolean sqlCircuit = repository.clearCircuitDegraded(installation, tenant) == 1
                && "DEGRADED".equals(status);
        state(status, reason, count);
        boolean sqlConfig = repository.clearDegraded(installation, tenant, false) == 1;

        assertThat(sqlCircuit).as("SQL circuit predicate").isEqualTo(effective == DegradedReason.CIRCUIT_OPEN);
        assertThat(sqlConfig).as("SQL config predicate").isEqualTo(effective == DegradedReason.CONFIG_LOAD_FAILED);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> equivalenceRows() {
        List<org.junit.jupiter.params.provider.Arguments> rows = new ArrayList<>();
        for (String status : new String[]{"HEALTHY", "DEGRADED"}) {
            for (String reason : new String[]{null, "CIRCUIT_OPEN", "CONFIG_LOAD_FAILED"}) {
                for (int count : new int[]{0, 4, 5, 6}) {
                    rows.add(org.junit.jupiter.params.provider.Arguments.of(status, reason, count));
                }
            }
        }
        return rows.stream();
    }

    @Test
    @DisplayName("legacy (rolling deploy): DEGRADED bez przyczyny - licznik >= progu = obwód (zdejmuje go recovery obwodu)")
    void legacyDegradedWithoutReasonAboveThresholdIsCircuit() {
        state("DEGRADED", null, 7);

        assertThat(repository.markDegradedByConfigLoad(installation, tenant)).isZero();
        assertThat(repository.clearDegraded(installation, tenant, false)).isZero();
        assertThat(repository.findByIdAndTenantId(installation, tenant).orElseThrow().effectiveDegradedReason())
                .isEqualTo(DegradedReason.CIRCUIT_OPEN);

        assertThat(repository.clearCircuitDegraded(installation, tenant)).isEqualTo(1);
        assertThat(status()).isEqualTo("HEALTHY/-:0");
    }

    @Test
    @DisplayName("legacy (rolling deploy): DEGRADED bez przyczyny - licznik < progu = konfiguracja (zdejmuje go clearDegraded(false), nie obwód)")
    void legacyDegradedWithoutReasonBelowThresholdIsConfig() {
        state("DEGRADED", null, 0);

        assertThat(repository.findByIdAndTenantId(installation, tenant).orElseThrow().effectiveDegradedReason())
                .isEqualTo(DegradedReason.CONFIG_LOAD_FAILED);
        assertThat(repository.clearCircuitDegraded(installation, tenant)).isZero();
        assertThat(repository.clearDegraded(installation, tenant, false)).isEqualTo(1);
        assertThat(status()).isEqualTo("HEALTHY/-:0");
    }

    @Test
    @DisplayName("przyczyna jest ignorowana gdy status != DEGRADED (stary zapis HEALTHY zostawiający reason): CAS nie reaguje, efektywna przyczyna = null")
    void reasonIgnoredWhenNotDegraded() {
        state("HEALTHY", "CIRCUIT_OPEN", 0); // an old instance wrote HEALTHY without clearing the reason

        assertThat(repository.clearDegraded(installation, tenant, true)).isZero();
        assertThat(repository.clearCircuitDegraded(installation, tenant)).isZero();
        TenantPluginInstallation i = repository.findByIdAndTenantId(installation, tenant).orElseThrow();
        assertThat(i.effectiveDegradedReason()).isNull();
        assertThat(i.getDegradedReason()).isEqualTo(DegradedReason.CIRCUIT_OPEN); // raw value preserved
    }

    @Test
    @DisplayName("DISABLED_BY_ADMIN nie jest nadpisywany przez żadną ze ścieżek CAS")
    void disabledByAdminIsNeverTouched() {
        state("DISABLED_BY_ADMIN", 0);

        assertThat(repository.markDegradedByConfigLoad(installation, tenant)).isZero();
        assertThat(repository.clearDegraded(installation, tenant, true)).isZero();
        assertThat(repository.clearCircuitDegraded(installation, tenant)).isZero();
        assertThat(status()).isEqualTo("DISABLED_BY_ADMIN/-:0");
    }

    @Test
    @DisplayName("wyścig: wiele instancji naraz oznacza porażkę konfiguracji - dokładnie jedna zmienia wiersz")
    void concurrentMarkExactlyOneWins() throws Exception {
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger changed = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    TenantContext.setTenantId(tenant);
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        changed.addAndGet(repository.markDegradedByConfigLoad(installation, tenant));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(changed.get()).isEqualTo(1);
        assertThat(status()).isEqualTo("DEGRADED/CONFIG_LOAD_FAILED:0");
    }
}
