package com.contactcenter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Wspólny zapis odpowiedzi 401 (RFC 9457 ProblemDetail, {@code application/problem+json}) dla komponentów
 * działających poza {@code @RestControllerAdvice}: {@link TenantFilter} oraz {@code authenticationEntryPoint}
 * w {@link SecurityConfig}. Gwarantuje identyczny {@code type}/{@code title} niezależnie od tego, który
 * element łańcucha odrzucił żądanie. Dodaje {@code WWW-Authenticate: Bearer} (RFC 9110 §15.5.2) bez parametru
 * {@code error}, aby nie ujawniać przyczyny odrzucenia.
 */
final class UnauthorizedResponseWriter {

    static final String ERROR_BASE_URI = "https://contactcenter.io/errors/";

    private UnauthorizedResponseWriter() {
    }

    static void write(HttpServletResponse response, ObjectMapper objectMapper, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(URI.create(ERROR_BASE_URI + "unauthorized"));
        problem.setTitle("Brak uwierzytelnienia");
        problem.setDetail(detail);
        // String zamiast Instant – ObjectMapper w filtrze może nie mieć modułu JSR310
        problem.setProperty("timestamp", Instant.now().toString());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
