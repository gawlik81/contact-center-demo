package com.contactcenter.api.campaign;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.campaign.dto.CampaignContactResponse;
import com.contactcenter.api.campaign.dto.ImportJobStatusResponse;
import com.contactcenter.domain.model.ImportJobStatus;
import com.contactcenter.domain.repository.CampaignContactRepository;
import com.contactcenter.domain.service.CampaignImportService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * Kontroler REST dla importu CSV kontaktów kampanii (BE-023).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST  /api/campaigns/{id}/contacts/import       – inicjuje asynchroniczny import CSV</li>
 *   <li>GET   /api/campaigns/{id}/import-status/{jobId} – polling statusu joba importu</li>
 * </ul>
 *
 * <p>Import działa asynchronicznie: POST zwraca jobId natychmiast,
 * klient polluje GET co kilka sekund aż status będzie COMPLETED lub FAILED_PARTIAL.
 *
 * <p>Dostęp: SUPERVISOR i ADMIN.
 */
@Slf4j
@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Campaigns", description = "Zarządzanie kampaniami wychodzącymi (outbound)")
public class CampaignImportController {

    private final CampaignImportService campaignImportService;
    private final CampaignContactRepository campaignContactRepository;

    // =========================================================================
    // POST – inicjacja importu CSV
    // =========================================================================

    /**
     * Inicjuje asynchroniczny import kontaktów kampanii z pliku CSV.
     *
     * <p>Plik jest walidowany synchronicznie (rozmiar, rozszerzenie, przynależność kampanii),
     * następnie przetwarzany w tle. Odpowiedź zawiera {@code jobId} do pollingu statusu.
     *
     * <p>Format CSV:
     * <ul>
     *   <li>Z nagłówkiem: {@code phone,first_name,last_name,custom_field_1,...}</li>
     *   <li>Bez nagłówka: kolumna 0=phone, 1=first_name, 2=last_name</li>
     * </ul>
     *
     * <p>Walidacja telefonu: format E.164 ({@code +[1-15 cyfr]}).
     * Rekordy z błędnym telefonem są odrzucane i raportowane w statusie joba.
     */
    @PostMapping(
        value = "/{id}/contacts/import",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Importuj kontakty z CSV",
        description = """
                Przyjmuje plik CSV z listą kontaktów i inicjuje asynchroniczny import.

                Zwraca jobId do pollingu statusu przez GET /api/campaigns/{id}/import-status/{jobId}.

                Format pliku CSV:
                - Z nagłówkiem: phone,first_name,last_name,custom_field_1,custom_field_2
                - Bez nagłówka: kolumna 0=phone, 1=first_name, 2=last_name

                Walidacja telefonu: format E.164 (+[1-15 cyfr]).
                Rekordy z błędnym telefonem są odrzucane i raportowane.

                Limity: max 50 MB, tylko .csv.
                """,
        responses = {
            @ApiResponse(
                responseCode = "202",
                description = "Import zainicjowany – zwraca jobId do pollingu statusu",
                content = @Content(schema = @Schema(example = "{\"jobId\": \"uuid\"}"))
            ),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji pliku (za duży, złe rozszerzenie)"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Kampania nie istnieje lub należy do innego tenanta")
        }
    )
    public ResponseEntity<Map<String, String>> importContacts(
            @Parameter(description = "UUID kampanii") @PathVariable UUID id,

            @Parameter(description = "Plik CSV z kontaktami (max 50 MB)", required = true)
            @RequestPart("file") MultipartFile file,

            @Parameter(description = "Pomiń duplikaty po numerze telefonu (domyślnie true)")
            @RequestParam(defaultValue = "true") boolean skipDuplicates,

            @Parameter(description = "Separator kolumn CSV (domyślnie przecinek)")
            @RequestParam(defaultValue = ",") String columnSeparator,

            @Parameter(description = "Znak cytowania stringów (domyślnie cudzysłów, pusty = brak)")
            @RequestParam(defaultValue = "\"") String quoteChar,

            @Parameter(description = "Mapowanie kolumn CSV: JSON {\"phone\":2,\"first_name\":0,...}. " +
                    "Gdy podane, nadpisuje auto-detekcję nagłówków.")
            @RequestParam(required = false) String columnMapping
    ) {
        UUID tenantId = TenantContext.getTenantId();

        log.info("[CampaignImport] POST /api/campaigns/{}/contacts/import: " +
                "tenant={}, fileName={}, fileSize={}B, skipDuplicates={}, separator='{}', quoteChar='{}'",
                id, tenantId,
                file.getOriginalFilename(),
                file.getSize(),
                skipDuplicates,
                columnSeparator,
                quoteChar);

        UUID jobId = campaignImportService.initiateImport(id, file, skipDuplicates, columnSeparator, quoteChar, columnMapping);

        log.debug("[CampaignImport] Import zainicjowany: campaignId={}, jobId={}", id, jobId);

        return ResponseEntity
                .accepted()
                .body(Map.of("jobId", jobId.toString()));
    }

    // =========================================================================
    // GET – polling statusu joba
    // =========================================================================

    /**
     * Pobiera aktualny status asynchronicznego joba importu CSV.
     *
     * <p>Klient powinien pollować co kilka sekund aż pole {@code status}
     * przyjmie wartość {@code COMPLETED} lub {@code FAILED_PARTIAL}.
     *
     * <p>Status jest przechowywany w Redis przez 1 godzinę od zakończenia joba.
     * Po tym czasie endpoint zwróci 404.
     */
    @GetMapping("/{id}/import-status/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Status joba importu CSV",
        description = """
                Zwraca aktualny stan przetwarzania importu CSV.

                Stany joba:
                - QUEUED: job zarejestrowany, oczekuje na przetworzenie
                - PROCESSING: import w toku (processedRows/totalRows pokazuje postęp)
                - COMPLETED: import zakończony sukcesem (wszystkie rekordy przetworzone)
                - FAILED_PARTIAL: import zakończony, ale część rekordów odrzucona

                Status dostępny przez 1 godzinę od zakończenia joba.
                """,
        responses = {
            @ApiResponse(responseCode = "200", description = "Status joba"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Job nie istnieje lub wygasł (TTL 1h)")
        }
    )
    public ResponseEntity<ImportJobStatusResponse> getImportStatus(
            @Parameter(description = "UUID kampanii") @PathVariable UUID id,
            @Parameter(description = "UUID joba zwrócony przez POST .../contacts/import") @PathVariable UUID jobId
    ) {
        ImportJobStatus status = campaignImportService.getJobStatus(jobId);

        if (status == null) {
            log.debug("[CampaignImport] GET import-status/{}: job nie istnieje w Redis", jobId);
            return ResponseEntity.notFound().build();
        }

        // Walidacja cross-campaign: job musi należeć do tej kampanii
        if (!id.equals(status.getCampaignId())) {
            log.warn("[CampaignImport] Cross-campaign access: jobId={} należy do campaignId={}, " +
                    "żądano dla campaignId={}", jobId, status.getCampaignId(), id);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(ImportJobStatusResponse.from(status));
    }

    // =========================================================================
    // GET – paginowana lista kontaktów kampanii
    // =========================================================================

    /**
     * Zwraca paginowaną listę kontaktów zaimportowanych do kampanii.
     *
     * <p>Pozwala supervisorowi/adminowi przeglądać rekordy importu wraz z ich
     * aktualnym statusem przetwarzania przez dialer.
     */
    @GetMapping("/{id}/contacts")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Lista kontaktów kampanii",
        description = """
                Zwraca paginowaną listę kontaktów zaimportowanych do kampanii.

                Opcjonalny filtr `status` ogranicza wyniki do rekordów o danym statusie:
                - PENDING: rekord oczekuje na wywołanie przez dialer
                - CALLED: dialer wykonał połączenie
                - FAILED: połączenie nieudane (szczegóły w polu errorMessage)
                - SKIPPED: rekord pominięty (np. nieprawidłowy telefon)

                Domyślna kolejność: od najnowszych (created_at DESC).
                Maksymalny rozmiar strony: 200.
                """,
        responses = {
            @ApiResponse(responseCode = "200", description = "Paginowana lista kontaktów"),
            @ApiResponse(responseCode = "400", description = "Nieprawidłowe parametry paginacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<PagedResponse<CampaignContactResponse>> listContacts(
            @Parameter(description = "UUID kampanii") @PathVariable UUID id,

            @Parameter(description = "Numer strony (0-based, domyślnie 0)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Rozmiar strony (domyślnie 50, max 200)")
            @RequestParam(defaultValue = "50") int size,

            @Parameter(description = "Filtr statusu: PENDING, CALLED, FAILED, SKIPPED (opcjonalny)")
            @RequestParam(required = false) String status
    ) {
        UUID tenantId = TenantContext.getTenantId();

        log.debug("[CampaignImport] GET /api/campaigns/{}/contacts: " +
                "tenant={}, page={}, size={}, status={}",
                id, tenantId, page, size, status);

        PagedResponse<CampaignContactResponse> response =
                campaignContactRepository.findByCampaign(tenantId, id, status, page, size);

        return ResponseEntity.ok(response);
    }
}
