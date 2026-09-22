package com.contactcenter.api.email;

import com.contactcenter.api.email.dto.EmailMessageResponse;
import com.contactcenter.api.email.dto.EmailReplyRequest;
import com.contactcenter.api.email.dto.OutboundEmailRequest;
import com.contactcenter.domain.email.EmailAttachmentAccessDeniedException;
import com.contactcenter.domain.email.EmailEncryptionService;
import com.contactcenter.domain.email.EmailMessage;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.email.EmailPollingService;
import com.contactcenter.domain.email.EmailSendService;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link EmailController#replyToMessage} i {@link EmailController#sendOutboundEmail}
 * (BE-143).
 *
 * <p>Wzorzec testów kontrolera w tym projekcie: wywołanie metod kontrolera bezpośrednio,
 * {@link EmailSendService} zamockowany, {@code TenantContext} mockowany statycznie — ten sam
 * wzorzec co {@code RetentionControllerTest}/{@code PluginAdminControllerTest} (bez {@code MockMvc}
 * ani łańcucha Spring Security dla pakietu {@code api.*}).
 *
 * <p><strong>BE-143 – dlaczego nie {@code MockMvc} z prawdziwym {@code EmailSendServiceImpl}:</strong>
 * ticket sugerował test integracyjny "prawdziwy EmailSendServiceImpl, mock S3 i SMTP" na poziomie
 * {@code MockMvc}. Zamiast tego pokrycie jest rozłożone na dwie klasy, zgodnie z ustaloną w tym
 * repo konwencją (patrz javadoc {@code RetentionControllerTest}): (1) {@code EmailSendServiceTest}
 * testuje SAMĄ walidację allow-listy i logikę wysyłki na rzeczywistym {@code EmailSendServiceImpl}
 * (spy na {@code sendSmtp}, mock S3) — to tam żyje cała logika biznesowa z AC (i)-(iv);
 * (2) ta klasa testuje wyłącznie, że kontroler poprawnie przekazuje {@code attachments} do serwisu
 * i NIE łapie/maskuje {@link EmailAttachmentAccessDeniedException} (deklaratywne potwierdzenie, że
 * wyjątek dotrze do {@code GlobalExceptionHandler} → 403, co z kolei ma własny test w
 * {@code GlobalExceptionHandlerTest}). Duplikowanie pełnej logiki serwisu za {@code MockMvc} nie
 * dodałoby tu pokrycia, tylko wolniejszy, bardziej kruchy test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailController – wysyłka z załącznikami (BE-143)")
class EmailControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORIGINAL_MSG_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Mock private EmailMessageService emailMessageService;
    @Mock private EmailSendService emailSendService;
    @Mock private TenantService tenantService;
    @Mock private EmailEncryptionService encryptionService;
    @Mock private EmailPollingService emailPollingService;

    private EmailController controller;
    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        controller = new EmailController(
                emailMessageService, emailSendService, tenantService, encryptionService, emailPollingService);
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(TenantContext::getUserId).thenReturn(AGENT_ID);
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    // =========================================================================
    // POST /api/email/messages/{id}/reply
    // =========================================================================

    @Nested
    @DisplayName("replyToMessage")
    class ReplyToMessage {

        @Test
        @DisplayName("przekazuje attachments z żądania do EmailSendService#sendReply bez zmian")
        void passesAttachmentsThroughToService() {
            EmailReplyRequest.PendingAttachment attachment = new EmailReplyRequest.PendingAttachment(
                    "email-attachments/" + TENANT_ID + "/pending/" + UUID.randomUUID() + "/f.pdf",
                    "f.pdf", "application/pdf", 10L);
            EmailReplyRequest request = new EmailReplyRequest("Treść", "Temat", List.of(attachment));

            when(emailSendService.sendReply(TENANT_ID, ORIGINAL_MSG_ID, "Treść", "Temat", AGENT_ID, List.of(attachment)))
                    .thenReturn(buildSavedMessage());

            ResponseEntity<EmailMessageResponse> response = controller.replyToMessage(ORIGINAL_MSG_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(emailSendService).sendReply(TENANT_ID, ORIGINAL_MSG_ID, "Treść", "Temat", AGENT_ID, List.of(attachment));
        }

        @Test
        @DisplayName("attachments == null w żądaniu → serwis dostaje pustą listę, nie null")
        void nullAttachments_passedAsEmptyList() {
            EmailReplyRequest request = new EmailReplyRequest("Treść", "Temat", null);
            when(emailSendService.sendReply(eq(TENANT_ID), eq(ORIGINAL_MSG_ID), eq("Treść"), eq("Temat"), eq(AGENT_ID), eq(List.of())))
                    .thenReturn(buildSavedMessage());

            controller.replyToMessage(ORIGINAL_MSG_ID, request);

            verify(emailSendService).sendReply(TENANT_ID, ORIGINAL_MSG_ID, "Treść", "Temat", AGENT_ID, List.of());
        }

        @Test
        @DisplayName("BE-143: EmailAttachmentAccessDeniedException rzucony przez serwis propaguje się niezłapany "
                + "(GlobalExceptionHandler zmapuje go na 403 – zob. GlobalExceptionHandlerTest)")
        void attachmentAccessDeniedException_propagatesUncaught() {
            EmailReplyRequest.PendingAttachment foreignAttachment = new EmailReplyRequest.PendingAttachment(
                    "email-attachments/" + OTHER_TENANT_ID + "/x/f.pdf", "f.pdf", "application/pdf", 10L);
            EmailReplyRequest request = new EmailReplyRequest("Treść", "Temat", List.of(foreignAttachment));

            when(emailSendService.sendReply(eq(TENANT_ID), eq(ORIGINAL_MSG_ID), eq("Treść"), eq("Temat"), eq(AGENT_ID), eq(List.of(foreignAttachment))))
                    .thenThrow(new EmailAttachmentAccessDeniedException(
                            "Załącznik wskazuje na obiekt S3 spoza dozwolonego zakresu tenanta"));

            assertThatThrownBy(() -> controller.replyToMessage(ORIGINAL_MSG_ID, request))
                    .isInstanceOf(EmailAttachmentAccessDeniedException.class);
        }
    }

    // =========================================================================
    // POST /api/email/messages/outbound
    // =========================================================================

    @Nested
    @DisplayName("sendOutboundEmail")
    class SendOutboundEmail {

        @Test
        @DisplayName("przekazuje attachments z żądania do EmailSendService#sendNew bez zmian")
        void passesAttachmentsThroughToService() {
            EmailReplyRequest.PendingAttachment attachment = new EmailReplyRequest.PendingAttachment(
                    "email-attachments/" + TENANT_ID + "/pending/" + UUID.randomUUID() + "/f.pdf",
                    "f.pdf", "application/pdf", 10L);
            OutboundEmailRequest request = new OutboundEmailRequest(
                    "klient@example.com", "Temat", "Treść", null, List.of(attachment));

            when(emailSendService.sendNew(TENANT_ID, "klient@example.com", "Temat", "Treść", AGENT_ID, List.of(attachment)))
                    .thenReturn(buildSavedMessage());

            ResponseEntity<EmailMessageResponse> response = controller.sendOutboundEmail(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(emailSendService).sendNew(TENANT_ID, "klient@example.com", "Temat", "Treść", AGENT_ID, List.of(attachment));
        }

        @Test
        @DisplayName("BE-143: EmailAttachmentAccessDeniedException rzucony przez serwis propaguje się niezłapany")
        void attachmentAccessDeniedException_propagatesUncaught() {
            EmailReplyRequest.PendingAttachment recordingKeyAttachment = new EmailReplyRequest.PendingAttachment(
                    TENANT_ID + "/2026/09/" + UUID.randomUUID() + ".mp3", "nagranie.mp3", "audio/mpeg", 10L);
            OutboundEmailRequest request = new OutboundEmailRequest(
                    "klient@example.com", "Temat", "Treść", null, List.of(recordingKeyAttachment));

            when(emailSendService.sendNew(eq(TENANT_ID), eq("klient@example.com"), eq("Temat"), eq("Treść"), eq(AGENT_ID), eq(List.of(recordingKeyAttachment))))
                    .thenThrow(new EmailAttachmentAccessDeniedException(
                            "Załącznik wskazuje na obiekt S3 spoza dozwolonego zakresu tenanta"));

            assertThatThrownBy(() -> controller.sendOutboundEmail(request))
                    .isInstanceOf(EmailAttachmentAccessDeniedException.class);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private EmailMessage buildSavedMessage() {
        return EmailMessage.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .direction("OUTBOUND")
                .fromAddress("agent@example.com")
                .toAddress("klient@example.com")
                .subject("Temat")
                .bodyHtml("Treść")
                .messageIdHeader("<" + UUID.randomUUID() + "@example.com>")
                .deliveryStatus("SENT")
                .sentAt(Instant.now())
                .build();
    }
}
