package com.contactcenter.api.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO dla endpointu POST /api/auth/logout.
 *
 * <p>Klient wysyła aktualny access token (aby dodać go do blacklisty Redis)
 * oraz refresh token (aby go unieważnić w DB).
 *
 * @param refreshToken refresh token do unieważnienia w DB
 */
public record LogoutRequest(

        @NotBlank(message = "refreshToken jest wymagany")
        String refreshToken

) {}
