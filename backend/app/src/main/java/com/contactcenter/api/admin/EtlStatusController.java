package com.contactcenter.api.admin;

import com.contactcenter.domain.etl.EtlSyncService;
import com.contactcenter.domain.etl.EtlTableStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.contactcenter.domain.exception.EtlSyncInProgressException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Kontroler REST dla statusu pipeline ETL.
 *
 * <p>Dostępny wyłącznie dla roli <strong>SUPER_ADMIN</strong> (globalny administrator
 * platformy, refaktor ról).
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
@PreAuthorize("hasRole('SUPER_ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Admin ETL", description = "Status pipeline ETL do Data Warehouse (tylko SUPER_ADMIN)")
public class EtlStatusController {

    private final EtlSyncService etlSyncService;
    private final LockingTaskExecutor lockingTaskExecutor;

    /** Safety net of the manual-trigger lock, same key as the scheduled cycle ({@code shedlock.etl.at-most-for}). */
    @Value("${shedlock.etl.at-most-for:PT10M}")
    private Duration lockAtMostFor = Duration.ofMinutes(10);

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
     *   <li>Żywotność (BE-223): {@code stale}, {@code lastActivityAt}, {@code livenessThresholdMs}</li>
     * </ul>
     */
    @GetMapping("/status")
    @Operation(
            summary = "Status ETL pipeline",
            description = "Zwraca status synchronizacji ETL do Data Warehouse. " +
                          "Lag w minutach (now - lastSyncedAt) – alert gdy > 30 min. " +
                          "Status ERROR zawiera errorMessage z przyczyną. " +
                          "Żywotność liczona na serwerze zegarem bazy (ten sam predykat czasowy co alert JOB_STALE; " +
                          "alert dodatkowo pomija tabele nie-CDC i status ERROR, status raportuje stale dla każdego wiersza): " +
                          "stale (pole pomijane gdy job nigdy nie ruszył), lastActivityAt = max(lastRunAt, heartbeat), " +
                          "livenessThresholdMs = etl.sync.liveness-threshold-ms.",
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
            description = "Natychmiast uruchamia cykl synchronizacji ETL tabeli contact (pod tą samą blokadą co cykl zaplanowany; " +
                          "gdy cykl już trwa - 409 ETL_SYNC_IN_PROGRESS). " +
                          "Operacja synchroniczna. Zwraca status po zakończeniu.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Synchronizacja zakończona"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
                    @ApiResponse(responseCode = "409", description = "Cykl ETL już trwa (code=ETL_SYNC_IN_PROGRESS)")
            }
    )
    public ResponseEntity<List<EtlTableStatus>> triggerEtlSync() {
        log.info("[EtlStatusController] POST /api/admin/etl/trigger – ręczne wyzwolenie ETL");
        // BE-172: the SAME ShedLock as the scheduled contact cycle, lockAtLeastFor = 0 (released right after the run).
        // Busy lock (cycle running here or on another instance) -> 409, never a second DW writer.
        LockConfiguration lockConfig = new LockConfiguration(
                Instant.now(), EtlSyncService.LOCK_CONTACT_SYNC, lockAtMostFor, Duration.ZERO);
        LockingTaskExecutor.TaskResult<Boolean> result;
        try {
            result = lockingTaskExecutor.executeWithLock((LockingTaskExecutor.TaskWithResult<Boolean>) () -> {
                etlSyncService.syncTable(EtlSyncService.TABLE_CONTACT);
                return Boolean.TRUE;
            }, lockConfig);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Ręczny trigger ETL nie powiódł się", t);
        }
        if (!result.wasExecuted()) {
            throw new EtlSyncInProgressException(EtlSyncService.TABLE_CONTACT);
        }
        List<EtlTableStatus> statuses = etlSyncService.getStatus();
        return ResponseEntity.ok(statuses);
    }
}
