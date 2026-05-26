package com.contactcenter.domain.email;

import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.email.EmailTemplateService.RenderedEmailTemplate;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.EmailMessageRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.security.TenantContext;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Serwis wysyłki wiadomości email przez SMTP.
 *
 * <p>Obsługuje:
 * <ul>
 *   <li>Wysyłkę odpowiedzi na wiadomości przychodzące (In-Reply-To, References)</li>
 *   <li>Generowanie unikalnego Message-ID dla każdej wysyłanej wiadomości</li>
 *   <li>Zapis wysłanej wiadomości jako OUTBOUND {@link EmailMessage} w DB</li>
 *   <li>Publikację eventu {@code email.sent} na RabbitMQ</li>
 * </ul>
 *
 * <p>Hasła SMTP są deszyfrowane tuż przed użyciem i NIE są logowane.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSendService {

    private final EmailMessageRepository emailMessageRepository;
    private final EmailEventPublisher emailEventPublisher;
    private final TenantRepository tenantRepository;
    private final EmailEncryptionService encryptionService;
    private final EmailTemplateService emailTemplateService;
    private final TemplateVariableResolver templateVariableResolver;

    // =========================================================================
    // Wysyłka odpowiedzi
    // =========================================================================

    /**
     * Wysyła odpowiedź na istniejącą wiadomość email.
     *
     * <p>Nagłówki {@code In-Reply-To} i {@code References} są ustawiane na podstawie
     * oryginalnej wiadomości dla poprawnego wątkowania w klientach email.
     *
     * @param tenantId          UUID tenanta (z TenantContext)
     * @param originalMessageId UUID wiadomości oryginalnej (PK tabeli email_message)
     * @param bodyHtml          treść odpowiedzi w HTML
     * @param subject           temat odpowiedzi (przekazany przez klienta lub auto-generowany)
     * @param agentId           UUID agenta wysyłającego odpowiedź
     * @return zapisana encja wiadomości OUTBOUND
     * @throws ResourceNotFoundException gdy oryginalna wiadomość nie istnieje
     * @throws EmailSendException        gdy wysyłka SMTP się nie powiedzie
     */
    @Transactional
    public EmailMessage sendReply(UUID tenantId, UUID originalMessageId,
                                   String bodyHtml, String subject, UUID agentId) {

        // 1. Pobierz oryginalną wiadomość
        EmailMessage original = emailMessageRepository.findById(originalMessageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Wiadomość email nie istnieje: " + originalMessageId));

        // 2. Pobierz konfigurację SMTP tenanta
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant nie istnieje: " + tenantId));

        EmailAccountConfig config = EmailAccountConfig.fromTenantConfig(tenant.getConfig());
        if (config == null) {
            throw new EmailSendException("Tenant " + tenantId + " nie ma skonfigurowanego konta SMTP");
        }

        String password;
        try {
            password = encryptionService.decrypt(config.getPassword());
        } catch (EmailEncryptionService.EmailEncryptionException e) {
            throw new EmailSendException("Nie można odszyfrować hasła SMTP", e);
        }

        // 3. Wyznacz temat (dodaj "Re: " jeśli brak)
        String replySubject = subject != null && !subject.isBlank()
                ? subject
                : buildReplySubject(original.getSubject());

        // 4. Generuj Message-ID dla odpowiedzi
        String newMessageId = "<" + UUID.randomUUID() + "@" + extractDomain(config.getUsername()) + ">";

        // 5. Wyślij przez SMTP
        log.info("[EmailSend] Wysyłam odpowiedź: originalId={}, to={}, tenant={}, agent={}",
                originalMessageId, original.getFromAddress(), tenantId, agentId);

        try {
            sendSmtp(config, password, config.getUsername(), original.getFromAddress(),
                    replySubject, bodyHtml, newMessageId,
                    original.getMessageIdHeader(), // In-Reply-To
                    buildReferences(original));    // References
        } catch (MessagingException e) {
            log.error("[EmailSend] Błąd SMTP: tenant={}, to={}, error={}",
                    tenantId, original.getFromAddress(), e.getMessage(), e);
            throw new EmailSendException("Wysyłka SMTP nie powiodła się: " + e.getMessage(), e);
        }

        // 6. Zapisz jako OUTBOUND w DB
        EmailMessage reply = EmailMessage.builder()
                .tenantId(tenantId)
                .contactId(original.getContactId())
                .direction(EmailMessage.Direction.OUTBOUND.name())
                .fromAddress(config.getUsername())
                .toAddress(original.getFromAddress())
                .subject(replySubject)
                .bodyHtml(bodyHtml)
                .messageIdHeader(newMessageId)
                .inReplyTo(original.getMessageIdHeader())
                .sentAt(Instant.now())
                .deliveryStatus(EmailMessage.DeliveryStatus.SENT.name())
                .build();

        EmailMessage saved = emailMessageRepository.save(reply);

        // 7. Publikuj event
        emailEventPublisher.publishSent(saved, agentId);

        log.info("[EmailSend] Odpowiedź wysłana i zapisana: replyId={}, originalId={}, agent={}",
                saved.getId(), originalMessageId, agentId);

        return saved;
    }

    // =========================================================================
    // Wysyłka z szablonem
    // =========================================================================

    /**
     * Wysyła odpowiedź na istniejącą wiadomość email z użyciem szablonu Mustache.
     *
     * <p>Renderuje szablon (temat + treść HTML) z podanymi zmiennymi i wywołuje
     * {@link #sendReply} z wyrenderowanymi wartościami.
     *
     * @param tenantId          UUID tenanta (z TenantContext)
     * @param originalMessageId UUID wiadomości oryginalnej (PK tabeli email_message)
     * @param templateId        UUID szablonu email (z tabeli email_template)
     * @param variables         mapa zmiennych do podstawienia w szablonie
     * @param agentId           UUID agenta wysyłającego odpowiedź
     * @return zapisana encja wiadomości OUTBOUND
     * @throws ResourceNotFoundException gdy oryginalna wiadomość lub szablon nie istnieje
     * @throws TemplateRenderException   gdy brakuje wymaganych zmiennych szablonu
     * @throws EmailSendException        gdy wysyłka SMTP się nie powiedzie
     */
    @Transactional
    public EmailMessage sendReplyWithTemplate(UUID tenantId, UUID originalMessageId,
                                              UUID templateId, java.util.Map<String, Object> variables,
                                              UUID agentId) {

        log.info("[EmailSend] Wysyłam odpowiedź z szablonem: templateId={}, originalId={}, tenant={}, agent={}",
                templateId, originalMessageId, tenantId, agentId);

        // Pobierz contactId z oryginalnej wiadomości, aby auto-uzupełnić predefiniowane zmienne
        EmailMessage original = emailMessageRepository.findById(originalMessageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Wiadomość email nie istnieje: " + originalMessageId));

        Map<String, Object> predefined = templateVariableResolver.resolveForContext(original.getContactId(), agentId);
        Map<String, Object> allVars = templateVariableResolver.mergeWithCustom(predefined, variables);

        // Renderuj szablon – predefiniowane zmienne są już uzupełnione
        RenderedEmailTemplate rendered = emailTemplateService.render(templateId, allVars);

        // Wyślij przez istniejącą metodę z wyrenderowanymi wartościami
        return sendReply(tenantId, originalMessageId, rendered.bodyHtml(), rendered.subject(), agentId);
    }

    // =========================================================================
    // Wysyłka nowej wiadomości (ad hoc)
    // =========================================================================

    /**
     * Wysyła nową wiadomość email do podanego odbiorcy – bez powiązania z istniejącym wątkiem.
     *
     * <p>Nie ustawia nagłówków {@code In-Reply-To} ani {@code References}, dzięki czemu
     * wiadomość tworzy nowy wątek w kliencie email odbiorcy.
     *
     * @param tenantId  UUID tenanta (z TenantContext)
     * @param toAddress adres odbiorcy (format RFC 2822)
     * @param subject   temat wiadomości
     * @param bodyHtml  treść wiadomości w HTML
     * @param agentId   UUID agenta wysyłającego wiadomość
     * @return zapisana encja wiadomości OUTBOUND
     * @throws ResourceNotFoundException gdy tenant nie istnieje
     * @throws EmailSendException        gdy brak konfiguracji SMTP lub wysyłka się nie powiedzie
     */
    @Transactional
    public EmailMessage sendNew(UUID tenantId, String toAddress, String subject,
                                String bodyHtml, UUID agentId) {

        // 1. Pobierz konfigurację SMTP tenanta
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant nie istnieje: " + tenantId));

        EmailAccountConfig config = EmailAccountConfig.fromTenantConfig(tenant.getConfig());
        if (config == null) {
            throw new EmailSendException("Tenant " + tenantId + " nie ma skonfigurowanego konta SMTP");
        }

        String password;
        try {
            password = encryptionService.decrypt(config.getPassword());
        } catch (EmailEncryptionService.EmailEncryptionException e) {
            throw new EmailSendException("Nie można odszyfrować hasła SMTP", e);
        }

        // 2. Generuj nowy Message-ID (bez In-Reply-To i References – nowy wątek)
        String newMessageId = "<" + UUID.randomUUID() + "@" + extractDomain(config.getUsername()) + ">";

        // 3. Wyślij przez SMTP
        log.info("[EmailSend] Wysyłam nową wiadomość: to={}, tenant={}, agent={}", toAddress, tenantId, agentId);

        try {
            sendSmtp(config, password, config.getUsername(), toAddress,
                    subject, bodyHtml, newMessageId, null, null);
        } catch (MessagingException e) {
            log.error("[EmailSend] Błąd SMTP: tenant={}, to={}, error={}",
                    tenantId, toAddress, e.getMessage(), e);
            throw new EmailSendException("Wysyłka SMTP nie powiodła się: " + e.getMessage(), e);
        }

        // 4. Zapisz jako OUTBOUND w DB (contactId = null dla ad hoc)
        EmailMessage outbound = EmailMessage.builder()
                .tenantId(tenantId)
                .contactId(null)
                .direction(EmailMessage.Direction.OUTBOUND.name())
                .fromAddress(config.getUsername())
                .toAddress(toAddress)
                .subject(subject)
                .bodyHtml(bodyHtml)
                .messageIdHeader(newMessageId)
                .sentAt(Instant.now())
                .deliveryStatus(EmailMessage.DeliveryStatus.SENT.name())
                .build();

        EmailMessage saved = emailMessageRepository.save(outbound);

        // 5. Publikuj event email.sent
        emailEventPublisher.publishSent(saved, agentId);

        log.info("[EmailSend] Nowa wiadomość wysłana i zapisana: id={}, to={}, agent={}",
                saved.getId(), toAddress, agentId);

        return saved;
    }

    // =========================================================================
    // SMTP
    // =========================================================================

    /**
     * Wysyła wiadomość przez Jakarta Mail SMTP.
     *
     * @param config       konfiguracja SMTP
     * @param password     hasło (odszyfrowane, NIE logujemy)
     * @param from         adres nadawcy
     * @param to           adres odbiorcy
     * @param subject      temat
     * @param bodyHtml     treść HTML
     * @param messageId    nagłówek Message-ID (nowy UUID)
     * @param inReplyTo    nagłówek In-Reply-To (z oryginalnej wiadomości)
     * @param references   nagłówek References (łańcuch wątku)
     */
    void sendSmtp(EmailAccountConfig config, String password,
                  String from, String to, String subject, String bodyHtml,
                  String messageId, String inReplyTo, String references)
            throws MessagingException {

        Properties props = buildSmtpProperties(config);
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getUsername(), password);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
        message.setSubject(subject, "UTF-8");

        // Ustaw nagłówki wątkowania RFC 2822
        message.setHeader("Message-ID", messageId);
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            message.setHeader("In-Reply-To", inReplyTo);
        }
        if (references != null && !references.isBlank()) {
            message.setHeader("References", references);
        }

        // Treść HTML + alternatywa tekstowa
        MimeMultipart multipart = new MimeMultipart("alternative");

        // Wersja tekstowa (stripped HTML – uproszczona)
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(stripHtml(bodyHtml), "UTF-8");
        multipart.addBodyPart(textPart);

        // Wersja HTML
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(bodyHtml, "text/html; charset=UTF-8");
        multipart.addBodyPart(htmlPart);

        message.setContent(multipart);

        Transport.send(message);
    }

    private Properties buildSmtpProperties(EmailAccountConfig config) {
        Properties props = new Properties();
        if (config.isSmtpSsl()) {
            // SMTPS (SSL/TLS na porcie 465)
            props.put("mail.smtp.host", config.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.trust", "*");
            props.put("mail.smtp.auth", "true");
        } else {
            // STARTTLS (na porcie 587)
            props.put("mail.smtp.host", config.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.ssl.trust", "*");
            props.put("mail.smtp.auth", "true");
        }
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.connectiontimeout", "10000");
        return props;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private String buildReplySubject(String originalSubject) {
        if (originalSubject == null || originalSubject.isBlank()) {
            return "Re: (brak tematu)";
        }
        if (originalSubject.toLowerCase().startsWith("re:")) {
            return originalSubject;
        }
        return "Re: " + originalSubject;
    }

    private String buildReferences(EmailMessage original) {
        if (original.getMessageIdHeader() == null) {
            return null;
        }
        // References = istniejące References + Message-ID oryginalnej wiadomości
        // Uproszczona implementacja: tylko Message-ID poprzedniej wiadomości
        return original.getMessageIdHeader();
    }

    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) {
            return "contactcenter.local";
        }
        return email.substring(email.indexOf('@') + 1);
    }

    /**
     * Uproszczone stripowanie tagów HTML do plain text.
     * Używane do generowania alternatywy tekstowej wiadomości HTML.
     */
    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").trim();
    }

    // =========================================================================
    // Wyjątek
    // =========================================================================

    /** Wyjątek wysyłki email przez SMTP. */
    public static class EmailSendException extends RuntimeException {
        public EmailSendException(String message) {
            super(message);
        }

        public EmailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
