package com.contactcenter.api.social;

import com.contactcenter.api.social.dto.SendSocialMessageRequest;
import com.contactcenter.domain.service.SocialMessageService;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Kontroler do wysyłki wiadomości social media przez agenta.
 *
 * <p>Endpoint: {@code POST /api/contacts/{contactId}/social/message}
 *
 * <p>Wymaga autentykacji (AGENT/SUPERVISOR/ADMIN). TenantContext jest ustawiony
 * przez TenantFilter na podstawie JWT.
 */
@Slf4j
@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
public class SocialContactController {

    private final SocialMessageService socialMessageService;

    /**
     * Wysyła wiadomość social media do klienta przez agenta.
     *
     * @param contactId UUID kontaktu social
     * @param request   treść wiadomości i opcjonalne załączniki
     * @return 200 OK przy sukcesie, 400 gdy kontakt nie jest kanałem social, 404 gdy kontakt nie istnieje
     */
    @PostMapping("/{contactId}/social/message")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    public ResponseEntity<Void> sendMessage(
            @PathVariable UUID contactId,
            @RequestBody SendSocialMessageRequest request) {

        UUID tenantId = TenantContext.getTenantId();
        log.info("[SocialContactCtrl] Wysyłka wiadomości: contactId={}, tenant={}", contactId, tenantId);

        try {
            socialMessageService.sendMessage(
                    contactId,
                    tenantId,
                    request.content(),
                    request.attachmentUrls() != null ? request.attachmentUrls() : List.of()
            );
            return ResponseEntity.ok().build();

        } catch (IllegalArgumentException e) {
            log.warn("[SocialContactCtrl] Błąd walidacji: contactId={}, error={}", contactId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            log.warn("[SocialContactCtrl] Brak integracji: contactId={}, error={}", contactId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
