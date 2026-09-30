package com.contactcenter.api.customer;

import com.contactcenter.api.customer.dto.AnonymizePreviewResponse;
import com.contactcenter.domain.gdpr.GdprService;
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
 * Kontroler REST obsługujący operacje RODO (BE-031, rozszerzony BE-129).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST /api/customers/{id}/gdpr/export            – eksport danych klienta (ZIP + manifest S3)</li>
 *   <li>POST /api/customers/{id}/gdpr/anonymize          – anonimizacja klienta (funkcja SQL {@code anonymize_customer})</li>
 *   <li>GET  /api/customers/{id}/gdpr/anonymize/preview  – podgląd anonimizacji (BE-129, bez efektów ubocznych)</li>
 * </ul>
 *
 * <p>Wszystkie endpointy wymagają roli SUPERVISOR lub ADMIN.
 * TenantId pobierany z {@code TenantContext} (ustawiony przez {@code TenantFilter}).
 *
 * <p>{@code DELETE /api/customers/{id}} ({@code CustomerController}) deleguje od BE-129 do tej samej
 * implementacji {@code GdprService#anonymizeCustomer} co {@code POST .../gdpr/anonymize} — identyczny
 * efekt w bazie.
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
            summary = "Eksport danych klienta (RODO Art. 15/20)",
            description = "Eksportuje wszystkie dane klienta do archiwum ZIP (bez ucięcia — pełny zbiór " +
                          "podmiotu, w tym dopasowania po znormalizowanym telefonie/e-mailu). Archiwum " +
                          "zawiera: customer.json, contacts.json, scheduled_callbacks.json, " +
                          "campaign_records.json, email_messages.json, social_messages.json, " +
                          "transcriptions.json, ai_summaries.json oraz manifest.json (metadane eksportu " +
                          "i lista obiektów S3 z presigned URL-ami — pliki audio/załączniki NIE są " +
                          "wklejane do ZIP-a). " +
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
            description = "Anonimizuje dane osobowe klienta i całego powiązanego zbioru podmiotu (kontakty, " +
                          "callbacki, rekordy kampanii, wiadomości e-mail/social, transkrypcje/podsumowania " +
                          "AI — funkcja SQL anonymize_customer, DB-062) i sprząta powiązane obiekty S3 " +
                          "(nagrania, EML, załączniki) po commit. " +
                          "Rekord klienta pozostaje w bazie (historia kontaktów zachowana, zanonimizowana). " +
                          "Operacja jest nieodwracalna. " +
                          "Wymaga roli ADMIN lub SUPERVISOR.",
            responses = {
                @ApiResponse(responseCode = "204", description = "Klient zanonimizowany"),
                @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymaga ADMIN lub SUPERVISOR)"),
                @ApiResponse(responseCode = "404", description = "Klient nie istnieje"),
                @ApiResponse(responseCode = "409", description = "Klient ma powiązany rekord w trakcie realizacji połączenia (DIALING/PROCESSING)")
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

    // =========================================================================
    // Podgląd anonimizacji klienta (BE-129)
    // =========================================================================

    @GetMapping("/anonymize/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
            summary = "Podgląd anonimizacji klienta (RODO Art. 17) — bez efektów ubocznych",
            description = "Woła anonymize_customer w trybie podglądu (p_dry_run = TRUE): baza i S3 " +
                          "pozostają nietknięte, brak wpisu audytowego. Zwraca liczniki rekordów, " +
                          "które zostałyby zmienione/usunięte w każdej tabeli, rozmiar zbioru podmiotu " +
                          "(matchedByLink/matchedByIdentifier) oraz liczbę obiektów S3 do usunięcia. " +
                          "Wymaga roli ADMIN lub SUPERVISOR.",
            responses = {
                @ApiResponse(responseCode = "200", description = "Liczniki podglądu anonimizacji"),
                @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymaga ADMIN lub SUPERVISOR)"),
                @ApiResponse(responseCode = "404", description = "Klient nie istnieje")
            }
    )
    public ResponseEntity<AnonymizePreviewResponse> previewAnonymizeCustomer(
            @Parameter(description = "UUID klienta", required = true)
            @PathVariable UUID id
    ) {
        log.info("[GdprController] Podgląd anonimizacji RODO: customerId={}", id);

        AnonymizePreviewResponse preview = gdprService.previewAnonymizeCustomer(id);

        return ResponseEntity.ok(preview);
    }
}
