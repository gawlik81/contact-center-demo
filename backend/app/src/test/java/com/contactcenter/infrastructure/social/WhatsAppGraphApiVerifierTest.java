package com.contactcenter.infrastructure.social;

import com.contactcenter.domain.exception.WhatsAppApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testy jednostkowe {@link WhatsAppGraphApiVerifier#verifyPhoneNumberAccess} – pre-flight
 * weryfikacja (token, phoneNumberId) względem Meta Graph API przy podłączaniu integracji
 * (naprawa code review 2026-08-29).
 *
 * <p>Ten sam wzorzec testowy co {@code WhatsAppAdapterTest}: lokalny
 * {@code com.sun.net.httpserver.HttpServer} zamiast mockowania {@link java.net.http.HttpClient}.
 */
@DisplayName("WhatsAppGraphApiVerifier – verifyPhoneNumberAccess()")
class WhatsAppGraphApiVerifierTest {

    private static final String PHONE_NUMBER_ID = "1234567890";
    private static final String ACCESS_TOKEN = "EAAB-super-secret-permanent-token";

    private com.sun.net.httpserver.HttpServer httpServer;
    private WhatsAppGraphApiVerifier verifier;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = httpServer.getAddress().getPort();
        verifier = new WhatsAppGraphApiVerifier("http://localhost:" + port);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    @DisplayName("Graph API zwraca 200 -> weryfikacja przechodzi bez wyjątku, GET z Authorization: Bearer <token>")
    void graphApiReturns200_doesNotThrow() throws IOException {
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedAuthHeader = new AtomicReference<>();

        httpServer.createContext("/" + PHONE_NUMBER_ID, exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedPath.set(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
            capturedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));

            byte[] responseBytes = "{\"verified_name\":\"Supermarket Support\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        httpServer.start();

        assertThatCode(() -> verifier.verifyPhoneNumberAccess(PHONE_NUMBER_ID, ACCESS_TOKEN))
                .doesNotThrowAnyException();

        assertThat(capturedMethod.get()).isEqualTo("GET");
        assertThat(capturedPath.get()).isEqualTo("/" + PHONE_NUMBER_ID + "?fields=verified_name");
        assertThat(capturedAuthHeader.get()).isEqualTo("Bearer " + ACCESS_TOKEN);
    }

    @Test
    @DisplayName("Graph API zwraca 401 -> IllegalArgumentException z czytelnym komunikatem PL, " +
                 "token NIE pojawia się w komunikacie wyjątku")
    void graphApiReturns401_throwsIllegalArgumentExceptionWithoutLeakingToken() {
        httpServer.createContext("/" + PHONE_NUMBER_ID, exchange -> {
            byte[] errorBody = "{\"error\":{\"message\":\"Invalid OAuth access token\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, errorBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(errorBody);
            }
        });
        httpServer.start();

        assertThatThrownBy(() -> verifier.verifyPhoneNumberAccess(PHONE_NUMBER_ID, ACCESS_TOKEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nieprawidłowy token dostępu lub identyfikator numeru telefonu")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(ACCESS_TOKEN));
    }

    @Test
    @DisplayName("Graph API zwraca 404 (nieprawidłowy phoneNumberId) -> IllegalArgumentException")
    void graphApiReturns404_throwsIllegalArgumentException() {
        httpServer.createContext("/" + PHONE_NUMBER_ID, exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        httpServer.start();

        assertThatThrownBy(() -> verifier.verifyPhoneNumberAccess(PHONE_NUMBER_ID, ACCESS_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("serwer niedostępny (connection refused) -> WhatsAppApiException opakowujący IOException")
    void networkError_throwsWhatsAppApiExceptionWrappingIOException() throws IOException {
        int unusedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unusedPort = probe.getLocalPort();
        }
        WhatsAppGraphApiVerifier unreachableVerifier =
                new WhatsAppGraphApiVerifier("http://localhost:" + unusedPort);

        assertThatThrownBy(() -> unreachableVerifier.verifyPhoneNumberAccess(PHONE_NUMBER_ID, ACCESS_TOKEN))
                .isInstanceOf(WhatsAppApiException.class)
                .hasCauseInstanceOf(IOException.class);
    }
}
