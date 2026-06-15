package com.contactcenter.domain.voicebot;

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
import java.util.Base64;
import java.util.Optional;

/**
 * Implementacja {@link VoicebotClient} – klient HTTP do serwisu Python voicebot (BE-014).
 *
 * <p>Aktywowana tylko gdy {@code voicebot.enabled=true} (domyślnie false).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "voicebot.enabled", havingValue = "true")
class VoicebotClientImpl implements VoicebotClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String voicebotUrl;

    VoicebotClientImpl(
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

    @Override
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

    @Override
    public Optional<TranscribeResponse> transcribeFull(byte[] audioBytes, String audioFormat) {
        try {
            String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);

            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            node.put("audio_base64", audioBase64);
            node.put("audio_format", audioFormat);
            String requestBody = objectMapper.writeValueAsString(node);

            log.debug("[VoicebotClient] Wysyłam POST /ai/transcribe: format={}, audioSize={}B",
                    audioFormat, audioBytes.length);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(voicebotUrl + "/ai/transcribe"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                log.warn("[VoicebotClient] Błąd HTTP {} przy transkrypcji: body={}",
                        httpResponse.statusCode(),
                        httpResponse.body().substring(0, Math.min(200, httpResponse.body().length())));
                return Optional.empty();
            }

            TranscribeResponse response = objectMapper.readValue(
                    httpResponse.body(), TranscribeResponse.class);

            log.debug("[VoicebotClient] Transkrypcja zakończona: language={}, confidence={}, textLen={}",
                    response.language(), response.confidence(), response.transcript().length());

            return Optional.of(response);

        } catch (Exception ex) {
            log.warn("[VoicebotClient] Błąd transkrypcji (graceful degradation): error={}",
                    ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    @Deprecated
    public Optional<String> transcribe(byte[] audioBytes, String audioFormat) {
        return transcribeFull(audioBytes, audioFormat)
                .map(TranscribeResponse::transcript);
    }

    @Override
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
