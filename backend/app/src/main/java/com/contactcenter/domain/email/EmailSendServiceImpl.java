package com.contactcenter.domain.email;

import com.contactcenter.api.email.dto.EmailReplyRequest;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.activation.DataHandler;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class EmailSendServiceImpl implements EmailSendService {

    private final EmailMessageRepository emailMessageRepository;
    private final EmailEventPublisher emailEventPublisher;
    private final TenantService tenantService;
    private final EmailEncryptionService encryptionService;
    private final EmailTemplateService emailTemplateService;
    private final TemplateVariableResolver templateVariableResolver;
    private final EmailAttachmentStorageService attachmentStorageService;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Wysyłka odpowiedzi
    // =========================================================================

    @Override
    @Transactional
    public EmailMessage sendReply(UUID tenantId, UUID originalMessageId,
                                   String bodyHtml, String subject, UUID agentId,
                                   List<EmailReplyRequest.PendingAttachment> attachments) {

        // 1. Pobierz oryginalną wiadomość
        EmailMessage original = emailMessageRepository.findById(originalMessageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Wiadomość email nie istnieje: " + originalMessageId));

        // 2. Pobierz konfigurację SMTP tenanta
        Tenant tenant = tenantService.findTenantEntity(tenantId)
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
        List<EmailReplyRequest.PendingAttachment> safeAttachments =
                attachments != null ? attachments : List.of();

        log.info("[EmailSend] Wysyłam odpowiedź: originalId={}, to={}, tenant={}, agent={}, attachments={}",
                originalMessageId, original.getFromAddress(), tenantId, agentId, safeAttachments.size());

        try {
            sendSmtp(config, password, config.getUsername(), original.getFromAddress(),
                    replySubject, bodyHtml, newMessageId,
                    original.getMessageIdHeader(), // In-Reply-To
                    buildReferences(original),     // References
                    safeAttachments);
        } catch (MessagingException e) {
            log.error("[EmailSend] Błąd SMTP: tenant={}, to={}, error={}",
                    tenantId, original.getFromAddress(), e.getMessage(), e);
            throw new EmailSendException("Wysyłka SMTP nie powiodła się: " + e.getMessage(), e);
        }

        // 6. Zbuduj JSON metadanych załączników dla OUTBOUND
        String attachmentsJson = buildAttachmentsJson(safeAttachments);

        // 7. Zapisz jako OUTBOUND w DB
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
                .attachments(attachmentsJson)
                .sentAt(Instant.now())
                .deliveryStatus(EmailMessage.DeliveryStatus.SENT.name())
                .build();

        EmailMessage saved = emailMessageRepository.save(reply);

        // 8. Publikuj event
        emailEventPublisher.publishSent(saved, agentId);

        log.info("[EmailSend] Odpowiedź wysłana i zapisana: replyId={}, originalId={}, agent={}",
                saved.getId(), originalMessageId, agentId);

        return saved;
    }

    // =========================================================================
    // Wysyłka z szablonem
    // =========================================================================

    @Override
    @Transactional
    public EmailMessage sendReplyWithTemplate(UUID tenantId, UUID originalMessageId,
                                              UUID templateId, Map<String, Object> variables,
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

        // Wyślij przez istniejącą metodę z wyrenderowanymi wartościami (bez załączników)
        return sendReply(tenantId, originalMessageId, rendered.bodyHtml(), rendered.subject(), agentId, List.of());
    }

    // =========================================================================
    // Wysyłka nowej wiadomości (ad hoc)
    // =========================================================================

    @Override
    @Transactional
    public EmailMessage sendNew(UUID tenantId, String toAddress, String subject,
                                String bodyHtml, UUID agentId,
                                List<EmailReplyRequest.PendingAttachment> attachments) {

        // 1. Pobierz konfigurację SMTP tenanta
        Tenant tenant = tenantService.findTenantEntity(tenantId)
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

        List<EmailReplyRequest.PendingAttachment> safeAttachments =
                attachments != null ? attachments : List.of();

        try {
            sendSmtp(config, password, config.getUsername(), toAddress,
                    subject, bodyHtml, newMessageId, null, null, safeAttachments);
        } catch (MessagingException e) {
            log.error("[EmailSend] Błąd SMTP: tenant={}, to={}, error={}",
                    tenantId, toAddress, e.getMessage(), e);
            throw new EmailSendException("Wysyłka SMTP nie powiodła się: " + e.getMessage(), e);
        }

        // 4. Zapisz jako OUTBOUND w DB (contactId = null dla ad hoc)
        String attachmentsJson = buildAttachmentsJson(safeAttachments);
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
                .attachments(attachmentsJson)
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
     * <p>Jeśli lista {@code attachments} jest niepusta, wiadomość jest strukturyzowana
     * jako {@code multipart/mixed}: alternatywa text/html jako pierwsza część,
     * następnie każdy załącznik pobierany z S3 jako kolejna część.
     * Jeśli brak załączników, stosowana jest prostsza struktura {@code multipart/alternative}.
     *
     * @param config      konfiguracja SMTP
     * @param password    hasło (odszyfrowane, NIE logujemy)
     * @param from        adres nadawcy
     * @param to          adres odbiorcy
     * @param subject     temat
     * @param bodyHtml    treść HTML
     * @param messageId   nagłówek Message-ID (nowy UUID)
     * @param inReplyTo   nagłówek In-Reply-To (z oryginalnej wiadomości)
     * @param references  nagłówek References (łańcuch wątku)
     * @param attachments lista załączników do dołączenia (pobierane z S3)
     */
    void sendSmtp(EmailAccountConfig config, String password,
                  String from, String to, String subject, String bodyHtml,
                  String messageId, String inReplyTo, String references,
                  List<EmailReplyRequest.PendingAttachment> attachments)
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

        if (attachments == null || attachments.isEmpty()) {
            // Brak załączników: multipart/alternative (text + html)
            MimeMultipart alternative = buildAlternativePart(bodyHtml);
            message.setContent(alternative);
        } else {
            // Z załącznikami: multipart/mixed { multipart/alternative, attachment... }
            MimeMultipart mixed = new MimeMultipart("mixed");

            // Part 1: treść (alternative)
            MimeBodyPart alternativePart = new MimeBodyPart();
            alternativePart.setContent(buildAlternativePart(bodyHtml));
            mixed.addBodyPart(alternativePart);

            // Part 2..N: załączniki
            for (EmailReplyRequest.PendingAttachment attachment : attachments) {
                MimeBodyPart attachmentPart = buildAttachmentPart(attachment);
                if (attachmentPart != null) {
                    mixed.addBodyPart(attachmentPart);
                }
            }

            message.setContent(mixed);
        }

        Transport.send(message);
    }

    /**
     * Buduje część {@code multipart/alternative} z plain text i HTML.
     */
    private MimeMultipart buildAlternativePart(String bodyHtml) throws MessagingException {
        MimeMultipart alternative = new MimeMultipart("alternative");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(stripHtml(bodyHtml), "UTF-8");
        alternative.addBodyPart(textPart);

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(bodyHtml, "text/html; charset=UTF-8");
        alternative.addBodyPart(htmlPart);

        return alternative;
    }

    /**
     * Pobiera bajty załącznika z S3 i buduje {@link MimeBodyPart}.
     * Błędy logowane per-załącznik – nie przerywają wysyłki pozostałych.
     */
    private MimeBodyPart buildAttachmentPart(EmailReplyRequest.PendingAttachment attachment) {
        try {
            byte[] data = attachmentStorageService.download(attachment.s3Key());
            String contentType = attachment.contentType() != null
                    ? attachment.contentType()
                    : "application/octet-stream";

            MimeBodyPart part = new MimeBodyPart();
            part.setDataHandler(new DataHandler(new ByteArrayDataSource(data, contentType)));
            part.setFileName(MimeUtility.encodeText(attachment.filename(), "UTF-8", "B"));
            part.setDisposition(MimePart.ATTACHMENT);

            log.debug("[EmailSend] Dołączono załącznik: filename={}, size={}B",
                    attachment.filename(), data.length);
            return part;
        } catch (Exception e) {
            log.error("[EmailSend] Błąd dołączania załącznika s3Key={}: {}",
                    attachment.s3Key(), e.getMessage(), e);
            return null;
        }
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

    /**
     * Serializuje listę załączników do JSON dla kolumny JSONB {@code attachments}.
     * Format: [{"filename": "...", "content_type": "...", "size_bytes": N, "s3_key": "..."}]
     */
    private String buildAttachmentsJson(List<EmailReplyRequest.PendingAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "[]";
        }
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (EmailReplyRequest.PendingAttachment a : attachments) {
                list.add(Map.of(
                        "filename", a.filename() != null ? a.filename() : "",
                        "content_type", a.contentType() != null ? a.contentType() : "application/octet-stream",
                        "size_bytes", a.sizeBytes(),
                        "s3_key", a.s3Key() != null ? a.s3Key() : ""
                ));
            }
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("[EmailSend] Nie można serializować metadanych załączników: {}", e.getMessage());
            return "[]";
        }
    }

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
}
