package com.contactcenter.api.social;

import com.contactcenter.api.social.dto.PagedSocialMessagesResponse;
import com.contactcenter.api.social.dto.SendSocialMessageRequest;
import com.contactcenter.api.social.dto.SocialMessageResponse;
import com.contactcenter.domain.model.SocialMessage;
import com.contactcenter.domain.repository.SocialMessageRepository;
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
    private final SocialMessageRepository socialMessageRepository;

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

    /**
     * Pobiera historię wiadomości social media dla danego kontaktu z paginacją.
     *
     * <p>Repozytorium zwraca maksymalnie 50 rekordów posortowanych po {@code sentAt DESC}.
     * Paginacja jest wykonywana ręcznie po stronie aplikacji (subList).
     *
     * @param contactId UUID kontaktu
     * @param page      numer strony (domyślnie 0)
     * @param size      rozmiar strony (domyślnie 20)
     * @return strona wiadomości opakowana w {@link PagedSocialMessagesResponse}
     */
    @GetMapping("/{contactId}/social/messages")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    public ResponseEntity<PagedSocialMessagesResponse> getMessages(
            @PathVariable UUID contactId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID tenantId = TenantContext.getTenantId();
        log.info("[SocialContactCtrl] Pobieranie wiadomości: contactId={}, tenant={}, page={}, size={}",
                contactId, tenantId, page, size);

        List<SocialMessage> all = socialMessageRepository.findByContactId(contactId, tenantId);

        long totalElements = all.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        int fromIndex = Math.min(page * size, (int) totalElements);
        int toIndex = Math.min(fromIndex + size, (int) totalElements);

        List<SocialMessageResponse> pageContent = all.subList(fromIndex, toIndex).stream()
                .map(msg -> new SocialMessageResponse(
                        msg.getMessageId(),
                        msg.getPlatform() != null ? msg.getPlatform().name() : null,
                        msg.getDirection(),
                        msg.getContent(),
                        msg.getAttachments(),
                        msg.getSentAt(),
                        msg.getSenderExternalId()
                ))
                .toList();

        return ResponseEntity.ok(new PagedSocialMessagesResponse(pageContent, page, size, totalElements, totalPages));
    }
}
