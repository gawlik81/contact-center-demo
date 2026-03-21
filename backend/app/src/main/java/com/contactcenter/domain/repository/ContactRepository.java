package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.Contact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium kontaktów – CRUD (BE-027) i operacje związane z nagraniami (BE-010).
 *
 * <p>Tabela {@code contact} jest partycjonowana RANGE po {@code started_at} z PK
 * {@code (contact_id, started_at)}. PostgreSQL nie obsługuje standardowych JPA INSERT
 * na tabelach partycjonowanych z PK zawierającym kolumnę partycjonowania.
 * Dlatego operacje zapisu używają natywnego SQL przez {@link jakarta.persistence.EntityManager}.
 *
 * <p>Operacje na nagraniach (BE-010) używają {@link JdbcTemplate} – zachowane dla kompatybilności
 * z istniejącymi serwisami.
 *
 * <p>Operacje na RLS:
 * Metody publiczne wywołują {@link #setTenantContextInDb()} przed każdą operacją DB,
 * aby PostgreSQL Row Level Security mogło filtrować dane per tenant.
 */
@Slf4j
@Repository
public class ContactRepository extends TenantAwareRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ContactRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // BE-027: Odczyt kontaktów
    // =========================================================================

    /**
     * Pobiera kontakt po ID z zabezpieczeniem cross-tenant.
     *
     * <p>Używa JPQL – Hibernate odpytuje tabelę nadrzędną {@code contact},
     * która automatycznie deleguje do właściwej partycji.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return Optional z kontaktem lub empty gdy nie istnieje lub inny tenant
     */
    @Transactional(readOnly = true)
    public Optional<Contact> findById(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        List<Contact> results = em.createQuery(
                        "SELECT c FROM Contact c WHERE c.contactId = :contactId AND c.tenantId = :tenantId",
                        Contact.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // =========================================================================
    // BE-027: Paginacja z filtrami
    // =========================================================================

    /**
     * Pobiera paginowaną listę kontaktów tenanta z opcjonalnymi filtrami.
     *
     * <p>Używa natywnego SQL z dynamicznymi warunkami WHERE. JPQL z opcjonalnymi
     * parametrami UUID/String i {@code :param IS NULL} powoduje błąd PostgreSQL
     * {@code lower(bytea) does not exist} przy Hibernate 6 – stąd natywny SQL
     * z jawnym CAST i null-check po stronie Java.
     *
     * <p>Sortowanie: {@code started_at DESC} – najnowsze kontakty pierwsze.
     *
     * @param tenantId   UUID tenanta
     * @param agentId    filtr po agencie (null = wszystkie)
     * @param customerId filtr po kliencie (null = wszystkie)
     * @param status     filtr po statusie (null = wszystkie)
     * @param channel    filtr po kanale (null = wszystkie)
     * @param dateFrom   filtr od daty started_at (null = bez ograniczenia)
     * @param dateTo     filtr do daty started_at (null = bez ograniczenia)
     * @param page       numer strony (0-based)
     * @param size       rozmiar strony
     * @return lista kontaktów spełniających kryteria
     */
    @Transactional(readOnly = true)
    public List<Contact> findContacts(UUID tenantId, UUID agentId, UUID customerId,
                                      String status, String channel,
                                      Instant dateFrom, Instant dateTo,
                                      int page, int size) {
        setTenantContextInDb(tenantId);

        int offset = page * size;
        log.debug("[ContactRepo] Lista kontaktów: tenant={}, agentId={}, customerId={}, status={}, " +
                  "channel={}, page={}, size={}",
                  tenantId, agentId, customerId, status, channel, page, size);

        StringBuilder sql = buildBaseSelectSql();
        Map<String, Object> params = buildBaseParams(tenantId);
        appendFilterConditions(sql, params, agentId, customerId, status, channel, dateFrom, dateTo);
        sql.append(" ORDER BY started_at DESC LIMIT :size OFFSET :offset");
        params.put("size", size);
        params.put("offset", offset);

        @SuppressWarnings("unchecked")
        List<Contact> results = buildTypedNativeQuery(sql.toString(), Contact.class, params).getResultList();

        log.debug("[ContactRepo] Znaleziono {} kontaktów (page={}, size={})", results.size(), page, size);
        return results;
    }

    /**
     * Zlicza kontakty tenanta z opcjonalnymi filtrami – do metadanych paginacji.
     *
     * @param tenantId   UUID tenanta
     * @param agentId    filtr po agencie (null = wszystkie)
     * @param customerId filtr po kliencie (null = wszystkie)
     * @param status     filtr po statusie (null = wszystkie)
     * @param channel    filtr po kanale (null = wszystkie)
     * @param dateFrom   filtr od daty started_at (null = bez ograniczenia)
     * @param dateTo     filtr do daty started_at (null = bez ograniczenia)
     * @return łączna liczba kontaktów spełniających kryteria
     */
    @Transactional(readOnly = true)
    public long countContacts(UUID tenantId, UUID agentId, UUID customerId,
                              String status, String channel,
                              Instant dateFrom, Instant dateTo) {
        setTenantContextInDb(tenantId);

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM contact WHERE tenant_id = CAST(:tenantId AS uuid)");
        Map<String, Object> params = buildBaseParams(tenantId);
        appendFilterConditions(sql, params, agentId, customerId, status, channel, dateFrom, dateTo);

        Number count = (Number) buildTypedNativeQuery(sql.toString(), null, params).getSingleResult();
        return count.longValue();
    }

    /**
     * Pobiera historię kontaktów klienta z paginacją.
     *
     * <p>Używa indeksu {@code idx_contact_customer_history} (tenant_id, customer_id, started_at DESC).
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @param page       numer strony (0-based)
     * @param size       rozmiar strony
     * @return lista kontaktów klienta posortowana od najnowszych
     */
    @Transactional(readOnly = true)
    public List<Contact> findByCustomerId(UUID customerId, UUID tenantId, int page, int size) {
        setTenantContextInDb(tenantId);

        int offset = page * size;
        log.debug("[ContactRepo] Historia klienta: customerId={}, tenant={}, page={}, size={}",
                customerId, tenantId, page, size);

        @SuppressWarnings("unchecked")
        List<Contact> results = em.createNativeQuery(
                        """
                        SELECT * FROM contact
                        WHERE tenant_id  = CAST(:tenantId AS uuid)
                          AND customer_id = CAST(:customerId AS uuid)
                        ORDER BY started_at DESC
                        LIMIT :size OFFSET :offset
                        """,
                        Contact.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("customerId", customerId.toString())
                .setParameter("size", size)
                .setParameter("offset", offset)
                .getResultList();

        log.debug("[ContactRepo] Znaleziono {} kontaktów dla klienta {}", results.size(), customerId);
        return results;
    }

    /**
     * Zlicza kontakty klienta – do metadanych paginacji historii.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @return łączna liczba kontaktów klienta
     */
    @Transactional(readOnly = true)
    public long countByCustomerId(UUID customerId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(
                        """
                        SELECT COUNT(*) FROM contact
                        WHERE tenant_id  = CAST(:tenantId AS uuid)
                          AND customer_id = CAST(:customerId AS uuid)
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("customerId", customerId.toString())
                .getSingleResult();

        return count.longValue();
    }

    // =========================================================================
    // BE-027: Zapis – natywny INSERT (wymagany dla tabel partycjonowanych)
    // =========================================================================

    /**
     * Tworzy nowy kontakt przez natywny INSERT.
     *
     * <p>Standardowy JPA persist nie działa na tabelach partycjonowanych PostgreSQL
     * z PK zawierającym kolumnę partycjonowania {@code started_at}.
     * Natywny INSERT pozwala PostgreSQL wybrać właściwą partycję na podstawie {@code started_at}.
     *
     * <p>Przed zapisem wywołuje {@code assertSameTenant()} dla bezpieczeństwa cross-tenant.
     *
     * <p><strong>UWAGA dot. typów kolumn:</strong> {@code channel}, {@code direction} i {@code status}
     * były ENUMami w V007, ale V025 skonwertowało je do {@code VARCHAR + CHECK constraint}.
     * Dlatego INSERT używa {@code CAST(:x AS VARCHAR)} zamiast dawnych typów
     * {@code contact_channel}, {@code contact_direction}, {@code contact_status} – te typy
     * nie istnieją po V025 i spowodowałyby błąd {@code type does not exist}.
     *
     * @param contact encja kontaktu do zapisu (contactId musi być ustawiony)
     * @return przekazana encja (trigger oblicza duration_seconds przy UPDATE)
     */
    @Transactional
    public Contact insert(Contact contact) {
        assertSameTenant(contact.getTenantId());
        setTenantContextInDb(contact.getTenantId());

        log.info("[ContactRepo] Tworzenie kontaktu: tenant={}, channel={}, direction={}",
                contact.getTenantId(), contact.getChannel(), contact.getDirection());

        em.createNativeQuery("""
                INSERT INTO contact (
                    contact_id, tenant_id, customer_id, agent_id, queue_id, campaign_id,
                    channel, direction, status, remote_address,
                    queued_at, assigned_at, started_at, ended_at,
                    duration_seconds, disposition_code, recording_url,
                    channel_metadata, created_at, updated_at
                ) VALUES (
                    CAST(:contactId AS uuid),
                    CAST(:tenantId AS uuid),
                    CAST(:customerId AS uuid),
                    CAST(:agentId AS uuid),
                    CAST(:queueId AS uuid),
                    CAST(:campaignId AS uuid),
                    CAST(:channel AS VARCHAR),
                    CAST(:direction AS VARCHAR),
                    CAST(:status AS VARCHAR),
                    :remoteAddress,
                    :queuedAt,
                    :assignedAt,
                    :startedAt,
                    :endedAt,
                    :durationSeconds,
                    :dispositionCode,
                    :recordingUrl,
                    CAST(:channelMetadata AS jsonb),
                    :createdAt,
                    :updatedAt
                )
                """)
                .setParameter("contactId", contact.getContactId().toString())
                .setParameter("tenantId", contact.getTenantId().toString())
                .setParameter("customerId", uuidToString(contact.getCustomerId()))
                .setParameter("agentId", uuidToString(contact.getAgentId()))
                .setParameter("queueId", uuidToString(contact.getQueueId()))
                .setParameter("campaignId", uuidToString(contact.getCampaignId()))
                .setParameter("channel", contact.getChannel())
                .setParameter("direction", contact.getDirection())
                .setParameter("status", contact.getStatus())
                .setParameter("remoteAddress", contact.getRemoteAddress())
                .setParameter("queuedAt", contact.getQueuedAt())
                .setParameter("assignedAt", contact.getAssignedAt())
                .setParameter("startedAt", contact.getStartedAt())
                .setParameter("endedAt", contact.getEndedAt())
                .setParameter("durationSeconds", contact.getDurationSeconds())
                .setParameter("dispositionCode", contact.getDispositionCode())
                .setParameter("recordingUrl", contact.getRecordingUrl())
                .setParameter("channelMetadata", channelMetadataToJson(contact.getChannelMetadata()))
                .setParameter("createdAt", contact.getCreatedAt())
                .setParameter("updatedAt", contact.getUpdatedAt())
                .executeUpdate();

        log.info("[ContactRepo] Kontakt utworzony: contactId={}, tenant={}",
                contact.getContactId(), contact.getTenantId());
        return contact;
    }

    // =========================================================================
    // BE-027: Aktualizacja – natywny UPDATE
    // =========================================================================

    /**
     * Aktualizuje istniejący kontakt przez natywny UPDATE.
     *
     * <p>Trigger {@code fn_contact_on_update} automatycznie:
     * <ul>
     *   <li>Ustawia {@code updated_at = NOW()}</li>
     *   <li>Oblicza {@code duration_seconds} gdy ustawiamy {@code ended_at}</li>
     * </ul>
     *
     * <p>Klucz partycji {@code started_at} jest wymagany w WHERE – pozwala PostgreSQL
     * ograniczyć UPDATE do jednej partycji bez skanowania wszystkich.
     *
     * <p>{@code status} to {@code VARCHAR} po V025 – używamy {@code CAST(:status AS VARCHAR)}.
     *
     * @param contact encja kontaktu z wypełnionym contactId i startedAt
     * @return liczba zaktualizowanych wierszy (0 = kontakt nie istnieje)
     */
    @Transactional
    public int update(Contact contact) {
        assertSameTenant(contact.getTenantId());
        setTenantContextInDb(contact.getTenantId());

        log.debug("[ContactRepo] Aktualizacja kontaktu: contactId={}, tenant={}",
                contact.getContactId(), contact.getTenantId());

        int updated = em.createNativeQuery("""
                UPDATE contact SET
                    agent_id          = CAST(:agentId AS uuid),
                    status            = CAST(:status AS VARCHAR),
                    assigned_at       = :assignedAt,
                    ended_at          = :endedAt,
                    remote_address    = :remoteAddress,
                    disposition_code  = :dispositionCode,
                    channel_metadata  = CAST(:channelMetadata AS jsonb)
                WHERE contact_id = CAST(:contactId AS uuid)
                  AND tenant_id  = CAST(:tenantId AS uuid)
                  AND started_at = :startedAt
                """)
                .setParameter("agentId", uuidToString(contact.getAgentId()))
                .setParameter("status", contact.getStatus())
                .setParameter("assignedAt", contact.getAssignedAt())
                .setParameter("endedAt", contact.getEndedAt())
                .setParameter("remoteAddress", contact.getRemoteAddress())
                .setParameter("dispositionCode", contact.getDispositionCode())
                .setParameter("channelMetadata", channelMetadataToJson(contact.getChannelMetadata()))
                .setParameter("contactId", contact.getContactId().toString())
                .setParameter("tenantId", contact.getTenantId().toString())
                .setParameter("startedAt", contact.getStartedAt())
                .executeUpdate();

        log.debug("[ContactRepo] Zaktualizowano {} wierszy dla contactId={}", updated, contact.getContactId());

        // Wyczyść L1 cache Hibernate po natywnym UPDATE – trigger DB mógł zmienić
        // duration_seconds i updated_at. Bez flush+clear kolejny JPQL select trafił
        // w cache i zwróciłby stan sprzed UPDATE.
        em.flush();
        em.clear();

        return updated;
    }

    // =========================================================================
    // Metody pomocnicze (BE-027)
    // =========================================================================

    private StringBuilder buildBaseSelectSql() {
        return new StringBuilder("SELECT * FROM contact WHERE tenant_id = CAST(:tenantId AS uuid)");
    }

    private Map<String, Object> buildBaseParams(UUID tenantId) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId.toString());
        return params;
    }

    private void appendFilterConditions(StringBuilder sql, Map<String, Object> params,
                                        UUID agentId, UUID customerId,
                                        String status, String channel,
                                        Instant dateFrom, Instant dateTo) {
        if (agentId != null) {
            sql.append(" AND agent_id = CAST(:agentId AS uuid)");
            params.put("agentId", agentId.toString());
        }
        if (customerId != null) {
            sql.append(" AND customer_id = CAST(:customerId AS uuid)");
            params.put("customerId", customerId.toString());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = CAST(:status AS VARCHAR)");
            params.put("status", status);
        }
        if (channel != null && !channel.isBlank()) {
            sql.append(" AND channel = CAST(:channel AS VARCHAR)");
            params.put("channel", channel);
        }
        if (dateFrom != null) {
            sql.append(" AND started_at >= :dateFrom");
            params.put("dateFrom", dateFrom);
        }
        if (dateTo != null) {
            sql.append(" AND started_at <= :dateTo");
            params.put("dateTo", dateTo);
        }
    }

    @SuppressWarnings("unchecked")
    private jakarta.persistence.Query buildTypedNativeQuery(String sql, Class<?> resultClass,
                                                             Map<String, Object> params) {
        jakarta.persistence.Query query = resultClass != null
                ? em.createNativeQuery(sql, resultClass)
                : em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return query;
    }

    private String uuidToString(UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }

    /**
     * Serializuje mapę channelMetadata do JSON string dla natywnych zapytań.
     *
     * <p>Używa {@link ObjectMapper} (Spring auto-konfiguruje go jako bean) zamiast
     * ręcznej serializacji. Poprzednia implementacja obsługiwała tylko płaskie typy
     * (String, Number, Boolean) i nie escapowała znaków kontrolnych Unicode –
     * wartości zagnieżdżone (List, Map) były błędnie serializowane przez {@code val.toString()}.
     *
     * <p>Przy błędzie serializacji zwraca {@code "{}"} i loguje warning – operacja
     * zapisu nie jest przerywana (zgubienie metadanych jest mniej krytyczne niż błąd kontaktu).
     *
     * @param metadata mapa metadanych kanałowych (może być null lub pusta)
     * @return poprawny JSON string do wstawienia jako JSONB w PostgreSQL
     */
    private String channelMetadataToJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("[ContactRepo] Błąd serializacji channelMetadata: {}", e.getMessage());
            return "{}";
        }
    }

    // =========================================================================
    // Aktualizacja statusu kontaktu przez adapter telefonii (mock)
    // =========================================================================

    /**
     * Aktualizuje status kontaktu po zdarzeniu telefonicznym (hangup → COMPLETED).
     *
     * <p>Używane wyłącznie przez {@link com.contactcenter.domain.telephony.MockTelephonyAdapter}
     * przy zamknięciu sesji połączenia. Metoda celowo używa {@link JdbcTemplate} z jawnym
     * {@code tenantId} zamiast {@link jakarta.persistence.EntityManager} z {@code assertSameTenant()},
     * bo adapter może być wywoływany z wątków bez aktywnego {@link com.contactcenter.security.TenantContext}
     * (np. scheduled hangup). Izolacja cross-tenant zapewniania przez warunek {@code AND tenant_id = ?}.
     *
     * <p>Nie rzuca wyjątku gdy kontakt nie istnieje – loguje WARN i kontynuuje.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (zabezpieczenie cross-tenant)
     * @param newStatus nowy status kontaktu (np. "COMPLETED", "ABANDONED")
     * @param endedAt   czas zakończenia (może być null)
     */
    @Transactional
    public void updateContactStatusOnTelephonyEvent(UUID contactId, UUID tenantId,
                                                     String newStatus, Instant endedAt) {
        if (contactId == null || tenantId == null) {
            log.debug("[ContactRepo] updateContactStatusOnTelephonyEvent: pominięto – contactId={}, tenantId={}",
                    contactId, tenantId);
            return;
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE contact
                   SET status   = CAST(? AS VARCHAR),
                       ended_at = ?
                 WHERE contact_id = ?
                   AND tenant_id  = ?
                """,
                newStatus,
                endedAt != null ? java.sql.Timestamp.from(endedAt) : null,
                contactId,
                tenantId
        );

        if (updated == 0) {
            log.warn("[ContactRepo] updateContactStatusOnTelephonyEvent: kontakt nie znaleziony: " +
                     "contactId={}, tenantId={}", contactId, tenantId);
        } else {
            log.info("[ContactRepo] Status kontaktu zaktualizowany: contactId={}, status={}, endedAt={}",
                    contactId, newStatus, endedAt);
        }
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
        assertSameTenant(tenantId, contactId);
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
     * <p><strong>WYŁĄCZNIE DLA SCHEDULED JOB ({@code RecordingRetentionJob}) – POMIJA RLS.</strong>
     * Metoda celowo nie wywołuje {@code setTenantContextInDb()} – zwraca dane wszystkich tenantów.
     * Jeśli zostanie wywołana przez pomyłkę ze ścieżki HTTP, ujawni dane cross-tenant.
     *
     * <p>Asercja w metodzie wymusza brak aktywnego TenantContext – jeśli kontekst HTTP jest
     * aktywny (co oznaczałoby błędne wywołanie z serwisu biznesowego), rzuca wyjątek.
     * Job retencji działa w wątku schedulera bez TenantContext.
     *
     * @return lista unikalnych tenant_id posiadających nagrania
     * @throws IllegalStateException gdy wywoływana z aktywnym TenantContext (błędne użycie)
     */
    @Transactional(readOnly = true)
    public java.util.List<UUID> findTenantsWithRecordings() {
        // Guard: ta metoda pomija RLS – tylko dla scheduled job (brak TenantContext w schedulerze).
        // Jeśli TenantContext jest aktywny – jesteśmy w wątku HTTP, co jest błędem.
        if (com.contactcenter.security.TenantContext.getTenantIdOrNull() != null) {
            throw new IllegalStateException(
                    "[ContactRepository] findTenantsWithRecordings() wywołana z aktywnym " +
                    "TenantContext – ta metoda jest przeznaczona wyłącznie dla scheduled job. " +
                    "Dla zapytań per-tenant użyj findExpiredRecordings(tenantId, ...).");
        }
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
