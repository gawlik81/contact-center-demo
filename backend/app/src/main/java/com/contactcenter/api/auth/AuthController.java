package com.contactcenter.api.auth;

import com.contactcenter.api.auth.dto.ChangePasswordRequest;
import com.contactcenter.api.auth.dto.LoginRequest;
import com.contactcenter.api.auth.dto.LoginResponse;
import com.contactcenter.api.auth.dto.LogoutRequest;
import com.contactcenter.api.auth.dto.MfaSetupResponse;
import com.contactcenter.api.auth.dto.MfaVerifyRequest;
import com.contactcenter.api.auth.dto.RefreshRequest;
import com.contactcenter.domain.user.AuthService;
import com.contactcenter.security.AppUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Kontroler REST obsługujący autentykację i zarządzanie tokenami.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>{@code POST /api/auth/login}              – logowanie (publiczny)</li>
 *   <li>{@code POST /api/auth/refresh}             – odświeżenie tokenów (publiczny)</li>
 *   <li>{@code POST /api/auth/logout}              – wylogowanie (wymaga JWT)</li>
 *   <li>{@code POST /api/auth/logout-all}          – wylogowanie ze wszystkich urządzeń</li>
 *   <li>{@code GET  /api/auth/mfa/setup}           – generowanie TOTP secret + QR code</li>
 *   <li>{@code POST /api/auth/mfa/verify}          – weryfikacja kodu TOTP</li>
 *   <li>{@code POST /api/auth/change-password}     – zmiana hasła (wymaga JWT)</li>
 *   <li>{@code POST /api/auth/force-reset/{userId}} – wymuszenie zmiany hasła (ADMIN/SUPERVISOR)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Logowanie, refresh tokenów, MFA")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    // =========================================================================
    // Login
    // =========================================================================

    @PostMapping("/login")
    @Operation(
        summary = "Logowanie użytkownika",
        description = "Weryfikuje dane logowania i zwraca parę access/refresh tokenów. " +
                      "Gdy użytkownik ma aktywne MFA, pole mfaRequired=true – wymagana weryfikacja TOTP. " +
                      "Gdy wymagana zmiana hasła, pole passwordResetRequired=true – klient musi wywołać " +
                      "POST /api/auth/change-password. Rate limit: 5 prób / 15 min / IP.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Zalogowano pomyślnie"),
            @ApiResponse(responseCode = "400", description = "Nieprawidłowe dane wejściowe"),
            @ApiResponse(responseCode = "401", description = "Nieprawidłowe dane logowania"),
            @ApiResponse(responseCode = "429", description = "Zbyt wiele prób logowania")
        }
    )
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        String ip = httpRequest.getRemoteAddr();
        LoginResponse response = authService.login(request, ip);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Token Refresh
    // =========================================================================

    @PostMapping("/refresh")
    @Operation(
        summary = "Odświeżenie tokenów (token rotation)",
        description = "Wymienia refresh token na nową parę access/refresh tokenów. " +
                      "Stary refresh token jest natychmiast unieważniany.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Tokeny odświeżone"),
            @ApiResponse(responseCode = "401", description = "Nieprawidłowy lub wygasły refresh token")
        }
    )
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        LoginResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Logout
    // =========================================================================

    @PostMapping("/logout")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Wylogowanie",
        description = "Dodaje access token do blacklisty Redis i unieważnia refresh token w DB.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Wylogowano pomyślnie"),
            @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token")
        }
    )
    public ResponseEntity<Void> logout(
            @Valid @RequestBody LogoutRequest request,
            HttpServletRequest httpRequest
    ) {
        String accessToken = extractBearerToken(httpRequest);
        authService.logout(accessToken, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Wylogowanie ze wszystkich urządzeń",
        description = "Unieważnia wszystkie aktywne refresh tokeny użytkownika.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Wylogowano ze wszystkich urządzeń"),
            @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token")
        }
    )
    public ResponseEntity<Void> logoutAll(
            @AuthenticationPrincipal AppUserDetails userDetails,
            HttpServletRequest httpRequest
    ) {
        String accessToken = extractBearerToken(httpRequest);
        authService.logoutAll(accessToken, userDetails.getUserId());
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // MFA
    // =========================================================================

    @GetMapping("/mfa/setup")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Inicjalizacja MFA",
        description = "Generuje TOTP secret i URI QR code do zeskanowania w Google Authenticator. " +
                      "Po zeskanowaniu QR kodu wywołaj POST /api/auth/mfa/verify aby aktywować MFA.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Secret i QR code wygenerowane"),
            @ApiResponse(responseCode = "409", description = "MFA już aktywne"),
            @ApiResponse(responseCode = "401", description = "Brak autentykacji")
        }
    )
    public ResponseEntity<MfaSetupResponse> mfaSetup(
            @AuthenticationPrincipal AppUserDetails userDetails
    ) {
        MfaSetupResponse response = authService.setupMfa(
                userDetails.getUserId(),
                userDetails.getEmail()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/mfa/verify")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Weryfikacja kodu TOTP",
        description = "Weryfikuje 6-cyfrowy kod TOTP z aplikacji MFA. " +
                      "Przy pierwszym wywołaniu aktywuje MFA na koncie. " +
                      "Zwraca nowy access token z claim mfaVerified=true.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Kod TOTP prawidłowy"),
            @ApiResponse(responseCode = "400", description = "Nieprawidłowy format kodu"),
            @ApiResponse(responseCode = "401", description = "Nieprawidłowy kod TOTP")
        }
    )
    public ResponseEntity<Map<String, String>> mfaVerify(
            @Valid @RequestBody MfaVerifyRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails,
            HttpServletRequest httpRequest
    ) {
        String oldAccessToken = extractBearerToken(httpRequest);
        String newAccessToken = authService.verifyMfa(
                userDetails.getUserId(),
                request,
                oldAccessToken
        );
        return ResponseEntity.ok(Map.of(
                "accessToken", newAccessToken,
                "tokenType", "Bearer"
        ));
    }

    // =========================================================================
    // Change Password
    // =========================================================================

    @PostMapping("/change-password")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Zmiana hasła",
        description = "Zmienia hasło użytkownika. Wymagane: aktualne hasło (weryfikacja) i nowe hasło " +
                      "(min 8 znaków, min 1 cyfra, min 1 wielka litera). " +
                      "Po zmianie stary access token jest unieważniany – odpowiedź zawiera nowe tokeny. " +
                      "Wymagany jest ważny JWT (Bearer token).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Hasło zmienione, nowe tokeny"),
            @ApiResponse(responseCode = "401", description = "Nieprawidłowe aktualne hasło lub brak JWT"),
            @ApiResponse(responseCode = "422", description = "Walidacja lub zbyt słabe nowe hasło")
        }
    )
    public ResponseEntity<LoginResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails,
            HttpServletRequest httpRequest
    ) {
        String oldAccessToken = extractBearerToken(httpRequest);
        LoginResponse response = authService.changePassword(
                userDetails.getUserId(),
                userDetails.getTenantId(),
                request,
                oldAccessToken
        );
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Force Password Reset (Admin / Supervisor)
    // =========================================================================

    @PostMapping("/force-reset/{userId}")
    @SecurityRequirement(name = "Bearer Authentication")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Wymuś zmianę hasła użytkownika",
        description = "Ustawia flagę passwordResetRequired=true i unieważnia wszystkie sesje docelowego " +
                      "użytkownika. Przy następnym logowaniu użytkownik będzie zmuszony do zmiany hasła. " +
                      "ADMIN może resetować dowolnego użytkownika. SUPERVISOR – tylko użytkowników " +
                      "własnego tenanta.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Flaga ustawiona, sesje unieważnione"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień lub cross-tenant"),
            @ApiResponse(responseCode = "422", description = "Użytkownik nie istnieje")
        }
    )
    public ResponseEntity<Void> forcePasswordReset(
            @PathVariable UUID userId,
            @AuthenticationPrincipal AppUserDetails userDetails
    ) {
        authService.forcePasswordReset(
                userId,
                userDetails.getTenantId(),
                userDetails.getRole()
        );
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private String extractBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
