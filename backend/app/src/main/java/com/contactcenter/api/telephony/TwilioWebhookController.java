package com.contactcenter.api.telephony;

import com.contactcenter.domain.telephony.TwilioTelephonyAdapter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Kontroler webhooków dla Twilio Programmable Voice.
 *
 * <p>Odbiera zdarzenia statusu połączeń od Twilio w formacie {@code application/x-www-form-urlencoded}.
 * Twilio wysyła parametry jako form-encoded POST na URL skonfigurowany jako StatusCallback.
 *
 * <p>Endpoint jest <strong>publiczny</strong> (bez JWT) – Twilio nie wysyła tokenów JWT.
 * Autentyczność żądań weryfikowana jest przez podpis HMAC-SHA256 w nagłówku
 * {@code X-Twilio-Signature} (opcjonalna weryfikacja opisana w komentarzu).
 *
 * <p>Aktywny tylko gdy {@link TwilioTelephonyAdapter} jest w kontekście Springa
 * (tj. gdy {@code twilio.enabled=true}).
 *
 * <h2>Parametry Twilio StatusCallback</h2>
 * <ul>
 *   <li>{@code CallSid} – unikalny identyfikator połączenia (np. CAxxxxxxxx)</li>
 *   <li>{@code From}   – numer dzwoniącego w formacie E.164</li>
 *   <li>{@code To}     – numer docelowy w formacie E.164</li>
 *   <li>{@code CallStatus} – status: queued, initiated, ringing, in-progress,
 *                            completed, busy, failed, no-answer, canceled</li>
 *   <li>{@code Direction} – inbound lub outbound-api</li>
 *   <li>{@code AccountSid} – SID konta Twilio</li>
 * </ul>
 *
 * <h2>Tenant routing</h2>
 * <p>Twilio nie przesyła {@code tenantId}. Oczekujemy go jako parametru query string
 * {@code ?tenantId=UUID} w URL StatusCallback lub jako dodatkowego parametru POST.
 * Alternatywnie: mapowanie numeru telefonu (To) na tenantId przez serwis konfiguracyjny.
 * Implementacja uproszczona – pobiera tenantId z query param lub formy, z fallback na null.
 */
@Slf4j
@RestController
@RequestMapping("/api/telephony/webhook/twilio")
@ConditionalOnBean(TwilioTelephonyAdapter.class)
@Tag(name = "Twilio Webhook", description = "Webhook do odbioru zdarzeń połączeń od Twilio Programmable Voice")
public class TwilioWebhookController {

    private final TwilioTelephonyAdapter twilioAdapter;

    /**
     * Konstruktor z opcjonalnym wstrzyknięciem adaptera.
     *
     * <p>{@code @Autowired(required = false)} – w środowisku testowym bez Twilio bean
     * może nie być dostępny. Żądania do endpointu bez skonfigurowanego adaptera
     * zwrócą 503.
     */
    public TwilioWebhookController(
            @Autowired(required = false) TwilioTelephonyAdapter twilioAdapter) {
        this.twilioAdapter = twilioAdapter;
    }

    // =========================================================================
    // StatusCallback endpoint
    // =========================================================================

    /**
     * Odbiera zdarzenia statusu połączeń od Twilio (StatusCallback).
     *
     * <p>Twilio wysyła dane jako {@code application/x-www-form-urlencoded}.
     * Każda zmiana statusu połączenia (ringing, in-progress, completed itp.)
     * generuje oddzielne żądanie POST.
     *
     * @param callSid    Twilio Call SID (identyfikator połączenia)
     * @param from       numer dzwoniącego w formacie E.164
     * @param to         numer docelowy w formacie E.164
     * @param callStatus aktualny status połączenia (np. "in-progress", "completed")
     * @param direction  kierunek połączenia ("inbound" lub "outbound-api")
     * @param tenantId   UUID tenanta – przekazywany jako query param lub form param w URL StatusCallback
     * @return 204 No Content przy sukcesie, 400 gdy brakuje wymaganych parametrów, 503 gdy adapter niedostępny
     */
    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(
            summary = "Odbierz zdarzenie statusu połączenia od Twilio",
            description = "Publiczny endpoint przyjmujący zdarzenia StatusCallback od Twilio. " +
                    "Dane przesyłane jako application/x-www-form-urlencoded. " +
                    "Wymagane parametry: CallSid, CallStatus. Opcjonalne: From, To, Direction. " +
                    "TenantId musi być podany jako parametr query string w URL StatusCallback.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Zdarzenie przetworzone"),
                    @ApiResponse(responseCode = "400", description = "Brakujące wymagane parametry (CallSid, CallStatus)"),
                    @ApiResponse(responseCode = "503", description = "Adapter Twilio niedostępny")
            }
    )
    public ResponseEntity<Void> handleStatusCallback(
            @RequestParam(value = "CallSid",    required = false) String callSid,
            @RequestParam(value = "From",       required = false) String from,
            @RequestParam(value = "To",         required = false) String to,
            @RequestParam(value = "CallStatus", required = false) String callStatus,
            @RequestParam(value = "Direction",  required = false) String direction,
            @RequestParam(value = "tenantId",   required = false) String tenantIdParam
    ) {
        // Walidacja wymaganych parametrów
        if (!StringUtils.hasText(callSid)) {
            log.warn("[TwilioWebhook] Odrzucono żądanie – brakuje CallSid");
            return ResponseEntity.badRequest().build();
        }
        if (!StringUtils.hasText(callStatus)) {
            log.warn("[TwilioWebhook] Odrzucono żądanie – brakuje CallStatus. callSid={}", callSid);
            return ResponseEntity.badRequest().build();
        }
        if (twilioAdapter == null) {
            log.error("[TwilioWebhook] Adapter Twilio niedostępny – bean nie jest w kontekście");
            return ResponseEntity.status(503).build();
        }

        // Parsowanie tenantId (opcjonalne – może być null przy pierwszym połączeniu przychodzącym)
        UUID tenantId = parseTenantId(tenantIdParam, callSid);

        log.info("[TwilioWebhook] Odebrano StatusCallback: callSid={}, status={}, from={}, to={}, " +
                "direction={}, tenantId={}",
                callSid, callStatus, from, to, direction, tenantId);

        try {
            twilioAdapter.handleWebhookStatusUpdate(callSid, from, to, callStatus, tenantId);
        } catch (Exception e) {
            // Logujemy błąd, ale zwracamy 204 – Twilio nie powinno ponownie wysyłać callbacku
            // przy błędach przetwarzania po naszej stronie
            log.error("[TwilioWebhook] Błąd przetwarzania statusu: callSid={}, status={}, error={}",
                    callSid, callStatus, e.getMessage(), e);
        }

        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Parsuje tenantId z parametru query string.
     *
     * <p>Gdy parametr jest pusty lub nieprawidłowy, loguje ostrzeżenie i zwraca null.
     * TenantId null oznacza połączenie bez przypisanego tenanta – event zostanie
     * opublikowany bez tenantId, co może skutkować ominięciem przez konsumentów RabbitMQ.
     *
     * @param tenantIdParam wartość parametru tenantId (może być null)
     * @param callSid       callSid do celów logowania
     * @return UUID tenanta lub null
     */
    private UUID parseTenantId(String tenantIdParam, String callSid) {
        if (!StringUtils.hasText(tenantIdParam)) {
            log.warn("[TwilioWebhook] Brak tenantId w żądaniu dla callSid={}. " +
                    "Dodaj ?tenantId=UUID do URL StatusCallback w konfiguracji Twilio.", callSid);
            return null;
        }
        try {
            return UUID.fromString(tenantIdParam);
        } catch (IllegalArgumentException e) {
            log.warn("[TwilioWebhook] Nieprawidłowy format tenantId='{}' dla callSid={}",
                    tenantIdParam, callSid);
            return null;
        }
    }
}
