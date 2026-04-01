package com.contactcenter.api.telephony;

import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.service.ContactService;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
