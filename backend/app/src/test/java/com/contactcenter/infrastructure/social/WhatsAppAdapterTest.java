package com.contactcenter.infrastructure.social;

import com.contactcenter.domain.exception.WhatsAppApiException;
import com.contactcenter.domain.social.SocialIntegrationDecrypted;
import com.contactcenter.domain.social.SocialIntegrationService;
import com.contactcenter.domain.social.SocialMediaAdapter;
import com.contactcenter.domain.social.SocialPlatform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link WhatsAppAdapter#sendMessage} (świeżo dodana, realna implementacja –
 * wcześniej brak testów dla {@code infrastructure.social}).
 *
 * <p><strong>Zmiana produkcyjna towarzysząca tym testom (minimalna, opisana też w podsumowaniu
 * zadania):</strong> {@code GRAPH_API_BASE} przekształcony z {@code private static final String}
 * w konfigurowalne pole konstruktora (domyślna wartość identyczna, {@code @Value} z fallbackiem
 * na produkcyjny URL Meta Graph API). Bez tej zmiany testy tego adaptera wymagałyby mockowania
 * {@link java.net.http.HttpClient#send}, którego {@code WhatsAppAdapter} nie eksponuje (pole
 * tworzone inline). Dzięki zmianie testy używają tego samego wzorca co
 * {@code TwilioRecordingDownloadServiceTest} w tym repo: prawdziwy lokalny
 * {@code com.sun.net.httpserver.HttpServer} zamiast mocka HTTP – weryfikacja na poziomie
 * rzeczywistego żądania/odpowiedzi HTTP (nagłówki, JSON body), nie tylko interakcji z mockiem.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WhatsAppAdapter – sendMessage() (Meta Graph API / WhatsApp Cloud API)")
class WhatsAppAdapterTest {

    private static final UUID INTEGRATION_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
    private static final String PHONE_NUMBER_ID = "1234567890";
    private static final String ACCESS_TOKEN = "EAAB-super-secret-permanent-token";
    private static final String RECIPIENT = "48600123456";

    @Mock
    private SocialIntegrationService integrationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private com.sun.net.httpserver.HttpServer httpServer;
    private WhatsAppAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = httpServer.getAddress().getPort();
        adapter = new WhatsAppAdapter(integrationService, objectMapper, "http://localhost:" + port);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private SocialIntegrationDecrypted decryptedIntegration() {
        return new SocialIntegrationDecrypted(INTEGRATION_ID, SocialPlatform.WHATSAPP, PHONE_NUMBER_ID, ACCESS_TOKEN);
    }

    // =========================================================================
    // getPlatform / getConversationHistory
    // =========================================================================

    @Test
    @DisplayName("getPlatform() zwraca WHATSAPP")
    void getPlatform_returnsWhatsApp() {
        assertThat(adapter.getPlatform()).isEqualTo(SocialPlatform.WHATSAPP);
    }

    @Test
    @DisplayName("getConversationHistory() to stub - zawsze zwraca pustą listę, bez wywołań sieciowych")
    void getConversationHistory_returnsEmptyList() {
        List<SocialMediaAdapter.SocialMessageDto> result =
                adapter.getConversationHistory(INTEGRATION_ID, "conv-1", 50);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // sendMessage() - happy path
    // =========================================================================

    @Nested
    @DisplayName("sendMessage() - happy path")
    class HappyPath {

        @Test
        @DisplayName("wysyła POST na /{phoneNumberId}/messages z Authorization: Bearer <token> i poprawnym JSON body")
        void sendsCorrectRequestAndBody() throws Exception {
            AtomicReference<String> capturedMethod = new AtomicReference<>();
            AtomicReference<String> capturedPath = new AtomicReference<>();
            AtomicReference<String> capturedAuthHeader = new AtomicReference<>();
            AtomicReference<String> capturedContentType = new AtomicReference<>();
            AtomicReference<String> capturedBody = new AtomicReference<>();

            httpServer.createContext("/" + PHONE_NUMBER_ID + "/messages", exchange -> {
                capturedMethod.set(exchange.getRequestMethod());
                capturedPath.set(exchange.getRequestURI().getPath());
                capturedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

                byte[] responseBytes = "{\"messages\":[{\"id\":\"wamid.TEST\"}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            });
            httpServer.start();

            when(integrationService.getDecryptedIntegration(INTEGRATION_ID)).thenReturn(decryptedIntegration());

            adapter.sendMessage(INTEGRATION_ID, RECIPIENT, "Cześć, jak mogę pomóc?", List.of());

            assertThat(capturedMethod.get()).isEqualTo("POST");
            assertThat(capturedPath.get()).isEqualTo("/" + PHONE_NUMBER_ID + "/messages");
            assertThat(capturedAuthHeader.get()).isEqualTo("Bearer " + ACCESS_TOKEN);
            assertThat(capturedContentType.get()).isEqualTo("application/json");

            JsonNode body = objectMapper.readTree(capturedBody.get());
            assertThat(body.get("messaging_product").asText()).isEqualTo("whatsapp");
            assertThat(body.get("to").asText()).isEqualTo(RECIPIENT);
            assertThat(body.get("type").asText()).isEqualTo("text");
            assertThat(body.get("text").get("body").asText()).isEqualTo("Cześć, jak mogę pomóc?");
        }

        @Test
        @DisplayName("treść z cudzysłowami/backslashem/unicode -> poprawny JSON (budowany przez ObjectMapper, nie konkatenację)")
        void specialCharactersInContent_producesValidJsonBody() throws Exception {
            AtomicReference<String> capturedBody = new AtomicReference<>();
            httpServer.createContext("/" + PHONE_NUMBER_ID + "/messages", exchange -> {
                capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            httpServer.start();

            when(integrationService.getDecryptedIntegration(INTEGRATION_ID)).thenReturn(decryptedIntegration());

            String tricky = "Klient napisał: \"nie działa\" \\ ścieżka C:\\temp i emoji \uD83D\uDE00 <script>";
            adapter.sendMessage(INTEGRATION_ID, RECIPIENT, tricky, List.of());

            // objectMapper.readTree rzuciłby wyjątek, gdyby body było uszkodzonym JSON-em
            // (np. przez niezescapowany cudzysłów wstrzyknięty ręczną konkatenacją stringów)
            JsonNode body = objectMapper.readTree(capturedBody.get());
            assertThat(body.get("text").get("body").asText()).isEqualTo(tricky);
        }
    }

    // =========================================================================
    // sendMessage() - błędy
    // =========================================================================

    @Nested
    @DisplayName("sendMessage() - błędy")
    class ErrorScenarios {

        @Test
        @DisplayName("attachmentUrls niepuste -> IllegalArgumentException (naprawa code review 2026-08-29: " +
                     "wcześniej cicho ignorowane), integracja NIE jest pobierana ani wysyłana")
        void nonEmptyAttachmentUrls_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> adapter.sendMessage(INTEGRATION_ID, RECIPIENT, "Zobacz załącznik",
                    List.of("https://example.com/image.png")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Załączniki nie są obsługiwane");

            verifyNoInteractions(integrationService);
        }

        @Test
        @DisplayName("content=null -> IllegalArgumentException zamiast wysłania {\"text\":{\"body\":null}} " +
                     "do Graph API, integracja NIE jest pobierana")
        void nullContent_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> adapter.sendMessage(INTEGRATION_ID, RECIPIENT, null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nie może być pusta");

            verifyNoInteractions(integrationService);
        }

        @Test
        @DisplayName("content pusty/blank (same spacje) -> IllegalArgumentException")
        void blankContent_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> adapter.sendMessage(INTEGRATION_ID, RECIPIENT, "   ", List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nie może być pusta");

            verifyNoInteractions(integrationService);
        }

        @Test
        @DisplayName("Graph API zwraca HTTP 400 -> WhatsAppApiException, token NIE pojawia się w komunikacie wyjątku")
        void graphApiReturns400_throwsWhatsAppApiExceptionWithoutLeakingToken() {
            httpServer.createContext("/" + PHONE_NUMBER_ID + "/messages", exchange -> {
                byte[] errorBody = "{\"error\":{\"message\":\"Invalid parameter\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, errorBody.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errorBody);
                }
            });
            httpServer.start();

            when(integrationService.getDecryptedIntegration(INTEGRATION_ID)).thenReturn(decryptedIntegration());

            assertThatThrownBy(() -> adapter.sendMessage(INTEGRATION_ID, RECIPIENT, "Test", List.of()))
                    .isInstanceOf(WhatsAppApiException.class)
                    .hasMessageContaining("400")
                    .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(ACCESS_TOKEN));
        }

        @Test
        @DisplayName("serwer niedostępny (connection refused) -> WhatsAppApiException opakowujący IOException")
        void networkError_throwsWhatsAppApiExceptionWrappingIOException() throws IOException {
            int unusedPort;
            try (ServerSocket probe = new ServerSocket(0)) {
                unusedPort = probe.getLocalPort();
            }
            // probe zamknięty tuż przed użyciem portu - z bardzo wysokim prawdopodobieństwem
            // nic go nie zajmie w trakcie testu, więc kolejne connect() dostanie connection refused
            WhatsAppAdapter unreachableAdapter =
                    new WhatsAppAdapter(integrationService, objectMapper, "http://localhost:" + unusedPort);

            when(integrationService.getDecryptedIntegration(INTEGRATION_ID)).thenReturn(decryptedIntegration());

            assertThatThrownBy(() -> unreachableAdapter.sendMessage(INTEGRATION_ID, RECIPIENT, "Test", List.of()))
                    .isInstanceOf(WhatsAppApiException.class)
                    .hasCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("integracja nie istnieje -> ResponseStatusException 404 z getDecryptedIntegration propagowany bez opakowania w WhatsAppApiException")
        void integrationNotFound_propagatesResponseStatusException() {
            when(integrationService.getDecryptedIntegration(INTEGRATION_ID))
                    .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Integracja nie istnieje"));

            assertThatThrownBy(() -> adapter.sendMessage(INTEGRATION_ID, RECIPIENT, "Test", List.of()))
                    .isInstanceOf(ResponseStatusException.class)
                    .isNotInstanceOf(WhatsAppApiException.class);
        }

        @Test
        @DisplayName("integracja bez skonfigurowanego tokenu -> ResponseStatusException 422 z getDecryptedIntegration propagowany")
        void integrationWithoutToken_propagatesResponseStatusException() {
            when(integrationService.getDecryptedIntegration(INTEGRATION_ID))
                    .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Brak tokenu"));

            assertThatThrownBy(() -> adapter.sendMessage(INTEGRATION_ID, RECIPIENT, "Test", List.of()))
                    .isInstanceOf(ResponseStatusException.class)
                    .isNotInstanceOf(WhatsAppApiException.class);
        }
    }
}
