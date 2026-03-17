package com.contactcenter.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Komponent odpowiedzialny za parsowanie i walidację JWT (RS256).
 *
 * <p>Wspiera dwa sposoby dostarczania klucza publicznego RSA:
 * <ol>
 *   <li>Plik PEM wskazany przez {@code jwt.public-key-location} (classpath lub filesystem)</li>
 *   <li>Zawartość PEM jako string przez {@code jwt.public-key-value} (ENV var w prod)</li>
 * </ol>
 *
 * <p>Waliduje następujące aspekty tokenu:
 * <ul>
 *   <li>Podpis RS256 (publiczny klucz RSA)</li>
 *   <li>Datę wygaśnięcia (claim {@code exp})</li>
 *   <li>Issuer (claim {@code iss})</li>
 *   <li>Obecność wymaganych claims: {@code tenant_id}, {@code user_id}</li>
 * </ul>
 *
 * <p>Pełna implementacja wystawiania tokenów (sign + issuer) jest w BE-003 (AuthService).
 * Ten komponent jest wymagany w BE-002 do ekstrakcji tenant_id w TenantFilter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtParser {

    // Nazwy claims JWT zgodne ze specyfikacją BE-002 / BE-003
    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_USER_ID   = "user_id";
    public static final String CLAIM_ROLE      = "role";

    private final JwtProperties jwtProperties;
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    private PublicKey publicKey;
    private io.jsonwebtoken.JwtParser parser;

    /**
     * Inicjalizuje parser z podanym kluczem publicznym i issuerem.
     *
     * <p><strong>Wyłącznie do użytku w testach jednostkowych</strong> – umożliwia
     * inicjalizację parsera bez pliku PEM (klucz RSA generowany in-memory w teście).
     * W kodzie produkcyjnym zawsze używaj {@link #init()} (poprzez @PostConstruct).
     *
     * @param publicKey klucz publiczny RSA
     * @param issuer    issuer JWT do weryfikacji
     */
    void initWithPublicKey(PublicKey publicKey, String issuer) {
        this.publicKey = publicKey;
        this.parser = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(issuer)
                .build();
    }

    /**
     * Ładuje klucz publiczny RSA przy starcie aplikacji.
     *
     * <p>Priorytet: {@code public-key-value} (ENV) > {@code public-key-location} (plik).
     */
    @PostConstruct
    void init() {
        try {
            String pemContent = resolvePemContent();
            this.publicKey = parsePublicKey(pemContent);
            this.parser = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(jwtProperties.issuer())
                    .build();
            log.info("[JWT] JwtParser zainicjalizowany (RS256, issuer={})", jwtProperties.issuer());
        } catch (Exception e) {
            log.error("[JWT] Błąd inicjalizacji JwtParser – klucz publiczny nie został załadowany: {}", e.getMessage(), e);
            throw new IllegalStateException("Nie można załadować klucza publicznego JWT. " +
                    "Sprawdź konfigurację jwt.public-key-location lub jwt.public-key-value.", e);
        }
    }

    // =========================================================================
    // Publiczne API
    // =========================================================================

    /**
     * Parsuje i waliduje token JWT.
     *
     * @param token surowy token JWT (bez prefiksu "Bearer ")
     * @return ekstrahowane claims
     * @throws JwtValidationException gdy token jest nieprawidłowy, wygasł lub podpis nie pasuje
     */
    public JwtClaims parse(String token) {
        try {
            Claims claims = parser
                    .parseSignedClaims(token)
                    .getPayload();

            UUID tenantId  = extractUuid(claims, CLAIM_TENANT_ID);
            UUID userId    = extractUuid(claims, CLAIM_USER_ID);
            String role    = claims.get(CLAIM_ROLE, String.class);
            // Pobieramy rzeczywisty exp z tokenu – wymagane do ustawienia precyzyjnego TTL blacklisty
            Instant expiry = claims.getExpiration() != null
                    ? claims.getExpiration().toInstant()
                    : null;

            return new JwtClaims(tenantId, userId, role, claims.getSubject(), expiry);

        } catch (ExpiredJwtException e) {
            log.debug("[JWT] Token wygasł: {}", e.getMessage());
            throw new JwtValidationException("Token JWT wygasł", e);
        } catch (SignatureException e) {
            log.warn("[JWT][Security] Nieprawidłowy podpis tokenu JWT");
            throw new JwtValidationException("Nieprawidłowy podpis tokenu JWT", e);
        } catch (JwtException e) {
            log.debug("[JWT] Błąd parsowania tokenu: {}", e.getMessage());
            throw new JwtValidationException("Nieprawidłowy token JWT: " + e.getMessage(), e);
        }
    }

    /**
     * Próbuje sparsować token bez rzucania wyjątku.
     *
     * @param token surowy token JWT
     * @return claims lub null gdy token jest nieprawidłowy
     */
    public JwtClaims parseQuiet(String token) {
        try {
            return parse(token);
        } catch (JwtValidationException e) {
            return null;
        }
    }

    // =========================================================================
    // Prywatne metody pomocnicze
    // =========================================================================

    private String resolvePemContent() throws Exception {
        // Priorytet 1: wartość bezpośrednia (ENV var w prod)
        if (StringUtils.hasText(jwtProperties.publicKeyValue())) {
            log.debug("[JWT] Ładowanie klucza publicznego z właściwości jwt.public-key-value");
            return jwtProperties.publicKeyValue();
        }

        // Priorytet 2: plik PEM (dev/test)
        if (StringUtils.hasText(jwtProperties.publicKeyLocation())) {
            log.debug("[JWT] Ładowanie klucza publicznego z pliku: {}", jwtProperties.publicKeyLocation());
            Resource resource = resourceLoader.getResource(jwtProperties.publicKeyLocation());
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        }

        throw new IllegalStateException(
                "Brak konfiguracji klucza publicznego JWT. " +
                "Ustaw jwt.public-key-location lub jwt.public-key-value."
        );
    }

    /**
     * Parsuje klucz publiczny RSA z formatu PEM (X.509 SubjectPublicKeyInfo).
     *
     * <p>Obsługuje format:
     * <pre>
     * -----BEGIN PUBLIC KEY-----
     * BASE64_CONTENT
     * -----END PUBLIC KEY-----
     * </pre>
     */
    private PublicKey parsePublicKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    private UUID extractUuid(Claims claims, String claimName) {
        String value = claims.get(claimName, String.class);
        if (!StringUtils.hasText(value)) {
            throw new JwtValidationException(
                    "Brak wymaganego claim '" + claimName + "' w tokenie JWT"
            );
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new JwtValidationException(
                    "Nieprawidłowy format UUID w claim '" + claimName + "': " + value
            );
        }
    }

    // =========================================================================
    // Typy wynikowe
    // =========================================================================

    /**
     * Ekstrahowane claims z tokenu JWT.
     *
     * @param tenantId UUID tenanta z claim {@code tenant_id}
     * @param userId   UUID użytkownika z claim {@code user_id}
     * @param role     rola użytkownika z claim {@code role} (może być null)
     * @param subject  claim {@code sub} – zwykle email użytkownika
     * @param expiresAt czas wygaśnięcia tokenu z claim {@code exp} (może być null dla tokenów bez exp)
     */
    public record JwtClaims(
            UUID tenantId,
            UUID userId,
            String role,
            String subject,
            Instant expiresAt
    ) {}

    /**
     * Wyjątek rzucany gdy token JWT jest nieprawidłowy.
     * Używany przez TenantFilter do zwracania HTTP 401.
     */
    public static class JwtValidationException extends RuntimeException {
        public JwtValidationException(String message) {
            super(message);
        }
        public JwtValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
