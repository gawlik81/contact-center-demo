package com.contactcenter.api.email;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.email.dto.EmailConfigRequest;
import com.contactcenter.api.email.dto.EmailConfigResponse;
import com.contactcenter.api.email.dto.EmailMessageResponse;
import com.contactcenter.api.email.dto.EmailReplyRequest;
import com.contactcenter.api.email.dto.OutboundEmailRequest;
import com.contactcenter.domain.email.*;
import com.contactcenter.domain.email.EmailMessage;
import com.contactcenter.domain.email.EmailMessageRepository;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.mail.Store;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST API dla modułu Email.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET  /api/email/messages          – lista wiadomości (paginacja)</li>
 *   <li>GET  /api/email/messages/{id}     – szczegóły wiadomości</li>
 *   <li>GET  /api/email/threads/{messageIdHeader} – wątek emaila</li>
 *   <li>POST /api/email/messages/{id}/reply – wysyłka odpowiedzi</li>
 *   <li>GET  /api/email/config            – odczyt konfiguracji IMAP/SMTP (bez hasła)</li>
 *   <li>PUT  /api/email/config            – zapis konfiguracji IMAP/SMTP (SUPERVISOR/ADMIN)</li>
 *   <li>POST /api/email/config/test       – test połączenia IMAP</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/email")
@RequiredArgsConstructor
@Tag(name = "Email", description = "Zarządzanie wiadomościami email i konfiguracją IMAP/SMTP")
public class EmailController {

    private final EmailMessageRepository emailMessageRepository;
    private final EmailSendService emailSendService;
    private final TenantService tenantService;
    private final EmailEncryptionService encryptionService;
    private final EmailPollingService emailPollingService;

    // =========================================================================
    // Wiadomości
    // =========================================================================

    /**
     * Lista wiadomości email dla bieżącego tenanta (paginacja).
     *
     * <p>Dostępne dla wszystkich uwierzytelnionych użytkowników tenanta.
     */
    @GetMapping("/messages")
    @Operation(summary = "Lista wiadomości email", description = "Paginowana lista wiadomości email dla bieżącego tenanta")
    public ResponseEntity<PagedResponse<EmailMessageResponse>> listMessages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<EmailMessage> messagesPage = emailMessageRepository.findAll(pageable);

        Page<EmailMessageResponse> mappedPage = messagesPage.map(EmailMessageResponse::from);
        return ResponseEntity.ok(PagedResponse.from(mappedPage));
    }

    /**
     * Szczegóły wiadomości email.
     */
    @GetMapping("/messages/{id}")
    @Operation(summary = "Szczegóły wiadomości email")
    public ResponseEntity<EmailMessageResponse> getMessage(@PathVariable UUID id) {
        EmailMessage message = emailMessageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wiadomość email nie istnieje: " + id));

        return ResponseEntity.ok(EmailMessageResponse.from(message));
    }

    /**
     * Pobiera pierwszą wiadomość INBOUND powiązaną z kontaktem.
     *
     * <p>Używane przez AgentDesktop do załadowania widoku email po przydzieleniu kontaktu.
     *
     * @param contactId UUID kontaktu (z zakładki agenta)
     */
    @GetMapping("/contacts/{contactId}/message")
    @Operation(summary = "Wiadomość email dla kontaktu",
            description = "Zwraca pierwszą wiadomość INBOUND powiązaną z podanym contactId")
    public ResponseEntity<EmailMessageResponse> getMessageByContactId(@PathVariable UUID contactId) {
        UUID tenantId = TenantContext.getTenantId();

        EmailMessage message = emailMessageRepository.findFirstInboundByContactId(contactId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Brak wiadomości email dla kontaktu: " + contactId));

        return ResponseEntity.ok(EmailMessageResponse.from(message));
    }

    /**
     * Wątek emaila – oryginalna wiadomość + wszystkie odpowiedzi.
     *
     * @param messageIdHeader nagłówek RFC 2822 Message-ID wiadomości oryginalnej (URL-encoded)
     */
    @GetMapping("/threads/{messageIdHeader}")
    @Operation(summary = "Wątek emaila", description = "Pobiera wątek emaila – oryginał + odpowiedzi")
    public ResponseEntity<PagedResponse<EmailMessageResponse>> getThread(
            @PathVariable String messageIdHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        UUID tenantId = TenantContext.getTenantId();
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));

        Page<EmailMessage> threadPage = emailMessageRepository
                .findByThreadRootMessageId(messageIdHeader, tenantId, pageable);

        Page<EmailMessageResponse> mappedThread = threadPage.map(EmailMessageResponse::from);
        return ResponseEntity.ok(PagedResponse.from(mappedThread));
    }

    /**
     * Wysyłka odpowiedzi na wiadomość email.
     *
     * <p>Dostępne dla agentów i supervisorów.
     */
    @PostMapping("/messages/{id}/reply")
    @Operation(summary = "Wyślij odpowiedź email")
    public ResponseEntity<EmailMessageResponse> replyToMessage(
            @PathVariable UUID id,
            @Valid @RequestBody EmailReplyRequest request) {

        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        EmailMessage reply = emailSendService.sendReply(tenantId, id,
                request.bodyHtml(), request.subject(), agentId);

        log.info("[EmailController] Odpowiedź wysłana: replyId={}, originalId={}, agent={}",
                reply.getId(), id, agentId);

        return ResponseEntity.ok(EmailMessageResponse.from(reply));
    }

    /**
     * Wysyłka nowej wiadomości email ad hoc – bez powiązania z istniejącym wątkiem.
     *
     * <p>Tworzy nowy wątek emailowy (brak nagłówków In-Reply-To / References).
     * Dostępne dla agentów, supervisorów i administratorów.
     */
    @PostMapping("/messages/outbound")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Wyślij nowy email ad hoc",
               description = "Wysyła nową wiadomość email do klienta bez powiązania z istniejącym wątkiem")
    public ResponseEntity<EmailMessageResponse> sendOutboundEmail(
            @Valid @RequestBody OutboundEmailRequest request) {

        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        EmailMessage sent = emailSendService.sendNew(tenantId, request.toAddress(),
                request.subject(), request.bodyHtml(), agentId);

        log.info("[EmailController] Email ad hoc wysłany: id={}, to={}, agent={}",
                sent.getId(), request.toAddress(), agentId);

        return ResponseEntity.ok(EmailMessageResponse.from(sent));
    }

    // =========================================================================
    // Konfiguracja IMAP/SMTP
    // =========================================================================

    /**
     * Odczyt konfiguracji email tenanta – BEZ hasła.
     *
     * <p>Dostępne dla SUPERVISOR i ADMIN.
     */
    @GetMapping("/config")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Odczyt konfiguracji email", description = "Zwraca konfigurację IMAP/SMTP bez hasła")
    public ResponseEntity<EmailConfigResponse> getConfig() {
        UUID tenantId = TenantContext.getTenantId();

        Tenant tenant = tenantService.findTenantEntity(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant nie istnieje: " + tenantId));

        EmailAccountConfig config = EmailAccountConfig.fromTenantConfig(tenant.getConfig());
        if (config == null) {
            return ResponseEntity.notFound().build();
        }

        boolean emailEnabled = Boolean.TRUE.equals(tenant.getConfig().get("email_enabled"));
        return ResponseEntity.ok(EmailConfigResponse.from(config, emailEnabled, tenant.getConfig()));
    }

    /**
     * Zapis konfiguracji email tenanta.
     *
     * <p>Hasło jest szyfrowane AES-256-GCM przed zapisem do JSONB.
     * Gdy {@code password} jest null – zachowuje istniejące hasło.
     *
     * <p>Dostępne tylko dla SUPERVISOR i ADMIN.
     */
    @PutMapping("/config")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Zapis konfiguracji email", description = "Zapisuje konfigurację IMAP/SMTP; hasło jest szyfrowane AES-256")
    public ResponseEntity<EmailConfigResponse> updateConfig(
            @Valid @RequestBody EmailConfigRequest request) {

        UUID tenantId = TenantContext.getTenantId();

        Tenant tenant = tenantService.findTenantEntity(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant nie istnieje: " + tenantId));

        Map<String, Object> config = tenant.getConfig();

        // Zaszyfruj hasło jeśli podane; zachowaj istniejące gdy null
        String encryptedPassword;
        if (request.password() != null && !request.password().isBlank()) {
            encryptedPassword = encryptionService.encrypt(request.password());
        } else {
            encryptedPassword = (String) config.get("email_password");
        }

        // Zbuduj konfigurację i scal z tenant.config
        EmailAccountConfig emailConfig = EmailAccountConfig.builder()
                .imapHost(request.imapHost())
                .imapPort(request.imapPort())
                .imapSsl(request.imapSsl())
                .smtpHost(request.smtpHost())
                .smtpPort(request.smtpPort())
                .smtpSsl(request.smtpSsl())
                .username(request.username())
                .password(encryptedPassword)
                .pollIntervalSeconds(request.pollIntervalSeconds())
                .build();

        config.putAll(emailConfig.toTenantConfig());
        config.put("email_enabled", request.emailEnabled());

        // Ustaw lub usuń domyślną kolejkę email
        if (request.defaultQueueId() != null) {
            config.put("email_default_queue_id", request.defaultQueueId().toString());
        } else {
            config.remove("email_default_queue_id");
        }

        tenantService.updateTenantConfig(tenantId, config);

        log.info("[EmailController] Konfiguracja email zapisana: tenant={}, user={}, enabled={}",
                tenantId, request.username(), request.emailEnabled());

        return ResponseEntity.ok(EmailConfigResponse.from(emailConfig, request.emailEnabled(), config));
    }

    /**
     * Test połączenia IMAP – weryfikuje poprawność konfiguracji.
     *
     * <p>Próbuje nawiązać połączenie IMAP z podanymi danymi i zwraca wynik.
     * Hasło podane w żądaniu używane tylko do testu – nie jest zapisywane.
     *
     * <p>Dostępne dla SUPERVISOR i ADMIN.
     */
    @PostMapping("/config/test")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Test połączenia IMAP", description = "Testuje połączenie IMAP bez zapisywania konfiguracji")
    public ResponseEntity<ImapTestResponse> testImapConnection(
            @Valid @RequestBody EmailConfigRequest request) {

        UUID tenantId = TenantContext.getTenantId();

        if (request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest().body(
                    new ImapTestResponse(false, "Hasło jest wymagane do testu połączenia"));
        }

        EmailAccountConfig config = EmailAccountConfig.builder()
                .imapHost(request.imapHost())
                .imapPort(request.imapPort())
                .imapSsl(request.imapSsl())
                .smtpHost(request.smtpHost())
                .smtpPort(request.smtpPort())
                .smtpSsl(request.smtpSsl())
                .username(request.username())
                .password(request.password()) // plaintext – tylko do testu
                .build();

        log.info("[EmailController] Test IMAP: host={}, port={}, user={}, tenant={}",
                config.getImapHost(), config.getImapPort(), config.getUsername(), tenantId);

        Store store = null;
        try {
            store = emailPollingService.connectImap(config, request.password());
            log.info("[EmailController] Test IMAP OK: tenant={}", tenantId);
            return ResponseEntity.ok(new ImapTestResponse(true, "Połączenie IMAP udane"));
        } catch (Exception e) {
            log.warn("[EmailController] Test IMAP nie powiódł się: tenant={}, error={}",
                    tenantId, e.getMessage());
            return ResponseEntity.ok(new ImapTestResponse(false, "Błąd IMAP: " + e.getMessage()));
        } finally {
            if (store != null) {
                try { store.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** DTO odpowiedzi testu połączenia IMAP. */
    public record ImapTestResponse(boolean success, String message) {}
}
