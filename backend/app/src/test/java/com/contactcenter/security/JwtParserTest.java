package com.contactcenter.security;

import com.contactcenter.security.JwtParser.JwtClaims;
import com.contactcenter.security.JwtParser.JwtValidationException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testy jednostkowe dla {@link JwtParser}.
 *
 * <p>Używa par kluczy RSA generowanych w pamięci (bez plików PEM).
 * Testuje parsowanie, walidację claims i obsługę błędów.
 */
@DisplayName("JwtParser – parsowanie i walidacja JWT RS256")
class JwtParserTest {

    private static final String ISSUER    = "contact-center-test";
    private static final UUID   TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID   USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String ROLE      = "SUPERVISOR";
    private static final String SUBJECT   = "user@tenant-a.test";

    private static KeyPair keyPair;
    private static PrivateKey privateKey;
    private static PublicKey  publicKey;
    private static JwtParser  jwtParser;

    @BeforeAll
    static void generateKeyPairAndInitParser() throws Exception {
        // Generuj parę kluczy RSA 2048 in-memory (nie wymaga pliku PEM)
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair    = generator.generateKeyPair();
        privateKey = keyPair.getPrivate();
        publicKey  = keyPair.getPublic();

        // Inicjalizuj JwtParser bezpośrednio z kluczem publicznym (z pominięciem @PostConstruct)
        JwtProperties props = new JwtProperties(
                null, null, null, null,
                ISSUER, 900L, 604800L
        );
        jwtParser = new JwtParser(props);
        // Inicjalizuj ręcznie (zamiast @PostConstruct)
        jwtParser.initWithPublicKey(publicKey, ISSUER);
    }

    // =========================================================================
    // Budowanie tokenów testowych
    // =========================================================================

    private String buildValidToken(UUID tenantId, UUID userId, String role) {
        return Jwts.builder()
                .subject(SUBJECT)
                .issuer(ISSUER)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtParser.CLAIM_TENANT_ID, tenantId.toString())
                .claim(JwtParser.CLAIM_USER_ID, userId.toString())
                .claim(JwtParser.CLAIM_ROLE, role)
                .signWith(privateKey)
                .compact();
    }

    private String buildExpiredToken() {
        return Jwts.builder()
                .subject(SUBJECT)
                .issuer(ISSUER)
                .issuedAt(Date.from(Instant.now().minus(30, ChronoUnit.MINUTES)))
                .expiration(Date.from(Instant.now().minus(15, ChronoUnit.MINUTES)))
                .claim(JwtParser.CLAIM_TENANT_ID, TENANT_ID.toString())
                .claim(JwtParser.CLAIM_USER_ID, USER_ID.toString())
                .claim(JwtParser.CLAIM_ROLE, ROLE)
                .signWith(privateKey)
                .compact();
    }

    private String buildTokenWithoutTenantId() {
        return Jwts.builder()
                .subject(SUBJECT)
                .issuer(ISSUER)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtParser.CLAIM_USER_ID, USER_ID.toString())
                .claim(JwtParser.CLAIM_ROLE, ROLE)
                // brak claim tenant_id
                .signWith(privateKey)
                .compact();
    }

    private String buildTokenWithWrongIssuer() {
        return Jwts.builder()
                .subject(SUBJECT)
                .issuer("wrong-issuer")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtParser.CLAIM_TENANT_ID, TENANT_ID.toString())
                .claim(JwtParser.CLAIM_USER_ID, USER_ID.toString())
                .signWith(privateKey)
                .compact();
    }

    // =========================================================================
    // Testy poprawnego parsowania
    // =========================================================================

    @Nested
    @DisplayName("Poprawne tokeny")
    class ValidTokens {

        @Test
        @DisplayName("parse() ekstrahuje tenant_id, user_id i role z poprawnego tokenu")
        void parse_validToken_returnsCorrectClaims() {
            String token = buildValidToken(TENANT_ID, USER_ID, ROLE);

            JwtClaims claims = jwtParser.parse(token);

            assertThat(claims.tenantId()).isEqualTo(TENANT_ID);
            assertThat(claims.userId()).isEqualTo(USER_ID);
            assertThat(claims.role()).isEqualTo(ROLE);
            assertThat(claims.subject()).isEqualTo(SUBJECT);
        }

        @Test
        @DisplayName("parse() obsługuje token z rolą ADMIN")
        void parse_tokenWithAdminRole_returnsAdminRole() {
            String token = buildValidToken(TENANT_ID, USER_ID, "ADMIN");

            JwtClaims claims = jwtParser.parse(token);

            assertThat(claims.role()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("parse() obsługuje różne UUID tenantów")
        void parse_differentTenants_returnsCorrectTenantId() {
            UUID anotherTenant = UUID.fromString("22222222-2222-2222-2222-222222222222");
            String token = buildValidToken(anotherTenant, USER_ID, ROLE);

            JwtClaims claims = jwtParser.parse(token);

            assertThat(claims.tenantId()).isEqualTo(anotherTenant);
        }

        @Test
        @DisplayName("parseQuiet() zwraca claims dla poprawnego tokenu")
        void parseQuiet_validToken_returnsClaims() {
            String token = buildValidToken(TENANT_ID, USER_ID, ROLE);

            JwtClaims claims = jwtParser.parseQuiet(token);

            assertThat(claims).isNotNull();
            assertThat(claims.tenantId()).isEqualTo(TENANT_ID);
        }
    }

    // =========================================================================
    // Testy błędnych tokenów
    // =========================================================================

    @Nested
    @DisplayName("Nieprawidłowe tokeny")
    class InvalidTokens {

        @Test
        @DisplayName("parse() rzuca JwtValidationException dla wygasłego tokenu")
        void parse_expiredToken_throwsJwtValidationException() {
            String expiredToken = buildExpiredToken();

            assertThatThrownBy(() -> jwtParser.parse(expiredToken))
                    .isInstanceOf(JwtValidationException.class)
                    .hasMessageContaining("wygasł");
        }

        @Test
        @DisplayName("parse() rzuca JwtValidationException gdy brak claim tenant_id")
        void parse_tokenWithoutTenantId_throwsJwtValidationException() {
            String token = buildTokenWithoutTenantId();

            assertThatThrownBy(() -> jwtParser.parse(token))
                    .isInstanceOf(JwtValidationException.class)
                    .hasMessageContaining("tenant_id");
        }

        @Test
        @DisplayName("parse() rzuca JwtValidationException dla tokenu z nieprawidłowym issuerem")
        void parse_wrongIssuer_throwsJwtValidationException() {
            String token = buildTokenWithWrongIssuer();

            assertThatThrownBy(() -> jwtParser.parse(token))
                    .isInstanceOf(JwtValidationException.class);
        }

        @Test
        @DisplayName("parse() rzuca JwtValidationException dla losowego stringa")
        void parse_randomString_throwsJwtValidationException() {
            assertThatThrownBy(() -> jwtParser.parse("not-a-jwt-token"))
                    .isInstanceOf(JwtValidationException.class);
        }

        @Test
        @DisplayName("parse() rzuca JwtValidationException dla nieprawidłowego podpisu")
        void parse_wrongSignature_throwsJwtValidationException() throws Exception {
            // Wygeneruj INNĄ parę kluczy i podpisz nimi token
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair otherKeyPair = gen.generateKeyPair();

            String tokenSignedWithDifferentKey = Jwts.builder()
                    .subject(SUBJECT)
                    .issuer(ISSUER)
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                    .claim(JwtParser.CLAIM_TENANT_ID, TENANT_ID.toString())
                    .claim(JwtParser.CLAIM_USER_ID, USER_ID.toString())
                    .signWith(otherKeyPair.getPrivate())
                    .compact();

            assertThatThrownBy(() -> jwtParser.parse(tokenSignedWithDifferentKey))
                    .isInstanceOf(JwtValidationException.class);
        }

        @Test
        @DisplayName("parseQuiet() zwraca null dla nieprawidłowego tokenu")
        void parseQuiet_invalidToken_returnsNull() {
            JwtClaims claims = jwtParser.parseQuiet("invalid-token");

            assertThat(claims).isNull();
        }

        @Test
        @DisplayName("parseQuiet() zwraca null dla wygasłego tokenu")
        void parseQuiet_expiredToken_returnsNull() {
            String expiredToken = buildExpiredToken();

            JwtClaims claims = jwtParser.parseQuiet(expiredToken);

            assertThat(claims).isNull();
        }
    }
}
