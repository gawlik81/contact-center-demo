package com.contactcenter.domain.contact;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium podsumowań AI dla kontaktów (tabela {@code contact_ai_summary}).
 *
 * <p>Podsumowania generowane są przez {@link com.contactcenter.domain.contact.AiSummaryService}
 * na żądanie (supervisor/agent) i zapisywane z pełnym timestampem. Jeden kontakt może mieć
 * wiele podsumowań – odczyt zawsze zwraca najnowsze (po {@code generated_at DESC}).
 *
 * <p>Wzorzec multi-tenant: rozszerza {@link TenantAwareRepository} – wywołuje
 * {@code assertSameTenant()} przed zapisem oraz {@code setTenantContextInDb()} przed każdym zapytaniem.
 *
 * <p><strong>BE-117:</strong> od migracji V087 (DB-051) tabela jest partycjonowana RANGE po
 * {@code generated_at} (PK złożony {@code (ai_summary_id, generated_at)}, patrz {@link ContactAiSummary}/
 * {@link ContactAiSummaryId}). {@link #save} to INSERT jawnie ustawiający wszystkie kolumny
 * (w tym {@code generated_at}) – bez zmian. Repozytorium nie ma metod UPDATE/DELETE
 * adresujących wiersz po PK; {@link #findLatestByContactId} czyta po {@code contact_id}, więc
 * kolumna partycjonowania nie musi występować w WHERE.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
class ContactAiSummaryRepository extends TenantAwareRepository {

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

    // =========================================================================
    // BE-113: Retencja – usuwanie batchowane (EPIC-29)
    // =========================================================================

    /**
     * Usuwa batch podsumowań AI tenanta starszych niż {@code cutoff} (retencja EPIC-29, BE-113 –
     * kategoria TRANSCRIPTS).
     *
     * <p>Identyfikuje wiersze do usunięcia przez pełny klucz główny {@code (ai_summary_id,
     * generated_at)} – NIE przez fizyczny {@code ctid} (patrz szczegółowe uzasadnienie w
     * {@code ContactRepository#deleteBatchOlderThan}: {@code ctid} nie jest unikalny globalnie na
     * tabeli partycjonowanej). {@code generated_at} jest kolumną partycjonowania (V087/DB-051,
     * BE-117) — UWAGA: nie {@code created_at}.
     *
     * @param tenantId  UUID tenanta
     * @param cutoff    granica czasowa – usuwane są podsumowania z {@code generated_at < cutoff}
     * @param batchSize maksymalna liczba wierszy usuwanych w jednym wywołaniu (rozmiar {@code LIMIT})
     * @return liczba usuniętych wierszy (0 = brak kwalifikujących się wierszy)
     */
    @Transactional
    public int deleteBatchOlderThan(UUID tenantId, Instant cutoff, int batchSize) {
        setTenantContextInDb(tenantId);

        int deleted = jdbcTemplate.update("""
                WITH batch AS (
                    SELECT ai_summary_id, generated_at FROM contact_ai_summary
                    WHERE tenant_id = ?::uuid AND generated_at < ?
                    ORDER BY generated_at
                    LIMIT ?
                )
                DELETE FROM contact_ai_summary c
                USING batch b
                WHERE c.ai_summary_id = b.ai_summary_id AND c.generated_at = b.generated_at
                """,
                tenantId.toString(),
                Timestamp.from(cutoff),
                batchSize);

        log.info("[ContactAiSummaryRepo] Purge batch: tenant={}, cutoff={}, usunięto={}", tenantId, cutoff, deleted);
        return deleted;
    }
}
