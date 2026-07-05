package com.contactcenter.api.customer;

import com.contactcenter.api.customer.dto.CustomerImportStatusResponse;
import com.contactcenter.domain.customer.CustomerImportService;
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
 * Kontroler REST dla asynchronicznego importu klientów z CSV (BE-026).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST /api/customers/import              – inicjuje import CSV, zwraca 202 + jobId</li>
 *   <li>GET  /api/customers/import/{jobId}      – polling statusu joba</li>
 *   <li>GET  /api/customers/import/{jobId}/errors – pobiera raport błędów jako CSV</li>
 * </ul>
 *
 * <p>Dostęp: SUPERVISOR i ADMIN.
 */
@Slf4j
@RestController
@RequestMapping("/api/customers/import")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Customer Management", description = "Zarządzanie profilami klientów końcowych – CRUD, fuzzy search, anonimizacja RODO")
public class CustomerImportController {

    private final CustomerImportService customerImportService;

    // =========================================================================
    // POST – inicjacja importu CSV
    // =========================================================================

    /**
     * Inicjuje asynchroniczny import klientów z pliku CSV.
     *
     * <p>Plik jest walidowany synchronicznie (rozmiar, rozszerzenie),
     * następnie przetwarzany w tle. Odpowiedź zawiera {@code jobId} do pollingu statusu.
     *
     * <p>Format CSV – kolumny mapowane przez {@code columnMapping}:
     * first_name, last_name, phone (wielokrotne wartości rozdzielone ;),
     * email (wielokrotne wartości rozdzielone ;), custom_fields (JSON).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Importuj klientów z CSV",
        description = """
                Przyjmuje plik CSV z listą klientów i inicjuje asynchroniczny import.

                Zwraca jobId do pollingu statusu przez GET /api/customers/import/{jobId}.

                Kolumny CSV: first_name, last_name, phone (wielokrotne wartości rozdzielone ;),
                email (wielokrotne wartości rozdzielone ;), custom_fields.

                columnMapping: JSON {"nagłówek_csv": "pole_systemu", ...}

                Deduplikacja:
                - SKIP      – pomija wiersz gdy klient z tym samym phone/email już istnieje
                - OVERWRITE – aktualizuje istniejącego klienta danymi z CSV

                Limity: max 50 MB, tylko .csv.
                """,
        responses = {
            @ApiResponse(
                responseCode = "202",
                description = "Import zainicjowany – zwraca jobId do pollingu statusu",
                content = @Content(schema = @Schema(example = "{\"jobId\": \"uuid\"}"))
            ),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji pliku"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymaga ADMIN lub SUPERVISOR)")
        }
    )
    public ResponseEntity<Map<String, String>> importCustomers(
            @Parameter(description = "Plik CSV z klientami (max 50 MB)", required = true)
            @RequestPart("file") MultipartFile file,

            @Parameter(description = "Strategia deduplikacji: SKIP (domyślnie) lub OVERWRITE")
            @RequestParam(name = "deduplication", defaultValue = "SKIP") DeduplicationMode deduplicationMode,

            @Parameter(description = "Separator kolumn CSV (domyślnie przecinek)")
            @RequestParam(name = "separator", defaultValue = ",") String columnSeparator,

            @Parameter(description = "Znak cytowania stringów (domyślnie cudzysłów, pusty = brak)")
            @RequestParam(defaultValue = "\"") String quoteChar,

            @Parameter(description = "Mapowanie kolumn: JSON {\"nagłówek_csv\": \"pole_systemu\", ...}")
            @RequestParam(required = false) String columnMapping
    ) {
        log.info("[CustomerImport] POST /api/customers/import: fileName={}, fileSize={}B, mode={}, sep='{}', quote='{}'",
                file.getOriginalFilename(), file.getSize(), deduplicationMode, columnSeparator, quoteChar);

        UUID jobId = customerImportService.initiateImport(file, deduplicationMode, columnSeparator, quoteChar, columnMapping);

        log.debug("[CustomerImport] Import zainicjowany: jobId={}", jobId);

        return ResponseEntity.accepted().body(Map.of("jobId", jobId.toString()));
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
    @GetMapping("/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Status joba importu klientów z CSV",
        description = """
                Zwraca aktualny stan przetwarzania importu CSV.

                Stany joba:
                - QUEUED: job zarejestrowany, oczekuje na przetworzenie
                - PROCESSING: import w toku (processed/total pokazuje postęp)
                - COMPLETED: import zakończony sukcesem
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
    public ResponseEntity<CustomerImportStatusResponse> getImportStatus(
            @Parameter(description = "UUID joba zwrócony przez POST /api/customers/import")
            @PathVariable UUID jobId
    ) {
        CustomerImportStatusResponse status = customerImportService.getJobStatus(jobId);

        if (status == null) {
            log.debug("[CustomerImport] GET /{}: job nie istnieje w Redis", jobId);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(status);
    }

    // =========================================================================
    // GET – raport błędów
    // =========================================================================

    /**
     * Pobiera raport błędów importu jako plik CSV.
     *
     * <p>Dostępny tylko gdy {@code errorFileAvailable=true} w statusie joba.
     * Zawiera wiersze odrzucone podczas importu wraz z przyczyną odrzucenia.
     */
    @GetMapping("/{jobId}/errors")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Raport błędów importu klientów",
        description = """
                Zwraca raport błędów w formacie CSV (Content-Type: text/csv).

                Dostępny tylko gdy pole errorFileAvailable=true w odpowiedzi GET /{jobId}.
                Raport zawiera: numer_linii, komunikat_bledu, oryginalne_dane.
                """,
        responses = {
            @ApiResponse(responseCode = "200", description = "Plik CSV z błędami"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Job nie istnieje lub brak błędów")
        }
    )
    public ResponseEntity<byte[]> getImportErrors(
            @Parameter(description = "UUID joba zwrócony przez POST /api/customers/import")
            @PathVariable UUID jobId
    ) {
        byte[] errorCsv = customerImportService.getErrorReport(jobId);

        if (errorCsv == null || errorCsv.length == 0) {
            log.debug("[CustomerImport] GET /{}/errors: brak raportu błędów w Redis", jobId);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition",
                        "attachment; filename=\"import-errors-" + jobId + ".csv\"")
                .body(errorCsv);
    }
}
