package com.contactcenter.api;

import com.contactcenter.domain.email.TemplateRenderException;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.exception.RateLimitExceededException;
import com.contactcenter.domain.exception.ResourceLimitExceededException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.service.AuthService;
import com.contactcenter.security.MfaService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Globalny handler wyjątków REST API.
 *
 * <p>Używa RFC 7807 Problem Details (ProblemDetail z Spring 6).
 * Wszystkie błędy zwracane w formacie:
 * <pre>
 * {
 *   "type": "https://contactcenter.io/errors/{error-code}",
 *   "title": "Human-readable error title",
 *   "status": 422,
 *   "detail": "Szczegółowy opis błędu",
 *   "instance": "/api/tenants/123",
 *   "timestamp": "2026-03-13T10:00:00Z",
 *   "errors": { "field": "message" }  // opcjonalnie przy walidacji
 * }
 * </pre>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String ERROR_BASE_URI = "https://contactcenter.io/errors/";

    /**
     * Błędy walidacji Bean Validation (@Valid, @Validated).
     *
     * <p>Zwraca HTTP 422 z mapą field → message.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {

        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        // W przypadku kilku błędów na tym samym polu – łączymy
                        (existing, newVal) -> existing + "; " + newVal
                ));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(ERROR_BASE_URI + "validation-failed"));
        problem.setTitle("Walidacja danych wejściowych nie powiodła się");
        problem.setDetail("Żądanie zawiera nieprawidłowe lub brakujące pola");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("errors", fieldErrors);

        log.debug("[API] Błąd walidacji: {}", fieldErrors);
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * Próba dostępu cross-tenant – HTTP 403 Forbidden.
     *
     * <p>Zgodnie z kryterium akceptacji BE-002: próba dostępu do zasobu innego
     * tenanta zwraca HTTP 403 (nie 404). Zwracamy 403 zamiast 404, żeby nie ujawniać
     * istnienia zasobu w systemie.
     */
    @ExceptionHandler(CrossTenantAccessException.class)
    public ResponseEntity<ProblemDetail> handleCrossTenantAccessException(
            CrossTenantAccessException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create(ERROR_BASE_URI + "cross-tenant-access-denied"));
        problem.setTitle("Brak dostępu do zasobu");
        // Celowo ogólny komunikat – nie ujawniamy czy zasób istnieje i do kogo należy
        problem.setDetail("Nie masz uprawnień do tego zasobu lub zasób nie istnieje");
        problem.setProperty("timestamp", Instant.now());

        // WARNING z detalami – do analizy security (nie ujawniamy w odpowiedzi HTTP)
        log.warn("[API][CrossTenant][Security] Odmowa dostępu cross-tenant: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    /**
     * Brak uprawnień (Spring Security) – HTTP 403 Forbidden.
     *
     * <p>Obsługuje AccessDeniedException z Spring Security (np. @PreAuthorize).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDeniedException(
            AccessDeniedException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create(ERROR_BASE_URI + "access-denied"));
        problem.setTitle("Brak uprawnień do zasobu");
        problem.setDetail("Nie masz uprawnień do wykonania tej operacji");
        problem.setProperty("timestamp", Instant.now());

        log.warn("[API][Security] Próba nieautoryzowanego dostępu: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    /**
     * Nieprawidłowe dane logowania (BadCredentialsException) – HTTP 401.
     *
     * <p>Generyczny komunikat: nie ujawniamy czy problem z hasłem czy kontem.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentialsException(
            BadCredentialsException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(URI.create(ERROR_BASE_URI + "invalid-credentials"));
        problem.setTitle("Nieprawidłowe dane uwierzytelniające");
        problem.setDetail("Nieprawidłowe dane logowania lub brak uprawnień");
        problem.setProperty("timestamp", Instant.now());

        log.debug("[API][Auth] Nieprawidłowe dane logowania: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * Konto nieaktywne (DisabledException) – HTTP 403 Forbidden.
     */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ProblemDetail> handleDisabledException(
            DisabledException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create(ERROR_BASE_URI + "account-disabled"));
        problem.setTitle("Konto nieaktywne");
        problem.setDetail("Konto użytkownika jest nieaktywne. Skontaktuj się z administratorem.");
        problem.setProperty("timestamp", Instant.now());

        log.warn("[API][Auth] Próba logowania na nieaktywne konto");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    /**
     * Nieprawidłowy lub wygasły refresh token – HTTP 401.
     */
    @ExceptionHandler(AuthService.InvalidTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidTokenException(
            AuthService.InvalidTokenException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(URI.create(ERROR_BASE_URI + "invalid-token"));
        problem.setTitle("Nieprawidłowy token");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());

        log.debug("[API][Auth] Nieprawidłowy refresh token: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * Błąd MFA – HTTP 409 Conflict (np. MFA już aktywne).
     */
    @ExceptionHandler(MfaService.MfaException.class)
    public ResponseEntity<ProblemDetail> handleMfaException(
            MfaService.MfaException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create(ERROR_BASE_URI + "mfa-error"));
        problem.setTitle("Błąd MFA");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());

        log.error("[API][MFA] Błąd MFA: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Przekroczono limit prób logowania – HTTP 429 Too Many Requests.
     *
     * <p>Nagłówek {@code Retry-After} informuje klienta ile sekund musi czekać
     * (zgodnie z RFC 6585). Wartość 900 = 15 minut (okno rate limitu).
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceededException(
            RateLimitExceededException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(URI.create(ERROR_BASE_URI + "rate-limit-exceeded"));
        problem.setTitle("Zbyt wiele prób logowania");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("retryAfterSeconds", 900);

        log.warn("[API][RateLimit] Przekroczono limit prób logowania: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "900")
                .body(problem);
    }

    /**
     * Przekroczono limit zasobu tenanta (agents/queues/campaigns) – HTTP 422.
     *
     * <p>Zwraca szczegółowe informacje o typie zasobu, limicie i aktualnej liczbie.
     * Używany przez {@code TenantResourceLimitService} (BE-006).
     */
    @ExceptionHandler(ResourceLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleResourceLimitExceededException(
            ResourceLimitExceededException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(ERROR_BASE_URI + "resource-limit-exceeded"));
        problem.setTitle("Przekroczono limit zasobów");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("error", "RESOURCE_LIMIT_EXCEEDED");
        problem.setProperty("resourceType", ex.getResourceType());
        problem.setProperty("limit", ex.getLimit());
        problem.setProperty("current", ex.getCurrent());

        log.warn("[API][TenantLimit] Przekroczono limit zasobu '{}': limit={}, current={}",
                ex.getResourceType(), ex.getLimit(), ex.getCurrent());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * Zasób nie istnieje – HTTP 404 Not Found.
     *
     * <p>Obsługuje {@link ResourceNotFoundException} dla zasobów domenowych
     * (IVR, audio, itp.) gdzie semantycznie właściwe jest HTTP 404.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFoundException(
            ResourceNotFoundException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create(ERROR_BASE_URI + "resource-not-found"));
        problem.setTitle("Zasób nie istnieje");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());

        log.debug("[API] Zasób nie istnieje: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Zasób nie istnieje – HTTP 422 Unprocessable Entity.
     *
     * <p>Obsługuje {@link EntityNotFoundException} rzucany przez serwisy domenowe
     * gdy encja o podanym ID nie istnieje.
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEntityNotFoundException(
            EntityNotFoundException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(ERROR_BASE_URI + "entity-not-found"));
        problem.setTitle("Zasób nie istnieje");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());

        log.debug("[API] Zasób nie istnieje: {}", ex.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * Nieprawidłowy typ parametru path/query (np. UUID zamiast String) – HTTP 400.
     *
     * <p>Rzucany przez Spring MVC gdy konwersja {@code @PathVariable} lub {@code @RequestParam}
     * na wymagany typ się nie powiedzie – np. gdy String {@code "mock-1"} nie może być
     * skonwertowany na {@code UUID}.
     *
     * <p>Zwraca HTTP 400 zamiast domyślnego 500 – błąd leży po stronie klienta (zły format ID).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {

        String paramName = ex.getName();
        String value = ex.getValue() != null ? ex.getValue().toString() : "null";
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown";

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(ERROR_BASE_URI + "invalid-parameter-type"));
        problem.setTitle("Nieprawidłowy format parametru");
        problem.setDetail(String.format(
                "Parametr '%s' ma nieprawidłową wartość '%s' – oczekiwano formatu %s.",
                paramName, value, requiredType));
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("parameter", paramName);

        log.debug("[API] Nieprawidłowy typ parametru: param={}, value={}, required={}",
                paramName, value, requiredType);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Naruszenie ograniczeń Bean Validation na parametrach metody (@RequestParam, @PathVariable).
     *
     * <p>Rzucany gdy kontroler jest oznaczony {@code @Validated} i parametr @RequestParam
     * lub @PathVariable narusza constraint (np. {@code @Pattern}, {@code @NotNull}).
     * Różni się od {@link MethodArgumentNotValidException} która dotyczy {@code @RequestBody}.
     *
     * <p>Zwraca HTTP 400 Bad Request (błąd danych wejściowych, nie błąd domenowy).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolationException(
            ConstraintViolationException ex, WebRequest request) {

        Map<String, String> fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        cv -> extractParamName(cv),
                        ConstraintViolation::getMessage,
                        (existing, newVal) -> existing + "; " + newVal
                ));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(ERROR_BASE_URI + "validation-failed"));
        problem.setTitle("Nieprawidłowe parametry żądania");
        problem.setDetail("Żądanie zawiera nieprawidłowe parametry");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("errors", fieldErrors);

        log.debug("[API] Błąd walidacji parametrów: {}", fieldErrors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Wyodrębnia nazwę parametru z ConstraintViolation (np. "listContacts.status"). */
    private String extractParamName(ConstraintViolation<?> cv) {
        String path = cv.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    /**
     * Nieprawidłowe argumenty (np. zbyt słabe hasło) – HTTP 422.
     *
     * <p>Obsługuje {@link IllegalArgumentException} rzucany przez walidację siły hasła
     * w {@code AuthService} i inne walidacje biznesowe.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(ERROR_BASE_URI + "invalid-argument"));
        problem.setTitle("Nieprawidłowe dane");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());

        log.debug("[API] Nieprawidłowy argument: {}", ex.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * Konflikt stanu zasobu – HTTP 409 Conflict.
     *
     * <p>Przykład: próba usunięcia agenta z aktywnymi kontaktami (BE-008).
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflictException(
            ConflictException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create(ERROR_BASE_URI + "conflict"));
        problem.setTitle("Konflikt stanu zasobu");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());

        log.warn("[API] Konflikt: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Niedozwolona operacja ze względu na stan domeny (np. MFA już aktywne) – HTTP 409.
     *
     * <p>Używaj {@link InvalidOperationException} zamiast {@link IllegalStateException}
     * dla domenowych naruszeń stanu (patrz klasa wyjątku po szczegóły).
     */
    @ExceptionHandler(InvalidOperationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidOperationException(
            InvalidOperationException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create(ERROR_BASE_URI + "invalid-operation"));
        problem.setTitle("Niedozwolona operacja");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());

        log.warn("[API] Niedozwolona operacja domenowa: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Brakujące zmienne szablonu Mustache – HTTP 422 Unprocessable Entity.
     *
     * <p>Rzucany gdy szablon email zawiera placeholdery, które nie zostały dostarczone
     * w mapie kontekstu renderowania (BE-016).
     */
    @ExceptionHandler(TemplateRenderException.class)
    public ResponseEntity<ProblemDetail> handleTemplateRenderException(
            TemplateRenderException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(ERROR_BASE_URI + "template-missing-variables"));
        problem.setTitle("Brakujące zmienne szablonu");
        problem.setDetail(ex.getMessage());
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("missingVariables", ex.getMissingVariables());

        log.debug("[API] Brakujące zmienne szablonu: {}", ex.getMissingVariables());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * Nieoczekiwane błędy serwera – HTTP 500.
     *
     * <p>Loguje stack trace, zwraca ogólny komunikat (bez ujawniania szczegółów).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(
            Exception ex, WebRequest request) {

        log.error("[API] Nieoczekiwany błąd serwera: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create(ERROR_BASE_URI + "internal-error"));
        problem.setTitle("Wewnętrzny błąd serwera");
        problem.setDetail("Wystąpił nieoczekiwany błąd. Spróbuj ponownie lub skontaktuj się z administratorem.");
        problem.setProperty("timestamp", Instant.now());

        return ResponseEntity.internalServerError().body(problem);
    }
}
