package com.contactcenter.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Właściwości konfiguracyjne JWT ładowane z {@code application.yml}.
 *
 * <p>Obsługuje dwa sposoby dostarczenia kluczy RSA:
 * <ul>
 *   <li><strong>Pliki PEM</strong> – przez {@code jwt.public-key-location} i
 *       {@code jwt.private-key-location} (ścieżka do pliku w classpath lub filesystem)</li>
 *   <li><strong>Zmienne środowiskowe</strong> – przez {@code jwt.public-key-value} i
 *       {@code jwt.private-key-value} (zawartość PEM jako string, preferowane w prod)</li>
 * </ul>
 *
 * <p>Priorytet: wartość ENV ({@code *-value}) > plik ({@code *-location}).
 *
 * <p>Konfiguracja w {@code application.yml}:
 * <pre>
 * jwt:
 *   public-key-location: classpath:keys/public.pem
 *   private-key-location: classpath:keys/private.pem
 *   issuer: contact-center
 *   access-token-ttl-seconds: 900    # 15 min
 * </pre>
 *
 * <p>Konfiguracja produkcyjna (ENV vars):
 * <pre>
 * JWT_PUBLIC_KEY_VALUE=-----BEGIN PUBLIC KEY-----\n...
 * JWT_PRIVATE_KEY_VALUE=-----BEGIN PRIVATE KEY-----\n...
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(

        /**
         * Ścieżka do publicznego klucza RSA (PEM).
         * Używane w środowisku dev. Przykład: {@code classpath:keys/public.pem}
         */
        String publicKeyLocation,

        /**
         * Zawartość publicznego klucza RSA (PEM) jako string.
         * Preferowane w środowisku prod (ENV var: JWT_PUBLIC_KEY_VALUE).
         */
        String publicKeyValue,

        /**
         * Ścieżka do prywatnego klucza RSA (PEM).
         * Używane w środowisku dev. Przykład: {@code classpath:keys/private.pem}
         */
        String privateKeyLocation,

        /**
         * Zawartość prywatnego klucza RSA (PEM) jako string.
         * Preferowane w środowisku prod (ENV var: JWT_PRIVATE_KEY_VALUE).
         */
        String privateKeyValue,

        /**
         * Issuer JWT – weryfikowany w claim {@code iss}.
         * Musi być zgodny z wartością ustawioną przy wystawianiu tokenu.
         */
        @NotBlank
        String issuer,

        /**
         * TTL access tokenu w sekundach (domyślnie 900 = 15 min).
         */
        long accessTokenTtlSeconds,

        /**
         * TTL refresh tokenu w sekundach (domyślnie 604800 = 7 dni).
         */
        long refreshTokenTtlSeconds

) {
    // Wartości domyślne przez compact constructor
    public JwtProperties {
        if (accessTokenTtlSeconds <= 0) {
            accessTokenTtlSeconds = 900L;
        }
        if (refreshTokenTtlSeconds <= 0) {
            refreshTokenTtlSeconds = 604_800L;
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "contact-center";
        }
    }
}
