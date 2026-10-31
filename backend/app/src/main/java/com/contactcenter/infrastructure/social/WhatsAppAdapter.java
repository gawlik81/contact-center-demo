package com.contactcenter.infrastructure.social;

import com.contactcenter.domain.exception.WhatsAppApiException;
import com.contactcenter.domain.social.SocialIntegrationDecrypted;
import com.contactcenter.domain.social.SocialIntegrationService;
import com.contactcenter.domain.social.SocialPlatform;
import com.contactcenter.domain.social.SocialMediaAdapter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Adapter WhatsApp Business (Cloud API).
 *
 * <p>Wysyłka wiadomości: {@code POST /v19.0/{phone_number_id}/messages} na Meta Graph API.
 * Dane integracji (phone_number_id jako {@code pageId}, odszyfrowany access token) pobierane
 * są przez {@link SocialIntegrationService#getDecryptedIntegration(UUID)} – ten adapter
 * (warstwa infrastruktury) nie ma bezpośredniego dostępu do repozytorium ani serwisu
 * szyfrowania tokenów (klasy pakietowe w {@code domain.social}).
 *
 * <p>Historia konwersacji pozostaje stubem – WhatsApp Business API nie udostępnia
 * endpointu do odczytu historii wiadomości (historia budowana wyłącznie z webhooków).
 */
@Slf4j
@Component
public class WhatsAppAdapter implements SocialMediaAdapter {

    private final SocialIntegrationService integrationService;
    private final ObjectMapper objectMapper;

    /**
     * Bazowy URL Meta Graph API – konfigurowalny (zamiast {@code static final}), aby testy
     * jednostkowe mogły przekierować wywołania na lokalny {@code com.sun.net.httpserver.HttpServer}
     * (ten sam wzorzec testowy co {@code TwilioRecordingDownloadServiceTest}), bez potrzeby
     * mockowania {@link HttpClient}. Domyślna wartość identyczna z poprzednią stałą – brak zmiany
     * zachowania produkcyjnego.
     */
    private final String graphApiBase;

    /**
     * Timeout połączenia TCP (naprawa CRITICAL, code review 2026-08-29): bez tego limitu
     * zawieszone/niedostępne Graph API mogłoby trzymać wątek w nieskończoność podczas próby
     * nawiązania połączenia.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Timeout całego żądania HTTP (nawiązanie połączenia + odpowiedź) – analogiczna ochrona
     * dla przypadku, gdy połączenie się nawiąże, ale Graph API nie odpowiada (albo odpowiada
     * bardzo wolno).
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    // HttpClient jako pole – reużywany między requestami (thread-safe)
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public WhatsAppAdapter(
            SocialIntegrationService integrationService,
            ObjectMapper objectMapper,
            @Value("${social.whatsapp.graph-api-base:https://graph.facebook.com/v19.0}") String graphApiBase) {
        this.integrationService = integrationService;
        this.objectMapper = objectMapper;
        this.graphApiBase = graphApiBase;
    }

    @Override
    public SocialPlatform getPlatform() {
        return SocialPlatform.WHATSAPP;
    }

    @Override
    public void sendMessage(UUID integrationId, String recipientExternalId,
                            String content, List<String> attachmentUrls) {

        // Naprawa (code review 2026-08-29): odrzucamy jawnie zamiast po cichu ignorować/wysyłać
        // żądanie, które i tak zawsze się nie powiedzie. WhatsApp Cloud API wymaga osobnego typu
        // wiadomości (image/document z polem "link") dla załączników – poza zakresem tej
        // implementacji (tylko wiadomości tekstowe).
        // TODO: obsługa typów image/document/audio przez odrębne body zgodne z Cloud API.
        if (attachmentUrls != null && !attachmentUrls.isEmpty()) {
            throw new IllegalArgumentException(
                    "Załączniki nie są obsługiwane w integracji WhatsApp — wyślij wiadomość tekstową.");
        }

        // Bez tej walidacji buildTextMessageBody() wygenerowałoby {"text":{"body":null}},
        // które Graph API odrzuci z HTTP 400 – dla agenta objawiłoby się to niejasnym 502
        // zamiast czytelnego błędu walidacji w momencie próby wysyłki.
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Treść wiadomości WhatsApp nie może być pusta.");
        }

        SocialIntegrationDecrypted integration = integrationService.getDecryptedIntegration(integrationId);
        String phoneNumberId = integration.pageId();

        String requestBody = buildTextMessageBody(recipientExternalId, content);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(graphApiBase + "/" + phoneNumberId + "/messages"))
                // Token przekazywany w nagłówku Authorization: Bearer, NIGDY jako query param
                // – query params trafiają do access logów infrastruktury (ryzyko wycieku).
                .header("Authorization", "Bearer " + integration.accessToken())
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("[WhatsAppAdapter] Błąd wysyłki wiadomości: integrationId={}, recipient={}, " +
                          "status={}, body={}",
                        integrationId, recipientExternalId, response.statusCode(), response.body());
                throw new WhatsAppApiException(
                        "WhatsApp Cloud API zwróciło błąd HTTP " + response.statusCode());
            }

            log.info("[WhatsAppAdapter] Wiadomość wysłana: integrationId={}, recipient={}, status={}",
                    integrationId, recipientExternalId, response.statusCode());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WhatsAppApiException("Wysyłka wiadomości WhatsApp przerwana", e);
        } catch (IOException e) {
            log.error("[WhatsAppAdapter] Błąd sieciowy przy wysyłce wiadomości: integrationId={}, error={}",
                    integrationId, e.getMessage());
            throw new WhatsAppApiException("Błąd komunikacji z WhatsApp Cloud API", e);
        }
    }

    @Override
    public List<SocialMessageDto> getConversationHistory(UUID integrationId,
                                                          String conversationId, int limit) {
        log.info("[WhatsAppAdapter][STUB] Pobieranie historii: integrationId={}, conversationId={}, limit={}",
                integrationId, conversationId, limit);

        // STUB: WhatsApp Business API nie udostępnia endpoint do odczytu historii.
        // Historia budowana jest wyłącznie z webhooków (baza danych lokalna).
        return Collections.emptyList();
    }

    /**
     * Buduje JSON body wiadomości tekstowej dla WhatsApp Cloud API.
     *
     * <p>Format: {@code {"messaging_product":"whatsapp","to":"...","type":"text","text":{"body":"..."}}}.
     * Budowane przez Jackson {@link ObjectNode}, nie przez ręczną konkatenację stringów –
     * unika ryzyka wstrzyknięcia nieprawidłowego JSON-a przez treść wiadomości (np. cudzysłowy).
     */
    private String buildTextMessageBody(String recipientExternalId, String content) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("messaging_product", "whatsapp");
        body.put("to", recipientExternalId);
        body.put("type", "text");
        body.putObject("text").put("body", content);

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new WhatsAppApiException("Błąd serializacji treści wiadomości WhatsApp", e);
        }
    }
}
