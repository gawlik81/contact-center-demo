package com.contactcenter.api.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO dla endpointu POST /api/auth/login.
 *
 * <p>Pola opcjonalne ({@code @JsonInclude(NON_NULL)}):
 * <ul>
 *   <li>{@code mfaRequired} – gdy true, klient musi wywołać /api/auth/mfa/verify
 *       przed uzyskaniem pełnego access tokenu</li>
 *   <li>{@code passwordResetRequired} – gdy true, klient musi wywołać
 *       /api/auth/change-password przed dalszym korzystaniem z API</li>
 * </ul>
 *
 * @param accessToken           JWT access token (RS256, 15 min TTL)
 * @param refreshToken          losowy UUID token do odświeżenia sesji (7 dni TTL)
 * @param tokenType             zawsze "Bearer"
 * @param expiresIn             czas wygaśnięcia access tokenu w sekundach
 * @param mfaRequired           true gdy użytkownik ma włączone MFA i musi zweryfikować TOTP
 * @param passwordResetRequired true gdy administrator wymagał zmiany hasła przy następnym logowaniu
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        Boolean mfaRequired,
        Boolean passwordResetRequired
) {
    /** Konstruktor dla standardowej odpowiedzi bez MFA i bez wymaganej zmiany hasła. */
    public static LoginResponse of(String accessToken, String refreshToken, long expiresIn) {
        return new LoginResponse(accessToken, refreshToken, "Bearer", expiresIn, null, null);
    }

    /** Konstruktor dla odpowiedzi wymagającej MFA. */
    public static LoginResponse mfaRequired(String accessToken, String refreshToken, long expiresIn) {
        return new LoginResponse(accessToken, refreshToken, "Bearer", expiresIn, true, null);
    }

    /**
     * Konstruktor dla odpowiedzi gdy wymagana jest zmiana hasła.
     *
     * <p>Access token jest wystawiony aby FE mógł wywołać POST /api/auth/change-password
     * (który wymaga ważnego JWT). Klient powinien wymusić ekran zmiany hasła przed
     * dalszą nawigacją.
     */
    public static LoginResponse passwordResetRequired(String accessToken, String refreshToken, long expiresIn) {
        return new LoginResponse(accessToken, refreshToken, "Bearer", expiresIn, null, true);
    }
}
