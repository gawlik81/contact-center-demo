package com.contactcenter.security;

import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.AppUser.UserRole;
import com.contactcenter.domain.model.AppUser.UserStatus;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Testy jednostkowe dla {@link JwtService}.
 *
 * <p>Testy weryfikują:
 * <ul>
 *   <li>Poprawność claims w wystawionych tokenach (userId, tenantId, role, email, mfaVerified)</li>
 *   <li>Czas wygaśnięcia access tokenu</li>
 *   <li>Unikalność generowanych refresh tokenów</li>
 *   <li>Obsługę flagi mfaVerified</li>
 * </ul>
 *
 * <p>Używa par kluczy RSA generowanych in-memory (bez plików PEM).
 */
@DisplayName("JwtService – wystawianie tokenów JWT RS256")
class JwtServiceTest {

    private static final String ISSUER    = "contact-center-test";
    private static final UUID   TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID   USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final long   TTL_SECONDS = 900L;  // 15 min

    private static KeyPair keyPair;
    private static PrivateKey privateKey;
    private static PublicKey  publicKey;

    private JwtService jwtService;
    private JwtParser  jwtParser;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        privateKey = keyPair.getPrivate();
        publicKey  = keyPair.getPublic();
    }

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties(
                null, null, null, null,
                ISSUER, TTL_SECONDS, 604_800L
        );

        jwtService = new JwtService(props);
        jwtService.initWithPrivateKey(privateKey);

        jwtParser = new JwtParser(props);
        jwtParser.initWithPublicKey(publicKey, ISSUER);
    }

    // =========================================================================
    // Wystawianie access tokenów
    // =========================================================================

    @Nested
    @DisplayName("issueAccessToken()")
    class IssueAccessToken {

        @Test
        @DisplayName("Token zawiera wymagane claims")
        void shouldContainRequiredClaims() {
            AppUser user = buildUser();

            String token = jwtService.issueAccessToken(user, "Test Tenant", false);

            assertThat(token).isNotBlank();
            JwtParser.JwtClaims claims = jwtParser.parse(token);
            assertThat(claims.tenantId()).isEqualTo(TENANT_ID);
            assertThat(claims.userId()).isEqualTo(USER_ID);
            assertThat(claims.role()).isEqualTo("ADMIN");
            assertThat(claims.subject()).isEqualTo("admin@test.com");
        }

        @Test
        @DisplayName("mfaVerified=false gdy MFA nie zweryfikowane")
        void shouldSetMfaVerifiedFalse() {
            AppUser user = buildUser();

            String token = jwtService.issueAccessToken(user, "Test Tenant", false);

            // Parsujemy bezpośrednio przez JJWT żeby sprawdzić claim mfaVerified
            Claims rawClaims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(rawClaims.get(JwtService.CLAIM_MFA_VERIFIED, Boolean.class)).isFalse();
        }

        @Test
        @DisplayName("mfaVerified=true gdy MFA zweryfikowane")
        void shouldSetMfaVerifiedTrue() {
            AppUser user = buildUser();

            String token = jwtService.issueAccessToken(user, "Test Tenant", true);

            Claims rawClaims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(rawClaims.get(JwtService.CLAIM_MFA_VERIFIED, Boolean.class)).isTrue();
        }

        @Test
        @DisplayName("Token wygasa po TTL (15 min + margines)")
        void shouldExpireAfterTtl() {
            AppUser user = buildUser();

            Instant before = Instant.now();
            String token = jwtService.issueAccessToken(user, "Test Tenant", false);
            Instant after = Instant.now();

            Claims rawClaims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Instant exp = rawClaims.getExpiration().toInstant();
            // Czas wygaśnięcia powinien być TTL_SECONDS ± 2s od teraz
            assertThat(exp).isAfter(before.plusSeconds(TTL_SECONDS - 2));
            assertThat(exp).isBefore(after.plusSeconds(TTL_SECONDS + 2));
        }

        @Test
        @DisplayName("Token zawiera issuer z konfiguracji")
        void shouldContainCorrectIssuer() {
            AppUser user = buildUser();

            String token = jwtService.issueAccessToken(user, "Test Tenant", false);

            Claims rawClaims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(rawClaims.getIssuer()).isEqualTo(ISSUER);
        }

        @Test
        @DisplayName("Token zawiera email w claims")
        void shouldContainEmail() {
            AppUser user = buildUser();

            String token = jwtService.issueAccessToken(user, "Test Tenant", false);

            Claims rawClaims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(rawClaims.get(JwtService.CLAIM_EMAIL, String.class))
                    .isEqualTo("admin@test.com");
        }
    }

    // =========================================================================
    // Refresh token
    // =========================================================================

    @Nested
    @DisplayName("generateRefreshTokenValue()")
    class GenerateRefreshToken {

        @Test
        @DisplayName("Refresh token nie jest pusty")
        void shouldNotBeBlank() {
            String token = jwtService.generateRefreshTokenValue();
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("Każdy refresh token jest unikalny")
        void shouldBeUnique() {
            String t1 = jwtService.generateRefreshTokenValue();
            String t2 = jwtService.generateRefreshTokenValue();
            assertThat(t1).isNotEqualTo(t2);
        }

        @Test
        @DisplayName("Refresh token ma format UUID")
        void shouldBeUuidFormat() {
            String token = jwtService.generateRefreshTokenValue();
            // UUID format: 8-4-4-4-12 hex chars z myślnikami
            assertThatCode(() -> UUID.fromString(token)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "Iteracja {index}: unikalny token")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        @DisplayName("5 iteracji – każdy token unikalny")
        void shouldBeUniqueAcrossMultipleCalls(int ignored) {
            // Wygeneruj 5 tokenów i sprawdź że wszystkie różne
            var tokens = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(i -> jwtService.generateRefreshTokenValue())
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(tokens).hasSize(5);
        }
    }

    // =========================================================================
    // TTL helpers
    // =========================================================================

    @Nested
    @DisplayName("accessTokenExpiresAt() / refreshTokenExpiresAt()")
    class ExpiresAt {

        @Test
        @DisplayName("Access token wygasa za ~15 min")
        void accessTokenExpiry() {
            Instant before = Instant.now();
            Instant expiry = jwtService.accessTokenExpiresAt();
            Instant after = Instant.now();

            assertThat(expiry).isAfter(before.plusSeconds(TTL_SECONDS - 1));
            assertThat(expiry).isBefore(after.plusSeconds(TTL_SECONDS + 1));
        }

        @Test
        @DisplayName("Refresh token wygasa za ~7 dni")
        void refreshTokenExpiry() {
            long refreshTtl = 604_800L;
            Instant before = Instant.now();
            Instant expiry = jwtService.refreshTokenExpiresAt();
            Instant after = Instant.now();

            assertThat(expiry).isAfter(before.plusSeconds(refreshTtl - 1));
            assertThat(expiry).isBefore(after.plusSeconds(refreshTtl + 1));
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private AppUser buildUser() {
        return AppUser.builder()
                .id(USER_ID)
                .tenantId(TENANT_ID)
                .email("admin@test.com")
                .passwordHash("$2a$12$hash")
                .role(UserRole.ADMIN)
                .active(true)
                .mfaEnabled(false)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
