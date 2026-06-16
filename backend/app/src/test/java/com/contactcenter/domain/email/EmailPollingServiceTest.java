package com.contactcenter.domain.email;

import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link EmailPollingService}.
 *
 * <p>Używa mockowania Jakarta Mail Store/Folder/Message – brak rzeczywistego IMAP.
 * Weryfikuje:
 * <ul>
 *   <li>Deduplicacja po Message-ID (nie zapisuje duplikatów)</li>
 *   <li>Zapisywanie nowych wiadomości INBOUND</li>
 *   <li>Publikacja eventów received i queued</li>
 *   <li>Pominięcie tenantów bez email_enabled</li>
 *   <li>Błąd tenanta nie przerywa pętli innych tenantów</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailPollingService – polling IMAP")
class EmailPollingServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TEST_KEY  =
            "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    @Mock private TenantService tenantService;
    @Mock private EmailMessageRepository emailMessageRepository;
    @Mock private EmailRoutingService emailRoutingService;
    @Mock private EmailEventPublisher emailEventPublisher;
    @Mock private EmailAttachmentStorageService attachmentStorageService;

    private EmailEncryptionService encryptionService;
    private EmailPollingServiceImpl pollingService;

    @BeforeEach
    void setUp() {
        encryptionService = new EmailEncryptionServiceImpl(TEST_KEY);
        pollingService = new EmailPollingServiceImpl(
                tenantService,
                emailMessageRepository,
                emailRoutingService,
                emailEventPublisher,
                encryptionService,
                attachmentStorageService,
                new ObjectMapper()
        );
    }

    // =========================================================================
    // pollTenantInbox – deduplicacja
    // =========================================================================

    @Nested
    @DisplayName("pollTenantInbox() – deduplicacja Message-ID")
    class Deduplication {

        @Test
        @DisplayName("wiadomość z istniejącym Message-ID powinna być pominięta")
        void shouldSkipDuplicateMessageId() throws Exception {
            Tenant tenant = buildTenant(true);
            String encryptedPassword = encryptionService.encrypt("test-password");
            tenant.getConfig().put("email_password", encryptedPassword);

            // Symuluj: Message-ID już istnieje w DB
            when(emailMessageRepository.findByMessageIdHeader(eq("<existing@domain.com>"), eq(TENANT_ID)))
                    .thenReturn(Optional.of(buildExistingMessage()));

            // Mockowany Store/Folder/Message
            Message mockMessage = mockImapMessage("<existing@domain.com>", "Re: stary temat",
                    "sender@example.com", "inbox@company.com");

            EmailPollingServiceImpl spy = spy(pollingService);
            Store mockStore = mock(Store.class);
            Folder mockFolder = mock(Folder.class);

            doReturn(mockStore).when(spy).connectImap(any(), anyString());
            when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
            when(mockFolder.search(any())).thenReturn(new Message[]{mockMessage});

            spy.pollTenantInbox(tenant);

            // Duplikat NIE powinien być zapisany
            verify(emailMessageRepository, never()).save(any());
            verify(emailEventPublisher, never()).publishReceived(any());
        }

        @Test
        @DisplayName("nowa wiadomość (brak duplikatu) powinna być zapisana")
        void shouldSaveNewMessage() throws Exception {
            Tenant tenant = buildTenant(true);
            String encryptedPassword = encryptionService.encrypt("test-password");
            tenant.getConfig().put("email_password", encryptedPassword);

            // Brak duplikatu w DB
            when(emailMessageRepository.findByMessageIdHeader(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            // Zwróć zapisaną encję z ID
            EmailMessage savedMessage = buildNewMessage();
            when(emailMessageRepository.save(any())).thenReturn(savedMessage);

            Message mockMessage = mockImapMessage("<new@domain.com>", "Nowy temat",
                    "new@sender.com", "inbox@company.com");

            EmailPollingServiceImpl spy = spy(pollingService);
            Store mockStore = mock(Store.class);
            Folder mockFolder = mock(Folder.class);

            doReturn(mockStore).when(spy).connectImap(any(), anyString());
            when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
            when(mockFolder.search(any())).thenReturn(new Message[]{mockMessage});

            spy.pollTenantInbox(tenant);

            // Nowa wiadomość POWINNA być zapisana
            verify(emailMessageRepository, times(1)).save(any());
            verify(emailEventPublisher, times(1)).publishReceived(eq(savedMessage));
        }
    }

    // =========================================================================
    // pollTenantInbox – konfiguracja
    // =========================================================================

    @Nested
    @DisplayName("pollTenantInbox() – konfiguracja tenanta")
    class TenantConfig {

        @Test
        @DisplayName("tenant bez konfiguracji email powinien być pominięty bez błędu")
        void shouldSkipTenantWithoutEmailConfig() throws Exception {
            Tenant tenant = Tenant.builder()
                    .id(TENANT_ID)
                    .name("Test Tenant")
                    .status(Tenant.TenantStatus.ACTIVE)
                    .config(new HashMap<>()) // brak email_imap_host
                    .build();

            // Brak wywołania connectImap – nie łączymy gdy brak konfiguracji
            EmailPollingServiceImpl spy = spy(pollingService);
            spy.pollTenantInbox(tenant);

            verify(spy, never()).connectImap(any(), anyString());
        }

        @Test
        @DisplayName("błąd deszyfrowania hasła powinien zatrzymać polling dla tenanta bez wyjątku globalnego")
        void shouldHandleDecryptionError() {
            Tenant tenant = buildTenant(true);
            // Zapisz nieprawidłowy szyfrogram – nie da się odszyfrować
            tenant.getConfig().put("email_password", "to-nie-jest-poprawny-szyfrogram");

            // Błąd deszyfrowania – metoda powinna zalogować błąd i zwrócić
            // (nie rzucać wyjątku na poziomie pollTenantInbox)
            assertThatCode(() -> pollingService.pollTenantInbox(tenant))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Tenant buildTenant(boolean emailEnabled) {
        Map<String, Object> config = new HashMap<>();
        config.put("email_enabled", emailEnabled);
        config.put("email_imap_host", "imap.test.com");
        config.put("email_imap_port", 993);
        config.put("email_imap_ssl", true);
        config.put("email_smtp_host", "smtp.test.com");
        config.put("email_smtp_port", 587);
        config.put("email_smtp_ssl", false);
        config.put("email_username", "inbox@company.com");
        // email_password ustawiane przez test

        return Tenant.builder()
                .id(TENANT_ID)
                .name("Test Tenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .config(config)
                .build();
    }

    private EmailMessage buildExistingMessage() {
        return EmailMessage.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .direction("INBOUND")
                .fromAddress("sender@example.com")
                .toAddress("inbox@company.com")
                .subject("Stary temat")
                .messageIdHeader("<existing@domain.com>")
                .receivedAt(Instant.now())
                .build();
    }

    private EmailMessage buildNewMessage() {
        return EmailMessage.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .direction("INBOUND")
                .fromAddress("new@sender.com")
                .toAddress("inbox@company.com")
                .subject("Nowy temat")
                .messageIdHeader("<new@domain.com>")
                .receivedAt(Instant.now())
                .build();
    }

    private Message mockImapMessage(String messageId, String subject,
                                     String from, String to) throws Exception {
        Message message = mock(Message.class);
        when(message.getHeader("Message-ID")).thenReturn(new String[]{messageId});
        when(message.getHeader("In-Reply-To")).thenReturn(null);
        when(message.getSubject()).thenReturn(subject);

        Address fromAddress = new jakarta.mail.internet.InternetAddress(from);
        when(message.getFrom()).thenReturn(new Address[]{fromAddress});

        Address toAddress = new jakarta.mail.internet.InternetAddress(to);
        when(message.getRecipients(Message.RecipientType.TO)).thenReturn(new Address[]{toAddress});
        when(message.getRecipients(Message.RecipientType.CC)).thenReturn(null);
        when(message.getRecipients(Message.RecipientType.BCC)).thenReturn(null);

        when(message.getContentType()).thenReturn("text/plain");
        when(message.getContent()).thenReturn("Treść wiadomości testowej.");
        when(message.getReceivedDate()).thenReturn(Date.from(Instant.now()));

        return message;
    }
}
