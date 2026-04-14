package com.contactcenter.domain.email;

import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.EmailMessageRepository;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.domain.service.RoutingService;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Konsumer eventów email z kolejki RabbitMQ {@code cc.queue.email-events}.
 *
 * <p>Nasłuchuje eventu {@code email.queued} (publikowanego przez {@link EmailRoutingService})
 * i tworzy kontakt kanału EMAIL w bazie danych, a następnie publikuje event
 * {@code contact.queued} do {@link RoutingService}.
 *
 * <p>Pozostałe typy eventów ({@code email.received}, {@code email.sent}, {@code email.assigned})
 * są logowane i ignorowane – nie wymagają tworzenia kontaktu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailContactCreator {

    private final ContactRepository contactRepository;
    private final EmailMessageRepository emailMessageRepository;
    private final RabbitTemplate rabbitTemplate;

    // =========================================================================
    // RabbitMQ Listener
    // =========================================================================

    /**
     * Obsługuje eventy z kolejki {@code cc.queue.email-events}.
     *
     * <p>Dla eventu {@code email.queued}:
     * <ol>
     *   <li>Tworzy {@link Contact} z kanałem EMAIL i statusem QUEUED</li>
     *   <li>Aktualizuje {@link EmailMessage#getContactId()} powiązując wiadomość z kontaktem</li>
     *   <li>Publikuje {@link ContactQueuedMessage} do {@code cc.queue.contact-routing},
     *       gdzie {@link RoutingService} przydzieli agenta</li>
     * </ol>
     *
     * @param event event email z RabbitMQ (Jackson deserializacja)
     */
    @Transactional
    @RabbitListener(queues = RabbitMQConfig.QUEUE_EMAIL_EVENTS)
    public void onEmailEvent(EmailEventPublisher.EmailEvent event) {
        if (event.eventType() != EmailEventPublisher.EventType.QUEUED) {
            log.debug("[EmailContact] Ignoruję event: type={}, messageId={}",
                    event.eventType(), event.messageId());
            return;
        }

        if (event.queueId() == null) {
            log.warn("[EmailContact] Event email.queued bez queueId – wiadomość nie zostanie zroutowana: " +
                    "messageId={}, tenant={}", event.messageId(), event.tenantId());
            return;
        }

        UUID tenantId  = event.tenantId();
        UUID messageId = event.messageId();
        UUID queueId   = event.queueId();

        log.info("[EmailContact] Tworzę kontakt EMAIL: messageId={}, queueId={}, tenant={}",
                messageId, queueId, tenantId);

        // Ustaw TenantContext – wymagane przez TenantAwareRepository.assertSameTenant()
        TenantContext.Snapshot snapshot = new TenantContext.Snapshot(tenantId, null, null, "SYSTEM");
        TenantContext.restore(snapshot);

        try {
            UUID contactId = createContact(tenantId, queueId, event);
            linkMessageToContact(messageId, contactId, tenantId);
            publishContactQueued(contactId, queueId, tenantId);

            log.info("[EmailContact] Kontakt EMAIL gotowy do routingu: contactId={}, queueId={}, messageId={}",
                    contactId, queueId, messageId);

        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Tworzy encję {@link Contact} z kanałem EMAIL i zapisuje ją w bazie.
     *
     * @param tenantId UUID tenanta
     * @param queueId  UUID kolejki docelowej
     * @param event    event email (źródło danych do channelMetadata)
     * @return UUID nowo utworzonego kontaktu
     */
    private UUID createContact(UUID tenantId, UUID queueId, EmailEventPublisher.EmailEvent event) {
        UUID contactId = UUID.randomUUID();
        Instant now = Instant.now();

        Map<String, Object> channelMetadata = new HashMap<>();
        channelMetadata.put("emailMessageId", event.messageId() != null ? event.messageId().toString() : null);
        channelMetadata.put("subject",        event.subject());
        channelMetadata.put("fromAddress",    event.fromAddress());

        Contact contact = Contact.builder()
                .contactId(contactId)
                .tenantId(tenantId)
                .queueId(queueId)
                .channel("EMAIL")
                .direction("INBOUND")
                .status("QUEUED")
                .remoteAddress(event.fromAddress())
                .queuedAt(now)
                .startedAt(now)
                .channelMetadata(channelMetadata)
                .createdAt(now)
                .updatedAt(now)
                .build();

        contactRepository.insert(contact);
        return contactId;
    }

    /**
     * Ustawia {@code contact_id} na wiadomości email, łącząc ją z kontaktem.
     *
     * @param messageId UUID wiadomości email
     * @param contactId UUID powiązanego kontaktu
     * @param tenantId  UUID tenanta
     */
    private void linkMessageToContact(UUID messageId, UUID contactId, UUID tenantId) {
        Optional<EmailMessage> messageOpt = emailMessageRepository.findById(messageId);
        if (messageOpt.isEmpty()) {
            log.warn("[EmailContact] Nie znaleziono wiadomości email: messageId={}", messageId);
            return;
        }
        EmailMessage message = messageOpt.get();
        message.setContactId(contactId);
        emailMessageRepository.update(message);
        log.debug("[EmailContact] Powiązano wiadomość {} z kontaktem {}", messageId, contactId);
    }

    /**
     * Publikuje event {@code contact.queued} do kolejki routingu kontaktów.
     *
     * @param contactId UUID kontaktu
     * @param queueId   UUID kolejki docelowej
     * @param tenantId  UUID tenanta
     */
    private void publishContactQueued(UUID contactId, UUID queueId, UUID tenantId) {
        ContactQueuedMessage msg = new ContactQueuedMessage(contactId, queueId, tenantId);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, "contact.queued", msg);
        log.debug("[EmailContact] Opublikowano contact.queued: contactId={}", contactId);
    }
}
