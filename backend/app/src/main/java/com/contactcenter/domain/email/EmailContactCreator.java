package com.contactcenter.domain.email;

import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.customer.CustomerService;
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
import java.util.List;
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
    private final CustomerService customerService;
    private final EmailMessageRepository emailMessageRepository;
    private final RabbitTemplate rabbitTemplate;
    private final EmailEmlService emailEmlService;

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
        if (event.eventType() == EmailEventPublisher.EventType.SENT) {
            handleEmailSent(event);
            return;
        }
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
            generateAndStoreEml(messageId, contactId, tenantId);
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

        // Szukaj klienta po adresie email nadawcy
        UUID customerId = null;
        String rawFrom = event.fromAddress();
        if (rawFrom != null) {
            String emailAddr = extractEmailAddress(rawFrom);
            if (emailAddr != null) {
                customerId = customerService.findByEmail(emailAddr, tenantId)
                        .map(Customer::getCustomerId)
                        .orElse(null);
                if (customerId != null) {
                    log.info("[EmailContact] Znaleziono klienta: customerId={}, emailAddr={}", customerId, emailAddr);
                }
            }
        }

        Map<String, Object> channelMetadata = new HashMap<>();
        channelMetadata.put("emailMessageId", event.messageId() != null ? event.messageId().toString() : null);
        channelMetadata.put("subject",        event.subject());
        channelMetadata.put("fromAddress",    event.fromAddress());

        Contact contact = Contact.builder()
                .contactId(contactId)
                .tenantId(tenantId)
                .customerId(customerId)
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
     * Generuje plik EML z wiadomości email i zapisuje klucz S3 w kolumnie {@code recording_url} kontaktu.
     *
     * <p>Operacja jest "best-effort" – błąd generowania EML nie blokuje routingu kontaktu.
     * Wyjątki są tylko logowane.
     *
     * @param messageId UUID wiadomości email
     * @param contactId UUID powiązanego kontaktu
     * @param tenantId  UUID tenanta
     */
    private void generateAndStoreEml(UUID messageId, UUID contactId, UUID tenantId) {
        try {
            Optional<EmailMessage> msgOpt = emailMessageRepository.findById(messageId);
            if (msgOpt.isEmpty()) {
                log.warn("[EmailContact] Nie znaleziono wiadomości do generowania EML: messageId={}", messageId);
                return;
            }
            String s3Key = emailEmlService.generateAndUpload(msgOpt.get(), contactId, tenantId);
            contactRepository.updateRecordingUrl(contactId, tenantId, s3Key);
            log.info("[EmailContact] EML zapisany w S3: contactId={}, s3Key={}", contactId, s3Key);
        } catch (Exception e) {
            // EML jest best-effort – nie blokuje routingu kontaktu
            log.error("[EmailContact] Błąd generowania EML: contactId={}, error={}", contactId, e.getMessage(), e);
        }
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

    /**
     * Obsługuje event {@code email.sent} – zamyka kontakt EMAIL po wysłaniu odpowiedzi.
     *
     * <p>Zmienia status kontaktu na COMPLETED i ustawia {@code ended_at}, o ile kontakt
     * nie jest już w stanie końcowym (COMPLETED lub ABANDONED).
     * TenantContext jest ustawiany ręcznie (wątek RabbitMQ nie ma aktywnego kontekstu HTTP).
     *
     * @param event event email.sent z RabbitMQ
     */
    private void handleEmailSent(EmailEventPublisher.EmailEvent event) {
        if (event.contactId() == null) {
            log.warn("[EmailContact] email.sent bez contactId – kontakt OUTBOUND nie zostanie utworzony: messageId={}, tenant={}", event.messageId(), event.tenantId());
            return;
        }

        UUID tenantId  = event.tenantId();
        UUID contactId = event.contactId();

        TenantContext.Snapshot snapshot = new TenantContext.Snapshot(tenantId, null, null, "SYSTEM");
        TenantContext.restore(snapshot);

        try {
            contactRepository.findById(contactId, tenantId).ifPresent(contact -> {
                if (!"COMPLETED".equals(contact.getStatus()) && !"ABANDONED".equals(contact.getStatus())) {
                    Instant now = Instant.now();
                    contact.setStatus("COMPLETED");
                    contact.setEndedAt(now);
                    contact.setUpdatedAt(now);
                    contactRepository.update(contact);
                    log.info("[EmailContact] Kontakt EMAIL zakończony po wysłaniu odpowiedzi: contactId={}", contactId);
                }
            });

            // Utwórz kontakt OUTBOUND EMAIL
            createOutboundContact(tenantId, contactId, event);

        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Tworzy kontakt OUTBOUND EMAIL po wysłaniu odpowiedzi przez agenta.
     *
     * <p>Nowy kontakt dziedziczy {@code customerId} i {@code queueId} z kontaktu przychodzącego
     * i jest od razu oznaczony jako COMPLETED.
     *
     * @param tenantId        UUID tenanta
     * @param inboundContactId UUID kontaktu przychodzącego (powiązanego z oryginalną wiadomością)
     * @param event           event email.sent z RabbitMQ
     */
    private void createOutboundContact(UUID tenantId, UUID inboundContactId,
                                        EmailEventPublisher.EmailEvent event) {
        Optional<Contact> inboundOpt = contactRepository.findById(inboundContactId, tenantId);
        if (inboundOpt.isEmpty()) {
            log.warn("[EmailContact] Nie można utworzyć kontaktu OUTBOUND – brak kontaktu przychodzącego: {}",
                    inboundContactId);
            return;
        }
        Contact inbound = inboundOpt.get();

        // Guard przed duplikatami – sprawdź czy kontakt OUTBOUND dla tego inboundContactId
        // już istnieje. Zabezpiecza przed wielokrotnym wywołaniem email.sent dla tej samej
        // wiadomości (np. retry kolejki RabbitMQ lub wielokrotny klik w UI).
        List<Contact> existingOutbound = contactRepository.findByChannelMetadataValue(
                "inboundContactId", inboundContactId.toString(), tenantId);
        if (!existingOutbound.isEmpty()) {
            log.warn("[EmailContact] Kontakt OUTBOUND dla inboundContactId={} już istnieje ({}), " +
                     "pomijam tworzenie duplikatu.",
                    inboundContactId, existingOutbound.get(0).getContactId());
            return;
        }

        UUID customerId = inbound.getCustomerId();

        // Pobierz adres odbiorcy z wiadomości OUTBOUND
        String recipientAddress = null;
        if (event.messageId() != null) {
            Optional<EmailMessage> outboundMsgOpt = emailMessageRepository.findById(event.messageId());
            recipientAddress = outboundMsgOpt
                    .map(EmailMessage::getToAddress)
                    .orElse(null);
        }

        Instant now = Instant.now();
        UUID outboundContactId = UUID.randomUUID();

        Map<String, Object> channelMetadata = new HashMap<>();
        channelMetadata.put("emailMessageId",   event.messageId() != null ? event.messageId().toString() : null);
        channelMetadata.put("subject",          event.subject());
        channelMetadata.put("fromAddress",      event.fromAddress());
        channelMetadata.put("inboundContactId", inboundContactId.toString());

        Contact outbound = Contact.builder()
                .contactId(outboundContactId)
                .tenantId(tenantId)
                .customerId(customerId)
                .queueId(inbound.getQueueId())
                .channel("EMAIL")
                .direction("OUTBOUND")
                .status("COMPLETED")
                .agentId(event.agentId())
                .remoteAddress(recipientAddress)
                .queuedAt(now)
                .startedAt(now)
                .endedAt(now)
                .channelMetadata(channelMetadata)
                .createdAt(now)
                .updatedAt(now)
                .build();

        contactRepository.insert(outbound);

        log.info("[EmailContact] Kontakt EMAIL OUTBOUND utworzony: contactId={}, inboundContactId={}, customer={}",
                outboundContactId, inboundContactId, customerId);

        // Generuj EML dla wiadomości OUTBOUND i zapisz klucz S3 w recording_url
        if (event.messageId() != null) {
            generateAndStoreEml(event.messageId(), outboundContactId, tenantId);
        }
    }

    /**
     * Parsuje adres email z formatu RFC 2822 {@code Display Name <email@domain>}.
     *
     * @param raw surowy string adresu (może być w formacie RFC 2822 lub czysty email)
     * @return adres email bez nawiasów trójkątnych lub surowy string po trim(); null gdy raw jest null
     */
    private String extractEmailAddress(String raw) {
        if (raw == null) return null;
        // Format "Display Name <email@domain>"
        int lt = raw.lastIndexOf('<');
        int gt = raw.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            return raw.substring(lt + 1, gt).trim();
        }
        // Czysty adres email
        return raw.trim();
    }
}
