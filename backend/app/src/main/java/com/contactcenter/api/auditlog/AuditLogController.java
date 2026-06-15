package com.contactcenter.api.auditlog;

import com.contactcenter.api.auditlog.dto.AuditLogResponse;
import com.contactcenter.domain.audit.AuditLog;
import com.contactcenter.domain.audit.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Kontroler REST udostępniający dziennik audytu.
 *
 * <p>Dostępny wyłącznie dla roli <strong>ADMIN</strong>.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>GET /api/audit-logs – lista wpisów z filtrowaniem i paginacją</li>
 * </ul>
 *
 * <p>Paginacja: maksymalnie 100 rekordów na stronę (wymóg akceptacyjny BE-005).
 * Gdy klient prosi o więcej, wartość jest obcinana do 100.
 */
@Slf4j
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Audit Log", description = "Dziennik audytu – historia operacji (tylko ADMIN)")
public class AuditLogController {

    /** Maksymalna liczba rekordów na stronę (wymóg akceptacyjny). */
    static final int MAX_PAGE_SIZE = 100;

    private final AuditLogService auditLogService;

    /**
     * Zwraca stronę wpisów dziennika audytu z opcjonalnym filtrowaniem.
     *
     * <p>Wszystkie parametry filtrów są opcjonalne. Brak parametrów = wszystkie wpisy.
     * Wyniki są posortowane od najnowszych (ORDER BY created_at DESC).
     *
     * @param tenantId   filtruj po UUID tenanta
     * @param entityType filtruj po typie encji (np. TENANT, USER, CAMPAIGN)
     * @param userId     filtruj po UUID użytkownika
     * @param dateFrom   filtruj od tej daty (ISO-8601, włącznie)
     * @param dateTo     filtruj do tej daty (ISO-8601, włącznie)
     * @param page       numer strony (0-based, domyślnie 0)
     * @param size       liczba rekordów na stronę (domyślnie 20, max 100)
     * @return strona wyników z metadanymi paginacji
     */
    @GetMapping
    @Operation(
        summary = "Lista wpisów dziennika audytu",
        description = "Zwraca stronicowaną listę wpisów audytowych posortowaną od najnowszych. " +
                      "Dostępna wyłącznie dla roli ADMIN. Maksymalnie 100 rekordów na stronę. " +
                      "Parametry filtrów są opcjonalne – brak parametrów zwraca wszystkie wpisy.",
        parameters = {
            @Parameter(name = "tenantId",   description = "UUID tenanta (opcjonalny)"),
            @Parameter(name = "entityType", description = "Typ encji: TENANT, USER, CAMPAIGN, CUSTOMER",
                       example = "TENANT"),
            @Parameter(name = "userId",     description = "UUID użytkownika (opcjonalny)"),
            @Parameter(name = "dateFrom",   description = "Data od (ISO-8601 UTC), np. 2026-01-01T00:00:00Z"),
            @Parameter(name = "dateTo",     description = "Data do (ISO-8601 UTC), np. 2026-12-31T23:59:59Z"),
            @Parameter(name = "page",       description = "Numer strony (0-based)", example = "0"),
            @Parameter(name = "size",       description = "Rozmiar strony (max 100)", example = "20")
        },
        responses = {
            @ApiResponse(responseCode = "200", description = "Strona wpisów audytowych"),
            @ApiResponse(responseCode = "400", description = "Nieprawidłowe parametry zapytania"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
        }
    )
    public ResponseEntity<Page<AuditLogResponse>> getAuditLogs(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // Ogranicz rozmiar strony do maksimum (wymóg akceptacyjny BE-005)
        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);
        if (effectiveSize <= 0) {
            effectiveSize = 20;
        }

        Pageable pageable = PageRequest.of(Math.max(page, 0), effectiveSize);

        log.debug("[AuditLog] Zapytanie: tenantId={}, entityType={}, userId={}, dateFrom={}, dateTo={}, page={}, size={}",
                tenantId, entityType, userId, dateFrom, dateTo, page, effectiveSize);

        Page<AuditLog> results = auditLogService.findAuditLogs(
                tenantId, entityType, userId, dateFrom, dateTo, pageable
        );

        Page<AuditLogResponse> response = results.map(AuditLogResponse::from);
        return ResponseEntity.ok(response);
    }
}
