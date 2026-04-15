package com.contactcenter.api.telephony;

import com.contactcenter.api.telephony.dto.SimulateCallRequest;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.MockTelephonyAdapter;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Kontroler symulacji zdarzeń telefonicznych – <strong>tylko profil dev</strong>.
 *
 * <p>Umożliwia programowe wyzwalanie zdarzeń telefonicznych bez prawdziwego providera VoIP.
 * Używany podczas development i testów manualnych w Swagger UI.
 *
 * <p>Endpoint {@code POST /api/dev/telephony/simulate} wymaga autoryzacji JWT
 * (nie jest publiczny) – dostępny tylko dla zalogowanych użytkowników.
 *
 * <p>Bean rejestrowany wyłącznie gdy aktywny profil zawiera {@code dev}
 * (Spring {@code @Profile("dev")}).
 */
@Slf4j
@RestController
@RequestMapping("/api/dev/telephony")
@RequiredArgsConstructor
@Profile("dev")
@ConditionalOnProperty(name = "telephony.provider", havingValue = "mock", matchIfMissing = true)
@Tag(name = "Dev: Telephony Simulator", description = "Symulacja zdarzeń telefonicznych (tylko dev)")
public class MockCallController {

    private final MockTelephonyAdapter mockAdapter;

    // =========================================================================
    // Symulacja zdarzeń
    // =========================================================================

    @PostMapping("/simulate")
    @Operation(
            summary = "Zasymuluj zdarzenie telefoniczne",
            description = """
                    Wyzwala zdarzenie telefoniczne w MockTelephonyAdapter. Dostępne akcje:
                    - INCOMING: symuluje przychodzące połączenie (wymagane: tenantId, from, to)
                    - ANSWER: odbiera połączenie (wymagane: callId)
                    - HANGUP: kończy połączenie (wymagane: callId)
                    - HOLD: wstrzymuje połączenie (wymagane: callId)
                    - UNHOLD: wznawia połączenie (wymagane: callId)
                    - BLIND_TRANSFER: blind transfer (wymagane: callId, to)
                    - ATTENDED_TRANSFER: attended transfer – tworzy 2nd leg (wymagane: callId, to)
                    - BRIDGE: łączy dwie nogi attended transfer (wymagane: callId, secondCallId)

                    Zdarzenia są publikowane na RabbitMQ (cc.events).
                    """
    )
    public ResponseEntity<Map<String, Object>> simulate(
            @Valid @RequestBody SimulateCallRequest request
    ) {
        log.info("[MockCallController] Symulacja: action={}, tenant={}, callId={}",
                request.action(), request.tenantId(), request.callId());

        return switch (request.action().toUpperCase()) {
            case "INCOMING" -> handleIncoming(request);
            case "ANSWER" -> handleAnswer(request);
            case "HANGUP" -> handleHangup(request);
            case "HOLD" -> handleHold(request, true);
            case "UNHOLD" -> handleHold(request, false);
            case "BLIND_TRANSFER" -> handleTransfer(request, TelephonyAdapter.TransferType.BLIND);
            case "ATTENDED_TRANSFER" -> handleTransfer(request, TelephonyAdapter.TransferType.ATTENDED);
            case "BRIDGE" -> handleBridge(request);
            default -> ResponseEntity.badRequest().body(Map.of(
                    "error", "Nieznana akcja: " + request.action(),
                    "dostepneAkcje", "INCOMING, ANSWER, HANGUP, HOLD, UNHOLD, BLIND_TRANSFER, ATTENDED_TRANSFER, BRIDGE"
            ));
        };
    }

    @GetMapping("/sessions/count")
    @Operation(summary = "Liczba aktywnych sesji połączeń w mock adapterze")
    public ResponseEntity<Map<String, Integer>> getActiveSessionCount() {
        return ResponseEntity.ok(Map.of("activeSessions", mockAdapter.getActiveSessionCount()));
    }

    @GetMapping("/sessions/{callId}")
    @Operation(summary = "Pobierz sesję połączenia po callId lub contactId (UUID)")
    public ResponseEntity<CallSession> getSession(@PathVariable String callId) {
        try {
            return ResponseEntity.ok(mockAdapter.getCallSession(mockAdapter.resolveCallId(callId)));
        } catch (TelephonyAdapter.TelephonyException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // =========================================================================
    // Prywatne handlery
    // =========================================================================

    private ResponseEntity<Map<String, Object>> handleIncoming(SimulateCallRequest request) {
        if (request.from() == null || request.to() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Akcja INCOMING wymaga pól: from, to, tenantId"
            ));
        }

        // Gdy agentId nie podano jawnie, przypisz połączenie do zalogowanego użytkownika.
        // W środowisku dev agent zwykle symuluje połączenie "do siebie", więc jest to
        // właściwe domyślne zachowanie – gwarantuje że contact.agent_id = current_user_id,
        // co jest wymagane przez ContactService.setDisposition (walidacja własności).
        UUID effectiveAgentId = request.agentId() != null
                ? request.agentId()
                : TenantContext.getUserId();

        log.info("[MockCallController] INCOMING: from={}, to={}, agentId={} (explicit={})",
                request.from(), request.to(), effectiveAgentId, request.agentId() != null);

        CallSession session = mockAdapter.simulateIncomingCall(
                request.tenantId(), request.from(), request.to(), effectiveAgentId
        );

        return ResponseEntity.ok(sessionToMap(session, "Przychodzące połączenie zasymulowane"));
    }

    private ResponseEntity<Map<String, Object>> handleAnswer(SimulateCallRequest request) {
        requireCallId(request);
        String callId = mockAdapter.resolveCallId(request.callId());
        mockAdapter.answerCall(callId, null);
        return ResponseEntity.ok(Map.of(
                "callId", callId,
                "message", "Połączenie odebrane"
        ));
    }

    private ResponseEntity<Map<String, Object>> handleHangup(SimulateCallRequest request) {
        requireCallId(request);
        String callId = mockAdapter.resolveCallId(request.callId());
        mockAdapter.hangupCall(callId);
        return ResponseEntity.ok(Map.of(
                "callId", callId,
                "message", "Połączenie zakończone"
        ));
    }

    private ResponseEntity<Map<String, Object>> handleHold(SimulateCallRequest request, boolean hold) {
        requireCallId(request);
        String callId = mockAdapter.resolveCallId(request.callId());
        mockAdapter.holdCall(callId, hold);
        return ResponseEntity.ok(Map.of(
                "callId", callId,
                "message", hold ? "Połączenie wstrzymane" : "Połączenie wznowione"
        ));
    }

    private ResponseEntity<Map<String, Object>> handleTransfer(
            SimulateCallRequest request, TelephonyAdapter.TransferType type) {
        requireCallId(request);
        if (request.to() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Transfer wymaga pola 'to' (numer docelowy)"
            ));
        }

        String callId = mockAdapter.resolveCallId(request.callId());
        CallSession result = mockAdapter.transferCall(callId, request.to(), type);
        return ResponseEntity.ok(sessionToMap(result,
                type == TelephonyAdapter.TransferType.BLIND
                        ? "Blind transfer wykonany"
                        : "Attended transfer – 2nd leg utworzony. Wywołaj BRIDGE po odebraniu."));
    }

    private ResponseEntity<Map<String, Object>> handleBridge(SimulateCallRequest request) {
        requireCallId(request);
        if (request.secondCallId() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BRIDGE wymaga pola 'secondCallId'"
            ));
        }

        String callId = mockAdapter.resolveCallId(request.callId());
        String secondCallId = mockAdapter.resolveCallId(request.secondCallId());
        mockAdapter.bridgeCalls(callId, secondCallId);
        return ResponseEntity.ok(Map.of(
                "callId1", callId,
                "callId2", secondCallId,
                "message", "Bridge wykonany. Połączenia połączone."
        ));
    }

    private void requireCallId(SimulateCallRequest request) {
        if (request.callId() == null || request.callId().isBlank()) {
            throw new IllegalArgumentException("callId jest wymagany dla akcji: " + request.action());
        }
    }

    private Map<String, Object> sessionToMap(CallSession session, String message) {
        // contactId (UUID z DB) jest kluczowy dla frontendu – umożliwia PATCH /api/contacts/{contactId}/...
        // Gdy null – persistMockContact() zawiodło i frontend użyje callId jako fallbacku (422 przy disposition).
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("callId", session.getCallId());
        map.put("contactId", session.getContactId() != null ? session.getContactId().toString() : null);
        map.put("tenantId", session.getTenantId().toString());
        map.put("from", session.getFrom() != null ? session.getFrom() : "");
        map.put("to", session.getTo() != null ? session.getTo() : "");
        map.put("status", session.getStatus().name());
        map.put("message", message);
        return map;
    }
}
