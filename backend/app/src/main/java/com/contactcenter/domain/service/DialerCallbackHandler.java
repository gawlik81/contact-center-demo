package com.contactcenter.domain.service;

import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Handler wyników połączeń inicjowanych przez Progressive Dialer.
 *
 * <p>Obsługuje trzy scenariusze zakończenia połączenia kampanijnego:
 * <ol>
 *   <li><strong>NO_ANSWER</strong> – brak odpowiedzi po 30s timeout: oznacza rekord
 *       campaign_contact jako NO_ANSWER i ustawia next_attempt_at na teraz + 4h.</li>
 *   <li><strong>ANSWERED</strong> – klient odebrał: aktualizuje status rekordu na CONNECTED
 *       i przekazuje połączenie agentowi przez TelephonyAdapter.</li>
 *   <li><strong>CALLBACK disposition</strong> – agent ustawił dyspozycję CALLBACK:
 *       tworzy rekord {@link ScheduledCallback} z datą/godziną wybraną przez agenta.</li>
 * </ol>
 *
 * <p>Wejście do handlera może być:
 * <ul>
 *   <li>Event RabbitMQ z routingiem {@code call.hangup} lub {@code call.answered}
 *       (gdy provider telefonii obsługuje eventy stanu połączenia)</li>
 *   <li>Wywołanie bezpośrednie przez {@link ProgressiveDialerService} lub kontroler
 *       po otrzymaniu dyspozycji od agenta</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dialer.enabled", havingValue = "true", matchIfMissing = true)
public class DialerCallbackHandler {

    // Domyślne opóźnienie między próbami (4 godziny)
    private static final int NO_ANSWER_RETRY_HOURS = 4;

    private final ScheduledCallbackRepository scheduledCallbackRepository;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    // =========================================================================
    // RabbitMQ listener – zakończenie połączenia kampanijnego (call.hangup)
    // =========================================================================

    /**
     * Nasłuchuje na zdarzenia zakończenia połączenia telefonicznego ({@code call.hangup}).
     *
     * <p>Gdy Twilio wysyła StatusCallback ze statusem {@code completed}, {@code TwilioTelephonyAdapter}
     * publikuje event {@code CALL_HANGUP} na {@code cc.events}. Ten listener odbiera go i sprawdza
     * czy połączenie było inicjowane przez dialer (klucz Redis {@code dialer:call:{callSid}}).
     * Jeśli tak – oznacza rekord {@code campaign_contact} jako {@code COMPLETED}
     * i zwalnia zasoby Redis (blokada agenta, stan połączenia, timeout).
     *
     * <p>Dla połączeń nieznanych dialerowi (np. inbound lub outbound bez kampanii) –
     * klucz Redis nie istnieje i event jest ignorowany bez skutków ubocznych.
     *
     * <p>Format klucza Redis {@code dialer:call:{callSid}}:
     * {@code {campaignContactId},{campaignId},{agentId},{tenantId}} (CSV, TTL 60s).
     *
     * <p>Używamy dedykowanej kolejki {@code cc.queue.dialer-hangup} aby uniknąć
     * consumer competition z {@code RabbitToWebSocketRelay} (kolejka {@code cc.queue.ws-relay-calls}).
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_DIALER_HANGUP)
    public void onCallHangup(CallEvent callEvent) {
        String callSid = callEvent.getCallId();
        log.debug("[DialerHandler] call.hangup odebrano: callSid={}, tenant={}, agentId={}",
                callSid, callEvent.getTenantId(), callEvent.getAgentId());

        if (callSid == null) {
            log.warn("[DialerHandler] call.hangup bez callSid – pomijam");
            return;
        }

        // Sprawdź czy to było połączenie dialera
        String callKey = "dialer:call:" + callSid;
        String callState = redisTemplate.opsForValue().get(callKey);

        if (callState == null) {
            // Nie jest to połączenie dialera (inbound lub outbound bez kampanii) – ignoruj
            log.debug("[DialerHandler] call.hangup: brak wpisu Redis dla callSid={} – nie jest połączeniem dialera, pomijam",
                    callSid);
            return;
        }

        // Format: campaignContactId,campaignId,agentId,tenantId
        String[] parts = callState.split(",");
        if (parts.length != 4) {
            log.error("[DialerHandler] call.hangup: nieprawidłowy format stanu Redis dla callSid={}: '{}'",
                    callSid, callState);
            redisTemplate.delete(callKey);
            return;
        }

        UUID recordId;
        UUID campaignId;
        UUID agentId;
        UUID tenantId;
        try {
            recordId   = UUID.fromString(parts[0]);
            campaignId = UUID.fromString(parts[1]);
            agentId    = UUID.fromString(parts[2]);
            tenantId   = UUID.fromString(parts[3]);
        } catch (IllegalArgumentException e) {
            log.error("[DialerHandler] call.hangup: błąd parsowania UUID ze stanu Redis dla callSid={}: {}",
                    callSid, e.getMessage());
            redisTemplate.delete(callKey);
            return;
        }

        log.info("[DialerHandler] Kończę połączenie kampanijne: callSid={}, rekord={}, kampania={}, agent={}, tenant={}",
                callSid, recordId, campaignId, agentId, tenantId);

        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(agentId);
        try {
            // Oznacz rekord campaign_contact jako COMPLETED – klient rozmawiał z agentem
            // (dla NO_ANSWER Twilio wysyła status no-answer, nie completed – obsługiwane przez handleNoAnswer)
            updateCampaignContact(recordId, campaignId, tenantId, "COMPLETED", null, null);
            log.info("[DialerHandler] Rekord kampanijny {} → COMPLETED po hangup (callSid={})", recordId, callSid);
        } catch (Exception e) {
            log.error("[DialerHandler] Błąd aktualizacji rekordu kampanijnego {} po hangup (callSid={}): {}",
                    recordId, callSid, e.getMessage(), e);
        } finally {
            // Zawsze zwalniaj zasoby Redis niezależnie od wyniku aktualizacji DB
            cleanupRedisKeys(callSid, agentId);
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Obsługa braku odpowiedzi (NO_ANSWER)
    // =========================================================================

    /**
     * Obsługuje brak odpowiedzi po timeout 30 sekund.
     *
     * <p>Aktualizuje rekord campaign_contact:
     * <ul>
     *   <li>status → NO_ANSWER</li>
     *   <li>next_attempt_at → NOW() + 4h (jeśli attempt_count < max_attempts)</li>
     *   <li>next_attempt_at → null, status → FAILED (jeśli wyczerpano próby)</li>
     * </ul>
     *
     * <p>Czyści klucze Redis: {@code dialer:call:{callSid}}, {@code dialer:timeout:{callSid}},
     * {@code dialer:agent:{agentId}}.
     *
     * @param callSid    identyfikator sesji połączenia
     * @param tenantId   UUID tenanta
     * @param campaignId UUID kampanii
     * @param recordId   UUID rekordu campaign_contact
     * @param agentId    UUID agenta (do zwolnienia blokady)
     * @param maxAttempts maksymalna liczba prób dla kampanii
     */
    @Transactional
    public void handleNoAnswer(String callSid, UUID tenantId, UUID campaignId,
                               UUID recordId, UUID agentId, int maxAttempts) {
        log.info("[DialerHandler] NO_ANSWER: callSid={}, kontakt={}, kampania={}", callSid, recordId, campaignId);

        // Odczyt aktualnego attempt_count
        int attemptCount = getCurrentAttemptCount(recordId, campaignId, tenantId);

        if (attemptCount >= maxAttempts) {
            // Wyczerpano próby – oznacz jako FAILED
            updateCampaignContact(recordId, campaignId, tenantId,
                    "FAILED", null, null);
            log.info("[DialerHandler] Kontakt {} wyczerpał próby ({}/{}), status=FAILED",
                    recordId, attemptCount, maxAttempts);
        } else {
            // Planuj kolejną próbę za 4 godziny
            Instant nextAttempt = Instant.now().plus(NO_ANSWER_RETRY_HOURS, ChronoUnit.HOURS);
            updateCampaignContact(recordId, campaignId, tenantId,
                    "NO_ANSWER", nextAttempt, null);
            log.info("[DialerHandler] Kontakt {} – NO_ANSWER, próba {}/{}, next_attempt_at={}",
                    recordId, attemptCount, maxAttempts, nextAttempt);
        }

        // Zwolnij zasoby Redis
        cleanupRedisKeys(callSid, agentId);
    }

    // =========================================================================
    // Obsługa odebrania połączenia (ANSWERED)
    // =========================================================================

    /**
     * Obsługuje odebranie połączenia przez klienta.
     *
     * <p>Aktualizuje status rekordu campaign_contact na CONNECTED.
     * Połączenie powinno być już zbridgowane z agentem przez TelephonyAdapter
     * (realizowane przez webhook obsługi połączeń).
     *
     * @param callSid    identyfikator sesji połączenia
     * @param tenantId   UUID tenanta
     * @param campaignId UUID kampanii
     * @param recordId   UUID rekordu campaign_contact
     * @param agentId    UUID agenta
     */
    @Transactional
    public void handleAnswered(String callSid, UUID tenantId, UUID campaignId,
                               UUID recordId, UUID agentId) {
        log.info("[DialerHandler] ANSWERED: callSid={}, kontakt={}, kampania={}, agent={}",
                callSid, recordId, campaignId, agentId);

        updateCampaignContact(recordId, campaignId, tenantId, "CONNECTED", null, null);

        // Timeout key już nie jest potrzebny (połączenie odebrane)
        redisTemplate.delete("dialer:timeout:" + callSid);

        log.info("[DialerHandler] Kontakt {} → CONNECTED, agent={}", recordId, agentId);
    }

    // =========================================================================
    // Obsługa dyspozycji CALLBACK
    // =========================================================================

    /**
     * Tworzy zaplanowane oddzwonienie po dyspozycji agenta CALLBACK.
     *
     * <p>Aktualizuje status rekordu campaign_contact na COMPLETED
     * i tworzy nowy rekord {@link ScheduledCallback} z podaną datą i godziną.
     *
     * @param tenantId          UUID tenanta
     * @param campaignId        UUID kampanii
     * @param recordId          UUID rekordu campaign_contact
     * @param agentId           UUID agenta ustawiającego dyspozycję
     * @param phone             numer telefonu do oddzwonienia
     * @param firstName         imię klienta (może być null)
     * @param lastName          nazwisko klienta (może być null)
     * @param scheduledAt       zaplanowany moment oddzwonienia
     * @param notes             notatka agenta (może być null)
     * @return utworzony rekord ScheduledCallback
     */
    @Transactional
    public ScheduledCallback handleCallbackDisposition(UUID tenantId, UUID campaignId,
                                                        UUID recordId, UUID agentId,
                                                        String phone, String firstName, String lastName,
                                                        Instant scheduledAt, String notes) {
        log.info("[DialerHandler] CALLBACK disposition: kontakt={}, kampania={}, scheduledAt={}, agent={}",
                recordId, campaignId, scheduledAt, agentId);

        // Nie ustawiamy TenantContext ani nie czyścimy – metoda jest wywoływana z HTTP path
        // gdzie TenantFilter już zarządza cyklem życia kontekstu. Dostęp do tenantId przez
        // jawny parametr, nie przez ThreadLocal.

        // Oznacz kontakt jako COMPLETED (dyspozycja CALLBACK = zakończona obsługa)
        updateCampaignContact(recordId, campaignId, tenantId, "COMPLETED", null, "CALLBACK");

        // Utwórz rekord scheduled_callback
        ScheduledCallback callback = ScheduledCallback.builder()
                .tenantId(tenantId)
                .campaignId(campaignId)
                .agentId(agentId)
                .phone(phone)
                .firstName(firstName)
                .lastName(lastName)
                .scheduledAt(scheduledAt)
                .notes(notes)
                .status("PENDING")
                .build();

        ScheduledCallback saved = scheduledCallbackRepository.save(callback);

        log.info("[DialerHandler] Callback zaplanowany: callbackId={}, phone={}, scheduledAt={}, tenant={}",
                saved.getCallbackId(), maskPhone(phone), scheduledAt, tenantId);

        return saved;
    }

    // =========================================================================
    // Obsługa zakończenia kampanijnego połączenia z dyspozycją ogólną
    // =========================================================================

    /**
     * Obsługuje zakończenie połączenia kampanijnego z dyspozycją agenta.
     *
     * <p>Aktualizuje status rekordu na COMPLETED i zapisuje kod dyspozycji.
     * Jeśli dyspozycja to CALLBACK – użyj metody {@link #handleCallbackDisposition}.
     *
     * @param tenantId       UUID tenanta
     * @param campaignId     UUID kampanii
     * @param recordId       UUID rekordu campaign_contact
     * @param agentId        UUID agenta
     * @param dispositionCode kod dyspozycji (np. SALE, DECLINED, CALLBACK)
     * @param callSid        identyfikator sesji połączenia (do czyszczenia Redis)
     */
    @Transactional
    public void handleCompleted(UUID tenantId, UUID campaignId, UUID recordId,
                                UUID agentId, String dispositionCode, String callSid) {
        log.info("[DialerHandler] COMPLETED: kontakt={}, kampania={}, dyspozycja={}, agent={}",
                recordId, campaignId, dispositionCode, agentId);

        updateCampaignContact(recordId, campaignId, tenantId, "COMPLETED", null, dispositionCode);
        cleanupRedisKeys(callSid, agentId);

        log.info("[DialerHandler] Kontakt {} → COMPLETED, dyspozycja={}", recordId, dispositionCode);
    }

    // =========================================================================
    // Pomocnicze – baza danych
    // =========================================================================

    /**
     * Aktualizuje status rekordu campaign_contact.
     *
     * @param recordId        UUID rekordu
     * @param campaignId      UUID kampanii (klucz partycji)
     * @param tenantId        UUID tenanta
     * @param newStatus       nowy status
     * @param nextAttemptAt   czas kolejnej próby (null = bez planowania)
     * @param dispositionCode kod dyspozycji (null = bez zmiany)
     */
    private void updateCampaignContact(UUID recordId, UUID campaignId, UUID tenantId,
                                        String newStatus, Instant nextAttemptAt,
                                        String dispositionCode) {
        setTenantContextInJdbc(tenantId);

        if (nextAttemptAt != null && dispositionCode != null) {
            jdbcTemplate.update(
                    """
                    UPDATE campaign_contact
                    SET status = ?,
                        next_attempt_at = ?,
                        disposition_code = ?,
                        updated_at = NOW()
                    WHERE record_id = ?::uuid
                      AND campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                    """,
                    newStatus,
                    java.sql.Timestamp.from(nextAttemptAt),
                    dispositionCode,
                    recordId.toString(),
                    campaignId.toString(),
                    tenantId.toString()
            );
        } else if (nextAttemptAt != null) {
            jdbcTemplate.update(
                    """
                    UPDATE campaign_contact
                    SET status = ?,
                        next_attempt_at = ?,
                        updated_at = NOW()
                    WHERE record_id = ?::uuid
                      AND campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                    """,
                    newStatus,
                    java.sql.Timestamp.from(nextAttemptAt),
                    recordId.toString(),
                    campaignId.toString(),
                    tenantId.toString()
            );
        } else if (dispositionCode != null) {
            jdbcTemplate.update(
                    """
                    UPDATE campaign_contact
                    SET status = ?,
                        disposition_code = ?,
                        updated_at = NOW()
                    WHERE record_id = ?::uuid
                      AND campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                    """,
                    newStatus,
                    dispositionCode,
                    recordId.toString(),
                    campaignId.toString(),
                    tenantId.toString()
            );
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE campaign_contact
                    SET status = ?,
                        updated_at = NOW()
                    WHERE record_id = ?::uuid
                      AND campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                    """,
                    newStatus,
                    recordId.toString(),
                    campaignId.toString(),
                    tenantId.toString()
            );
        }
    }

    /**
     * Pobiera aktualny attempt_count rekordu campaign_contact.
     *
     * @param recordId   UUID rekordu
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return attempt_count lub 0 gdy rekord nie istnieje
     */
    private int getCurrentAttemptCount(UUID recordId, UUID campaignId, UUID tenantId) {
        setTenantContextInJdbc(tenantId);

        try {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT attempt_count FROM campaign_contact
                    WHERE record_id = ?::uuid
                      AND campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                    """,
                    Integer.class,
                    recordId.toString(),
                    campaignId.toString(),
                    tenantId.toString()
            );
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("[DialerHandler] Nie można pobrać attempt_count dla rekordu {}: {}", recordId, e.getMessage());
            return 0;
        }
    }

    // =========================================================================
    // Pomocnicze – baza danych (kontekst RLS)
    // =========================================================================

    /**
     * Ustawia kontekst tenant RLS dla JdbcTemplate przez parametryzowane zapytanie.
     *
     * <p>Używa bindowania parametru zamiast string concatenation – eliminuje ryzyko
     * SQL injection i jest zgodne z konwencją projektu.
     *
     * @param tenantId UUID tenanta
     */
    private void setTenantContextInJdbc(UUID tenantId) {
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });
    }

    // =========================================================================
    // Pomocnicze – Redis
    // =========================================================================

    /**
     * Czyści klucze Redis po zakończeniu połączenia dialera.
     *
     * @param callSid identyfikator sesji połączenia
     * @param agentId UUID agenta
     */
    private void cleanupRedisKeys(String callSid, UUID agentId) {
        if (callSid != null) {
            redisTemplate.delete("dialer:call:" + callSid);
            redisTemplate.delete("dialer:timeout:" + callSid);
        }
        if (agentId != null) {
            redisTemplate.delete("dialer:agent:" + agentId);
        }
    }

    /**
     * Maskuje numer telefonu dla logów.
     *
     * @param phone numer telefonu
     * @return zamaskowany numer
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "****";
        }
        return phone.substring(0, phone.length() - 4).replaceAll("\\d", "*")
                + phone.substring(phone.length() - 4);
    }
}
