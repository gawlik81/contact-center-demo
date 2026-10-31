package com.contactcenter.infrastructure.social;

import com.contactcenter.domain.exception.WhatsAppApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Weryfikuje parę (phone_number_id, access_token) względem Meta Graph API PRZED zapisaniem
 * integracji WhatsApp Business (pre-flight check, naprawa code review 2026-08-29).
 *
 * <p>Bez tej weryfikacji {@code POST /api/integrations/WHATSAPP/connect} zapisywał integrację
 * "w ciemno" – pierwszą realną weryfikacją poprawności pary (token, numer) była dopiero pierwsza
 * próba wysłania wiadomości przez agenta, kończąca się wtedy niejasnym HTTP 502.
 *
 * <p>Celowo NIE dzieli {@link HttpClient}/stałych timeoutów z {@link WhatsAppAdapter} – w obu
 * miejscach potrzebne są tylko dwie krótkie stałe {@link Duration} i lekki klient HTTP;
 * wydzielenie wspólnej abstrakcji dla dwóch operacji o innej semantyce (POST wysyłki wiadomości
 * mapowany zawsze na 502 vs. GET weryfikacji mapowany na 422 dla błędnych danych i na 502 tylko
 * dla awarii sieci) dodałoby więcej złożoności niż realnie oszczędza przy zaledwie dwóch użyciach.
 */
@Slf4j
@Component
public class WhatsAppGraphApiVerifier {

    /** Timeout połączenia TCP – analogiczny do stałej {@code CONNECT_TIMEOUT} w {@link WhatsAppAdapter}. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Timeout całego żądania weryfikacyjnego (nawiązanie połączenia + odpowiedź). */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final String graphApiBase;

    public WhatsAppGraphApiVerifier(
            @Value("${social.whatsapp.graph-api-base:https://graph.facebook.com/v19.0}") String graphApiBase) {
        this.graphApiBase = graphApiBase;
    }

    /**
     * Weryfikuje, że podany {@code accessToken} ma dostęp do {@code phoneNumberId} przez lekkie
     * wywołanie {@code GET /{phoneNumberId}?fields=verified_name}.
     *
     * @param phoneNumberId identyfikator numeru telefonu WhatsApp Business (Graph API)
     * @param accessToken   permanentny access token wklejony ręcznie przez administratora
     * @throws IllegalArgumentException gdy Graph API zwróci status inny niż 2xx (błędny token
     *         lub numer, np. 401/403/404) – mapowane przez {@code GlobalExceptionHandler} na
     *         HTTP 422, spójnie z pozostałymi walidacjami biznesowymi w tym module.
     * @throws WhatsAppApiException gdy weryfikacja nie powiodła się z powodu błędu sieciowego lub
     *         timeoutu (Graph API niedostępne, nie kwestia poprawności danych) – mapowane na
     *         HTTP 502, spójnie z błędami wysyłki wiadomości w {@link WhatsAppAdapter}.
     */
    public void verifyPhoneNumberAccess(String phoneNumberId, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(graphApiBase + "/" + phoneNumberId + "?fields=verified_name"))
                // Token przekazywany w nagłówku Authorization: Bearer, NIGDY jako query param
                // – ten sam wzorzec co w WhatsAppAdapter (query params trafiają do access logów).
                .header("Authorization", "Bearer " + accessToken)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[WhatsAppGraphApiVerifier] Weryfikacja integracji nieudana: phoneNumberId={}, status={}",
                        phoneNumberId, response.statusCode());
                throw new IllegalArgumentException(
                        "Nieprawidłowy token dostępu lub identyfikator numeru telefonu. " +
                        "Sprawdź dane w Meta Business Suite i spróbuj ponownie.");
            }

            log.info("[WhatsAppGraphApiVerifier] Weryfikacja integracji OK: phoneNumberId={}, status={}",
                    phoneNumberId, response.statusCode());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WhatsAppApiException("Weryfikacja integracji WhatsApp przerwana", e);
        } catch (IOException e) {
            log.error("[WhatsAppGraphApiVerifier] Błąd sieciowy przy weryfikacji integracji: " +
                      "phoneNumberId={}, error={}", phoneNumberId, e.getMessage());
            throw new WhatsAppApiException(
                    "Błąd komunikacji z WhatsApp Cloud API podczas weryfikacji danych integracji", e);
        }
    }
}
