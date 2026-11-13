package com.contactcenter.domain.email;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-196 on a REAL PostgreSQL, the REAL SQL of {@link EmailMessageRepository#findTenantIdsWithEml} (cross-tenant
 * discovery - needs an RLS-bypassing role, as in production, hence the superuser pool) and of
 * {@link EmailMessageRepository#findContactIdsWithExpiredEml} (keyset paging, DISTINCT, retention predicates).
 */
@DisplayName("EmailMessageRepository - wybor tenantow i kandydatow EML (BE-196)")
class EmailMessageTenantDiscoveryIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static EmailMessageRepository repository;

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(pool, new Class<?>[]{EmailMessage.class},
                new Class<?>[]{EmailMessageRepository.class}, null);
        repository = ctx.getBean(EmailMessageRepository.class);
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

    private static Instant daysAgo(int d) {
        return Instant.now().minus(d, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    }

    private UUID contact(UUID tenant, String channel, String recordingUrl, Instant endedAt) {
        UUID id = UUID.randomUUID();
        Instant started = (endedAt != null ? endedAt : daysAgo(100)).minus(1, ChronoUnit.HOURS);
        jdbc.update("INSERT INTO contact (contact_id, tenant_id, channel, direction, status, remote_address, "
                        + "queued_at, started_at, ended_at, recording_url) VALUES (?, ?, ?, 'INBOUND', 'COMPLETED', "
                        + "'x@y.pl', CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz), ?)",
                id, tenant, channel, started.toString(), started.toString(),
                endedAt == null ? null : endedAt.toString(), recordingUrl);
        return id;
    }

    private void message(UUID tenant, UUID contact, Instant at, String key) {
        jdbc.update("INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, "
                        + "to_address, subject, body_text, attachments, received_at, message_at, eml_s3_key) "
                        + "VALUES (?, ?, ?, 'OUTBOUND', 'a@b.pl', 'c@d.pl', 's', 'b', '[]'::jsonb, now(), "
                        + "CAST(? AS timestamptz), ?)",
                UUID.randomUUID(), tenant, contact, at.toString(), key);
    }

    @Test
    @DisplayName("findTenantIdsWithEml: tenant z zywym kluczem jest zwrocony; tenant z samymi NULL-ami i bez wiadomosci - nie")
    void tenantDiscoveryUsesLiveKeys() {
        UUID withKey = PostgresTestDatabase.insertTenant(jdbc, "T1 BE-196 " + UUID.randomUUID());
        UUID nullKeysOnly = PostgresTestDatabase.insertTenant(jdbc, "T2 BE-196 " + UUID.randomUUID());
        UUID noMessages = PostgresTestDatabase.insertTenant(jdbc, "T3 BE-196 " + UUID.randomUUID());
        UUID c1 = contact(withKey, "EMAIL", null, daysAgo(100));
        UUID c2 = contact(nullKeysOnly, "EMAIL", null, daysAgo(100));
        message(withKey, c1, daysAgo(100), withKey + "/k.eml");
        message(nullKeysOnly, c2, daysAgo(100), null);

        List<UUID> tenants = repository.findTenantIdsWithEml();

        assertThat(tenants).contains(withKey).doesNotContain(nullKeysOnly, noMessages);
    }

    @Test
    @DisplayName("findTenantIdsWithEml: rzuca przy aktywnym TenantContext (guard cross-tenant)")
    void tenantDiscoveryRejectsActiveContext() {
        TenantContext.setTenantId(UUID.randomUUID());
        assertThatThrownBy(() -> repository.findTenantIdsWithEml()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("findContactIdsWithExpiredEml: predykaty retencji, DISTINCT, izolacja tenanta i keyset")
    void candidatesPredicatesDistinctAndKeyset() {
        UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "T4 BE-196 " + UUID.randomUUID());
        UUID other = PostgresTestDatabase.insertTenant(jdbc, "T5 BE-196 " + UUID.randomUUID());
        Instant cutoff = daysAgo(90);
        TenantContext.setTenantId(tenant);

        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID c = contact(tenant, "EMAIL", null, daysAgo(100));
            message(tenant, c, daysAgo(101), tenant + "/a" + i + ".eml");
            message(tenant, c, daysAgo(100), tenant + "/b" + i + ".eml"); // 2 messages -> DISTINCT
            expected.add(c);
        }
        UUID withRecording = contact(tenant, "EMAIL", tenant + "/r.eml", daysAgo(100));
        message(tenant, withRecording, daysAgo(100), tenant + "/x.eml");
        UUID phone = contact(tenant, "PHONE", null, daysAgo(100));
        message(tenant, phone, daysAgo(100), tenant + "/p.eml");
        UUID open = contact(tenant, "EMAIL", null, null);
        message(tenant, open, daysAgo(100), tenant + "/o.eml");
        UUID fresh = contact(tenant, "EMAIL", null, daysAgo(10));
        message(tenant, fresh, daysAgo(100), tenant + "/f.eml");
        UUID cleaned = contact(tenant, "EMAIL", null, daysAgo(100));
        message(tenant, cleaned, daysAgo(100), null);
        UUID foreign = contact(other, "EMAIL", null, daysAgo(100));
        message(other, foreign, daysAgo(100), other + "/z.eml");

        List<UUID> all = repository.findContactIdsWithExpiredEml(tenant, cutoff, null, 100);
        List<UUID> sorted = expected.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();

        assertThat(all).containsExactlyElementsOf(sorted);

        List<UUID> page1 = repository.findContactIdsWithExpiredEml(tenant, cutoff, null, 2);
        List<UUID> page2 = repository.findContactIdsWithExpiredEml(tenant, cutoff, page1.get(1), 2);
        List<UUID> page3 = repository.findContactIdsWithExpiredEml(tenant, cutoff, page2.get(1), 2);
        assertThat(page1).containsExactlyElementsOf(sorted.subList(0, 2));
        assertThat(page2).containsExactlyElementsOf(sorted.subList(2, 4));
        assertThat(page3).containsExactly(sorted.get(4));
    }
}
