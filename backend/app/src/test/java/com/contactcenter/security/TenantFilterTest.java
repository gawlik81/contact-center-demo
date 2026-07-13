package com.contactcenter.security;

import com.contactcenter.security.JwtParser.JwtClaims;
import com.contactcenter.security.JwtParser.JwtValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link TenantFilter}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Poprawne ustawienie TenantContext dla prawidłowego tokenu JWT</li>
 *   <li>HTTP 401 dla żądań bez tokenu</li>
 *   <li>HTTP 401 dla żądań z nieprawidłowym tokenem</li>
 *   <li>Pominięcie weryfikacji dla publicznych endpointów</li>
 *   <li>Czyszczenie TenantContext po zakończeniu żądania (finally block)</li>
 * </ul>
 */
@DisplayName("TenantFilter – JWT extraction and TenantContext setup")
@ExtendWith(MockitoExtension.class)
class TenantFilterTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String ROLE    = "AGENT";
    private static final String VALID_TOKEN = "valid.jwt.token";

    @Mock
    private JwtParser jwtParser;

    @Mock
    private FilterChain filterChain;

    private TenantFilter tenantFilter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tenantFilter = new TenantFilter(jwtParser, objectMapper);
    }

    @AfterEach
    void cleanup() {
        // Zawsze czyść TenantContext po teście
        TenantContext.clear();
    }

    // =========================================================================
    // Testy publicznych endpointów
    // =========================================================================

    @Nested
    @DisplayName("Publiczne endpointy – skip JWT verification")
    class PublicEndpoints {

        @Test
        @DisplayName("actuator/health jest pominięty – brak JWT, filterChain.doFilter() wywołany")
        void actuatorHealth_noJwtRequired_chainContinues() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(jwtParser);
            assertThat(response.getStatus()).isEqualTo(200); // nie zmieniony przez filtr
        }

        @Test
        @DisplayName("/api/auth/login jest pominięty – brak JWT")
        void authLogin_noJwtRequired_chainContinues() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(jwtParser);
        }

        @Test
        @DisplayName("/api/auth/refresh jest pominięty – brak JWT")
        void authRefresh_noJwtRequired_chainContinues() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(jwtParser);
        }

        @Test
        @DisplayName("/api-docs jest pominięty – brak JWT")
        void apiDocs_noJwtRequired_chainContinues() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api-docs");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(jwtParser);
        }

        @Test
        @DisplayName("/webhooks/facebook jest pominięty – weryfikacja przez HMAC")
        void webhooks_noJwtRequired_chainContinues() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/facebook");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(jwtParser);
        }
    }

    // =========================================================================
    // Testy chronionych endpointów z poprawnym JWT
    // =========================================================================

    @Nested
    @DisplayName("Chronione endpointy z poprawnym JWT")
    class ProtectedEndpointsWithValidJwt {

        @Test
        @DisplayName("poprawny JWT ustawia TenantContext i kontynuuje łańcuch filtrów")
        void validJwt_setsTenantContextAndContinues() throws Exception {
            JwtClaims claims = new JwtClaims(TENANT_ID, USER_ID, ROLE, "user@test.com", null);
            when(jwtParser.parse(VALID_TOKEN)).thenReturn(claims);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();

            // Sprawdź TenantContext PODCZAS wywołania filterChain
            doAnswer(invocation -> {
                assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_ID);
                assertThat(TenantContext.getUserId()).isEqualTo(USER_ID);
                assertThat(TenantContext.getUserRole()).isEqualTo(ROLE);
                return null;
            }).when(filterChain).doFilter(request, response);

            tenantFilter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verify(jwtParser).parse(VALID_TOKEN);
        }

        @Test
        @DisplayName("po zakończeniu żądania TenantContext jest wyczyszczony")
        void afterRequest_tenantContextIsCleared() throws Exception {
            JwtClaims claims = new JwtClaims(TENANT_ID, USER_ID, ROLE, "user@test.com", null);
            when(jwtParser.parse(VALID_TOKEN)).thenReturn(claims);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            // Po zakończeniu filtra TenantContext MUSI być pusty
            assertThat(TenantContext.getTenantIdOrNull()).isNull();
            assertThat(TenantContext.getUserIdOrNull()).isNull();
        }

        @Test
        @DisplayName("TenantContext jest czyszczony nawet gdy filterChain rzuca wyjątek")
        void whenFilterChainThrows_tenantContextStillCleared() throws Exception {
            JwtClaims claims = new JwtClaims(TENANT_ID, USER_ID, ROLE, "user@test.com", null);
            when(jwtParser.parse(VALID_TOKEN)).thenReturn(claims);
            doThrow(new RuntimeException("Błąd w łańcuchu filtrów"))
                    .when(filterChain).doFilter(any(), any());

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();

            try {
                tenantFilter.doFilter(request, response, filterChain);
            } catch (RuntimeException e) {
                // Oczekiwany wyjątek
            }

            // TenantContext MUSI być wyczyszczony (blok finally)
            assertThat(TenantContext.getTenantIdOrNull()).isNull();
        }
    }

    // =========================================================================
    // Testy dla SUPER_ADMIN (refaktor ról) – tenant_id nieobecny w tokenie
    // =========================================================================

    @Nested
    @DisplayName("SUPER_ADMIN – token bez tenant_id (refaktor ról)")
    class SuperAdminWithoutTenantId {

        @Test
        @DisplayName("token SUPER_ADMIN bez tenant_id kontynuuje łańcuch filtrów bez ustawienia TenantContext.tenantId")
        void superAdminToken_withoutTenantId_continuesWithoutTenantContext() throws Exception {
            JwtClaims claims = new JwtClaims(null, USER_ID, "SUPER_ADMIN", "superadmin@test.com", null);
            when(jwtParser.parse(VALID_TOKEN)).thenReturn(claims);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();

            doAnswer(invocation -> {
                assertThat(TenantContext.getTenantIdOrNull()).isNull();
                assertThat(TenantContext.getUserId()).isEqualTo(USER_ID);
                assertThat(TenantContext.getUserRole()).isEqualTo("SUPER_ADMIN");
                return null;
            }).when(filterChain).doFilter(request, response);

            tenantFilter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("token bez tenant_id dla roli innej niż SUPER_ADMIN zwraca HTTP 401 (defensywny check)")
        void nonSuperAdminToken_withoutTenantId_returns401() throws Exception {
            JwtClaims claims = new JwtClaims(null, USER_ID, "ADMIN", "admin@test.com", null);
            when(jwtParser.parse(VALID_TOKEN)).thenReturn(claims);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verifyNoInteractions(filterChain);
            assertThat(TenantContext.getTenantIdOrNull()).isNull();
        }
    }

    // =========================================================================
    // Testy dla żądań bez JWT
    // =========================================================================

    @Nested
    @DisplayName("Chronione endpointy bez JWT – HTTP 401")
    class ProtectedEndpointsWithoutJwt {

        @Test
        @DisplayName("brak nagłówka Authorization zwraca HTTP 401")
        void noAuthHeader_returns401() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verifyNoInteractions(filterChain);
            verifyNoInteractions(jwtParser);
        }

        @Test
        @DisplayName("nagłówek Authorization bez prefiksu 'Bearer' zwraca HTTP 401")
        void authHeaderWithoutBearerPrefix_returns401() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            request.addHeader("Authorization", "Basic dXNlcjpwYXNz"); // Basic auth
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("pusty nagłówek Authorization zwraca HTTP 401")
        void emptyAuthHeader_returns401() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            request.addHeader("Authorization", "");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("odpowiedź 401 jest w formacie JSON (RFC 7807 Problem Details)")
        void unauthorized_responseIsJson() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).contains("application/problem+json");
            String body = response.getContentAsString();
            assertThat(body).contains("unauthorized");
        }
    }

    // =========================================================================
    // Testy dla nieprawidłowych tokenów JWT
    // =========================================================================

    @Nested
    @DisplayName("Chronione endpointy z nieprawidłowym JWT – HTTP 401")
    class ProtectedEndpointsWithInvalidJwt {

        @Test
        @DisplayName("wygasły token zwraca HTTP 401")
        void expiredToken_returns401() throws Exception {
            when(jwtParser.parse(anyString()))
                    .thenThrow(new JwtValidationException("Token JWT wygasł"));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            request.addHeader("Authorization", "Bearer expired.token.here");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("token z nieprawidłowym podpisem zwraca HTTP 401")
        void tokenWithWrongSignature_returns401() throws Exception {
            when(jwtParser.parse(anyString()))
                    .thenThrow(new JwtValidationException("Nieprawidłowy podpis tokenu JWT"));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            request.addHeader("Authorization", "Bearer tampered.token.here");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("po HTTP 401 TenantContext nie jest ustawiony")
        void afterUnauthorized_tenantContextIsNotSet() throws Exception {
            when(jwtParser.parse(anyString()))
                    .thenThrow(new JwtValidationException("Token JWT wygasł"));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts");
            request.addHeader("Authorization", "Bearer expired.token");
            MockHttpServletResponse response = new MockHttpServletResponse();

            tenantFilter.doFilter(request, response, filterChain);

            assertThat(TenantContext.getTenantIdOrNull()).isNull();
        }
    }
}
