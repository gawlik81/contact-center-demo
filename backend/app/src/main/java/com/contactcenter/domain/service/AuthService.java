package com.contactcenter.domain.service;

import com.contactcenter.api.auth.dto.ChangePasswordRequest;
import com.contactcenter.api.auth.dto.LoginRequest;
import com.contactcenter.api.auth.dto.LoginResponse;
import com.contactcenter.api.auth.dto.LogoutRequest;
import com.contactcenter.api.auth.dto.MfaSetupResponse;
import com.contactcenter.api.auth.dto.MfaVerifyRequest;
import com.contactcenter.api.auth.dto.RefreshRequest;
import com.contactcenter.api.user.dto.UpdateStatusRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.AppUser.UserStatus;
import com.contactcenter.domain.model.RefreshToken;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.RefreshTokenRepository;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.*;
import com.contactcenter.security.JwtParser.JwtClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Serwis domenowy obsługujący autentykację i zarządzanie tokenami.
 *
 * <p>Implementuje następujące przepływy:
 * <ol>
 *   <li><strong>Login</strong> – weryfikacja hasła bcrypt, wystawienie access+refresh tokenów</li>
 *   <li><strong>Refresh</strong> – token rotation: stary refresh token → nowa para tokenów</li>
 *   <li><strong>Logout</strong> – blacklista access tokenu w Redis + revoke refresh tokenu w DB</li>
 *   <li><strong>MFA Setup</strong> – generowanie TOTP secret + QR code URI</li>
 *   <li><strong>MFA Verify</strong> – weryfikacja kodu TOTP, aktywacja MFA, nowy token z mfaVerified=true</li>
 * </ol>
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Hasła weryfikowane przez {@code DaoAuthenticationProvider} (bcrypt cost=12)</li>
 *   <li>Komunikaty błędów są generyczne (nie zdradzają czy konto istnieje)</li>
 *   <li>Refresh token rotation: każdy refresh wystawia nową parę tokenów i unieważnia stary</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtParser jwtParser;
    private final TokenBlacklistService tokenBlacklistService;
    private final MfaService mfaService;
    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter loginRateLimiter;
    private final UserService userService;

    // =========================================================================
    // Login
    // =========================================================================

    /**
     * Loguje użytkownika: weryfikuje hasło i wystawia parę access+refresh tokenów.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Rate limiting: sprawdza liczbę prób logowania z danego IP (max 5/15 min)</li>
     *   <li>Autentykacja przez AuthenticationManager (bcrypt weryfikacja hasła)</li>
     *   <li>Sprawdzenie flagi {@code passwordResetRequired} – jeśli true, zwraca
     *       {@link LoginResponse#passwordResetRequired} (klient musi zmienić hasło)</li>
     *   <li>Dla użytkowników z MFA: tymczasowy token z {@code mfaVerified=false}</li>
     *   <li>Po udanym logowaniu resetuje licznik rate limit dla IP</li>
     * </ol>
     *
     * @param request dane logowania (tenantId, email, password)
     * @param ip      adres IP klienta (do rate limiting)
     * @return para tokenów; pole {@code mfaRequired=true} lub {@code passwordResetRequired=true}
     *         gdy wymagane dodatkowe akcje
     * @throws com.contactcenter.domain.exception.RateLimitExceededException gdy przekroczono limit prób
     * @throws BadCredentialsException gdy hasło nieprawidłowe
     * @throws DisabledException       gdy konto nieaktywne
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String ip) {
        // Rate limiting: sprawdź i inkrementuj licznik dla IP
        loginRateLimiter.checkAndIncrement(ip);

        UUID tenantId;
        try {
            tenantId = UUID.fromString(request.tenantId());
        } catch (IllegalArgumentException e) {
            log.warn("[Auth] Nieprawidłowy format tenantId: {}", request.tenantId());
            throw new BadCredentialsException("Nieprawidłowe dane logowania");
        }

        // AuthenticationManager wywołuje UserDetailsServiceImpl.loadUserByUsername(tenantId:email)
        // i weryfikuje hasło przez BCryptPasswordEncoder
        String usernameKey = UserDetailsServiceImpl.buildKey(tenantId, request.email());
        Authentication authRequest = new UsernamePasswordAuthenticationToken(
                usernameKey, request.password()
        );

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(authRequest);
        } catch (AuthenticationException e) {
            log.warn("[Auth] Nieudana próba logowania dla tenant={}, email={}: {}",
                    tenantId, request.email(), e.getClass().getSimpleName());
            // Generyczny komunikat – nie zdradzamy czy problem z hasłem czy z kontem
            throw new BadCredentialsException("Nieprawidłowe dane logowania");
        }

        AppUserDetails userDetails = (AppUserDetails) authentication.getPrincipal();

        // Pobierz pełną encję (potrzebujemy mfaEnabled, passwordResetRequired, id do refresh tokenu)
        AppUser user = appUserRepository.findById(userDetails.getUserId())
                .orElseThrow(() -> new BadCredentialsException("Nieprawidłowe dane logowania"));

        // Po udanym uwierzytelnieniu: zresetuj licznik rate limit
        loginRateLimiter.reset(ip);

        String accessToken = jwtService.issueAccessToken(user, getTenantName(user), !user.isMfaEnabled());
        String refreshTokenValue = createRefreshToken(user);
        long ttl = jwtService.getAccessTokenTtlSeconds();

        // Priorytet: zmiana hasła wymagana przed weryfikacją MFA
        if (user.isPasswordResetRequired()) {
            log.info("[Auth] Logowanie z wymaganą zmianą hasła: userId={}, tenantId={}",
                    user.getId(), user.getTenantId());
            return LoginResponse.passwordResetRequired(accessToken, refreshTokenValue, ttl);
        }

        if (user.isMfaEnabled()) {
            log.info("[Auth] Logowanie z MFA: userId={}, tenantId={}", user.getId(), user.getTenantId());
            return LoginResponse.mfaRequired(accessToken, refreshTokenValue, ttl);
        }

        log.info("[Auth] Logowanie bez MFA: userId={}, tenantId={}", user.getId(), user.getTenantId());
        return LoginResponse.of(accessToken, refreshTokenValue, ttl);
    }

    // =========================================================================
    // Token Refresh (Rotation)
    // =========================================================================

    /**
     * Wymienia refresh token na nową parę access+refresh tokenów (token rotation).
     *
     * <p>Stary refresh token jest unieważniany natychmiast po wystawieniu nowego.
     * Jeśli stary token zostanie użyty ponownie (replay attack), jest już w stanie
     * {@code revoked=true} i żądanie zostanie odrzucone.
     *
     * @param request zawiera wartość refresh tokenu
     * @return nowa para access+refresh tokenów
     * @throws InvalidTokenException gdy token wygasł, unieważniony lub nieistniejący
     */
    @Transactional
    public LoginResponse refresh(RefreshRequest request) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> {
                    log.warn("[Auth] Nieznany refresh token: {}", maskToken(request.refreshToken()));
                    return new InvalidTokenException("Nieprawidłowy refresh token");
                });

        if (!refreshToken.isValid()) {
            log.warn("[Auth] Użyto wygasłego lub unieważnionego refresh tokenu. userId={}",
                    refreshToken.getUserId());
            throw new InvalidTokenException("Refresh token wygasł lub został unieważniony");
        }

        // Pobierz użytkownika i unieważnij stary token (rotation)
        AppUser user = appUserRepository.findById(refreshToken.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Użytkownik nie istnieje"));

        if (!user.isActive()) {
            log.warn("[Auth] Próba refresh dla nieaktywnego użytkownika: userId={}", user.getId());
            throw new DisabledException("Konto użytkownika jest nieaktywne");
        }

        // Unieważnij stary refresh token
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        // Wystaw nową parę tokenów.
        // mfaVerified = false – refresh token nie zastępuje weryfikacji TOTP.
        // Przy włączonym MFA klient musi ponownie zweryfikować kod TOTP przez /api/auth/mfa/verify.
        // Wcześniejszy kod używał user.isMfaEnabled() co oznaczało, że użytkownik z MFA
        // otrzymywał token z mfaVerified=true bez podania kodu TOTP – luka bezpieczeństwa.
        String newAccessToken = jwtService.issueAccessToken(user, getTenantName(user), false);
        String newRefreshTokenValue = createRefreshToken(user);

        log.info("[Auth] Token refresh (rotation): userId={}, tenantId={}", user.getId(), user.getTenantId());
        return LoginResponse.of(newAccessToken, newRefreshTokenValue, jwtService.getAccessTokenTtlSeconds());
    }

    // =========================================================================
    // Logout
    // =========================================================================

    /**
     * Wylogowuje użytkownika:
     * <ol>
     *   <li>Dodaje access token do blacklisty Redis (TTL = pozostały czas ważności)</li>
     *   <li>Unieważnia refresh token w bazie danych</li>
     * </ol>
     *
     * @param accessToken   surowy JWT access token (z nagłówka Authorization)
     * @param logoutRequest zawiera refresh token do unieważnienia
     */
    @Transactional
    public void logout(String accessToken, LogoutRequest logoutRequest) {
        // Krok 1: Blacklista access tokenu w Redis
        blacklistAccessToken(accessToken);

        // Krok 2: Unieważnij refresh token w DB
        int revoked = refreshTokenRepository.revokeByToken(logoutRequest.refreshToken());
        if (revoked == 0) {
            log.warn("[Auth] Logout: refresh token nieznany lub już unieważniony");
        }

        // Krok 3: Jeśli wylogowuje się agent – ustaw status OFFLINE
        setAgentOfflineOnLogout();

        log.info("[Auth] Logout wykonany pomyślnie");
    }

    /**
     * Wylogowuje użytkownika ze wszystkich urządzeń (unieważnia wszystkie refresh tokeny).
     *
     * @param accessToken surowy JWT access token
     * @param userId      UUID użytkownika
     */
    @Transactional
    public void logoutAll(String accessToken, UUID userId) {
        blacklistAccessToken(accessToken);
        int count = refreshTokenRepository.revokeAllByUserId(userId);
        setAgentOfflineOnLogout();
        log.info("[Auth] Logout ze wszystkich urządzeń: userId={}, unieważniono {} tokenów", userId, count);
    }

    /**
     * Ustawia status agenta na OFFLINE przy wylogowaniu.
     * Wywołuje UserService.updateStatus(), który zapisuje status w DB, Redis i publikuje event RabbitMQ.
     * Operacja jest best-effort – błąd nie przerywa procesu wylogowania.
     */
    private void setAgentOfflineOnLogout() {
        try {
            String role = TenantContext.getUserRole();
            if (!"AGENT".equalsIgnoreCase(role)) {
                return;
            }
            UUID userId   = TenantContext.getUserId();
            UUID tenantId = TenantContext.getTenantId();
            userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.OFFLINE), tenantId);
            log.info("[Auth] Status agenta ustawiony na OFFLINE przy wylogowaniu: userId={}", userId);
        } catch (Exception e) {
            log.warn("[Auth] Nie udało się ustawić statusu OFFLINE przy wylogowaniu: {}", e.getMessage());
        }
    }

    // =========================================================================
    // MFA Setup
    // =========================================================================

    /**
     * Generuje TOTP secret i QR code URI dla setup MFA.
     *
     * <p>Secret jest zapisywany do bazy danych, ale {@code mfaEnabled} pozostaje {@code false}
     * do czasu pomyślnej weryfikacji kodu TOTP przez endpoint {@code /api/auth/mfa/verify}.
     *
     * @param userId      UUID zalogowanego użytkownika
     * @param userEmail   email użytkownika (etykieta w aplikacji MFA)
     * @return secret (Base32) + QR code data URI
     * @throws IllegalStateException gdy MFA już aktywne
     */
    @Transactional
    public MfaSetupResponse setupMfa(UUID userId, String userEmail) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new InvalidOperationException("Użytkownik nie istnieje"));

        if (user.isMfaEnabled()) {
            log.warn("[MFA] Próba ponownego setup dla użytkownika z aktywnym MFA: userId={}", userId);
            throw new InvalidOperationException("MFA jest już aktywne dla tego konta. " +
                    "Aby zresetować MFA skontaktuj się z administratorem.");
        }

        String secret = mfaService.generateSecret();
        String qrCodeUri = mfaService.generateQrCodeDataUri(secret, userEmail);

        // Zapisz secret do DB (mfaEnabled pozostaje false – czeka na weryfikację)
        appUserRepository.updateMfaSecret(userId, secret);

        log.info("[MFA] Setup TOTP: userId={}", userId);
        return new MfaSetupResponse(secret, qrCodeUri);
    }

    // =========================================================================
    // MFA Verify
    // =========================================================================

    /**
     * Weryfikuje kod TOTP i wystawia nowy access token z {@code mfaVerified=true}.
     *
     * <p>Przy pierwszej weryfikacji (gdy {@code mfaEnabled=false}) aktywuje MFA na koncie.
     * Przy kolejnych weryfikacjach (po ponownym logowaniu) tylko wystawia nowy token.
     *
     * @param userId      UUID zalogowanego użytkownika
     * @param request     kod TOTP od użytkownika
     * @param oldAccessToken stary access token (z mfaVerified=false) – do blacklisty
     * @return nowy access token z {@code mfaVerified=true}
     * @throws BadCredentialsException gdy kod TOTP nieprawidłowy
     */
    @Transactional
    public String verifyMfa(UUID userId, MfaVerifyRequest request, String oldAccessToken) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Użytkownik nie istnieje"));

        if (user.getMfaSecret() == null || user.getMfaSecret().isBlank()) {
            log.warn("[MFA] Brak MFA secret dla userId={}. Setup wymagany przed weryfikacją.", userId);
            throw new InvalidOperationException("MFA nie zostało skonfigurowane. Wywołaj najpierw /api/auth/mfa/setup");
        }

        // Weryfikacja kodu TOTP z oknem tolerancji ±30s + ochrona przed replay attack
        if (!mfaService.verifyCode(user.getMfaSecret(), request.code(), userId)) {
            log.warn("[MFA] Nieprawidłowy lub już użyty kod TOTP dla userId={}", userId);
            throw new BadCredentialsException("Nieprawidłowy kod MFA");
        }

        // Pierwsza weryfikacja: aktywuj MFA na koncie
        if (!user.isMfaEnabled()) {
            appUserRepository.enableMfa(userId);
            user.setMfaEnabled(true);
            log.info("[MFA] MFA aktywowane dla userId={}", userId);
        }

        // Blacklista starego access tokenu (z mfaVerified=false)
        if (oldAccessToken != null) {
            blacklistAccessToken(oldAccessToken);
        }

        // Wystaw nowy access token z mfaVerified=true
        String newAccessToken = jwtService.issueAccessToken(user, getTenantName(user), true);
        log.info("[MFA] Weryfikacja TOTP pomyślna: userId={}", userId);
        return newAccessToken;
    }

    // =========================================================================
    // Change Password
    // =========================================================================

    /**
     * Zmienia hasło użytkownika i wystawia nową parę tokenów.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Weryfikacja aktualnego hasła przez bcrypt</li>
     *   <li>Walidacja siły nowego hasła (min 8 znaków, 1 cyfra, 1 wielka litera)</li>
     *   <li>Zapisanie nowego hasła i wyczyszczenie flagi {@code passwordResetRequired}</li>
     *   <li>Unieważnienie starego access tokenu (blacklista Redis)</li>
     *   <li>Unieważnienie wszystkich refresh tokenów użytkownika</li>
     *   <li>Wystawienie nowych tokenów</li>
     * </ol>
     *
     * @param userId         UUID zalogowanego użytkownika (z JWT)
     * @param tenantId       UUID tenanta (z JWT, do pobrania encji)
     * @param request        request z aktualnym i nowym hasłem
     * @param oldAccessToken stary access token do blacklisty
     * @return nowa para tokenów
     * @throws BadCredentialsException  gdy aktualne hasło jest nieprawidłowe
     * @throws IllegalArgumentException gdy nowe hasło nie spełnia wymagań siły
     */
    @Transactional
    public LoginResponse changePassword(UUID userId, UUID tenantId,
                                        ChangePasswordRequest request, String oldAccessToken) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Użytkownik nie istnieje"));

        // Weryfikacja aktualnego hasła
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.warn("[Auth] Zmiana hasła – nieprawidłowe aktualne hasło: userId={}", userId);
            throw new BadCredentialsException("Nieprawidłowe aktualne hasło");
        }

        // Walidacja siły nowego hasła: min 1 cyfra, min 1 wielka litera
        validatePasswordStrength(request.newPassword());

        // Zakoduj nowe hasło i zapisz
        String newHash = passwordEncoder.encode(request.newPassword());
        appUserRepository.updatePasswordAndClearReset(userId, newHash);

        // Unieważnij stary access token (blacklista Redis)
        blacklistAccessToken(oldAccessToken);

        // Unieważnij wszystkie refresh tokeny użytkownika (wymuszamy re-login na innych urządzeniach)
        int revokedCount = refreshTokenRepository.revokeAllByUserId(userId);
        log.debug("[Auth] Zmiana hasła: unieważniono {} refresh tokenów dla userId={}", revokedCount, userId);

        // Zaktualizuj lokalny stan encji (do wystawienia tokenu)
        user.setPasswordHash(newHash);
        user.setPasswordResetRequired(false);

        // Wystaw nowe tokeny
        String newAccessToken = jwtService.issueAccessToken(user, getTenantName(user), !user.isMfaEnabled());
        String newRefreshTokenValue = createRefreshToken(user);

        log.info("[Auth] Hasło zmienione pomyślnie: userId={}, tenantId={}", userId, tenantId);
        return LoginResponse.of(newAccessToken, newRefreshTokenValue, jwtService.getAccessTokenTtlSeconds());
    }

    // =========================================================================
    // Force Password Reset (Admin / Supervisor)
    // =========================================================================

    /**
     * Wymusza zmianę hasła przy następnym logowaniu docelowego użytkownika.
     *
     * <p>Uprawnienia:
     * <ul>
     *   <li>ADMIN – może resetować dowolnego użytkownika (cross-tenant)</li>
     *   <li>SUPERVISOR – może resetować tylko użytkowników własnego tenanta</li>
     * </ul>
     *
     * <p>Skutki: ustawia {@code passwordResetRequired=true} i unieważnia
     * wszystkie aktywne refresh tokeny (wymusza wylogowanie ze wszystkich urządzeń).
     *
     * @param targetUserId  UUID użytkownika do zresetowania
     * @param callerTenantId UUID tenanta wywołującego (z JWT)
     * @param callerRole    rola wywołującego ("ADMIN" lub "SUPERVISOR")
     * @throws IllegalArgumentException gdy użytkownik nie istnieje
     * @throws AccessDeniedException    gdy SUPERVISOR próbuje resetować użytkownika innego tenanta
     */
    @Transactional
    public void forcePasswordReset(UUID targetUserId, UUID callerTenantId, String callerRole) {
        AppUser target = appUserRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Użytkownik nie istnieje"));

        // SUPERVISOR może resetować tylko użytkowników swojego tenanta
        if ("SUPERVISOR".equals(callerRole) && !target.getTenantId().equals(callerTenantId)) {
            log.warn("[Auth] SUPERVISOR próbuje force-reset użytkownika innego tenanta: " +
                     "callerTenant={}, targetUserId={}, targetTenant={}",
                     callerTenantId, targetUserId, target.getTenantId());
            throw new AccessDeniedException("Supervisor może resetować hasła tylko agentów własnego tenanta");
        }

        target.setPasswordResetRequired(true);
        appUserRepository.save(target);

        int revokedCount = refreshTokenRepository.revokeAllByUserId(targetUserId);
        log.info("[Auth] Force password reset: targetUserId={}, by role={}, unieważniono {} tokenów",
                targetUserId, callerRole, revokedCount);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Tworzy i zapisuje nowy refresh token w bazie danych.
     */
    private String getTenantName(AppUser user) {
        return tenantService.findTenantEntity(user.getTenantId())
                .map(Tenant::getName)
                .orElse(user.getTenantId().toString());
    }

    private String createRefreshToken(AppUser user) {
        String tokenValue = jwtService.generateRefreshTokenValue();
        Instant expiresAt = jwtService.refreshTokenExpiresAt();

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(user.getId())
                .tenantId(user.getTenantId())
                .token(tokenValue)
                .expiresAt(expiresAt)
                .revoked(false)
                .build();

        refreshTokenRepository.save(refreshToken);
        log.debug("[Auth] Zapisano refresh token dla userId={}, expiresAt={}", user.getId(), expiresAt);
        return tokenValue;
    }

    /**
     * Dodaje access token do blacklisty Redis.
     *
     * <p>TTL wpisu Redis = rzeczywisty czas wygaśnięcia z claim {@code exp} tokenu.
     * Wcześniej używaliśmy {@code now + konfiguracja_ttl} jako przybliżenia – to błąd,
     * bo token wystawiony godzinę temu miałby w Redis TTL dłuższy niż jego faktyczna
     * ważność. Teraz pobieramy {@code expiresAt} bezpośrednio z {@link JwtClaims}.
     *
     * <p>Jeśli token jest już wygasły ({@code parseQuiet} zwraca null) – blacklista
     * nie jest potrzebna, token i tak zostanie odrzucony przez parser JWT.
     */
    private void blacklistAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            // parseQuiet zwraca null gdy token wygasł lub jest nieprawidłowy –
            // w takim przypadku nie trzeba blacklistować
            JwtClaims claims = jwtParser.parseQuiet(accessToken);
            if (claims != null) {
                Instant expiresAt;
                if (claims.expiresAt() != null) {
                    // Używamy rzeczywistego exp z tokenu (nie TTL z konfiguracji)
                    expiresAt = claims.expiresAt();
                } else {
                    // Fallback: token bez exp claim – używamy konfiguracji jako bezpieczne przybliżenie
                    log.warn("[Auth] Token nie ma claim exp – używam TTL z konfiguracji jako fallback");
                    expiresAt = Instant.now().plusSeconds(jwtService.getAccessTokenTtlSeconds());
                }
                tokenBlacklistService.blacklist(accessToken, expiresAt);
            }
        } catch (Exception e) {
            log.warn("[Auth] Nie udało się dodać tokenu do blacklisty: {}", e.getMessage());
        }
    }

    /**
     * Waliduje siłę hasła: min 8 znaków (sprawdzane przez Bean Validation),
     * min 1 cyfra, min 1 wielka litera.
     *
     * @param password hasło w plain text
     * @throws IllegalArgumentException gdy hasło nie spełnia wymagań
     */
    private void validatePasswordStrength(String password) {
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasUppercase = password.chars().anyMatch(Character::isUpperCase);

        if (!hasDigit || !hasUppercase) {
            throw new IllegalArgumentException(
                    "Nowe hasło nie spełnia wymagań: musi zawierać min. 1 cyfrę i min. 1 wielką literę"
            );
        }
    }

    /**
     * Maskuje token do logowania (pokazuje tylko pierwsze 8 znaków).
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 8) + "...";
    }

    // =========================================================================
    // Wyjątki domenowe
    // =========================================================================

    /** Rzucany gdy refresh token jest nieprawidłowy, wygasł lub został unieważniony. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
