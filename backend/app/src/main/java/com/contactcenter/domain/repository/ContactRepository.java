package com.contactcenter.domain.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium kontaktów – operacje związane z nagraniami (BE-010).
 *
 * <p>Tabela {@code contact} jest partycjonowana po {@code started_at}, co wyklucza
 * standardowe JPA {@code @Repository} z {@code findById(UUID)} (klucz główny jest złożony).
 * Używamy natywnego SQL przez {@link JdbcTemplate} zgodnie z wzorcem z AuditLogRepository.
 *
 * <p>Operacje na RLS:
 * Metody publiczne wywołują {@link #setTenantContextInDb()} przed każdą operacją DB,
 * aby PostgreSQL Row Level Security mogło filtrować dane per tenant.
 */
@Slf4j
@Repository
public class ContactRepository extends TenantAwareRepository {

    private final JdbcTemplate jdbcTemplate;

    public ContactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================================
    // Operacje na recording_url
    // =========================================================================

    /**
     * Aktualizuje URL nagrania dla podanego kontaktu.
     *
     * <p>Używane przez {@link com.contactcenter.domain.service.RecordingService}
     * po pomyślnym uploadzie nagrania do S3.
     *
     * @param contactId    UUID kontaktu
     * @param tenantId     UUID tenanta (do weryfikacji RLS i assertSameTenant)
     * @param recordingUrl ścieżka S3 w formacie {@code /{tenantId}/{year}/{month}/{contactId}.mp3}
     */
    @Transactional
    public void updateRecordingUrl(UUID contactId, UUID tenantId, String recordingUrl) {
        assertSameTenant(tenantId, contactId);
        setTenantContextInDb(tenantId);

        int updated = jdbcTemplate.update(
                "UPDATE contact SET recording_url = ?, updated_at = NOW() WHERE contact_id = ? AND tenant_id = ?",
                recordingUrl,
                contactId,
                tenantId
        );

        if (updated == 0) {
            log.warn("[ContactRepository] Nie znaleziono kontaktu do aktualizacji recording_url: contactId={}, tenantId={}",
                    contactId, tenantId);
        } else {
            log.debug("[ContactRepository] Zaktualizowano recording_url: contactId={}, url={}",
                    contactId, recordingUrl);
        }
    }

    /**
     * Pobiera ścieżkę S3 nagrania dla danego kontaktu.
     *
     * <p>Używane przez {@link com.contactcenter.domain.service.RecordingService}
     * do pobrania klucza S3 przed generowaniem presigned URL.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (wymagany dla RLS)
     * @return Optional z ścieżką S3 lub empty jeśli nagranie nie istnieje
     */
    @Transactional(readOnly = true)
    public Optional<String> findRecordingUrl(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        return jdbcTemplate.query(
                "SELECT recording_url FROM contact WHERE contact_id = ? AND tenant_id = ?",
                rs -> {
                    if (rs.next()) {
                        return Optional.ofNullable(rs.getString("recording_url"));
                    }
                    return Optional.empty();
                },
                contactId,
                tenantId
        );
    }

    /**
     * Usuwa URL nagrania (ustawia NULL) dla podanego kontaktu.
     *
     * <p>Używane przez {@link com.contactcenter.domain.service.RecordingRetentionJob}
     * po usunięciu pliku z S3.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    @Transactional
    public void clearRecordingUrl(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        jdbcTemplate.update(
                "UPDATE contact SET recording_url = NULL, updated_at = NOW() WHERE contact_id = ? AND tenant_id = ?",
                contactId,
                tenantId
        );

        log.debug("[ContactRepository] Wyczyszczono recording_url: contactId={}", contactId);
    }

    /**
     * Pobiera listę kontaktów z nagraniami, których data zakończenia jest starsza niż podana data.
     *
     * <p>Używane przez job retencji do identyfikacji nagrań do usunięcia.
     * Zapytanie jest ograniczone do tenanta przez RLS (set_tenant_context wywoływany przez callera)
     * oraz przez jawny filtr tenant_id dla bezpieczeństwa.
     *
     * @param tenantId          UUID tenanta
     * @param retentionCutoffSql data graniczna w formacie SQL (np. "NOW() - INTERVAL '90 days'")
     *                           – parametr jest już obliczony przez callera
     * @param cutoffTimestamp   timestamp graniczny (ended_at < cutoffTimestamp)
     * @param limit             maksymalna liczba rekordów do przetworzenia w jednej iteracji
     * @return lista par (contactId, recordingUrl) do usunięcia
     */
    @Transactional(readOnly = true)
    public java.util.List<ContactRecordingEntry> findExpiredRecordings(
            UUID tenantId,
            java.time.Instant cutoffTimestamp,
            int limit
    ) {
        setTenantContextInDb(tenantId);

        return jdbcTemplate.query(
                """
                SELECT contact_id, recording_url
                FROM contact
                WHERE tenant_id = ?
                  AND recording_url IS NOT NULL
                  AND ended_at IS NOT NULL
                  AND ended_at < ?
                LIMIT ?
                """,
                (rs, rowNum) -> new ContactRecordingEntry(
                        UUID.fromString(rs.getString("contact_id")),
                        rs.getString("recording_url")
                ),
                tenantId,
                java.sql.Timestamp.from(cutoffTimestamp),
                limit
        );
    }

    /**
     * Pobiera wszystkie tenantId, które posiadają kontakty z nagraniami.
     *
     * <p>Używane przez job retencji do iterowania po tenantach.
     * Zapytanie jest globalne (SUPERUSER) – brak RLS kontekstu.
     * Wymaga bezpośredniego dostępu do tabeli (pomija RLS przez jawny SQL).
     *
     * <p><strong>Uwaga bezpieczeństwa:</strong> Ta metoda jest wywoływana tylko przez
     * {@code @Scheduled} job z uprawnieniami administratorskimi. Nigdy nie jest
     * eksponowana przez API.
     *
     * @return lista unikalnych tenant_id posiadających nagrania
     */
    @Transactional(readOnly = true)
    public java.util.List<UUID> findTenantsWithRecordings() {
        // Brak setTenantContextInDb() – zapytanie globalne dla jobu retencji
        // RLS jest pomijane przez jawne zapytanie (potrzebujemy wszystkich tenantów)
        return jdbcTemplate.query(
                """
                SELECT DISTINCT tenant_id
                FROM contact
                WHERE recording_url IS NOT NULL
                  AND ended_at IS NOT NULL
                """,
                (rs, rowNum) -> UUID.fromString(rs.getString("tenant_id"))
        );
    }

    // =========================================================================
    // Inner record
    // =========================================================================

    /**
     * Para (contactId, recordingUrl) zwracana przez findExpiredRecordings.
     */
    public record ContactRecordingEntry(UUID contactId, String recordingUrl) {}
}
