package com.contactcenter.security;

import com.contactcenter.security.JwtParser.JwtClaims;
import com.contactcenter.security.JwtParser.JwtValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Filtr HTTP ekstrahujący tenant_id i user_id z JWT i ustawiający {@link TenantContext}.
 *
 * <p><strong>Kolejność działania przy każdym żądaniu:</strong>
 * <ol>
 *   <li>Sprawdź czy żądanie wymaga autentykacji (skip dla publicznych endpointów)</li>
 *   <li>Odczytaj nagłówek {@code Authorization: Bearer <token>}</li>
 *   <li>Parsuj JWT (podpis RS256, exp, iss, tenant_id, user_id)</li>
 *   <li>Ustaw {@link TenantContext} (tenantId, userId, userRole)</li>
 *   <li>Ustaw MDC dla logowania ({@code tenantId}, {@code userId}, {@code requestId})</li>
 *   <li>Przekaż żądanie dalej przez łańcuch filtrów</li>
 *   <li>W bloku finally – wyczyść TenantContext i MDC</li>
 * </ol>
 *
 * <p><strong>Publiczne endpointy</strong> (bez JWT) są pomijane przez filtr.
 * TenantContext nie jest ustawiany dla tych żądań.
 *
 * <p><strong>Błędy:</strong>
 * <ul>
 *   <li>Brak tokenu dla chronionego endpointu → HTTP 401 z treścią RFC 7807</li>
 *   <li>Nieprawidłowy/wygasły token → HTTP 401</li>
 *   <li>Brak wymaganych claims → HTTP 401</li>
 * </ul>
 *
 * <p>Filtr jest rejestrowany PRZED {@code UsernamePasswordAuthenticationFilter}
 * w Spring Security filter chain (patrz {@link SecurityConfig}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ERROR_BASE_URI = "https://contactcenter.io/errors/";

    // MDC klucze – synchronizowane z wzorcem logowania w application.yml
    static final String MDC_TENANT_ID  = "tenantId";
    static final String MDC_USER_ID    = "userId";
    static final String MDC_REQUEST_ID = "requestId";

    private final JwtParser jwtParser;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Główna logika filtra
    // =========================================================================

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String requestId = generateRequestId(request);
        String path = request.getRequestURI();

        // MDC dla requestId jest zawsze ustawiane (do logowania żądań)
        MDC.put(MDC_REQUEST_ID, requestId);

        try {
            // Publiczne endpointy – pomijamy weryfikację JWT
            if (isPublicPath(path)) {
                log.trace("[TenantFilter] Publiczny endpoint, pomijam JWT: {}", path);
                filterChain.doFilter(request, response);
                return;
            }

            // Odczytaj nagłówek Authorization
            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
                log.debug("[TenantFilter] Brak nagłówka Authorization dla: {}", path);
                sendUnauthorized(response, "Brak tokenu uwierzytelniającego. " +
                        "Wymagany nagłówek: Authorization: Bearer <token>");
                return;
            }

            // Ekstrahuj i parsuj token
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            JwtClaims claims;
            try {
                claims = jwtParser.parse(token);
            } catch (JwtValidationException e) {
                log.debug("[TenantFilter] Nieprawidłowy token JWT dla {}: {}", path, e.getMessage());
                sendUnauthorized(response, "Nieprawidłowy token: " + e.getMessage());
                return;
            }

            // Ustaw TenantContext
            TenantContext.setTenantId(claims.tenantId());
            TenantContext.setUserId(claims.userId());
            if (StringUtils.hasText(claims.role())) {
                TenantContext.setUserRole(claims.role());
            }

            // Ustaw MDC dla logowania (wzorzec: [tenantId] [userId])
            MDC.put(MDC_TENANT_ID, claims.tenantId().toString());
            MDC.put(MDC_USER_ID, claims.userId().toString());

            log.trace("[TenantFilter] Ustawiono kontekst: tenant={}, user={}, role={}",
                    claims.tenantId(), claims.userId(), claims.role());

            // Przekaż dalej przez łańcuch filtrów
            filterChain.doFilter(request, response);

        } finally {
            // KRYTYCZNE: zawsze czyść TenantContext i MDC po zakończeniu żądania,
            // niezależnie od wyjątku. Bez tego: cross-tenant data leakage przez thread pool!
            TenantContext.clear();
            MDC.remove(MDC_TENANT_ID);
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Sprawdza czy ścieżka jest publiczna (nie wymaga JWT).
     * Lista publicznych ścieżek zarządzana centralnie w {@link PublicPathsConfig}.
     */
    private boolean isPublicPath(String path) {
        return PublicPathsConfig.PUBLIC_PREFIXES.stream()
                .anyMatch(path::startsWith);
    }

    /**
     * Wysyła odpowiedź HTTP 401 w formacie RFC 7807 Problem Details.
     *
     * <p>Nie deleguje do Spring Security, bo TenantFilter działa przed
     * {@code ExceptionTranslationFilter}. Błąd musi być zapisany bezpośrednio
     * do {@code HttpServletResponse}.
     */
    private void sendUnauthorized(HttpServletResponse response, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(URI.create(ERROR_BASE_URI + "unauthorized"));
        problem.setTitle("Brak uwierzytelnienia");
        problem.setDetail(detail);
        // Używamy String zamiast Instant – ObjectMapper w filtrze może nie mieć modułu JSR310
        problem.setProperty("timestamp", Instant.now().toString());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), problem);
    }

    /**
     * Generuje identyfikator żądania dla celów korelacji logów.
     *
     * <p>Próbuje odczytać nagłówek {@code X-Request-Id} (Gateway/Load Balancer).
     * Jeśli brak – generuje skrócone ID z czasu i hash kodu żądania.
     */
    private String generateRequestId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (StringUtils.hasText(requestId)) {
            // Sanitizacja – zapobiegamy log injection
            // UWAGA: używamy sanitized.length() (po usunięciu znaków), NIE requestId.length()
            // Poprzedni kod powodował StringIndexOutOfBoundsException gdy usunięte znaki
            // skracały string poniżej długości requestId.length().
            String sanitized = requestId.replaceAll("[^a-zA-Z0-9\\-_]", "");
            return sanitized.substring(0, Math.min(sanitized.length(), 36));
        }
        // Fallback: czas+hash (wystarczający do korelacji w logu)
        return System.currentTimeMillis() + "-" + Integer.toHexString(request.hashCode());
    }
}
