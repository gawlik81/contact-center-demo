package com.contactcenter.api.telephony;

import com.contactcenter.api.telephony.dto.TransferAgentResponse;
import com.contactcenter.api.telephony.dto.TransferQueueResponse;
import com.contactcenter.domain.telephony.TransferService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST API do zarządzania transferem połączeń telefonicznych.
 *
 * <p>Endpoint zwraca listę agentów dostępnych do przyjęcia transferu
 * od zalogowanego agenta. Lista jest filtrowana i posortowana po stronie serwera:
 * agenci AVAILABLE trafiają na górę listy, potem pozostałe aktywne statusy,
 * w ramach statusu – alfabetycznie po nazwisku.
 *
 * <p>Endpoint wymaga JWT – nie jest publiczny. Nie należy dodawać go
 * do {@code TenantFilter.PUBLIC_PATH_PREFIXES}.
 */
@Slf4j
@RestController
@RequestMapping("/api/telephony/transfer")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Call Transfer",
        description = "Endpointy do zarządzania transferem połączeń między agentami.")
public class TransferController {

    private final TransferService transferService;

    /**
     * Zwraca listę agentów dostępnych do transferu połączenia.
     *
     * <p>Filtrowanie:
     * <ul>
     *   <li>Tylko agenci tego samego tenanta</li>
     *   <li>Tylko rola AGENT (ADMIN/SUPERVISOR nie obsługują połączeń)</li>
     *   <li>Wykluczone statusy: OFFLINE</li>
     *   <li>Wykluczony zalogowany agent (nie można transferować do siebie)</li>
     * </ul>
     *
     * <p>Sortowanie: AVAILABLE → BUSY/AFTER_CONTACT/inne → alfabetycznie po nazwisku.
     *
     * @return lista agentów dostępnych do transferu, HTTP 200
     */
    @GetMapping("/agents")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Lista agentów dostępnych do transferu połączenia",
            description = """
                    Zwraca posortowaną listę agentów, do których można transferować aktywne połączenie.

                    Filtrowanie:
                    - Tylko agenci tego samego tenanta.
                    - Wykluczone statusy: OFFLINE.
                    - Wykluczony zalogowany agent (nie można transferować do siebie).

                    Sortowanie: AVAILABLE najpierw, następnie BUSY, AFTER_CONTACT i inne,
                    w ramach statusu – alfabetycznie po nazwisku.

                    Dla każdego agenta zwracane są nazwy kolejek, do których jest przypisany
                    (bezpośrednio, przez grupę lub przez flagę all_agents).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Lista agentów dostępnych do transferu (może być pusta)"),
                    @ApiResponse(responseCode = "401",
                            description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403",
                            description = "Niewystarczające uprawnienia")
            }
    )
    public ResponseEntity<List<TransferAgentResponse>> getAvailableAgents() {
        UUID tenantId = TenantContext.getTenantId();
        UUID currentUserId = TenantContext.getUserId();

        log.debug("[TransferController] GET /api/telephony/transfer/agents: tenant={}, agent={}",
                tenantId, currentUserId);

        List<TransferAgentResponse> agents =
                transferService.getAvailableAgents(tenantId, currentUserId);

        log.info("[TransferController] Zwracam {} agentów do transferu: tenant={}, agent={}",
                agents.size(), tenantId, currentUserId);

        return ResponseEntity.ok(agents);
    }

    /**
     * Zwraca listę kolejek dostępnych jako cel transferu połączenia.
     *
     * <p>Dla każdej kolejki zwracany jest snapshot statystyk:
     * liczba kontaktów w statusie QUEUED oraz liczba agentów w statusie AVAILABLE.
     *
     * <p>Lista posortowana alfabetycznie po nazwie kolejki.
     *
     * @return lista kolejek z metrykami, HTTP 200
     */
    @GetMapping("/queues")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(
            summary = "Lista kolejek dostępnych jako cel transferu połączenia",
            description = """
                    Zwraca aktywne kolejki tenanta posortowane alfabetycznie.

                    Dla każdej kolejki zwracane są metryki snapshot:
                    - waitingContacts: liczba kontaktów oczekujących (status QUEUED)
                    - availableAgents: liczba agentów dostępnych (status AVAILABLE)

                    Wartości mogą być nieaktualne o kilka sekund.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Lista kolejek (może być pusta)"),
                    @ApiResponse(responseCode = "401",
                            description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403",
                            description = "Niewystarczające uprawnienia")
            }
    )
    public ResponseEntity<List<TransferQueueResponse>> getTransferQueues() {
        UUID tenantId = TenantContext.getTenantId();

        log.debug("[TransferController] GET /api/telephony/transfer/queues: tenant={}", tenantId);

        List<TransferQueueResponse> queues = transferService.getAvailableQueues(tenantId);

        log.info("[TransferController] Zwracam {} kolejek do transferu: tenant={}",
                queues.size(), tenantId);

        return ResponseEntity.ok(queues);
    }
}
