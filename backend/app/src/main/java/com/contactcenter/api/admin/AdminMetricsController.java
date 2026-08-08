package com.contactcenter.api.admin;

import com.contactcenter.api.admin.dto.AdminMetricsResponse;
import com.contactcenter.api.admin.dto.ContactChannelMatrix;
import com.contactcenter.api.admin.dto.GrowthMetrics;
import com.contactcenter.api.admin.dto.SystemResourceMetrics;
import com.contactcenter.api.admin.dto.TenantDetailMetrics;
import com.contactcenter.api.admin.dto.UsageMetrics;
import com.contactcenter.domain.tenant.AdminMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Kontroler REST dla metryk RT (real-time) platformy Contact Center.
 *
 * <p>Dostępny wyłącznie dla roli <strong>SUPER_ADMIN</strong> (globalny administrator
 * platformy, refaktor ról).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET /api/admin/metrics                    – globalne metryki platformy (cachowane 30s)</li>
 *   <li>GET /api/admin/metrics/tenants/{id}        – metryki per tenant</li>
 *   <li>GET /api/admin/metrics/usage               – wykorzystanie platformy dzisiaj (cachowane 5 min)</li>
 *   <li>GET /api/admin/metrics/contacts-by-channel?days=N – kontakty w zakresie 7/30/90 dni per tenant/kanał (cachowane 5 min)</li>
 *   <li>GET /api/admin/metrics/growth?weeks=N       – trendy wzrostu tygodniowego (cachowane 20 min)</li>
 *   <li>GET /api/admin/metrics/resources            – zasoby systemowe (live, bez cache)</li>
 *   <li>GET /api/admin/metrics/tenants/export       – eksport CSV rozszerzonej tabeli tenantów</li>
 * </ul>
 *
 * <p>Ścieżka {@code /api/admin/**} jest zabezpieczona na poziomie {@code SecurityConfig}
 * (reguła {@code hasRole("SUPER_ADMIN")}). Adnotacja {@code @PreAuthorize} dodaje
 * dodatkową warstwę ochrony na poziomie metody (defense in depth).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Admin Metrics", description = "Metryki RT platformy Contact Center (tylko SUPER_ADMIN)")
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
     */
    @GetMapping("/tenants/{id}")
    @Operation(
        summary = "Metryki per tenant",
        description = "Zwraca szczegółowe metryki operacyjne dla wskazanego tenanta. " +
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

    // =========================================================================
    // Metryki wykorzystania platformy (usage)
    // =========================================================================

    /**
     * Zwraca zagregowane wskaźniki wykorzystania platformy "dzisiaj" (cross-tenant).
     *
     * <p>Dane są cachowane w Redis z TTL 5 min.
     */
    @GetMapping("/usage")
    @Operation(
        summary = "Wskaźniki wykorzystania platformy (dzisiaj)",
        description = "Zwraca zagregowane wskaźniki wykorzystania platformy: liczbę obsłużonych " +
                      "kontaktów, zmianę względem wczoraj, średnie czasy obsługi/oczekiwania, FCR " +
                      "oraz stan kampanii. Dane cachowane w Redis (TTL 5 min).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Metryki wykorzystania platformy"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
        }
    )
    public ResponseEntity<UsageMetrics> getUsageMetrics() {
        log.debug("[AdminMetricsController] GET /api/admin/metrics/usage");
        UsageMetrics metrics = adminMetricsService.getUsageMetrics();
        return ResponseEntity.ok(metrics);
    }

    // =========================================================================
    // Macierz kontaktów per tenant i kanał
    // =========================================================================

    /**
     * Zwraca macierz liczby kontaktów w wybranym zakresie dat w podziale na tenanta i kanał
     * komunikacji.
     *
     * <p>Dane są cachowane w Redis z TTL 5 min (osobno per wartość {@code days}).
     */
    @GetMapping("/contacts-by-channel")
    @Operation(
        summary = "Kontakty per tenant i kanał (zakres 7/30/90 dni)",
        description = "Zwraca macierz liczby kontaktów obsłużonych (status COMPLETED/TRANSFERRED) "
                      + "w ostatnich N dniach (dzień dzisiejszy + N-1 poprzednich, oba krańce włącznie), "
                      + "rozbitą per tenant i kanał komunikacji (PHONE, EMAIL, SOCIAL_FACEBOOK, "
                      + "SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP). Zwraca wszystkich tenantów platformy, "
                      + "każdy z pełnym zestawem 5 kanałów (brak kontaktów danego kanału = 0, klucz "
                      + "nie jest pomijany). Odpowiedź zawiera fromDate/toDate z dokładnym zakresem. "
                      + "Dla days=7 (i zakresu = tylko dziś) suma per tenant zgadza się z "
                      + "TenantMetrics.contactsToday z GET /api/admin/metrics. Dane cachowane w Redis "
                      + "(TTL 5 min, osobno per wartość days).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Macierz kontaktów per tenant i kanał"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
            @ApiResponse(responseCode = "422", description = "Parametr days poza dozwolonym zbiorem {7, 30, 90}")
        }
    )
    public ResponseEntity<ContactChannelMatrix> getContactChannelMatrix(
            @Parameter(description = "Liczba dni wstecz do uwzględnienia (dozwolone WYŁĄCZNIE: 7, 30, 90)")
            @RequestParam(defaultValue = "30") @Min(7) @Max(90) int days
    ) {
        log.debug("[AdminMetricsController] GET /api/admin/metrics/contacts-by-channel?days={}", days);
        ContactChannelMatrix matrix = adminMetricsService.getContactChannelMatrix(days);
        return ResponseEntity.ok(matrix);
    }

    // =========================================================================
    // Metryki wzrostu platformy (growth)
    // =========================================================================

    /**
     * Zwraca trendy wzrostu platformy: nowych tenantów/użytkowników tygodniowo i top pluginy.
     *
     * <p>Dane są cachowane w Redis z TTL 20 min.
     */
    @GetMapping("/growth")
    @Operation(
        summary = "Trendy wzrostu platformy",
        description = "Zwraca liczbę nowych tenantów/użytkowników pogrupowaną tygodniowo za ostatnie " +
                      "N tygodni oraz ranking top 5 pluginów wg liczby aktywnych instalacji. " +
                      "Dane cachowane w Redis (TTL 20 min).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Metryki wzrostu platformy"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
            @ApiResponse(responseCode = "422", description = "Parametr weeks poza zakresem 1-52")
        }
    )
    public ResponseEntity<GrowthMetrics> getGrowthMetrics(
            @Parameter(description = "Liczba ostatnich tygodni do uwzględnienia (1-52)")
            @RequestParam(defaultValue = "6") @Min(1) @Max(52) int weeks
    ) {
        log.debug("[AdminMetricsController] GET /api/admin/metrics/growth?weeks={}", weeks);
        GrowthMetrics metrics = adminMetricsService.getGrowthMetrics(weeks);
        return ResponseEntity.ok(metrics);
    }

    // =========================================================================
    // Metryki zasobów systemowych (resources)
    // =========================================================================

    /**
     * Zwraca metryki zasobów WSPÓŁDZIELONEJ instancji JVM/procesu backendu.
     *
     * <p>Dane NIE są cachowane – zawsze aktualny, żywy stan procesu.
     */
    @GetMapping("/resources")
    @Operation(
        summary = "Zasoby systemowe backendu (live)",
        description = "Zwraca metryki CPU/pamięci/wątków JVM, pulę połączeń DB (HikariCP), " +
                      "status Redis/RabbitMQ i głębokość kluczowych kolejek operacyjnych. " +
                      "Dane NIE są cachowane – zawsze aktualne.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Metryki zasobów systemowych"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
        }
    )
    public ResponseEntity<SystemResourceMetrics> getSystemResourceMetrics() {
        log.debug("[AdminMetricsController] GET /api/admin/metrics/resources");
        SystemResourceMetrics metrics = adminMetricsService.getSystemResourceMetrics();
        return ResponseEntity.ok(metrics);
    }

    // =========================================================================
    // Eksport CSV metryk tenantów
    // =========================================================================

    /**
     * Eksportuje rozszerzoną tabelę metryk tenantów do pliku CSV (UTF-8).
     */
    @GetMapping("/tenants/export")
    @Operation(
        summary = "Eksport metryk tenantów – CSV",
        description = "Pobiera rozszerzoną tabelę metryk tenantów (name, status, agentsOnline, " +
                      "agentsTotal, contactsToday, avgHandleTimeSeconds, fcrPercentage, " +
                      "agentLimitUtilizationPercent) jako plik CSV.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Plik CSV z metrykami tenantów"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
        }
    )
    public ResponseEntity<byte[]> exportTenantMetricsCsv() {
        log.info("[AdminMetricsController] GET /api/admin/metrics/tenants/export (CSV)");

        byte[] csvBytes = adminMetricsService.exportTenantMetricsCsv();

        String filename = "tenant-metrics-" + LocalDate.now() + ".csv";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename).build());

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csvBytes);
    }
}
