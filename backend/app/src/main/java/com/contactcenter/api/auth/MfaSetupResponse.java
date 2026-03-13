package com.contactcenter.api.auth;

/**
 * Response DTO dla endpointu GET /api/auth/mfa/setup.
 *
 * @param secret     TOTP secret zakodowany w Base32 (do ręcznego wpisu w aplikacji MFA)
 * @param qrCodeUri  data URI z obrazem QR code (skanowany przez Google Authenticator / Authy)
 */
public record MfaSetupResponse(
        String secret,
        String qrCodeUri
) {}
