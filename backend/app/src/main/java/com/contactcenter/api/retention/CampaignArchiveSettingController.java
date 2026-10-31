package com.contactcenter.api.retention;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.retention.CampaignArchiveSettingService;
import com.contactcenter.domain.retention.dto.CampaignArchivePreviewDto;
import com.contactcenter.domain.retention.dto.CampaignArchiveSettingDto;
import com.contactcenter.domain.retention.dto.UpdateCampaignArchiveSettingRequest;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Okres archiwizacji kontaktów zakończonych kampanii per tenant (BE-151, EPIC-30) — osobny zasób
 * pod {@code /api/tenants/{tenantId}/retention/campaign-archive} (model danych B z DB-084:
 * tabela {@code tenant_campaign_archive_setting}, brak wiersza = 180 dni).
 *
 * <p>Osobny kontroler (a nie kolejne metody w {@link RetentionController}), żeby nie zmieniać
 * konstruktora/kontraktu istniejącego kontrolera polityk. Bezpieczeństwo jak tam: ADMIN
 * ({@code @PreAuthorize} + reguła {@code /api/tenants/*&#47;retention/**} w {@code SecurityConfig},
 * więc BRAK nowych publicznych ścieżek) oraz {@link #assertOwnTenant} — {@code tenantId} z URL
 * musi być tenantem z JWT (403 {@link CrossTenantAccessException}).
 *
 * <p>Błędy walidacji ({@code null}, tekst, poza [30,730]) = HTTP 422, także dla nieczytelnego body
 * i nienumerycznego parametru zapytania (lokalne handlery poniżej — globalny handler zwróciłby dla
 * nieczytelnego body 500, a dla złego typu parametru 400).
 */
@Slf4j
@RestController
@RequestMapping("/api/tenants/{tenantId}/retention/campaign-archive")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Retention Management")
public class CampaignArchiveSettingController {

    private static final String ERROR_BASE_URI = "https://contactcenter.io/errors/";
    private static final String TENANT_ID_DESCRIPTION = "UUID tenanta (musi być zgodny z tenantem z JWT)";
    private static final String FORBIDDEN_DESCRIPTION = "Brak roli ADMIN lub {tenantId} != własny tenant";

    private final CampaignArchiveSettingService service;

    @GetMapping
    @Operation(
            summary = "Okres archiwizacji kontaktów zakończonych kampanii",
            description = "Zwraca okres EFEKTYWNY w DNIACH (180, gdy tenant nie ma jawnego ustawienia — "
                    + "isDefault=true) wraz z zakresem (30-730). Po upływie okresu od zakończenia kampanii "
                    + "(COMPLETED/STOPPED) CampaignArchiveJob przenosi jej kontakty do archiwum — znikają "
                    + "z listy kontaktów, statystyk i dialera.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Okres efektywny: "
                            + "{\"archiveAfterDays\":180,\"isDefault\":true,\"defaultDays\":180,"
                            + "\"minDays\":30,\"maxDays\":730,...}"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION)
            }
    )
    public ResponseEntity<CampaignArchiveSettingDto> get(
            @Parameter(description = TENANT_ID_DESCRIPTION) @PathVariable UUID tenantId) {
        assertOwnTenant(tenantId);
        return ResponseEntity.ok(service.get(tenantId));
    }

    @PutMapping
    @Operation(
            summary = "Ustaw okres archiwizacji kontaktów zakończonych kampanii",
            description = "Ustawia okres w DNIACH (30-730). UWAGA: SKRÓCENIE okresu powoduje masową archiwizację "
                    + "przy najbliższym przebiegu CampaignArchiveJob — kontakty wszystkich kampanii zakończonych "
                    + "dawniej niż nowy okres znikną z UI/statystyk/dialera. Skutek sprawdzisz wcześniej przez "
                    + "GET .../campaign-archive/preview. Zmiana efektywnej wartości trafia do audit_log "
                    + "(CAMPAIGN_ARCHIVE_PERIOD_CHANGED, stara/nowa wartość, flaga skrócenia); PUT z wartością "
                    + "już obowiązującą nie zapisuje zmiany ani audytu.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Okres zapisany (stan po zapisie)"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
                    @ApiResponse(responseCode = "422",
                            description = "archiveAfterDays null/nienumeryczny/ułamkowy/poza zakresem 30-730")
            }
    )
    public ResponseEntity<CampaignArchiveSettingDto> update(
            @Parameter(description = TENANT_ID_DESCRIPTION) @PathVariable UUID tenantId,
            @Valid @RequestBody UpdateCampaignArchiveSettingRequest request) {
        assertOwnTenant(tenantId);
        return ResponseEntity.ok(service.update(tenantId, request.archiveAfterDays(), TenantContext.getUserId()));
    }

    @GetMapping("/preview")
    @Operation(
            summary = "Podgląd skutku zmiany okresu archiwizacji (tylko odczyt)",
            description = "Zwraca, ile kampanii COMPLETED/STOPPED i kontaktów z campaign_contact zostałoby "
                    + "zarchiwizowanych przy najbliższym przebiegu dla okresu archiveAfterDays (dni), oraz różnicę "
                    + "względem okresu bieżącego. Nic nie zapisuje i nie uruchamia archiwizacji.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Podgląd skutku"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
                    @ApiResponse(responseCode = "422",
                            description = "archiveAfterDays brak/nienumeryczny/poza zakresem 30-730")
            }
    )
    public ResponseEntity<CampaignArchivePreviewDto> preview(
            @Parameter(description = TENANT_ID_DESCRIPTION) @PathVariable UUID tenantId,
            // required=false po stronie Springa celowo: brak parametru ma dać 422 (walidacja serwisu),
            // a nie 400 z globalnego handlera; w kontrakcie OpenAPI parametr jest WYMAGANY.
            @Parameter(description = "Okres do przetestowania, w DNIACH (30-730)", example = "30", required = true)
            @RequestParam(required = false) Integer archiveAfterDays) {
        assertOwnTenant(tenantId);
        return ResponseEntity.ok(service.preview(tenantId, archiveAfterDays));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return unprocessable("Pole archiveAfterDays musi być liczbą całkowitą z zakresu 30-730 (dni)");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        if ("archiveAfterDays".equals(ex.getName())) {
            return unprocessable("Parametr archiveAfterDays musi być liczbą całkowitą z zakresu 30-730 (dni)");
        }
        // inne parametry (np. {tenantId} nie-UUID): zachowanie globalne, 400
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(ERROR_BASE_URI + "invalid-parameter-type"));
        problem.setTitle("Nieprawidłowy format parametru");
        problem.setDetail("Parametr '" + ex.getName() + "' ma nieprawidłowy format.");
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.badRequest().body(problem);
    }

    private static ResponseEntity<ProblemDetail> unprocessable(String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(ERROR_BASE_URI + "validation-failed"));
        problem.setTitle("Walidacja danych wejściowych nie powiodła się");
        problem.setDetail(detail);
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** Jedyna bariera dla {@code {tenantId}} z URL (serwis nie weryfikuje własności tenanta). */
    private void assertOwnTenant(UUID tenantId) {
        UUID callerTenantId = TenantContext.getTenantId();
        if (!tenantId.equals(callerTenantId)) {
            log.warn("[CampaignArchiveSettingController][Security] Próba cross-tenant: "
                    + "pathTenantId={}, callerTenantId={}", tenantId, callerTenantId);
            throw new CrossTenantAccessException(tenantId, callerTenantId);
        }
    }
}
