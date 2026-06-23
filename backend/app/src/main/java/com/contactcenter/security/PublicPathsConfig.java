package com.contactcenter.security;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Centralna lista publicznych ścieżek HTTP (bez wymaganego JWT).
 *
 * <p>Używana przez {@link TenantFilter} do pominięcia weryfikacji tokenu.
 * Lista w {@link SecurityConfig} jest zsynchronizowana z tą listą, ale używa
 * wzorców Spring Security (z {@code /**}), więc jest utrzymywana osobno.
 *
 * <p>Dodając nowy publiczny endpoint: zaktualizuj obie listy.
 */
@Component
public class PublicPathsConfig {

    public static final List<String> PUBLIC_PREFIXES = List.of(
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
            "/api/telephony/hold-music",
            // WebSocket/SockJS endpoint – autentykacja JWT dzieje się w STOMP CONNECT frame
            "/ws",
            // WebSocket plain endpoint (bez SockJS) – używany przez Angular Agent Desktop
            "/ws-native",
            // Endpoint przyjmowania logów z frontendu – publiczny (FE wysyła logi przed zalogowaniem)
            "/api/logs",
            // OAuth callback social media – wywoływany przez serwer OAuth bez JWT
            "/api/oauth/",
            // Webhooks social media (Facebook/Instagram/WhatsApp) – weryfikacja przez HMAC X-Hub-Signature
            "/api/webhooks/",
            // Zasoby UI pluginu (plugin-ui/) – wczytywane przez sandboxowany <iframe> bez JWT
            // agenta; autoryzacja dzieje się na poziomie strony hosta Angular, nie tego endpointu
            // (ARCHITECTURE.md §11.10, EPIC-28 BE-107). Pliki statyczne, niesekretne.
            "/plugin-assets/",
            // PluginUiSdk (plugin-ui-sdk.js) – plik statyczny serwowany z resources/static/,
            // wstrzykiwany przez deweloperów pluginów w ich plugin-ui/index.html (<script src=...>).
            // Wczytywany przez ten sam sandboxowany <iframe> co plugin-assets, bez JWT agenta
            // (EPIC-28, ticket uzupełniający kontrakt FE-099 nieprzewidziany w BE-107).
            "/plugin-ui-sdk.js"
    );
}
