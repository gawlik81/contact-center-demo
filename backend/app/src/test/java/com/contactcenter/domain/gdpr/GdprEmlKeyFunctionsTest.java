package com.contactcenter.domain.gdpr;

import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V146: {@code anonymize_customer} / {@code export_customer_data} znaja {@code email_message.eml_s3_key}
 * (per-message EML, V145): klucze trafiaja do {@code s3_keys} (sprzatanie S3 / manifest eksportu), a
 * anonimizacja zeruje kolumne. Na prawdziwym PostgreSQL (Flyway do V146).
 */
@DisplayName("V146: GDPR (anonymize_customer, export_customer_data) obejmuje email_message.eml_s3_key")
class GdprEmlKeyFunctionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
    }

    @AfterAll
    static void stop() {
        pool.close();
    }

    @Test
    @DisplayName("dry-run i eksport widza klucze EML; anonimizacja zwraca je w s3_keys i zeruje kolumne (idempotentnie)")
    void emlKeysAreCollectedAndCleared() throws Exception {
        UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant V146 " + UUID.randomUUID());
        UUID customer = UUID.randomUUID();
        UUID contact = UUID.randomUUID();
        String email = "v146-" + UUID.randomUUID() + "@example.com";
        String firstKey = tenant + "/2026/10/" + contact + ".eml";
        String followKey = tenant + "/2026/10/" + contact + "/" + UUID.randomUUID() + ".eml";

        jdbc.update("INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                + "VALUES (?, ?, 'Jan', 'V146', '[]'::jsonb, ?::jsonb, 'MANUAL')", customer, tenant, "[\"" + email + "\"]");
        jdbc.update("INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, "
                + "started_at, recording_url) VALUES (?, ?, ?, 'EMAIL', 'INBOUND', 'COMPLETED', ?, now(), ?)",
                contact, tenant, customer, email, firstKey);
        insertMessage(tenant, contact, firstKey);
        insertMessage(tenant, contact, followKey);
        insertMessage(tenant, contact, null);

        // podglad: klucze widoczne, nic nie zmienione
        JsonNode dry = json("SELECT anonymize_customer(?, ?, NULL, TRUE)", customer, tenant);
        assertThat(keys(dry)).contains(firstKey, followKey);
        assertThat(dry.get("counts").get("email_message").asInt()).isEqualTo(3);
        assertThat(emlKeyCount(contact)).isEqualTo(2);

        // eksport (Art. 15): s3_keys zawiera klucze EML
        JsonNode export = json("SELECT export_customer_data(?, ?)", customer, tenant);
        assertThat(keys(export)).contains(firstKey, followKey);

        // anonimizacja: klucze zwrocone (przed zerowaniem), kolumna wyzerowana
        JsonNode real = json("SELECT anonymize_customer(?, ?, NULL, FALSE)", customer, tenant);
        assertThat(keys(real)).contains(firstKey, followKey);
        assertThat(emlKeyCount(contact)).isZero();

        // idempotencja: drugie wywolanie nie zwraca juz kluczy EML
        JsonNode again = json("SELECT anonymize_customer(?, ?, NULL, FALSE)", customer, tenant);
        assertThat(keys(again)).doesNotContain(firstKey, followKey);
        assertThat(again.get("counts").get("email_message").asInt()).isZero();
    }

    private static void insertMessage(UUID tenant, UUID contact, String emlKey) {
        jdbc.update("INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, "
                + "subject, body_text, attachments, received_at, message_at, eml_s3_key) "
                + "VALUES (?, ?, ?, 'INBOUND', 'klient@example.com', 'biuro@example.com', 'Temat', 'Tresc', '[]'::jsonb, "
                + "now(), now(), ?)", UUID.randomUUID(), tenant, contact, emlKey);
    }

    private static int emlKeyCount(UUID contact) {
        return jdbc.queryForObject("SELECT count(*) FROM email_message WHERE contact_id = ? AND eml_s3_key IS NOT NULL",
                Integer.class, contact);
    }

    private static JsonNode json(String sql, Object... args) throws Exception {
        return MAPPER.readTree(jdbc.queryForObject("SELECT (" + sql.substring("SELECT ".length()) + ")::text", String.class, args));
    }

    private static List<String> keys(JsonNode result) {
        List<String> out = new ArrayList<>();
        result.get("s3_keys").forEach(n -> out.add(n.asText()));
        return out;
    }
}
