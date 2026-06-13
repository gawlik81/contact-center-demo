package com.contactcenter.domain.contact;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium podsumowań AI dla kontaktów (tabela {@code contact_ai_summary}).
 *
 * <p>Podsumowania generowane są przez {@link com.contactcenter.domain.service.AiSummaryService}
 * na żądanie (supervisor/agent) i zapisywane z pełnym timestampem. Jeden kontakt może mieć
 * wiele podsumowań – odczyt zawsze zwraca najnowsze (po {@code generated_at DESC}).
 *
 * <p>Wzorzec multi-tenant: rozszerza {@link TenantAwareRepository} – wywołuje
 * {@code assertSameTenant()} przed zapisem oraz {@code setTenantContextInDb()} przed każdym zapytaniem.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ContactAiSummaryRepository extends TenantAwareRepository {

    private final JdbcTemplate jdbcTemplate;

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Wstawia nowy rekord podsumowania AI dla kontaktu.
     *
     * <p>Jeden kontakt może mieć wiele podsumowań (np. po ponownym generowaniu).
     * Odczyt przez {@link #findLatestByContactId} zawsze zwraca najnowsze.
     *
     * @param entity encja podsumowania z uzupełnionymi wszystkimi polami
     */
    @Transactional
    public void save(ContactAiSummary entity) {
        assertSameTenant(entity.getTenantId());
        setTenantContextInDb(entity.getTenantId());

        jdbcTemplate.update("""
                INSERT INTO contact_ai_summary
                    (ai_summary_id, contact_id, tenant_id, summary, model, generated_at, created_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)
                """,
                entity.getAiSummaryId().toString(),
                entity.getContactId().toString(),
                entity.getTenantId().toString(),
                entity.getSummary(),
                entity.getModel(),
                Timestamp.from(entity.getGeneratedAt()),
                Timestamp.from(entity.getCreatedAt()));

        log.info("[ContactAiSummaryRepo] Zapisano podsumowanie AI: contactId={}, model={}, summaryLength={}",
                entity.getContactId(), entity.getModel(), entity.getSummary().length());
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Zwraca najnowsze podsumowanie AI dla wskazanego kontaktu.
     *
     * <p>Gdy kontakt ma wiele podsumowań (np. po wielokrotnym generowaniu),
     * zwracane jest to z największą wartością {@code generated_at}.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (cross-tenant safety)
     * @return Optional z encją podsumowania lub empty() gdy brak rekordu
     */
    @Transactional(readOnly = true)
    public Optional<ContactAiSummary> findLatestByContactId(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        List<ContactAiSummary> results = jdbcTemplate.query("""
                SELECT ai_summary_id, contact_id, tenant_id, summary, model, generated_at, created_at
                FROM contact_ai_summary
                WHERE contact_id = ?::uuid
                  AND tenant_id  = ?::uuid
                ORDER BY generated_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> ContactAiSummary.builder()
                        .aiSummaryId(UUID.fromString(rs.getString("ai_summary_id")))
                        .contactId(UUID.fromString(rs.getString("contact_id")))
                        .tenantId(UUID.fromString(rs.getString("tenant_id")))
                        .summary(rs.getString("summary"))
                        .model(rs.getString("model"))
                        .generatedAt(rs.getTimestamp("generated_at").toInstant())
                        .createdAt(rs.getTimestamp("created_at").toInstant())
                        .build(),
                contactId.toString(),
                tenantId.toString());

        log.debug("[ContactAiSummaryRepo] findLatestByContactId: contactId={}, found={}",
                contactId, !results.isEmpty());

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
