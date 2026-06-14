package com.contactcenter.domain.contact;

import com.contactcenter.domain.repository.TenantAwareRepository;
import com.contactcenter.domain.service.RoutingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
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
class ContactRepository extends TenantAwareRepository {

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
   * @param tenantId      UUID tenanta
   * @param agentId       filtr po agencie (null = wszystkie)
   * @param customerId    filtr po kliencie (null = wszystkie)
   * @param status        filtr po statusie (null = wszystkie)
   * @param channel       filtr po kanale (null = wszystkie)
   * @param dateFrom      filtr od daty started_at (null = bez ograniczenia)
   * @param dateTo        filtr do daty started_at (null = bez ograniczenia)
   * @param queueId       filtr po kolejce – UUID jako String (null = wszystkie)
   * @param campaignId    filtr po kampanii – UUID jako String (null = wszystkie)
   * @param remoteAddress filtr ILIKE '%value%' po numerze telefonu (null = bez ograniczenia)
   * @param durationMin   filtr duration_seconds >= durationMin (null = bez ograniczenia)
   * @param durationMax   filtr duration_seconds <= durationMax (null = bez ograniczenia)
   * @param page          numer strony (0-based)
   * @param size          rozmiar strony
   * @return lista kontaktów spełniających kryteria
   */
  @Transactional(readOnly = true)
  public List<Contact> findContacts(UUID tenantId, UUID agentId, UUID customerId,
      String status, String channel,
      java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
      String queueId, String campaignId, String remoteAddress,
      Integer durationMin, Integer durationMax,
      int page, int size) {
    setTenantContextInDb(tenantId);

    int offset = page * size;
    log.debug("[ContactRepo] Lista kontaktów: tenant={}, agentId={}, customerId={}, status={}, " +
            "channel={}, queueId={}, campaignId={}, remoteAddress={}, durationMin={}, durationMax={}, " +
            "page={}, size={}",
        tenantId, agentId, customerId, status, channel,
        queueId, campaignId, remoteAddress, durationMin, durationMax, page, size);

    StringBuilder sql = buildBaseSelectSql();
    Map<String, Object> params = buildBaseParams(tenantId);
    appendFilterConditions(sql, params, agentId, customerId, status, channel, dateFrom, dateTo,
        queueId, campaignId, remoteAddress, durationMin, durationMax);
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
   * @param tenantId      UUID tenanta
   * @param agentId       filtr po agencie (null = wszystkie)
   * @param customerId    filtr po kliencie (null = wszystkie)
   * @param status        filtr po statusie (null = wszystkie)
   * @param channel       filtr po kanale (null = wszystkie)
   * @param dateFrom      filtr od daty started_at (null = bez ograniczenia)
   * @param dateTo        filtr do daty started_at (null = bez ograniczenia)
   * @param queueId       filtr po kolejce – UUID jako String (null = wszystkie)
   * @param campaignId    filtr po kampanii – UUID jako String (null = wszystkie)
   * @param remoteAddress filtr ILIKE '%value%' po numerze telefonu (null = bez ograniczenia)
   * @param durationMin   filtr duration_seconds >= durationMin (null = bez ograniczenia)
   * @param durationMax   filtr duration_seconds <= durationMax (null = bez ograniczenia)
   * @return łączna liczba kontaktów spełniających kryteria
   */
  @Transactional(readOnly = true)
  public long countContacts(UUID tenantId, UUID agentId, UUID customerId,
      String status, String channel,
      java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
      String queueId, String campaignId, String remoteAddress,
      Integer durationMin, Integer durationMax) {
    setTenantContextInDb(tenantId);

    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM contact WHERE tenant_id = CAST(:tenantId AS uuid)");
    Map<String, Object> params = buildBaseParams(tenantId);
    appendFilterConditions(sql, params, agentId, customerId, status, channel, dateFrom, dateTo,
        queueId, campaignId, remoteAddress, durationMin, durationMax);

    Number count = (Number)buildTypedNativeQuery(sql.toString(), null, params).getSingleResult();
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

    Number count = (Number)em.createNativeQuery(
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
  // Twilio session resolution – mapowanie contactId ↔ callSid
  // =========================================================================

  /**
   * Zwraca Twilio Call SID przechowywany w {@code channel_metadata->>'sip_call_id'}
   * dla danego contactId.
   *
   * <p>Używane przez {@code AgentCallController} do tłumaczenia UUID kontaktu
   * (wysyłanego przez frontend) na Twilio CallSid (CA...) wymaganego przez adapter.
   *
   * @param contactId UUID kontaktu
   * @param tenantId  UUID tenanta
   * @return Optional z Twilio CallSid lub empty gdy kontakt nie istnieje lub brak sip_call_id
   */
  @Transactional(readOnly = true)
  public Optional<String> findCallSidByContactId(UUID contactId, UUID tenantId) {
    setTenantContextInDb(tenantId);

    List<?> results = em.createNativeQuery("""
            SELECT channel_metadata->>'sip_call_id'
            FROM contact
            WHERE contact_id = CAST(:contactId AS uuid)
              AND tenant_id  = CAST(:tenantId AS uuid)
            """)
        .setParameter("contactId", contactId.toString())
        .setParameter("tenantId", tenantId.toString())
        .setMaxResults(1)
        .getResultList();

    if (results.isEmpty() || results.get(0) == null) {
      return Optional.empty();
    }
    return Optional.of(results.get(0).toString());
  }

  /**
   * Zwraca contactId dla kontaktu z danym Twilio Call SID w {@code channel_metadata->>'sip_call_id'}.
   *
   * <p>Używane przez {@code TwilioTelephonyAdapter.handleWebhookStatusUpdate()} do wykrycia
   * czy contact został już utworzony przez webhook {@code /voice} przed wywołaniem StatusCallback.
   * Zapobiega tworzeniu duplikatów rekordów contact.
   *
   * @param callSid  Twilio Call SID (CA...)
   * @param tenantId UUID tenanta
   * @return Optional z UUID contactId lub empty gdy brak kontaktu dla tego callSid
   */
  @Transactional(readOnly = true)
  public Optional<UUID> findContactIdByCallSid(String callSid, UUID tenantId) {
    setTenantContextInDb(tenantId);

    List<?> results = em.createNativeQuery("""
            SELECT contact_id
            FROM contact
            WHERE channel_metadata->>'sip_call_id' = :callSid
              AND tenant_id = CAST(:tenantId AS uuid)
            """)
        .setParameter("callSid", callSid)
        .setParameter("tenantId", tenantId.toString())
        .setMaxResults(1)
        .getResultList();

    if (results.isEmpty() || results.get(0) == null) {
      return Optional.empty();
    }
    return Optional.of(UUID.fromString(results.get(0).toString()));
  }

  /**
   * Zapisuje Twilio Conference SID do {@code channel_metadata->>'conference_sid'} kontaktu
   * identyfikowanego przez {@code sip_call_id} (Call SID).
   *
   * <p>Wywoływane przez {@code TwilioWebhookController.handleStatusCallback()} gdy Twilio
   * wysyła StatusCallback z parametrem {@code ConferenceSid} dla połączenia biorącego udział
   * w konferencji. Dzięki temu nagranie konferencji może być później powiązane z kontaktem
   * przez {@code TwilioRecordingDownloadService}, który przy braku callSid odczytuje
   * FriendlyName konferencji przez Twilio API w wątku {@code @Async}.
   *
   * <p>Operacja jest idempotentna – wielokrotne wywołanie z tym samym conferenceSid
   * nie zmienia wyniku (PostgreSQL jsonb {@code ||} operator nadpisuje klucz).
   *
   * @param callSid       Twilio Call SID (CA...) – identyfikuje rekord contact przez sip_call_id
   * @param conferenceSid Twilio Conference SID (CF...) – zapisywany do channel_metadata
   * @param tenantId      UUID tenanta
   */
  @Transactional
  public void updateConferenceSidInMetadata(String callSid, String conferenceSid, UUID tenantId) {
    setTenantContextInDb(tenantId);

    int updated = jdbcTemplate.update(
        """
            UPDATE contact
            SET channel_metadata = channel_metadata || jsonb_build_object('conference_sid', CAST(? AS text)),
                updated_at = NOW()
            WHERE channel_metadata->>'sip_call_id' = ?
              AND tenant_id = CAST(? AS uuid)
            """,
        conferenceSid,
        callSid,
        tenantId.toString()
    );

    if (updated == 0) {
      log.warn("[ContactRepository] Nie znaleziono kontaktu do aktualizacji conference_sid: " +
               "callSid={}, conferenceSid={}, tenantId={}", callSid, conferenceSid, tenantId);
    } else {
      log.debug("[ContactRepository] Zaktualizowano conference_sid w channel_metadata: " +
                "callSid={}, conferenceSid={}", callSid, conferenceSid);
    }
  }

  /**
   * Uzupełnia {@code channel_metadata->>'sip_call_id'} dla kontaktu wychodzącego (OUTBOUND),
   * który został utworzony przed inicjacją połączenia Twilio i nie ma jeszcze callSid.
   *
   * <p>Dla połączeń wychodzących inicjowanych przez dialer rekord {@code contact} jest tworzony
   * przez {@code TwilioTelephonyAdapter.persistOutboundContact()} PRZED wywołaniem Twilio API,
   * więc {@code sip_call_id} jest null. Gdy pierwszy StatusCallback (initiated/ringing) dotrze
   * z Twilio, callSid jest już znany i należy go backfillować, aby:
   * <ul>
   *   <li>{@code findContactIdByCallSid} mógł znaleźć kontakt po callSid</li>
   *   <li>{@code updateConferenceSidInMetadata} mógł powiązać nagranie konferencji</li>
   *   <li>Inne ścieżki korzystające z sip_call_id działały poprawnie</li>
   * </ul>
   *
   * <p>Identyfikuje rekord kontaktu wychodzącego przez jego UUID (contactId) przekazany
   * z sesji połączenia. Operacja jest idempotentna – wielokrotne wywołanie z tym samym
   * callSid nie zmienia wyniku.
   *
   * @param contactId UUID rekordu contact (z sesji połączenia)
   * @param callSid   Twilio Call SID (CA...) do zapisania jako sip_call_id
   * @param tenantId  UUID tenanta
   */
  @Transactional
  public void backfillCallSidInMetadata(UUID contactId, String callSid, UUID tenantId) {
    setTenantContextInDb(tenantId);

    int updated = jdbcTemplate.update(
        """
            UPDATE contact
            SET channel_metadata = COALESCE(channel_metadata, '{}'::jsonb)
                                   || jsonb_build_object('sip_call_id', CAST(? AS text)),
                updated_at = NOW()
            WHERE contact_id = CAST(? AS uuid)
              AND tenant_id  = CAST(? AS uuid)
              AND (channel_metadata->>'sip_call_id' IS NULL
                   OR channel_metadata->>'sip_call_id' = '')
            """,
        callSid,
        contactId.toString(),
        tenantId.toString()
    );

    if (updated > 0) {
      log.info("[ContactRepository] Backfill sip_call_id: contactId={}, callSid={}, tenant={}",
          contactId, callSid, tenantId);
    } else {
      log.debug("[ContactRepository] Backfill sip_call_id pominięty (już ustawiony lub brak rekordu): " +
                "contactId={}, callSid={}", contactId, callSid);
    }
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
                duration_seconds, disposition_code, notes, recording_url,
                channel_metadata, created_at, updated_at, callback_id,
                transferred_from_contact_id, campaign_contact_record_id
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
                :notes,
                :recordingUrl,
                CAST(:channelMetadata AS jsonb),
                :createdAt,
                :updatedAt,
                CAST(:callbackId AS uuid),
                CAST(:transferredFromContactId AS uuid),
                CAST(:campaignContactRecordId AS uuid)
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
        .setParameter("notes", contact.getNotes())
        .setParameter("recordingUrl", contact.getRecordingUrl())
        .setParameter("channelMetadata", channelMetadataToJson(contact.getChannelMetadata()))
        .setParameter("createdAt", contact.getCreatedAt())
        .setParameter("updatedAt", contact.getUpdatedAt())
        .setParameter("callbackId", uuidToString(contact.getCallbackId()))
        .setParameter("transferredFromContactId", uuidToString(contact.getTransferredFromContactId()))
        .setParameter("campaignContactRecordId", uuidToString(contact.getCampaignContactRecordId()))
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
                notes             = :notes,
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
        .setParameter("notes", contact.getNotes())
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

  /**
   * Assigns an agent to a contact that was created without one (e.g. inbound Twilio call).
   *
   * <p>Unlike {@link #update(Contact)}, this method does NOT require {@code started_at}
   * in the WHERE clause because it uses JdbcTemplate with positional parameters.
   * PostgreSQL will scan all partitions by {@code contact_id + tenant_id}.
   * This is intentional – the caller (answer endpoint) only knows the callId/contactId,
   * not {@code started_at}.
   *
   * <p>Sets {@code agent_id}, {@code assigned_at} and {@code status = 'ACTIVE'}.
   *
   * @param contactId  UUID of the contact to update
   * @param tenantId   UUID of the tenant (cross-tenant safety)
   * @param agentId    UUID of the agent who answered the call
   * @param assignedAt timestamp when the agent answered
   * @return number of updated rows (0 = contact not found or wrong tenant)
   */
  @Transactional
  public int assignAgent(UUID contactId, UUID tenantId, UUID agentId, Instant assignedAt) {
    assertSameTenant(tenantId);
    setTenantContextInDb(tenantId);

    log.info("[ContactRepo] Przypisywanie agenta do kontaktu: contactId={}, agentId={}, tenant={}",
        contactId, agentId, tenantId);

    int updated = jdbcTemplate.update("""
            UPDATE contact
            SET agent_id    = ?::uuid,
                assigned_at = ?,
                status      = 'ACTIVE'
            WHERE contact_id = ?::uuid
              AND tenant_id  = ?::uuid
              AND status IN ('QUEUED', 'ACTIVE')
            """,
        agentId.toString(),
        java.sql.Timestamp.from(assignedAt),
        contactId.toString(),
        tenantId.toString()
    );

    // Clear Hibernate L1 cache – native UPDATE via JdbcTemplate bypasses EntityManager
    em.flush();
    em.clear();

    log.debug("[ContactRepo] assignAgent: zaktualizowano {} wierszy dla contactId={}", updated, contactId);
    return updated;
  }

  // =========================================================================
  // Aktualizacja agent_id (attended transfer – przepisanie kontaktu na Agent2)
  // =========================================================================

  /**
   * Aktualizuje agent_id kontaktu bez zmiany statusu.
   *
   * <p>Używane przy attended transfer gdy Agent2 przejmuje kontakt po bridge.
   * W przeciwieństwie do {@link #assignAgent}, nie zmienia statusu kontaktu.
   *
   * @param contactId  UUID kontaktu
   * @param tenantId   UUID tenanta
   * @param newAgentId UUID nowego agenta
   */
  public void updateAgentId(UUID contactId, UUID tenantId, UUID newAgentId) {
    assertSameTenant(tenantId);
    setTenantContextInDb(tenantId);

    jdbcTemplate.update("""
            UPDATE contact
            SET agent_id = ?::uuid
            WHERE contact_id = ?::uuid
              AND tenant_id  = ?::uuid
            """,
        newAgentId.toString(), contactId.toString(), tenantId.toString());

    em.flush();
    em.clear();

    log.debug("[ContactRepo] updateAgentId: contactId={}, newAgentId={}", contactId, newAgentId);
  }

  // =========================================================================
  // Aktualizacja queue_id (BUGFIX: IVR nie zapisywał kolejki do DB)
  // =========================================================================

  /**
   * Aktualizuje {@code queue_id} kontaktu będącego w statusie QUEUED lub IVR.
   *
   * <p>Wywoływana przez {@code IvrEngineService.executeQueueTransfer()} po wyznaczeniu
   * docelowej kolejki z węzła IVR, przed publikacją eventu {@code contact.queued} do RabbitMQ.
   *
   * <p>Bez tej aktualizacji {@code queue_id} pozostaje NULL w bazie danych.
   * Skutkuje to trwałym blokowaniem retry routingu w
   * {@code RoutingService.onAgentStatusChanged()}, który pomija kontakty z {@code queue_id = NULL}.
   *
   * <p><strong>Przejście IVR -&gt; QUEUED:</strong> gdy kontakt jest w statusie {@code IVR}
   * (klient wciąż w drzewie IVR, {@code queued_at = NULL}), ten transfer do kolejki agentów
   * jest momentem faktycznego "wejścia do kolejki" dla KPI ASA – status zmienia się na
   * {@code QUEUED} i {@code queued_at} ustawiane jest na {@code NOW()}. Gdy kontakt jest już
   * w statusie {@code QUEUED} (routing bezpośredni, bez IVR), {@code queued_at} pozostaje
   * niezmienione (ustawione przy utworzeniu kontaktu).
   *
   * <p>Używa JdbcTemplate (bez {@code started_at} w WHERE) – IVR nie zna partycji ({@code started_at})
   * kontaktu, zna tylko {@code contactId}. PostgreSQL przeszuka wszystkie partycje.
   *
   * @param contactId UUID kontaktu
   * @param tenantId  UUID tenanta (cross-tenant safety)
   * @param queueId   UUID docelowej kolejki wyznaczonej przez węzeł IVR
   * @return liczba zaktualizowanych wierszy (0 = kontakt nie istnieje, ma inny tenant lub inny status)
   */
  @Transactional
  public int updateQueueId(UUID contactId, UUID tenantId, UUID queueId) {
    assertSameTenant(tenantId);
    setTenantContextInDb(tenantId);

    int updated = jdbcTemplate.update("""
            UPDATE contact
               SET queue_id    = ?::uuid,
                   status       = CASE WHEN status = 'IVR' THEN 'QUEUED' ELSE status END,
                   queued_at    = CASE WHEN status = 'IVR' THEN NOW() ELSE queued_at END,
                   updated_at  = NOW()
             WHERE contact_id = ?::uuid
               AND tenant_id  = ?::uuid
               AND status IN ('QUEUED', 'IVR')
            """,
        queueId.toString(),
        contactId.toString(),
        tenantId.toString()
    );

    // Wyczyść L1 cache Hibernate – natywny UPDATE przez JdbcTemplate pomija EntityManager
    em.flush();
    em.clear();

    log.info("[ContactRepo] updateQueueId: contactId={}, queueId={}, rows={}",
        contactId, queueId, updated);
    return updated;
  }

  // =========================================================================
  // BE-085: Powiązanie z rekordem listy kampanii
  // =========================================================================

  /**
   * Ustawia {@code campaign_contact_record_id} na kontakcie wychodzącym dialera.
   *
   * <p>Wywoływane przez {@code ProgressiveDialerService} po pomyślnym inicjowaniu
   * połączenia – łączy kontakt z rekordem listy kampanii umożliwiając późniejszy
   * odczyt historii prób wydzwonienia.
   *
   * @param contactId UUID kontaktu
   * @param recordId  UUID rekordu campaign_contact
   * @param tenantId  UUID tenanta (cross-tenant safety)
   * @return liczba zaktualizowanych wierszy
   */
  @Transactional
  public int updateCampaignContactRecordId(UUID contactId, UUID recordId, UUID tenantId) {
    assertSameTenant(tenantId);
    setTenantContextInDb(tenantId);

    int updated = jdbcTemplate.update("""
            UPDATE contact
               SET campaign_contact_record_id = ?::uuid,
                   updated_at = NOW()
             WHERE contact_id = ?::uuid
               AND tenant_id  = ?::uuid
            """,
        recordId.toString(),
        contactId.toString(),
        tenantId.toString()
    );

    em.flush();
    em.clear();

    log.debug("[ContactRepo] updateCampaignContactRecordId: contactId={}, recordId={}, rows={}",
        contactId, recordId, updated);
    return updated;
  }

  /**
   * Zwraca listę kontaktów powiązanych z danym rekordem listy kampanii.
   *
   * <p>Używane przez endpoint historii prób wydzwonienia
   * {@code GET /api/campaigns/{campaignId}/contacts/{recordId}/attempts}.
   *
   * @param campaignContactRecordId UUID rekordu campaign_contact
   * @param tenantId                UUID tenanta
   * @return lista kontaktów posortowana od najnowszych
   */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<Contact> findByCampaignContactRecordId(UUID campaignContactRecordId, UUID tenantId) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] findByCampaignContactRecordId: recordId={}, tenant={}",
        campaignContactRecordId, tenantId);

    return em.createNativeQuery("""
            SELECT * FROM contact
            WHERE tenant_id                    = CAST(:tenantId AS uuid)
              AND campaign_contact_record_id   = CAST(:recordId AS uuid)
            ORDER BY started_at DESC
            """,
            Contact.class)
        .setParameter("tenantId", tenantId.toString())
        .setParameter("recordId", campaignContactRecordId.toString())
        .getResultList();
  }

  // =========================================================================
  // Powiązania z callbackami (BE-043)
  // =========================================================================

  /**
   * Zwraca listę kontaktów powiązanych z danym callbackiem.
   *
   * <p>Kontakt jest powiązany z callbackiem gdy pole {@code callback_id} wskazuje
   * na UUID danego callbacku. Są to kontakty realizacji – tworzone przez dialer
   * przy dzwonieniu do klienta w ramach obsługi callbacku.
   *
   * @param callbackId UUID callbacku (scheduled_callback.callback_id)
   * @param tenantId   UUID tenanta
   * @return lista kontaktów z ustawionym callback_id równym podanemu callbackId
   */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<Contact> findByCallbackId(UUID callbackId, UUID tenantId) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] findByCallbackId: callbackId={}, tenant={}", callbackId, tenantId);

    return em.createNativeQuery("""
            SELECT * FROM contact
            WHERE tenant_id  = CAST(:tenantId AS uuid)
              AND callback_id = CAST(:callbackId AS uuid)
            ORDER BY started_at DESC
            """,
            Contact.class)
        .setParameter("tenantId", tenantId.toString())
        .setParameter("callbackId", callbackId.toString())
        .getResultList();
  }

  // =========================================================================
  // Powiązania po transferze kolejkowym
  // =========================================================================

  /**
   * Zwraca listę kontaktów powstałych z transferu kolejkowego danego kontaktu.
   *
   * <p>Kontakt jest powiązany z kontaktem źródłowym gdy pole
   * {@code transferred_from_contact_id} wskazuje na UUID kontaktu źródłowego.
   * Są to kontakty "dzieci" – tworzone przez TwilioTelephonyAdapter w metodzie
   * {@code transferToQueue()} przy przekierowaniu połączenia do nowej kolejki.
   *
   * @param transferredFromContactId UUID kontaktu źródłowego (contact.contact_id)
   * @param tenantId                 UUID tenanta
   * @return lista kontaktów z ustawionym transferred_from_contact_id równym podanemu UUID
   */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<Contact> findByTransferredFromContactId(UUID transferredFromContactId, UUID tenantId) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] findByTransferredFromContactId: transferredFromContactId={}, tenant={}",
        transferredFromContactId, tenantId);

    return em.createNativeQuery("""
            SELECT * FROM contact
            WHERE tenant_id                    = CAST(:tenantId AS uuid)
              AND transferred_from_contact_id  = CAST(:transferredFromContactId AS uuid)
            ORDER BY started_at DESC
            """,
            Contact.class)
        .setParameter("tenantId", tenantId.toString())
        .setParameter("transferredFromContactId", transferredFromContactId.toString())
        .getResultList();
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
      java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
      String queueId, String campaignId, String remoteAddress,
      Integer durationMin, Integer durationMax) {
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
      params.put("dateFrom", java.sql.Date.valueOf(dateFrom));
    }
    if (dateTo != null) {
      sql.append(" AND started_at < :dateTo");
      params.put("dateTo", java.sql.Date.valueOf(dateTo.plusDays(1)));
    }
    if (queueId != null && !queueId.isBlank()) {
      sql.append(" AND queue_id = CAST(:queueId AS uuid)");
      params.put("queueId", queueId);
    }
    if (campaignId != null && !campaignId.isBlank()) {
      sql.append(" AND campaign_id = CAST(:campaignId AS uuid)");
      params.put("campaignId", campaignId);
    }
    if (remoteAddress != null && !remoteAddress.isBlank()) {
      sql.append(" AND remote_address ILIKE '%' || :remoteAddress || '%'");
      params.put("remoteAddress", remoteAddress);
    }
    if (durationMin != null) {
      sql.append(" AND duration_seconds IS NOT NULL AND duration_seconds >= :durationMin");
      params.put("durationMin", durationMin);
    }
    if (durationMax != null) {
      sql.append(" AND duration_seconds IS NOT NULL AND duration_seconds <= :durationMax");
      params.put("durationMax", durationMax);
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
    }
    catch (JsonProcessingException e) {
      log.warn("[ContactRepo] Błąd serializacji channelMetadata: {}", e.getMessage());
      return "{}";
    }
  }

  // =========================================================================
  // BE-019: Routing – pobieranie oczekujących kontaktów
  // =========================================================================

  /**
   * Pobiera kontakty ze statusem QUEUED dla danego tenanta (oczekujące na przydzielenie agenta).
   *
   * <p>Używane przez {@link RoutingService#onAgentStatusChanged}
   * do retry routingu gdy agent staje się AVAILABLE. Posortowane od najstarszych (FIFO).
   *
   * <p>Limit 50 – chroni przed zbyt dużym batche'm w jednym wywołaniu listenera.
   *
   * @param tenantId UUID tenanta
   * @return lista oczekujących kontaktów posortowana od najstarszych (queuedAt ASC)
   */
  @Transactional(readOnly = true)
  public List<Contact> findQueuedContacts(UUID tenantId) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] Szukam oczekujących kontaktów: tenant={}", tenantId);

    @SuppressWarnings("unchecked")
    List<Contact> results = em.createNativeQuery(
            """
                SELECT * FROM contact
                WHERE tenant_id = CAST(:tenantId AS uuid)
                  AND status    = 'QUEUED'
                ORDER BY queued_at ASC NULLS LAST
                LIMIT 50
                """,
            Contact.class)
        .setParameter("tenantId", tenantId.toString())
        .getResultList();

    log.debug("[ContactRepo] Znaleziono {} oczekujących kontaktów dla tenanta={}", results.size(), tenantId);
    return results;
  }

  // =========================================================================
  // Cleanup – kontakty przeterminowane lub błędne w kolejce
  // =========================================================================

  /**
   * Zwraca kontakty w statusie QUEUED, które są uznawane za "błędne" i powinny zostać
   * zakończone ze statusem ERROR.
   *
   * <p>Kontakt jest uznawany za błędny jeśli spełnia jeden z warunków:
   * <ul>
   *   <li>Timeout: {@code queued_at} starsze niż podany próg ({@code olderThan}) –
   *       klient prawdopodobnie rozłączył się lub połączenie padło.</li>
   *   <li>Błąd telefonii: {@code channel_metadata->>'callStatus'} to
   *       {@code 'failed'}, {@code 'busy'}, {@code 'no-answer'} lub {@code 'canceled'}.</li>
   *   <li>Flaga błędu: {@code channel_metadata->>'error'} rzutowane na boolean = true.</li>
   * </ul>
   *
   * <p>Brak limitu wynikowego – caller ({@link com.contactcenter.domain.contact.ContactService})
   * przetwarza cały batch w jednej transakcji.
   *
   * @param tenantId  UUID tenanta
   * @param olderThan próg czasu – kontakty z {@code queued_at} starszym od tego progu są timeout
   * @return lista kontaktów do zakończenia ze statusem ERROR
   */
  @Transactional(readOnly = true)
  public List<Contact> findStaleQueuedContacts(UUID tenantId, Instant olderThan) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] Szukam przeterminowanych kontaktów QUEUED: tenant={}, olderThan={}",
        tenantId, olderThan);

    @SuppressWarnings("unchecked")
    List<Contact> results = em.createNativeQuery(
            """
                SELECT * FROM contact
                WHERE tenant_id = CAST(:tenantId AS uuid)
                  AND status    = 'QUEUED'
                  AND (
                      queued_at < :olderThan
                      OR channel_metadata->>'callStatus' IN ('failed', 'busy', 'no-answer', 'canceled')
                      OR (channel_metadata->>'error')::boolean = true
                  )
                ORDER BY queued_at ASC
                """,
            Contact.class)
        .setParameter("tenantId", tenantId.toString())
        .setParameter("olderThan", olderThan)
        .getResultList();

    log.debug("[ContactRepo] Znaleziono {} przeterminowanych/błędnych kontaktów dla tenanta={}",
        results.size(), tenantId);
    return results;
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
    }
    else {
      log.info("[ContactRepo] Status kontaktu zaktualizowany: contactId={}, status={}, endedAt={}",
          contactId, newStatus, endedAt);
    }
  }

  /**
   * Atomicznie ustawia status kontaktu tylko jeśli nie jest już w stanie terminalnym.
   * Zapobiega race condition między handlerem hangup (→ COMPLETED) a callbackiem
   * conference-end (→ ABANDONED) — oba mogą działać równocześnie.
   *
   * @return true jeśli status został zmieniony
   */
  public boolean updateContactStatusIfNotTerminal(UUID contactId, UUID tenantId,
      String newStatus, Instant endedAt) {
    if (contactId == null || tenantId == null) {
      return false;
    }

    int updated = jdbcTemplate.update(
        """
            UPDATE contact
               SET status   = CAST(? AS VARCHAR),
                   ended_at = ?
             WHERE contact_id = ?
               AND tenant_id  = ?
               AND status NOT IN ('COMPLETED', 'ABANDONED', 'NOT_REACHED', 'ERROR', 'TRANSFERRED')
            """,
        newStatus,
        endedAt != null ? java.sql.Timestamp.from(endedAt) : null,
        contactId,
        tenantId
    );

    if (updated > 0) {
      log.info("[ContactRepo] Status kontaktu zaktualizowany (conditional): contactId={}, status={}, endedAt={}",
          contactId, newStatus, endedAt);
      return true;
    } else {
      log.debug("[ContactRepo] updateContactStatusIfNotTerminal: pominięto (status terminalny lub brak): contactId={}", contactId);
      return false;
    }
  }

  /**
   * Identyczna logika co {@link #updateContactStatusOnTelephonyEvent}, ale z propagacją
   * {@code REQUIRES_NEW} – zawiesza zewnętrzną transakcję i commituje natychmiast.
   *
   * <p>Używana przez {@code bridgeCalls()} w adapterze Twilio: TRANSFERRED musi być widoczny
   * dla innych wątków (np. callbacku conference-end) jeszcze podczas trwania zewnętrznej
   * transakcji {@code ContactService.bridgeCalls()}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateContactStatusRequiresNew(UUID contactId, UUID tenantId,
      String newStatus, Instant endedAt) {
    if (contactId == null || tenantId == null) {
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
      log.warn("[ContactRepo] updateContactStatusRequiresNew: kontakt nie znaleziony: " +
          "contactId={}, tenantId={}", contactId, tenantId);
    } else {
      log.info("[ContactRepo] Status kontaktu zaktualizowany (REQUIRES_NEW): contactId={}, status={}, endedAt={}",
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
    }
    else {
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
   * @param tenantId           UUID tenanta
   * @param retentionCutoffSql data graniczna w formacie SQL (np. "NOW() - INTERVAL '90 days'")
   *                           – parametr jest już obliczony przez callera
   * @param cutoffTimestamp    timestamp graniczny (ended_at < cutoffTimestamp)
   * @param limit              maksymalna liczba rekordów do przetworzenia w jednej iteracji
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
   * Pobiera listę kluczy S3 nagrań dla wszystkich kontaktów danego klienta.
   *
   * <p>Używane przez {@code GdprService} do usuwania nagrań z S3 podczas
   * anonimizacji klienta (prawo do bycia zapomnianym, RODO Art. 17).
   *
   * @param customerId UUID klienta
   * @param tenantId   UUID tenanta
   * @return lista kluczy S3 (recording_url) – może być pusta gdy brak nagrań
   */
  @Transactional(readOnly = true)
  public java.util.List<String> findRecordingUrlsByCustomer(UUID customerId, UUID tenantId) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepository] Pobieranie recording_url dla klienta: customerId={}, tenant={}",
        customerId, tenantId);

    return jdbcTemplate.query(
        """
            SELECT recording_url
            FROM contact
            WHERE tenant_id   = ?
              AND customer_id = ?
              AND recording_url IS NOT NULL
            """,
        (rs, rowNum) -> rs.getString("recording_url"),
        tenantId,
        customerId
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
  // BE-028: Agregacje raportów agentów
  // =========================================================================

  /**
   * Pobiera paginowane, zagregowane statystyki agentów za dany zakres dat.
   *
   * <p>Grupuje zakończone kontakty (status = COMPLETED) według agenta, dnia i kanału.
   * Zwraca tablice {@code Object[]} z kolumnami w kolejności:
   * {@code [agent_id, agent_name, report_date, contacts_count, avg_handle_time, avg_wait_time, fcr, channel]}.
   *
   * <p>Filtr {@code :agentId IS NULL} obsługiwany po stronie Java przez warunkowe
   * dołączanie klauzul SQL – to samo podejście co w {@link #findContacts} (unikamy
   * błędu {@code lower(bytea) does not exist} Hibernate 6 przy parametrach UUID z null).
   *
   * @param tenantId UUID tenanta
   * @param agentId  filtr po agencie (null = wszystkie)
   * @param queueId  filtr po kolejce (null = wszystkie)
   * @param channel  filtr po kanale (null = wszystkie)
   * @param dateFrom data początku zakresu (włącznie)
   * @param dateTo   data końca zakresu (wyłącznie – zapytanie używa {@code < dateTo + 1})
   * @param offset   offset paginacji
   * @param size     rozmiar strony
   * @return lista wierszy agregacji jako Object[]
   */
  @Transactional(readOnly = true)
  public List<Object[]> findAgentReportRows(UUID tenantId, UUID agentId, UUID queueId,
      String channel,
      java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
      int offset, int size) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] Raport agentów: tenant={}, dateFrom={}, dateTo={}, agentId={}, queueId={}, channel={}",
        tenantId, dateFrom, dateTo, agentId, queueId, channel);

    StringBuilder sql = buildAgentReportBaseSql();
    Map<String, Object> params = new java.util.HashMap<>();
    params.put("tenantId", tenantId.toString());
    params.put("dateFrom", java.sql.Date.valueOf(dateFrom));
    params.put("dateTo", java.sql.Date.valueOf(dateTo.plusDays(1)));

    appendAgentReportFilters(sql, params, agentId, queueId, channel);

    sql.append("""
        GROUP BY u.user_id, u.first_name, u.last_name, u.email, DATE(c.started_at), c.channel
        ORDER BY report_date DESC, contacts_count DESC
        LIMIT :size OFFSET :offset
        """);
    params.put("size", size);
    params.put("offset", offset);

    @SuppressWarnings("unchecked")
    List<Object[]> results = buildTypedNativeQuery(sql.toString(), null, params).getResultList();

    log.debug("[ContactRepo] Raport agentów: znaleziono {} wierszy", results.size());
    return results;
  }

  /**
   * Zlicza łączną liczbę wierszy raportu agentów – do metadanych paginacji.
   *
   * <p>Wykonuje zapytanie COUNT z tą samą logiką filtrowania co
   * {@link #findAgentReportRows}, ale bez GROUP BY (zlicza grupy przez podzapytanie).
   *
   * @param tenantId UUID tenanta
   * @param agentId  filtr po agencie (null = wszystkie)
   * @param queueId  filtr po kolejce (null = wszystkie)
   * @param channel  filtr po kanale (null = wszystkie)
   * @param dateFrom data początku zakresu
   * @param dateTo   data końca zakresu
   * @return łączna liczba wierszy grupowania (agent + dzień + kanał)
   */
  @Transactional(readOnly = true)
  public long countAgentReportRows(UUID tenantId, UUID agentId, UUID queueId,
      String channel,
      java.time.LocalDate dateFrom, java.time.LocalDate dateTo) {
    setTenantContextInDb(tenantId);

    StringBuilder innerSql = buildAgentReportBaseSql();
    Map<String, Object> params = new java.util.HashMap<>();
    params.put("tenantId", tenantId.toString());
    params.put("dateFrom", java.sql.Date.valueOf(dateFrom));
    params.put("dateTo", java.sql.Date.valueOf(dateTo.plusDays(1)));

    appendAgentReportFilters(innerSql, params, agentId, queueId, channel);

    innerSql.append("GROUP BY u.user_id, u.first_name, u.last_name, u.email, DATE(c.started_at), c.channel");

    String countSql = "SELECT COUNT(*) FROM (" + innerSql + ") AS report_rows";

    Number count = (Number)buildTypedNativeQuery(countSql, null, params).getSingleResult();
    return count.longValue();
  }

  // =========================================================================
  // Metody pomocnicze dla raportów (BE-028)
  // =========================================================================

  /**
   * Buduje bazowe zapytanie SELECT dla raportu agentów (bez filtrów opcjonalnych i GROUP BY).
   */
  private StringBuilder buildAgentReportBaseSql() {
    return new StringBuilder("""
        SELECT
            u.user_id::text                                     AS agent_id,
            COALESCE(u.first_name || ' ' || u.last_name,
                     u.email)                                   AS agent_name,
            DATE(c.started_at)                                  AS report_date,
            COUNT(*)                                            AS contacts_count,
            COALESCE(AVG(c.duration_seconds), 0)                AS avg_handle_time,
            COALESCE(AVG(EXTRACT(EPOCH FROM
                (c.assigned_at - c.queued_at))), 0)             AS avg_wait_time,
            COALESCE(AVG(CASE
                WHEN c.disposition_code NOT IN
                     ('CALLBACK', 'TRANSFER', 'ESCALATE')
                THEN 1.0 ELSE 0.0
            END), 0)                                            AS fcr,
            c.channel
        FROM contact c
        JOIN app_user u ON c.agent_id = u.user_id
        WHERE c.tenant_id  = CAST(:tenantId AS uuid)
          AND c.started_at >= :dateFrom
          AND c.started_at <  :dateTo
          AND c.status     IN ('COMPLETED', 'TRANSFERRED')
          AND c.duration_seconds IS NOT NULL
        """);
  }

  /**
   * Dołącza opcjonalne filtry do zapytania raportu agentów.
   */
  private void appendAgentReportFilters(StringBuilder sql, Map<String, Object> params,
      UUID agentId, UUID queueId, String channel) {
    if (agentId != null) {
      sql.append("  AND c.agent_id = CAST(:agentId AS uuid)\n");
      params.put("agentId", agentId.toString());
    }
    if (queueId != null) {
      sql.append("  AND c.queue_id = CAST(:queueId AS uuid)\n");
      params.put("queueId", queueId.toString());
    }
    if (channel != null && !channel.isBlank()) {
      sql.append("  AND c.channel = CAST(:channel AS VARCHAR)\n");
      params.put("channel", channel);
    }
  }

  // =========================================================================
  // EMAIL INBOUND↔OUTBOUND – powiązania przez channelMetadata (BE-040 follow-up)
  // =========================================================================

  /**
   * Zwraca listę kontaktów, w których {@code channel_metadata->>'key' = value}.
   *
   * <p>Używane przez {@code ContactController.getRelatedContacts()} do znajdowania
   * odpowiedzi OUTBOUND powiązanych z danym kontaktem INBOUND EMAIL poprzez
   * {@code channel_metadata->>'inboundContactId'}.
   *
   * <p>Zapytanie natywne – JSONB operator {@code ->>} zwraca wartość jako TEXT,
   * więc porównanie z parametrem String jest bezpieczne bez castowania.
   *
   * @param key      klucz JSON w channel_metadata (np. {@code "inboundContactId"})
   * @param value    oczekiwana wartość tekstowa
   * @param tenantId UUID tenanta (cross-tenant safety)
   * @return lista kontaktów spełniających kryterium (posortowana od najnowszych)
   */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<Contact> findByChannelMetadataValue(String key, String value, UUID tenantId) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] findByChannelMetadataValue: key={}, value={}, tenant={}", key, value, tenantId);

    return em.createNativeQuery("""
            SELECT * FROM contact
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND channel_metadata->>:key = :value
            ORDER BY started_at DESC
            """,
            Contact.class)
        .setParameter("tenantId", tenantId.toString())
        .setParameter("key", key)
        .setParameter("value", value)
        .getResultList();
  }

  // =========================================================================
  // BE-021: Wait Time Estimation – zapytania agregujące
  // =========================================================================

  /**
   * Oblicza średni czas obsługi kontaktu (handle time) w sekundach dla danej kolejki
   * na podstawie zakończonych kontaktów z ostatnich 7 dni.
   *
   * <p>Używa EXTRACT(EPOCH FROM (ended_at - started_at)) do obliczenia czasu w sekundach.
   * COALESCE zwraca wartość domyślną 300s (5 minut) gdy brak danych historycznych.
   *
   * <p>Nie wywołuje {@code setTenantContextInDb()} – metoda wywoływana z kontekstu
   * scheduled job iterującego po tenantach. Filtr {@code tenant_id} zapewnia izolację.
   *
   * @param tenantId UUID tenanta
   * @param queueId  UUID kolejki
   * @return średni czas obsługi w sekundach (domyślnie 300 jeśli brak danych)
   */
  @Transactional(readOnly = true)
  public double getAvgHandleTimeSeconds(UUID tenantId, UUID queueId) {
    log.debug("[ContactRepo] AVG handle time: tenant={}, queue={}", tenantId, queueId);

    Number result = (Number)em.createNativeQuery(
            """
                SELECT COALESCE(
                    AVG(EXTRACT(EPOCH FROM (ended_at - started_at))),
                    300
                )
                FROM contact
                WHERE tenant_id  = CAST(:tenantId AS uuid)
                  AND queue_id   = CAST(:queueId  AS uuid)
                  AND started_at >= NOW() - INTERVAL '7 days'
                  AND ended_at IS NOT NULL
                """)
        .setParameter("tenantId", tenantId.toString())
        .setParameter("queueId", queueId.toString())
        .getSingleResult();

    double avg = result != null ? result.doubleValue() : 300.0;
    log.debug("[ContactRepo] AVG handle time dla queue={}: {}s", queueId, avg);
    return avg;
  }

  /**
   * Zlicza kontakty w statusie QUEUED dla danej kolejki tenanta.
   *
   * <p>Używane przez {@link com.contactcenter.domain.service.WaitTimeEstimationService}
   * do wyznaczania liczby oczekujących kontaktów przy obliczaniu EWT.
   *
   * <p>Nie wywołuje {@code setTenantContextInDb()} – analogicznie do
   * {@link #getAvgHandleTimeSeconds}, izolacja zapewniana przez jawny filtr tenant_id.
   *
   * @param tenantId UUID tenanta
   * @param queueId  UUID kolejki
   * @return liczba kontaktów ze statusem QUEUED
   */
  @Transactional(readOnly = true)
  public int countWaitingByQueueId(UUID tenantId, UUID queueId) {
    log.debug("[ContactRepo] Liczba oczekujących: tenant={}, queue={}", tenantId, queueId);

    Number count = (Number)em.createNativeQuery(
            """
                SELECT COUNT(*)
                FROM contact
                WHERE tenant_id  = CAST(:tenantId AS uuid)
                  AND queue_id   = CAST(:queueId  AS uuid)
                  AND status     = 'QUEUED'
                """)
        .setParameter("tenantId", tenantId.toString())
        .setParameter("queueId", queueId.toString())
        .getSingleResult();

    int waiting = count != null ? count.intValue() : 0;
    log.debug("[ContactRepo] Oczekujących w queue={}: {}", queueId, waiting);
    return waiting;
  }

  /**
   * Oblicza średni czas oczekiwania (w sekundach) kontaktów AKTUALNIE czekających
   * w kolejkach tenanta (status QUEUED), na podstawie {@code NOW() - queued_at}.
   *
   * <p>Odzwierciedla rzeczywisty czas oczekiwania "na żywo" – jeśli w kolejce
   * czeka jeden klient od kilku minut, zwrócona wartość odda ten czas.
   *
   * <p>Warunek {@code queue_id IS NOT NULL} jest kluczowy: kontakt jest tworzony ze
   * statusem QUEUED i {@code queued_at = NOW()} już przy odebraniu połączenia przez
   * webhook (zanim klient wejdzie do IVR). {@code queue_id} jest ustawiane dopiero
   * przy transferze IVR → kolejka agentów ({@code QUEUE_TRANSFER}). Bez tego warunku
   * KPI liczyłoby też czas spędzony w IVR jako "czas oczekiwania na agenta".
   *
   * <p>Nie wywołuje {@code setTenantContextInDb()} – analogicznie do
   * {@link #getAvgHandleTimeSeconds}, izolacja zapewniana przez jawny filtr tenant_id.
   *
   * @param tenantId UUID tenanta
   * @return średni czas oczekiwania w sekundach (0.0 jeśli brak kontaktów QUEUED)
   */
  @Transactional(readOnly = true)
  public double getAvgCurrentWaitSeconds(UUID tenantId) {
    log.debug("[ContactRepo] AVG current wait time: tenant={}", tenantId);

    Number result = (Number)em.createNativeQuery(
            """
                SELECT COALESCE(
                    AVG(EXTRACT(EPOCH FROM (NOW() - queued_at))),
                    0
                )
                FROM contact
                WHERE tenant_id = CAST(:tenantId AS uuid)
                  AND status    = 'QUEUED'
                  AND queued_at IS NOT NULL
                  AND queue_id  IS NOT NULL
                """)
        .setParameter("tenantId", tenantId.toString())
        .getSingleResult();

    double avg = result != null ? result.doubleValue() : 0.0;
    log.debug("[ContactRepo] AVG current wait time dla tenant={}: {}s", tenantId, avg);
    return avg;
  }

  // =========================================================================
  // BE-018: Social Media – wyszukiwanie aktywnych kontaktów
  // =========================================================================

  /**
   * Szuka aktywnego kontaktu social media dla danego nadawcy.
   *
   * <p>Aktywny kontakt social to kontakt ze statusem QUEUED lub ACTIVE,
   * z kanałem odpowiadającym platformie (SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP)
   * i remote_address równym zewnętrznemu ID nadawcy.
   *
   * <p>Używane przez {@code SocialMessageService} do dołączania nowych wiadomości
   * do istniejącej rozmowy zamiast tworzenia nowego kontaktu za każdym razem.
   *
   * @param tenantId          UUID tenanta
   * @param senderExternalId  ID nadawcy na platformie (np. Facebook user ID)
   * @param channel           kanał (np. "SOCIAL_FACEBOOK")
   * @return Optional z aktywnym kontaktem lub empty gdy brak
   */
  @Transactional(readOnly = true)
  public Optional<Contact> findActiveSocialContact(UUID tenantId, String senderExternalId, String channel) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] Szukam aktywnego kontaktu social: tenant={}, sender={}, channel={}",
        tenantId, senderExternalId, channel);

    @SuppressWarnings("unchecked")
    List<Contact> results = em.createNativeQuery(
            """
                SELECT * FROM contact
                WHERE tenant_id     = CAST(:tenantId AS uuid)
                  AND channel       = CAST(:channel AS VARCHAR)
                  AND remote_address = :senderExternalId
                  AND status        IN ('QUEUED', 'ACTIVE')
                LIMIT 1
                """,
            Contact.class)
        .setParameter("tenantId", tenantId.toString())
        .setParameter("channel", channel)
        .setParameter("senderExternalId", senderExternalId)
        .getResultList();

    log.debug("[ContactRepo] Aktywny kontakt social {}: {}",
        senderExternalId, results.isEmpty() ? "nie znaleziono" : results.get(0).getContactId());

    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  // =========================================================================
  // BE-046: ASSIGNED status monitoring (ContactAssignmentMonitor)
  // =========================================================================

  /**
   * Zwraca kontakty ze statusem ASSIGNED, których assigned_at jest starsze niż podany próg.
   *
   * <p>Zapytanie cross-tenant (bez wywołania {@code setTenantContextInDb()}) – używane
   * wyłącznie przez wewnętrzny scheduler {@code ContactAssignmentMonitor}. Aplikacja łączy
   * się jako user {@code postgres} (superuser), który domyślnie omija RLS PostgreSQL.
   * Nie wolno wywoływać tej metody ze ścieżki HTTP – ujawniłoby dane cross-tenant.
   *
   * @param assignedBefore próg czasowy – kontakty z assigned_at starszym od tego progu
   * @return lista "przestarzałych" kontaktów ASSIGNED (posortowana od najstarszych)
   */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<Contact> findStaleAssignedContacts(Instant assignedBefore) {
    log.debug("[ContactRepo] findStaleAssignedContacts: assignedBefore={}", assignedBefore);

    // Zapytanie JPQL bez setTenantContextInDb() – cross-tenant dla schedulera.
    // Postgres superuser omija RLS, więc zwraca rekordy wszystkich tenantów.
    List<Contact> results = em.createQuery(
            "SELECT c FROM Contact c WHERE c.status = 'ASSIGNED' AND c.assignedAt < :cutoff",
            Contact.class)
        .setParameter("cutoff", assignedBefore)
        .getResultList();

    log.debug("[ContactRepo] findStaleAssignedContacts: znaleziono {} kontaktów", results.size());
    return results;
  }

  /**
   * Zwraca kontakt aktualnie przypisany (status ASSIGNED) do danego agenta.
   *
   * <p>Używane przez endpoint {@code GET /api/agent/me/assigned-contact} do recovery
   * po reconnect WebSocket – agent może pobrać informacje o oczekującym połączeniu
   * przydzielonym przez routing, jeśli WS event nie dotarł przy pierwszym połączeniu.
   *
   * @param agentId  UUID agenta
   * @param tenantId UUID tenanta
   * @return Optional z kontaktem ASSIGNED lub empty gdy brak
   */
  @Transactional(readOnly = true)
  public Optional<Contact> findAssignedContactForAgent(UUID agentId, UUID tenantId) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] findAssignedContactForAgent: agentId={}, tenant={}", agentId, tenantId);

    @SuppressWarnings("unchecked")
    List<Contact> results = em.createQuery(
            "SELECT c FROM Contact c WHERE c.agentId = :agentId AND c.tenantId = :tenantId " +
            "AND c.status = 'ASSIGNED'",
            Contact.class)
        .setParameter("agentId", agentId)
        .setParameter("tenantId", tenantId)
        .setMaxResults(1)
        .getResultList();

    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  // =========================================================================
  // Agent queue view – dane potrzebne do sidebaru agenta
  // =========================================================================

  /**
   * Zwraca listę oczekujących kontaktów (status QUEUED) z detalami potrzebnymi
   * do wyświetlenia w sidebarze agenta.
   *
   * <p>Zapytanie natywne z LEFT JOIN do {@code queue} i {@code customer}.
   * Gdy klient jest znany – zwraca imię i nazwisko; w przeciwnym razie {@code remote_address}.
   * Limit 50 – spójny z {@link #findQueuedContacts}.
   *
   * @param tenantId UUID tenanta
   * @return lista projekcji {@link QueuedContactView} posortowana wg queued_at ASC
   */
  @Transactional(readOnly = true)
  public List<QueuedContactView> findQueuedContactsForAgentView(UUID tenantId) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] findQueuedContactsForAgentView: tenant={}", tenantId);

    List<Object[]> rows = jdbcTemplate.query(
        """
            SELECT
                c.contact_id::text,
                c.channel,
                c.remote_address,
                c.queued_at,
                COALESCE(
                    NULLIF(TRIM(COALESCE(cu.first_name,'') || ' ' || COALESCE(cu.last_name,'')), ''),
                    c.remote_address
                ) AS customer_name,
                COALESCE(q.name, '') AS queue_name
            FROM contact c
            LEFT JOIN queue q
                   ON c.queue_id  = q.queue_id
                  AND q.tenant_id = c.tenant_id
            LEFT JOIN customer cu
                   ON c.customer_id  = cu.customer_id
                  AND cu.tenant_id   = c.tenant_id
            WHERE c.tenant_id = CAST(? AS uuid)
              AND c.status    = 'QUEUED'
            ORDER BY c.queued_at ASC NULLS LAST
            LIMIT 50
            """,
        (rs, rowNum) -> new Object[]{
            rs.getString("contact_id"),
            rs.getString("channel"),
            rs.getString("remote_address"),
            rs.getTimestamp("queued_at"),
            rs.getString("customer_name"),
            rs.getString("queue_name")
        },
        tenantId.toString()
    );

    List<QueuedContactView> views = rows.stream()
        .map(row -> new QueuedContactView(
            UUID.fromString((String) row[0]),
            (String) row[1],
            (String) row[2],
            row[3] != null ? ((java.sql.Timestamp) row[3]).toInstant() : null,
            (String) row[4],
            (String) row[5]
        ))
        .toList();

    log.debug("[ContactRepo] findQueuedContactsForAgentView: znaleziono {} kontaktów", views.size());
    return views;
  }

  // =========================================================================
  // AgentKPI – statystyki agenta za bieżący dzień (BE-AgentKPI)
  // =========================================================================

  /**
   * Pobiera zagregowane KPI agenta za podany zakres dat (dzień UTC).
   *
   * <p>Zapytanie zawsze zwraca dokładnie jeden wiersz – COUNT może wynosić 0,
   * a COALESCE zapewnia wartości domyślne 0 dla pozostałych kolumn.
   *
   * <p>Kolumny wynikowe (w kolejności):
   * <ol>
   *   <li>{@code contacts_count}   – liczba zakończonych kontaktów</li>
   *   <li>{@code avg_handle_time}  – AVG(duration_seconds), 0 gdy brak</li>
   *   <li>{@code sla_percent}      – % kontaktów z (assigned_at - queued_at) &lt; 30s, 0 gdy brak</li>
   *   <li>{@code work_time_seconds} – sekundy od pierwszego started_at do NOW(), 0 gdy brak</li>
   * </ol>
   *
   * @param agentId  UUID agenta
   * @param tenantId UUID tenanta
   * @param dayStart początek dnia UTC (włącznie)
   * @param dayEnd   koniec dnia UTC (wyłącznie)
   * @return tablica Object[] z czterema wartościami liczbowymi
   */
  @Transactional(readOnly = true)
  public Object[] findAgentKpiToday(UUID agentId, UUID tenantId, Instant dayStart, Instant dayEnd) {
    setTenantContextInDb(tenantId);

    log.debug("[ContactRepo] findAgentKpiToday: agentId={}, tenant={}, dayStart={}, dayEnd={}",
        agentId, tenantId, dayStart, dayEnd);

    return (Object[]) em.createNativeQuery(
            """
                SELECT
                    COUNT(*)                                                        AS contacts_count,
                    COALESCE(AVG(c.duration_seconds), 0)                           AS avg_handle_time,
                    COALESCE(
                        AVG(CASE
                            WHEN c.assigned_at IS NOT NULL AND c.queued_at IS NOT NULL
                             AND EXTRACT(EPOCH FROM (c.assigned_at - c.queued_at)) < 30
                            THEN 1.0 ELSE 0.0
                        END) * 100,
                        0
                    )                                                               AS sla_percent,
                    COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(c.started_at))), 0)   AS work_time_seconds
                FROM contact c
                WHERE c.tenant_id  = CAST(:tenantId AS uuid)
                  AND c.agent_id   = CAST(:agentId AS uuid)
                  AND c.started_at >= :dayStart
                  AND c.started_at <  :dayEnd
                  AND c.status     IN ('COMPLETED', 'TRANSFERRED')
                  AND c.duration_seconds IS NOT NULL
                """)
        .setParameter("tenantId", tenantId.toString())
        .setParameter("agentId", agentId.toString())
        .setParameter("dayStart", dayStart)
        .setParameter("dayEnd", dayEnd)
        .getSingleResult();
  }

  /**
   * Zapisuje transkrypt nagrania (Whisper) do pola {@code notes} kontaktu.
   *
   * <p>Dedykowany natywny UPDATE – nie ładuje całej encji do pamięci.
   * Analogicznie do {@link #updateConferenceSidInMetadata} i {@link #updateRecordingUrl}.
   *
   * <p>Wywoływany przez {@link TwilioRecordingDownloadService} po pomyślnej transkrypcji
   * nagrania przez voicebot Whisper. Błąd jest logowany przez calling service –
   * ta metoda rzuca wyjątek gdy kontakt nie istnieje.
   *
   * @param contactId UUID kontaktu
   * @param tenantId  UUID tenanta (cross-tenant safety)
   * @param notes     treść transkryptu do zapisania
   * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy kontakt nie istnieje
   */
  @Transactional
  public void updateNotes(UUID contactId, UUID tenantId, String notes) {
    assertSameTenant(tenantId);
    setTenantContextInDb(tenantId);

    int updated = jdbcTemplate.update("""
            UPDATE contact
               SET notes      = ?,
                   updated_at = NOW()
             WHERE contact_id = ?::uuid
               AND tenant_id  = ?::uuid
            """,
        notes, contactId.toString(), tenantId.toString());

    if (updated == 0) {
      throw new com.contactcenter.domain.exception.ResourceNotFoundException(
          "Kontakt nie istnieje: " + contactId);
    }
    log.debug("[ContactRepo] Zaktualizowano notes (transkrypt): contactId={}, length={}", contactId, notes.length());
  }

}
