package com.contactcenter.domain.email;

import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.EmailMessageRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.security.TenantContext;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Serwis pollingu skrzynek IMAP dla wszystkich aktywnych tenantów.
 *
 * <p>Uruchamiany przez {@code @Scheduled} co {@code email.poll-delay-ms} ms (domyślnie 60s).
 * Dla każdego tenanta z {@code email_enabled=true} w config otwiera połączenie IMAP,
 * pobiera wiadomości UNSEEN i zapisuje je w DB.
 *
 * <p>Dla każdego tenanta kontekst jest ustawiany ręcznie przez
 * {@link TenantContext#restore(TenantContext.Snapshot)} i czyszczony w bloku {@code finally}.
 * Błąd jednego tenanta NIE przerywa pętli pozostałych.
 *
 * <p>Aktywny tylko gdy {@code email.enabled=true} (domyślnie true).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email.enabled", havingValue = "true", matchIfMissing = true)
public class EmailPollingService {

    private final TenantRepository tenantRepository;
    private final EmailMessageRepository emailMessageRepository;
    private final EmailRoutingService emailRoutingService;
    private final EmailEventPublisher emailEventPublisher;
    private final EmailEncryptionService encryptionService;

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
        List<Tenant> activeTenants = tenantRepository.findAll().stream()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE)
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
     * Przetwarza pojedynczą wiadomość IMAP: parsuje, deduplikuje, zapisuje w DB.
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
            String[] parts = extractMultipart(multipart);
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
    // Połączenie IMAP
    // =========================================================================

    /**
     * Tworzy i zwraca połączoną sesję IMAP Store.
     *
     * @param config   konfiguracja IMAP
     * @param password hasło odszyfrowane (NIE logujemy)
     * @return połączony Store
     * @throws MessagingException gdy połączenie nie powiedzie się
     */
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
     *
     * @param multipart struktura multipart
     * @return tablica 2-elementowa: [0]=text/plain, [1]=text/html
     */
    private String[] extractMultipart(MimeMultipart multipart) throws MessagingException, IOException {
        String bodyText = null;
        String bodyHtml = null;

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String contentType = part.getContentType().toLowerCase();

            if (contentType.startsWith("text/plain")) {
                bodyText = part.getContent().toString();
            } else if (contentType.startsWith("text/html")) {
                bodyHtml = part.getContent().toString();
            } else if (part.getContent() instanceof MimeMultipart nested) {
                String[] nested_parts = extractMultipart(nested);
                if (bodyText == null) bodyText = nested_parts[0];
                if (bodyHtml == null) bodyHtml = nested_parts[1];
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
