package com.contactcenter.api.agentbreak;

import com.contactcenter.api.agentbreak.dto.AgentCalendarResponse;
import com.contactcenter.domain.agentbreak.AgentCalendarService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Kontroler REST kalendarza agenta (BE-051).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET /api/agent/calendar?from={ISO}&to={ISO} – zagregowany kalendarz agenta
 *       (callbacki, kampanie, przerwy)</li>
 * </ul>
 *
 * <p>Uprawnienia: wyłącznie AGENT.
 * AgentId i TenantId pobierane z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/agent/calendar")
@RequiredArgsConstructor
@PreAuthorize("hasRole('AGENT')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Agent Calendar", description = "Kalendarz agenta – callbacki, kampanie, przerwy")
public class AgentCalendarController {

    private final AgentCalendarService agentCalendarService;

    // =========================================================================
    // Kalendarz agenta
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Kalendarz agenta",
        description = "Zwraca zagregowany widok kalendarza zalogowanego agenta: zaplanowane " +
                      "oddzwonienia, kampanie powiązane przez kolejkę oraz przerwy. " +
                      "Parametry from/to są opcjonalne – gdy nie podano, stosowany jest " +
                      "bieżący tydzień (poniedziałek 00:00 UTC – niedziela 23:59:59 UTC). " +
                      "Maksymalny zakres to 90 dni.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Kalendarz agenta",
                content = @Content(schema = @Schema(implementation = AgentCalendarResponse.class))
            ),
            @ApiResponse(
                responseCode = "400",
                description = "Nieprawidłowy format daty lub zakres parametrów " +
                              "(from > to, zakres > 90 dni)"
            ),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – tylko AGENT")
        }
    )
    public ResponseEntity<AgentCalendarResponse> getCalendar(
            @Parameter(
                description = "Początek zakresu kalendarza (ISO-8601), np. 2026-04-21T00:00:00Z",
                example = "2026-04-21T00:00:00Z"
            )
            @RequestParam(required = false) String from,

            @Parameter(
                description = "Koniec zakresu kalendarza (ISO-8601), np. 2026-04-27T23:59:59Z",
                example = "2026-04-27T23:59:59Z"
            )
            @RequestParam(required = false) String to
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        log.debug("[AgentCalendarController] Pobieranie kalendarza: tenant={}, agentId={}, from={}, to={}",
                tenantId, agentId, from, to);

        Instant parsedFrom = parseInstantOrThrow(from, "from");
        Instant parsedTo   = parseInstantOrThrow(to,   "to");

        AgentCalendarResponse response = agentCalendarService.getCalendar(agentId, tenantId, parsedFrom, parsedTo);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Parsuje string ISO-8601 na Instant lub zwraca null gdy wartość jest null.
     *
     * @param value     wartość do sparsowania (może być null)
     * @param paramName nazwa parametru – używana w komunikacie błędu
     * @return Instant lub null
     * @throws IllegalArgumentException gdy wartość jest niepusta, ale nieprawidłowo sformatowana
     */
    private Instant parseInstantOrThrow(String value, String paramName) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Nieprawidłowy format parametru '" + paramName + "': '" + value
                    + "'. Oczekiwany format ISO-8601, np. 2026-04-21T00:00:00Z");
        }
    }
}
