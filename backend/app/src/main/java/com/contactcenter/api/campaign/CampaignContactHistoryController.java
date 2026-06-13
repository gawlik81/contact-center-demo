package com.contactcenter.api.campaign;

import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.campaign.CampaignRepository;
import com.contactcenter.domain.contact.ContactRepository;
import com.contactcenter.security.TenantContext;
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

import java.util.List;
import java.util.UUID;

/**
 * Kontroler REST historii prób wydzwonienia dla rekordu kampanii (BE-085).
 *
 * <p>Endpoint:
 * <ul>
 *   <li>GET /api/campaigns/{campaignId}/contacts/{recordId}/attempts – lista prób wydzwonienia</li>
 * </ul>
 *
 * <p>Uprawnienia: SUPERVISOR lub ADMIN.
 */
@Slf4j
@RestController
@RequestMapping("/api/campaigns/{campaignId}/contacts/{recordId}/attempts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Campaign Contact History", description = "Historia prób wydzwonienia rekordu kampanii")
public class CampaignContactHistoryController {

    private final CampaignRepository campaignRepository;
    private final ContactRepository contactRepository;

    /**
     * Zwraca listę prób wydzwonienia dla danego rekordu kampanii.
     *
     * <p>Kontakty wychodzące powiązane z rekordem przez {@code campaign_contact_record_id}.
     * Posortowane od najnowszych (started_at DESC).
     *
     * @param campaignId UUID kampanii
     * @param recordId   UUID rekordu campaign_contact
     * @return lista ContactResponse posortowana od najnowszych prób
     */
    @GetMapping
    @Operation(
        summary = "Historia prób wydzwonienia rekordu kampanii",
        description = "Zwraca listę kontaktów wychodzących powiązanych z danym rekordem campaign_contact " +
                      "(przez campaign_contact_record_id). Posortowane od najnowszych prób wydzwonienia.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista prób wydzwonienia"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kampania nie istnieje")
        }
    )
    public ResponseEntity<List<ContactResponse>> getAttempts(
            @Parameter(description = "UUID kampanii", required = true)
            @PathVariable UUID campaignId,
            @Parameter(description = "UUID rekordu campaign_contact", required = true)
            @PathVariable UUID recordId
    ) {
        UUID tenantId = TenantContext.getTenantId();

        // Weryfikacja istnienia kampanii
        campaignRepository.findById(campaignId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Kampania nie istnieje lub nie należy do tego tenanta: " + campaignId));

        List<Contact> contacts = contactRepository.findByCampaignContactRecordId(recordId, tenantId);

        List<ContactResponse> response = contacts.stream()
                .map(ContactResponse::from)
                .toList();

        log.debug("[CampaignContactHistoryController] Próby wydzwonienia: campaignId={}, recordId={}, " +
                "tenant={}, count={}", campaignId, recordId, tenantId, response.size());

        return ResponseEntity.ok(response);
    }
}
