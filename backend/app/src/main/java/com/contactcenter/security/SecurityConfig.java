package com.contactcenter.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Konfiguracja Spring Security 6 – pełna implementacja BE-003.
 *
 * <p>Zmiany w BE-003 względem BE-002:
 * <ul>
 *   <li>Dodano {@link JwtAuthFilter} ustawiający SecurityContext (Authentication)
 *       – rejestrowany PRZED {@link TenantFilter}</li>
 *   <li>Skonfigurowano {@link DaoAuthenticationProvider} z {@link UserDetailsServiceImpl}
 *       i BCryptPasswordEncoder(12)</li>
 *   <li>Dodano {@link AuthenticationManager} bean (do wstrzyknięcia w AuthService)</li>
 *   <li>Dodano CORS konfigurację dla Angular SPA</li>
 *   <li>Dodano endpointy MFA (/api/auth/mfa/**) i logout do listy publicznych</li>
 * </ul>
 *
 * <p>Kolejność filtrów (od pierwszego do ostatniego):
 * <ol>
 *   <li>{@link JwtAuthFilter} – weryfikuje JWT, ustawia SecurityContext</li>
 *   <li>{@link TenantFilter} – ekstrahuje tenant_id, ustawia TenantContext</li>
 *   <li>{@code UsernamePasswordAuthenticationFilter} – pomijany (STATELESS)</li>
 *   <li>{@code ExceptionTranslationFilter} – obsługuje AccessDeniedException / AuthenticationException</li>
 * </ol>
 *
 * <p>Sesje: STATELESS. CSRF: wyłączone (REST API z JWT).
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final TenantFilter tenantFilter;
    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsServiceImpl userDetailsService;

    /** Dozwolone origins CORS – nadpisywalne przez ENV var (prod: domena SPA). */
    @Value("${cors.allowed-origins:http://localhost:4200,http://localhost:3000}")
    private List<String> allowedOrigins;

    // =========================================================================
    // Security Filter Chain
    // =========================================================================

    /**
     * Główny łańcuch filtrów bezpieczeństwa.
     *
     * <p>Kolejność filtrów jest kluczowa:
     * <ol>
     *   <li>JwtAuthFilter musi być przed TenantFilter (SecurityContext przed TenantContext)</li>
     *   <li>Obydwa filtry muszą być przed UsernamePasswordAuthenticationFilter</li>
     * </ol>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF – wyłączone (REST API z JWT; CSRF chroni tylko session-based auth)
            .csrf(AbstractHttpConfigurer::disable)

            // CORS – skonfigurowany dla Angular SPA
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // Sesje: STATELESS – JWT eliminuje potrzebę sesji HTTP
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Reguły autoryzacji
            // Lista publicznych ścieżek zsynchronizowana z PublicPathsConfig.PUBLIC_PREFIXES
            .authorizeHttpRequests(auth -> auth
                // Health check – publiczny (Kubernetes liveness/readiness probe)
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Dokumentacja API – publiczna w DEV
                .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Auth endpoints – publiczne (logowanie, refresh tokenów)
                .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                // Publiczne API (bez JWT) – np. lista tenantów na stronie logowania
                .requestMatchers("/api/public/**").permitAll()
                // Webhooks zewnętrzne (Facebook, Instagram, WhatsApp) – weryfikacja przez HMAC
                .requestMatchers("/webhooks/**").permitAll()
                // Webhooks social media pod /api/webhooks/** – publiczne (brak JWT z platform zewnętrznych)
                // Weryfikacja autentyczności przez HMAC signature (TODO: produkcja)
                .requestMatchers("/api/webhooks/**").permitAll()
                // Webhook VoIP od providera telefonii – publiczny, weryfikacja przez X-Webhook-Secret
                .requestMatchers("/api/telephony/webhook/**").permitAll()
                // OAuth callback – wywoływany przez serwer Facebook/Instagram bez JWT
                // Bezpieczeństwo przez weryfikację parametru 'state' (OAuth CSRF protection)
                .requestMatchers("/api/oauth/*/callback").permitAll()
                // Hold music TwiML – publiczny, wywoływany przez Twilio jako waitUrl w Conference
                .requestMatchers("/api/telephony/hold-music").permitAll()
                // WebSocket endpoint (SockJS) – publiczny (HTTP → WS upgrade); autentykacja przez JWT w STOMP CONNECT
                .requestMatchers("/ws/**").permitAll()
                // WebSocket endpoint (plain WS + STOMP, bez SockJS) – używany przez Angular Agent Desktop
                .requestMatchers("/ws-native/**").permitAll()
                // Endpoint przyjmowania logów z frontendu – publiczny (FE wysyła logi przed zalogowaniem)
                .requestMatchers("/api/logs").permitAll()
                // Actuator (poza health) – wymaga autentykacji
                .requestMatchers("/actuator/**").authenticated()
                // Endpointy ADMIN – tylko rola ADMIN
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Odczyt konfiguracji Twilio per-tenant – ADMIN lub SUPERVISOR (FE-025)
                // Musi być przed ogólną regułą /api/tenants/** (Spring Security dopasowuje po kolei)
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/tenants/*/config").hasAnyRole("ADMIN", "SUPERVISOR")
                .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/api/tenants/*/config").hasAnyRole("ADMIN", "SUPERVISOR")
                // Tenant management – tylko ADMIN (BE-006)
                .requestMatchers("/api/tenants/**").hasRole("ADMIN")
                // Wszystkie pozostałe endpointy – wymagają autentykacji
                .anyRequest().authenticated()
            )

            // JwtAuthFilter: ustawia SecurityContext (Authentication)
            // Musi być PRZED TenantFilter i UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            // TenantFilter: ekstrahuje tenant_id z JWT, ustawia TenantContext
            // Musi być PO JwtAuthFilter (bo TenantFilter też parsuje JWT)
            .addFilterAfter(tenantFilter, JwtAuthFilter.class);

        log.info("[Security] SecurityFilterChain skonfigurowany (BE-003: JwtAuthFilter + TenantFilter aktywne).");
        return http.build();
    }

    // =========================================================================
    // Authentication
    // =========================================================================

    /**
     * BCrypt password encoder z 12 rundami.
     *
     * <p>Cost factor 12: ~500ms na hash (skuteczne dla brute-force).
     * Weryfikacja: {@code new BCryptPasswordEncoder(12)}.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Provider autentykacji: ładuje użytkownika przez UserDetailsService + weryfikuje hasło bcrypt.
     *
     * <p>{@link DaoAuthenticationProvider} wywołuje:
     * <ol>
     *   <li>{@code UserDetailsService.loadUserByUsername(tenantId:email)}</li>
     *   <li>{@code PasswordEncoder.matches(rawPassword, encodedPassword)}</li>
     * </ol>
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        // Nie ujawniamy czy konto istnieje czy hasło jest złe (security best practice)
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    /**
     * AuthenticationManager – wstrzykiwany do AuthService do wywołania autentykacji.
     *
     * <p>Spring Boot 3 wymaga jawnego beana – nie konfiguruje go automatycznie
     * gdy definiujemy własny {@code SecurityFilterChain}.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration
    ) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    // =========================================================================
    // CORS
    // =========================================================================

    /**
     * Konfiguracja CORS dla Angular SPA.
     *
     * <p>Dozwolone origins konfigurowane przez {@code cors.allowed-origins}
     * (domyślnie: localhost:4200 i localhost:3000 dla dev).
     *
     * <p>W produkcji ustaw: {@code CORS_ALLOWED_ORIGINS=https://app.yourdomain.com}
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Dozwolone origins (lista domen SPA)
        config.setAllowedOrigins(allowedOrigins);

        // Dozwolone metody HTTP
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // Dozwolone nagłówki
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "X-Request-Id",
                "X-Tenant-Id"
        ));

        // Eksponowane nagłówki (dostępne przez JavaScript po stronie klienta)
        config.setExposedHeaders(List.of("Authorization"));

        // Poświadczenia (cookies, Authorization header) dozwolone przy CORS
        config.setAllowCredentials(true);

        // Czas cache preflight request (sekundy)
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        log.info("[Security] CORS skonfigurowany. Allowed origins: {}", allowedOrigins);
        return source;
    }
}
