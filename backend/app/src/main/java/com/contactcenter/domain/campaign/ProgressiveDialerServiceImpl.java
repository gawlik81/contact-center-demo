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
import com.contactcenter.infrastructure.lock.AdvisoryLockKeys;
import com.contactcenter.infrastructure.lock.LockSkipMetrics;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.function.Supplier;

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

    /** Params: agent_id, tenant_id. See {@link #isAgentStillFree}. */
    static final String AGENT_STILL_FREE_SQL =
            "SELECT EXISTS (SELECT 1 FROM app_user u WHERE u.user_id = p.agent_id AND u.tenant_id = p.tenant_id"
                    + " AND u.status = 'AVAILABLE' AND u.is_deleted = FALSE)"
                    + " AND NOT EXISTS (SELECT 1 FROM contact c WHERE c.agent_id = p.agent_id"
                    + " AND c.tenant_id = p.tenant_id AND c.status IN ("
                    + com.contactcenter.domain.contact.OccupiedSlot.AGENT_LOAD_STATUSES_SQL + ")"
                    + " AND (c.channel = 'PHONE' OR c.channel IS NULL))"
                    + " AND NOT EXISTS (SELECT 1 FROM campaign_contact d WHERE d.tenant_id = p.tenant_id"
                    + " AND d.dialing_agent_id = p.agent_id AND d.status = 'DIALING')"
                    + " FROM (SELECT ?::uuid AS agent_id, ?::uuid AS tenant_id) p";

    // Domyślna strefa czasowa kampanii
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Warsaw");

    // Numer wychodzący (fallback gdy kampania nie ma skonfigurowanego numeru)
    @Value("${telephony.outbound-number:+48000000000}")
    private String defaultOutboundNumber;

    /**
     * BE-173: how long one dial attempt keeps retrying the campaign lock when another instance holds it. The holder's
     * critical section is a few milliseconds (it does NOT include telephony), so a short bounded wait turns a
     * collision into a tiny delay instead of a lost trigger (the next poll is 30 s away). 0 = single attempt.
     */
    @Value("${dialer.campaign-lock.wait-ms:500}")
    private long campaignLockWaitMs;

    @Value("${dialer.campaign-lock.retry-interval-ms:25}")
    private long campaignLockRetryIntervalMs = 25;

    private final CampaignRepository campaignRepository;
    private final CampaignAssignmentRepository campaignAssignmentRepository;
    private final CampaignContactRepository campaignContactRepository;
    private final ContactService contactService;
    private final UserService userService;
    private final TelephonyAdapter telephonyAdapter;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final TenantTwilioConfigService tenantTwilioConfigService;
    /** BE-173: short claim transactions (the dial itself runs OUTSIDE any transaction). */
    private final PlatformTransactionManager transactionManager;
    private final LockSkipMetrics lockSkipMetrics;
    private volatile TransactionTemplate txTemplate;

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
            if (Thread.currentThread().isInterrupted()) {
                log.info("[Dialer] pollAvailableAgents przerwany (shutdown) - kończę przebieg");
                return;
            }
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
     * tę metodę równolegle dla tego samego agenta (także między instancjami: Redis jest wspólny). Blokada jest
     * zwalniana gdy:
     * <ul>
     *   <li>brak kampanii lub kontaktów (zwolnienie jawne)</li>
     *   <li>agent przestał być wolny (status != AVAILABLE albo ma otwarte połączenie telefoniczne)</li>
     *   <li>błąd po stronie wywołującego (zwolnienie przez wywołującego, nie tę metodę)</li>
     *   <li>wygaśnięcie TTL (60s, failsafe)</li>
     * </ul>
     * Gdy połączenie zostaje zainicjowane, blokada pozostaje aktywna przez cały czas trwania
     * połączenia (do momentu obsługi dyspozycji przez {@link DialerCallbackHandler}).
     *
     * <p><strong>BE-173 - granice transakcji:</strong> metoda NIE jest transakcyjna. Przydział (claim) kontaktu to
     * krótka transakcja ({@link #claimNextContact}): {@code pg_try_advisory_xact_lock} per kampania + weryfikacja, że
     * agent nadal jest wolny + {@code FOR UPDATE SKIP LOCKED} + warunkowe {@code DIALING}. Blokada advisory zwalnia się
     * z commitem, ZANIM wystartuje wywołanie telefonii (HTTP do Twilio) - transakcja nigdy nie obejmuje I/O sieciowego.
     * Skutek: wiele instancji obsługuje RÓŻNE kampanie równolegle, tę samą kampanię - przydziela się po kolei.
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     */
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

        try {
            dialPass(agentId, tenantId);
        } catch (RuntimeException e) {
            // Nieoczekiwany błąd: nie trzymaj agenta zablokowanego do wygaśnięcia TTL (60 s). Przy udanym
            // połączeniu dialPass zwraca normalnie i token zostaje.
            releaseAgentLock(agentId);
            throw e;
        }
    }

    /** Przebieg po zdobyciu tokenu agenta; na ścieżkach bez połączenia zwalnia token sam. */
    private void dialPass(UUID agentId, UUID tenantId) {
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

            // Krótka transakcja: blokada kampanii + agent nadal wolny + kontakt -> DIALING
            ClaimResult claim = claimWithBoundedWait(campaign.getCampaignId(), tenantId, agentId);
            switch (claim.outcome()) {
                case INTERRUPTED -> {
                    // wątek przerwany (shutdown): nie liczymy skip, nie próbujemy kolejnych kampanii
                    releaseAgentLock(agentId);
                    return;
                }
                case LOCK_BUSY -> {
                    // inna instancja (lub wątek) przydziela teraz kontakty tej kampanii - spróbuje dla agenta
                    // przy następnym wyzwalaczu; agent zostaje wolny (blokada Redis zwolniona na końcu)
                    lockSkipMetrics.recordSkipped(LockSkipMetrics.TYPE_DIALER);
                    log.debug("[Dialer] Kampania {} przetwarzana przez inną instancję – pomijam (agent={})",
                            campaign.getCampaignId(), agentId);
                    continue;
                }
                case AGENT_NOT_FREE -> {
                    log.debug("[Dialer] Agent {} nie jest już wolny (status/połączenie) – nie dzwonię", agentId);
                    releaseAgentLock(agentId);
                    return;
                }
                case NO_CONTACT, SKIPPED -> {
                    continue;
                }
                case CLAIMED -> {
                    if (dialClaimedContact(campaign, claim, agentId, tenantId)) {
                        // Sukces – blokada agenta pozostaje aktywna (TTL 60s)
                        return;
                    }
                    // błąd telefonii (kontakt oznaczony ERROR) – spróbuj kolejnej kampanii
                }
            }
        }

        // Żadna kampania nie dała kontaktu – zwolnij blokadę
        releaseAgentLock(agentId);
        log.debug("[Dialer] Brak kontaktów do zadzwonienia dla agenta {} (tenant={})", agentId, tenantId);
    }

    /**
     * Inicjuje połączenie dla już przydzielonego (DIALING) kontaktu. Działa POZA transakcją.
     *
     * @return {@code true} gdy połączenie zainicjowane; {@code false} gdy błąd telefonii (kontakt = ERROR)
     */
    private boolean dialClaimedContact(Campaign campaign, ClaimResult claim, UUID agentId, UUID tenantId) {
        UUID recordId = claim.recordId();
        UUID campaignId = campaign.getCampaignId();
        CallSession session;
        try {
            log.info("[Dialer] Inicjowanie połączenia: kampania={}, kontakt={}, numer={}, agent={}, tenant={}",
                    campaignId, recordId, maskPhone(claim.phone()), agentId, tenantId);

            String fromNumber = resolveFromNumber(campaign, tenantId);
            session = telephonyAdapter.initiateCall(tenantId, fromNumber, claim.phone(), agentId,
                    campaignId, null); // callbackId null – połączenie z kampanii, nie oddzwonienie
        } catch (TelephonyAdapter.TelephonyException e) {
            log.error("[Dialer] Błąd telefonii dla kontaktu {} w kampanii {}: {}",
                    recordId, campaignId, e.getMessage());
            // ERROR (nie FAILED) – błąd adaptera telefonii (np. Twilio ApiException)
            // jest trwały i nie kwalifikuje rekordu do ponowienia przez dialer.
            inTx(() -> {
                markContactStatus(recordId, campaignId, tenantId, "ERROR", null);
                return null;
            });
            return false;
        } catch (RuntimeException e) {
            // Wcześniej cofała to transakcja obejmująca całość; teraz cofamy claim jawnie (DIALING -> PENDING).
            revertClaimQuietly(recordId, campaignId, tenantId);
            throw e;
        }

        // Stan w Redis najpierw - po udanym initiateCall połączenie istnieje. Gdy Redis padnie, nie ma po czym
        // DialerCallbackHandler/ring-timeout rozpoznać tego połączenia, a rekord zostałby DIALING na zawsze.
        // Rekord NIE wraca do PENDING (to byłby dubel połączenia): wynik jest nieznany, więc traktujemy próbę jak
        // NO_ANSWER z opóźnionym ponowieniem (albo NOT_REACHED po wyczerpaniu prób) - jak handleNoAnswer.
        try {
            saveCallState(session.getCallId(), recordId, campaignId, agentId, tenantId);
            scheduleNoAnswerTimeout(session.getCallId(), campaign.getRingTimeoutSeconds());
        } catch (RuntimeException e) {
            lockSkipMetrics.recordCallStateLost();
            log.error("[Dialer] Połączenie {} zainicjowane, ale zapis stanu w Redis nie powiódł się (kontakt {}, "
                    + "kampania {}): {} - rekord przenoszony z DIALING do NO_ANSWER/NOT_REACHED",
                    session.getCallId(), recordId, campaignId, e.getMessage(), e);
            try {
                inTx(() -> markCallStateLost(recordId, campaignId, tenantId,
                        campaign.getMaxAttempts(), campaign.getRetryDelayMinutes()));
            } catch (RuntimeException ex) {
                log.error("[Dialer] Nie udało się wyjść z DIALING dla kontaktu {} (kampania {}) - zostaje reaperowi: {}",
                        recordId, campaignId, ex.getMessage(), ex);
            }
        }

        // BE-085: Zapisz powiązanie contact ↔ campaign_contact_record i zaktualizuj last_contact_id
        if (session.getContactId() != null) {
            try {
                contactService.updateCampaignContactRecordId(session.getContactId(), recordId, tenantId);
                campaignContactRepository.updateLastContactId(recordId, campaignId, session.getContactId());
            } catch (RuntimeException e) {
                log.error("[Dialer] Połączenie {} zainicjowane, ale zapis powiązania contact↔kontakt kampanii " +
                        "{} nie powiódł się: {}", session.getCallId(), recordId, e.getMessage(), e);
            }
        }

        log.info("[Dialer] Połączenie zainicjowane: callId={}, kontakt={}, kampania={}",
                session.getCallId(), recordId, campaignId);
        return true;
    }

    // =========================================================================
    // BE-173 - claim kontaktu pod blokadą kampanii (krótka transakcja)
    // =========================================================================

    /** Wynik przydziału kontaktu w {@link #claimNextContact}. */
    enum ClaimOutcome { CLAIMED, LOCK_BUSY, AGENT_NOT_FREE, NO_CONTACT, SKIPPED, INTERRUPTED }

    /** Wynik claim; {@code recordId}/{@code phone} tylko dla {@link ClaimOutcome#CLAIMED}. */
    record ClaimResult(ClaimOutcome outcome, UUID recordId, String phone) {
        static ClaimResult of(ClaimOutcome outcome) {
            return new ClaimResult(outcome, null, null);
        }
    }

    /**
     * Przydziela następny kontakt kampanii agentowi. MUSI działać w krótkiej transakcji wołającego
     * ({@code pg_try_advisory_xact_lock} i {@code set_tenant_context} są transaction-scoped).
     *
     * <ol>
     *   <li>{@code pg_try_advisory_xact_lock(NAMESPACE_DIALER_CAMPAIGN, hash(campaignId))}: USZEREGOWANIE przydziałów
     *       jednej kampanii i ochrona przed zbędną pracą - NIE mechanizm poprawności (tę dają punkty 2-4). Brak
     *       blokady = {@link ClaimOutcome#LOCK_BUSY}, wołający czeka krótko ({@code dialer.campaign-lock.wait-ms})</li>
     *   <li>blokada per agent ({@code AdvisoryLockKeys#AGENT_ASSIGNMENT_LOCK_SQL}, ten sam klucz co
     *       {@code ContactRepository#assignIfQueued}; UWAGA: {@code assignIfQueued} bierze ją tylko przy limicie async,
     *       a routing PHONE nie bierze jej wcale - blokada szereguje więc dialer x dialer i dialer x przydział async,
     *       NIE dialer x routing telefoniczny; to otwarty ticket P0) + ponowna weryfikacja POD blokadami: agent {@code AVAILABLE},
     *       bez otwartego połączenia PHONE i bez własnego rekordu w DIALING ({@code dialing_agent_id}, V151)</li>
     *   <li>{@code FOR UPDATE SKIP LOCKED}</li>
     *   <li>{@code UPDATE ... SET status='DIALING', dialing_agent_id=:agent WHERE status IN (PENDING, NO_ANSWER) AND
     *       NOT EXISTS (inny DIALING tego agenta)} ze sprawdzeniem liczby zmienionych wierszy - warunkowy zapis
     *       przydziału agenta, niezależny od Redis. Token Redis {@code dialer:agent:*} (TTL 60 s) jest dodatkowym
     *       pasem bezpieczeństwa.</li>
     * </ol>
     * Poza zakresem (osobny ticket P0): ten sam agent wybierany równolegle przez routing PHONE - patrz 04-backend.md §4.6.
     * {@code dialing_agent_id} jest ważne tylko przy statusie DIALING (niezmiennik V151: trigger + CHECK + zapisy Java).
     */
    ClaimResult claimNextContact(UUID campaignId, UUID tenantId, UUID agentId) {
        setTenantContextInJdbc(tenantId);

        Boolean locked = jdbcTemplate.queryForObject(AdvisoryLockKeys.TRY_XACT_LOCK_SQL, Boolean.class,
                AdvisoryLockKeys.NAMESPACE_DIALER_CAMPAIGN, campaignId.toString());
        if (!Boolean.TRUE.equals(locked)) {
            return ClaimResult.of(ClaimOutcome.LOCK_BUSY);
        }

        // Per-agent lock (the same key as ContactRepository#assignIfQueued, BE-156): the "agent still free" check
        // and the conditional DIALING write below are one serialised step per agent, independent of Redis.
        jdbcTemplate.queryForRowSet(AdvisoryLockKeys.AGENT_ASSIGNMENT_LOCK_SQL,
                AdvisoryLockKeys.agentAssignmentKey(tenantId, agentId));
        if (!isAgentStillFree(agentId, tenantId)) {
            return ClaimResult.of(ClaimOutcome.AGENT_NOT_FREE);
        }

        // Pobierz następny kontakt z pessimistic lock
        Map<String, Object> contact = fetchNextPendingContact(campaignId, tenantId);
        if (contact == null) {
            log.debug("[Dialer] Brak kontaktów PENDING w kampanii {}", campaignId);
            return ClaimResult.of(ClaimOutcome.NO_CONTACT);
        }

        UUID recordId = UUID.fromString((String) contact.get("record_id"));
        String phone = (String) contact.get("phone");

        if (phone == null || phone.isBlank()) {
            log.warn("[Dialer] Kontakt {} nie ma numeru telefonu – pomijam i oznaczam SKIPPED", recordId);
            markContactStatus(recordId, campaignId, tenantId, "SKIPPED", null);
            return ClaimResult.of(ClaimOutcome.SKIPPED);
        }

        // Oznacz kontakt jako DIALING (warunkowo - 0 wierszy = ktoś inny już go wziął albo agent już dzwoni)
        if (markDialing(recordId, campaignId, tenantId, agentId) != 1) {
            log.debug("[Dialer] Kontakt {} zmienił stan w międzyczasie albo agent {} już dzwoni – pomijam",
                    recordId, agentId);
            return ClaimResult.of(ClaimOutcome.NO_CONTACT);
        }
        return new ClaimResult(ClaimOutcome.CLAIMED, recordId, phone);
    }

    /** One claim transaction per attempt; retries only {@code LOCK_BUSY}, for at most {@code campaignLockWaitMs}. */
    private ClaimResult claimWithBoundedWait(UUID campaignId, UUID tenantId, UUID agentId) {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(Math.max(0, campaignLockWaitMs)).toNanos();
        while (true) {
            ClaimResult result = inTx(() -> claimNextContact(campaignId, tenantId, agentId));
            if (result.outcome() != ClaimOutcome.LOCK_BUSY || System.nanoTime() >= deadlineNanos) {
                return result;
            }
            try {
                Thread.sleep(Math.max(5, campaignLockRetryIntervalMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ClaimResult.of(ClaimOutcome.INTERRUPTED);
            }
        }
    }

    /**
     * Agent nadal wolny: status AVAILABLE i brak otwartego połączenia telefonicznego (QUEUED/ASSIGNED/ACTIVE/ON_HOLD
     * z kanałem PHONE - wychodzące połączenie agenta jest wstawiane jako QUEUED z agent_id, patrz
     * {@code OccupiedSlot}). Kontakty async (e-mail/czat) NIE blokują agenta - tak jak przed BE-173.
     */
    private boolean isAgentStillFree(UUID agentId, UUID tenantId) {
        Boolean free = jdbcTemplate.queryForObject(AGENT_STILL_FREE_SQL, Boolean.class,
                agentId.toString(), tenantId.toString());
        return Boolean.TRUE.equals(free);
    }

    /** Executes {@code work} in its own short transaction (set_tenant_context and advisory xact locks are tx-local). */
    private <T> T inTx(Supplier<T> work) {
        TransactionTemplate template = txTemplate;
        if (template == null) {
            template = new TransactionTemplate(transactionManager);   // thread-safe once configured
            txTemplate = template;
        }
        return template.execute(status -> work.get());
    }

    /** Compensation for an unexpected failure after the claim: DIALING -> PENDING, never throws. */
    private void revertClaimQuietly(UUID recordId, UUID campaignId, UUID tenantId) {
        try {
            campaignContactRepository.revertDialingToPending(recordId, campaignId, tenantId);
        } catch (RuntimeException e) {
            log.error("[Dialer] Nie udało się cofnąć DIALING -> PENDING dla kontaktu {} (kampania {}): {}",
                    recordId, campaignId, e.getMessage(), e);
        }
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
                ORDER BY created_at ASC, record_id ASC
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
     * <p>Przejście na DIALING: {@link #markDialing} (warunkowe, z liczbą wierszy).
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

        jdbcTemplate.update(
                """
                UPDATE campaign_contact
                SET status = ?,
                    dialing_agent_id = NULL,
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

        log.debug("[Dialer] Kontakt {} → status={}", recordId, newStatus);
    }

    /**
     * Przejście na DIALING (ustawia {@code last_attempt_at = NOW()}, inkrementuje {@code attempt_count},
     * zapisuje {@code dialing_agent_id}), WARUNKOWE: tylko ze statusu PENDING/NO_ANSWER i tylko gdy ten agent nie ma
     * już innego rekordu w DIALING (V151). Wywoływane pod blokadą per agent, więc to compare-and-set, a nie tylko
     * odczyt.
     *
     * @return liczba zmienionych wierszy (0 = rekord zmienił stan albo agent już dzwoni)
     */
    private int markDialing(UUID recordId, UUID campaignId, UUID tenantId, UUID agentId) {
        setTenantContextInJdbc(tenantId);
        int rows = jdbcTemplate.update(
                """
                UPDATE campaign_contact
                SET status = 'DIALING',
                    dialing_agent_id = ?::uuid,
                    last_attempt_at = NOW(),
                    attempt_count = attempt_count + 1,
                    updated_at = NOW()
                WHERE record_id = ?::uuid
                  AND campaign_id = ?::uuid
                  AND tenant_id = ?::uuid
                  AND status IN ('PENDING', 'NO_ANSWER')
                  AND NOT EXISTS (SELECT 1 FROM campaign_contact o
                                   WHERE o.tenant_id = ?::uuid AND o.dialing_agent_id = ?::uuid
                                     AND o.status = 'DIALING')
                """,
                agentId.toString(),
                recordId.toString(),
                campaignId.toString(),
                tenantId.toString(),
                tenantId.toString(),
                agentId.toString()
        );
        log.debug("[Dialer] Kontakt {} → status=DIALING (rows={})", recordId, rows);
        return rows;
    }

    /**
     * Połączenie wykonane, ale jego stan jest nie do odtworzenia: DIALING -> NO_ANSWER z ponowieniem po
     * {@code retryDelayMinutes} albo NOT_REACHED, gdy próby wyczerpane (bez wzrostu attempt_count - próba była już
     * policzona przy claim). Działa tylko na rekordzie nadal w DIALING.
     */
    private Void markCallStateLost(UUID recordId, UUID campaignId, UUID tenantId, int maxAttempts, int retryDelayMinutes) {
        setTenantContextInJdbc(tenantId);
        jdbcTemplate.update(CALL_STATE_LOST_SQL, maxAttempts, maxAttempts, retryDelayMinutes,
                recordId.toString(), campaignId.toString(), tenantId.toString());
        return null;
    }

    /** Params: maxAttempts, maxAttempts, retryDelayMinutes, record_id, campaign_id, tenant_id. */
    static final String CALL_STATE_LOST_SQL = """
            UPDATE campaign_contact
            SET status = CASE WHEN attempt_count >= ? THEN 'NOT_REACHED' ELSE 'NO_ANSWER' END,
                next_attempt_at = CASE WHEN attempt_count >= ? THEN NULL
                                       ELSE NOW() + (? * INTERVAL '1 minute') END,
                dialing_agent_id = NULL,
                updated_at = NOW()
            WHERE record_id = ?::uuid
              AND campaign_id = ?::uuid
              AND tenant_id = ?::uuid
              AND status = 'DIALING'
            """;

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
