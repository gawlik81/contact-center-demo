package com.contactcenter.api.telephony;

import com.contactcenter.api.telephony.dto.WebhookRequest;
import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.domain.telephony.TelephonyEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;

/**
 * Kontroler webhooków telefonicznych – przyjmuje zdarzenia od zewnętrznego providera VoIP.
 *
 * <p>Endpoint {@code POST /api/telephony/webhook} jest <strong>publiczny</strong>
 * (bez JWT) – provider VoIP nie wysyła tokenów JWT. Weryfikacja autentyczności
 * odbywa się przez shared secret w nagłówku {@code X-Webhook-Secret}.
 *
 * <p>Zdarzenia są mapowane na domenowe {@link CallEvent} i publikowane na RabbitMQ
 * przez {@link TelephonyEventPublisher}.
 *
 * <p>Format żądania jest provider-agnostic. W produkcji, dla konkretnych providerów
 * (Twilio, Vonage), należy dodać dedykowane mappery lub osobne endpointy
 * pod {@code /api/telephony/webhook/twilio}, {@code /api/telephony/webhook/vonage}.
 */
@Slf4j
@RestController
@RequestMapping("/api/telephony/webhook")
@RequiredArgsConstructor
@Tag(name = "Telephony Webhook", description = "Webhook do odbioru zdarzeń VoIP od zewnętrznych providerów")
public class TelephonyWebhookController {

    private static final String WEBHOOK_SECRET_HEADER = "X-Webhook-Secret";

    private final TelephonyEventPublisher eventPublisher;

    @Value("${telephony.webhook.secret:dev-secret}")
    private String webhookSecret;

    // =========================================================================
    // Webhook endpoint
    // =========================================================================

    @PostMapping
    @Operation(
            summary = "Odbierz zdarzenie VoIP od providera",
            description = "Publiczny endpoint przyjmujący zdarzenia od zewnętrznego providera telefonii. " +
                    "Weryfikacja przez nagłówek X-Webhook-Secret. " +
                    "Zdarzenie mapowane na CallEvent i publikowane na RabbitMQ (cc.events).",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Zdarzenie przetworzone i opublikowane"),
                    @ApiResponse(responseCode = "400", description = "Nieprawidłowy format zdarzenia lub nieznany eventType"),
                    @ApiResponse(responseCode = "401", description = "Nieprawidłowy lub brakujący X-Webhook-Secret")
            }
    )
    public ResponseEntity<Void> handleWebhook(
            @Valid @RequestBody WebhookRequest request,
            @RequestHeader(value = WEBHOOK_SECRET_HEADER, required = false) String secret
    ) {
        // Weryfikacja shared secret
        if (!verifySecret(secret)) {
            log.warn("[TelephonyWebhook] Odrzucono żądanie – nieprawidłowy secret. callId={}",
                    request.callId());
            return ResponseEntity.status(401).build();
        }

        // Mapuj eventType na domenowy enum
        CallEvent.EventType eventType;
        try {
            eventType = parseEventType(request.eventType());
        } catch (IllegalArgumentException e) {
            log.warn("[TelephonyWebhook] Nieznany typ zdarzenia: '{}' dla callId={}",
                    request.eventType(), request.callId());
            return ResponseEntity.badRequest().build();
        }

        log.info("[TelephonyWebhook] Odebrano zdarzenie: type={}, callId={}, tenant={}, from={}, to={}",
                eventType, request.callId(), request.tenantId(), request.from(), request.to());

        // Buduj i publikuj event
        CallEvent event = CallEvent.builder()
                .eventType(eventType)
                .callId(request.callId())
                .tenantId(request.tenantId())
                .agentId(request.agentId())
                .from(request.from())
                .to(request.to())
                .timestamp(Instant.now())
                .metadata(request.metadata())
                .build();

        eventPublisher.publish(event);

        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Weryfikuje shared secret z nagłówka X-Webhook-Secret.
     *
     * <p>W produkcji rozważ zastąpienie HMAC-SHA256 signature verification
     * (zgodnie z wymaganiami Twilio / Vonage).
     */
    private boolean verifySecret(String secret) {
        if (!StringUtils.hasText(secret)) {
            return false;
        }
        return webhookSecret.equals(secret);
    }

    /**
     * Mapuje string eventType (od providera) na domenowy enum.
     *
     * <p>Obsługuje zarówno format domenowy (CALL_INCOMING) jak i
     * skrócony (incoming, answered itp.) dla elastyczności integracji.
     */
    private CallEvent.EventType parseEventType(String eventType) {
        // Próba bezpośredniego mapowania (CALL_INCOMING, CALL_ANSWERED itp.)
        try {
            return CallEvent.EventType.valueOf(eventType.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            // ignore, spróbuj skrócony format
        }

        // Skrócony format (incoming → CALL_INCOMING)
        return Arrays.stream(CallEvent.EventType.values())
                .filter(e -> e.toRoutingKeySuffix().equalsIgnoreCase(eventType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Nieznany typ zdarzenia: " + eventType));
    }
}
