package com.contactcenter.security;

import com.contactcenter.domain.user.AppUser;
import io.jsonwebtoken.Jwts;
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
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Serwis do wystawiania tokenów JWT podpisanych kluczem prywatnym RS256.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Wystawianie access tokenów (15 min TTL, claims: userId, tenantId, role, email, mfaVerified)</li>
 *   <li>Generowanie wartości refresh tokenów (bezpieczny UUID – token sam w sobie jest secret)</li>
 *   <li>Wystawianie access tokenu z flagą {@code mfaVerified=true} po pomyślnym TOTP</li>
 * </ul>
 *
 * <p>Walidacja tokenów jest w {@link JwtParser} (klucz publiczny).
 * Ten komponent używa klucza prywatnego do podpisywania.
 *
 * <p>Klucz prywatny RSA:
 * <ul>
 *   <li>DEV: {@code classpath:keys/private.pem} (PKCS#8 format)</li>
 *   <li>PROD: ENV var {@code JWT_PRIVATE_KEY_VALUE}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtService {

    // Claims JWT – spójne z JwtParser
    public static final String CLAIM_TENANT_ID   = JwtParser.CLAIM_TENANT_ID;
    public static final String CLAIM_TENANT_NAME = "tenant_name";
    public static final String CLAIM_USER_ID     = JwtParser.CLAIM_USER_ID;
    public static final String CLAIM_ROLE        = JwtParser.CLAIM_ROLE;
    public static final String CLAIM_EMAIL       = "email";
    public static final String CLAIM_MFA_VERIFIED = "mfaVerified";

    private final JwtProperties jwtProperties;
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    private PrivateKey privateKey;

    /**
     * Ładuje klucz prywatny RSA przy starcie aplikacji.
     * Wyłącznie do użytku produkcyjnego – nie eksponować klucza prywatnego w testach.
     */
    @PostConstruct
    void init() {
        try {
            String pemContent = resolvePemContent();
            this.privateKey = parsePrivateKey(pemContent);
            log.info("[JWT] JwtService zainicjalizowany (RS256 signing, issuer={})", jwtProperties.issuer());
        } catch (Exception e) {
            log.error("[JWT] Błąd inicjalizacji JwtService – klucz prywatny nie załadowany: {}", e.getMessage(), e);
            throw new IllegalStateException("Nie można załadować klucza prywatnego JWT. " +
                    "Sprawdź konfigurację jwt.private-key-location lub jwt.private-key-value.", e);
        }
    }

    // =========================================================================
    // Publiczne API
    // =========================================================================

    /**
     * Wystawia access token JWT dla zalogowanego użytkownika.
     *
     * @param user        encja użytkownika (źródło claims)
     * @param mfaVerified czy użytkownik pomyślnie przeszedł weryfikację MFA w tej sesji
     * @return podpisany access token JWT (RS256)
     */
    public String issueAccessToken(AppUser user, String tenantName, boolean mfaVerified) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(jwtProperties.accessTokenTtlSeconds());

        String token = Jwts.builder()
                .subject(user.getEmail())
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_TENANT_ID,   user.getTenantId().toString())
                .claim(CLAIM_TENANT_NAME, tenantName)
                .claim(CLAIM_USER_ID,     user.getId().toString())
                .claim(CLAIM_ROLE,        user.getRole().name())
                .claim(CLAIM_EMAIL,       user.getEmail())
                .claim(CLAIM_MFA_VERIFIED, mfaVerified)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        log.debug("[JWT] Wystawiono access token dla userId={}, tenantId={}, mfaVerified={}",
                user.getId(), user.getTenantId(), mfaVerified);
        return token;
    }

    /**
     * Generuje wartość refresh tokenu – bezpieczny UUID.
     *
     * <p>Token sam w sobie jest secret (nieprzewidywalny 128-bitowy UUID v4).
     * Przechowywany jako-is w tabeli {@code refresh_token}.
     *
     * @return string UUID (bez myślników – 32 znaki hex)
     */
    public String generateRefreshTokenValue() {
        // UUID v4 = 122 bity losowości – wystarczające bezpieczeństwo
        return UUID.randomUUID().toString();
    }

    /**
     * Oblicza czas wygaśnięcia access tokenu.
     *
     * @return Instant wygaśnięcia (teraz + TTL z konfiguracji)
     */
    public Instant accessTokenExpiresAt() {
        return Instant.now().plusSeconds(jwtProperties.accessTokenTtlSeconds());
    }

    /**
     * Oblicza czas wygaśnięcia refresh tokenu.
     *
     * @return Instant wygaśnięcia (teraz + TTL z konfiguracji)
     */
    public Instant refreshTokenExpiresAt() {
        return Instant.now().plusSeconds(jwtProperties.refreshTokenTtlSeconds());
    }

    /**
     * Zwraca TTL access tokenu w sekundach (z konfiguracji).
     */
    public long getAccessTokenTtlSeconds() {
        return jwtProperties.accessTokenTtlSeconds();
    }

    // =========================================================================
    // Tylko do użytku w testach jednostkowych
    // =========================================================================

    /**
     * Inicjalizuje serwis bezpośrednio kluczem prywatnym.
     * <strong>Wyłącznie do użytku w testach jednostkowych.</strong>
     */
    void initWithPrivateKey(PrivateKey key) {
        this.privateKey = key;
    }

    // =========================================================================
    // Prywatne metody pomocnicze
    // =========================================================================

    private String resolvePemContent() throws Exception {
        // Priorytet 1: wartość bezpośrednia (ENV var w prod)
        if (StringUtils.hasText(jwtProperties.privateKeyValue())) {
            log.debug("[JWT] Ładowanie klucza prywatnego z właściwości jwt.private-key-value");
            return jwtProperties.privateKeyValue();
        }

        // Priorytet 2: plik PEM (dev/test)
        if (StringUtils.hasText(jwtProperties.privateKeyLocation())) {
            log.debug("[JWT] Ładowanie klucza prywatnego z pliku: {}", jwtProperties.privateKeyLocation());
            Resource resource = resourceLoader.getResource(jwtProperties.privateKeyLocation());
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        }

        throw new IllegalStateException(
                "Brak konfiguracji klucza prywatnego JWT. " +
                "Ustaw jwt.private-key-location lub jwt.private-key-value."
        );
    }

    /**
     * Parsuje klucz prywatny RSA z formatu PKCS#8 PEM.
     *
     * <p>Obsługuje format:
     * <pre>
     * -----BEGIN PRIVATE KEY-----
     * BASE64_CONTENT
     * -----END PRIVATE KEY-----
     * </pre>
     *
     * <p>Generowanie klucza: {@code openssl genrsa -out private.pem 2048}
     * Konwersja do PKCS#8: {@code openssl pkcs8 -topk8 -nocrypt -in private.pem -out private_pkcs8.pem}
     */
    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                // Stary format RSA (od openssl genrsa bez konwersji)
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }
}
