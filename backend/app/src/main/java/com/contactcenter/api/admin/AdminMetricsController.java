package com.contactcenter.api.admin;

import com.contactcenter.api.admin.dto.AdminMetricsResponse;
import com.contactcenter.api.admin.dto.TenantDetailMetrics;
import com.contactcenter.domain.service.AdminMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Kontroler REST dla metryk RT (real-time) platformy Contact Center.
 *
 * <p>Dostępny wyłącznie dla roli <strong>ADMIN</strong>.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET /api/admin/metrics                    – globalne metryki platformy (cachowane 30s)</li>
 *   <li>GET /api/admin/metrics/tenants/{id}        – metryki per tenant</li>
 * </ul>
 *
 * <p>Ścieżka {@code /api/admin/**} jest zabezpieczona na poziomie {@code SecurityConfig}
 * (reguła {@code hasRole("ADMIN")}). Adnotacja {@code @PreAuthorize} dodaje
 * dodatkową warstwę ochrony na poziomie metody (defense in depth).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Admin Metrics", description = "Metryki RT platformy Contact Center (tylko ADMIN)")
public class AdminMetricsController {

    private final AdminMetricsService adminMetricsService;

    // =========================================================================
    // Globalne metryki platformy
    // =========================================================================

    /**
     * Zwraca zagregowane metryki RT całej platformy.
     *
     * <p>Dane są cachowane w Redis z TTL 30s. Cache jest inwalidowany po zmianie
     * statusu tenanta (np. dezaktywacji lub zawieszeniu).
     *
     * <p>Odpowiedź zawiera:
     * <ul>
     *   <li>Liczbę aktywnych tenantów</li>
     *   <li>Łączną liczbę agentów online we wszystkich tenantach</li>
     *   <li>Alerty systemowe (tenanty SUSPENDED itp.)</li>
     *   <li>Metryki per tenant (id, name, status, agentsOnline, agentsTotal)</li>
     * </ul>
     */
    @GetMapping
    @Operation(
        summary = "Globalne metryki RT platformy",
        description = "Zwraca zagregowane metryki RT wszystkich tenantów platformy. " +
                      "Dane cachowane w Redis (TTL 30s). " +
                      "Zawiera: liczbę aktywnych tenantów, agentów online, " +
                      "alerty systemowe i metryki per tenant.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Metryki platformy"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
        }
    )
    public ResponseEntity<AdminMetricsResponse> getGlobalMetrics() {
        log.debug("[AdminMetricsController] GET /api/admin/metrics");
        AdminMetricsResponse metrics = adminMetricsService.getGlobalMetrics();
        return ResponseEntity.ok(metrics);
    }

    // =========================================================================
    // Metryki per tenant
    // =========================================================================

    /**
     * Zwraca szczegółowe metryki dla konkretnego tenanta.
     *
     * <p>Na etapie MVP pola {@code cpuUsage} i {@code memoryUsage} są mockami (0.0).
     * Docelowo dane te powinny być pobierane z systemu monitoringu (np. Prometheus/Grafana).
     */
    @GetMapping("/tenants/{id}")
    @Operation(
        summary = "Metryki per tenant",
        description = "Zwraca szczegółowe metryki operacyjne dla wskazanego tenanta. " +
                      "Pola cpuUsage i memoryUsage są na MVP mockami (wartość 0.0). " +
                      "Dane nie są cachowane – zawsze aktualne.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Metryki tenanta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
            @ApiResponse(responseCode = "422", description = "Tenant nie istnieje")
        }
    )
    public ResponseEntity<TenantDetailMetrics> getTenantMetrics(
            @Parameter(description = "UUID tenanta", required = true)
            @PathVariable UUID id
    ) {
        log.debug("[AdminMetricsController] GET /api/admin/metrics/tenants/{}", id);
        TenantDetailMetrics metrics = adminMetricsService.getTenantMetrics(id);
        return ResponseEntity.ok(metrics);
    }
}
