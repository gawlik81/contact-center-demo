package com.contactcenter.domain.contact;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-222: {@link ContactRepository#insert} lets the database stamp {@code created_at}/{@code updated_at} inside the
 * writing transaction and reads the values back (RETURNING) into the entity. Real PostgreSQL under RLS.
 */
@DisplayName("ContactRepository.insert - znaczniki czasu z bazy (BE-222)")
class ContactInsertDbTimestampsIntegrationTest {

    private static final String ROLE = "be222_contact_app_role";

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static JdbcTemplate appJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactRepository repository;
    private static TransactionTemplate tx;

    private UUID tenantId;

    @BeforeAll
    static void start() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE);
        appPool = PostgresTestDatabase.pool(ROLE, password, 2);
        appJdbc = new JdbcTemplate(appPool);
        ctx = JpaTestContext.create(appPool, new Class<?>[]{Contact.class}, new Class<?>[]{ContactRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", appJdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                });
        repository = ctx.getBean(ContactRepository.class);
        tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        appPool.close();
        superPool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(superJdbc, "Tenant BE-222 contact " + UUID.randomUUID());
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        superJdbc.update("DELETE FROM contact WHERE tenant_id = ?", tenantId);
    }

    private Contact contact(Instant javaCreatedAt, Instant javaUpdatedAt) {
        Instant now = Instant.now();
        return Contact.builder()
                .contactId(UUID.randomUUID())
                .tenantId(tenantId)
                .channel("PHONE")
                .direction("INBOUND")
                .status("QUEUED")
                .queuedAt(now)
                .startedAt(now)
                .channelMetadata(new HashMap<>())
                .createdAt(javaCreatedAt)
                .updatedAt(javaUpdatedAt)
                .build();
    }

    private Instant dbValue(UUID contactId, String column) {
        Timestamp ts = superJdbc.queryForObject("SELECT " + column + " FROM contact WHERE contact_id = ?",
                Timestamp.class, contactId);
        return ts == null ? null : ts.toInstant();
    }

    @Test
    @DisplayName("created_at = NOW() transakcji zapisu (>= xact_start), znacznik Javy z entity jest ignorowany, entity dostaje wartosc z RETURNING")
    void createdAtStampedByDatabaseAndReadBackIntoEntity() {
        Instant javaStamp = Instant.parse("2001-01-01T00:00:00Z"); // clearly not "now": proves it is not persisted
        Contact contact = contact(javaStamp, null);
        Instant[] xactStart = new Instant[1];

        tx.execute(status -> {
            xactStart[0] = appJdbc.queryForObject("SELECT NOW()", Timestamp.class).toInstant();
            appJdbc.execute("SELECT pg_sleep(0.05)");
            return repository.insert(contact);
        });

        Instant stored = dbValue(contact.getContactId(), "created_at");
        assertThat(stored).isEqualTo(xactStart[0]).isAfterOrEqualTo(xactStart[0]);
        assertThat(stored).isNotEqualTo(javaStamp);
        assertThat(contact.getCreatedAt()).as("R2: caller reading createdAt after insert must not see Java/null").isEqualTo(stored);
        assertThat(contact.getUpdatedAt()).isNull();
        assertThat(dbValue(contact.getContactId(), "updated_at")).isNull();
    }

    @Test
    @DisplayName("entity z updatedAt != null -> updated_at = NOW() z bazy (jak created_at), wartosc wraca do entity")
    void updatedAtStampedByDatabaseWhenRequested() {
        Contact contact = contact(Instant.now(), Instant.parse("2001-01-01T00:00:00Z"));
        Instant[] xactStart = new Instant[1];

        tx.execute(status -> {
            xactStart[0] = appJdbc.queryForObject("SELECT NOW()", Timestamp.class).toInstant();
            return repository.insert(contact);
        });

        assertThat(dbValue(contact.getContactId(), "updated_at")).isEqualTo(xactStart[0]);
        assertThat(contact.getUpdatedAt()).isEqualTo(xactStart[0]);
        assertThat(contact.getCreatedAt()).isEqualTo(dbValue(contact.getContactId(), "created_at"));
    }

    @Test
    @DisplayName("insert poza zewnetrzna transakcja (wlasna transakcja repozytorium) tez zwraca znacznik z bazy")
    void standaloneInsertReturnsDatabaseTimestamp() {
        Contact contact = contact(null, null);

        repository.insert(contact);

        assertThat(contact.getCreatedAt()).isNotNull()
                .isEqualTo(dbValue(contact.getContactId(), "created_at"));
    }
}
