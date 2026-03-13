package com.contactcenter.api;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link GlobalExceptionHandler}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>HTTP 403 dla {@link CrossTenantAccessException} (kryterium akceptacji BE-002)</li>
 *   <li>HTTP 403 dla {@link AccessDeniedException} (Spring Security)</li>
 *   <li>HTTP 422 dla błędów walidacji Bean Validation</li>
 *   <li>HTTP 500 dla nieobsługiwanych wyjątków</li>
 *   <li>Format odpowiedzi RFC 7807 Problem Details</li>
 * </ul>
 */
@DisplayName("GlobalExceptionHandler – obsługa wyjątków REST API (RFC 7807)")
class GlobalExceptionHandlerTest {

    private static final UUID RESOURCE_ID  = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID TENANT_A     = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B     = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private GlobalExceptionHandler handler;
    private WebRequest webRequest;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contacts/" + RESOURCE_ID);
        webRequest = new ServletWebRequest(request);
    }

    // =========================================================================
    // Testy CrossTenantAccessException – kryterium akceptacji BE-002
    // =========================================================================

    @Nested
    @DisplayName("CrossTenantAccessException – HTTP 403 (kryterium BE-002)")
    class CrossTenantAccessExceptionTests {

        @Test
        @DisplayName("próba dostępu cross-tenant zwraca HTTP 403 (nie 404)")
        void crossTenantAccess_returns403NotFound() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_ID, TENANT_A, TENANT_B
            );

            ResponseEntity<ProblemDetail> response = handler.handleCrossTenantAccessException(ex, webRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("odpowiedź jest w formacie RFC 7807 Problem Details")
        void crossTenantAccess_responseIsProblemDetail() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_ID, TENANT_A, TENANT_B
            );

            ResponseEntity<ProblemDetail> response = handler.handleCrossTenantAccessException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getStatus()).isEqualTo(403);
            assertThat(problem.getType().toString()).contains("cross-tenant-access-denied");
            assertThat(problem.getTitle()).isNotBlank();
            assertThat(problem.getDetail()).isNotBlank();
        }

        @Test
        @DisplayName("odpowiedź nie ujawnia do którego tenanta należy zasób")
        void crossTenantAccess_responseDoesNotRevealResourceTenant() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_ID, TENANT_A, TENANT_B
            );

            ResponseEntity<ProblemDetail> response = handler.handleCrossTenantAccessException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            // Komunikat dla klienta nie może ujawniać tenant_id zasobu
            String detail = problem.getDetail();
            assertThat(detail).doesNotContain(TENANT_B.toString());
            assertThat(detail).doesNotContain(TENANT_A.toString());
        }

        @Test
        @DisplayName("odpowiedź zawiera timestamp")
        void crossTenantAccess_responseContainsTimestamp() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_ID, TENANT_A
            );

            ResponseEntity<ProblemDetail> response = handler.handleCrossTenantAccessException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties()).containsKey("timestamp");
        }

        @Test
        @DisplayName("bezpieczna wersja (bez resourceTenantId) też zwraca HTTP 403")
        void crossTenantAccess_safeVersion_returns403() {
            // Wersja bez ujawniania resourceTenantId
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_ID, TENANT_A
            );

            ResponseEntity<ProblemDetail> response = handler.handleCrossTenantAccessException(ex, webRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // =========================================================================
    // Testy AccessDeniedException (Spring Security)
    // =========================================================================

    @Nested
    @DisplayName("AccessDeniedException – HTTP 403 (Spring Security)")
    class AccessDeniedExceptionTests {

        @Test
        @DisplayName("brak uprawnień zwraca HTTP 403")
        void accessDenied_returns403() {
            AccessDeniedException ex = new AccessDeniedException("Access is denied");

            ResponseEntity<ProblemDetail> response = handler.handleAccessDeniedException(ex, webRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("odpowiedź jest w formacie RFC 7807 Problem Details")
        void accessDenied_responseIsProblemDetail() {
            AccessDeniedException ex = new AccessDeniedException("Access is denied");

            ResponseEntity<ProblemDetail> response = handler.handleAccessDeniedException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getStatus()).isEqualTo(403);
            assertThat(problem.getType().toString()).contains("access-denied");
            assertThat(problem.getTitle()).isNotBlank();
        }
    }

    // =========================================================================
    // Testy MethodArgumentNotValidException (walidacja Bean Validation)
    // =========================================================================

    @Nested
    @DisplayName("MethodArgumentNotValidException – HTTP 422 (walidacja)")
    class ValidationExceptionTests {

        @Test
        @DisplayName("błąd walidacji zwraca HTTP 422")
        void validationError_returns422() {
            MethodArgumentNotValidException ex = buildValidationException("email", "must be a valid email");

            ResponseEntity<ProblemDetail> response = handler.handleValidationException(ex, webRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getStatusCode().value()).isEqualTo(422);
        }

        @Test
        @DisplayName("odpowiedź zawiera mapę błędów walidacji z nazwami pól")
        void validationError_responseContainsFieldErrors() {
            MethodArgumentNotValidException ex = buildValidationException("email", "must be a valid email");

            ResponseEntity<ProblemDetail> response = handler.handleValidationException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties()).containsKey("errors");

            @SuppressWarnings("unchecked")
            var errors = (java.util.Map<String, String>) problem.getProperties().get("errors");
            assertThat(errors).containsKey("email");
            assertThat(errors.get("email")).isEqualTo("must be a valid email");
        }

        @Test
        @DisplayName("odpowiedź jest w formacie RFC 7807 z type validation-failed")
        void validationError_responseIsProblemDetail() {
            MethodArgumentNotValidException ex = buildValidationException("name", "must not be blank");

            ResponseEntity<ProblemDetail> response = handler.handleValidationException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getType().toString()).contains("validation-failed");
            assertThat(problem.getTitle()).isNotBlank();
            assertThat(problem.getDetail()).isNotBlank();
        }

        @Test
        @DisplayName("wiele błędów walidacji na różnych polach – wszystkie w mapie errors")
        void multipleValidationErrors_allFieldsInErrorsMap() {
            MethodArgumentNotValidException ex = buildMultiFieldValidationException();

            ResponseEntity<ProblemDetail> response = handler.handleValidationException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties()).containsKey("errors");

            @SuppressWarnings("unchecked")
            var errors = (java.util.Map<String, String>) problem.getProperties().get("errors");
            assertThat(errors).containsKey("email");
            assertThat(errors).containsKey("name");
        }

        // Helper: buduje MethodArgumentNotValidException z jednym błędem pola
        private MethodArgumentNotValidException buildValidationException(String field, String message) {
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("object", field, message);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            when(ex.getBindingResult()).thenReturn(bindingResult);
            return ex;
        }

        // Helper: buduje MethodArgumentNotValidException z wieloma błędami
        private MethodArgumentNotValidException buildMultiFieldValidationException() {
            BindingResult bindingResult = mock(BindingResult.class);
            List<FieldError> errors = List.of(
                    new FieldError("object", "email", "must be a valid email"),
                    new FieldError("object", "name", "must not be blank")
            );
            when(bindingResult.getFieldErrors()).thenReturn(errors);

            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            when(ex.getBindingResult()).thenReturn(bindingResult);
            return ex;
        }
    }

    // =========================================================================
    // Testy ogólnego handlera Exception
    // =========================================================================

    @Nested
    @DisplayName("Exception (fallback) – HTTP 500")
    class GenericExceptionTests {

        @Test
        @DisplayName("nieobsługiwany wyjątek zwraca HTTP 500")
        void unexpectedException_returns500() {
            RuntimeException ex = new RuntimeException("Nieoczekiwany błąd DB");

            ResponseEntity<ProblemDetail> response = handler.handleGenericException(ex, webRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("odpowiedź HTTP 500 jest w formacie RFC 7807")
        void unexpectedException_responseIsProblemDetail() {
            RuntimeException ex = new RuntimeException("Nieoczekiwany błąd");

            ResponseEntity<ProblemDetail> response = handler.handleGenericException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getStatus()).isEqualTo(500);
            assertThat(problem.getType().toString()).contains("internal-error");
            assertThat(problem.getTitle()).isNotBlank();
        }

        @Test
        @DisplayName("odpowiedź HTTP 500 nie ujawnia szczegółów błędu klientowi")
        void unexpectedException_responseDoesNotRevealInternalDetails() {
            RuntimeException ex = new RuntimeException("SQL error: table tenant not found");

            ResponseEntity<ProblemDetail> response = handler.handleGenericException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            // Komunikat nie może zawierać szczegółów wewnętrznych (np. SQL stacktrace)
            assertThat(problem.getDetail()).doesNotContain("SQL error");
            assertThat(problem.getDetail()).doesNotContain("tenant not found");
        }

        @Test
        @DisplayName("odpowiedź HTTP 500 zawiera timestamp")
        void unexpectedException_responseContainsTimestamp() {
            Exception ex = new Exception("Błąd");

            ResponseEntity<ProblemDetail> response = handler.handleGenericException(ex, webRequest);

            ProblemDetail problem = response.getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.getProperties()).containsKey("timestamp");
        }
    }
}
