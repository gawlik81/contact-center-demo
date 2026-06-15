package com.contactcenter.api.social;

import com.contactcenter.domain.social.SocialPlatform;
import com.contactcenter.domain.social.IncomingSocialMessage;
import com.contactcenter.domain.social.SocialMessagePublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Kontroler obsługujący webhooki od platform social media.
 *
 * <p>Endpointy PUBLICZNE (bez JWT) – platformy wywołują je bezpośrednio:
 * <ul>
 *   <li>{@code POST /api/webhooks/facebook} – nowa wiadomość Messenger</li>
 *   <li>{@code POST /api/webhooks/instagram} – nowa wiadomość Instagram Direct</li>
 *   <li>{@code POST /api/webhooks/whatsapp} – nowa wiadomość WhatsApp</li>
 *   <li>{@code GET /api/webhooks/facebook} – weryfikacja webhooka przez Meta</li>
 *   <li>{@code GET /api/webhooks/instagram} – weryfikacja webhooka przez Meta</li>
 *   <li>{@code GET /api/webhooks/whatsapp} – weryfikacja webhooka</li>
 * </ul>
 *
 * <p><strong>Wzorzec async:</strong> Każdy webhook zwraca HTTP 200 natychmiast (< 3s).
 * Payload jest parsowany defensywnie (try-catch), po czym zdarzenie jest publikowane
 * do kolejki RabbitMQ przez {@link SocialMessagePublisher}. Właściwe przetwarzanie
 * odbywa się asynchronicznie w {@link com.contactcenter.domain.social.SocialMessageConsumer}.
 *
 * <p><strong>Bezpieczeństwo:</strong> Platformy retryują przy HTTP 5xx. Nasz endpoint
 * zawsze zwraca 200, nawet przy błędzie parsowania – błędy są logowane. Weryfikacja
 * autentyczności (HMAC signature) powinna być dodana w produckji.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class SocialWebhookController {

    private final SocialMessagePublisher socialMessagePublisher;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Weryfikacja webhooka (GET) – wymagane przez Meta przy rejestracji
    // =========================================================================

    /**
     * Weryfikacja webhooka Facebook przez Meta.
     *
     * <p>Meta wysyła GET z parametrami:
     * <ul>
     *   <li>{@code hub.mode=subscribe}</li>
     *   <li>{@code hub.verify_token=TWÓJ_TOKEN}</li>
     *   <li>{@code hub.challenge=LOSOWA_WARTOŚĆ}</li>
     * </ul>
     *
     * <p>W produkcji: weryfikuj {@code hub.verify_token} z konfiguracją integracji.
     * Stub: przyjmuje każdy token i zwraca challenge.
     */
    @GetMapping("/facebook")
    public ResponseEntity<String> verifyFacebookWebhook(
            @RequestParam(value = "hub.mode", required = false) String mode,
            @RequestParam(value = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(value = "hub.challenge", required = false) String challenge) {

        log.info("[WebhookFB] Weryfikacja webhooka: mode={}, challenge={}", mode, challenge);

        // W produkcji: weryfikuj token z konfiguracją per integracja
        // Stub: akceptuj i zwróć challenge
        if ("subscribe".equals(mode) && challenge != null) {
            log.info("[WebhookFB] Webhook zweryfikowany pomyślnie");
            return ResponseEntity.ok(challenge);
        }

        log.warn("[WebhookFB] Nieprawidłowe parametry weryfikacji: mode={}", mode);
        return ResponseEntity.status(403).body("Verification failed");
    }

    /**
     * Weryfikacja webhooka Instagram przez Meta (identyczny format jak Facebook).
     */
    @GetMapping("/instagram")
    public ResponseEntity<String> verifyInstagramWebhook(
            @RequestParam(value = "hub.mode", required = false) String mode,
            @RequestParam(value = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(value = "hub.challenge", required = false) String challenge) {

        log.info("[WebhookIG] Weryfikacja webhooka: mode={}, challenge={}", mode, challenge);

        if ("subscribe".equals(mode) && challenge != null) {
            log.info("[WebhookIG] Webhook zweryfikowany pomyślnie");
            return ResponseEntity.ok(challenge);
        }

        log.warn("[WebhookIG] Nieprawidłowe parametry weryfikacji: mode={}", mode);
        return ResponseEntity.status(403).body("Verification failed");
    }

    /**
     * Weryfikacja webhooka WhatsApp Business.
     */
    @GetMapping("/whatsapp")
    public ResponseEntity<String> verifyWhatsAppWebhook(
            @RequestParam(value = "hub.mode", required = false) String mode,
            @RequestParam(value = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(value = "hub.challenge", required = false) String challenge) {

        log.info("[WebhookWA] Weryfikacja webhooka: mode={}, challenge={}", mode, challenge);

        if ("subscribe".equals(mode) && challenge != null) {
            log.info("[WebhookWA] Webhook zweryfikowany pomyślnie");
            return ResponseEntity.ok(challenge);
        }

        log.warn("[WebhookWA] Nieprawidłowe parametry weryfikacji: mode={}", mode);
        return ResponseEntity.status(403).body("Verification failed");
    }

    // =========================================================================
    // Odbiór wiadomości (POST)
    // =========================================================================

    /**
     * Odbiera zdarzenia z Facebook Messenger webhook.
     *
     * <p>Payload format:
     * <pre>{@code
     * {
     *   "object": "page",
     *   "entry": [{
     *     "id": "PAGE_ID",
     *     "messaging": [{
     *       "sender": {"id": "USER_ID"},
     *       "message": {"mid": "MSG_ID", "text": "Hello"}
     *     }]
     *   }]
     * }
     * }</pre>
     *
     * <p>Webhook ZAWSZE zwraca 200 – błędy są logowane bez przerywania.
     */
    @PostMapping("/facebook")
    public ResponseEntity<Void> handleFacebookWebhook(@RequestBody String rawPayload) {
        log.debug("[WebhookFB] Odebrany payload: {}", rawPayload);

        try {
            JsonNode root = objectMapper.readTree(rawPayload);

            // Walidacja – upewnij się że to zdarzenie od strony Facebook
            if (!"page".equals(root.path("object").asText())) {
                log.warn("[WebhookFB] Nieoczekiwany object type: {}", root.path("object").asText());
                return ResponseEntity.ok().build();
            }

            JsonNode entries = root.path("entry");
            if (!entries.isArray() || entries.isEmpty()) {
                log.debug("[WebhookFB] Brak wpisów w payload – ignoruję");
                return ResponseEntity.ok().build();
            }

            for (JsonNode entry : entries) {
                String pageId = entry.path("id").asText();
                JsonNode messagingList = entry.path("messaging");

                if (!messagingList.isArray() || messagingList.isEmpty()) {
                    log.debug("[WebhookFB] Wpis bez messaging: pageId={}", pageId);
                    continue;
                }

                for (JsonNode messagingEvent : messagingList) {
                    parseFacebookEvent(pageId, messagingEvent);
                }
            }

        } catch (Exception e) {
            // Defensywne parsowanie – błąd NIE zwraca 5xx (platformy retryują)
            log.error("[WebhookFB] Błąd parsowania payload: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Odbiera zdarzenia z Instagram Direct webhook (analogiczny format do Facebook).
     */
    @PostMapping("/instagram")
    public ResponseEntity<Void> handleInstagramWebhook(@RequestBody String rawPayload) {
        log.debug("[WebhookIG] Odebrany payload: {}", rawPayload);

        try {
            JsonNode root = objectMapper.readTree(rawPayload);

            // Instagram używa object="instagram"
            JsonNode entries = root.path("entry");
            if (!entries.isArray() || entries.isEmpty()) {
                log.debug("[WebhookIG] Brak wpisów w payload – ignoruję");
                return ResponseEntity.ok().build();
            }

            for (JsonNode entry : entries) {
                String pageId = entry.path("id").asText();
                JsonNode messagingList = entry.path("messaging");

                if (!messagingList.isArray() || messagingList.isEmpty()) {
                    continue;
                }

                for (JsonNode messagingEvent : messagingList) {
                    parseInstagramEvent(pageId, messagingEvent);
                }
            }

        } catch (Exception e) {
            log.error("[WebhookIG] Błąd parsowania payload: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Odbiera zdarzenia z WhatsApp Business API webhook.
     *
     * <p>Payload format:
     * <pre>{@code
     * {
     *   "object": "whatsapp_business_account",
     *   "entry": [{
     *     "changes": [{
     *       "value": {
     *         "metadata": {"phone_number_id": "PHONE_ID"},
     *         "messages": [{
     *           "from": "PHONE", "id": "MSG_ID",
     *           "text": {"body": "Hello"}, "type": "text"
     *         }]
     *       }
     *     }]
     *   }]
     * }
     * }</pre>
     */
    @PostMapping("/whatsapp")
    public ResponseEntity<Void> handleWhatsAppWebhook(@RequestBody String rawPayload) {
        log.debug("[WebhookWA] Odebrany payload: {}", rawPayload);

        try {
            JsonNode root = objectMapper.readTree(rawPayload);

            if (!"whatsapp_business_account".equals(root.path("object").asText())) {
                log.warn("[WebhookWA] Nieoczekiwany object type: {}", root.path("object").asText());
                return ResponseEntity.ok().build();
            }

            JsonNode entries = root.path("entry");
            if (!entries.isArray() || entries.isEmpty()) {
                log.debug("[WebhookWA] Brak wpisów w payload – ignoruję");
                return ResponseEntity.ok().build();
            }

            for (JsonNode entry : entries) {
                JsonNode changes = entry.path("changes");
                if (!changes.isArray()) continue;

                for (JsonNode change : changes) {
                    parseWhatsAppChange(change);
                }
            }

        } catch (Exception e) {
            log.error("[WebhookWA] Błąd parsowania payload: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    // =========================================================================
    // Metody parsowania (defensywne – każda otoczona try-catch)
    // =========================================================================

    /**
     * Parsuje zdarzenie messaging z payloadu Facebook.
     */
    private void parseFacebookEvent(String pageId, JsonNode messagingEvent) {
        try {
            String senderExternalId = messagingEvent.path("sender").path("id").asText();
            JsonNode messageNode = messagingEvent.path("message");

            if (messageNode.isMissingNode()) {
                log.debug("[WebhookFB] Zdarzenie bez message (np. delivery/read receipt) – pomijam");
                return;
            }

            String externalMessageId = messageNode.path("mid").asText();
            String text = messageNode.path("text").asText(null);

            if (externalMessageId.isBlank()) {
                log.warn("[WebhookFB] Brak mid w zdarzeniu – pomijam");
                return;
            }

            IncomingSocialMessage incoming = new IncomingSocialMessage(
                    SocialPlatform.FACEBOOK,
                    pageId,
                    senderExternalId,
                    externalMessageId,
                    text,
                    null, // załączniki – uproszczenie dla stubu
                    Instant.now()
            );

            socialMessagePublisher.publish(incoming);

        } catch (Exception e) {
            log.error("[WebhookFB] Błąd parsowania zdarzenia messaging: {}", e.getMessage(), e);
        }
    }

    /**
     * Parsuje zdarzenie messaging z payloadu Instagram (analogiczny format do Facebook).
     */
    private void parseInstagramEvent(String pageId, JsonNode messagingEvent) {
        try {
            String senderExternalId = messagingEvent.path("sender").path("id").asText();
            JsonNode messageNode = messagingEvent.path("message");

            if (messageNode.isMissingNode()) {
                log.debug("[WebhookIG] Zdarzenie bez message – pomijam");
                return;
            }

            String externalMessageId = messageNode.path("mid").asText();
            String text = messageNode.path("text").asText(null);

            if (externalMessageId.isBlank()) {
                log.warn("[WebhookIG] Brak mid w zdarzeniu – pomijam");
                return;
            }

            IncomingSocialMessage incoming = new IncomingSocialMessage(
                    SocialPlatform.INSTAGRAM,
                    pageId,
                    senderExternalId,
                    externalMessageId,
                    text,
                    null,
                    Instant.now()
            );

            socialMessagePublisher.publish(incoming);

        } catch (Exception e) {
            log.error("[WebhookIG] Błąd parsowania zdarzenia messaging: {}", e.getMessage(), e);
        }
    }

    /**
     * Parsuje zmianę z payloadu WhatsApp Business API.
     */
    private void parseWhatsAppChange(JsonNode change) {
        try {
            JsonNode value = change.path("value");
            String phoneNumberId = value.path("metadata").path("phone_number_id").asText();

            JsonNode messages = value.path("messages");
            if (!messages.isArray() || messages.isEmpty()) {
                log.debug("[WebhookWA] Brak messages w change (np. status update) – pomijam");
                return;
            }

            for (JsonNode msg : messages) {
                parseWhatsAppMessage(phoneNumberId, msg);
            }

        } catch (Exception e) {
            log.error("[WebhookWA] Błąd parsowania change: {}", e.getMessage(), e);
        }
    }

    /**
     * Parsuje pojedynczą wiadomość WhatsApp.
     */
    private void parseWhatsAppMessage(String phoneNumberId, JsonNode msg) {
        try {
            String from = msg.path("from").asText();
            String externalMessageId = msg.path("id").asText();
            String type = msg.path("type").asText("text");

            if (externalMessageId.isBlank()) {
                log.warn("[WebhookWA] Brak id wiadomości – pomijam");
                return;
            }

            // Wyciągnij treść w zależności od typu wiadomości
            String text = null;
            if ("text".equals(type)) {
                text = msg.path("text").path("body").asText(null);
            } else {
                log.info("[WebhookWA] Typ wiadomości '{}' – zapisuję bez treści (stub)", type);
            }

            // Timestamp z WhatsApp (Unix timestamp w sekundach)
            long timestampSec = msg.path("timestamp").asLong(0);
            Instant sentAt = timestampSec > 0
                    ? Instant.ofEpochSecond(timestampSec)
                    : Instant.now();

            IncomingSocialMessage incoming = new IncomingSocialMessage(
                    SocialPlatform.WHATSAPP,
                    phoneNumberId,
                    from,
                    externalMessageId,
                    text,
                    null,
                    sentAt
            );

            socialMessagePublisher.publish(incoming);

        } catch (Exception e) {
            log.error("[WebhookWA] Błąd parsowania wiadomości WhatsApp: {}", e.getMessage(), e);
        }
    }
}
