package com.contactcenter.domain.user;

import com.contactcenter.api.auth.dto.ChangePasswordRequest;
import com.contactcenter.api.auth.dto.LoginRequest;
import com.contactcenter.api.auth.dto.LoginResponse;
import com.contactcenter.api.auth.dto.LogoutRequest;
import com.contactcenter.api.auth.dto.MfaSetupResponse;
import com.contactcenter.api.auth.dto.MfaVerifyRequest;
import com.contactcenter.api.auth.dto.RefreshRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

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
public interface AuthService {

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
    LoginResponse login(LoginRequest request, String ip);

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
    LoginResponse refresh(RefreshRequest request);

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
    void logout(String accessToken, LogoutRequest logoutRequest);

    /**
     * Wylogowuje użytkownika ze wszystkich urządzeń (unieważnia wszystkie refresh tokeny).
     *
     * @param accessToken surowy JWT access token
     * @param userId      UUID użytkownika
     */
    void logoutAll(String accessToken, UUID userId);

    /**
     * Generuje TOTP secret i QR code URI dla setup MFA.
     *
     * <p>Secret jest zapisywany do bazy danych, ale {@code mfaEnabled} pozostaje {@code false}
     * do czasu pomyślnej weryfikacji kodu TOTP przez endpoint {@code /api/auth/mfa/verify}.
     *
     * @param userId    UUID zalogowanego użytkownika
     * @param userEmail email użytkownika (etykieta w aplikacji MFA)
     * @return secret (Base32) + QR code data URI
     * @throws IllegalStateException gdy MFA już aktywne
     */
    MfaSetupResponse setupMfa(UUID userId, String userEmail);

    /**
     * Weryfikuje kod TOTP i wystawia nowy access token z {@code mfaVerified=true}.
     *
     * <p>Przy pierwszej weryfikacji (gdy {@code mfaEnabled=false}) aktywuje MFA na koncie.
     * Przy kolejnych weryfikacjach (po ponownym logowaniu) tylko wystawia nowy token.
     *
     * @param userId         UUID zalogowanego użytkownika
     * @param request        kod TOTP od użytkownika
     * @param oldAccessToken stary access token (z mfaVerified=false) – do blacklisty
     * @return nowy access token z {@code mfaVerified=true}
     * @throws BadCredentialsException gdy kod TOTP nieprawidłowy
     */
    String verifyMfa(UUID userId, MfaVerifyRequest request, String oldAccessToken);

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
    LoginResponse changePassword(UUID userId, UUID tenantId, ChangePasswordRequest request, String oldAccessToken);

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
     * @param targetUserId   UUID użytkownika do zresetowania
     * @param callerTenantId UUID tenanta wywołującego (z JWT)
     * @param callerRole     rola wywołującego ("ADMIN" lub "SUPERVISOR")
     * @throws IllegalArgumentException gdy użytkownik nie istnieje
     * @throws AccessDeniedException    gdy SUPERVISOR próbuje resetować użytkownika innego tenanta
     */
    void forcePasswordReset(UUID targetUserId, UUID callerTenantId, String callerRole);
}
