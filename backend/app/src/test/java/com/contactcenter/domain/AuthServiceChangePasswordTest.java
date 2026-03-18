package com.contactcenter.domain;

import com.contactcenter.api.auth.ChangePasswordRequest;
import com.contactcenter.api.auth.LoginResponse;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.RefreshTokenRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.domain.service.AuthService;
import com.contactcenter.domain.service.UserService;
import com.contactcenter.security.*;
import com.contactcenter.security.JwtParser.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla metody {@link AuthService#changePassword}.
 *
 * <p>Testowane scenariusze:
 * <ul>
 *   <li>Poprawna zmiana hasła – zwraca nowe tokeny, czyści flagę passwordResetRequired</li>
 *   <li>Błędne aktualne hasło – BadCredentialsException</li>
 *   <li>Nowe hasło bez cyfry – IllegalArgumentException</li>
 *   <li>Nowe hasło bez wielkiej litery – IllegalArgumentException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthService – changePassword()")
class AuthServiceChangePasswordTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;
    @Mock private JwtParser jwtParser;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private MfaService mfaService;
    @Mock private AppUserRepository appUserRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LoginRateLimiter loginRateLimiter;
    @Mock private UserService userService;
    @Mock private TenantRepository tenantRepository;

    private AuthService authService;

    private static final UUID USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        when(tenantRepository.findById(any())).thenReturn(Optional.of(tenant));

        authService = new AuthService(
                authenticationManager, jwtService, jwtParser,
                tokenBlacklistService, mfaService,
                appUserRepository, refreshTokenRepository,
                passwordEncoder, loginRateLimiter, userService, tenantRepository
        );
    }

    // =========================================================================
    // Pomocnicze metody budujące fixture
    // =========================================================================

    private AppUser buildUser(String passwordHash) {
        return AppUser.builder()
                .id(USER_ID)
                .tenantId(TENANT_ID)
                .email("agent@tenant.com")
                .passwordHash(passwordHash)
                .role(AppUser.UserRole.AGENT)
                .active(true)
                .mfaEnabled(false)
                .passwordResetRequired(true)
                .status(AppUser.UserStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("Pomyślna zmiana hasła")
    class SuccessfulPasswordChange {

        @Test
        @DisplayName("zwraca nową pare tokenów po poprawnej zmianie hasła")
        void changePassword_success_returnsNewTokens() {
            // given
            String oldHash = "$2a$12$oldHashValue";
            String newHash = "$2a$12$newHashValue";
            AppUser user = buildUser(oldHash);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1", oldHash)).thenReturn(true);
            when(passwordEncoder.encode("NewPass2")).thenReturn(newHash);
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(2);
            when(jwtService.issueAccessToken(any(AppUser.class), anyString(), anyBoolean()))
                    .thenReturn("new.access.token");
            when(jwtService.generateRefreshTokenValue()).thenReturn("new-refresh-token-uuid");
            when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);
            when(jwtParser.parseQuiet(anyString())).thenReturn(mock(JwtClaims.class));

            ChangePasswordRequest request = new ChangePasswordRequest("OldPass1", "NewPass2");

            // when
            LoginResponse response = authService.changePassword(USER_ID, TENANT_ID, request, "old.jwt.token");

            // then
            assertThat(response).isNotNull();
            assertThat(response.accessToken()).isEqualTo("new.access.token");
            assertThat(response.refreshToken()).isEqualTo("new-refresh-token-uuid");
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.mfaRequired()).isNull();
            assertThat(response.passwordResetRequired()).isNull();

            // weryfikuj że hasło zostało zapisane
            verify(appUserRepository).updatePasswordAndClearReset(USER_ID, newHash);

            // weryfikuj że stare sesje zostały unieważnione
            verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
        }

        @Test
        @DisplayName("inkrementuje nowy refresh token w repozytorium")
        void changePassword_success_savesNewRefreshToken() {
            // given
            AppUser user = buildUser("$2a$12$oldHash");
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$newHash");
            when(refreshTokenRepository.revokeAllByUserId(any())).thenReturn(0);
            when(jwtService.issueAccessToken(any(), anyString(), anyBoolean())).thenReturn("token");
            when(jwtService.generateRefreshTokenValue()).thenReturn("refresh-uuid");
            when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);
            when(jwtService.refreshTokenExpiresAt()).thenReturn(java.time.Instant.now().plusSeconds(604800));
            when(jwtParser.parseQuiet(anyString())).thenReturn(mock(JwtClaims.class));

            // when
            authService.changePassword(USER_ID, TENANT_ID,
                    new ChangePasswordRequest("OldPass1", "NewPass2"), "old.token");

            // then – nowy refresh token zapisany
            verify(refreshTokenRepository).save(any());
        }
    }

    @Nested
    @DisplayName("Błędne aktualne hasło")
    class WrongCurrentPassword {

        @Test
        @DisplayName("rzuca BadCredentialsException gdy aktualne hasło nieprawidłowe")
        void changePassword_wrongCurrentPassword_throwsBadCredentials() {
            // given
            AppUser user = buildUser("$2a$12$correctHash");
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("WrongPassword1", "$2a$12$correctHash")).thenReturn(false);

            ChangePasswordRequest request = new ChangePasswordRequest("WrongPassword1", "NewPass2");

            // when / then
            assertThatThrownBy(() -> authService.changePassword(USER_ID, TENANT_ID, request, "token"))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Nieprawidłowe aktualne hasło");

            // hasło nie może być zmienione gdy weryfikacja nie przeszła
            verify(appUserRepository, never()).updatePasswordAndClearReset(any(), anyString());
        }
    }

    @Nested
    @DisplayName("Zbyt słabe nowe hasło")
    class WeakNewPassword {

        @Test
        @DisplayName("rzuca IllegalArgumentException gdy nowe hasło nie zawiera cyfry")
        void changePassword_noDigitInNewPassword_throwsIllegalArgument() {
            // given
            AppUser user = buildUser("$2a$12$hash");
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1", "$2a$12$hash")).thenReturn(true);

            // Hasło bez cyfry
            ChangePasswordRequest request = new ChangePasswordRequest("OldPass1", "NoDigitPass");

            // when / then
            assertThatThrownBy(() -> authService.changePassword(USER_ID, TENANT_ID, request, "token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cyfr");
        }

        @Test
        @DisplayName("rzuca IllegalArgumentException gdy nowe hasło nie zawiera wielkiej litery")
        void changePassword_noUppercaseInNewPassword_throwsIllegalArgument() {
            // given
            AppUser user = buildUser("$2a$12$hash");
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1", "$2a$12$hash")).thenReturn(true);

            // Hasło bez wielkiej litery
            ChangePasswordRequest request = new ChangePasswordRequest("OldPass1", "nouppercase1");

            // when / then
            assertThatThrownBy(() -> authService.changePassword(USER_ID, TENANT_ID, request, "token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("wielk");
        }

        @Test
        @DisplayName("rzuca IllegalArgumentException gdy nowe hasło same cyfry")
        void changePassword_onlyDigitsInNewPassword_throwsIllegalArgument() {
            // given
            AppUser user = buildUser("$2a$12$hash");
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1", "$2a$12$hash")).thenReturn(true);

            ChangePasswordRequest request = new ChangePasswordRequest("OldPass1", "12345678");

            // when / then
            assertThatThrownBy(() -> authService.changePassword(USER_ID, TENANT_ID, request, "token"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
