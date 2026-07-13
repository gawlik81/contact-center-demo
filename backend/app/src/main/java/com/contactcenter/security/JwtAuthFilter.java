package com.contactcenter.security;

import com.contactcenter.security.JwtParser.JwtClaims;
import com.contactcenter.security.JwtParser.JwtValidationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

/**
 * Filtr HTTP ustawiający {@link org.springframework.security.core.context.SecurityContext}
 * na podstawie JWT access tokenu.
 *
 * <p>Odpowiedzialności (oddzielone od {@link TenantFilter}):
 * <ul>
 *   <li>Ekstrahuje JWT z nagłówka {@code Authorization: Bearer}</li>
 *   <li>Sprawdza blacklistę tokenów w Redis</li>
 *   <li>Ładuje {@link AppUserDetails} przez {@link UserDetailsServiceImpl}</li>
 *   <li>Ustawia {@link UsernamePasswordAuthenticationToken} w SecurityContext</li>
 * </ul>
 *
 * <p>Kolejność filtrów w filter chain:
 * <ol>
 *   <li>{@link JwtAuthFilter} – ustawia SecurityContext (Authentication)</li>
 *   <li>{@link TenantFilter} – ustawia TenantContext (ThreadLocal)</li>
 *   <li>{@code UsernamePasswordAuthenticationFilter} – pomijany (STATELESS)</li>
 * </ol>
 *
 * <p>Dla publicznych endpointów filtr przepuszcza żądanie bez autentykacji
 * (SecurityContext pozostaje pusty – Spring Security sprawdza to w autoryzacji).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Publiczne ścieżki – te same co w TenantFilter i SecurityConfig.
     * Dla tych ścieżek filtr nie próbuje autentykować (brak wymogu JWT).
     */
    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/actuator/health",
            "/api-docs",
            "/swagger-ui",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/public/",
            "/webhooks/",
            // Webhook VoIP – weryfikacja przez X-Webhook-Secret (nie przez JWT)
            "/api/telephony/webhook",
            // Hold music TwiML – wywoływany przez Twilio jako waitUrl w Conference (bez JWT)
            "/api/telephony/hold-music"
    );

    private final JwtParser jwtParser;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Publiczne endpointy – pomijamy weryfikację JWT
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Jeśli SecurityContext już jest ustawiony (np. inny filtr go ustawił) – pomijamy
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            // Brak tokenu – przepuszczamy żądanie, Spring Security oceni autoryzację
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        try {
            // Krok 1: Parsuj i waliduj token (podpis, exp, iss, claims)
            JwtClaims claims = jwtParser.parse(token);

            // Krok 2: Sprawdź blacklistę Redis (logout/revoke)
            if (tokenBlacklistService.isBlacklisted(token)) {
                log.info("[JwtAuthFilter] Token na blackliście Redis. Odmowa dostępu dla userId={}",
                        claims.userId());
                // Przepuszczamy bez ustawiania SecurityContext – Spring Security zwróci 401
                filterChain.doFilter(request, response);
                return;
            }

            // Krok 3: Załaduj UserDetails z bazy (weryfikacja istnienia i aktywności konta)
            // SUPER_ADMIN (refaktor ról): claims.tenantId() jest null – budujemy klucz GLOBAL:email
            // zamiast tenantId:email, inaczej buildKey() rzuciłby NPE na tenantId.toString().
            String userKey = claims.tenantId() != null
                    ? UserDetailsServiceImpl.buildKey(claims.tenantId(), claims.subject())
                    : UserDetailsServiceImpl.buildGlobalKey(claims.subject());
            UserDetails userDetails = userDetailsService.loadUserByUsername(userKey);

            // Krok 4: Ustaw Authentication w SecurityContext
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null, // credentials = null (token już zwalidowany)
                            userDetails.getAuthorities()
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.trace("[JwtAuthFilter] SecurityContext ustawiony: userId={}, tenantId={}, role={}",
                    claims.userId(), claims.tenantId(), claims.role());

        } catch (JwtValidationException e) {
            // Nieprawidłowy/wygasły token – przepuszczamy bez SecurityContext
            log.debug("[JwtAuthFilter] Walidacja JWT nieudana: {}", e.getMessage());
        } catch (Exception e) {
            // Nieoczekiwany błąd – logujemy i przepuszczamy (nie blokujemy ruchu awaryjnie)
            log.warn("[JwtAuthFilter] Nieoczekiwany błąd podczas przetwarzania JWT: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private boolean isPublicPath(String path) {
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
