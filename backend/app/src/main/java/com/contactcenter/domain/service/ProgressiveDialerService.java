package com.contactcenter.domain.service;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.AppUser.UserStatus;
import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Silnik Progressive Dialer – automatyczne dzwonienie dla kampanii wychodzących.
 *
 * <p>Nasłuchuje na eventy {@code agent.status.changed} z kolejki RabbitMQ.
 * Gdy agent zmienia status na AVAILABLE i istnieje aktywna kampania (status=RUNNING)
 * mieszcząca się w oknie harmonogramu, dialer pobiera następny kontakt PENDING
 * z listy kampanii i inicjuje połączenie przez {@link TelephonyAdapter}.
 *
 * <p>Ochrona przed race condition: przed inicjacją połączenia ustawia klucz Redis
 * {@code dialer:agent:{agentId}} z TTL 60s. Jeśli klucz istnieje → agent już
 * obsługiwany przez dialer, pomijamy zdarzenie.
 *
 * <p>Aktywny warunkowo przez właściwość {@code dialer.enabled} (domyślnie: true).
 * Wyłącz przez {@code DIALER_ENABLED=false} w ENV vars.
 *
 * <p>Klucze Redis:
 * <ul>
 *   <li>{@code dialer:agent:{agentId}} → callSid, TTL 60s (guard przed duplikacją)</li>
 *   <li>{@code dialer:call:{callSid}} → JSON z campaignContactId/agentId/tenantId/campaignId, TTL 60s</li>
 *   <li>{@code dialer:timeout:{callSid}} → "", TTL 30s (po wygaśnięciu = NO_ANSWER)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dialer.enabled", havingValue = "true", matchIfMissing = true)
public class ProgressiveDialerService {

    // Redis TTL (sekundy)
    private static final int AGENT_LOCK_TTL_SECONDS   = 60;
    private static final int CALL_STATE_TTL_SECONDS   = 1800; // 30 minut – chroni przed wygaśnięciem klucza w trakcie dłuższej rozmowy
    private static final int NO_ANSWER_TIMEOUT_SECONDS = 30;

    // Domyślna strefa czasowa kampanii
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Warsaw");

    // Numer wychodzący (fallback gdy kampania nie ma skonfigurowanego numeru)
    @Value("${telephony.outbound-number:+48000000000}")
    private String defaultOutboundNumber;

    private final CampaignRepository campaignRepository;
    private final QueueRepository queueRepository;
    private final AppUserRepository appUserRepository;
    private final TelephonyAdapter telephonyAdapter;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final TenantTwilioConfigService tenantTwilioConfigService;

    /**
     * Self-reference przez @Lazy – pozwala wywoływać @Transactional przez proxy Springa.
     * Bez tego self-invocation (this.initiateDialForAgent) omijałoby proxy i transakcja
     * nigdy by nie startowała (Spring AOP limitation).
     */
    @Autowired
    @Lazy
    private ProgressiveDialerService self;

    /**
     * Resolwuje numer "from" dla połączenia wychodzącego.
     * Priorytet: campaign.callerId → tenant_twilio_config.phone_number → TwilioProperties.phoneNumber
     */
    private String resolveFromNumber(Campaign campaign, UUID tenantId) {
        if (StringUtils.hasText(campaign.getCallerId())) {
            log.debug("[Dialer] Używam caller_id kampanii: {}, kampania={}",
                    maskPhone(campaign.getCallerId()), campaign.getCampaignId());
            return campaign.getCallerId();
        }
        try {
            String perTenantNumber = tenantTwilioConfigService.getDecryptedConfig(tenantId)
                    .map(cfg -> cfg.phoneNumber())
                    .filter(StringUtils::hasText)
                    .orElse(null);
            if (perTenantNumber != null) {
                log.debug("[Dialer] Używam numeru per-tenant z TenantTwilioConfig: {}, tenant={}",
                        maskPhone(perTenantNumber), tenantId);
                return perTenantNumber;
            }
        } catch (Exception e) {
            log.warn("[Dialer] Błąd odczytu per-tenant phone_number, tenant={}: {} – fallback do defaultOutboundNumber",
                    tenantId, e.getMessage());
        }
        log.debug("[Dialer] Używam defaultOutboundNumber: {}, tenant={}", maskPhone(defaultOutboundNumber), tenantId);
        return defaultOutboundNumber;
    }

    // =========================================================================
    // RabbitMQ listener – zmiana statusu agenta
    // =========================================================================

    /**
     * Nasłuchuje na zdarzenia zmiany statusu agenta z kolejki {@code cc.queue.agent-status}.
     *
     * <p>Gdy agent zmienia status na AVAILABLE:
     * <ol>
     *   <li>Sprawdza blokadę Redis (ochrona przed race condition)</li>
     *   <li>Wyszukuje aktywne kampanie (status=RUNNING) dla tenanta agenta</li>
     *   <li>Sprawdza harmonogram każdej kampanii</li>
     *   <li>Pobiera następny kontakt PENDING (pessimistic lock)</li>
     *   <li>Inicjuje połączenie przez TelephonyAdapter</li>
     *   <li>Zapisuje stan w Redis z TTL</li>
     * </ol>
     *
     * <p>Serwis działa w wątku RabbitMQ (brak TenantContext z HTTP filter).
     * TenantContext jest ustawiany jawnie na podstawie danych z eventu i czyszczony
     * w bloku finally.
     *
     * @param event zdarzenie zmiany statusu agenta
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_DIALER_AGENT_STATUS)
    public void onAgentStatusChanged(AgentStatusChangedEvent event) {
        // Filtrujemy tylko zdarzenia AVAILABLE
        if (event.newStatus() != UserStatus.AVAILABLE) {
            return;
        }

        UUID agentId = event.userId();
        UUID tenantId = event.tenantId();

        log.debug("[Dialer] Agent {} zmienił status na AVAILABLE (tenant={})", agentId, tenantId);

        // Ochrona przed race condition: SET NX z TTL 60s
        String agentLockKey = "dialer:agent:" + agentId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(agentLockKey, "locked", Duration.ofSeconds(AGENT_LOCK_TTL_SECONDS));

        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("[Dialer] Agent {} już obsługiwany przez dialer – pomijam", agentId);
            return;
        }

        // Ustawiamy TenantContext dla wątku RabbitMQ
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(agentId);

        try {
            // Wywołanie przez self-proxy – gwarantuje działanie @Transactional (uniknięcie self-invocation)
            self.initiateDialForAgent(agentId, tenantId);
        } catch (Exception e) {
            log.error("[Dialer] Błąd podczas inicjowania połączenia dla agenta {}: {}", agentId, e.getMessage(), e);
            // Zwolnij blokadę przy błędzie – agenta można ponowić
            redisTemplate.delete(agentLockKey);
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Logika dialera
    // =========================================================================

    /**
     * Inicjuje połączenie wychodzące dla dostępnego agenta.
     *
     * <p>Przeszukuje aktywne kampanie tenanta (RUNNING), sprawdza harmonogram
     * i pobiera następny kontakt PENDING z pessimistic lock.
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     */
    @Transactional
    public void initiateDialForAgent(UUID agentId, UUID tenantId) {
        // Pobierz kampanie RUNNING z typem PROGRESSIVE dla tenanta.
        // Kampanie MANUAL są celowo wykluczone – wymagają ręcznej inicjacji przez agenta
        // (endpoint POST /api/dialer/manual/call). Kampanie PREDICTIVE również wykluczone
        // (nie zaimplementowane w tej wersji).
        List<Campaign> runningCampaigns = campaignRepository.findRunningProgressiveByTenantId(tenantId);

        if (runningCampaigns.isEmpty()) {
            log.debug("[Dialer] Brak aktywnych kampanii dla tenanta {}", tenantId);
            releaseAgentLock(agentId);
            return;
        }

        for (Campaign campaign : runningCampaigns) {
            // Sprawdź harmonogram kampanii
            if (!isInSchedule(campaign)) {
                log.debug("[Dialer] Kampania {} poza oknem harmonogramu – pomijam",
                        campaign.getCampaignId());
                continue;
            }

            // Sprawdź czy agent spełnia wymagania skills kolejki kampanii
            if (campaign.getQueueId() != null && !agentHasRequiredSkills(agentId, tenantId, campaign)) {
                log.debug("[Dialer] Agent {} nie spełnia wymagań skills kampanii {} (kolejka={}) – pomijam",
                        agentId, campaign.getCampaignId(), campaign.getQueueId());
                continue;
            }

            // Pobierz następny kontakt z pessimistic lock
            Map<String, Object> contact = fetchNextPendingContact(campaign.getCampaignId(), tenantId);
            if (contact == null) {
                log.debug("[Dialer] Brak kontaktów PENDING w kampanii {}", campaign.getCampaignId());
                continue;
            }

            UUID recordId = UUID.fromString((String) contact.get("record_id"));
            String phone = (String) contact.get("phone");

            if (phone == null || phone.isBlank()) {
                log.warn("[Dialer] Kontakt {} nie ma numeru telefonu – pomijam i oznaczam SKIPPED", recordId);
                markContactStatus(recordId, campaign.getCampaignId(), tenantId, "SKIPPED", null);
                continue;
            }

            // Inicjuj połączenie przez TelephonyAdapter
            try {
                log.info("[Dialer] Inicjowanie połączenia: kampania={}, kontakt={}, numer={}, agent={}, tenant={}",
                        campaign.getCampaignId(), recordId, maskPhone(phone), agentId, tenantId);

                // Oznacz kontakt jako DIALING (pessimistic update)
                markContactStatus(recordId, campaign.getCampaignId(), tenantId, "DIALING", agentId);

                String fromNumber = resolveFromNumber(campaign, tenantId);
                CallSession session = telephonyAdapter.initiateCall(tenantId, fromNumber, phone, agentId,
                        campaign.getQueueId(), null); // callbackId null – połączenie z kampanii, nie oddzwonienie

                // Zapisz stan w Redis
                saveCallState(session.getCallId(), recordId, campaign.getCampaignId(), agentId, tenantId);
                scheduleNoAnswerTimeout(session.getCallId());

                log.info("[Dialer] Połączenie zainicjowane: callId={}, kontakt={}, kampania={}",
                        session.getCallId(), recordId, campaign.getCampaignId());

                // Sukces – blokada agenta pozostaje aktywna (TTL 60s)
                return;

            } catch (TelephonyAdapter.TelephonyException e) {
                log.error("[Dialer] Błąd telefonii dla kontaktu {} w kampanii {}: {}",
                        recordId, campaign.getCampaignId(), e.getMessage());
                // ERROR (nie FAILED) – błąd adaptera telefonii (np. Twilio ApiException)
                // jest trwały i nie kwalifikuje rekordu do ponowienia przez dialer.
                markContactStatus(recordId, campaign.getCampaignId(), tenantId, "ERROR", null);
                // Kontynuuj – spróbuj kolejną kampanię lub kontakt
            }
        }

        // Żadna kampania nie dała kontaktu – zwolnij blokadę
        releaseAgentLock(agentId);
        log.debug("[Dialer] Brak kontaktów do zadzwonienia dla agenta {} (tenant={})", agentId, tenantId);
    }

    // =========================================================================
    // Pomocnicze – harmonogram
    // =========================================================================

    /**
     * Sprawdza czy kampania aktualnie mieści się w oknie harmonogramu.
     *
     * <p>Analizuje pola JSONB: {@code start_date}, {@code end_date},
     * {@code active_days} (np. ["MON","TUE"]) i {@code active_hours}
     * ({@code from}, {@code to}).
     *
     * @param campaign kampania do sprawdzenia
     * @return true gdy kampania jest w oknie harmonogramu
     */
    public boolean isInSchedule(Campaign campaign) {
        Map<String, Object> schedule = campaign.getSchedule();

        // Brak harmonogramu = zawsze aktywna
        if (schedule == null || schedule.isEmpty()) {
            return true;
        }

        ZoneId zoneId = resolveTimezone(schedule);
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        LocalDate today = now.toLocalDate();

        // Sprawdź start_date
        String startDateStr = (String) schedule.get("start_date");
        if (startDateStr != null) {
            LocalDate startDate = LocalDate.parse(startDateStr);
            if (today.isBefore(startDate)) {
                return false;
            }
        }

        // Sprawdź end_date
        String endDateStr = (String) schedule.get("end_date");
        if (endDateStr != null) {
            LocalDate endDate = LocalDate.parse(endDateStr);
            if (today.isAfter(endDate)) {
                return false;
            }
        }

        // Sprawdź active_days (np. ["MON","TUE","WED","THU","FRI"])
        @SuppressWarnings("unchecked")
        List<String> activeDays = (List<String>) schedule.get("active_days");
        if (activeDays != null && !activeDays.isEmpty()) {
            // DayOfWeek.name() zwraca MONDAY, TUESDAY itd. – bierzemy pierwsze 3 litery
            String currentDay = now.getDayOfWeek().name().substring(0, 3);
            if (!activeDays.contains(currentDay)) {
                return false;
            }
        }

        // Sprawdź active_hours
        @SuppressWarnings("unchecked")
        Map<String, Object> activeHours = (Map<String, Object>) schedule.get("active_hours");
        if (activeHours != null) {
            String fromStr = (String) activeHours.get("from");
            String toStr = (String) activeHours.get("to");
            if (fromStr != null && toStr != null) {
                LocalTime fromTime = LocalTime.parse(fromStr);
                LocalTime toTime = LocalTime.parse(toStr);
                LocalTime currentTime = now.toLocalTime();
                if (currentTime.isBefore(fromTime) || currentTime.isAfter(toTime)) {
                    return false;
                }
            }
        }

        return true;
    }

    // =========================================================================
    // Pomocnicze – baza danych
    // =========================================================================

    /**
     * Pobiera następny kontakt PENDING dla kampanii z pessimistic lock (FOR UPDATE SKIP LOCKED).
     *
     * <p>Filtruje kontakty, których {@code next_attempt_at} jest w przeszłości lub null,
     * sortuje po {@code created_at ASC} i pobiera jeden rekord z blokadą optymistyczną.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return mapa kolumn rekordu lub null gdy brak dostępnych kontaktów
     */
    private Map<String, Object> fetchNextPendingContact(UUID campaignId, UUID tenantId) {
        // Ustawienie kontekstu RLS dla JdbcTemplate
        setTenantContextInJdbc(tenantId);

        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                """
                SELECT record_id::text, phone, first_name, last_name,
                       attempt_count, last_attempt_at
                FROM campaign_contact
                WHERE campaign_id = ?::uuid
                  AND tenant_id = ?::uuid
                  AND status IN ('PENDING', 'NO_ANSWER')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
                ORDER BY created_at ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
                campaignId.toString(),
                tenantId.toString()
        );

        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Aktualizuje status rekordu campaign_contact.
     *
     * <p>Przy przejściu na DIALING ustawia również {@code last_attempt_at = NOW()}
     * i inkrementuje {@code attempt_count}.
     *
     * @param recordId     UUID rekordu campaign_contact
     * @param campaignId   UUID kampanii (klucz partycji)
     * @param tenantId     UUID tenanta
     * @param newStatus    nowy status (PENDING, DIALING, CONNECTED, NO_ANSWER, FAILED, COMPLETED, SKIPPED)
     * @param assignedAgent UUID agenta (może być null)
     */
    private void markContactStatus(UUID recordId, UUID campaignId, UUID tenantId,
                                   String newStatus, UUID assignedAgent) {
        setTenantContextInJdbc(tenantId);

        if ("DIALING".equals(newStatus)) {
            jdbcTemplate.update(
                    """
                    UPDATE campaign_contact
                    SET status = 'DIALING',
                        last_attempt_at = NOW(),
                        attempt_count = attempt_count + 1,
                        updated_at = NOW()
                    WHERE record_id = ?::uuid
                      AND campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                    """,
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

        log.debug("[Dialer] Kontakt {} → status={}", recordId, newStatus);
    }

    // =========================================================================
    // Pomocnicze – Redis
    // =========================================================================

    /**
     * Zapisuje stan aktywnego połączenia dialera w Redis.
     *
     * <p>Klucz {@code dialer:call:{callSid}} zawiera CSV z danymi połączenia.
     * TTL = 60 sekund (z marginesem ponad timeout 30s).
     *
     * @param callSid           identyfikator sesji telefonicznej
     * @param campaignContactId UUID rekordu campaign_contact
     * @param campaignId        UUID kampanii
     * @param agentId           UUID agenta
     * @param tenantId          UUID tenanta
     */
    public void saveCallState(String callSid, UUID campaignContactId,
                               UUID campaignId, UUID agentId, UUID tenantId) {
        String callKey = "dialer:call:" + callSid;
        String value = campaignContactId + "," + campaignId + "," + agentId + "," + tenantId;
        redisTemplate.opsForValue().set(callKey, value, Duration.ofSeconds(CALL_STATE_TTL_SECONDS));
    }

    /**
     * Ustawia klucz timeout w Redis dla połączenia dialera.
     *
     * <p>Po wygaśnięciu TTL (30s) zewnętrzny komponent (np. Redis Keyspace Notification
     * lub scheduled job) może zareagować na brak odpowiedzi. W tej implementacji
     * {@link DialerCallbackHandler} sprawdza klucz przy przetwarzaniu wyników połączeń.
     *
     * @param callSid identyfikator sesji telefonicznej
     */
    private void scheduleNoAnswerTimeout(String callSid) {
        String timeoutKey = "dialer:timeout:" + callSid;
        redisTemplate.opsForValue().set(timeoutKey, "", Duration.ofSeconds(NO_ANSWER_TIMEOUT_SECONDS));
    }

    /**
     * Zwalnia blokadę agenta w Redis.
     *
     * @param agentId UUID agenta
     */
    private void releaseAgentLock(UUID agentId) {
        redisTemplate.delete("dialer:agent:" + agentId);
    }

    // =========================================================================
    // Pomocnicze – walidacja
    // =========================================================================

    /**
     * Sprawdza czy agent posiada wszystkie wymagane umiejętności (skills) kolejki kampanii.
     *
     * <p>Logika:
     * <ul>
     *   <li>Jeśli kolejka kampanii nie ma wymaganych skills ({@code requiredSkills} puste) → agent kwalifikuje się</li>
     *   <li>Jeśli kolejka nie istnieje (np. usunięta po przypisaniu) → agent kwalifikuje się (fail-open)</li>
     *   <li>Jeśli agent nie istnieje → agent nie kwalifikuje się</li>
     *   <li>Agent musi posiadać WSZYSTKIE skills wymagane przez kolejkę (superset)</li>
     * </ul>
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     * @param campaign kampania z ustawionym queueId
     * @return true gdy agent spełnia wymagania skills kolejki
     */
    private boolean agentHasRequiredSkills(UUID agentId, UUID tenantId, Campaign campaign) {
        Queue queue = queueRepository.findByIdAndTenantId(campaign.getQueueId(), tenantId)
                .orElse(null);

        if (queue == null) {
            log.warn("[Dialer] Kolejka {} kampanii {} nie istnieje – pomijam filtr skills (fail-open)",
                    campaign.getQueueId(), campaign.getCampaignId());
            return true;
        }

        List<String> requiredSkills = queue.getRequiredSkills();
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return true;
        }

        AppUser agent = appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)
                .orElse(null);

        if (agent == null) {
            log.warn("[Dialer] Agent {} nie istnieje lub jest usunięty (tenant={})", agentId, tenantId);
            return false;
        }

        List<String> agentSkills = agent.getSkills();
        if (agentSkills == null) {
            return false;
        }

        boolean hasAll = agentSkills.containsAll(requiredSkills);
        if (!hasAll) {
            log.debug("[Dialer] Agent {} ma skills={}, wymagane={} – nie kwalifikuje się do kampanii {}",
                    agentId, agentSkills, requiredSkills, campaign.getCampaignId());
        }
        return hasAll;
    }

    /**
     * Maskuje numer telefonu dla logów (widoczne ostatnie 4 cyfry).
     *
     * @param phone numer telefonu
     * @return zamaskowany numer (np. "+48****5678")
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "****";
        }
        return phone.substring(0, phone.length() - 4).replaceAll("\\d", "*")
                + phone.substring(phone.length() - 4);
    }

    /**
     * Ustawia kontekst tenant RLS dla JdbcTemplate przez parametryzowane zapytanie.
     *
     * <p>Używa bindowania parametru zamiast string concatenation – eliminuje ryzyko
     * SQL injection i konwencjonalnie jest zgodne z pozostałymi repozytoriami projektu.
     *
     * @param tenantId UUID tenanta
     */
    private void setTenantContextInJdbc(UUID tenantId) {
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });
    }

    /**
     * Pobiera strefę czasową z harmonogramu kampanii. Domyślnie: Europe/Warsaw.
     *
     * @param schedule mapa harmonogramu JSONB
     * @return ZoneId strefy czasowej
     */
    private ZoneId resolveTimezone(Map<String, Object> schedule) {
        String timezone = (String) schedule.get("timezone");
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("[Dialer] Nieznana strefa czasowa '{}', używam Europe/Warsaw", timezone);
            return DEFAULT_ZONE;
        }
    }
}
