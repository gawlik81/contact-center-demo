package com.contactcenter.domain.email;

import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email.enabled", havingValue = "true", matchIfMissing = true)
class EmailPollingServiceImpl implements EmailPollingService {

    private final TenantService tenantService;
    private final EmailMessageRepository emailMessageRepository;
    private final EmailRoutingService emailRoutingService;
    private final EmailEventPublisher emailEventPublisher;
    private final EmailEncryptionService encryptionService;
    private final EmailAttachmentStorageService attachmentStorageService;
    private final ObjectMapper objectMapper;

    @Value("${email.poll-delay-ms:60000}")
    private long pollDelayMs;

    // =========================================================================
    // Polling (Scheduled)
    // =========================================================================

    /**
     * Główna pętla pollingu IMAP.
     *
     * <p>Wywołuje {@link #pollTenantInbox(Tenant)} dla każdego aktywnego tenanta
     * z włączoną obsługą email. Błąd per-tenant nie przerywa pętli.
     *
     * <p>Używa {@code fixedDelay} (nie {@code fixedRate}) – kolejny run startuje
     * dopiero po zakończeniu poprzedniego, co chroni przed nakładającymi się pollami
     * przy wolnych połączeniach IMAP.
     */
    @Scheduled(fixedDelayString = "${email.poll-delay-ms:60000}")
    public void pollAllTenants() {
        List<Tenant> activeTenants = tenantService.getActiveTenants().stream()
                .filter(t -> isEmailEnabled(t))
                .toList();

        if (activeTenants.isEmpty()) {
            log.debug("[EmailPolling] Brak tenantów z email_enabled=true – pomijam polling");
            return;
        }

        log.info("[EmailPolling] Rozpoczynam polling IMAP dla {} tenantów", activeTenants.size());

        for (Tenant tenant : activeTenants) {
            // Snapshot kontekstu = pusty (brak HTTP request) – tworzymy ręcznie per tenant
            TenantContext.Snapshot snapshot = new TenantContext.Snapshot(
                    tenant.getId(),
                    null,    // userId: polling = system action, brak usera
                    tenant.getName(),
                    "SYSTEM"
            );

            try {
                TenantContext.restore(snapshot);
                pollTenantInbox(tenant);
            } catch (Exception e) {
                // Błąd jednego tenanta NIE przerywa pętli – logujemy i przechodzimy dalej
                log.error("[EmailPolling] Błąd pollingu dla tenanta {}: {}",
                        tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }

        log.info("[EmailPolling] Zakończono polling IMAP dla {} tenantów", activeTenants.size());
    }

    // =========================================================================
    // Polling per tenant
    // =========================================================================

    /**
     * Polling skrzynki IMAP dla pojedynczego tenanta.
     *
     * <p>Otwiera połączenie IMAP, pobiera wiadomości UNSEEN, zapisuje w DB,
     * routuje do kolejki, oznacza jako przeczytane.
     *
     * @param tenant encja tenanta – musi mieć email_enabled=true w config
     */
    void pollTenantInbox(Tenant tenant) {
        EmailAccountConfig config = EmailAccountConfig.fromTenantConfig(tenant.getConfig());
        if (config == null) {
            log.debug("[EmailPolling] Tenant {} nie ma skonfigurowanego konta email – pomijam",
                    tenant.getId());
            return;
        }

        String password;
        try {
            password = encryptionService.decrypt(config.getPassword());
        } catch (EmailEncryptionService.EmailEncryptionException e) {
            log.error("[EmailPolling] Nie można odszyfrować hasła IMAP dla tenanta {}: {}",
                    tenant.getId(), e.getMessage());
            return;
        }

        // Łączymy z IMAP – hasło NIE jest logowane
        log.info("[EmailPolling] Łączę z IMAP: host={}, port={}, ssl={}, user={}, tenant={}",
                config.getImapHost(), config.getImapPort(), config.isImapSsl(),
                config.getUsername(), tenant.getId());

        Store store = null;
        Folder inbox = null;

        try {
            store = connectImap(config, password);
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            // Pobierz wiadomości UNSEEN (flaga \Seen nie ustawiona)
            Message[] messages = inbox.search(
                    new jakarta.mail.search.FlagTerm(
                            new Flags(Flags.Flag.SEEN), false));

            log.info("[EmailPolling] Tenant {}: {} nowych wiadomości w INBOX",
                    tenant.getId(), messages.length);

            int saved = 0;
            for (Message message : messages) {
                try {
                    boolean wasSaved = processMessage(message, tenant);
                    if (wasSaved) {
                        // Oznacz jako przeczytane (SEEN) – dopiero po zapisie w DB
                        message.setFlag(Flags.Flag.SEEN, true);
                        saved++;
                    }
                } catch (Exception e) {
                    log.error("[EmailPolling] Błąd przetwarzania wiadomości dla tenanta {}: {}",
                            tenant.getId(), e.getMessage(), e);
                    // Nie oznaczamy jako SEEN – zostanie pobrana w następnym pollingu
                }
            }

            log.info("[EmailPolling] Tenant {}: zapisano {}/{} wiadomości",
                    tenant.getId(), saved, messages.length);

        } catch (MessagingException e) {
            log.error("[EmailPolling] Błąd IMAP dla tenanta {}: {}",
                    tenant.getId(), e.getMessage(), e);
            throw new RuntimeException("Błąd IMAP dla tenanta " + tenant.getId(), e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    /**
     * Przetwarza pojedynczą wiadomość IMAP: parsuje, deduplikuje, zapisuje w DB,
     * a następnie extrahuje i uploaduje załączniki do S3.
     *
     * @param message wiadomość Jakarta Mail
     * @param tenant  tenant docelowy
     * @return true jeśli wiadomość została zapisana (nie była duplikatem), false dla duplikatów
     */
    private boolean processMessage(Message message, Tenant tenant) throws MessagingException, IOException {
        String messageIdHeader = getHeader(message, "Message-ID");

        // Deduplicacja: sprawdź czy wiadomość o tym Message-ID już istnieje
        if (messageIdHeader != null) {
            boolean exists = emailMessageRepository
                    .findByMessageIdHeader(messageIdHeader, tenant.getId())
                    .isPresent();
            if (exists) {
                log.debug("[EmailPolling] Duplikat pominięty: messageIdHeader={}, tenant={}",
                        messageIdHeader, tenant.getId());
                return false;
            }
        }

        EmailMessage emailMessage = parseMessage(message, tenant.getId(), messageIdHeader);
        EmailMessage saved = emailMessageRepository.save(emailMessage);

        // Wyodrębnij i uploaduj załączniki do S3 po zapisaniu EmailMessage
        saved = extractAndStoreAttachments(message, saved, tenant.getId());

        // Publikuj event received
        emailEventPublisher.publishReceived(saved);

        // Routuj do kolejki
        emailRoutingService.route(saved, tenant.getConfig());

        return true;
    }

    /**
     * Parsuje wiadomość Jakarta Mail do encji {@link EmailMessage}.
     *
     * @param message         wiadomość IMAP
     * @param tenantId        UUID tenanta
     * @param messageIdHeader wartość nagłówka Message-ID (może być null)
     * @return encja gotowa do zapisu
     */
    private EmailMessage parseMessage(Message message, UUID tenantId, String messageIdHeader)
            throws MessagingException, IOException {

        String from = message.getFrom() != null && message.getFrom().length > 0
                ? message.getFrom()[0].toString()
                : "";

        String to = addressesToString(message.getRecipients(Message.RecipientType.TO));
        String cc = addressesToString(message.getRecipients(Message.RecipientType.CC));
        String bcc = addressesToString(message.getRecipients(Message.RecipientType.BCC));

        String inReplyTo = getHeader(message, "In-Reply-To");
        String subject = message.getSubject();

        String bodyText = null;
        String bodyHtml = null;

        // Parsowanie treści (multipart lub plain)
        Object content = message.getContent();
        if (content instanceof String text) {
            String contentType = message.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                bodyHtml = text;
            } else {
                bodyText = text;
            }
        } else if (content instanceof MimeMultipart multipart) {
            String[] parts = extractTextParts(multipart);
            bodyText = parts[0];
            bodyHtml = parts[1];
        }

        Instant receivedDate = message.getReceivedDate() != null
                ? message.getReceivedDate().toInstant()
                : Instant.now();

        return EmailMessage.builder()
                .tenantId(tenantId)
                .direction(EmailMessage.Direction.INBOUND.name())
                .fromAddress(from)
                .toAddress(to != null ? to : "")
                .ccAddress(cc)
                .bccAddress(bcc)
                .subject(subject)
                .bodyText(bodyText)
                .bodyHtml(bodyHtml)
                .messageIdHeader(messageIdHeader)
                .inReplyTo(inReplyTo)
                .receivedAt(receivedDate)
                .deliveryStatus(null) // INBOUND – brak delivery status
                .build();
    }

    // =========================================================================
    // Wyodrębnianie załączników
    // =========================================================================

    /**
     * Wyodrębnia załączniki z wiadomości MIME, uploaduje do S3 i aktualizuje
     * pole {@code attachments} JSONB na zapisanej encji {@link EmailMessage}.
     *
     * <p>Błędy per-załącznik są logowane ale nie przerywają przetwarzania pozostałych.
     * Tylko części z {@code Content-Disposition: attachment} są traktowane jako załączniki –
     * części {@code inline} (np. osadzone obrazy) są pomijane.
     *
     * @param message wiadomość IMAP
     * @param saved   zapisana encja (potrzebny {@code id} jako messageId w kluczu S3)
     * @param tenantId UUID tenanta
     * @return zaktualizowana encja (z wypełnionym polem {@code attachments})
     */
    private EmailMessage extractAndStoreAttachments(Message message, EmailMessage saved, UUID tenantId) {
        List<Map<String, Object>> attachmentMeta = new ArrayList<>();

        try {
            Object content = message.getContent();
            if (content instanceof MimeMultipart multipart) {
                collectAttachments(multipart, tenantId, saved.getId(), attachmentMeta);
            }
            // Wiadomość niebędąca multipart nie ma załączników
        } catch (Exception e) {
            log.error("[EmailPolling] Błąd wyodrębniania załączników dla messageId={}: {}",
                    saved.getId(), e.getMessage(), e);
        }

        if (attachmentMeta.isEmpty()) {
            return saved;
        }

        try {
            String json = objectMapper.writeValueAsString(attachmentMeta);
            saved.setAttachments(json);
            saved = emailMessageRepository.save(saved);
            log.info("[EmailPolling] Zapisano {} załącznik(ów) dla messageId={}",
                    attachmentMeta.size(), saved.getId());
        } catch (Exception e) {
            log.error("[EmailPolling] Błąd aktualizacji metadanych załączników dla messageId={}: {}",
                    saved.getId(), e.getMessage(), e);
        }

        return saved;
    }

    /**
     * Rekurencyjnie przeszukuje strukturę {@link MimeMultipart} w poszukiwaniu
     * części z {@code Content-Disposition: attachment} i uploaduje je do S3.
     *
     * @param multipart      struktura do przeszukania
     * @param tenantId       UUID tenanta
     * @param messageId      UUID wiadomości (do klucza S3)
     * @param attachmentMeta lista do wypełnienia metadanymi
     */
    private void collectAttachments(MimeMultipart multipart, UUID tenantId, UUID messageId,
                                    List<Map<String, Object>> attachmentMeta)
            throws MessagingException, IOException {

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);

            // Rekurencja: zagnieżdżone multipart/mixed lub multipart/related
            if (part.getContent() instanceof MimeMultipart nested) {
                collectAttachments(nested, tenantId, messageId, attachmentMeta);
                continue;
            }

            // Sprawdź Content-Disposition: tylko "attachment", pomiń "inline" i null
            String disposition = part.getDisposition();
            if (disposition == null || !disposition.equalsIgnoreCase(MimePart.ATTACHMENT)) {
                continue;
            }

            // Pobierz nazwę pliku (może być zakodowana RFC 2047)
            String filename = decodeFilename(part.getFileName());
            if (filename == null || filename.isBlank()) {
                filename = "attachment-" + (attachmentMeta.size() + 1);
            }

            // Pobierz MIME type (bez parametrów)
            String contentType = extractBaseContentType(part.getContentType());
            long sizeBytes;
            String s3Key;

            try (InputStream is = part.getInputStream()) {
                byte[] data = is.readAllBytes();
                sizeBytes = data.length;
                s3Key = attachmentStorageService.store(tenantId, messageId, filename, contentType, data);
                log.debug("[EmailPolling] Załącznik uploadowany: filename={}, size={}B, s3Key={}",
                        filename, sizeBytes, s3Key);
            } catch (Exception e) {
                log.error("[EmailPolling] Błąd uploadu załącznika '{}' dla messageId={}: {}",
                        filename, messageId, e.getMessage(), e);
                continue; // pomiń ten załącznik, kontynuuj z pozostałymi
            }

            attachmentMeta.add(Map.of(
                    "filename", filename,
                    "content_type", contentType,
                    "size_bytes", sizeBytes,
                    "s3_key", s3Key
            ));
        }
    }

    /**
     * Dekoduje nazwę pliku z nagłówka MIME (RFC 2047 / RFC 2231).
     */
    private String decodeFilename(String rawFilename) {
        if (rawFilename == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(rawFilename);
        } catch (Exception e) {
            log.debug("[EmailPolling] Nie można zdekodować nazwy pliku '{}': {}", rawFilename, e.getMessage());
            return rawFilename;
        }
    }

    /**
     * Zwraca base MIME type bez parametrów (np. "application/pdf" z "application/pdf; name=...").
     */
    private String extractBaseContentType(String contentType) {
        if (contentType == null) {
            return "application/octet-stream";
        }
        int semicolon = contentType.indexOf(';');
        return semicolon > 0 ? contentType.substring(0, semicolon).trim().toLowerCase() : contentType.trim().toLowerCase();
    }

    // =========================================================================
    // Połączenie IMAP
    // =========================================================================

    @Override
    public Store connectImap(EmailAccountConfig config, String password) throws MessagingException {
        Properties props = new Properties();

        if (config.isImapSsl()) {
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", config.getImapHost());
            props.put("mail.imaps.port", String.valueOf(config.getImapPort()));
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.trust", "*");
            props.put("mail.imaps.timeout", "10000");
            props.put("mail.imaps.connectiontimeout", "10000");
        } else {
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", config.getImapHost());
            props.put("mail.imap.port", String.valueOf(config.getImapPort()));
            props.put("mail.imap.ssl.trust", "*");
            props.put("mail.imap.timeout", "10000");
            props.put("mail.imap.connectiontimeout", "10000");
        }

        Session session = Session.getInstance(props);
        Store store = session.getStore();
        store.connect(config.getImapHost(), config.getUsername(), password);
        return store;
    }

    // =========================================================================
    // Metody pomocnicze parsowania
    // =========================================================================

    private String getHeader(Message message, String headerName) throws MessagingException {
        String[] headers = message.getHeader(headerName);
        if (headers != null && headers.length > 0) {
            return headers[0].trim();
        }
        return null;
    }

    private String addressesToString(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addresses.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(addresses[i].toString());
        }
        return sb.toString();
    }

    /**
     * Rekurencyjnie wyodrębnia treść tekstową i HTML z multipart wiadomości.
     * Pomija części z Content-Disposition: attachment (te obsługuje {@link #collectAttachments}).
     *
     * @param multipart struktura multipart
     * @return tablica 2-elementowa: [0]=text/plain, [1]=text/html
     */
    private String[] extractTextParts(MimeMultipart multipart) throws MessagingException, IOException {
        String bodyText = null;
        String bodyHtml = null;

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);

            // Pomiń załączniki – zostaną obsłużone osobno
            String disposition = part.getDisposition();
            if (MimePart.ATTACHMENT.equalsIgnoreCase(disposition)) {
                continue;
            }

            String contentType = part.getContentType().toLowerCase();

            if (contentType.startsWith("text/plain")) {
                bodyText = part.getContent().toString();
            } else if (contentType.startsWith("text/html")) {
                bodyHtml = part.getContent().toString();
            } else if (part.getContent() instanceof MimeMultipart nested) {
                String[] nestedParts = extractTextParts(nested);
                if (bodyText == null) bodyText = nestedParts[0];
                if (bodyHtml == null) bodyHtml = nestedParts[1];
            }
        }
        return new String[]{bodyText, bodyHtml};
    }

    private boolean isEmailEnabled(Tenant tenant) {
        if (tenant.getConfig() == null) {
            return false;
        }
        Object enabled = tenant.getConfig().get("email_enabled");
        if (enabled == null) {
            return false;
        }
        if (enabled instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(enabled));
    }

    private void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                // expunge=false – nie usuwamy wiadomości oznaczonych \Deleted
                folder.close(false);
            }
        } catch (Exception e) {
            log.warn("[EmailPolling] Błąd zamykania folderu IMAP: {}", e.getMessage());
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            log.warn("[EmailPolling] Błąd zamykania połączenia IMAP Store: {}", e.getMessage());
        }
    }
}
