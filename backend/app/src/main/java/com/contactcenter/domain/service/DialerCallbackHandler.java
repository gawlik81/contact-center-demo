package com.contactcenter.domain.service;

import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.domain.telephony.TelephonyAdapter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;

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

    // Wartości domyślne gdy kampania nie jest dostępna
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_RETRY_DELAY_MINUTES = 60;

    private final ScheduledCallbackRepository scheduledCallbackRepository;
    private final CampaignRepository campaignRepository;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final TelephonyAdapter telephonyAdapter;

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

        log.info("[DialerHandler] Kończę połączenie kampanijne: callSid={}, rekord={}, kampania={}, agent={}, tenant={}, outcome={}",
                callSid, recordId, campaignId, agentId, tenantId, callEvent.getCallOutcome());

        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(agentId);
        try {
            String outcome = callEvent.getCallOutcome();
            if (isNoAnswerOutcome(outcome)) {
                // Brak odpowiedzi lub zajętość – odczytaj parametry kampanii i zaplanuj ponowną próbę
                Campaign campaign = loadCampaignOrNull(campaignId, tenantId);
                int maxAttempts = campaign != null ? campaign.getMaxAttempts() : DEFAULT_MAX_ATTEMPTS;
                int retryDelayMinutes = campaign != null ? campaign.getRetryDelayMinutes() : DEFAULT_RETRY_DELAY_MINUTES;
                handleNoAnswer(callSid, tenantId, campaignId, recordId, agentId, maxAttempts, retryDelayMinutes);
            } else {
                // completed, canceled, failed, null → zakończ jako COMPLETED
                updateCampaignContact(recordId, campaignId, tenantId, "COMPLETED", null, null);
                log.info("[DialerHandler] Rekord kampanijny {} → COMPLETED po hangup (outcome={}, callSid={})",
                        recordId, outcome, callSid);
            }
        } catch (Exception e) {
            log.error("[DialerHandler] Błąd aktualizacji rekordu kampanijnego {} po hangup (callSid={}): {}",
                    recordId, callSid, e.getMessage(), e);
        } finally {
            // Zwolnij zasoby Redis zawsze – niezależnie od sukcesu lub wyjątku w try.
            // cleanupRedisKeys jest idempotentne, więc podwójne wywołanie (np. z handleNoAnswer) jest bezpieczne.
            cleanupRedisKeys(callSid, agentId);
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Scheduled – wykrywanie wygasłych ring timeout
    // =========================================================================

    /**
     * Co kilka sekund sprawdza połączenia dialera, którym wygasł ring timeout.
     *
     * <p>Logika: jeśli {@code dialer:call:{callSid}} istnieje, ale {@code dialer:timeout:{callSid}}
     * już nie → TTL timeout wygasł → klient nie odebrał → rozłącz i oznacz NO_ANSWER.
     *
     * <p>Atomowość: używa {@code GETDEL} (pobierz i usuń atomicznie) na kluczu {@code dialer:call},
     * co gwarantuje że tylko jeden wywołujący (ten job lub {@code onCallHangup}) przetworzy zdarzenie.
     */
    @Scheduled(fixedDelayString = "${dialer.ring-timeout-check-interval-ms:5000}")
    public void checkRingTimeouts() {
        // SCAN zamiast KEYS – nieblokujące, bezpieczne przy dużej liczbie kluczy
        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match("dialer:call:*")
                .count(200)
                .build();

        List<String> callKeys = new ArrayList<>();
        try (var cursor = redisTemplate.scan(scanOptions)) {
            cursor.forEachRemaining(callKeys::add);
        }

        if (callKeys.isEmpty()) {
            return;
        }

        log.debug("[DialerHandler] checkRingTimeouts: sprawdzam {} aktywnych połączeń", callKeys.size());

        for (String callKey : callKeys) {
            String callSid = callKey.substring("dialer:call:".length());
            String timeoutKey = "dialer:timeout:" + callSid;

            // Jeśli timeout klucz wciąż istnieje – czas jeszcze nie upłynął, pomijamy
            if (Boolean.TRUE.equals(redisTemplate.hasKey(timeoutKey))) {
                continue;
            }

            // Jeśli klient odebrał – timeout key usunięto celowo, ale to nie jest ring timeout
            String answeredKey = "dialer:answered:" + callSid;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(answeredKey))) {
                log.debug("[DialerHandler] checkRingTimeouts: callSid={} – klient odebrał, pomijam", callSid);
                continue;
            }

            // Atomowe GETDEL – zwraca wartość i usuwa klucz; jeśli null → onCallHangup był pierwszy
            String callState = redisTemplate.opsForValue().getAndDelete(callKey);
            if (callState == null) {
                continue; // inny wątek (onCallHangup) już przetworzył to połączenie
            }

            // Format: recordId,campaignId,agentId,tenantId
            String[] parts = callState.split(",");
            if (parts.length != 4) {
                log.warn("[DialerHandler] checkRingTimeouts: nieprawidłowy format stanu Redis dla callSid={}: '{}'",
                        callSid, callState);
                continue;
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
                log.error("[DialerHandler] checkRingTimeouts: błąd parsowania UUID dla callSid={}: {}",
                        callSid, e.getMessage());
                continue;
            }

            log.info("[DialerHandler] Ring timeout wygasł – rozłączam: callSid={}, kontakt={}, kampania={}, agent={}",
                    callSid, recordId, campaignId, agentId);

            TenantContext.setTenantId(tenantId);
            TenantContext.setUserId(agentId);
            try {
                // Rozłącz aktywne połączenie Twilio
                try {
                    telephonyAdapter.hangupCall(callSid);
                    log.debug("[DialerHandler] Połączenie {} rozłączone przez ring timeout", callSid);
                } catch (Exception e) {
                    log.warn("[DialerHandler] Błąd hangup dla callSid={}: {} – kontynuuję aktualizację statusu",
                            callSid, e.getMessage());
                }

                // Oznacz NO_ANSWER w bazie – z parametrami kampanii
                Campaign campaign = loadCampaignOrNull(campaignId, tenantId);
                int maxAttempts       = campaign != null ? campaign.getMaxAttempts()       : DEFAULT_MAX_ATTEMPTS;
                int retryDelayMinutes = campaign != null ? campaign.getRetryDelayMinutes() : DEFAULT_RETRY_DELAY_MINUTES;

                handleNoAnswer(callSid, tenantId, campaignId, recordId, agentId, maxAttempts, retryDelayMinutes);

            } catch (Exception e) {
                log.error("[DialerHandler] checkRingTimeouts: błąd dla callSid={}: {}", callSid, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
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
     *   <li>status → {@code NO_ANSWER}, next_attempt_at → NOW() + retryDelayMinutes
     *       (jeśli attempt_count &lt; max_attempts)</li>
     *   <li>status → {@code NOT_REACHED}, next_attempt_at → null
     *       (jeśli wyczerpano próby — rekord finalny, nie wraca do kolejki)</li>
     * </ul>
     *
     * <p>Czyści klucze Redis: {@code dialer:call:{callSid}}, {@code dialer:timeout:{callSid}},
     * {@code dialer:agent:{agentId}}.
     *
     * @param callSid           identyfikator sesji połączenia
     * @param tenantId          UUID tenanta
     * @param campaignId        UUID kampanii
     * @param recordId          UUID rekordu campaign_contact
     * @param agentId           UUID agenta (do zwolnienia blokady)
     * @param maxAttempts       maksymalna liczba prób dla kampanii
     * @param retryDelayMinutes opóźnienie przed kolejną próbą w minutach (z konfiguracji kampanii)
     */
    @Transactional
    public void handleNoAnswer(String callSid, UUID tenantId, UUID campaignId,
                               UUID recordId, UUID agentId, int maxAttempts, int retryDelayMinutes) {
        log.info("[DialerHandler] NO_ANSWER: callSid={}, kontakt={}, kampania={}", callSid, recordId, campaignId);

        // Sprawdź czy to był callback attempt (zainicjowany przez ScheduledCallbackExecutor)
        boolean isCallbackAttempt = Boolean.TRUE.equals(
                redisTemplate.hasKey("dialer:callback-attempt:" + callSid));

        if (isCallbackAttempt) {
            // Callback attempt: zaplanuj kolejną próbę BEZ sprawdzania attempt_count
            Instant nextAttempt = Instant.now().plus(retryDelayMinutes, ChronoUnit.MINUTES);
            updateCampaignContact(recordId, campaignId, tenantId, "NO_ANSWER", nextAttempt, null);
            redisTemplate.delete("dialer:callback-attempt:" + callSid);
            log.info("[DialerHandler] Kontakt {} – NO_ANSWER po callback attempt, next_attempt_at={} (+{}min), callSid={}",
                    recordId, nextAttempt, retryDelayMinutes, callSid);
        } else {
            // Normalny flow dialera: odczyt attempt_count i decyzja o dalszych próbach
            int attemptCount = getCurrentAttemptCount(recordId, campaignId, tenantId);

            if (attemptCount >= maxAttempts) {
                // Wyczerpano próby – rekord finalny (niedodzwoniony)
                updateCampaignContact(recordId, campaignId, tenantId, "NOT_REACHED", null, null);
                log.info("[DialerHandler] Kontakt {} wyczerpał próby ({}/{}), status=NOT_REACHED",
                        recordId, attemptCount, maxAttempts);
            } else {
                // Zaplanuj kolejną próbę wg konfiguracji kampanii
                Instant nextAttempt = Instant.now().plus(retryDelayMinutes, ChronoUnit.MINUTES);
                updateCampaignContact(recordId, campaignId, tenantId, "NO_ANSWER", nextAttempt, null);
                log.info("[DialerHandler] Kontakt {} – NO_ANSWER, próba {}/{}, next_attempt_at={} (+{}min)",
                        recordId, attemptCount, maxAttempts, nextAttempt, retryDelayMinutes);
            }
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
        // Ustaw flagę "answered" – checkRingTimeouts() rozróżnia wygasły timeout od celowego usunięcia klucza
        redisTemplate.opsForValue().set(
            "dialer:answered:" + callSid, "1", java.time.Duration.ofMinutes(60));

        log.info("[DialerHandler] Kontakt {} → CONNECTED, agent={}", recordId, agentId);
    }

    // =========================================================================
    // Obsługa dyspozycji CALLBACK
    // =========================================================================

    /**
     * Tworzy zaplanowane oddzwonienie po dyspozycji agenta CALLBACK.
     *
     * <p>Aktualizuje status rekordu campaign_contact na CALLBACK
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

        // Oznacz kontakt jako CALLBACK – rekord aktywny, czeka na oddzwonienie
        updateCampaignContact(recordId, campaignId, tenantId, "CALLBACK", scheduledAt, "CALLBACK");

        // Utwórz rekord scheduled_callback z powiązaniem do rekordu kampanijnego
        ScheduledCallback callback = ScheduledCallback.builder()
                .tenantId(tenantId)
                .campaignId(campaignId)
                .campaignContactRecordId(recordId)
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
            redisTemplate.delete("dialer:callback-attempt:" + callSid);
            redisTemplate.delete("dialer:answered:" + callSid);
        }
        if (agentId != null) {
            redisTemplate.delete("dialer:agent:" + agentId);
            // Ustaw AFTER_CONTACT – zapobiega ponownemu wydzwonieniu przez pollAvailableAgents()
            // zanim agent zapisze dyspozycję i ręcznie wróci do AVAILABLE.
            // Zmiana tylko z AVAILABLE/BUSY – nie nadpisuje BREAK/OFFLINE/INACTIVE.
            int updated = jdbcTemplate.update(
                "UPDATE app_user SET status = 'AFTER_CONTACT', updated_at = NOW() " +
                "WHERE user_id = ?::uuid AND status IN ('AVAILABLE', 'BUSY')",
                agentId.toString()
            );
            if (updated > 0) {
                log.debug("[DialerHandler] Agent {} → AFTER_CONTACT (ACW po połączeniu kampanijnym)", agentId);
            }
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

    // =========================================================================
    // Pomocnicze – wynik połączenia
    // =========================================================================

    /**
     * Sprawdza czy wynik połączenia oznacza brak odpowiedzi lub zajętość.
     *
     * @param outcome wartość {@code callOutcome} z {@link CallEvent} (może być null)
     * @return true gdy outcome to "no-answer" lub "busy" (case-insensitive)
     */
    private boolean isNoAnswerOutcome(String outcome) {
        if (outcome == null) {
            return false;
        }
        String lower = outcome.toLowerCase();
        return "no-answer".equals(lower) || "busy".equals(lower);
    }

    /**
     * Wczytuje kampanię z repozytorium; zwraca null gdy kampania nie istnieje lub wystąpił błąd.
     *
     * <p>Defensywny fallback: brak kampanii nie może blokować przetwarzania hangup.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return encja {@link Campaign} lub null
     */
    private Campaign loadCampaignOrNull(UUID campaignId, UUID tenantId) {
        try {
            return campaignRepository.findById(campaignId, tenantId).orElse(null);
        } catch (Exception e) {
            log.warn("[DialerHandler] Nie udało się wczytać kampanii {}: {} – używam wartości domyślnych",
                    campaignId, e.getMessage());
            return null;
        }
    }
}
