package com.contactcenter.domain.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Klient HTTP do serwisu Python voicebot (BE-014).
 *
 * <p>Aktywowany tylko gdy {@code voicebot.enabled=true} (domyślnie false).
 * Przy błędzie – loguje WARN i zwraca {@link Optional#empty()}, żeby silnik IVR
 * mógł zastosować graceful degradation (fallback node).
 *
 * <p>Timeout: connectTimeout=1s, readTimeout=3s (wymaganie: p95 < 2s dla 5s audio).
 * Używa java.net.http.HttpClient zamiast RestClient – nie wymaga negocjacji Content-Type.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "voicebot.enabled", havingValue = "true")
public class VoicebotClient {

    // DTO do komunikacji z serwisem Python
    public record TurnRequest(
            @JsonProperty("session_id")   String sessionId,
            @JsonProperty("tenant_id")    String tenantId,
            @JsonProperty("contact_id")   String contactId,
            @JsonProperty("audio_base64") String audioBase64,
            @JsonProperty("audio_format") String audioFormat,
            @JsonProperty("turn_number")  int turnNumber
    ) {}

    public record TurnResponse(
            @JsonProperty("session_id")           String sessionId,
            @JsonProperty("transcript")           String transcript,
            @JsonProperty("intent")               String intent,
            @JsonProperty("confidence")           double confidence,
            @JsonProperty("escalate")             boolean escalate,
            @JsonProperty("escalation_reason")    String escalationReason,
            @JsonProperty("full_transcript")      List<String> fullTranscript,
            @JsonProperty("response_text")        String responseText,
            @JsonProperty("continue_conversation") boolean continueConversation
    ) {}

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String voicebotUrl;

    public VoicebotClient(
            @Value("${voicebot.url:http://localhost:8001}") String voicebotUrl,
            ObjectMapper springObjectMapper
    ) {
        this.voicebotUrl = voicebotUrl;
        // Kopia Spring-owego ObjectMapper (ma ParameterNamesModule + record support)
        // z SNAKE_CASE do zgodności z Pydantic
        this.objectMapper = springObjectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();

        log.info("[VoicebotClient] Zainicjalizowano klienta voicebota: url={}", voicebotUrl);
    }

    /**
     * Przetwarza jedną turę konwersacji.
     *
     * @param request dane audio + kontekst sesji
     * @return odpowiedź voicebota lub empty() gdy serwis niedostępny lub błąd
     */
    public Optional<TurnResponse> processTurn(TurnRequest request) {
        try {
            // Budujemy JSON ręcznie przez ObjectNode – omijamy problemy z serializacją Java record
            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            node.put("session_id",   request.sessionId());
            node.put("tenant_id",    request.tenantId());
            node.put("contact_id",   request.contactId());
            node.put("audio_base64", request.audioBase64());
            node.put("audio_format", request.audioFormat());
            node.put("turn_number",  request.turnNumber());
            String requestBody = objectMapper.writeValueAsString(node);
            log.debug("[VoicebotClient] Wysyłam JSON (len={}): session_id={}", requestBody.length(), request.sessionId());

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(voicebotUrl + "/voicebot/turn"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                log.warn("[VoicebotClient] Błąd HTTP {}: sessionId={}, body={}",
                        httpResponse.statusCode(), request.sessionId(),
                        httpResponse.body().substring(0, Math.min(200, httpResponse.body().length())));
                return Optional.empty();
            }

            TurnResponse response = objectMapper.readValue(httpResponse.body(), TurnResponse.class);

            log.debug("[VoicebotClient] Odpowiedź: sessionId={}, intent={}, confidence={}, escalate={}",
                    request.sessionId(), response.intent(), response.confidence(), response.escalate());

            return Optional.of(response);

        } catch (Exception ex) {
            log.warn("[VoicebotClient] Błąd wywołania voicebota (graceful degradation): sessionId={}, error={}",
                    request.sessionId(), ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Usuwa sesję voicebota po hangup.
     * Błędy są logowane ale nie propagowane.
     *
     * @param sessionId identyfikator sesji do usunięcia
     */
    public void deleteSession(String sessionId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(voicebotUrl + "/voicebot/session/" + sessionId))
                    .DELETE()
                    .timeout(Duration.ofSeconds(3))
                    .build();

            httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());

            log.debug("[VoicebotClient] Sesja usunięta: sessionId={}", sessionId);

        } catch (Exception ex) {
            log.warn("[VoicebotClient] Błąd usuwania sesji voicebota: sessionId={}, error={}",
                    sessionId, ex.getMessage());
        }
    }
}
