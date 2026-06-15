package com.contactcenter.domain.email;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link EmailSendService#sendNew(UUID, String, String, String, UUID)}.
 *
 * <p>Strategia: {@code @Spy EmailSendService} + {@code doNothing().when(spy).sendSmtp(...)}
 * – unikamy realnego wywołania SMTP bez konieczności PowerMocka.
 * Weryfikujemy: zapis do repozytorium (ArgumentCaptor) i publikację eventu email.sent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailSendService – sendNew()")
class EmailSendServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MSG_ID    = UUID.fromString("33333333-3333-3333-3333-333333333333");

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

    private EmailSendService emailSendService;

    @BeforeEach
    void setUp() {
        emailSendService = new EmailSendService(
                emailMessageRepository,
                emailEventPublisher,
                tenantService,
                encryptionService,
                emailTemplateService,
                templateVariableResolver
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
            EmailSendService spy = spy(emailSendService);
            doNothing().when(spy).sendSmtp(
                    any(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), isNull(), isNull());

            // when
            EmailMessage result = spy.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID);

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

            EmailSendService spy = spy(emailSendService);
            doNothing().when(spy).sendSmtp(
                    any(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), any(), any());

            // when
            spy.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID);

            // then – inReplyTo i references muszą być null
            verify(spy).sendSmtp(
                    any(), anyString(), anyString(), eq(TO_ADDRESS),
                    eq(SUBJECT), eq(BODY_HTML), anyString(),
                    isNull(),  // inReplyTo
                    isNull()); // references
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
                    emailSendService.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID))
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
                    emailSendService.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID))
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
                    emailSendService.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID))
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

            EmailSendService spy = spy(emailSendService);
            doThrow(new MessagingException("Connection refused"))
                    .when(spy).sendSmtp(
                            any(), anyString(), anyString(), anyString(),
                            anyString(), anyString(), anyString(), any(), any());

            // when / then
            assertThatThrownBy(() ->
                    spy.sendNew(TENANT_ID, TO_ADDRESS, SUBJECT, BODY_HTML, AGENT_ID))
                    .isInstanceOf(EmailSendService.EmailSendException.class)
                    .hasMessageContaining("Wysyłka SMTP nie powiodła się")
                    .hasCauseInstanceOf(MessagingException.class);

            // wiadomość NIE powinna być zapisana w DB po błędzie SMTP
            verify(emailMessageRepository, never()).save(any());
            verifyNoInteractions(emailEventPublisher);
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
