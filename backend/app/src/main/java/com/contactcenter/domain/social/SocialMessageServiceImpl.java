package com.contactcenter.domain.social;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.infrastructure.social.SocialAdapterRegistry;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class SocialMessageServiceImpl implements SocialMessageService {

    private final SocialIntegrationRepository socialIntegrationRepository;
    private final SocialMessageRepository socialMessageRepository;
    private final ContactService contactService;
    private final SocialAdapterRegistry adapterRegistry;
    private final RabbitTemplate rabbitTemplate;

    // =========================================================================
    // Przetwarzanie przychodzących wiadomości
    // =========================================================================

    @Override
    @Transactional
    public void processIncomingMessage(IncomingSocialMessage incoming) {
        log.info("[SocialMessage] Przetwarzam przychodzącą wiadomość: platform={}, pageId={}, " +
                 "sender={}, externalMsgId={}",
                incoming.platform(), incoming.pageId(), incoming.senderExternalId(),
                incoming.externalMessageId());

        // 1. Znajdź integrację (cross-tenant lookup – brak JWT)
        Optional<SocialIntegration> integrationOpt = socialIntegrationRepository
                .findByPlatformAndPageId(incoming.platform(), incoming.pageId());

        if (integrationOpt.isEmpty()) {
            log.warn("[SocialMessage] Brak integracji dla platform={}, pageId={} – ignoruję wiadomość",
                    incoming.platform(), incoming.pageId());
            return;
        }

        SocialIntegration integration = integrationOpt.get();
        UUID tenantId = integration.getTenantId();

        // 2. Ustaw TenantContext (wymagany przez TenantAwareRepository)
        TenantContext.Snapshot snapshot = new TenantContext.Snapshot(tenantId, null, null, "SYSTEM");
        TenantContext.restore(snapshot);

        try {
            processIncomingWithTenantContext(incoming, integration, tenantId);
        } finally {
            // KRYTYCZNE: zawsze czyść TenantContext po zakończeniu przetwarzania
            TenantContext.clear();
        }
    }

    /**
     * Wewnętrzna logika przetwarzania – wywoływana po ustawieniu TenantContext.
     */
    private void processIncomingWithTenantContext(IncomingSocialMessage incoming,
                                                   SocialIntegration integration,
                                                   UUID tenantId) {
        // 3. Sprawdź idempotentność
        Optional<SocialMessage> existing = socialMessageRepository
                .findByExternalMessageId(incoming.externalMessageId(), tenantId);
        if (existing.isPresent()) {
            log.info("[SocialMessage] Duplikat pominięty: externalMsgId={}, tenant={}",
                    incoming.externalMessageId(), tenantId);
            return;
        }

        // 4. Znajdź lub utwórz kontakt
        String channel = platformToChannel(incoming.platform());
        Optional<Contact> activeContact = contactService
                .findActiveSocialContact(tenantId, incoming.senderExternalId(), channel);

        boolean isNewContact = activeContact.isEmpty();
        UUID contactId;

        if (isNewContact) {
            contactId = createSocialContact(tenantId, incoming, channel);
            log.info("[SocialMessage] Nowy kontakt social: contactId={}, channel={}, sender={}",
                    contactId, channel, incoming.senderExternalId());
        } else {
            contactId = activeContact.get().getContactId();
            log.info("[SocialMessage] Dołączam do istniejącego kontaktu: contactId={}, sender={}",
                    contactId, incoming.senderExternalId());
        }

        // 5. Zapisz wiadomość
        String attachmentsJson = serializeAttachments(incoming.attachments());
        SocialMessage message = SocialMessage.builder()
                .tenantId(tenantId)
                .contactId(contactId)
                .integrationId(integration.getIntegrationId())
                .platform(incoming.platform())
                .direction(SocialMessage.Direction.INBOUND.name())
                .externalMessageId(incoming.externalMessageId())
                .senderExternalId(incoming.senderExternalId())
                .content(incoming.content())
                .attachments(attachmentsJson)
                .sentAt(incoming.sentAt() != null ? incoming.sentAt() : Instant.now())
                .receivedAt(Instant.now())
                .build();

        socialMessageRepository.save(message);
        log.info("[SocialMessage] Wiadomość zapisana: messageId={}, contactId={}, tenant={}",
                message.getMessageId(), contactId, tenantId);

        // 6. Dla nowego kontaktu – opublikuj do routingu (brak kolejki dla social → null queueId)
        if (isNewContact) {
            publishContactQueued(contactId, null, tenantId);
        }
    }

    // =========================================================================
    // Wysyłka wiadomości przez agenta
    // =========================================================================

    @Override
    @Transactional
    public void sendMessage(UUID contactId, UUID tenantId, String content, List<String> attachmentUrls) {
        log.info("[SocialMessage] Wysyłam wiadomość: contactId={}, tenant={}", contactId, tenantId);

        // 1. Pobierz kontakt
        Contact contact = contactService.findContactEntity(contactId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Kontakt nie istnieje: contactId=" + contactId));

        String channel = contact.getChannel();
        SocialPlatform platform = channelToPlatform(channel);
        if (platform == null) {
            throw new IllegalArgumentException(
                    "Kontakt nie jest kanałem social media: channel=" + channel);
        }

        // 2. Znajdź integrację dla tego tenanta i platformy
        List<SocialIntegration> integrations = socialIntegrationRepository
                .findByTenantIdAndPlatform(tenantId, platform);

        if (integrations.isEmpty()) {
            throw new IllegalStateException(
                    "Brak aktywnej integracji social dla platformy: " + platform + ", tenant=" + tenantId);
        }

        // Użyj pierwszej aktywnej integracji
        SocialIntegration integration = integrations.stream()
                .filter(i -> "ACTIVE".equals(i.getWebhookStatus()))
                .findFirst()
                .orElse(integrations.get(0));

        // 3. Wyślij przez adapter
        String recipientExternalId = contact.getRemoteAddress();
        adapterRegistry.getAdapter(platform)
                .sendMessage(integration.getIntegrationId(), recipientExternalId, content,
                        attachmentUrls != null ? attachmentUrls : List.of());

        // 4. Zapisz wiadomość OUTBOUND
        SocialMessage outbound = SocialMessage.builder()
                .tenantId(tenantId)
                .contactId(contactId)
                .integrationId(integration.getIntegrationId())
                .platform(platform)
                .direction(SocialMessage.Direction.OUTBOUND.name())
                .externalMessageId("OUTBOUND-" + UUID.randomUUID())
                .senderExternalId(integration.getPageId())
                .content(content)
                .sentAt(Instant.now())
                .build();

        socialMessageRepository.save(outbound);
        log.info("[SocialMessage] Wiadomość OUTBOUND zapisana: contactId={}, platform={}", contactId, platform);
    }

    // =========================================================================
    // Odczyt historii wiadomości
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<SocialMessage> getRecentMessagesForContact(UUID contactId, UUID tenantId) {
        return socialMessageRepository.findByContactId(contactId, tenantId);
    }

    // =========================================================================
    // BE-113: Retencja – odcięcie referencji (EPIC-29)
    // =========================================================================

    @Override
    @Transactional
    public int detachContactReferences(UUID tenantId, List<UUID> contactIds) {
        return socialMessageRepository.detachContactReferences(tenantId, contactIds);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Tworzy nowy kontakt dla kanału social media.
     *
     * @param tenantId UUID tenanta
     * @param incoming dane przychodzącego zdarzenia
     * @param channel  kanał (np. "SOCIAL_FACEBOOK")
     * @return UUID nowo utworzonego kontaktu
     */
    private UUID createSocialContact(UUID tenantId, IncomingSocialMessage incoming, String channel) {
        UUID contactId = UUID.randomUUID();
        Instant now = Instant.now();

        Contact contact = Contact.builder()
                .contactId(contactId)
                .tenantId(tenantId)
                .channel(channel)
                .direction("INBOUND")
                .status("QUEUED")
                .remoteAddress(incoming.senderExternalId())
                .queuedAt(now)
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        contactService.insertContact(contact);
        return contactId;
    }

    /**
     * Publikuje event contact.queued do kolejki routingu.
     *
     * <p>Dla kanałów social media kolejka może być null (brak konfiguracji routingu
     * przez reguły kolejkowe). RoutingService obsłuży brak kolejki przez domyślną kolejkę.
     *
     * @param contactId UUID kontaktu
     * @param queueId   UUID kolejki (może być null)
     * @param tenantId  UUID tenanta
     */
    private void publishContactQueued(UUID contactId, UUID queueId, UUID tenantId) {
        ContactQueuedMessage msg = new ContactQueuedMessage(contactId, queueId, tenantId);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, "contact.queued", msg);
        log.info("[SocialMessage] Opublikowano contact.queued: contactId={}, queueId={}, tenant={}",
                contactId, queueId, tenantId);
    }

    /**
     * Mapuje platformę social media na kanał kontaktu.
     *
     * @param platform platforma
     * @return nazwa kanału (np. "SOCIAL_FACEBOOK")
     */
    private String platformToChannel(SocialPlatform platform) {
        return switch (platform) {
            case FACEBOOK -> "SOCIAL_FACEBOOK";
            case INSTAGRAM -> "SOCIAL_INSTAGRAM";
            case WHATSAPP -> "SOCIAL_WHATSAPP";
        };
    }

    /**
     * Mapuje kanał kontaktu na platformę social media.
     *
     * @param channel kanał kontaktu
     * @return platforma lub null gdy kanał nie jest social
     */
    private SocialPlatform channelToPlatform(String channel) {
        if (channel == null) return null;
        return switch (channel) {
            case "SOCIAL_FACEBOOK" -> SocialPlatform.FACEBOOK;
            case "SOCIAL_INSTAGRAM" -> SocialPlatform.INSTAGRAM;
            case "SOCIAL_WHATSAPP" -> SocialPlatform.WHATSAPP;
            default -> null;
        };
    }

    /**
     * Serializuje listę załączników do JSON string dla kolumny JSONB.
     *
     * @param attachments lista map z metadanymi załączników (może być null)
     * @return JSON string lub "[]" gdy pusta/null
     */
    private String serializeAttachments(List<java.util.Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "[]";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(attachments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[SocialMessage] Błąd serializacji załączników: {}", e.getMessage());
            return "[]";
        }
    }
}
