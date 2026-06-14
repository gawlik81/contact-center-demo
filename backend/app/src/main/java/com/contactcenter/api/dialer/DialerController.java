package com.contactcenter.api.dialer;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.dialer.dto.CallbackListItemResponse;
import com.contactcenter.api.dialer.dto.CreateCallbackRequest;
import com.contactcenter.api.dialer.dto.CreateInboundCallbackRequest;
import com.contactcenter.api.dialer.dto.DialerStatusResponse;
import com.contactcenter.api.dialer.dto.ManualCallRequest;
import com.contactcenter.api.dialer.dto.ManualCallResponse;
import com.contactcenter.api.dialer.dto.ManualCampaignRecordsResponse;
import com.contactcenter.api.dialer.dto.RescheduleCallbackRequest;
import com.contactcenter.api.dialer.dto.UpdateCallbackRequest;
import com.contactcenter.api.dialer.dto.ScheduledCallbackResponse;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.campaign.Campaign;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.campaign.ScheduledCallback;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.campaign.CampaignAssignmentService;
import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.campaign.ScheduledCallbackService;
import com.contactcenter.domain.campaign.DialerCallbackHandler;
import com.contactcenter.domain.campaign.ProgressiveDialerService;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final CampaignService campaignService;
    private final CampaignAssignmentService campaignAssignmentService;
    private final ScheduledCallbackService scheduledCallbackService;
    private final TelephonyAdapter telephonyAdapter;
    private final ContactService contactService;
    private final UserService userService;

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
     * <p>Implementacja: jedno zapytanie GROUP BY do {@link ProgressiveDialerService}
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

        List<Campaign> runningCampaigns = campaignService.getRunningCampaigns(tenantId);

        if (runningCampaigns.isEmpty()) {
            return ResponseEntity.ok(new DialerStatusResponse(tenantId, List.of(), 0, Instant.now()));
        }

        List<UUID> campaignIds = runningCampaigns.stream()
                .map(Campaign::getCampaignId)
                .toList();

        // Jedno zapytanie GROUP BY zamiast N×3 COUNT – eliminacja N+1 queries
        Map<UUID, Map<String, Long>> countsByCampaign =
                progressiveDialerService.getContactCountsByStatus(tenantId, campaignIds, DIALER_STATUSES);

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
     * Zwraca stronicowaną listę zaplanowanych oddzwonień z obsługą ról i filtrami (BE-041).
     *
     * <p>Logika izolacji ról:
     * <ul>
     *   <li>AGENT – widzi tylko swoje callbacki (agentId z JWT); parametr {@code agentId} z query jest ignorowany</li>
     *   <li>SUPERVISOR/ADMIN – widzi wszystkie callbacki tenanta; może filtrować po {@code agentId}</li>
     * </ul>
     *
     * <p>Pole {@code agentName} jest rozwiązywane przez jeden batch SELECT po wszystkich unikalnych
     * agentId na stronie – brak problemu N+1.
     *
     * @param status  filtr statusu (null = wszystkie statusy: PENDING/PROCESSING/COMPLETED/CANCELLED)
     * @param agentId filtr po agentId (tylko SUPERVISOR/ADMIN); AGENT ignoruje ten parametr
     * @param sortDir kierunek sortowania po scheduled_at (ASC/DESC, domyślnie ASC)
     * @param page    numer strony (0-based, domyślnie 0)
     * @param size    rozmiar strony (domyślnie 20, max 100)
     * @return stronicowana lista callbacków
     */
    @GetMapping("/callbacks")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Lista zaplanowanych oddzwonień",
        description = "Zwraca stronicowaną listę callbacków z obsługą ról i filtrami. " +
                      "AGENT widzi tylko własne callbacki. SUPERVISOR/ADMIN widzą wszystkie callbacki tenanta. " +
                      "Pole agentName rozwiązywane batch'owo (jeden SELECT na stronę).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista callbacków"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia")
        }
    )
    public ResponseEntity<PagedResponse<CallbackListItemResponse>> listCallbacks(
            @Parameter(description = "Filtr statusu: PENDING, PROCESSING, COMPLETED, CANCELLED (null = wszystkie)")
            @RequestParam(required = false) String status,
            @Parameter(description = "Filtr po agentId – tylko SUPERVISOR/ADMIN; AGENT ignoruje ten parametr")
            @RequestParam(required = false) UUID agentId,
            @Parameter(description = "Kierunek sortowania po scheduledAt: ASC lub DESC")
            @RequestParam(defaultValue = "ASC") String sortDir,
            @Parameter(description = "Numer strony (0-based)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Rozmiar strony (max 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        // Normalizacja parametrów
        if (size > 100) size = 100;
        if (size < 1)   size = 1;
        if (page < 0)   page = 0;
        String effectiveSortDir = "DESC".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";
        String effectiveStatus  = (status != null && !status.isBlank()) ? status.trim() : null;

        UUID tenantId   = TenantContext.getTenantId();
        UUID jwtAgentId = TenantContext.getUserId();
        String role     = TenantContext.getUserRole();

        List<ScheduledCallback> callbacks;
        long total;

        if ("AGENT".equals(role)) {
            // AGENT widzi wyłącznie własne callbacki – parametr agentId z query jest ignorowany
            callbacks = scheduledCallbackService.findCallbacksByAgentId(
                    tenantId, jwtAgentId, effectiveStatus, effectiveSortDir, page, size);
            total = scheduledCallbackService.countCallbacksByAgentId(tenantId, jwtAgentId, effectiveStatus);
        } else {
            // SUPERVISOR / ADMIN – widok wszystkich callbacków tenanta z opcjonalnym filtrem agentId
            callbacks = scheduledCallbackService.findCallbacksByTenantIdWithFilters(
                    tenantId, effectiveStatus, agentId, effectiveSortDir, page, size);
            total = scheduledCallbackService.countCallbacksByTenantIdWithFilters(tenantId, effectiveStatus, agentId);
        }

        // Batch lookup agentów – jeden SELECT dla wszystkich unikalnych agentId na stronie
        Set<UUID> agentIds = callbacks.stream()
                .map(ScheduledCallback::getAgentId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<UUID, String> agentNames = agentIds.isEmpty()
                ? Map.of()
                : userService.findUsersByIds(agentIds).stream()
                        .collect(Collectors.toMap(
                                AppUser::getId,
                                u -> u.getFirstName() + " " + u.getLastName()
                        ));

        List<CallbackListItemResponse> content = callbacks.stream()
                .map(cb -> new CallbackListItemResponse(
                        cb.getCallbackId(),
                        cb.getAgentId(),
                        cb.getAgentId() != null ? agentNames.get(cb.getAgentId()) : null,
                        cb.getPhone(),
                        cb.getFirstName(),
                        cb.getLastName(),
                        cb.getScheduledAt(),
                        cb.getNotes(),
                        cb.getStatus(),
                        cb.getSourceType(),
                        cb.getOriginContactId(),
                        cb.getCreatedAt()
                ))
                .toList();

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;

        PagedResponse<CallbackListItemResponse> response = new PagedResponse<>(
                content, page, size, total, totalPages, page == 0, page >= totalPages - 1
        );

        log.debug("[DialerController] Lista callbacków: tenant={}, role={}, total={}, page={}, status={}",
                tenantId, role, total, page, effectiveStatus);

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

            callback = scheduledCallbackService.saveCallback(newCallback);
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
        ScheduledCallback callback = scheduledCallbackService.getCallbackOrThrow(callbackId, tenantId);

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

        ScheduledCallback updated = scheduledCallbackService.saveCallback(callback);

        log.info("[DialerController] Callback przełożony: callbackId={}, tenant={}, scheduledAt={}, role={}",
                callbackId, tenantId, request.scheduledAt(), role);

        return ResponseEntity.ok(ScheduledCallbackResponse.from(updated));
    }

    /**
     * Pełna edycja zaplanowanego oddzwonienia (BE-042).
     *
     * <p>Semantyka PATCH: aktualizowane są wyłącznie pola niepuste (null = bez zmiany).
     *
     * <p>Reguły dostępu:
     * <ul>
     *   <li>Callback musi istnieć → 404</li>
     *   <li>Status musi być PENDING → 409 dla COMPLETED / CANCELLED / PROCESSING</li>
     *   <li>AGENT: może edytować tylko własny callback → 403 dla cudzych; {@code agentId} ignorowany</li>
     *   <li>SUPERVISOR/ADMIN: może edytować każdy callback; {@code agentId} niepuste → reassign</li>
     * </ul>
     *
     * @param callbackId UUID callbacku do edycji
     * @param request    pola do zaktualizowania (null = bez zmiany)
     * @return zaktualizowany callback jako {@link CallbackListItemResponse}
     */
    @PatchMapping("/callbacks/{callbackId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Edytuj zaplanowane oddzwonienie",
        description = "PATCH semantics: pola null = bez zmiany. " +
                      "Dozwolone tylko dla callbacków w statusie PENDING. " +
                      "AGENT może edytować tylko własne callbacki; pole agentId jest ignorowane dla roli AGENT. " +
                      "SUPERVISOR/ADMIN mogą wykonać reassign przez podanie agentId.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Callback zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji – scheduledAt w przeszłości lub nieprawidłowy numer"),
            @ApiResponse(responseCode = "403", description = "AGENT próbuje edytować cudzy callback"),
            @ApiResponse(responseCode = "404", description = "Callback nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Callback nie jest w statusie PENDING")
        }
    )
    public ResponseEntity<CallbackListItemResponse> updateCallback(
            @Parameter(description = "UUID callbacku") @PathVariable UUID callbackId,
            @Valid @RequestBody UpdateCallbackRequest request
    ) {
        UUID tenantId   = TenantContext.getTenantId();
        UUID jwtAgentId = TenantContext.getUserId();
        String role     = TenantContext.getUserRole();

        // 1. Pobierz callback – 404 jeśli nie istnieje lub inny tenant
        ScheduledCallback callback = scheduledCallbackService.getCallbackOrThrow(callbackId, tenantId);

        // 2. Weryfikacja statusu – PATCH dozwolony tylko dla PENDING
        if (!"PENDING".equals(callback.getStatus())) {
            throw new ConflictException(
                    "Nie można edytować callbacku w statusie " + callback.getStatus() +
                    ". Edycja dozwolona tylko dla statusu PENDING.");
        }

        // 3. Autoryzacja
        if ("AGENT".equals(role) && !jwtAgentId.equals(callback.getAgentId())) {
            throw new AccessDeniedException(
                    "Agent może edytować tylko własne callbacki");
        }

        // 4. Patch semantics – aktualizuj tylko pola niepuste
        if (request.phone() != null) {
            callback.setPhone(request.phone());
        }
        if (request.firstName() != null) {
            callback.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            callback.setLastName(request.lastName());
        }
        if (request.scheduledAt() != null) {
            callback.setScheduledAt(request.scheduledAt());
        }
        if (request.notes() != null) {
            callback.setNotes(request.notes());
        }
        // agentId – reassign: tylko dla SUPERVISOR/ADMIN
        if (!"AGENT".equals(role) && request.agentId() != null) {
            callback.setAgentId(request.agentId());
        }

        // 5. Zapisz i zwróć
        ScheduledCallback updated = scheduledCallbackService.saveCallback(callback);

        log.info("[DialerController] Callback zaktualizowany (PATCH): callbackId={}, tenant={}, role={}",
                callbackId, tenantId, role);

        // Batch lookup agentName (może być po reassign)
        String agentName = null;
        if (updated.getAgentId() != null) {
            agentName = userService.findUserById(updated.getAgentId())
                    .map(u -> u.getFirstName() + " " + u.getLastName())
                    .orElse(null);
        }

        CallbackListItemResponse responseBody = new CallbackListItemResponse(
                updated.getCallbackId(),
                updated.getAgentId(),
                agentName,
                updated.getPhone(),
                updated.getFirstName(),
                updated.getLastName(),
                updated.getScheduledAt(),
                updated.getNotes(),
                updated.getStatus(),
                updated.getSourceType(),
                updated.getOriginContactId(),
                updated.getCreatedAt()
        );

        return ResponseEntity.ok(responseBody);
    }

    /**
     * Soft-delete zaplanowanego oddzwonienia przez zmianę statusu na CANCELLED (BE-042).
     *
     * <p>Wiersz pozostaje w bazie – historia dla raportów.
     *
     * <p>Reguły dostępu:
     * <ul>
     *   <li>Callback musi istnieć → 404</li>
     *   <li>AGENT: tylko własne callbacki → 403 dla cudzych</li>
     *   <li>Callbacki w statusie PROCESSING nie mogą być anulowane → 409</li>
     * </ul>
     *
     * @param callbackId UUID callbacku do anulowania
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/callbacks/{callbackId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Anuluj zaplanowane oddzwonienie (soft-delete)",
        description = "Zmienia status callbacku na CANCELLED. Wiersz pozostaje w bazie – historia dla raportów. " +
                      "AGENT może anulować tylko własne callbacki. " +
                      "Callbacki w statusie PROCESSING nie mogą być anulowane.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Callback anulowany"),
            @ApiResponse(responseCode = "403", description = "AGENT próbuje anulować cudzy callback"),
            @ApiResponse(responseCode = "404", description = "Callback nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Callback jest aktualnie przetwarzany (status=PROCESSING)")
        }
    )
    public ResponseEntity<Void> cancelCallback(
            @Parameter(description = "UUID callbacku") @PathVariable UUID callbackId
    ) {
        UUID tenantId   = TenantContext.getTenantId();
        UUID jwtAgentId = TenantContext.getUserId();
        String role     = TenantContext.getUserRole();

        // 1. Pobierz callback – 404 jeśli nie istnieje lub inny tenant
        ScheduledCallback callback = scheduledCallbackService.getCallbackOrThrow(callbackId, tenantId);

        // 2. Autoryzacja – AGENT może anulować tylko własny callback
        if ("AGENT".equals(role) && !jwtAgentId.equals(callback.getAgentId())) {
            throw new AccessDeniedException(
                    "Agent może anulować tylko własne callbacki");
        }

        // 3. Callbacki w statusie PROCESSING nie mogą być anulowane
        if ("PROCESSING".equals(callback.getStatus())) {
            throw new ConflictException(
                    "Callback jest aktualnie przetwarzany i nie może być anulowany");
        }

        // 4. Soft-delete: zmiana statusu na CANCELLED
        scheduledCallbackService.cancelCallback(callbackId, tenantId);

        log.info("[DialerController] Callback anulowany (soft-delete): callbackId={}, tenant={}, role={}",
                callbackId, tenantId, role);

        return ResponseEntity.noContent().build();
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
        UUID agentId = TenantContext.getUserId();

        // 1. Kampanie MANUAL+RUNNING dla tenanta
        List<Campaign> manualCampaigns = campaignService.getRunningManualCampaigns(tenantId);

        if (manualCampaigns.isEmpty()) {
            log.debug("[DialerController] Brak aktywnych kampanii MANUAL dla tenant={}", tenantId);
            return ResponseEntity.ok(List.of());
        }

        // BE-084: filtruj kampanie do których agent jest przypisany
        List<Campaign> eligibleCampaigns = manualCampaigns.stream()
                .filter(campaign -> {
                    if (campaign.isAllAgents()) return true;
                    Set<UUID> eligible = campaignAssignmentService
                            .resolveEligibleAgentIds(campaign.getCampaignId(), tenantId);
                    return eligible.contains(agentId);
                })
                .toList();

        if (eligibleCampaigns.isEmpty()) {
            log.debug("[DialerController] Agent {} nie jest przypisany do żadnej kampanii MANUAL (tenant={})",
                    agentId, tenantId);
            return ResponseEntity.ok(List.of());
        }

        List<UUID> campaignIds = eligibleCampaigns.stream()
                .map(Campaign::getCampaignId)
                .toList();

        // 2. Rekordy PENDING dla wszystkich kampanii jednym zapytaniem (batch IN)
        List<Map<String, Object>> pendingRows =
                progressiveDialerService.findPendingRecordsByCampaignIds(tenantId, campaignIds);

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
        List<ManualCampaignRecordsResponse> response = eligibleCampaigns.stream()
                .map(campaign -> new ManualCampaignRecordsResponse(
                        campaign.getCampaignId(),
                        campaign.getName(),
                        recordsByCampaign.getOrDefault(campaign.getCampaignId(), List.of())
                ))
                .toList();

        log.debug("[DialerController] Kampanie MANUAL dla agent={}, tenant={}: kampanie={}, rekordy PENDING={}",
                agentId, tenantId, eligibleCampaigns.size(), pendingRows.size());

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
        Campaign campaign = campaignService.findCampaignEntity(request.campaignId(), tenantId)
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

        // Pobranie rekordu campaign_contact z weryfikacją dostępności do wydzwonienia
        Map<String, Object> row = progressiveDialerService
                .findRecordForManualDial(request.recordId(), request.campaignId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Rekord campaign_contact nie istnieje: " + request.recordId()));

        String currentStatus = (String) row.get("status");
        java.sql.Timestamp nextAttemptTs = (java.sql.Timestamp) row.get("next_attempt_at");
        boolean retryReady = ("NO_ANSWER".equals(currentStatus) || "FAILED".equals(currentStatus))
                && (nextAttemptTs == null || !nextAttemptTs.toInstant().isAfter(Instant.now()));

        if (!"PENDING".equals(currentStatus) && !retryReady) {
            throw new InvalidOperationException(
                    "Rekord " + request.recordId() + " nie jest dostępny do wydzwonienia (status=" + currentStatus + ")");
        }

        String phone = (String) row.get("phone");
        if (phone == null || phone.isBlank()) {
            throw new InvalidOperationException(
                    "Rekord " + request.recordId() + " nie ma numeru telefonu");
        }

        // Oznacz rekord jako DIALING
        progressiveDialerService.markRecordAsDialing(request.recordId(), request.campaignId(), tenantId);

        // Inicjuj połączenie przez TelephonyAdapter
        CallSession session;
        try {
            session = telephonyAdapter.initiateCall(
                    tenantId, defaultOutboundNumber, phone, agentId, campaign.getCampaignId(), null);
        } catch (TelephonyAdapter.TelephonyException e) {
            // Oznacz rekord jako ERROR – błąd Twilio API jest trwały (np. niezweryfikowany numer)
            // i nie ma sensu wracać do PENDING, bo kolejna próba da ten sam błąd.
            progressiveDialerService.markRecordAsError(
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
        // Ustaw klucz ring timeout – checkRingTimeouts() uzna brak tego klucza za NO_ANSWER.
        // Bez tego wywołania każde połączenie manualne jest natychmiast rozłączane przez scheduler.
        progressiveDialerService.scheduleNoAnswerTimeout(
                session.getCallId(),
                campaign.getRingTimeoutSeconds()
        );

        // Powiąż contact ↔ campaign_contact_record – bez tego UI pokazuje "Brak prób wydzwonienia".
        if (session.getContactId() != null) {
            contactService.updateCampaignContactRecordId(
                    session.getContactId(), request.recordId(), tenantId);
            progressiveDialerService.updateLastContactId(
                    request.recordId(), request.campaignId(), session.getContactId());
        }

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
