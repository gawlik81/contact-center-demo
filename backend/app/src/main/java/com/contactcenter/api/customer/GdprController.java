package com.contactcenter.api.customer;

import com.contactcenter.domain.service.GdprService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Kontroler REST obsługujący operacje RODO (BE-031).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST /api/customers/{id}/gdpr/export    – eksport danych klienta (ZIP)</li>
 *   <li>POST /api/customers/{id}/gdpr/anonymize – anonimizacja klienta</li>
 * </ul>
 *
 * <p>Oba endpointy wymagają roli SUPERVISOR lub ADMIN.
 * TenantId pobierany z {@code TenantContext} (ustawiony przez {@code TenantFilter}).
 */
@Slf4j
@RestController
@RequestMapping("/api/customers/{id}/gdpr")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "GDPR", description = "Operacje RODO: eksport danych i anonimizacja klienta")
public class GdprController {

    private final GdprService gdprService;

    // =========================================================================
    // Eksport danych klienta (Art. 20)
    // =========================================================================

    @PostMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
            summary = "Eksport danych klienta (RODO Art. 20)",
            description = "Eksportuje wszystkie dane klienta do archiwum ZIP. " +
                          "Archiwum zawiera: customer.json (profil PII), contacts.json (historia kontaktów), " +
                          "audit_log.json (metadane eksportu). " +
                          "Wymaga roli ADMIN lub SUPERVISOR.",
            responses = {
                @ApiResponse(responseCode = "200", description = "Archiwum ZIP z danymi klienta"),
                @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymaga ADMIN lub SUPERVISOR)"),
                @ApiResponse(responseCode = "404", description = "Klient nie istnieje")
            }
    )
    public ResponseEntity<byte[]> exportCustomerData(
            @Parameter(description = "UUID klienta", required = true)
            @PathVariable UUID id
    ) {
        log.info("[GdprController] Export danych RODO: customerId={}", id);

        byte[] zipBytes = gdprService.exportCustomerData(id);

        String filename = "gdpr_export_" + id + ".zip";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(zipBytes.length))
                .body(zipBytes);
    }

    // =========================================================================
    // Anonimizacja klienta (Art. 17)
    // =========================================================================

    @PostMapping("/anonymize")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
            summary = "Anonimizuj klienta (RODO Art. 17 – prawo do bycia zapomnianym)",
            description = "Anonimizuje dane osobowe klienta: first_name='ANONYMIZED', last_name='ANONYMIZED', " +
                          "phone=[], email=[], is_deleted=true. " +
                          "Usuwa nagrania rozmów z S3. " +
                          "Rekord pozostaje w bazie (historia kontaktów zachowana dla celów rozliczeniowych). " +
                          "Operacja jest nieodwracalna. " +
                          "Wymaga roli ADMIN lub SUPERVISOR.",
            responses = {
                @ApiResponse(responseCode = "204", description = "Klient zanonimizowany"),
                @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymaga ADMIN lub SUPERVISOR)"),
                @ApiResponse(responseCode = "404", description = "Klient nie istnieje lub jest już zanonimizowany")
            }
    )
    public ResponseEntity<Void> anonymizeCustomer(
            @Parameter(description = "UUID klienta do anonimizacji", required = true)
            @PathVariable UUID id
    ) {
        log.info("[GdprController] Anonimizacja klienta (RODO): customerId={}", id);

        gdprService.anonymizeCustomer(id);

        return ResponseEntity.noContent().build();
    }
}
