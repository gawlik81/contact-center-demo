package com.contactcenter.domain.campaign;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.domain.tenant.TenantTwilioConfigService;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * Implementacja {@link ProgressiveDialerService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dialer.enabled", havingValue = "true", matchIfMissing = true)
class ProgressiveDialerServiceImpl implements ProgressiveDialerService {

    // Redis TTL (sekundy)
    private static final int AGENT_LOCK_TTL_SECONDS   = 60;
    private static final int CALL_STATE_TTL_SECONDS   = 1800; // 30 minut – chroni przed wygaśnięciem klucza w trakcie dłuższej rozmowy

    // Domyślna strefa czasowa kampanii
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Warsaw");

    // Numer wychodzący (fallback gdy kampania nie ma skonfigurowanego numeru)
    @Value("${telephony.outbound-number:+48000000000}")
    private String defaultOutboundNumber;

    private final CampaignRepository campaignRepository;
    private final CampaignAssignmentRepository campaignAssignmentRepository;
    private final CampaignContactRepository campaignContactRepository;
    private final ContactService contactService;
    private final UserService userService;
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
    @Override
    public void onAgentStatusChanged(AgentStatusChangedEvent event) {
        // Filtrujemy tylko zdarzenia AVAILABLE
        if (event.newStatus() != UserStatus.AVAILABLE) {
            return;
        }

        UUID agentId = event.userId();
        UUID tenantId = event.tenantId();

        log.debug("[Dialer] Agent {} zmienił status na AVAILABLE (tenant={})", agentId, tenantId);

        // Ustawiamy TenantContext dla wątku RabbitMQ
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(agentId);

        try {
            // Wywołanie przez self-proxy – gwarantuje działanie @Transactional (uniknięcie self-invocation)
            // Blokada Redis (SET NX) jest zarządzana wewnątrz initiateDialForAgent
            self.initiateDialForAgent(agentId, tenantId);
        } catch (Exception e) {
            log.error("[Dialer] Błąd podczas inicjowania połączenia dla agenta {}: {}", agentId, e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Scheduled polling – uzupełnienie event-driven triggering
    // =========================================================================

    /**
     * Cyklicznie wyzwala logikę dialera dla wszystkich aktualnie AVAILABLE agentów.
     *
     * <p>Uzupełnienie event-driven triggeringu (RabbitMQ): obsługuje przypadki gdy
     * kontakt kampanijny trafił do kolejki zanim agent zmienił status lub gdy event zaginął.
     *
     * <p>Używa {@code fixedDelay} – kolejna iteracja startuje PO zakończeniu poprzedniej
     * (ochrona przed nakładaniem się przy wolnych tenantach).
     *
     * <p>Ochrona przed race condition jest w {@link #initiateDialForAgent} (Redis SET NX)
     * – jeśli agent jest już obsługiwany przez dialer, metoda zwróci bez działania.
     */
    @Scheduled(fixedDelayString = "${dialer.agent-poll-interval-ms:30000}")
    @Override
    public void pollAvailableAgents() {
        List<AppUser> availableAgents = userService.findAvailableAgents();

        if (availableAgents.isEmpty()) {
            log.debug("[Dialer] pollAvailableAgents: brak AVAILABLE agentów – pomijam");
            return;
        }

        log.debug("[Dialer] pollAvailableAgents: sprawdzam {} AVAILABLE agentów",
                availableAgents.size());

        for (AppUser agent : availableAgents) {
            UUID agentId  = agent.getId();
            UUID tenantId = agent.getTenantId();

            TenantContext.setTenantId(tenantId);
            TenantContext.setUserId(agentId);
            try {
                self.initiateDialForAgent(agentId, tenantId);
            } catch (Exception e) {
                log.warn("[Dialer] pollAvailableAgents: błąd dla agenta {}: {}",
                        agentId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    // =========================================================================
    // Logika dialera
    // =========================================================================

    /**
     * Inicjuje połączenie wychodzące dla dostępnego agenta.
     *
     * <p>Przed właściwą logiką ustawia blokadę Redis (SET NX) dla agenta – chroni przed
     * race condition gdy {@code onAgentStatusChanged} i {@code pollAvailableAgents} wywołają
     * tę metodę równolegle dla tego samego agenta. Blokada jest zwalniana gdy:
     * <ul>
     *   <li>brak kampanii lub kontaktów (zwolnienie jawne)</li>
     *   <li>błąd po stronie wywołującego (zwolnienie przez wywołującego, nie tę metodę)</li>
     *   <li>wygaśnięcie TTL (60s, failsafe)</li>
     * </ul>
     * Gdy połączenie zostaje zainicjowane, blokada pozostaje aktywna przez cały czas trwania
     * połączenia (do momentu obsługi dyspozycji przez {@link DialerCallbackHandler}).
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     */
    @Transactional
    @Override
    public void initiateDialForAgent(UUID agentId, UUID tenantId) {
        // Ochrona przed race condition: SET NX z TTL 60s
        // Pojedyncze miejsce zarządzania blokadą – niezależnie od tego, czy wywołanie pochodzi
        // z onAgentStatusChanged (RabbitMQ) czy pollAvailableAgents (Scheduler)
        String agentLockKey = "dialer:agent:" + agentId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(agentLockKey, "locked", Duration.ofSeconds(AGENT_LOCK_TTL_SECONDS));
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("[Dialer] Agent {} już obsługiwany przez dialer – blokada aktywna, pomijam", agentId);
            return;
        }

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

            // Sprawdź czy agent jest kwalifikowany do kampanii (trójpoziomowe: allAgents, direct, przez grupy)
            if (!isAgentEligibleForCampaign(agentId, campaign, tenantId)) {
                log.debug("[Dialer] Agent {} nie jest kwalifikowany do kampanii {} – pomijam",
                        agentId, campaign.getCampaignId());
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
                        campaign.getCampaignId(), null); // callbackId null – połączenie z kampanii, nie oddzwonienie

                // BE-085: Zapisz powiązanie contact ↔ campaign_contact_record i zaktualizuj last_contact_id
                if (session != null && session.getContactId() != null) {
                    contactService.updateCampaignContactRecordId(
                            session.getContactId(), recordId, tenantId);
                    campaignContactRepository.updateLastContactId(
                            recordId, campaign.getCampaignId(), session.getContactId());
                }

                // Zapisz stan w Redis
                saveCallState(session.getCallId(), recordId, campaign.getCampaignId(), agentId, tenantId);
                scheduleNoAnswerTimeout(session.getCallId(), campaign.getRingTimeoutSeconds());

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
    @Override
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
    @Override
    public void saveCallState(String callSid, UUID campaignContactId,
                               UUID campaignId, UUID agentId, UUID tenantId) {
        String callKey = "dialer:call:" + callSid;
        String value = campaignContactId + "," + campaignId + "," + agentId + "," + tenantId;
        redisTemplate.opsForValue().set(callKey, value, Duration.ofSeconds(CALL_STATE_TTL_SECONDS));
    }

    /**
     * Ustawia klucz timeout w Redis dla połączenia dialera.
     *
     * <p>Po wygaśnięciu TTL zewnętrzny komponent (np. Redis Keyspace Notification
     * lub scheduled job) może zareagować na brak odpowiedzi. W tej implementacji
     * {@link DialerCallbackHandler} sprawdza klucz przy przetwarzaniu wyników połączeń.
     *
     * @param callSid        identyfikator sesji telefonicznej
     * @param timeoutSeconds czas oczekiwania na odebranie (konfigurowany per kampania)
     */
    @Override
    public void scheduleNoAnswerTimeout(String callSid, int timeoutSeconds) {
        String timeoutKey = "dialer:timeout:" + callSid;
        redisTemplate.opsForValue().set(timeoutKey, "", Duration.ofSeconds(timeoutSeconds));
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
     * <p>Logika trójpoziomowa (BE-081):
     * <ul>
     *   <li>allAgents=true → każdy AVAILABLE agent tenanta kwalifikuje się</li>
     *   <li>allAgents=false + brak przypisań → kampania pominięta (WARN log)</li>
     *   <li>allAgents=false + przypisania → agent kwalifikuje się tylko jeśli jest w zbiorze eligible</li>
     * </ul>
     *
     * @param agentId  UUID agenta
     * @param campaign kampania
     * @param tenantId UUID tenanta
     * @return true gdy agent kwalifikuje się do kampanii
     */
    private boolean isAgentEligibleForCampaign(UUID agentId, Campaign campaign, UUID tenantId) {
        if (campaign.isAllAgents()) {
            return true;
        }
        Set<UUID> eligible = campaignAssignmentRepository
                .resolveEligibleAgentIds(campaign.getCampaignId(), tenantId);
        if (eligible.isEmpty()) {
            log.warn("[Dialer] Kampania {} (all_agents=false) nie ma przypisanych agentów — pomijam",
                    campaign.getCampaignId());
            return false;
        }
        return eligible.contains(agentId);
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

    // =========================================================================
    // Dostęp do encji campaign_contact (encapsulation pass – pkt 9 wzorca)
    // =========================================================================

    @Transactional(readOnly = true)
    @Override
    public Map<UUID, Map<String, Long>> getContactCountsByStatus(UUID tenantId, List<UUID> campaignIds, List<String> statuses) {
        return campaignContactRepository.countByStatusGroupedByCampaign(tenantId, campaignIds, statuses);
    }

    @Transactional(readOnly = true)
    @Override
    public List<Map<String, Object>> findPendingRecordsByCampaignIds(UUID tenantId, List<UUID> campaignIds) {
        return campaignContactRepository.findPendingByCampaignIds(tenantId, campaignIds);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<Map<String, Object>> findRecordForManualDial(UUID recordId, UUID campaignId, UUID tenantId) {
        return campaignContactRepository.findRecordForManualDial(recordId, campaignId, tenantId);
    }

    @Transactional
    @Override
    public void markRecordAsDialing(UUID recordId, UUID campaignId, UUID tenantId) {
        campaignContactRepository.markAsDialing(recordId, campaignId, tenantId);
    }

    @Transactional
    @Override
    public void markRecordAsError(UUID recordId, UUID campaignId, UUID tenantId) {
        campaignContactRepository.markAsError(recordId, campaignId, tenantId);
    }

    @Transactional
    @Override
    public void updateLastContactId(UUID recordId, UUID campaignId, UUID contactId) {
        campaignContactRepository.updateLastContactId(recordId, campaignId, contactId);
    }
}
