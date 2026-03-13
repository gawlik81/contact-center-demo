package com.contactcenter.api.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO dla endpointu POST /api/auth/refresh.
 *
 * @param refreshToken aktualny refresh token (UUID string)
 */
public record RefreshRequest(

        @NotBlank(message = "refreshToken jest wymagany")
        String refreshToken

) {}
