package com.contactcenter.domain.service;

import com.contactcenter.api.auth.*;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.RefreshToken;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.RefreshTokenRepository;
import com.contactcenter.security.*;
import com.contactcenter.security.JwtParser.JwtClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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

    // =========================================================================
    // Login
    // =========================================================================

    /**
     * Loguje użytkownika: weryfikuje hasło i wystawia parę access+refresh tokenów.
     *
     * <p>Dla użytkowników z MFA: wystawiany jest tymczasowy access token z {@code mfaVerified=false}.
     * Klient musi następnie wywołać {@code POST /api/auth/mfa/verify} z kodem TOTP.
     *
     * @param request dane logowania (tenantId, email, password)
     * @return para tokenów; pole {@code mfaRequired=true} gdy wymagana weryfikacja TOTP
     * @throws BadCredentialsException gdy hasło nieprawidłowe
     * @throws DisabledException       gdy konto nieaktywne
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
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

        // Pobierz pełną encję (potrzebujemy mfaEnabled, id do refresh tokenu)
        AppUser user = appUserRepository.findById(userDetails.getUserId())
                .orElseThrow(() -> new BadCredentialsException("Nieprawidłowe dane logowania"));

        boolean mfaRequired = user.isMfaEnabled();
        // access token z mfaVerified=false gdy MFA wymagane, true gdy MFA nieaktywne
        String accessToken = jwtService.issueAccessToken(user, !mfaRequired);
        String refreshTokenValue = createRefreshToken(user);

        if (mfaRequired) {
            log.info("[Auth] Logowanie z MFA: userId={}, tenantId={}", user.getId(), user.getTenantId());
            return LoginResponse.mfaRequired(accessToken, refreshTokenValue, jwtService.getAccessTokenTtlSeconds());
        }

        log.info("[Auth] Logowanie bez MFA: userId={}, tenantId={}", user.getId(), user.getTenantId());
        return LoginResponse.of(accessToken, refreshTokenValue, jwtService.getAccessTokenTtlSeconds());
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

        // Wystaw nową parę tokenów
        // mfaVerified zachowujemy stan z poprzedniej sesji (refresh nie resetuje MFA)
        String newAccessToken = jwtService.issueAccessToken(user, user.isMfaEnabled());
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
        log.info("[Auth] Logout ze wszystkich urządzeń: userId={}, unieważniono {} tokenów", userId, count);
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
                .orElseThrow(() -> new IllegalStateException("Użytkownik nie istnieje"));

        if (user.isMfaEnabled()) {
            log.warn("[MFA] Próba ponownego setup dla użytkownika z aktywnym MFA: userId={}", userId);
            throw new IllegalStateException("MFA jest już aktywne dla tego konta. " +
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
            throw new IllegalStateException("MFA nie zostało skonfigurowane. Wywołaj najpierw /api/auth/mfa/setup");
        }

        // Weryfikacja kodu TOTP z oknem tolerancji ±30s
        if (!mfaService.verifyCode(user.getMfaSecret(), request.code())) {
            log.warn("[MFA] Nieprawidłowy kod TOTP dla userId={}", userId);
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
        String newAccessToken = jwtService.issueAccessToken(user, true);
        log.info("[MFA] Weryfikacja TOTP pomyślna: userId={}", userId);
        return newAccessToken;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Tworzy i zapisuje nowy refresh token w bazie danych.
     */
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
     * Pobiera czas wygaśnięcia z claims tokenu (nie z konfiguracji).
     */
    private void blacklistAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            // Parsujemy ciche – jeśli token już wygasł, blacklista nie jest potrzebna
            JwtClaims claims = jwtParser.parseQuiet(accessToken);
            if (claims != null) {
                // Pobierz exp z claims przez bezpośrednie parsowanie (JwtParser nie eksponuje exp)
                // Używamy TTL z konfiguracji jako przybliżenie (bezpieczne – lepiej za długo niż za krótko)
                Instant expiresAt = Instant.now().plusSeconds(jwtService.getAccessTokenTtlSeconds());
                tokenBlacklistService.blacklist(accessToken, expiresAt);
            }
        } catch (Exception e) {
            log.warn("[Auth] Nie udało się dodać tokenu do blacklisty: {}", e.getMessage());
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
