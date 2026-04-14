package com.contactcenter.api.phonenumber;

import com.contactcenter.api.phonenumber.dto.ConflictRuleResponse;
import com.contactcenter.api.phonenumber.dto.CreatePhoneRoutingRuleRequest;
import com.contactcenter.api.phonenumber.dto.PhoneRoutingRuleResponse;
import com.contactcenter.api.phonenumber.dto.UpdatePhoneRoutingRuleRequest;
import com.contactcenter.domain.exception.RoutingRuleConflictException;
import com.contactcenter.domain.service.PhoneRoutingRuleService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Kontroler REST zarządzający regułami routingu dla numerów telefonów.
 *
 * <p>Implementuje BE-034: PhoneRoutingRule CRUD API.
 *
 * <p>Endpointy (zagnieżdżone pod /api/phone-numbers/{numberId}):
 * <ul>
 *   <li>POST   /api/phone-numbers/{numberId}/routing-rules              – dodanie reguły</li>
 *   <li>GET    /api/phone-numbers/{numberId}/routing-rules              – lista reguł</li>
 *   <li>PATCH  /api/phone-numbers/{numberId}/routing-rules/{ruleId}    – aktualizacja reguły</li>
 *   <li>DELETE /api/phone-numbers/{numberId}/routing-rules/{ruleId}    – usunięcie reguły</li>
 * </ul>
 *
 * <p>Dostęp tylko dla ról ADMIN i SUPERVISOR.
 * TenantId pobierany z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 *
 * <p>Przy kolizji harmonogramów ({@link RoutingRuleConflictException}) zwraca HTTP 409
 * z ciałem {@link ConflictRuleResponse} zawierającym listę kolidujących ruleId.
 */
@Slf4j
@RestController
@RequestMapping("/api/phone-numbers/{numberId}/routing-rules")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Phone Routing Rules", description = "Zarządzanie regułami routingu dla numerów telefonów")
public class PhoneRoutingRuleController {

    private final PhoneRoutingRuleService phoneRoutingRuleService;

    // =========================================================================
    // Tworzenie
    // =========================================================================

    @PostMapping
    @Operation(
        summary = "Dodaj regułę routingu",
        description = "Tworzy nową regułę routingu dla numeru telefonu. " +
                      "Zwraca HTTP 409 z listą kolidujących ruleId gdy harmonogram się nakłada.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Reguła utworzona"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Numer telefonu nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Kolizja harmonogramów – lista kolidujących ruleId"),
            @ApiResponse(responseCode = "422", description = "Nieprawidłowe dane wejściowe")
        }
    )
    public ResponseEntity<?> createRule(
            @Parameter(description = "UUID numeru telefonu")
            @PathVariable UUID numberId,
            @Valid @RequestBody CreatePhoneRoutingRuleRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();

        try {
            PhoneRoutingRuleResponse response = phoneRoutingRuleService.createRule(tenantId, numberId, request);

            URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(response.ruleId())
                    .toUri();

            log.debug("[PhoneRoutingRuleController] POST /routing-rules → 201, ruleId={}",
                    response.ruleId());
            return ResponseEntity.created(location).body(response);

        } catch (RoutingRuleConflictException ex) {
            return buildConflictResponse(ex);
        }
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Lista reguł routingu",
        description = "Zwraca wszystkie reguły routingu dla danego numeru telefonu, " +
                      "posortowane po time_start rosnąco.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista reguł"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Numer telefonu nie istnieje")
        }
    )
    public ResponseEntity<List<PhoneRoutingRuleResponse>> listRules(
            @Parameter(description = "UUID numeru telefonu")
            @PathVariable UUID numberId
    ) {
        UUID tenantId = TenantContext.getTenantId();

        List<PhoneRoutingRuleResponse> response = phoneRoutingRuleService.listRules(tenantId, numberId);

        log.debug("[PhoneRoutingRuleController] GET /routing-rules → 200, count={}", response.size());
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

    @PatchMapping("/{ruleId}")
    @Operation(
        summary = "Aktualizuj regułę routingu",
        description = "Częściowa aktualizacja reguły (PATCH). " +
                      "Pola null = brak zmiany. Zwraca HTTP 409 gdy nowy harmonogram koliduje.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Reguła zaktualizowana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Reguła lub numer nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Kolizja harmonogramów"),
            @ApiResponse(responseCode = "422", description = "Nieprawidłowe dane wejściowe")
        }
    )
    public ResponseEntity<?> updateRule(
            @Parameter(description = "UUID numeru telefonu")
            @PathVariable UUID numberId,
            @Parameter(description = "UUID reguły routingu")
            @PathVariable UUID ruleId,
            @Valid @RequestBody UpdatePhoneRoutingRuleRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();

        try {
            PhoneRoutingRuleResponse response =
                    phoneRoutingRuleService.updateRule(tenantId, numberId, ruleId, request);

            log.debug("[PhoneRoutingRuleController] PATCH /routing-rules/{} → 200", ruleId);
            return ResponseEntity.ok(response);

        } catch (RoutingRuleConflictException ex) {
            return buildConflictResponse(ex);
        }
    }

    // =========================================================================
    // Usuwanie
    // =========================================================================

    @DeleteMapping("/{ruleId}")
    @Operation(
        summary = "Usuń regułę routingu",
        description = "Trwałe usunięcie reguły routingu (hard delete).",
        responses = {
            @ApiResponse(responseCode = "204", description = "Reguła usunięta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Reguła lub numer nie istnieje")
        }
    )
    public ResponseEntity<Void> deleteRule(
            @Parameter(description = "UUID numeru telefonu")
            @PathVariable UUID numberId,
            @Parameter(description = "UUID reguły routingu")
            @PathVariable UUID ruleId
    ) {
        UUID tenantId = TenantContext.getTenantId();

        phoneRoutingRuleService.deleteRule(tenantId, numberId, ruleId);

        log.debug("[PhoneRoutingRuleController] DELETE /routing-rules/{} → 204", ruleId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /** Buduje HTTP 409 z ciałem {@link ConflictRuleResponse}. */
    private ResponseEntity<ConflictRuleResponse> buildConflictResponse(RoutingRuleConflictException ex) {
        log.warn("[PhoneRoutingRuleController] Kolizja reguł → 409, kolidujące={}", ex.getCollidingRuleIds());
        ConflictRuleResponse body = new ConflictRuleResponse(
                ex.getCollidingRuleIds(), ex.getMessage());
        return ResponseEntity.status(409).body(body);
    }
}
