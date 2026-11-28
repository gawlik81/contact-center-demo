package com.contactcenter.domain.user;

import com.contactcenter.api.auth.dto.ChangePasswordRequest;
import com.contactcenter.api.auth.dto.LoginRequest;
import com.contactcenter.api.auth.dto.LogoutRequest;
import com.contactcenter.api.auth.dto.MfaVerifyRequest;
import com.contactcenter.api.auth.dto.RefreshRequest;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.tenant.TenantStatusGate;
import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.domain.websocket.WebSocketSessionTerminator;
import com.contactcenter.security.JwtParser;
import com.contactcenter.security.JwtParser.JwtClaims;
import com.contactcenter.security.JwtService;
import com.contactcenter.security.LoginRateLimiter;
import com.contactcenter.security.MfaService;
import com.contactcenter.security.PendingLoginTracker;
import com.contactcenter.security.TenantContext;
import com.contactcenter.security.TokenBlacklistService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-249: sesja logowania ({@code sid} = rodzina refresh tokenow), logout per urzadzenie, samodzielna zmiana hasla
 * (pozostale urzadzenia), wspolna bramka statusu tenanta w login/refresh.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthService (BE-249): sid, logout per urzadzenie, zmiana hasla, bramka statusu tenanta")
class AuthServiceSessionTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_USER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String ACCESS = "access.jwt";
    private static final String REFRESH = "refresh-value";

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
    @Mock private WebSocketSessionTerminator terminator;
    @Mock private TenantStatusGate tenantStatusGate;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(authenticationManager, jwtService, jwtParser, tokenBlacklistService,
                mfaService, appUserRepository, refreshTokenRepository, tenantService, passwordEncoder,
                loginRateLimiter, userService, pendingLoginTracker, terminator, tenantStatusGate);
        Tenant tenant = new Tenant();
        tenant.setName("T");
        when(tenantService.findTenantEntity(any())).thenReturn(Optional.of(tenant));
        when(jwtService.issueAccessToken(any(), any(), anyBoolean(), any())).thenReturn("new.access");
        when(jwtService.generateRefreshTokenValue()).thenReturn("new-refresh");
        when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);
        when(jwtService.refreshTokenExpiresAt()).thenReturn(Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AppUser user(UUID tenantId, AppUser.UserRole role) {
        return AppUser.builder().id(USER_ID).tenantId(tenantId).email("a@t.com").passwordHash("hash").role(role)
                .active(true).mfaEnabled(false).passwordResetRequired(false).status(AppUser.UserStatus.ACTIVE).build();
    }

    private static JwtClaims claims(UUID sid) {
        return new JwtClaims(TENANT_ID, USER_ID, "AGENT", "a@t.com", Instant.now().plusSeconds(600), Instant.now(), sid);
    }

    private ArgumentCaptor<RefreshToken> savedRefreshToken() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        return captor;
    }

    // ------------------------------------------------------------------ sid

    @Test
    @DisplayName("login: nowa sesja logowania - ten sam sid w access tokenie i w rodzinie refresh tokenu")
    void loginStartsNewLoginSession() {
        AppUser u = user(TENANT_ID, AppUser.UserRole.AGENT);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new com.contactcenter.security.AppUserDetails(u));
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        authService.login(new LoginRequest(TENANT_ID.toString(), "a@t.com", "pw"), "1.1.1.1");

        ArgumentCaptor<UUID> sid = ArgumentCaptor.forClass(UUID.class);
        verify(jwtService).issueAccessToken(eq(u), anyString(), anyBoolean(), sid.capture());
        assertThat(sid.getValue()).isNotNull();
        assertThat(savedRefreshToken().getValue().getSessionId()).isEqualTo(sid.getValue());
    }

    @Test
    @DisplayName("login: nieaktywny tenant (bramka) = DisabledException PO weryfikacji hasla, bez tokenow")
    void loginRejectsInactiveTenant() {
        AppUser u = user(TENANT_ID, AppUser.UserRole.AGENT);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new com.contactcenter.security.AppUserDetails(u));
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(tenantStatusGate.isBlocked(TENANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest(TENANT_ID.toString(), "a@t.com", "pw"), "1.1.1.1"))
                .isInstanceOf(DisabledException.class);

        verify(jwtService, never()).issueAccessToken(any(), any(), anyBoolean(), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("login SUPER_ADMIN (bez tenanta): bramka dostaje null, nie jest blokowany, brak odczytu tenanta")
    void loginSuperAdminSkipsTenantGate() {
        AppUser sa = user(null, AppUser.UserRole.SUPER_ADMIN);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new com.contactcenter.security.AppUserDetails(sa));
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(sa));
        when(tenantStatusGate.isBlocked(null)).thenReturn(false);

        authService.login(new LoginRequest(null, "a@t.com", "pw"), "1.1.1.1");

        verify(tenantStatusGate).isBlocked(null);
        verify(tenantService, never()).findTenantEntity(any());
    }

    @Test
    @DisplayName("refresh: rotacja DZIEDZICZY rodzine (sid) - nowy access token i nowy refresh token w tej samej sesji")
    void refreshInheritsSession() {
        AppUser u = user(TENANT_ID, AppUser.UserRole.AGENT);
        RefreshToken old = RefreshToken.builder().id(UUID.randomUUID()).userId(USER_ID).tenantId(TENANT_ID)
                .token(REFRESH).sessionId(SID).expiresAt(Instant.now().plusSeconds(600)).revoked(false).build();
        when(refreshTokenRepository.findByToken(REFRESH)).thenReturn(Optional.of(old));
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        authService.refresh(new RefreshRequest(REFRESH));

        verify(jwtService).issueAccessToken(eq(u), anyString(), eq(false), eq(SID));
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(saved.capture()); // old (revoked) + new
        assertThat(saved.getAllValues().stream().filter(t -> "new-refresh".equals(t.getToken()))
                .findFirst().orElseThrow().getSessionId()).isEqualTo(SID);
    }

    @Test
    @DisplayName("refresh SUPER_ADMIN: nie woluje findTenantEntity, bramka dostaje null")
    void refreshSuperAdminDoesNotLookUpTenant() {
        AppUser sa = user(null, AppUser.UserRole.SUPER_ADMIN);
        RefreshToken old = RefreshToken.builder().id(UUID.randomUUID()).userId(USER_ID).token(REFRESH)
                .sessionId(SID).expiresAt(Instant.now().plusSeconds(600)).revoked(false).build();
        when(refreshTokenRepository.findByToken(REFRESH)).thenReturn(Optional.of(old));
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(sa));

        authService.refresh(new RefreshRequest(REFRESH));

        verify(tenantService, never()).findTenantEntity(any());
        verify(tenantStatusGate).isBlocked(null);
    }

    // --------------------------------------------------------------- logout

    @Test
    @DisplayName("logout: zamyka TYLKO sesje tego urzadzenia (sid z access tokenu), nie wszystkie sesje uzytkownika")
    void logoutClosesOnlyThisDevice() {
        when(jwtParser.parseQuiet(ACCESS)).thenReturn(claims(SID));

        authService.logout(ACCESS, new LogoutRequest(REFRESH));

        verify(terminator).closeLoginSession(TENANT_ID, USER_ID, SID, SessionCloseReason.LOGOUT);
        verify(terminator, never()).closeUserSessions(any(), any(), any());
        verify(refreshTokenRepository).revokeByTokenAndUserId(REFRESH, USER_ID);
        verify(refreshTokenRepository).revokeBySessionId(USER_ID, SID);
    }

    @Test
    @DisplayName("logout tokenem sprzed BE-249 (bez sid): sid z refresh tokenu NALEZACEGO do tego samego uzytkownika")
    void logoutLegacyTokenUsesOwnRefreshTokenFamily() {
        when(jwtParser.parseQuiet(ACCESS)).thenReturn(claims(null));
        when(refreshTokenRepository.findByToken(REFRESH)).thenReturn(Optional.of(RefreshToken.builder()
                .id(UUID.randomUUID()).userId(USER_ID).sessionId(SID).token(REFRESH)
                .expiresAt(Instant.now().plusSeconds(60)).build()));

        authService.logout(ACCESS, new LogoutRequest(REFRESH));

        verify(terminator).closeLoginSession(TENANT_ID, USER_ID, SID, SessionCloseReason.LOGOUT);
    }

    @Test
    @DisplayName("logout cudzym refresh tokenem nie wskazuje cudzej sesji: fallback = wszystkie sesje WLASNEGO uzytkownika")
    void logoutWithForeignRefreshTokenFallsBackToAllOwnSessions() {
        when(jwtParser.parseQuiet(ACCESS)).thenReturn(claims(null));
        when(refreshTokenRepository.findByToken(REFRESH)).thenReturn(Optional.of(RefreshToken.builder()
                .id(UUID.randomUUID()).userId(OTHER_USER).sessionId(SID).token(REFRESH)
                .expiresAt(Instant.now().plusSeconds(60)).build()));

        authService.logout(ACCESS, new LogoutRequest(REFRESH));

        verify(terminator).closeUserSessions(TENANT_ID, USER_ID, SessionCloseReason.LOGOUT);
        verify(terminator, never()).closeLoginSession(any(), any(), any(), any());
        verify(refreshTokenRepository, never()).revokeBySessionId(any(), any());
    }

    @Test
    @DisplayName("logoutAll: zamyka sesje WSZYSTKICH urzadzen (closeUserSessions), nie jednego")
    void logoutAllClosesEverything() {
        when(jwtParser.parseQuiet(ACCESS)).thenReturn(claims(SID));

        authService.logoutAll(ACCESS, USER_ID);

        verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
        verify(terminator).closeUserSessions(TENANT_ID, USER_ID, SessionCloseReason.LOGOUT);
        verify(terminator, never()).closeLoginSession(any(), any(), any(), any());
    }

    @Test
    @DisplayName("logout: blad terminatora nie przerywa wylogowania")
    void logoutSurvivesTerminatorFailure() {
        when(jwtParser.parseQuiet(ACCESS)).thenReturn(claims(SID));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(terminator)
                .closeLoginSession(any(), any(), any(), any());

        authService.logout(ACCESS, new LogoutRequest(REFRESH));

        verify(refreshTokenRepository).revokeByTokenAndUserId(REFRESH, USER_ID);
    }

    // ------------------------------------------------------- changePassword

    @Test
    @DisplayName("changePassword: bierzaca sesja zostaje (ten sam sid), POZOSTALE urzadzenia zamykane i unieważniane")
    void changePasswordClosesOtherDevicesAndKeepsCurrent() {
        AppUser u = user(TENANT_ID, AppUser.UserRole.AGENT);
        UUID other1 = UUID.randomUUID();
        UUID other2 = UUID.randomUUID();
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("OldPass1", "hash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass2")).thenReturn("newhash");
        when(jwtParser.parseQuiet(ACCESS)).thenReturn(claims(SID));
        when(refreshTokenRepository.findActiveSessionIdsExcept(eq(USER_ID), eq(SID), any(Instant.class)))
                .thenReturn(List.of(other1, other2));

        authService.changePassword(USER_ID, TENANT_ID, new ChangePasswordRequest("OldPass1", "NewPass2"), ACCESS);

        verify(terminator).closeOtherLoginSessions(TENANT_ID, USER_ID, SID, List.of(other1, other2),
                SessionCloseReason.PASSWORD_CHANGED);
        verify(terminator, never()).closeUserSessions(any(), any(), any());
        verify(jwtService).issueAccessToken(eq(u), anyString(), anyBoolean(), eq(SID));
        assertThat(savedRefreshToken().getValue().getSessionId()).isEqualTo(SID);
        verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
    }

    @Test
    @DisplayName("changePassword tokenem bez sid: nowa rodzina; wszystkie sesje WS poza nowa zamykane (bez 'wyjatku')")
    void changePasswordWithLegacyTokenStartsNewFamily() {
        AppUser u = user(TENANT_ID, AppUser.UserRole.AGENT);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("newhash");
        when(jwtParser.parseQuiet(ACCESS)).thenReturn(claims(null));

        authService.changePassword(USER_ID, TENANT_ID, new ChangePasswordRequest("OldPass1", "NewPass2"), ACCESS);

        ArgumentCaptor<UUID> sid = ArgumentCaptor.forClass(UUID.class);
        verify(jwtService).issueAccessToken(eq(u), anyString(), anyBoolean(), sid.capture());
        assertThat(sid.getValue()).isNotNull();
        verify(terminator).closeOtherLoginSessions(eq(TENANT_ID), eq(USER_ID), eq(sid.getValue()), any(),
                eq(SessionCloseReason.PASSWORD_CHANGED));
    }

    @Test
    @DisplayName("changePassword: blad terminatora nie psuje zmiany hasla")
    void changePasswordSurvivesTerminatorFailure() {
        AppUser u = user(TENANT_ID, AppUser.UserRole.AGENT);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("newhash");
        when(jwtParser.parseQuiet(ACCESS)).thenReturn(claims(SID));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(terminator)
                .closeOtherLoginSessions(any(), any(), any(), any(), any());

        assertThat(authService.changePassword(USER_ID, TENANT_ID, new ChangePasswordRequest("OldPass1", "NewPass2"),
                ACCESS).accessToken()).isEqualTo("new.access");
    }

    // ------------------------------------------------------------ verifyMfa

    @Test
    @DisplayName("verifyMfa: nowy access token zostaje w tej samej sesji logowania (sid ze starego tokenu)")
    void verifyMfaKeepsLoginSession() {
        AppUser u = user(TENANT_ID, AppUser.UserRole.AGENT);
        u.setMfaSecret("secret");
        u.setMfaEnabled(true);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(mfaService.verifyCode(anyString(), anyString(), any())).thenReturn(true);
        when(jwtParser.parseQuiet(ACCESS)).thenReturn(claims(SID));

        authService.verifyMfa(USER_ID, new MfaVerifyRequest("123456"), ACCESS);

        verify(jwtService).issueAccessToken(eq(u), anyString(), eq(true), eq(SID));
    }

    @Test
    @DisplayName("identyfikacja bez tokenu i bez kontekstu: logout nie wola terminatora (nie ma kogo zamykac)")
    void logoutWithoutIdentityDoesNothing() {
        authService.logout(null, new LogoutRequest(REFRESH));

        verify(terminator, never()).closeUserSessions(any(), any(), any());
        verify(terminator, never()).closeLoginSession(any(), any(), any(), any());
    }
}
