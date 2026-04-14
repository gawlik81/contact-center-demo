package com.contactcenter.api.admin;

import com.contactcenter.domain.etl.EtlTableStatus;
import com.contactcenter.domain.service.EtlSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Kontroler REST dla statusu pipeline ETL.
 *
 * <p>Dostępny wyłącznie dla roli <strong>ADMIN</strong>.
 * Ścieżka {@code /api/admin/**} jest zabezpieczona na poziomie {@link com.contactcenter.security.SecurityConfig}.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET  /api/admin/etl/status  – status synchronizacji per tabela (lag, lastSync)</li>
 *   <li>POST /api/admin/etl/trigger – ręczne wyzwolenie synchronizacji (do testów / recovery)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/etl")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Admin ETL", description = "Status pipeline ETL do Data Warehouse (tylko ADMIN)")
public class EtlStatusController {

    private final EtlSyncService etlSyncService;

    /**
     * Zwraca status synchronizacji ETL dla wszystkich tabel.
     *
     * <p>Odpowiedź zawiera per tabela:
     * <ul>
     *   <li>Pozycję CDC ({@code lastSyncedAt})</li>
     *   <li>Czas ostatniego uruchomienia ({@code lastRunAt})</li>
     *   <li>Liczbę przetworzonych rekordów ({@code lastRowCount})</li>
     *   <li>Status: IDLE | RUNNING | DONE | ERROR</li>
     *   <li>Lag w minutach ({@code lagMinutes})</li>
     *   <li>Komunikat błędu gdy {@code status=ERROR}</li>
     * </ul>
     */
    @GetMapping("/status")
    @Operation(
            summary = "Status ETL pipeline",
            description = "Zwraca status synchronizacji ETL do Data Warehouse. " +
                          "Lag w minutach (now - lastSyncedAt) – alert gdy > 30 min. " +
                          "Status ERROR zawiera errorMessage z przyczyną.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Status ETL wszystkich tabel"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
            }
    )
    public ResponseEntity<List<EtlTableStatus>> getEtlStatus() {
        log.debug("[EtlStatusController] GET /api/admin/etl/status");
        List<EtlTableStatus> statuses = etlSyncService.getStatus();
        return ResponseEntity.ok(statuses);
    }

    /**
     * Ręcznie wyzwala synchronizację ETL (np. po błędzie lub do testów).
     *
     * <p>Operacja synchroniczna – endpoint czeka na zakończenie cyklu.
     * Dla dużych danych może zająć do kilku sekund. Nie wyzwalaj w środowisku produkcyjnym
     * gdy {@code status=RUNNING} (możliwe nakładanie się batchów).
     */
    @PostMapping("/trigger")
    @Operation(
            summary = "Ręczne wyzwolenie ETL",
            description = "Natychmiast uruchamia cykl synchronizacji ETL. " +
                          "Operacja synchroniczna. Zwraca status po zakończeniu.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Synchronizacja zakończona"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
            }
    )
    public ResponseEntity<List<EtlTableStatus>> triggerEtlSync() {
        log.info("[EtlStatusController] POST /api/admin/etl/trigger – ręczne wyzwolenie ETL");
        etlSyncService.syncTable(EtlSyncService.TABLE_CONTACT);
        List<EtlTableStatus> statuses = etlSyncService.getStatus();
        return ResponseEntity.ok(statuses);
    }
}
