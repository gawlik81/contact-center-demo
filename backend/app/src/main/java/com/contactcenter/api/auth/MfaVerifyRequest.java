package com.contactcenter.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO dla endpointu POST /api/auth/mfa/verify.
 *
 * @param code 6-cyfrowy kod TOTP z aplikacji Google Authenticator / Authy
 */
public record MfaVerifyRequest(

        @NotBlank(message = "Kod TOTP jest wymagany")
        @Pattern(regexp = "\\d{6}", message = "Kod TOTP musi składać się z 6 cyfr")
        String code

) {}
