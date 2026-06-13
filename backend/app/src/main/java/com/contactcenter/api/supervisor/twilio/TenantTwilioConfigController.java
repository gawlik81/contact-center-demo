package com.contactcenter.api.supervisor.twilio;

import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigRequest;
import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigResponse;
import com.contactcenter.api.supervisor.twilio.dto.TwilioConnectionTestResult;
import com.contactcenter.api.supervisor.twilio.dto.TwilioPhoneNumberListResponse;
import com.contactcenter.domain.tenant.TenantTwilioConfigService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Kontroler REST zarządzający konfiguracją Twilio per-tenant.
 *
 * <p>Implementuje BE-057: TenantTwilioConfig REST API.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET    /api/supervisor/twilio-config        – pobierz konfigurację (204 gdy brak)</li>
 *   <li>PUT    /api/supervisor/twilio-config        – upsert konfiguracji</li>
 *   <li>DELETE /api/supervisor/twilio-config        – usuń konfigurację</li>
 *   <li>POST   /api/supervisor/twilio-config/test   – test połączenia z Twilio</li>
 * </ul>
 *
 * <p>Dostęp wyłącznie dla roli SUPERVISOR.
 * TenantId pobierany z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@RestController
@RequestMapping("/api/supervisor/twilio-config")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERVISOR')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Twilio Config", description = "Per-tenant konfiguracja Twilio – zarządzanie przez supervisora")
public class TenantTwilioConfigController {

    private final TenantTwilioConfigService configService;

    @GetMapping
    @Operation(
        summary = "Pobierz konfigurację Twilio",
        description = "Zwraca aktualną konfigurację Twilio tenanta z zamaskowanymi sekretami. " +
                      "Zwraca 204 gdy brak konfiguracji.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Konfiguracja istnieje"),
            @ApiResponse(responseCode = "204", description = "Brak konfiguracji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagana rola SUPERVISOR)")
        }
    )
    public ResponseEntity<TenantTwilioConfigResponse> getConfig() {
        UUID tenantId = TenantContext.getTenantId();
        return configService.getConfig(tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PutMapping
    @Operation(
        summary = "Zapisz konfigurację Twilio (upsert)",
        description = "Tworzy lub aktualizuje konfigurację Twilio dla tenanta. " +
                      "accountSid musi mieć format AC+32 znaki hex. " +
                      "phoneNumber opcjonalny, musi być w formacie E.164.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Konfiguracja zapisana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Nieprawidłowy format danych")
        }
    )
    public ResponseEntity<TenantTwilioConfigResponse> saveConfig(
            @Valid @RequestBody TenantTwilioConfigRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        TenantTwilioConfigResponse response = configService.saveConfig(tenantId, request);
        log.debug("[TwilioConfigController] PUT /api/supervisor/twilio-config → 200, tenant={}", tenantId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    @Operation(
        summary = "Usuń konfigurację Twilio",
        description = "Usuwa konfigurację Twilio tenanta.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Konfiguracja usunięta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Brak konfiguracji do usunięcia")
        }
    )
    public ResponseEntity<Void> deleteConfig() {
        UUID tenantId = TenantContext.getTenantId();
        configService.deleteConfig(tenantId);
        log.debug("[TwilioConfigController] DELETE /api/supervisor/twilio-config → 204, tenant={}", tenantId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/phone-numbers")
    @Operation(
        summary = "Pobierz listę aktywnych numerów Twilio",
        description = "Zwraca listę numerów telefonu przypisanych do konta Twilio tenanta. " +
                      "Wymaga skonfigurowanej konfiguracji Twilio per-tenant. " +
                      "Zwraca pustą listę gdy konto Twilio nie ma żadnych numerów.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista numerów (może być pusta)"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagana rola SUPERVISOR)"),
            @ApiResponse(responseCode = "404", description = "Brak konfiguracji Twilio dla tenanta"),
            @ApiResponse(responseCode = "502", description = "Twilio API niedostępne lub błąd autoryzacji")
        }
    )
    public ResponseEntity<TwilioPhoneNumberListResponse> listPhoneNumbers() {
        UUID tenantId = TenantContext.getTenantId();
        TwilioPhoneNumberListResponse response = configService.listActivePhoneNumbers(tenantId);
        log.debug("[TwilioConfigController] GET /api/supervisor/twilio-config/phone-numbers → 200, tenant={}, count={}",
                tenantId, response.phoneNumbers().size());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/test")
    @Operation(
        summary = "Testuj połączenie z Twilio",
        description = "Weryfikuje poprawność kredencjałów Twilio wywołując Twilio API. " +
                      "Zawsze zwraca 200 – wynik testu w polu success.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Wynik testu (success true/false)"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<TwilioConnectionTestResult> testConnection() {
        UUID tenantId = TenantContext.getTenantId();
        TwilioConnectionTestResult result = configService.testConnection(tenantId);
        log.debug("[TwilioConfigController] POST /api/supervisor/twilio-config/test → 200, tenant={}, success={}",
                tenantId, result.success());
        return ResponseEntity.ok(result);
    }
}
