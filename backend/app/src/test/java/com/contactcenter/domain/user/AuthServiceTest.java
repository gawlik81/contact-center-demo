package com.contactcenter.domain.user;

import com.contactcenter.api.auth.dto.ChangePasswordRequest;
import com.contactcenter.api.auth.dto.LoginRequest;
import com.contactcenter.api.auth.dto.LoginResponse;
import com.contactcenter.api.auth.dto.LogoutRequest;
import com.contactcenter.api.auth.dto.MfaVerifyRequest;
import com.contactcenter.api.auth.dto.RefreshRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.exception.RateLimitExceededException;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.AppUserDetails;
import com.contactcenter.security.JwtParser;
import com.contactcenter.security.JwtService;
import com.contactcenter.security.LoginRateLimiter;
import com.contactcenter.security.MfaService;
import com.contactcenter.security.TenantContext;
import com.contactcenter.security.TokenBlacklistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link AuthService}.
 *
 * <p>Weryfikuje przepływy: login (z MFA, bez MFA, password reset), refresh token rotation,
 * logout, MFA setup/verify, zmianę hasła, force password reset oraz izolację tenantów.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthService – autentykacja i zarządzanie tokenami")
class AuthServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B    = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID     = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String USER_EMAIL = "agent@test.com";
    private static final String PASSWORD   = "Secret123!";
    private static final String HASH       = "$2a$12$hashedpassword";
    private static final String ACCESS_TOKEN  = "eyJhbGciOiJSUzI1NiJ9.access";
    private static final String REFRESH_TOKEN = "refresh-uuid-value";
    private static final String CLIENT_IP     = "192.168.1.1";

    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService            jwtService;
    @Mock private JwtParser             jwtParser;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private MfaService            mfaService;
    @Mock private AppUserRepository     appUserRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private TenantService tenantService;
    @Mock private PasswordEncoder       passwordEncoder;
    @Mock private LoginRateLimiter      loginRateLimiter;
    @Mock private UserService           userService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                authenticationManager, jwtService, jwtParser, tokenBlacklistService,
                mfaService, appUserRepository, refreshTokenRepository, tenantService,
                passwordEncoder, loginRateLimiter, userService
        );

        // Wspólne defaulty
        when(jwtService.issueAccessToken(any(), anyString(), anyBoolean())).thenReturn(ACCESS_TOKEN);
        when(jwtService.generateRefreshTokenValue()).thenReturn(REFRESH_TOKEN);
        when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);
        when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(buildTenant()));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // login()
    // =========================================================================

    @Nested
    @DisplayName("login() – uwierzytelnianie e-mail + hasło")
    class LoginTests {

        @Test
        @DisplayName("zwraca tokeny bez MFA dla standardowego użytkownika")
        void login_standardUser_returnsTokens() {
            AppUser user = buildUser(false, false, false);
            Authentication auth = mockAuthentication(user);

            when(authenticationManager.authenticate(any())).thenReturn(auth);
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(buildLoginRequest(), CLIENT_IP);

            assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(response.refreshToken()).isEqualTo(REFRESH_TOKEN);
            assertThat(response.mfaRequired()).isNull();
            assertThat(response.passwordResetRequired()).isNull();
            assertThat(response.tokenType()).isEqualTo("Bearer");
        }

        @Test
        @DisplayName("zwraca mfaRequired=true gdy użytkownik ma włączone MFA")
        void login_userWithMfa_returnsMfaRequired() {
            AppUser user = buildUser(true, false, false);
            Authentication auth = mockAuthentication(user);

            when(authenticationManager.authenticate(any())).thenReturn(auth);
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(buildLoginRequest(), CLIENT_IP);

            assertThat(response.mfaRequired()).isTrue();
            assertThat(response.passwordResetRequired()).isNull();
        }

        @Test
        @DisplayName("zwraca passwordResetRequired=true gdy wymagana zmiana hasła (priorytet przed MFA)")
        void login_passwordResetRequired_returnsFlagBeforeMfa() {
            // passwordResetRequired ma priorytet nad mfaRequired
            AppUser user = buildUser(true, true, false); // MFA=true, passwordReset=true
            Authentication auth = mockAuthentication(user);

            when(authenticationManager.authenticate(any())).thenReturn(auth);
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            LoginResponse response = authService.login(buildLoginRequest(), CLIENT_IP);

            assertThat(response.passwordResetRequired()).isTrue();
            assertThat(response.mfaRequired()).isNull();
        }

        @Test
        @DisplayName("rzuca BadCredentialsException gdy hasło nieprawidłowe")
        void login_wrongPassword_throwsBadCredentials() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Bad credentials"));

            assertThatThrownBy(() -> authService.login(buildLoginRequest(), CLIENT_IP))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Nieprawidłowe dane logowania");
        }

        @Test
        @DisplayName("rzuca BadCredentialsException gdy tenantId jest nieprawidłowym UUID")
        void login_invalidTenantIdFormat_throwsBadCredentials() {
            LoginRequest badRequest = new LoginRequest("not-a-uuid", USER_EMAIL, PASSWORD);

            assertThatThrownBy(() -> authService.login(badRequest, CLIENT_IP))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Nieprawidłowe dane logowania");

            verifyNoInteractions(authenticationManager);
        }

        @Test
        @DisplayName("rzuca RateLimitExceededException gdy przekroczono limit prób logowania")
        void login_rateLimitExceeded_throwsRateLimitException() {
            doThrow(new RateLimitExceededException("Zbyt wiele prób logowania"))
                    .when(loginRateLimiter).checkAndIncrement(CLIENT_IP);

            assertThatThrownBy(() -> authService.login(buildLoginRequest(), CLIENT_IP))
                    .isInstanceOf(RateLimitExceededException.class);

            verifyNoInteractions(authenticationManager);
        }

        @Test
        @DisplayName("resetuje licznik rate limit po udanym logowaniu")
        void login_success_resetsRateLimitCounter() {
            AppUser user = buildUser(false, false, false);
            when(authenticationManager.authenticate(any())).thenReturn(mockAuthentication(user));
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            authService.login(buildLoginRequest(), CLIENT_IP);

            verify(loginRateLimiter).reset(CLIENT_IP);
        }

        @Test
        @DisplayName("tworzy refresh token w bazie danych")
        void login_success_savesRefreshToken() {
            AppUser user = buildUser(false, false, false);
            when(authenticationManager.authenticate(any())).thenReturn(mockAuthentication(user));
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            authService.login(buildLoginRequest(), CLIENT_IP);

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
            assertThat(captor.getValue().getToken()).isEqualTo(REFRESH_TOKEN);
        }

        // =====================================================================
        // SUPER_ADMIN – logowanie globalne bez tenantId (refaktor ról)
        // =====================================================================

        @Test
        @DisplayName("loguje SUPER_ADMIN z pustym tenantId – buduje klucz GLOBAL:email zamiast tenantId:email")
        void login_superAdminWithBlankTenantId_buildsGlobalUsernameKey() {
            AppUser superAdmin = buildSuperAdminUser();
            Authentication auth = mockAuthentication(superAdmin);

            when(authenticationManager.authenticate(any())).thenReturn(auth);
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(superAdmin));
            // Nadpisuje domyślny stub z @BeforeEach: anyString() NIE dopasowuje null –
            // SUPER_ADMIN nie ma tenantName (getTenantName() zwraca null).
            when(jwtService.issueAccessToken(eq(superAdmin), isNull(), anyBoolean())).thenReturn(ACCESS_TOKEN);

            LoginResponse response = authService.login(
                    new LoginRequest("", USER_EMAIL, PASSWORD), CLIENT_IP);

            assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);

            ArgumentCaptor<Authentication> authCaptor = ArgumentCaptor.forClass(Authentication.class);
            verify(authenticationManager).authenticate(authCaptor.capture());
            assertThat(authCaptor.getValue().getPrincipal())
                    .isEqualTo(com.contactcenter.security.UserDetailsServiceImpl.buildGlobalKey(USER_EMAIL));
        }

        @Test
        @DisplayName("loguje SUPER_ADMIN z null tenantId – buduje klucz GLOBAL:email")
        void login_superAdminWithNullTenantId_buildsGlobalUsernameKey() {
            AppUser superAdmin = buildSuperAdminUser();
            Authentication auth = mockAuthentication(superAdmin);

            when(authenticationManager.authenticate(any())).thenReturn(auth);
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(superAdmin));
            when(jwtService.issueAccessToken(eq(superAdmin), isNull(), anyBoolean())).thenReturn(ACCESS_TOKEN);

            LoginResponse response = authService.login(
                    new LoginRequest(null, USER_EMAIL, PASSWORD), CLIENT_IP);

            assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);

            ArgumentCaptor<Authentication> authCaptor = ArgumentCaptor.forClass(Authentication.class);
            verify(authenticationManager).authenticate(authCaptor.capture());
            assertThat(authCaptor.getValue().getPrincipal())
                    .isEqualTo(com.contactcenter.security.UserDetailsServiceImpl.buildGlobalKey(USER_EMAIL));
        }

        @Test
        @DisplayName("SUPER_ADMIN: nie woła tenantService.findTenantEntity – tenantName jest null w tokenie")
        void login_superAdmin_doesNotLookUpTenantName() {
            AppUser superAdmin = buildSuperAdminUser();
            when(authenticationManager.authenticate(any())).thenReturn(mockAuthentication(superAdmin));
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(superAdmin));

            authService.login(new LoginRequest(null, USER_EMAIL, PASSWORD), CLIENT_IP);

            verify(tenantService, never()).findTenantEntity(any());
            verify(jwtService).issueAccessToken(eq(superAdmin), isNull(), anyBoolean());
        }

        @Test
        @DisplayName("tenantId nadal wymagany w praktyce dla zwykłego usera – pusty tenantId buduje klucz " +
                "globalny, który nie pasuje do żadnego tenant-scoped usera (odrzucone przez " +
                "AuthenticationManager tak samo jak błędne hasło)")
        void login_blankTenantId_forRegularUser_delegatesRejectionToAuthenticationManager() {
            // AuthServiceImpl samo w sobie nie odróżnia "usera nie znaleziono" od "złe hasło" –
            // obie ścieżki przechodzą przez AuthenticationManager i kończą się tym samym
            // BadCredentialsException (generyczny komunikat, brak ujawniania stanu konta).
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Bad credentials"));

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("", USER_EMAIL, PASSWORD), CLIENT_IP))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Nieprawidłowe dane logowania");
        }
    }

    // =========================================================================
    // refresh()
    // =========================================================================

    @Nested
    @DisplayName("refresh() – token rotation")
    class RefreshTests {

        @Test
        @DisplayName("zwraca nową parę tokenów i unieważnia stary refresh token")
        void refresh_validToken_returnsNewTokensAndRevokesOld() {
            AppUser user = buildUser(false, false, true);
            RefreshToken existing = buildRefreshToken(false);

            when(refreshTokenRepository.findByToken(REFRESH_TOKEN)).thenReturn(Optional.of(existing));
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            LoginResponse response = authService.refresh(new RefreshRequest(REFRESH_TOKEN));

            assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(existing.isRevoked()).isTrue();
            verify(refreshTokenRepository).save(existing);
        }

        @Test
        @DisplayName("nowy access token NIE ma mfaVerified=true (wymagana ponowna weryfikacja MFA)")
        void refresh_mfaUser_newTokenHasMfaVerifiedFalse() {
            AppUser user = buildUser(true, false, true);
            RefreshToken existing = buildRefreshToken(false);

            when(refreshTokenRepository.findByToken(REFRESH_TOKEN)).thenReturn(Optional.of(existing));
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            authService.refresh(new RefreshRequest(REFRESH_TOKEN));

            // mfaVerified MUSI być false – refresh token nie zastępuje weryfikacji TOTP
            verify(jwtService).issueAccessToken(any(), anyString(), eq(false));
        }

        @Test
        @DisplayName("rzuca InvalidTokenException gdy refresh token nieznany")
        void refresh_unknownToken_throwsInvalidTokenException() {
            when(refreshTokenRepository.findByToken(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh(new RefreshRequest("unknown-token")))
                    .isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("rzuca InvalidTokenException gdy refresh token wygasł")
        void refresh_expiredToken_throwsInvalidTokenException() {
            RefreshToken expired = buildRefreshToken(false);
            expired.setExpiresAt(Instant.now().minusSeconds(1)); // wygasły

            when(refreshTokenRepository.findByToken(REFRESH_TOKEN)).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(REFRESH_TOKEN)))
                    .isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("rzuca InvalidTokenException gdy refresh token już unieważniony (replay attack)")
        void refresh_revokedToken_throwsInvalidTokenException() {
            RefreshToken revoked = buildRefreshToken(true); // revoked=true

            when(refreshTokenRepository.findByToken(REFRESH_TOKEN)).thenReturn(Optional.of(revoked));

            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(REFRESH_TOKEN)))
                    .isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("rzuca DisabledException gdy użytkownik jest nieaktywny")
        void refresh_inactiveUser_throwsDisabledException() {
            AppUser inactiveUser = buildUser(false, false, false); // active=false
            RefreshToken existing = buildRefreshToken(false);

            when(refreshTokenRepository.findByToken(REFRESH_TOKEN)).thenReturn(Optional.of(existing));
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(inactiveUser));

            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(REFRESH_TOKEN)))
                    .isInstanceOf(DisabledException.class);
        }
    }

    // =========================================================================
    // logout()
    // =========================================================================

    @Nested
    @DisplayName("logout() – unieważnianie tokenów")
    class LogoutTests {

        @Test
        @DisplayName("blacklistuje access token i odwołuje refresh token")
        void logout_blacklistsAccessTokenAndRevokesRefreshToken() {
            when(jwtParser.parseQuiet(ACCESS_TOKEN)).thenReturn(
                    new JwtParser.JwtClaims(TENANT_ID, USER_ID, "AGENT", USER_EMAIL,
                            Instant.now().plusSeconds(300)));
            when(refreshTokenRepository.revokeByToken(REFRESH_TOKEN)).thenReturn(1);

            authService.logout(ACCESS_TOKEN, new LogoutRequest(REFRESH_TOKEN));

            verify(tokenBlacklistService).blacklist(eq(ACCESS_TOKEN), any(Instant.class));
            verify(refreshTokenRepository).revokeByToken(REFRESH_TOKEN);
        }

        @Test
        @DisplayName("ustawia status OFFLINE dla agenta przy wylogowaniu")
        void logout_agentRole_setsOfflineStatus() {
            TenantContext.setTenantId(TENANT_ID);
            TenantContext.setUserId(USER_ID);
            TenantContext.setUserRole("AGENT");

            when(jwtParser.parseQuiet(ACCESS_TOKEN)).thenReturn(
                    new JwtParser.JwtClaims(TENANT_ID, USER_ID, "AGENT", USER_EMAIL,
                            Instant.now().plusSeconds(300)));
            when(refreshTokenRepository.revokeByToken(REFRESH_TOKEN)).thenReturn(1);

            authService.logout(ACCESS_TOKEN, new LogoutRequest(REFRESH_TOKEN));

            verify(userService).updateStatus(eq(USER_ID), any(), eq(TENANT_ID));
        }

        @Test
        @DisplayName("NIE ustawia statusu OFFLINE dla supervisora przy wylogowaniu")
        void logout_supervisorRole_doesNotSetOfflineStatus() {
            TenantContext.setTenantId(TENANT_ID);
            TenantContext.setUserId(USER_ID);
            TenantContext.setUserRole("SUPERVISOR");

            when(jwtParser.parseQuiet(ACCESS_TOKEN)).thenReturn(
                    new JwtParser.JwtClaims(TENANT_ID, USER_ID, "SUPERVISOR", USER_EMAIL,
                            Instant.now().plusSeconds(300)));
            when(refreshTokenRepository.revokeByToken(REFRESH_TOKEN)).thenReturn(1);

            authService.logout(ACCESS_TOKEN, new LogoutRequest(REFRESH_TOKEN));

            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("nie rzuca wyjątku gdy refresh token jest nieznany (idempotentność)")
        void logout_unknownRefreshToken_doesNotThrow() {
            when(jwtParser.parse(ACCESS_TOKEN)).thenReturn(
                    new JwtParser.JwtClaims(TENANT_ID, USER_ID, "SUPERVISOR", USER_EMAIL,
                            Instant.now().plusSeconds(300)));
            when(refreshTokenRepository.revokeByToken(anyString())).thenReturn(0);

            assertThatNoException().isThrownBy(() ->
                    authService.logout(ACCESS_TOKEN, new LogoutRequest("unknown")));
        }
    }

    // =========================================================================
    // verifyMfa()
    // =========================================================================

    @Nested
    @DisplayName("verifyMfa() – weryfikacja kodu TOTP")
    class VerifyMfaTests {

        private static final String MFA_SECRET = "JBSWY3DPEHPK3PXP"; // base32
        private static final String VALID_CODE  = "123456";

        @Test
        @DisplayName("zwraca nowy access token z mfaVerified=true po poprawnej weryfikacji")
        void verifyMfa_validCode_returnsNewAccessToken() {
            AppUser user = buildUser(true, false, true);
            user.setMfaSecret(MFA_SECRET);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(mfaService.verifyCode(MFA_SECRET, VALID_CODE, USER_ID)).thenReturn(true);

            String newToken = authService.verifyMfa(USER_ID,
                    new MfaVerifyRequest(VALID_CODE), ACCESS_TOKEN);

            assertThat(newToken).isEqualTo(ACCESS_TOKEN);
            verify(jwtService).issueAccessToken(eq(user), anyString(), eq(true));
        }

        @Test
        @DisplayName("aktywuje MFA na koncie przy pierwszej weryfikacji")
        void verifyMfa_firstVerification_enablesMfa() {
            AppUser user = buildUser(false, false, true); // mfaEnabled=false
            user.setMfaSecret(MFA_SECRET);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(mfaService.verifyCode(MFA_SECRET, VALID_CODE, USER_ID)).thenReturn(true);

            authService.verifyMfa(USER_ID, new MfaVerifyRequest(VALID_CODE), ACCESS_TOKEN);

            verify(appUserRepository).enableMfa(USER_ID);
        }

        @Test
        @DisplayName("NIE wywołuje enableMfa gdy MFA już aktywne")
        void verifyMfa_alreadyEnabled_doesNotCallEnableMfa() {
            AppUser user = buildUser(true, false, true); // mfaEnabled=true
            user.setMfaSecret(MFA_SECRET);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(mfaService.verifyCode(MFA_SECRET, VALID_CODE, USER_ID)).thenReturn(true);

            authService.verifyMfa(USER_ID, new MfaVerifyRequest(VALID_CODE), ACCESS_TOKEN);

            verify(appUserRepository, never()).enableMfa(any());
        }

        @Test
        @DisplayName("blacklistuje stary access token po weryfikacji")
        void verifyMfa_validCode_blacklistsOldToken() {
            AppUser user = buildUser(true, false, true);
            user.setMfaSecret(MFA_SECRET);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(mfaService.verifyCode(MFA_SECRET, VALID_CODE, USER_ID)).thenReturn(true);
            when(jwtParser.parseQuiet(ACCESS_TOKEN)).thenReturn(
                    new JwtParser.JwtClaims(TENANT_ID, USER_ID, "AGENT", USER_EMAIL,
                            Instant.now().plusSeconds(300)));

            authService.verifyMfa(USER_ID, new MfaVerifyRequest(VALID_CODE), ACCESS_TOKEN);

            verify(tokenBlacklistService).blacklist(eq(ACCESS_TOKEN), any(Instant.class));
        }

        @Test
        @DisplayName("rzuca BadCredentialsException gdy kod TOTP jest nieprawidłowy")
        void verifyMfa_invalidCode_throwsBadCredentials() {
            AppUser user = buildUser(true, false, true);
            user.setMfaSecret(MFA_SECRET);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(mfaService.verifyCode(MFA_SECRET, "000000", USER_ID)).thenReturn(false);

            assertThatThrownBy(() ->
                    authService.verifyMfa(USER_ID, new MfaVerifyRequest("000000"), ACCESS_TOKEN))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Nieprawidłowy kod MFA");
        }

        @Test
        @DisplayName("rzuca InvalidOperationException gdy MFA secret nie jest skonfigurowany")
        void verifyMfa_noMfaSecret_throwsInvalidOperation() {
            AppUser user = buildUser(false, false, true);
            user.setMfaSecret(null); // brak secret

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    authService.verifyMfa(USER_ID, new MfaVerifyRequest("123456"), ACCESS_TOKEN))
                    .isInstanceOf(InvalidOperationException.class);
        }
    }

    // =========================================================================
    // changePassword()
    // =========================================================================

    @Nested
    @DisplayName("changePassword() – zmiana hasła")
    class ChangePasswordTests {

        private static final String NEW_PASSWORD  = "NewSecret123!";
        private static final String NEW_HASH      = "$2a$12$newhash";

        @Test
        @DisplayName("zmienia hasło i zwraca nowe tokeny")
        void changePassword_validCurrentPassword_changesAndReturnsTokens() {
            AppUser user = buildUser(false, true, true); // passwordResetRequired=true

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
            when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_HASH);
            when(jwtParser.parseQuiet(ACCESS_TOKEN)).thenReturn(
                    new JwtParser.JwtClaims(TENANT_ID, USER_ID, "AGENT", USER_EMAIL,
                            Instant.now().plusSeconds(300)));
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(1);

            LoginResponse response = authService.changePassword(USER_ID, TENANT_ID,
                    new ChangePasswordRequest(PASSWORD, NEW_PASSWORD), ACCESS_TOKEN);

            assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
            verify(appUserRepository).updatePasswordAndClearReset(USER_ID, NEW_HASH);
        }

        @Test
        @DisplayName("unieważnia wszystkie refresh tokeny użytkownika przy zmianie hasła")
        void changePassword_success_revokesAllRefreshTokens() {
            AppUser user = buildUser(false, false, true);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
            when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_HASH);
            when(jwtParser.parseQuiet(ACCESS_TOKEN)).thenReturn(
                    new JwtParser.JwtClaims(TENANT_ID, USER_ID, "AGENT", USER_EMAIL,
                            Instant.now().plusSeconds(300)));
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(2);

            authService.changePassword(USER_ID, TENANT_ID,
                    new ChangePasswordRequest(PASSWORD, NEW_PASSWORD), ACCESS_TOKEN);

            verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
        }

        @Test
        @DisplayName("rzuca BadCredentialsException gdy aktualne hasło jest nieprawidłowe")
        void changePassword_wrongCurrentPassword_throwsBadCredentials() {
            AppUser user = buildUser(false, false, true);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);

            assertThatThrownBy(() ->
                    authService.changePassword(USER_ID, TENANT_ID,
                            new ChangePasswordRequest(PASSWORD, NEW_PASSWORD), ACCESS_TOKEN))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Nieprawidłowe aktualne hasło");
        }

        @Test
        @DisplayName("rzuca IllegalArgumentException gdy nowe hasło nie spełnia wymagań siły")
        void changePassword_weakNewPassword_throwsIllegalArgument() {
            AppUser user = buildUser(false, false, true);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);

            // Słabe hasło – brak cyfry i wielkiej litery
            assertThatThrownBy(() ->
                    authService.changePassword(USER_ID, TENANT_ID,
                            new ChangePasswordRequest(PASSWORD, "weakpassword"), ACCESS_TOKEN))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // forcePasswordReset()
    // =========================================================================

    @Nested
    @DisplayName("forcePasswordReset() – wymuszenie zmiany hasła przez admina/supervisora")
    class ForcePasswordResetTests {

        @Test
        @DisplayName("SUPER_ADMIN może zresetować hasło użytkownika dowolnego tenanta")
        void forcePasswordReset_superAdminCanResetAnyUser() {
            AppUser target = buildUser(false, false, true);
            target.setTenantId(TENANT_B); // inny tenant

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(target));
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(1);

            assertThatNoException().isThrownBy(() ->
                    authService.forcePasswordReset(USER_ID, null, "SUPER_ADMIN"));

            verify(appUserRepository).save(org.mockito.ArgumentMatchers.<AppUser>argThat(
                    u -> u.isPasswordResetRequired()));
        }

        @Test
        @DisplayName("ADMIN może zresetować hasło użytkownika własnego tenanta")
        void forcePasswordReset_adminCanResetOwnTenantUser() {
            AppUser target = buildUser(false, false, true);
            target.setTenantId(TENANT_ID); // ten sam tenant

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(target));
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(1);

            assertThatNoException().isThrownBy(() ->
                    authService.forcePasswordReset(USER_ID, TENANT_ID, "ADMIN"));
        }

        @Test
        @DisplayName("ADMIN nie może zresetować hasła użytkownika innego tenanta – cross-tenant isolation")
        void forcePasswordReset_adminCannotResetOtherTenantUser_crossTenantBlocked() {
            AppUser target = buildUser(false, false, true);
            target.setTenantId(TENANT_B); // INNY tenant

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(target));

            assertThatThrownBy(() ->
                    authService.forcePasswordReset(USER_ID, TENANT_ID, "ADMIN"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("własnego tenanta");

            verify(appUserRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        }

        @Test
        @DisplayName("SUPERVISOR może zresetować hasło użytkownika własnego tenanta")
        void forcePasswordReset_supervisorCanResetOwnTenantUser() {
            AppUser target = buildUser(false, false, true);
            target.setTenantId(TENANT_ID); // ten sam tenant

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(target));
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(1);

            assertThatNoException().isThrownBy(() ->
                    authService.forcePasswordReset(USER_ID, TENANT_ID, "SUPERVISOR"));
        }

        @Test
        @DisplayName("SUPERVISOR nie może zresetować hasła użytkownika innego tenanta – cross-tenant isolation")
        void forcePasswordReset_supervisorCannotResetOtherTenantUser_crossTenantBlocked() {
            AppUser target = buildUser(false, false, true);
            target.setTenantId(TENANT_B); // INNY tenant

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(target));

            assertThatThrownBy(() ->
                    authService.forcePasswordReset(USER_ID, TENANT_ID, "SUPERVISOR"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("własnego tenanta");

            verify(appUserRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        }

        @Test
        @DisplayName("unieważnia wszystkie refresh tokeny celu przy force reset")
        void forcePasswordReset_revokesAllTargetRefreshTokens() {
            AppUser target = buildUser(false, false, true);
            target.setTenantId(TENANT_ID);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(target));
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(3);

            authService.forcePasswordReset(USER_ID, TENANT_ID, "SUPERVISOR");

            verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
        }

        @Test
        @DisplayName("unieważnia refresh tokeny PRZED zapisem encji – @Modifying(clearAutomatically=true) " +
                "na revokeAllByUserId() wywołuje entityManager.clear(), które po cichu odrzuca " +
                "niezflushowane zmiany na już załadowanej encji AppUser bez żadnego wyjątku")
        void forcePasswordReset_revokesTokensBeforeSavingUserEntity() {
            AppUser target = buildUser(false, false, true);
            target.setTenantId(TENANT_ID);

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(target));
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(1);

            authService.forcePasswordReset(USER_ID, TENANT_ID, "ADMIN");

            InOrder order = inOrder(refreshTokenRepository, appUserRepository);
            order.verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
            order.verify(appUserRepository).save(target);
        }

        @Test
        @DisplayName("rzuca IllegalArgumentException gdy użytkownik nie istnieje")
        void forcePasswordReset_unknownUser_throwsIllegalArgument() {
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    authService.forcePasswordReset(USER_ID, TENANT_ID, "ADMIN"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // setupMfa()
    // =========================================================================

    @Nested
    @DisplayName("setupMfa() – konfiguracja TOTP")
    class SetupMfaTests {

        @Test
        @DisplayName("rzuca InvalidOperationException gdy MFA już aktywne")
        void setupMfa_alreadyEnabled_throwsInvalidOperation() {
            AppUser user = buildUser(true, false, true); // mfaEnabled=true

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.setupMfa(USER_ID, USER_EMAIL))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("MFA jest już aktywne");
        }

        @Test
        @DisplayName("zwraca secret i QR code URI gdy MFA nie jest aktywne")
        void setupMfa_notEnabled_returnsSecretAndQrCode() {
            AppUser user = buildUser(false, false, true); // mfaEnabled=false

            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(mfaService.generateSecret()).thenReturn("JBSWY3DPEHPK3PXP");
            when(mfaService.generateQrCodeDataUri(anyString(), eq(USER_EMAIL)))
                    .thenReturn("data:image/png;base64,iVBOR...");

            var response = authService.setupMfa(USER_ID, USER_EMAIL);

            assertThat(response.secret()).isEqualTo("JBSWY3DPEHPK3PXP");
            assertThat(response.qrCodeUri()).startsWith("data:image/png");
            verify(appUserRepository).updateMfaSecret(USER_ID, "JBSWY3DPEHPK3PXP");
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Buduje użytkownika z konfigurowalnymi flagami.
     *
     * @param mfaEnabled           czy MFA jest włączone
     * @param passwordResetRequired czy wymagana jest zmiana hasła
     * @param active               czy konto jest aktywne
     */
    private AppUser buildUser(boolean mfaEnabled, boolean passwordResetRequired, boolean active) {
        return AppUser.builder()
                .id(USER_ID)
                .tenantId(TENANT_ID)
                .email(USER_EMAIL)
                .passwordHash(HASH)
                .role(UserRole.AGENT)
                .active(active)
                .mfaEnabled(mfaEnabled)
                .passwordResetRequired(passwordResetRequired)
                .status(UserStatus.AVAILABLE)
                .build();
    }

    /** Buduje użytkownika SUPER_ADMIN (bez tenanta) – refaktor ról. */
    private AppUser buildSuperAdminUser() {
        return AppUser.builder()
                .id(USER_ID)
                .tenantId(null)
                .email(USER_EMAIL)
                .passwordHash(HASH)
                .role(UserRole.SUPER_ADMIN)
                .active(true)
                .mfaEnabled(false)
                .passwordResetRequired(false)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private Tenant buildTenant() {
        return Tenant.builder()
                .id(TENANT_ID)
                .name("Test Tenant")
                .build();
    }

    private RefreshToken buildRefreshToken(boolean revoked) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .token(REFRESH_TOKEN)
                .expiresAt(Instant.now().plusSeconds(604_800))
                .revoked(revoked)
                .build();
    }

    private LoginRequest buildLoginRequest() {
        return new LoginRequest(TENANT_ID.toString(), USER_EMAIL, PASSWORD);
    }

    private Authentication mockAuthentication(AppUser user) {
        AppUserDetails details = new AppUserDetails(user);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                details, null, details.getAuthorities());
        return auth;
    }
}
