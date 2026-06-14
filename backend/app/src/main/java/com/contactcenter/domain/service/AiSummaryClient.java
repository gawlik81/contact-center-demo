package com.contactcenter.domain.service;

import com.contactcenter.domain.exception.AiSummaryGenerationException;
import com.contactcenter.domain.voicebot.VoicebotClient;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Klient HTTP do serwisu Python voicebot – endpoint generowania podsumowania AI (BE-089).
 *
 * <p>Zawsze aktywny (brak {@code @ConditionalOnProperty}) – w przeciwieństwie do
 * {@link VoicebotClient}, który wymaga {@code voicebot.enabled=true}.
 * AiSummaryClient jest używany przez {@link AiSummaryService} wywoływany jawnie przez agenta.
 *
 * <p>Timeout: 30s – generowanie podsumowania AI może być wolniejsze niż analiza audio w czasie rzeczywistym.
 * Używa java.net.http.HttpClient (analogicznie do {@link VoicebotClient}).
 */
@Slf4j
@Service
public class AiSummaryClient {

    // =========================================================================
    // DTO
    // =========================================================================

    public record AiSummarizeRequest(
            @JsonProperty("channel")          String channel,
            @JsonProperty("content")          String content,
            @JsonProperty("provider")         String provider,
            @JsonProperty("api_key")          String apiKey,
            @JsonProperty("model_name")       String modelName,
            @JsonProperty("azure_endpoint")   String azureEndpoint,
            @JsonProperty("deployment_name")  String deploymentName,
            @JsonProperty("prompt_template")  String promptTemplate
    ) {}

    public record AiSummarizeResponse(
            @JsonProperty("summary")     String summary,
            @JsonProperty("model_used")  String modelUsed,
            @JsonProperty("tokens_used") int tokensUsed
    ) {}

    // =========================================================================
    // Pola
    // =========================================================================

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String voicebotUrl;

    // =========================================================================
    // Konstruktor
    // =========================================================================

    public AiSummaryClient(
            @Value("${voicebot.url:http://localhost:8001}") String voicebotUrl,
            ObjectMapper springObjectMapper
    ) {
        this.voicebotUrl = voicebotUrl;
        // Kopia Spring-owego ObjectMapper z SNAKE_CASE – zgodność z Pydantic po stronie Python
        this.objectMapper = springObjectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        log.info("[AiSummaryClient] Zainicjalizowano klienta AI summarize: url={}", voicebotUrl);
    }

    // =========================================================================
    // Metody
    // =========================================================================

    /**
     * Wysyła żądanie generowania podsumowania do serwisu Python.
     *
     * <p>Przy błędzie HTTP (status >= 300) lub wyjątku sieciowym (timeout, IOException)
     * rzuca {@link AiSummaryGenerationException} – nie stosuje graceful degradation,
     * bo operacja jest wywoływana jawnie przez agenta i wymaga informacji o błędzie.
     *
     * <p><strong>Bezpieczeństwo:</strong> {@code apiKey} NIE jest logowany – tylko
     * channel i provider, aby nie ujawniać poufnych kluczy w logach.
     *
     * @param request dane do generowania podsumowania
     * @return odpowiedź z podsumowaniem i metadanymi modelu
     * @throws AiSummaryGenerationException gdy serwis zwrócił błąd lub nie jest dostępny
     */
    public AiSummarizeResponse summarize(AiSummarizeRequest request) {
        log.debug("[AiSummaryClient] Wywołanie POST /ai/summarize: channel={}, provider={}",
                request.channel(), request.provider());
        try {
            // Budujemy JSON ręcznie przez ObjectNode – pomijamy problemy z serializacją Java record
            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            node.put("channel",          request.channel());
            node.put("content",          request.content());
            node.put("provider",         request.provider());
            node.put("api_key",          request.apiKey());
            node.put("model_name",       request.modelName());
            node.put("azure_endpoint",   request.azureEndpoint());
            node.put("deployment_name",  request.deploymentName());
            node.put("prompt_template",  request.promptTemplate());

            String requestBody = objectMapper.writeValueAsString(node);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(voicebotUrl + "/ai/summarize"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                String truncated = httpResponse.body().substring(
                        0, Math.min(300, httpResponse.body().length()));
                log.warn("[AiSummaryClient] Błąd HTTP {} od serwisu AI: channel={}, body={}",
                        httpResponse.statusCode(), request.channel(), truncated);
                throw new AiSummaryGenerationException(
                        "Serwis AI zwrócił błąd HTTP " + httpResponse.statusCode()
                                + ": " + truncated);
            }

            AiSummarizeResponse response = objectMapper.readValue(
                    httpResponse.body(), AiSummarizeResponse.class);

            log.debug("[AiSummaryClient] Podsumowanie wygenerowane: modelUsed={}, tokensUsed={}",
                    response.modelUsed(), response.tokensUsed());

            return response;

        } catch (AiSummaryGenerationException ex) {
            // Przepuść własny wyjątek bez owijania
            throw ex;
        } catch (Exception ex) {
            log.warn("[AiSummaryClient] Błąd wywołania serwisu AI: channel={}, error={}",
                    request.channel(), ex.getMessage());
            throw new AiSummaryGenerationException(
                    "Błąd komunikacji z serwisem AI: " + ex.getMessage(), ex);
        }
    }
}
