package com.contactcenter.api.reports;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.reports.dto.AgentReportParams;
import com.contactcenter.api.reports.dto.AgentReportRow;
import com.contactcenter.domain.service.ReportsService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Kontroler REST dla raportów historycznych (BE-028).
 *
 * <p>Dostępne endpointy:
 * <ul>
 *   <li>{@code GET /api/reports/agents}         – paginowana lista statystyk agentów</li>
 *   <li>{@code GET /api/reports/campaigns}       – placeholder do czasu wdrożenia BE-022</li>
 *   <li>{@code GET /api/reports/agents/export}   – eksport CSV</li>
 *   <li>{@code GET /api/reports/agents/export/xlsx} – eksport XLSX</li>
 * </ul>
 *
 * <p>Wszystkie endpointy wymagają roli SUPERVISOR lub ADMIN.
 * UUID tenanta pobierany z {@link TenantContext} (ustawianego przez {@link com.contactcenter.security.TenantFilter}).
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Raporty historyczne – agregacje per agent i kampania (BE-028)")
public class ReportsController {

    private final ReportsService reportsService;

    // =========================================================================
    // GET /api/reports/agents
    // =========================================================================

    /**
     * Pobiera paginowane, zagregowane statystyki agentów za podany zakres dat.
     *
     * @param dateFrom data początku zakresu (ISO date, wymagane)
     * @param dateTo   data końca zakresu (ISO date, wymagane, max 90 dni od dateFrom)
     * @param agentId  filtr po agencie (opcjonalne)
     * @param queueId  filtr po kolejce (opcjonalne)
     * @param channel  filtr po kanale: CALL/EMAIL/CHAT/SOCIAL (opcjonalne)
     * @param page     numer strony (domyślnie 0)
     * @param size     rozmiar strony (domyślnie 20, max 100)
     * @return paginowana lista wierszy raportu
     */
    @GetMapping("/agents")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Raport agentów", description = "Zagregowane statystyki agentów za podany zakres dat")
    public ResponseEntity<PagedResponse<AgentReportRow>> getAgentReport(
            @Parameter(description = "Data od (ISO: yyyy-MM-dd)", required = true)
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,

            @Parameter(description = "Data do (ISO: yyyy-MM-dd, max 90 dni od dateFrom)", required = true)
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,

            @Parameter(description = "Filtr: UUID agenta")
            @RequestParam(required = false) UUID agentId,

            @Parameter(description = "Filtr: UUID kolejki")
            @RequestParam(required = false) UUID queueId,

            @Parameter(description = "Filtr: kanał (CALL/EMAIL/CHAT/SOCIAL)")
            @RequestParam(required = false) String channel,

            @Parameter(description = "Numer strony (0-based)")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Rozmiar strony (max 100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        UUID tenantId = TenantContext.getTenantId();

        log.debug("[ReportsCtrl] GET /api/reports/agents: tenant={}, dateFrom={}, dateTo={}, " +
                  "agentId={}, queueId={}, channel={}, page={}, size={}",
                tenantId, dateFrom, dateTo, agentId, queueId, channel, page, size);

        AgentReportParams params = new AgentReportParams(
                dateFrom, dateTo, agentId, queueId, channel, page, size);

        PagedResponse<AgentReportRow> response = reportsService.getAgentReport(params, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // GET /api/reports/campaigns  (placeholder – BE-022 nie jest zrealizowane)
    // =========================================================================

    /**
     * Placeholder dla raportu kampanii – endpoint zarezerwowany do czasu wdrożenia BE-022.
     *
     * <p>Zwraca 501 Not Implemented z informacją o planowanej implementacji.
     *
     * @return HTTP 501 z wiadomością
     */
    @GetMapping("/campaigns")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Raport kampanii (placeholder)",
               description = "Zarezerwowane – kampanie będą dostępne po wdrożeniu BE-022")
    public ResponseEntity<String> getCampaignReport() {
        log.debug("[ReportsCtrl] GET /api/reports/campaigns – placeholder (BE-022 not yet implemented)");
        return ResponseEntity.status(501)
                .body("Raport kampanii będzie dostępny po wdrożeniu modułu kampanii (BE-022).");
    }

    // =========================================================================
    // GET /api/reports/agents/export  (CSV)
    // =========================================================================

    /**
     * Eksportuje raport agentów do pliku CSV (UTF-8).
     *
     * <p>Zwraca bajty pliku z nagłówkami do pobrania jako attachment.
     * Parametry identyczne jak w {@link #getAgentReport} (poza page/size – eksport ignoruje paginację).
     *
     * @return plik CSV jako bajty z nagłówkiem Content-Disposition: attachment
     */
    @GetMapping("/agents/export")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Eksport raportu agentów – CSV",
               description = "Pobiera pełny raport agentów jako plik CSV")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID queueId,
            @RequestParam(required = false) String channel
    ) {
        UUID tenantId = TenantContext.getTenantId();

        log.info("[ReportsCtrl] GET /api/reports/agents/export (CSV): tenant={}, dateFrom={}, dateTo={}",
                tenantId, dateFrom, dateTo);

        // page=0, size=10000 – eksport pobiera wszystkie dane (limitowane w serwisie)
        AgentReportParams params = new AgentReportParams(dateFrom, dateTo, agentId, queueId, channel, 0, 100);

        byte[] csvBytes = reportsService.exportAgentReportCsv(params, tenantId);

        String filename = "agent-report-" + LocalDate.now() + ".csv";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename).build());

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csvBytes);
    }

    // =========================================================================
    // GET /api/reports/agents/export/xlsx  (XLSX)
    // =========================================================================

    /**
     * Eksportuje raport agentów do pliku XLSX (Excel).
     *
     * <p>Wymaga Apache POI ({@code poi-ooxml:5.2.5}) dodanego do pom.xml.
     * Zwraca bajty pliku XLSX z nagłówkiem Content-Disposition: attachment.
     *
     * @return plik XLSX jako bajty z nagłówkiem Content-Disposition: attachment
     */
    @GetMapping("/agents/export/xlsx")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Eksport raportu agentów – XLSX",
               description = "Pobiera pełny raport agentów jako plik Excel (.xlsx)")
    public ResponseEntity<byte[]> exportXlsx(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID queueId,
            @RequestParam(required = false) String channel
    ) {
        UUID tenantId = TenantContext.getTenantId();

        log.info("[ReportsCtrl] GET /api/reports/agents/export/xlsx: tenant={}, dateFrom={}, dateTo={}",
                tenantId, dateFrom, dateTo);

        AgentReportParams params = new AgentReportParams(dateFrom, dateTo, agentId, queueId, channel, 0, 100);

        byte[] xlsxBytes = reportsService.exportAgentReportXlsx(params, tenantId);

        String filename = "agent-report-" + LocalDate.now() + ".xlsx";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename).build());

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsxBytes);
    }
}
