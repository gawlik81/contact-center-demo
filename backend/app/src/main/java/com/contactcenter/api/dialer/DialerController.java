package com.contactcenter.api.dialer;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.dialer.dto.CreateCallbackRequest;
import com.contactcenter.api.dialer.dto.CreateInboundCallbackRequest;
import com.contactcenter.api.dialer.dto.DialerStatusResponse;
import com.contactcenter.api.dialer.dto.ManualCallRequest;
import com.contactcenter.api.dialer.dto.ManualCallResponse;
import com.contactcenter.api.dialer.dto.ManualCampaignRecordsResponse;
import com.contactcenter.api.dialer.dto.RescheduleCallbackRequest;
import com.contactcenter.api.dialer.dto.ScheduledCallbackResponse;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.repository.CampaignContactRepository;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.service.DialerCallbackHandler;
import com.contactcenter.domain.service.ProgressiveDialerService;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.security.TenantContext;
import org.springframework.security.access.AccessDeniedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kontroler REST dla Progressive Dialer.
 *
 * <p>Implementuje BE-024: Progressive Dialer.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET  /api/dialer/status               – stan dialera per tenant (aktywne kampanie, połączenia)</li>
 *   <li>GET  /api/dialer/callbacks             – lista zaplanowanych oddzwonień z paginacją</li>
 *   <li>POST /api/dialer/callbacks             – utwórz zaplanowane oddzwonienie (dyspozycja CALLBACK)</li>
 *   <li>GET  /api/dialer/manual/records        – kampanie manualne z rekordami PENDING (widok agenta)</li>
 *   <li>POST /api/dialer/manual/call           – ręczne inicjowanie połączenia (tylko AGENT)</li>
 * </ul>
 *
 * <p>Endpointy zarządzania kampanią (start/pause/stop) są w
 * {@link com.contactcenter.api.campaign.CampaignController}.
 */
@Slf4j
@RestController
@RequestMapping("/api/dialer")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Dialer", description = "Progressive Dialer – automatyczne dzwonienie kampanii wychodzących")
public class DialerController {

    @Value("${telephony.outbound-number:+48000000000}")
    private String defaultOutboundNumber;

    private final ProgressiveDialerService progressiveDialerService;
    private final DialerCallbackHandler dialerCallbackHandler;
    private final CampaignRepository campaignRepository;
    private final CampaignContactRepository campaignContactRepository;
    private final ScheduledCallbackRepository scheduledCallbackRepository;
    private final TelephonyAdapter telephonyAdapter;
    private final ContactRepository contactRepository;

    // Statusy uwzględniane w podsumowaniu dialera
    private static final List<String> DIALER_STATUSES =
            List.of("PENDING", "DIALING", "COMPLETED", "NO_ANSWER", "FAILED");

    // =========================================================================
    // Stan dialera
    // =========================================================================

    /**
     * Zwraca aktualny stan dialera dla tenanta.
     *
     * <p>Zawiera listę aktywnych kampanii (status=RUNNING), liczbę kontaktów PENDING/DIALING,
     * informację o harmonogramie i łączną liczbę aktywnych połączeń dialera.
     *
     * <p>Implementacja: jedno zapytanie GROUP BY do {@link CampaignContactRepository}
     * zamiast N×3 zapytań COUNT – eliminuje problem N+1 queries.
     *
     * @return stan dialera
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Stan dialera",
        description = "Zwraca aktualny stan Progressive Dialer: aktywne kampanie, kontakty PENDING/DIALING, stan harmonogramu.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Stan dialera"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<DialerStatusResponse> getDialerStatus() {
        UUID tenantId = TenantContext.getTenantId();

        List<Campaign> runningCampaigns = campaignRepository.findRunningByTenantId(tenantId);

        if (runningCampaigns.isEmpty()) {
            return ResponseEntity.ok(new DialerStatusResponse(tenantId, List.of(), 0, Instant.now()));
        }

        List<UUID> campaignIds = runningCampaigns.stream()
                .map(Campaign::getCampaignId)
                .toList();

        // Jedno zapytanie GROUP BY zamiast N×3 COUNT – eliminacja N+1 queries
        Map<UUID, Map<String, Long>> countsByCampaign =
                campaignContactRepository.countByStatusGroupedByCampaign(tenantId, campaignIds, DIALER_STATUSES);

        List<DialerStatusResponse.ActiveCampaignSummary> summaries = new ArrayList<>();
        int totalActiveCalls = 0;

        for (Campaign campaign : runningCampaigns) {
            boolean inSchedule = progressiveDialerService.isInSchedule(campaign);
            Map<String, Long> counts = countsByCampaign.getOrDefault(campaign.getCampaignId(), Map.of());

            long pendingCount   = counts.getOrDefault("PENDING", 0L);
            long dialingCount   = counts.getOrDefault("DIALING", 0L);
            long completedCount = counts.getOrDefault("COMPLETED", 0L)
                                + counts.getOrDefault("NO_ANSWER", 0L)
                                + counts.getOrDefault("FAILED", 0L);

            totalActiveCalls += (int) dialingCount;

            summaries.add(new DialerStatusResponse.ActiveCampaignSummary(
                    campaign.getCampaignId(),
                    campaign.getName(),
                    pendingCount,
                    dialingCount,
                    completedCount,
                    inSchedule
            ));
        }

        DialerStatusResponse response = new DialerStatusResponse(
                tenantId,
                summaries,
                totalActiveCalls,
                Instant.now()
        );

        log.debug("[DialerController] Status dialera: tenant={}, aktywne kampanie={}, połączenia={}",
                tenantId, summaries.size(), totalActiveCalls);

        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Zaplanowane oddzwonienia
    // =========================================================================

    /**
     * Zwraca stronicowaną listę zaplanowanych oddzwonień (status=PENDING) dla tenanta.
     *
     * @param page numer strony (0-based, domyślnie 0)
     * @param size rozmiar strony (domyślnie 20, max 100)
     * @return stronicowana lista callbacków
     */
    @GetMapping("/callbacks")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Lista zaplanowanych oddzwonień",
        description = "Zwraca stronicowaną listę callbacków o statusie PENDING dla tenanta. " +
                      "Sortowanie: scheduledAt ASC (najwcześniejsze pierwsze).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista callbacków"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia")
        }
    )
    public ResponseEntity<PagedResponse<ScheduledCallbackResponse>> listCallbacks(
            @Parameter(description = "Numer strony (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Rozmiar strony (max 100)") @RequestParam(defaultValue = "20") int size
    ) {
        if (size > 100) {
            size = 100;
        }
        if (size < 1) {
            size = 1;
        }
        if (page < 0) {
            page = 0;
        }

        UUID tenantId = TenantContext.getTenantId();

        List<ScheduledCallback> callbacks = scheduledCallbackRepository.findPendingByTenantId(tenantId, page, size);
        long total = scheduledCallbackRepository.countPendingByTenantId(tenantId);

        List<ScheduledCallbackResponse> content = callbacks.stream()
                .map(ScheduledCallbackResponse::from)
                .toList();

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;

        PagedResponse<ScheduledCallbackResponse> response = new PagedResponse<>(
                content, page, size, total, totalPages, page == 0, page >= totalPages - 1
        );

        log.debug("[DialerController] Lista callbacków: tenant={}, total={}, page={}", tenantId, total, page);

        return ResponseEntity.ok(response);
    }

    /**
     * Tworzy zaplanowane oddzwonienie (dyspozycja CALLBACK agenta).
     *
     * <p>Agent rejestruje dyspozycję CALLBACK po zakończeniu rozmowy, podając
     * preferowany termin oddzwonienia i opcjonalną notatkę.
     *
     * <p>Bezpieczeństwo: dla roli AGENT pole {@code agentId} w żądaniu jest ignorowane –
     * zawsze używany jest agentId z JWT (zapobiega podmiance agentId przez złośliwego klienta).
     * Dla ról ADMIN/SUPERVISOR agentId z żądania jest akceptowany.
     *
     * @param request dane oddzwonienia
     * @return DTO utworzonego callbacku z HTTP 201 Created
     */
    @PostMapping("/callbacks")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Utwórz zaplanowane oddzwonienie",
        description = "Rejestruje dyspozycję CALLBACK agenta. " +
                      "Opcjonalnie aktualizuje status rekordu campaign_contact na COMPLETED. " +
                      "Dla roli AGENT pole agentId w żądaniu jest ignorowane – używany jest agentId z JWT.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Callback zaplanowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia")
        }
    )
    public ResponseEntity<ScheduledCallbackResponse> createCallback(
            @Valid @RequestBody CreateCallbackRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId = TenantContext.getUserId();
        String role = TenantContext.getUserRole();

        // Bug #6 fix: AGENT nie może podstawić innego agentId.
        // Dla ADMIN/SUPERVISOR agentId z żądania jest akceptowany (gdy nie null).
        UUID effectiveAgentId = resolveAgentId(request.agentId(), agentId, role);

        ScheduledCallback callback;

        // Jeśli podano campaignId i recordId – użyj handlera dyspozycji (aktualizuje campaign_contact)
        if (request.campaignId() != null && request.campaignContactRecordId() != null) {
            callback = dialerCallbackHandler.handleCallbackDisposition(
                    tenantId,
                    request.campaignId(),
                    request.campaignContactRecordId(),
                    effectiveAgentId,
                    request.phone(),
                    request.firstName(),
                    request.lastName(),
                    request.scheduledAt(),
                    request.notes()
            );
        } else {
            // Standalone callback – bez powiązania z kampanią
            ScheduledCallback newCallback = ScheduledCallback.builder()
                    .tenantId(tenantId)
                    .campaignId(request.campaignId())
                    .agentId(effectiveAgentId)
                    .phone(request.phone())
                    .firstName(request.firstName())
                    .lastName(request.lastName())
                    .scheduledAt(request.scheduledAt())
                    .notes(request.notes())
                    .status("PENDING")
                    .build();

            callback = scheduledCallbackRepository.save(newCallback);
        }

        log.info("[DialerController] Callback zaplanowany: callbackId={}, tenant={}, scheduledAt={}",
                callback.getCallbackId(), tenantId, callback.getScheduledAt());

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(callback.getCallbackId())
                .toUri();

        return ResponseEntity.created(location).body(ScheduledCallbackResponse.from(callback));
    }

    /**
     * Przekłada zaplanowane oddzwonienie na nowy termin (BE-039).
     *
     * <p>Reguły dostępu:
     * <ul>
     *   <li>Callback musi istnieć i należeć do tenanta → 404 jeśli nie</li>
     *   <li>Status musi być PENDING → 409 jeśli COMPLETED/CANCELLED/PROCESSING</li>
     *   <li>Dla roli AGENT: tylko własne callbacki (agentId z JWT) → 403 dla cudzych</li>
     *   <li>scheduledAt w przeszłości → 422 (Bean Validation @Future)</li>
     * </ul>
     *
     * @param callbackId UUID callbacku do przełożenia
     * @param request    nowy termin i opcjonalna notatka
     * @return zaktualizowane DTO callbacku
     */
    @PutMapping("/callbacks/{callbackId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Przełóż zaplanowane oddzwonienie",
        description = "Zmienia termin oddzwonienia PENDING na nowy. " +
                      "Dla roli AGENT dozwolone tylko dla własnych callbacków (sticky agent). " +
                      "Callback musi być w statusie PENDING – w przeciwnym razie HTTP 409.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Callback przełożony"),
            @ApiResponse(responseCode = "400", description = "scheduledAt w przeszłości lub brakujące pole"),
            @ApiResponse(responseCode = "403", description = "AGENT próbuje przełożyć cudzy callback"),
            @ApiResponse(responseCode = "404", description = "Callback nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Callback nie jest w statusie PENDING")
        }
    )
    public ResponseEntity<ScheduledCallbackResponse> rescheduleCallback(
            @Parameter(description = "UUID callbacku") @PathVariable UUID callbackId,
            @Valid @RequestBody RescheduleCallbackRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID jwtAgentId = TenantContext.getUserId();
        String role = TenantContext.getUserRole();

        // 1. Pobierz callback – 404 jeśli nie istnieje lub inny tenant
        ScheduledCallback callback = scheduledCallbackRepository.findById(callbackId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Callback nie istnieje: " + callbackId));

        // 2. Weryfikacja statusu – tylko PENDING można przełożyć
        if (!"PENDING".equals(callback.getStatus())) {
            throw new ConflictException(
                    "Nie można przełożyć callbacku w statusie " + callback.getStatus() +
                    ". Dozwolone tylko dla statusu PENDING.");
        }

        // 3. AGENT może przełożyć tylko własny callback (sticky agent)
        if ("AGENT".equals(role) && !jwtAgentId.equals(callback.getAgentId())) {
            throw new AccessDeniedException(
                    "Agent może przełożyć tylko własne callbacki");
        }

        // 4. Zaktualizuj termin i opcjonalną notatkę
        callback.setScheduledAt(request.scheduledAt());
        if (request.notes() != null) {
            callback.setNotes(request.notes());
        }

        ScheduledCallback updated = scheduledCallbackRepository.save(callback);

        log.info("[DialerController] Callback przełożony: callbackId={}, tenant={}, scheduledAt={}, role={}",
                callbackId, tenantId, request.scheduledAt(), role);

        return ResponseEntity.ok(ScheduledCallbackResponse.from(updated));
    }

    // =========================================================================
    // Dialer manualny – widok agenta
    // =========================================================================

    /**
     * Zwraca kampanie MANUAL+RUNNING z rekordami PENDING dostępnymi do ręcznego wybierania.
     *
     * <p>Przeznaczony wyłącznie dla agentów – zastępuje potrzebę dostępu do
     * GET /api/campaigns (widok supervisora). Agent widzi tylko to, co może wybrać:
     * kampanie manualne w toku z niezadzwonionymi rekordami.
     *
     * <p>Algorytm:
     * <ol>
     *   <li>Pobierz kampanie RUNNING + dialer_type=MANUAL dla tenanta (filtr w DB)</li>
     *   <li>Jeśli brak kampanii – zwróć pustą listę (HTTP 200)</li>
     *   <li>Pobierz rekordy PENDING dla wszystkich kampanii jednym zapytaniem (batch IN)</li>
     *   <li>Zgrupuj rekordy po campaignId i zbuduj listę DTO</li>
     * </ol>
     *
     * @return lista kampanii z rekordami PENDING (może być pusta)
     */
    @GetMapping("/manual/records")
    @PreAuthorize("hasRole('AGENT')")
    @Operation(
        summary = "Kampanie manualne z rekordami PENDING (widok agenta)",
        description = "Zwraca kampanie z dialer_type=MANUAL i statusem RUNNING wraz z rekordami " +
                      "w statusie PENDING. Agent może wybrać rekord i zainicjować połączenie " +
                      "przez POST /api/dialer/manual/call.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista kampanii (może być pusta)"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – endpoint tylko dla AGENT")
        }
    )
    public ResponseEntity<List<ManualCampaignRecordsResponse>> getManualCampaignRecords() {
        UUID tenantId = TenantContext.getTenantId();

        // 1. Kampanie MANUAL+RUNNING dla tenanta
        List<Campaign> manualCampaigns = campaignRepository.findRunningManualByTenantId(tenantId);

        if (manualCampaigns.isEmpty()) {
            log.debug("[DialerController] Brak aktywnych kampanii MANUAL dla tenant={}", tenantId);
            return ResponseEntity.ok(List.of());
        }

        List<UUID> campaignIds = manualCampaigns.stream()
                .map(Campaign::getCampaignId)
                .toList();

        // 2. Rekordy PENDING dla wszystkich kampanii jednym zapytaniem (batch IN)
        List<Map<String, Object>> pendingRows =
                campaignContactRepository.findPendingByCampaignIds(tenantId, campaignIds);

        // 3. Grupowanie rekordów po campaignId
        java.util.Map<UUID, List<ManualCampaignRecordsResponse.ManualCampaignRecord>> recordsByCampaign =
                new java.util.LinkedHashMap<>();

        for (Map<String, Object> row : pendingRows) {
            UUID campaignId = UUID.fromString((String) row.get("campaign_id"));
            UUID recordId   = UUID.fromString((String) row.get("record_id"));
            String phone     = (String) row.get("phone");
            String firstName = (String) row.get("first_name");
            String lastName  = (String) row.get("last_name");
            String status    = (String) row.get("status");

            recordsByCampaign
                    .computeIfAbsent(campaignId, k -> new ArrayList<>())
                    .add(new ManualCampaignRecordsResponse.ManualCampaignRecord(
                            recordId, phone, firstName, lastName, status));
        }

        // 4. Budowanie odpowiedzi – zachowuje kolejność kampanii z DB (created_at ASC)
        List<ManualCampaignRecordsResponse> response = manualCampaigns.stream()
                .map(campaign -> new ManualCampaignRecordsResponse(
                        campaign.getCampaignId(),
                        campaign.getName(),
                        recordsByCampaign.getOrDefault(campaign.getCampaignId(), List.of())
                ))
                .toList();

        log.debug("[DialerController] Kampanie MANUAL dla agent/tenant={}: kampanie={}, rekordy PENDING={}",
                tenantId, manualCampaigns.size(), pendingRows.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Inicjuje połączenie wychodzące ręcznie przez agenta (dialer MANUAL).
     *
     * <p>Agent wskazuje konkretny rekord kampanii ({@code recordId}) i inicjuje połączenie.
     * Endpoint waliduje że:
     * <ol>
     *   <li>Kampania istnieje i należy do tenanta agenta</li>
     *   <li>Kampania ma status RUNNING</li>
     *   <li>Kampania ma {@code dialer_type = 'MANUAL'}</li>
     *   <li>Rekord campaign_contact ma status PENDING</li>
     * </ol>
     *
     * <p>Po pomyślnej walidacji rekord zostaje oznaczony jako DIALING, a połączenie
     * jest inicjowane przez {@link TelephonyAdapter}.
     *
     * <p>Endpoint dostępny wyłącznie dla roli AGENT – połączenia manualne inicjuje agent,
     * nie supervisor czy admin.
     *
     * @param request żądanie zawierające campaignId i recordId
     * @return dane zainicjowanego połączenia (callId, recordId, agentId, phone)
     */
    @PostMapping("/manual/call")
    @PreAuthorize("hasRole('AGENT')")
    @Operation(
        summary = "Ręczne inicjowanie połączenia (dialer MANUAL)",
        description = "Agent ręcznie inicjuje połączenie do wybranego rekordu kampanii. " +
                      "Dostępne wyłącznie dla kampanii z dialer_type=MANUAL i statusem RUNNING. " +
                      "Endpoint przeznaczony wyłącznie dla roli AGENT.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Połączenie zainicjowane"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji – brakujące pola"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – tylko AGENT"),
            @ApiResponse(responseCode = "404", description = "Kampania lub rekord nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Kampania nie jest MANUAL/RUNNING lub rekord nie jest PENDING")
        }
    )
    public ResponseEntity<ManualCallResponse> initiateManualCall(
            @Valid @RequestBody ManualCallRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId = TenantContext.getUserId();

        // Weryfikacja kampanii
        Campaign campaign = campaignRepository.findById(request.campaignId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Kampania nie istnieje: " + request.campaignId()));

        if (!"RUNNING".equals(campaign.getStatus())) {
            throw new InvalidOperationException(
                    "Kampania " + request.campaignId() + " nie jest RUNNING (status=" + campaign.getStatus() + ")");
        }

        if (!"MANUAL".equals(campaign.getDialerType())) {
            throw new InvalidOperationException(
                    "Kampania " + request.campaignId() + " nie jest typu MANUAL (dialer_type=" + campaign.getDialerType() + "). " +
                    "Dla kampanii PROGRESSIVE połączenia są inicjowane automatycznie przez dialer.");
        }

        // Pobranie rekordu campaign_contact z weryfikacją statusu PENDING
        // (logika przeniesiona do CampaignContactRepository – brak SQL w kontrolerze)
        Map<String, Object> row = campaignContactRepository
                .findRecordForManualDial(request.recordId(), request.campaignId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Rekord campaign_contact nie istnieje: " + request.recordId()));

        String currentStatus = (String) row.get("status");
        if (!"PENDING".equals(currentStatus)) {
            throw new InvalidOperationException(
                    "Rekord " + request.recordId() + " nie jest w statusie PENDING (status=" + currentStatus + ")");
        }

        String phone = (String) row.get("phone");
        if (phone == null || phone.isBlank()) {
            throw new InvalidOperationException(
                    "Rekord " + request.recordId() + " nie ma numeru telefonu");
        }

        // Oznacz rekord jako DIALING
        campaignContactRepository.markAsDialing(request.recordId(), request.campaignId(), tenantId);

        // Inicjuj połączenie przez TelephonyAdapter
        CallSession session;
        try {
            session = telephonyAdapter.initiateCall(
                    tenantId, defaultOutboundNumber, phone, agentId, campaign.getQueueId());
        } catch (TelephonyAdapter.TelephonyException e) {
            // Oznacz rekord jako ERROR – błąd Twilio API jest trwały (np. niezweryfikowany numer)
            // i nie ma sensu wracać do PENDING, bo kolejna próba da ten sam błąd.
            campaignContactRepository.markAsError(
                    request.recordId(), request.campaignId(), tenantId);
            throw new InvalidOperationException(
                    "Błąd inicjowania połączenia: " + e.getMessage());
        }

        // Zapisz stan połączenia w Redis – DialerCallbackHandler użyje klucza dialer:call:{callSid}
        // przy webhooku call.hangup, aby zaktualizować campaign_contact z DIALING → COMPLETED/NO_ANSWER/FAILED.
        // Bez tego wpisu handler pomija webhook i rekord pozostaje na zawsze w statusie DIALING.
        progressiveDialerService.saveCallState(
                session.getCallId(),
                request.recordId(),
                request.campaignId(),
                agentId,
                tenantId
        );

        log.info("[ManualDialer] Połączenie zainicjowane ręcznie: kampania={}, rekord={}, agent={}, callId={}, tenant={}",
                request.campaignId(), request.recordId(), agentId, session.getCallId(), tenantId);

        return ResponseEntity.ok(new ManualCallResponse(
                session.getCallId(),
                request.recordId(),
                request.campaignId(),
                agentId,
                phone
        ));
    }


    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /**
     * Rozstrzyga efektywny agentId na podstawie roli.
     *
     * <p>Dla roli AGENT: zawsze używa agentId z JWT – agent nie może podstawić
     * UUID innego agenta (zapobiega IDOR przez podmianę agentId w żądaniu).
     * Dla ADMIN/SUPERVISOR: akceptuje agentId z żądania jeśli podany,
     * w przeciwnym razie używa agentId z JWT.
     *
     * <p>Uwaga: {@code TenantContext.getUserRole()} zwraca wartości bez prefiksu ROLE_
     * (np. "AGENT", "SUPERVISOR", "ADMIN") – zgodnie z JWT claim "role".
     *
     * @param requestedAgentId agentId z żądania HTTP (może być null)
     * @param jwtAgentId       agentId z JWT (zawsze nie-null)
     * @param role             rola użytkownika z TenantContext (np. "AGENT", "SUPERVISOR", "ADMIN")
     * @return efektywny UUID agenta
     */
    private UUID resolveAgentId(UUID requestedAgentId, UUID jwtAgentId, String role) {
        // AGENT nie może wskazywać innego agenta – zawsze jego własny id z JWT
        if ("AGENT".equals(role)) {
            return jwtAgentId;
        }
        // SUPERVISOR/ADMIN mogą wskazać konkretnego agenta lub użyć własnego id
        return requestedAgentId != null ? requestedAgentId : jwtAgentId;
    }
}
