package com.contactcenter.api.social;

import com.contactcenter.api.social.dto.SocialIntegrationDto;
import com.contactcenter.api.social.dto.WhatsAppConnectRequest;
import com.contactcenter.domain.social.SocialIntegrationService;
import com.contactcenter.domain.social.SocialPlatform;
import com.contactcenter.infrastructure.social.WhatsAppGraphApiVerifier;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link SocialOAuthController#connectWhatsApp(WhatsAppConnectRequest)}
 * (świeżo dodany endpoint {@code POST /api/integrations/WHATSAPP/connect}, brak wcześniejszych
 * testów dla {@code SocialOAuthController}).
 *
 * <p><strong>Wzorzec testów kontrolera w tym projekcie:</strong> wywołanie metod kontrolera
 * bezpośrednio, bez {@code MockMvc}/{@code @WebMvcTest} – ten sam wzorzec co
 * {@code RetentionControllerTest} i {@code TenantTwilioConfigControllerTest}. Projekt świadomie
 * NIE uruchamia łańcucha Spring Security (w tym filtrów {@code JwtAuthFilter}/{@code TenantFilter})
 * w testach kontrolerów pakietu {@code api.*} – zweryfikowano na istniejącym kodzie: nawet
 * istniejące {@code @WebMvcTest} w tym repo jawnie wyłączają filtry ({@code addFilters = false}).
 *
 * <p><strong>Konsekwencja dla wymogu "403 bez roli ADMIN":</strong> {@code @PreAuthorize("hasRole('ADMIN')")}
 * na {@link SocialOAuthController#connectWhatsApp} jest zweryfikowana deklaratywnie (code review,
 * ta sama adnotacja co na pozostałych endpointach tego kontrolera) – NIE osobnym testem MockMvc,
 * zgodnie z udokumentowaną decyzją projektową opisaną w Javadoc {@code RetentionControllerTest}.
 * Nie dodano tu nowej infrastruktury {@code @WebMvcTest} tylko dla tego jednego endpointu, żeby nie
 * rozjeżdżać konwencji testowej reszty pakietu {@code api.social} – jeśli będzie potrzebny osobny
 * test bezpieczeństwa całego łańcucha filtrów, powinien objąć wszystkie kontrolery {@code api.*}
 * naraz (osobne zadanie).
 *
 * <p><strong>Walidacja Bean Validation</strong> ({@code @Valid} na {@link WhatsAppConnectRequest})
 * jest infrastrukturą Spring MVC nieaktywną przy bezpośrednim wywołaniu metody kontrolera – testowana
 * tu bezpośrednio przez {@link Validator}, analogicznie do {@code RetentionControllerTest.BeanValidation}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SocialOAuthController – POST /api/integrations/WHATSAPP/connect")
class SocialOAuthControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Mock
    private SocialIntegrationService integrationService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private WhatsAppGraphApiVerifier whatsAppGraphApiVerifier;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SocialOAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new SocialOAuthController(
                integrationService, stringRedisTemplate, objectMapper, whatsAppGraphApiVerifier);
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SocialIntegrationDto savedDto(String pageId, String displayName) {
        return new SocialIntegrationDto(
                UUID.randomUUID(), SocialPlatform.WHATSAPP, pageId, displayName,
                "ACTIVE", null, null, Instant.now(), Instant.now());
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("poprawny request z businessAccountId -> 200 + DTO; saveIntegration wywołane z expiresAt=null i platformConfig zawierającym businessAccountId")
        void validRequestWithBusinessAccountId_returns200AndSavesWithPlatformConfig() throws Exception {
            WhatsAppConnectRequest request = new WhatsAppConnectRequest(
                    "1234567890", "EAAB-permanent-token", "Supermarket Support", "waba-987");
            SocialIntegrationDto dto = savedDto("1234567890", "Supermarket Support");

            when(integrationService.saveIntegration(
                    eq(SocialPlatform.WHATSAPP), eq("1234567890"), eq("Supermarket Support"),
                    eq("EAAB-permanent-token"), isNull(), any()))
                    .thenReturn(dto);

            ResponseEntity<SocialIntegrationDto> response = controller.connectWhatsApp(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(dto);

            ArgumentCaptor<String> platformConfigCaptor = ArgumentCaptor.forClass(String.class);
            verify(integrationService).saveIntegration(
                    eq(SocialPlatform.WHATSAPP), eq("1234567890"), eq("Supermarket Support"),
                    eq("EAAB-permanent-token"), isNull(), platformConfigCaptor.capture());

            String platformConfig = platformConfigCaptor.getValue();
            assertThat(platformConfig).isNotNull();
            JsonNode node = objectMapper.readTree(platformConfig);
            assertThat(node.get("businessAccountId").asText()).isEqualTo("waba-987");
        }

        @Test
        @DisplayName("request bez businessAccountId (null) -> platformConfig przekazany do serwisu jest null")
        void validRequestWithoutBusinessAccountId_passesNullPlatformConfig() {
            WhatsAppConnectRequest request = new WhatsAppConnectRequest(
                    "1234567890", "EAAB-permanent-token", "Supermarket Support", null);
            SocialIntegrationDto dto = savedDto("1234567890", "Supermarket Support");

            when(integrationService.saveIntegration(
                    eq(SocialPlatform.WHATSAPP), eq("1234567890"), eq("Supermarket Support"),
                    eq("EAAB-permanent-token"), isNull(), isNull()))
                    .thenReturn(dto);

            ResponseEntity<SocialIntegrationDto> response = controller.connectWhatsApp(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(integrationService).saveIntegration(
                    eq(SocialPlatform.WHATSAPP), eq("1234567890"), eq("Supermarket Support"),
                    eq("EAAB-permanent-token"), isNull(), isNull());
        }

        @Test
        @DisplayName("businessAccountId to blank string (spacje) -> traktowany jak brak, platformConfig = null")
        void blankBusinessAccountId_treatedAsAbsent() {
            WhatsAppConnectRequest request = new WhatsAppConnectRequest(
                    "1234567890", "token", "Display Name", "   ");
            SocialIntegrationDto dto = savedDto("1234567890", "Display Name");

            when(integrationService.saveIntegration(any(), any(), any(), any(), isNull(), isNull()))
                    .thenReturn(dto);

            controller.connectWhatsApp(request);

            verify(integrationService).saveIntegration(
                    eq(SocialPlatform.WHATSAPP), eq("1234567890"), eq("Display Name"),
                    eq("token"), isNull(), isNull());
        }

        @Test
        @DisplayName("businessAccountId zawierający cudzysłów -> platformConfig to poprawny JSON (budowany przez ObjectMapper, nie ręczną konkatenację)")
        void businessAccountIdWithQuoteChar_producesValidJson() throws Exception {
            String tricky = "waba-\"weird\"-id";
            WhatsAppConnectRequest request = new WhatsAppConnectRequest("111", "tok", "Name", tricky);
            SocialIntegrationDto dto = savedDto("111", "Name");

            when(integrationService.saveIntegration(any(), any(), any(), any(), isNull(), any()))
                    .thenReturn(dto);

            controller.connectWhatsApp(request);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(integrationService).saveIntegration(any(), any(), any(), any(), isNull(), captor.capture());

            // objectMapper.readTree rzuciłby wyjątek gdyby JSON był uszkodzony przez
            // nieprawidłowo zescapowany cudzysłów - to jest właściwa weryfikacja tego wymogu
            JsonNode node = objectMapper.readTree(captor.getValue());
            assertThat(node.get("businessAccountId").asText()).isEqualTo(tricky);
        }
    }

    // =========================================================================
    // Pre-flight weryfikacja Graph API (naprawa code review 2026-08-29)
    // =========================================================================

    @Nested
    @DisplayName("Pre-flight weryfikacja WhatsAppGraphApiVerifier – wykonywana PRZED saveIntegration()")
    class PreFlightVerification {

        @Test
        @DisplayName("weryfikacja OK -> whatsAppGraphApiVerifier wywołany z (phoneNumberId, accessToken) " +
                     "przed saveIntegration; integracja zapisana normalnie")
        void verificationSucceeds_savesIntegration() {
            WhatsAppConnectRequest request = new WhatsAppConnectRequest(
                    "1234567890", "EAAB-permanent-token", "Supermarket Support", null);
            SocialIntegrationDto dto = savedDto("1234567890", "Supermarket Support");

            when(integrationService.saveIntegration(any(), any(), any(), any(), isNull(), isNull()))
                    .thenReturn(dto);

            ResponseEntity<SocialIntegrationDto> response = controller.connectWhatsApp(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(whatsAppGraphApiVerifier)
                    .verifyPhoneNumberAccess("1234567890", "EAAB-permanent-token");
            verify(integrationService).saveIntegration(
                    eq(SocialPlatform.WHATSAPP), eq("1234567890"), eq("Supermarket Support"),
                    eq("EAAB-permanent-token"), isNull(), isNull());
        }

        @Test
        @DisplayName("weryfikacja nieudana (błędny token/numer) -> IllegalArgumentException propagowany, " +
                     "saveIntegration NIGDY nie jest wywołane – integracja nie jest zapisywana")
        void verificationFails_doesNotSaveIntegration() {
            WhatsAppConnectRequest request = new WhatsAppConnectRequest(
                    "1234567890", "invalid-token", "Supermarket Support", null);

            doThrow(new IllegalArgumentException(
                    "Nieprawidłowy token dostępu lub identyfikator numeru telefonu. " +
                    "Sprawdź dane w Meta Business Suite i spróbuj ponownie."))
                    .when(whatsAppGraphApiVerifier)
                    .verifyPhoneNumberAccess("1234567890", "invalid-token");

            assertThatThrownBy(() -> controller.connectWhatsApp(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Nieprawidłowy token dostępu lub identyfikator numeru telefonu");

            verify(integrationService, never()).saveIntegration(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("weryfikacja rzuca WhatsAppApiException (Graph API niedostępne) -> propagowany, " +
                     "saveIntegration NIGDY nie jest wywołane")
        void verificationNetworkError_doesNotSaveIntegration() {
            WhatsAppConnectRequest request = new WhatsAppConnectRequest(
                    "1234567890", "some-token", "Supermarket Support", null);

            doThrow(new com.contactcenter.domain.exception.WhatsAppApiException(
                    "Błąd komunikacji z WhatsApp Cloud API podczas weryfikacji danych integracji"))
                    .when(whatsAppGraphApiVerifier)
                    .verifyPhoneNumberAccess("1234567890", "some-token");

            assertThatThrownBy(() -> controller.connectWhatsApp(request))
                    .isInstanceOf(com.contactcenter.domain.exception.WhatsAppApiException.class);

            verifyNoInteractions(integrationService);
        }
    }

    // =========================================================================
    // Walidacja @Valid – WhatsAppConnectRequest
    // =========================================================================

    @Nested
    @DisplayName("Walidacja @Valid – WhatsAppConnectRequest (Bean Validation bezpośrednio, bez łańcucha Spring MVC)")
    class BeanValidation {

        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        @Test
        @DisplayName("phoneNumberId puste -> violation")
        void blankPhoneNumberId_isRejected() {
            var request = new WhatsAppConnectRequest("", "token", "Name", null);
            Set<ConstraintViolation<WhatsAppConnectRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("phoneNumberId null -> violation")
        void nullPhoneNumberId_isRejected() {
            var request = new WhatsAppConnectRequest(null, "token", "Name", null);
            assertThat(validator.validate(request)).isNotEmpty();
        }

        @Test
        @DisplayName("accessToken puste -> violation")
        void blankAccessToken_isRejected() {
            var request = new WhatsAppConnectRequest("111", "", "Name", null);
            assertThat(validator.validate(request)).isNotEmpty();
        }

        @Test
        @DisplayName("accessToken null -> violation")
        void nullAccessToken_isRejected() {
            var request = new WhatsAppConnectRequest("111", null, "Name", null);
            assertThat(validator.validate(request)).isNotEmpty();
        }

        @Test
        @DisplayName("displayName puste -> violation")
        void blankDisplayName_isRejected() {
            var request = new WhatsAppConnectRequest("111", "token", "", null);
            assertThat(validator.validate(request)).isNotEmpty();
        }

        @Test
        @DisplayName("displayName null -> violation")
        void nullDisplayName_isRejected() {
            var request = new WhatsAppConnectRequest("111", "token", null, null);
            assertThat(validator.validate(request)).isNotEmpty();
        }

        @Test
        @DisplayName("wszystkie wymagane pola wypełnione, businessAccountId brak -> brak violation (pole opcjonalne)")
        void allRequiredFieldsPresent_businessAccountIdAbsent_isAccepted() {
            var request = new WhatsAppConnectRequest("111", "token", "Name", null);
            assertThat(validator.validate(request)).isEmpty();
        }

        @Test
        @DisplayName("wszystkie pola wypełnione (w tym businessAccountId) -> brak violation")
        void allFieldsPresent_isAccepted() {
            var request = new WhatsAppConnectRequest("111", "token", "Name", "waba-1");
            assertThat(validator.validate(request)).isEmpty();
        }

        // =====================================================================
        // @Size(max = 255) – naprawa (code review 2026-08-29): spójność z limitem kolumn
        // page_id/display_name VARCHAR(255) w social_integration (V010)
        // =====================================================================

        @Test
        @DisplayName("phoneNumberId > 255 znaków -> violation")
        void tooLongPhoneNumberId_isRejected() {
            var request = new WhatsAppConnectRequest("1".repeat(256), "token", "Name", null);
            assertThat(validator.validate(request)).isNotEmpty();
        }

        @Test
        @DisplayName("phoneNumberId dokładnie 255 znaków -> brak violation (wartość graniczna)")
        void exactly255CharsPhoneNumberId_isAccepted() {
            var request = new WhatsAppConnectRequest("1".repeat(255), "token", "Name", null);
            assertThat(validator.validate(request)).isEmpty();
        }

        @Test
        @DisplayName("displayName > 255 znaków -> violation")
        void tooLongDisplayName_isRejected() {
            var request = new WhatsAppConnectRequest("111", "token", "N".repeat(256), null);
            assertThat(validator.validate(request)).isNotEmpty();
        }

        @Test
        @DisplayName("businessAccountId > 255 znaków -> violation")
        void tooLongBusinessAccountId_isRejected() {
            var request = new WhatsAppConnectRequest("111", "token", "Name", "w".repeat(256));
            assertThat(validator.validate(request)).isNotEmpty();
        }

        @Test
        @DisplayName("accessToken > 255 znaków -> brak violation (permanentne tokeny Meta mogą być długie, celowo bez limitu długości)")
        void veryLongAccessToken_isAccepted() {
            var request = new WhatsAppConnectRequest("111", "T".repeat(1000), "Name", null);
            assertThat(validator.validate(request)).isEmpty();
        }
    }
}
