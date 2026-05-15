package com.contactcenter.api.telephony;

import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.telephony.dto.OutboundCallRequest;
import com.contactcenter.api.telephony.dto.OutboundCallResponse;
import com.contactcenter.api.telephony.dto.TransferCallRequest;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.service.ContactService;
import com.contactcenter.domain.service.TenantTwilioConfigService;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Agent-facing telephony actions – answer, hold, mute, transfer.
 *
 * <p>All endpoints require a valid JWT with role AGENT (or SUPERVISOR/ADMIN).
 * They are NOT public – unlike webhook endpoints they go through TenantFilter
 * and TenantContext is populated before the handler is invoked.
 *
 * <p>Key endpoint: {@code POST /api/telephony/calls/{callId}/answer}
 * This replaces the previously used {@code POST /api/dev/telephony/simulate action=ANSWER}
 * which is only available when {@code telephony.provider=mock}. The new endpoint works
 * with any active {@link TelephonyAdapter} implementation (Mock or Twilio).
 *
 * <p>Side effect of answer: the contact record ({@code agent_id = null}) is updated
 * with the answering agent's UUID and status is changed to ACTIVE.
 * This ensures {@code ContactService.setDisposition} can validate agent ownership.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/telephony/calls")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Agent Call Actions",
        description = "Agent-facing telephony actions (answer, hangup, hold, mute, transfer). " +
                "Works with any active telephony provider (Mock or Twilio).")
public class AgentCallController {

    private final TelephonyAdapter telephonyAdapter;
    private final ContactService contactService;
    private final ContactRepository contactRepository;
    private final TenantTwilioConfigService tenantTwilioConfigService;
    private final TwilioProperties twilioProperties;

    // =========================================================================
    // Initiate outbound call (ad hoc – without campaign)
    // =========================================================================

    /**
     * Agent initiates an outbound call to an arbitrary phone number.
     *
     * <p>The call is ad hoc – not linked to any campaign or scheduled callback.
     * A new contact record is created by the telephony adapter with direction=OUTBOUND.
     *
     * <p>Resolves the "from" number in the following priority:
     * <ol>
     *   <li>Per-tenant {@code TenantTwilioConfig.phoneNumber}</li>
     *   <li>Global {@code TwilioProperties.phoneNumber} (fallback)</li>
     *   <li>Empty string (mock / dev environment)</li>
     * </ol>
     *
     * @param request body containing {@code phoneNumber} (E.164) and optional {@code customerId}
     * @return {@link OutboundCallResponse} with {@code contactId} and {@code callId}
     */
    @PostMapping("/outbound")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Initiate an outbound call (ad hoc)",
            description = """
                    Agent initiates an outbound call to the given phone number.
                    The call is not tied to any campaign or scheduled callback.

                    The response contains:
                    - contactId: UUID of the newly created contact record in the database.
                    - callId: telephony session identifier (Twilio CallSid or mock UUID).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Call initiated, contactId and callId returned"),
                    @ApiResponse(responseCode = "400", description = "Invalid phone number format (not E.164)"),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token"),
                    @ApiResponse(responseCode = "403", description = "Insufficient role"),
                    @ApiResponse(responseCode = "500", description = "Telephony adapter error")
            }
    )
    public ResponseEntity<OutboundCallResponse> initiateOutboundCall(
            @Valid @RequestBody OutboundCallRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        String resolvedFromNumber = resolveFromNumber(tenantId);

        log.info("[AgentCallController] OUTBOUND: to={}, agentId={}, tenant={}, from={}",
                request.phoneNumber(), agentId, tenantId, maskPhone(resolvedFromNumber));

        CallSession session = telephonyAdapter.initiateCall(
                tenantId,
                resolvedFromNumber,
                request.phoneNumber(),
                agentId,
                null,  // queueId – not applicable for ad hoc calls
                null   // callbackId – not a scheduled callback
        );

        log.info("[AgentCallController] Połączenie wychodzące zainicjowane: callId={}, contactId={}, agentId={}",
                session.getCallId(), session.getContactId(), agentId);

        return ResponseEntity.ok(new OutboundCallResponse(session.getContactId(), session.getCallId()));
    }

    // =========================================================================
    // Answer call
    // =========================================================================

    /**
     * Agent answers an inbound call.
     *
     * <p>Two things happen in this call:
     * <ol>
     *   <li>The telephony adapter transitions the call session to ACTIVE
     *       (for Twilio this updates the local session state and publishes CALL_ANSWERED;
     *       for Mock it does the same inside MockTelephonyAdapter).</li>
     *   <li>The contact record in the DB has its {@code agent_id} set to the calling agent,
     *       {@code assigned_at} set to now, and {@code status} changed to ACTIVE.
     *       This is necessary because inbound Twilio calls create the contact with
     *       {@code agent_id = null} (the webhook fires before the agent answers).</li>
     * </ol>
     *
     * <p>Returns the updated {@link ContactResponse} so the frontend can refresh the contact tab.
     *
     * @param callId Twilio Call SID (e.g. CA8de745...) or mock callId
     * @return updated contact DTO
     */
    @PostMapping("/{callId}/answer")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Answer an inbound call",
            description = """
                    Agent answers an inbound call identified by callId (Twilio SID or mock UUID).

                    Side effects:
                    - Telephony session status transitions to ACTIVE.
                    - Contact record agent_id and assigned_at are updated in the database.
                    - Contact status changes to ACTIVE.

                    The returned contact DTO can be used to refresh the agent desktop tab.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Call answered, contact updated"),
                    @ApiResponse(responseCode = "404", description = "Call session or contact not found"),
                    @ApiResponse(responseCode = "409", description = "Contact already assigned to a different agent"),
                    @ApiResponse(responseCode = "403", description = "JWT missing or insufficient role")
            }
    )
    public ResponseEntity<ContactResponse> answerCall(
            @Parameter(description = "Twilio Call SID or mock callId", required = true)
            @PathVariable String callId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        String resolvedCallSid = resolveCallSid(callId, tenantId);
        log.info("[AgentCallController] ANSWER: callId={}, resolvedCallSid={}, agentId={}, tenant={}",
                callId, resolvedCallSid, agentId, tenantId);

        // Step 1 – notify the telephony adapter (updates session state, publishes CALL_ANSWERED event)
        try {
            telephonyAdapter.answerCall(resolvedCallSid, agentId);
        } catch (TelephonyAdapter.TelephonyException e) {
            log.warn("[AgentCallController] Adapter answerCall failed for callId={}: {}", callId, e.getMessage());
            // Do not abort – the call may already be ACTIVE (idempotent scenario).
            // We still want to update agent_id in the DB if the session exists.
        }

        // Step 2 – resolve contactId: if original callId was a UUID use it directly,
        // otherwise fall back to session lookup (for CA... style callSids).
        UUID contactId;
        try {
            contactId = UUID.fromString(callId);
        } catch (IllegalArgumentException e) {
            contactId = resolveContactId(resolvedCallSid);
        }
        if (contactId == null) {
            log.warn("[AgentCallController] Brak contactId w sesji dla callId={} – pomijam aktualizację agenta w DB",
                    callId);
            return ResponseEntity.notFound().build();
        }

        ContactResponse response = contactService.assignAgent(contactId, tenantId, agentId);

        log.info("[AgentCallController] Połączenie odebrane: callId={}, contactId={}, agentId={}",
                callId, contactId, agentId);

        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Hangup call
    // =========================================================================

    /**
     * Agent hangs up a call.
     *
     * @param callId Twilio Call SID or mock callId
     * @return 204 No Content
     */
    @PostMapping("/{callId}/hangup")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Hang up a call",
            description = "Terminates the call. Idempotent – calling on an already-ended call is safe.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Call terminated"),
                    @ApiResponse(responseCode = "404", description = "Call session not found")
            }
    )
    public ResponseEntity<Void> hangupCall(
            @Parameter(description = "Twilio Call SID or mock callId", required = true)
            @PathVariable String callId
    ) {
        UUID agentId = TenantContext.getUserId();
        UUID tenantId = TenantContext.getTenantId();
        String resolvedCallSid = resolveCallSid(callId, tenantId);
        log.info("[AgentCallController] HANGUP: callId={}, resolvedCallSid={}, agentId={}", callId, resolvedCallSid, agentId);

        try {
            telephonyAdapter.hangupCall(resolvedCallSid);
        } catch (TelephonyAdapter.TelephonyException e) {
            // Idempotent – session may already be gone (call already ended)
            log.warn("[AgentCallController] hangupCall failed for callId={}: {}", callId, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Hold / Unhold
    // =========================================================================

    /**
     * Puts a call on hold or resumes it.
     *
     * @param callId Twilio Call SID or mock callId
     * @param hold   {@code true} = hold, {@code false} = resume
     * @return 204 No Content
     */
    @PostMapping("/{callId}/hold")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Hold or resume a call",
            description = "Puts the call on hold (hold=true) or resumes it (hold=false).",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Hold state changed"),
                    @ApiResponse(responseCode = "409", description = "Call is in wrong state for this operation")
            }
    )
    public ResponseEntity<Void> holdCall(
            @PathVariable String callId,
            @RequestParam(defaultValue = "true") boolean hold
    ) {
        UUID agentId = TenantContext.getUserId();
        UUID tenantId = TenantContext.getTenantId();
        String resolvedCallSid = resolveCallSid(callId, tenantId);
        log.info("[AgentCallController] HOLD: callId={}, resolvedCallSid={}, hold={}, agentId={}", callId, resolvedCallSid, hold, agentId);

        telephonyAdapter.holdCall(resolvedCallSid, hold);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Mute / Unmute
    // =========================================================================

    /**
     * Mutes or unmutes the agent's microphone for a call.
     *
     * @param callId Twilio Call SID or mock callId
     * @param mute   {@code true} = mute, {@code false} = unmute
     * @return 204 No Content
     */
    @PostMapping("/{callId}/mute")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Mute or unmute the agent microphone",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Mute state changed")
            }
    )
    public ResponseEntity<Void> muteCall(
            @PathVariable String callId,
            @RequestParam(defaultValue = "true") boolean mute
    ) {
        UUID agentId = TenantContext.getUserId();
        UUID tenantId = TenantContext.getTenantId();
        String resolvedCallSid = resolveCallSid(callId, tenantId);
        log.info("[AgentCallController] MUTE: callId={}, resolvedCallSid={}, mute={}, agentId={}", callId, resolvedCallSid, mute, agentId);

        telephonyAdapter.muteCall(resolvedCallSid, mute);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Transfer call (BE-077)
    // =========================================================================

    /**
     * Inicjuje transfer aktywnego połączenia do numeru telefonu, agenta lub kolejki.
     *
     * <p>Obsługiwane typy celu ({@code targetType}):
     * <ul>
     *   <li>{@code PHONE} – przekazanie na numer E.164; wymaga {@code phoneNumber}</li>
     *   <li>{@code AGENT} – przekazanie do innego agenta; wymaga {@code agentId}</li>
     *   <li>{@code QUEUE} – przekazanie do kolejki; wymaga {@code queueId}; tylko BLIND</li>
     * </ul>
     *
     * <p>Obsługiwane typy transferu ({@code transferType}):
     * <ul>
     *   <li>{@code BLIND} – natychmiastowe przekazanie bez konsultacji (dostępne dla wszystkich celów)</li>
     *   <li>{@code ATTENDED} – z konsultacją, druga noga tworzona przed przekazaniem (PHONE i AGENT)</li>
     * </ul>
     *
     * @param callId identyfikator sesji (contactId jako UUID lub Twilio SID CA...)
     * @param req    ciało żądania z typem transferu i danymi celu
     * @return sesja połączenia zwrócona przez adapter (HTTP 200)
     */
    @PostMapping("/{callId}/transfer")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Initiate call transfer (BE-077)",
            description = """
                    Transfers an active call to a phone number, another agent, or a queue.

                    Target types:
                    - PHONE: transfers to an E.164 phone number (requires phoneNumber)
                    - AGENT: transfers to another agent (requires agentId)
                    - QUEUE: transfers to a queue (requires queueId; BLIND only)

                    Transfer types:
                    - BLIND: immediate transfer without consultation
                    - ATTENDED: consultation leg first; bridge calls after confirmation

                    Validation:
                    - QUEUE + ATTENDED combination is rejected (400)
                    - Contact must be ACTIVE (409)
                    - Caller must be the assigned agent (403)
                    - Contact must exist and belong to the same tenant (404)
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Transfer initiated, session returned"),
                    @ApiResponse(responseCode = "400", description = "Invalid targetType/transferType combination or missing required field"),
                    @ApiResponse(responseCode = "403", description = "Agent is not the owner of this contact or cross-tenant access"),
                    @ApiResponse(responseCode = "404", description = "Contact not found for this callId"),
                    @ApiResponse(responseCode = "409", description = "Contact is not in ACTIVE state")
            }
    )
    public ResponseEntity<CallSession> transferCall(
            @Parameter(description = "Contact UUID or Twilio Call SID", required = true)
            @PathVariable @Size(max = 64) String callId,
            @Valid @RequestBody TransferCallRequest req
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        log.info("[AgentCallController] TRANSFER: callId={}, targetType={}, transferType={}, agentId={}, tenant={}",
                callId, req.targetType(), req.transferType(), agentId, tenantId);

        CallSession session = contactService.initiateTransfer(callId, req, tenantId, agentId);

        return ResponseEntity.ok(session);
    }

    // =========================================================================
    // Bridge calls – finalizacja attended transfer (BE-078)
    // =========================================================================

    /**
     * Finalizuje attended transfer łącząc dwie nogi połączenia (bridge).
     *
     * <p>Wywołać po tym jak agent skonsultował się z drugą stroną i chce przekazać klienta.
     * {@code callId} to oryginalne połączenie z klientem (ON_HOLD),
     * {@code secondCallId} to noga konsultacyjna (druga sesja).
     *
     * @param callId       identyfikator pierwotnej sesji (UUID lub Twilio SID)
     * @param secondCallId identyfikator drugiej nogi transferu
     * @param auth         kontekst uwierzytelnienia (JWT)
     * @return 204 No Content
     */
    @PostMapping("/{callId}/bridge/{secondCallId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Bridge two call legs (finalize attended transfer)",
            description = """
                    Connects two call legs after an attended transfer consultation.

                    callId is the original call (customer leg, typically ON_HOLD).
                    secondCallId is the consultation leg (to the target agent or number).

                    After a successful bridge:
                    - First leg (callId) transitions to TRANSFERRED.
                    - Second leg (secondCallId) transitions to ACTIVE.
                    - CONSULTING and ON_HOLD stages are closed in contact history.
                    - A TRANSFER event is recorded.

                    Validation:
                    - Caller must be the assigned agent of callId (403).
                    - Both sessions must exist and belong to the same tenant (404).
                    - Sessions must be in ACTIVE or ON_HOLD state (409).
                    """,
            responses = {
                    @ApiResponse(responseCode = "204", description = "Bridge executed, calls connected"),
                    @ApiResponse(responseCode = "403", description = "Agent is not the owner of callId or cross-tenant access"),
                    @ApiResponse(responseCode = "404", description = "Contact or call session not found for this callId"),
                    @ApiResponse(responseCode = "409", description = "Call sessions not in bridgeable state (ACTIVE or ON_HOLD)")
            }
    )
    public void bridgeCalls(
            @Parameter(description = "Original call leg (customer) – UUID or Twilio SID", required = true)
            @PathVariable @Size(max = 64) String callId,
            @Parameter(description = "Second call leg (consultation target) – UUID or Twilio SID", required = true)
            @PathVariable @Size(max = 64) String secondCallId,
            org.springframework.security.core.Authentication auth
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        log.info("[AgentCallController] BRIDGE: callId={}, secondCallId={}, agentId={}, tenant={}",
                callId, secondCallId, agentId, tenantId);

        contactService.bridgeCalls(callId, secondCallId, tenantId, agentId);

        log.info("[AgentCallController] Bridge wykonany: callId={}, secondCallId={}, agentId={}",
                callId, secondCallId, agentId);
    }

    // =========================================================================
    // Session info
    // =========================================================================

    /**
     * Returns the current session state for a call (for debugging / status polling).
     *
     * @param callId Twilio Call SID or mock callId
     * @return session map with status, contactId, etc.
     */
    @GetMapping("/{callId}/session")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Get call session state",
            description = "Returns current session state for a given callId. " +
                    "Useful for debugging or status reconciliation.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Session found"),
                    @ApiResponse(responseCode = "404", description = "No session for this callId")
            }
    )
    public ResponseEntity<Map<String, Object>> getSession(
            @PathVariable String callId
    ) {
        try {
            CallSession session = telephonyAdapter.getCallSession(callId);
            return ResponseEntity.ok(sessionToMap(session));
        } catch (TelephonyAdapter.TelephonyException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Resolves the "from" phone number for outbound calls.
     *
     * <p>Priority:
     * <ol>
     *   <li>Per-tenant phone number from {@link TenantTwilioConfigService}</li>
     *   <li>Global {@link TwilioProperties#getPhoneNumber()} (fallback for Twilio)</li>
     *   <li>Empty string (mock / dev environment – adapter ignores the value)</li>
     * </ol>
     *
     * @param tenantId tenant scope
     * @return resolved "from" number, never null
     */
    private String resolveFromNumber(UUID tenantId) {
        try {
            String perTenantNumber = tenantTwilioConfigService.getDecryptedConfig(tenantId)
                    .map(cfg -> cfg.phoneNumber())
                    .filter(StringUtils::hasText)
                    .orElse(null);
            if (perTenantNumber != null) {
                log.debug("[AgentCallController] Używam numeru per-tenant: {}, tenant={}",
                        maskPhone(perTenantNumber), tenantId);
                return perTenantNumber;
            }
        } catch (Exception e) {
            log.warn("[AgentCallController] Błąd odczytu per-tenant phone_number, tenant={}: {} – fallback",
                    tenantId, e.getMessage());
        }

        String globalNumber = twilioProperties.getPhoneNumber();
        if (StringUtils.hasText(globalNumber)) {
            log.debug("[AgentCallController] Używam globalnego numeru Twilio: {}, tenant={}",
                    maskPhone(globalNumber), tenantId);
            return globalNumber;
        }

        log.debug("[AgentCallController] Brak skonfigurowanego numeru from – używam pustego (mock), tenant={}", tenantId);
        return "";
    }

    /**
     * Masks a phone number for log output (last 4 digits visible).
     *
     * @param phone raw phone number
     * @return masked phone number (e.g. "+48****5678")
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "****";
        }
        return phone.substring(0, phone.length() - 4).replaceAll("\\d", "*")
                + phone.substring(phone.length() - 4);
    }



    /**
     * Translates a callId to a Twilio CallSid if the callId looks like a UUID (contactId from DB).
     *
     * <p>The frontend receives {@code contactId} (UUID) via the WebSocket {@code contact.assigned}
     * event and uses it in endpoint URLs. The telephony adapter session map is keyed by
     * Twilio CallSid (CA...), so the UUID must be resolved to a CallSid via the DB lookup.
     *
     * @param callId   the raw callId from the path variable (may be UUID or CA...)
     * @param tenantId tenant scope for the DB lookup
     * @return Twilio CallSid or the original callId if resolution fails / not needed
     */
    private String resolveCallSid(String callId, UUID tenantId) {
        try {
            UUID contactId = UUID.fromString(callId);
            return contactRepository.findCallSidByContactId(contactId, tenantId)
                    .orElse(callId);
        } catch (IllegalArgumentException e) {
            return callId;
        }
    }

    /**
     * Resolves the database contactId from the telephony session cache.
     *
     * <p>For inbound Twilio calls the contactId is stored in the session by
     * {@link com.contactcenter.domain.telephony.TwilioTelephonyAdapter#handleWebhookStatusUpdate}.
     * For mock calls it is stored by
     * {@link com.contactcenter.domain.telephony.MockTelephonyAdapter#simulateIncomingCall}.
     *
     * @param callId the telephony callId
     * @return UUID of the contact record or null if the session does not contain one
     */
    private UUID resolveContactId(String callId) {
        try {
            CallSession session = telephonyAdapter.getCallSession(callId);
            return session.getContactId();
        } catch (TelephonyAdapter.TelephonyException e) {
            log.warn("[AgentCallController] Nie znaleziono sesji dla callId={}: {}", callId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> sessionToMap(CallSession session) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("callId", session.getCallId());
        map.put("contactId", session.getContactId() != null ? session.getContactId().toString() : null);
        map.put("tenantId", session.getTenantId().toString());
        map.put("agentId", session.getAgentId() != null ? session.getAgentId().toString() : null);
        map.put("from", session.getFrom() != null ? session.getFrom() : "");
        map.put("to", session.getTo() != null ? session.getTo() : "");
        map.put("status", session.getStatus().name());
        return map;
    }
}
