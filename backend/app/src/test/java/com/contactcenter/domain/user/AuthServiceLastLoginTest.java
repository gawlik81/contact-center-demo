package com.contactcenter.domain.user;

import com.contactcenter.api.auth.dto.ChangePasswordRequest;
import com.contactcenter.api.auth.dto.LoginRequest;
import com.contactcenter.api.auth.dto.MfaVerifyRequest;
import com.contactcenter.api.auth.dto.RefreshRequest;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.security.AppUserDetails;
import com.contactcenter.security.JwtParser;
import com.contactcenter.security.JwtService;
import com.contactcenter.security.LoginRateLimiter;
import com.contactcenter.security.MfaService;
import com.contactcenter.security.PendingLoginTracker;
import com.contactcenter.security.TokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-158: {@code app_user.last_login_at} is written only when a login is fully completed
 * (password, plus TOTP when MFA is enabled) and never on token refresh.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthService - zapis lastLoginAt (BE-158)")
class AuthServiceLastLoginTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String HASH = "$2a$12$hash";

    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;
    @Mock private JwtParser jwtParser;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private MfaService mfaService;
    @Mock private AppUserRepository appUserRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private TenantService tenantService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LoginRateLimiter loginRateLimiter;
    @Mock private UserService userService;
    @Mock private PendingLoginTracker pendingLoginTracker;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(authenticationManager, jwtService, jwtParser, tokenBlacklistService,
                mfaService, appUserRepository, refreshTokenRepository, tenantService, passwordEncoder,
                loginRateLimiter, userService, pendingLoginTracker);
        when(jwtService.issueAccessToken(any(), any(), anyBoolean())).thenReturn("access");
        when(jwtService.generateRefreshTokenValue()).thenReturn("refresh");
        when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);
        when(tenantService.findTenantEntity(TENANT_ID))
                .thenReturn(Optional.of(Tenant.builder().id(TENANT_ID).name("T").build()));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(appUserRepository.updateLastLoginAt(any(), any(), any())).thenReturn(1);
    }

    private AppUser user(boolean mfa, boolean resetRequired) {
        return AppUser.builder().id(USER_ID).tenantId(TENANT_ID).email("a@t.com").passwordHash(HASH)
                .role(UserRole.AGENT).active(true).mfaEnabled(mfa).mfaSecret(mfa ? "SECRET" : null)
                .passwordResetRequired(resetRequired).status(UserStatus.AVAILABLE).build();
    }

    private void authenticateAs(AppUser u) {
        AppUserDetails details = new AppUserDetails(u);
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
    }

    private LoginRequest loginRequest() {
        return new LoginRequest(TENANT_ID.toString(), "a@t.com", "pw");
    }

    @Test
    @DisplayName("login bez MFA i bez wymuszonej zmiany hasla zapisuje lastLoginAt = teraz")
    void loginWithoutMfa_recordsLastLogin() {
        AppUser u = user(false, false);
        authenticateAs(u);
        Instant before = Instant.now();

        authService.login(loginRequest(), "1.1.1.1");

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(appUserRepository).updateLastLoginAt(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(TENANT_ID), at.capture());
        assertThat(at.getValue()).isBetween(before, Instant.now());
        assertThat(u.getLastLoginAt()).isEqualTo(at.getValue());
        verify(pendingLoginTracker, never()).markPending(any());
    }

    @Test
    @DisplayName("login z MFA NIE zapisuje lastLoginAt (czeka na TOTP), tylko oznacza oczekujace logowanie")
    void loginWithMfa_doesNotRecordYet() {
        AppUser u = user(true, false);
        authenticateAs(u);

        authService.login(loginRequest(), "1.1.1.1");

        verify(appUserRepository, never()).updateLastLoginAt(any(), any(), any());
        verify(pendingLoginTracker).markPending(USER_ID);
    }

    @Test
    @DisplayName("verifyMfa po swiezym logowaniu zapisuje lastLoginAt")
    void verifyMfa_afterFreshLogin_records() {
        AppUser u = user(true, false);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(mfaService.verifyCode("SECRET", "123456", USER_ID)).thenReturn(true);
        when(pendingLoginTracker.consume(USER_ID)).thenReturn(true);

        authService.verifyMfa(USER_ID, new MfaVerifyRequest("123456"), null);

        verify(appUserRepository).updateLastLoginAt(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(TENANT_ID), any());
    }

    @Test
    @DisplayName("verifyMfa bez oczekujacego logowania (po refresh / pierwsza aktywacja MFA) NIE zmienia lastLoginAt")
    void verifyMfa_withoutPendingLogin_doesNotRecord() {
        AppUser u = user(true, false);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(mfaService.verifyCode("SECRET", "123456", USER_ID)).thenReturn(true);
        when(pendingLoginTracker.consume(USER_ID)).thenReturn(false);

        authService.verifyMfa(USER_ID, new MfaVerifyRequest("123456"), null);

        verify(appUserRepository, never()).updateLastLoginAt(any(), any(), any());
    }

    @Test
    @DisplayName("niepoprawny kod TOTP nie zapisuje lastLoginAt")
    void verifyMfa_wrongCode_doesNotRecord() {
        AppUser u = user(true, false);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(mfaService.verifyCode("SECRET", "000000", USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> authService.verifyMfa(USER_ID, new MfaVerifyRequest("000000"), null))
                .isInstanceOf(BadCredentialsException.class);

        verify(appUserRepository, never()).updateLastLoginAt(any(), any(), any());
        verify(pendingLoginTracker, never()).consume(any());
    }

    @Test
    @DisplayName("login z wymuszona zmiana hasla nie zapisuje lastLoginAt; zapisze dopiero changePassword (bez MFA)")
    void forcedPasswordReset_recordsOnChangePassword() {
        AppUser u = user(false, true);
        authenticateAs(u);

        authService.login(loginRequest(), "1.1.1.1");
        verify(appUserRepository, never()).updateLastLoginAt(any(), any(), any());
        verify(pendingLoginTracker).markPending(USER_ID);

        when(passwordEncoder.matches("old", HASH)).thenReturn(true);
        when(passwordEncoder.encode("NewPass2")).thenReturn("newhash");
        authService.changePassword(USER_ID, TENANT_ID, new ChangePasswordRequest("old", "NewPass2"), "tok");

        verify(appUserRepository).updateLastLoginAt(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(TENANT_ID), any());
    }

    @Test
    @DisplayName("dobrowolna zmiana hasla zalogowanego uzytkownika (bez wymuszenia) nie zmienia lastLoginAt")
    void voluntaryPasswordChange_doesNotRecord() {
        AppUser u = user(false, false);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("old", HASH)).thenReturn(true);
        when(passwordEncoder.encode("NewPass2")).thenReturn("newhash");

        authService.changePassword(USER_ID, TENANT_ID, new ChangePasswordRequest("old", "NewPass2"), "tok");

        verify(appUserRepository, never()).updateLastLoginAt(any(), any(), any());
    }

    @Test
    @DisplayName("refresh tokenu NIE zmienia lastLoginAt")
    void refresh_doesNotRecord() {
        RefreshToken stored = RefreshToken.builder().id(UUID.randomUUID()).userId(USER_ID).tenantId(TENANT_ID)
                .token("refresh").expiresAt(Instant.now().plusSeconds(600)).revoked(false).build();
        when(refreshTokenRepository.findByToken(anyString())).thenReturn(Optional.of(stored));
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user(false, false)));

        try {
            authService.refresh(new RefreshRequest("refresh"));
        } catch (RuntimeException ignored) {
            // token plumbing is irrelevant here - only the absence of the timestamp write matters
        }

        verify(appUserRepository, never()).updateLastLoginAt(any(), any(), any());
        verify(pendingLoginTracker, never()).markPending(any());
    }

    @Test
    @DisplayName("0 zaktualizowanych wierszy nie przerywa logowania (tylko WARN)")
    void recordFailureDoesNotBreakLogin() {
        when(appUserRepository.updateLastLoginAt(any(), any(), any())).thenReturn(0);
        AppUser u = user(false, false);
        authenticateAs(u);

        assertThat(authService.login(loginRequest(), "1.1.1.1").accessToken()).isEqualTo("access");
        assertThat(u.getLastLoginAt()).isNull();
    }

    // ---------------------------------------------------------------------
    // Marker lifecycle (review BE-158)
    // ---------------------------------------------------------------------

    private java.util.Set<UUID> statefulTracker() {
        java.util.Set<UUID> pending = new java.util.HashSet<>();
        org.mockito.Mockito.doAnswer(inv -> pending.add(inv.getArgument(0)))
                .when(pendingLoginTracker).markPending(any());
        when(pendingLoginTracker.consume(any())).thenAnswer(inv -> pending.remove((UUID) inv.getArgument(0)));
        return pending;
    }

    @Test
    @DisplayName("reset hasla bez MFA zuzywa znacznik: pozniejsza pierwsza aktywacja MFA nie zmienia lastLoginAt")
    void forcedResetWithoutMfa_leavesNoMarker_laterMfaActivationDoesNotRecord() {
        java.util.Set<UUID> pending = statefulTracker();
        AppUser u = user(false, true);
        authenticateAs(u);
        authService.login(loginRequest(), "1.1.1.1");
        assertThat(pending).containsExactly(USER_ID);

        when(passwordEncoder.matches("old", HASH)).thenReturn(true);
        when(passwordEncoder.encode("NewPass2")).thenReturn("newhash");
        authService.changePassword(USER_ID, TENANT_ID, new ChangePasswordRequest("old", "NewPass2"), "tok");

        assertThat(pending).isEmpty();
        verify(appUserRepository, org.mockito.Mockito.times(1)).updateLastLoginAt(any(), any(), any());

        // first MFA activation inside the running session
        AppUser sameUser = user(false, false);
        sameUser.setMfaSecret("SECRET");
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(sameUser));
        when(mfaService.verifyCode("SECRET", "123456", USER_ID)).thenReturn(true);
        authService.verifyMfa(USER_ID, new MfaVerifyRequest("123456"), null);

        verify(appUserRepository, org.mockito.Mockito.times(1)).updateLastLoginAt(any(), any(), any());
    }

    @Test
    @DisplayName("reset hasla Z MFA zostawia znacznik dla verifyMfa (nie zuzywa go)")
    void forcedResetWithMfa_keepsMarkerForVerify() {
        java.util.Set<UUID> pending = statefulTracker();
        AppUser u = user(true, true);
        authenticateAs(u);
        authService.login(loginRequest(), "1.1.1.1");
        when(passwordEncoder.matches("old", HASH)).thenReturn(true);
        when(passwordEncoder.encode("NewPass2")).thenReturn("newhash");

        authService.changePassword(USER_ID, TENANT_ID, new ChangePasswordRequest("old", "NewPass2"), "tok");

        assertThat(pending).containsExactly(USER_ID);
        verify(appUserRepository, never()).updateLastLoginAt(any(), any(), any());
    }

    @Test
    @DisplayName("logoutAll czyści znacznik oczekującego logowania")
    void logoutAll_clearsPendingMarker() {
        authService.logoutAll("tok", USER_ID);

        verify(pendingLoginTracker).consume(USER_ID);
    }

    @Test
    @DisplayName("SUPER_ADMIN (tenant_id NULL) zapisuje lastLoginAt wariantem globalnym")
    void superAdmin_usesGlobalUpdate() {
        AppUser admin = AppUser.builder().id(USER_ID).tenantId(null).email("sa@t.com").passwordHash(HASH)
                .role(UserRole.SUPER_ADMIN).active(true).status(UserStatus.AVAILABLE).build();
        authenticateAs(admin);
        when(appUserRepository.updateLastLoginAtGlobal(any(), any())).thenReturn(1);

        authService.login(new LoginRequest(null, "sa@t.com", "pw"), "1.1.1.1");

        verify(appUserRepository).updateLastLoginAtGlobal(org.mockito.ArgumentMatchers.eq(USER_ID), any());
        verify(appUserRepository, never()).updateLastLoginAt(any(), any(), any());
        assertThat(admin.getLastLoginAt()).isNotNull();
    }
}
