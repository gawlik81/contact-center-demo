package com.contactcenter.api.dialer;

import com.contactcenter.api.dialer.dto.ManualCallbackRequest;
import com.contactcenter.api.dialer.dto.ManualCallbackResponse;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.campaign.ScheduledCallback;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.campaign.ScheduledCallbackRepository;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Kontroler REST dla manualnego planowania oddzwonień przez agenta (BE-048).
 *
 * <p>Endpoint {@code POST /api/callbacks/manual} umożliwia agentowi lub supervisorowi
 * zaplanowanie oddzwonienia do klienta bez aktywnej rozmowy (source_type=AGENT_MANUAL).
 *
 * <p>Warunki:
 * <ul>
 *   <li>Klient (customerId) musi należeć do tenanta agenta → 403 jeśli inny tenant</li>
 *   <li>scheduledAt musi być co najmniej 5 minut w przyszłości → 400 jeśli nie</li>
 *   <li>phoneNumber musi być w formacie E.164 → 400 jeśli nie</li>
 *   <li>assigned_agent_id ustawiany zawsze z JWT (agent nie może podstawić innego agenta)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/callbacks")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Callbacks", description = "Manualne planowanie oddzwonień przez agenta")
public class ManualCallbackController {

    /** Minimalne wyprzedzenie czasowe scheduledAt od momentu wywołania (5 minut). */
    private static final long MIN_SCHEDULED_AT_OFFSET_SECONDS = 5 * 60L;

    private final CustomerService customerService;
    private final ScheduledCallbackRepository scheduledCallbackRepository;

    /**
     * Planuje manualne oddzwonienie do klienta.
     *
     * <p>Agent wskazuje klienta i termin oddzwonienia. Endpoint:
     * <ol>
     *   <li>Weryfikuje że klient należy do tenanta agenta (→ 403 jeśli nie)</li>
     *   <li>Waliduje phoneNumber (E.164) przez Bean Validation (@Pattern)</li>
     *   <li>Waliduje scheduledAt – musi być min. 5 minut w przyszłości (→ 400 jeśli nie)</li>
     *   <li>Tworzy rekord scheduled_callback z source_type=AGENT_MANUAL</li>
     *   <li>Zwraca 201 Created z lokalizacją nowego zasobu</li>
     * </ol>
     *
     * <p>Pole assigned_agent_id jest zawsze ustawiane na agentId z JWT –
     * agent nie może podstawić UUID innego agenta (IDOR protection).
     *
     * @param request dane oddzwonienia (customerId, phoneNumber, scheduledAt, notes)
     * @return ManualCallbackResponse z HTTP 201
     */
    @PostMapping("/manual")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR')")
    @Operation(
        summary = "Zaplanuj manualne oddzwonienie",
        description = "Agent lub supervisor planuje oddzwonienie do klienta bez aktywnej rozmowy. " +
                      "source_type=AGENT_MANUAL. " +
                      "assigned_agent_id zawsze z JWT (ochrona IDOR). " +
                      "scheduledAt musi być co najmniej 5 minut w przyszłości.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Oddzwonienie zaplanowane"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji – niepoprawny numer lub scheduledAt w przeszłości/za blisko"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień lub klient należy do innego tenanta"),
            @ApiResponse(responseCode = "404", description = "Klient nie istnieje")
        }
    )
    public ResponseEntity<ManualCallbackResponse> createManualCallback(
            @Valid @RequestBody ManualCallbackRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        // 1. Walidacja scheduledAt – min. 5 minut w przyszłości
        Instant minAllowed = Instant.now().plusSeconds(MIN_SCHEDULED_AT_OFFSET_SECONDS);
        if (request.scheduledAt().isBefore(minAllowed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scheduledAt musi być co najmniej 5 minut w przyszłości");
        }

        // 2. Weryfikacja klienta – czy istnieje i należy do tenanta
        Customer customer = customerService.findById(request.customerId(), tenantId)
                .orElseThrow(() -> {
                    // Nie ujawniamy czy klient istnieje w innym tenancie – zawsze 403
                    log.warn("[ManualCallbackController] Klient nie istnieje lub inny tenant: " +
                            "customerId={}, requestingTenant={}", request.customerId(), tenantId);
                    return new CrossTenantAccessException(request.customerId(), tenantId);
                });

        // 3. Zbuduj i zapisz callback
        String customerName = buildCustomerName(customer);

        ScheduledCallback callback = ScheduledCallback.builder()
                .tenantId(tenantId)
                .customerId(customer.getCustomerId())
                .agentId(agentId)
                .phone(request.phoneNumber())
                .firstName(customer.getFirstName())
                .lastName(customer.getLastName())
                .scheduledAt(request.scheduledAt())
                .notes(request.notes())
                .sourceType("AGENT_MANUAL")
                .status("PENDING")
                // campaign_id i origin_contact_id są null – callback niezwiązany z kampanią/kontaktem
                .build();

        ScheduledCallback saved = scheduledCallbackRepository.save(callback);

        log.info("[ManualCallbackController] Zaplanowano manualne oddzwonienie: " +
                        "callbackId={}, customerId={}, agent={}, scheduledAt={}, tenant={}",
                saved.getCallbackId(), customer.getCustomerId(), agentId,
                request.scheduledAt(), tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentContextPath()
                .path("/api/dialer/callbacks/{id}")
                .buildAndExpand(saved.getCallbackId())
                .toUri();

        return ResponseEntity.created(location)
                .body(ManualCallbackResponse.from(saved, customerName));
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /**
     * Buduje czytelną nazwę klienta z dostępnych pól.
     *
     * @param customer encja klienta
     * @return "Jan Kowalski", "Jan" gdy brak nazwiska, lub pusty string gdy brak obu pól
     */
    private String buildCustomerName(Customer customer) {
        String first = customer.getFirstName() != null ? customer.getFirstName().trim() : "";
        String last  = customer.getLastName()  != null ? customer.getLastName().trim()  : "";

        if (!first.isEmpty() && !last.isEmpty()) {
            return first + " " + last;
        }
        return !first.isEmpty() ? first : last;
    }
}
