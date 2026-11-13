package com.contactcenter.domain.contact;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real SQL of {@link ContactRepository#findTenantsWithRecordings} (BE-196 restored its original semantics: tenants
 * with an ended contact holding a {@code recording_url}). Cross-tenant by design, hence the RLS-bypassing superuser
 * pool (production model).
 */
@DisplayName("ContactRepository.findTenantsWithRecordings - prawdziwe SQL")
class ContactRepositoryTenantsWithRecordingsIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactRepository repository;

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(pool, new Class<?>[]{Contact.class}, new Class<?>[]{ContactRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", jdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                });
        repository = ctx.getBean(ContactRepository.class);
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void contact(UUID tenant, String channel, String recordingUrl, boolean ended) {
        Instant end = Instant.now().minus(100, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Instant start = end.minus(1, ChronoUnit.HOURS);
        jdbc.update("INSERT INTO contact (contact_id, tenant_id, channel, direction, status, remote_address, "
                        + "queued_at, started_at, ended_at, recording_url) VALUES (?, ?, ?, 'INBOUND', 'COMPLETED', "
                        + "'x@y.pl', CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz), ?)",
                UUID.randomUUID(), tenant, channel, start.toString(), start.toString(),
                ended ? end.toString() : null, recordingUrl);
    }

    @Test
    @DisplayName("zwraca tenantow z zakonczonym kontaktem z nagraniem; nie: EMAIL bez nagrania, kontakt otwarty z nagraniem")
    void returnsOnlyTenantsWithEndedRecordings() {
        UUID withRecording = PostgresTestDatabase.insertTenant(jdbc, "R1 BE-196 " + UUID.randomUUID());
        UUID emailNoRecording = PostgresTestDatabase.insertTenant(jdbc, "R2 BE-196 " + UUID.randomUUID());
        UUID openWithRecording = PostgresTestDatabase.insertTenant(jdbc, "R3 BE-196 " + UUID.randomUUID());
        contact(withRecording, "PHONE", withRecording + "/r.mp3", true);
        contact(emailNoRecording, "EMAIL", null, true);
        contact(openWithRecording, "PHONE", openWithRecording + "/r.mp3", false);

        List<UUID> tenants = repository.findTenantsWithRecordings();

        assertThat(tenants).contains(withRecording).doesNotContain(emailNoRecording, openWithRecording);
    }
}
