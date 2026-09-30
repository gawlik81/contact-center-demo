package com.contactcenter.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Współdzielona (per JVM testów) baza PostgreSQL 16 na Testcontainers z PEŁNYM łańcuchem migracji
 * Flyway ({@code classpath:db/migration}) — do testów integracyjnych repozytoriów i serwisów, które
 * mają sprawdzić prawdziwy natywny SQL / Hibernate / RLS (WP-1 EPIC-30). Mocki {@code EntityManager}
 * nie łapały błędów tej klasy (mapowanie enum, brak {@code TenantContext}, semantyka {@code DELETE}).
 *
 * <p>Kontener startuje leniwie przy pierwszym użyciu i żyje do końca JVM (sprząta go Ryuk), więc
 * kolejne klasy testowe nie płacą za start bazy i {@code migrate}. Konsekwencja: dane zostają między
 * klasami/testami — testy MUSZĄ używać własnych, losowych tenantów ({@link #insertTenant}) i nie
 * zakładać pustych tabel.
 *
 * <p>Domyślny użytkownik {@code cc_test} jest superuserem (omija RLS) — tak jak {@code ccapp} w demo.
 * Do testów RLS pod rolą ograniczoną: {@link #createRestrictedLoginRole}.
 */
public final class PostgresTestDatabase {

    private static final String DB_NAME = "contact_center_test";
    private static final String DB_USER = "cc_test";
    private static final String DB_PASSWORD = "cc_test";

    private static PostgreSQLContainer<?> container;

    private PostgresTestDatabase() {
    }

    /** Zwraca kontener (startuje go i migruje schemat przy pierwszym wywołaniu). */
    private static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            TestcontainersSupport.ensureDockerApiVersion();
            PostgreSQLContainer<?> started = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB_NAME)
                    .withUsername(DB_USER)
                    .withPassword(DB_PASSWORD);
            started.start();

            Flyway.configure()
                    .dataSource(started.getJdbcUrl(), DB_USER, DB_PASSWORD)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            container = started;
        }
        return container;
    }

    /** JDBC URL bazy testowej. */
    public static String jdbcUrl() {
        return container().getJdbcUrl();
    }

    /**
     * Pula HikariCP na bazę testową. Wywołujący zamyka ją sam (zwykle {@code @AfterAll}).
     *
     * @param username        użytkownik ({@code cc_test} = superuser albo rola z {@link #createRestrictedLoginRole})
     * @param password        hasło
     * @param maxPoolSize     maksymalny rozmiar puli (1 pozwala wykryć trzymanie połączenia w transakcji)
     */
    public static HikariDataSource pool(String username, String password, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }

    /** Pula superusera {@code cc_test} — do seedowania danych i asercji stanu bazy. */
    public static HikariDataSource superuserPool(int maxPoolSize) {
        return pool(DB_USER, DB_PASSWORD, maxPoolSize);
    }

    /**
     * Tworzy (idempotentnie) rolę LOGIN bez BYPASSRLS, będącą członkiem {@code app_user} (V012) —
     * odpowiednik roli aplikacyjnej pod RLS. Zwraca hasło roli.
     */
    public static String createRestrictedLoginRole(JdbcTemplate superuserJdbc, String roleName) {
        String password = "probe-" + roleName;
        superuserJdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '%1$s') THEN
                        CREATE ROLE %1$s LOGIN PASSWORD '%2$s' NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END
                $$
                """.formatted(roleName, password));
        superuserJdbc.execute("GRANT app_user TO " + roleName);
        return password;
    }

    /** Wstawia tenanta (minimalny wiersz) i zwraca jego UUID. */
    public static UUID insertTenant(JdbcTemplate jdbc, String name) {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", tenantId, name);
        return tenantId;
    }
}
