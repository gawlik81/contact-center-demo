package com.contactcenter.domain.email;

import com.contactcenter.api.email.dto.EmailReplyRequest;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link EmailSendService#sendNew(UUID, String, String, String, UUID)} oraz
 * (BE-143) allow-listy {@code s3Key} załączników w {@code sendNew}/{@code sendReply}.
 *
 * <p>Strategia: {@code @Spy EmailSendService} + {@code doNothing().when(spy).sendSmtp(...)}
 * – unikamy realnego wywołania SMTP bez konieczności PowerMocka.
 * Weryfikujemy: zapis do repozytorium (ArgumentCaptor) i publikację eventu email.sent.
 *
 * <p><strong>BE-143 – decyzja testowa:</strong> pełna macierz AC (i)-(iv) jest tu pokryta na
 * poziomie {@code sendNew} (rzeczywisty {@link EmailSendServiceImpl}, mock
 * {@link EmailAttachmentStorageService} i {@code sendSmtp} — ten sam wzorzec co reszta klasy);
 * {@code sendReply} ma własny, mniejszy zestaw (obcy klucz odrzucony + własny klucz INBOUND
 * przechodzi), bo logika walidacji jest wspólna ({@code validateAttachmentKeys}) — dublowanie
 * wszystkich 4 przypadków na obu ścieżkach nie dodałoby pokrycia. Wiązanie
 * {@code EmailController} → {@code EmailSendService} (w tym propagacja wyjątku, niezłapana przez
 * kontroler) jest osobno w {@code EmailControllerTest} (pakiet {@code api.email}), a mapowanie
 * wyjątku na HTTP 403 w {@code GlobalExceptionHandlerTest} — zgodnie z konwencją tego projektu dla
 * testów kontrolerów (zob. {@code RetentionControllerTest}: bez pełnego {@code MockMvc} + Spring
 * Security dla pakietu {@code api.*}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailSendService – sendNew() i walidacja załączników (BE-143)")
class EmailSendServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID AGENT_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MSG_ID    = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ORIGINAL_MSG_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final String TO_ADDRESS = "klient@example.com";
    private static final String SUBJECT    = "Testowy temat";
    private static final String BODY_HTML  = "<p>Treść testowa</p>";

    @Mock
    private EmailMessageRepository emailMessageRepository;
    @Mock
    private EmailEventPublisher emailEventPublisher;
    @Mock
    private TenantService tenantService;
    @Mock
    private EmailEncryptionService encryptionService;
    @Mock
    private EmailTemplateService emailTemplateService;
    @Mock
    private TemplateVariableResolver templateVariableResolver;
    @Mock
    private EmailAttachmentStorageService attachmentStorageService;

    private EmailSendServiceImpl emailSendService;

    @BeforeEach
    void setUp() {
        emailSendService = new EmailSendServiceImpl(
                emailMessageRepository,
                emailEventPublisher,
                tenantService,
                encryptionService,
                emailTemplateService,
                templateVariableResolver,
                attachmentStorageService,
                new ObjectMapper()
        );
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("powinien wysłać wiadomość, zapisać ją jako OUTBOUND i opublikować event")
        void shouldSendSaveAndPublish() throws Exception {
            // given
            Tenant tenant = buildTenantWithSmtpConfig();
            when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(tenant));
            when(encryptionService.decrypt(anyString())).thenReturn("smtp-plaintext-password");

            EmailMessage saved = buildSavedMessage();
            when(emailMessageRepository.save(any(EmailMessage.class))).thenReturn(saved);

            // Spy: pomiń rzeczywiste wywołanie SMTP
            EmailSendServiceImpl spy = spy(emailSendService);
            doNothing().when(spy).sendSmtp(
                    any(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), isNull(), isNull(), anyList());

            // when
            EmailMessage result = spy.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, null);

            // then – wynik to zapisana encja
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(MSG_ID);

            // encja przekazana do save() ma poprawne pola
            ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
            verify(emailMessageRepository).save(captor.capture());
            EmailMessage toSave = captor.getValue();

            assertThat(toSave.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(toSave.getDirection()).isEqualTo("OUTBOUND");
            assertThat(toSave.getToAddress()).isEqualTo(TO_ADDRESS);
            assertThat(toSave.getSubject()).isEqualTo(SUBJECT);
            assertThat(toSave.getBodyHtml()).isEqualTo(BODY_HTML);
            assertThat(toSave.getContactId()).isNull();
            assertThat(toSave.getDeliveryStatus()).isEqualTo("SENT");
            assertThat(toSave.getMessageIdHeader()).startsWith("<");
            assertThat(toSave.getMessageIdHeader()).contains("@");
            assertThat(toSave.getSentAt()).isNotNull();

            // event email.sent opublikowany z zapisaną wiadomością i agentId
            verify(emailEventPublisher).publishSent(eq(saved), eq(AGENT_ID));
        }

        @Test
        @DisplayName("sendSmtp powinien być wywołany z null inReplyTo i null references (nowy wątek)")
        void shouldCallSendSmtpWithNullThreadHeaders() throws Exception {
            // given
            Tenant tenant = buildTenantWithSmtpConfig();
            when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(tenant));
            when(encryptionService.decrypt(anyString())).thenReturn("pass");
            when(emailMessageRepository.save(any())).thenReturn(buildSavedMessage());

            EmailSendServiceImpl spy = spy(emailSendService);
            doNothing().when(spy).sendSmtp(
                    any(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), any(), any(), anyList());

            // when
            spy.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, null);

            // then – inReplyTo i references muszą być null
            verify(spy).sendSmtp(
                    any(), anyString(), anyString(), eq(TO_ADDRESS),
                    eq(SUBJECT), eq(BODY_HTML), anyString(),
                    isNull(),  // inReplyTo
                    isNull(),  // references
                    anyList()); // attachments
        }
    }

    // =========================================================================
    // Scenariusze błędów
    // =========================================================================

    @Nested
    @DisplayName("Scenariusze błędów")
    class ErrorScenarios {

        @Test
        @DisplayName("powinien rzucić ResourceNotFoundException gdy tenant nie istnieje")
        void shouldThrowWhenTenantNotFound() {
            // given
            when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    emailSendService.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, null))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(TENANT_ID.toString());

            verifyNoInteractions(emailMessageRepository, emailEventPublisher);
        }

        @Test
        @DisplayName("powinien rzucić EmailSendException gdy brak konfiguracji SMTP")
        void shouldThrowWhenNoSmtpConfig() {
            // given – tenant bez konfiguracji email
            Tenant tenant = Tenant.builder()
                    .id(TENANT_ID)
                    .config(new HashMap<>()) // brak kluczy SMTP
                    .build();
            when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(tenant));

            // when / then
            assertThatThrownBy(() ->
                    emailSendService.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, null))
                    .isInstanceOf(EmailSendService.EmailSendException.class)
                    .hasMessageContaining("nie ma skonfigurowanego konta SMTP");

            verifyNoInteractions(emailMessageRepository, emailEventPublisher);
        }

        @Test
        @DisplayName("powinien rzucić EmailSendException gdy odszyfrowanie hasła SMTP zawiedzie")
        void shouldThrowWhenDecryptionFails() {
            // given
            Tenant tenant = buildTenantWithSmtpConfig();
            when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(tenant));
            when(encryptionService.decrypt(anyString()))
                    .thenThrow(new EmailEncryptionService.EmailEncryptionException("Błąd AES",
                            new RuntimeException("cause")));

            // when / then
            assertThatThrownBy(() ->
                    emailSendService.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, null))
                    .isInstanceOf(EmailSendService.EmailSendException.class)
                    .hasMessageContaining("odszyfrować hasła SMTP")
                    .hasCauseInstanceOf(EmailEncryptionService.EmailEncryptionException.class);

            verifyNoInteractions(emailMessageRepository, emailEventPublisher);
        }

        @Test
        @DisplayName("powinien rzucić EmailSendException gdy sendSmtp rzuci MessagingException")
        void shouldThrowWhenSmtpFails() throws Exception {
            // given
            Tenant tenant = buildTenantWithSmtpConfig();
            when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(tenant));
            when(encryptionService.decrypt(anyString())).thenReturn("pass");

            EmailSendServiceImpl spy = spy(emailSendService);
            doThrow(new MessagingException("Connection refused"))
                    .when(spy).sendSmtp(
                            any(), anyString(), anyString(), anyString(),
                            anyString(), anyString(), anyString(), any(), any(), anyList());

            // when / then
            assertThatThrownBy(() ->
                    spy.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, null))
                    .isInstanceOf(EmailSendService.EmailSendException.class)
                    .hasMessageContaining("Wysyłka SMTP nie powiodła się")
                    .hasCauseInstanceOf(MessagingException.class);

            // wiadomość NIE powinna być zapisana w DB po błędzie SMTP
            verify(emailMessageRepository, never()).save(any());
            verifyNoInteractions(emailEventPublisher);
        }
    }

    // =========================================================================
    // BE-143: allow-lista s3Key załączników PRZED SMTP/S3
    // =========================================================================

    @Nested
    @DisplayName("BE-143 – allow-lista s3Key: sendNew")
    class SendNewAttachmentKeyValidation {

        @Test
        @DisplayName("(i) klucz obcego tenanta odrzucony: S3/tenant/SMTP nie wywołane, wiadomość niezapisana")
        void foreignTenantKey_rejected() {
            EmailReplyRequest.PendingAttachment attachment = new EmailReplyRequest.PendingAttachment(
                    "email-attachments/" + OTHER_TENANT_ID + "/x/plik.pdf", "plik.pdf", "application/pdf", 10L);

            assertThatThrownBy(() ->
                    emailSendService.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, List.of(attachment)))
                    .isInstanceOf(EmailAttachmentAccessDeniedException.class);

            verifyNoInteractions(attachmentStorageService, emailMessageRepository, emailEventPublisher, tenantService);
        }

        @Test
        @DisplayName("(ii) klucz z segmentem .. (próba wyjścia z prefiksu) odrzucony")
        void pathTraversalKey_rejected() {
            EmailReplyRequest.PendingAttachment attachment = new EmailReplyRequest.PendingAttachment(
                    "email-attachments/" + TENANT_ID + "/../" + OTHER_TENANT_ID + "/x/plik.pdf",
                    "plik.pdf", "application/pdf", 10L);

            assertThatThrownBy(() ->
                    emailSendService.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, List.of(attachment)))
                    .isInstanceOf(EmailAttachmentAccessDeniedException.class);

            verifyNoInteractions(attachmentStorageService, emailMessageRepository, emailEventPublisher, tenantService);
        }

        @Test
        @DisplayName("(iii) klucz nagrania {tenantId}/....mp3 odrzucony (inny korzeń niż email-attachments/)")
        void recordingKey_rejected() {
            EmailReplyRequest.PendingAttachment attachment = new EmailReplyRequest.PendingAttachment(
                    TENANT_ID + "/2026/09/" + UUID.randomUUID() + ".mp3", "nagranie.mp3", "audio/mpeg", 10L);

            assertThatThrownBy(() ->
                    emailSendService.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, List.of(attachment)))
                    .isInstanceOf(EmailAttachmentAccessDeniedException.class);

            verifyNoInteractions(attachmentStorageService, emailMessageRepository, emailEventPublisher, tenantService);
        }

        @Test
        @DisplayName("(iv) własny klucz pending/ przechodzi i trafia do attachments zapisanej wiadomości OUTBOUND")
        void ownPendingKey_allowedAndPersisted() throws Exception {
            Tenant tenant = buildTenantWithSmtpConfig();
            when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(tenant));
            when(encryptionService.decrypt(anyString())).thenReturn("pass");
            when(emailMessageRepository.save(any())).thenReturn(buildSavedMessage());

            String ownKey = "email-attachments/" + TENANT_ID + "/pending/" + UUID.randomUUID() + "/plik.pdf";
            EmailReplyRequest.PendingAttachment attachment = new EmailReplyRequest.PendingAttachment(
                    ownKey, "plik.pdf", "application/pdf", 10L);

            EmailSendServiceImpl spy = spy(emailSendService);
            doNothing().when(spy).sendSmtp(
                    any(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), any(), any(), anyList());

            spy.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID, List.of(attachment));

            ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
            verify(emailMessageRepository).save(captor.capture());
            assertThat(captor.getValue().getAttachments()).contains(ownKey);

            // sendSmtp otrzymał listę zawierającą właśnie ten załącznik
            verify(spy).sendSmtp(any(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), any(), any(),
                    eq(List.of(attachment)));
        }
    }

    @Nested
    @DisplayName("BE-143 – allow-lista s3Key: sendReply")
    class SendReplyAttachmentKeyValidation {

        @Test
        @DisplayName("obcy klucz odrzucony: oryginalna wiadomość nie jest nawet pobierana, S3/SMTP nie wywołane")
        void foreignTenantKey_rejectedBeforeOriginalMessageLookup() {
            EmailReplyRequest.PendingAttachment attachment = new EmailReplyRequest.PendingAttachment(
                    "email-attachments/" + OTHER_TENANT_ID + "/x/plik.pdf", "plik.pdf", "application/pdf", 10L);

            assertThatThrownBy(() ->
                    emailSendService.sendReply(TENANT_ID, ORIGINAL_MSG_ID, BODY_HTML, SUBJECT, AGENT_ID, List.of(attachment)))
                    .isInstanceOf(EmailAttachmentAccessDeniedException.class);

            // walidacja jest pierwszym krokiem sendReply – repo (findById original, save),
            // tenantService i S3 nie są w ogóle dotykane
            verifyNoInteractions(emailMessageRepository, tenantService, attachmentStorageService, emailEventPublisher);
        }

        @Test
        @DisplayName("własny klucz INBOUND ({messageId}/) przechodzi end-to-end (regresja)")
        void ownInboundStyleKey_allowedAndPersisted() throws Exception {
            EmailMessage original = EmailMessage.builder()
                    .id(ORIGINAL_MSG_ID)
                    .tenantId(TENANT_ID)
                    .direction("INBOUND")
                    .fromAddress(TO_ADDRESS)
                    .subject("Pytanie klienta")
                    .messageIdHeader("<original@example.com>")
                    .build();
            when(emailMessageRepository.findById(ORIGINAL_MSG_ID)).thenReturn(Optional.of(original));

            Tenant tenant = buildTenantWithSmtpConfig();
            when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(tenant));
            when(encryptionService.decrypt(anyString())).thenReturn("pass");
            when(emailMessageRepository.save(any())).thenReturn(buildSavedMessage());

            String ownKey = "email-attachments/" + TENANT_ID + "/" + UUID.randomUUID() + "/zalacznik.pdf";
            EmailReplyRequest.PendingAttachment attachment = new EmailReplyRequest.PendingAttachment(
                    ownKey, "zalacznik.pdf", "application/pdf", 10L);

            EmailSendServiceImpl spy = spy(emailSendService);
            doNothing().when(spy).sendSmtp(
                    any(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), any(), any(), anyList());

            spy.sendReply(TENANT_ID, ORIGINAL_MSG_ID, BODY_HTML, SUBJECT, AGENT_ID, List.of(attachment));

            ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
            verify(emailMessageRepository).save(captor.capture());
            assertThat(captor.getValue().getAttachments()).contains(ownKey);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Buduje tenanta z minimalną, poprawną konfiguracją SMTP (pola wymagane przez
     * {@link EmailAccountConfig#fromTenantConfig(Map)}).
     *
     * <p>Uwaga: pole PK Tenant to {@code id} (kolumna tenant_id), nie {@code tenantId}.
     */
    private Tenant buildTenantWithSmtpConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("email_smtp_host", "smtp.example.com");
        config.put("email_smtp_port", 587);
        config.put("email_smtp_ssl", false);
        config.put("email_imap_host", "imap.example.com");
        config.put("email_imap_port", 993);
        config.put("email_imap_ssl", true);
        config.put("email_username", "agent@example.com");
        config.put("email_password", "ENCRYPTED_PASSWORD");

        return Tenant.builder()
                .id(TENANT_ID)
                .config(config)
                .build();
    }

    private EmailMessage buildSavedMessage() {
        return EmailMessage.builder()
                .id(MSG_ID)
                .tenantId(TENANT_ID)
                .direction("OUTBOUND")
                .fromAddress("agent@example.com")
                .toAddress(TO_ADDRESS)
                .subject(SUBJECT)
                .bodyHtml(BODY_HTML)
                .messageIdHeader("<" + UUID.randomUUID() + "@example.com>")
                .deliveryStatus("SENT")
                .build();
    }
}
