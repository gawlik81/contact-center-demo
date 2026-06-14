package com.contactcenter.domain;

import com.contactcenter.domain.exception.AiConfigNotFoundException;
import com.contactcenter.domain.exception.AiSummaryGenerationException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.AiProvider;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactAiSummary;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.repository.EmailMessageRepository;
import com.contactcenter.domain.service.AiSummaryClient;
import com.contactcenter.domain.service.AiSummaryService;
import com.contactcenter.domain.tenant.TenantAiConfigDecrypted;
import com.contactcenter.domain.tenant.TenantAiConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AiSummaryService – logika generowania podsumowań AI")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiSummaryServiceTest {

    private static final UUID TENANT_ID  = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CONTACT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Mock
    private ContactService contactService;

    @Mock
    private EmailMessageRepository emailMessageRepository;

    @Mock
    private TenantAiConfigService aiConfigService;

    @Mock
    private AiSummaryClient aiSummaryClient;

    @InjectMocks
    private AiSummaryService aiSummaryService;

    private TenantAiConfigDecrypted validAiConfig;

    @BeforeEach
    void setUp() {
        validAiConfig = new TenantAiConfigDecrypted(
                AiProvider.OPENAI,
                "sk-test-api-key",
                "gpt-4o",
                null,
                null,
                null
        );
    }

    // =========================================================================
    // generateSummary() – happy path
    // =========================================================================

    @Nested
    @DisplayName("generateSummary() – happy path")
    class HappyPathTests {

        @Test
        @DisplayName("PHONE: zwraca wynik i zapisuje ai_summary w bazie")
        void generateSummary_phoneChannel_savesAndReturnsResult() {
            // given
            Contact contact = buildContact("PHONE");

            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(contact));
            when(aiConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(validAiConfig));
            // transkrypcja pochodzi teraz z contact_transcription, nie z notes
            when(contactService.findTranscriptionContent(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of("Klient pytał o status zamówienia nr 12345."));
            AiSummaryClient.AiSummarizeResponse clientResponse =
                    new AiSummaryClient.AiSummarizeResponse("Klient pytał o status zamówienia.", "gpt-4o", 120);
            when(aiSummaryClient.summarize(any(AiSummaryClient.AiSummarizeRequest.class)))
                    .thenReturn(clientResponse);
            doNothing().when(contactService).saveAiSummary(any(ContactAiSummary.class));

            // when
            AiSummaryService.AiSummaryResult result = aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID);

            // then
            assertThat(result.summary()).isEqualTo("Klient pytał o status zamówienia.");
            assertThat(result.modelUsed()).isEqualTo("gpt-4o");
            assertThat(result.tokensUsed()).isEqualTo(120);

            // contactService.saveAiSummary() musi zostać wywołane raz z poprawnymi argumentami
            ArgumentCaptor<ContactAiSummary> captor = ArgumentCaptor.forClass(ContactAiSummary.class);
            verify(contactService, times(1)).saveAiSummary(captor.capture());
            assertThat(captor.getValue().getSummary()).isEqualTo("Klient pytał o status zamówienia.");
            assertThat(captor.getValue().getModel()).isEqualTo("gpt-4o");
            assertThat(captor.getValue().getContactId()).isEqualTo(CONTACT_ID);
            assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("EMAIL: treść pobierana z emailMessageRepository (brak wiadomości → fallback)")
        void generateSummary_emailChannel_usesChannelMetadataBody() {
            // given
            Contact contact = buildContact("EMAIL");
            contact.setChannelMetadata(new HashMap<>());

            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(contact));
            when(aiConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(validAiConfig));
            when(emailMessageRepository.findByContactId(eq(CONTACT_ID), eq(TENANT_ID), any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(aiSummaryClient.summarize(any()))
                    .thenReturn(new AiSummaryClient.AiSummarizeResponse("Brak emaili.", "gpt-4o", 20));
            doNothing().when(contactService).saveAiSummary(any(ContactAiSummary.class));

            // when
            aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID);

            // then: content fallback gdy brak wiadomości email
            ArgumentCaptor<AiSummaryClient.AiSummarizeRequest> reqCaptor =
                    ArgumentCaptor.forClass(AiSummaryClient.AiSummarizeRequest.class);
            verify(aiSummaryClient).summarize(reqCaptor.capture());
            assertThat(reqCaptor.getValue().content())
                    .isEqualTo("[Brak treści emaila]");
        }

        @Test
        @DisplayName("PHONE bez transkrypcji: content = '[Brak transkrypcji rozmowy]'")
        void generateSummary_phoneNoNotes_usesFallbackContent() {
            // given
            Contact contact = buildContact("PHONE");

            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(contact));
            when(aiConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(validAiConfig));
            // brak transkrypcji w contact_transcription → fallback
            when(contactService.findTranscriptionContent(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.empty());
            when(aiSummaryClient.summarize(any()))
                    .thenReturn(new AiSummaryClient.AiSummarizeResponse("Brak treści.", "gpt-4o", 20));
            doNothing().when(contactService).saveAiSummary(any(ContactAiSummary.class));

            // when
            aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID);

            // then
            ArgumentCaptor<AiSummaryClient.AiSummarizeRequest> reqCaptor =
                    ArgumentCaptor.forClass(AiSummaryClient.AiSummarizeRequest.class);
            verify(aiSummaryClient).summarize(reqCaptor.capture());
            assertThat(reqCaptor.getValue().content()).isEqualTo("[Brak transkrypcji rozmowy]");
        }
    }

    // =========================================================================
    // generateSummary() – błędy
    // =========================================================================

    @Nested
    @DisplayName("generateSummary() – scenariusze błędów")
    class ErrorTests {

        @Test
        @DisplayName("brak kontaktu → ResourceNotFoundException")
        void generateSummary_contactNotFound_throwsResourceNotFoundException() {
            // given
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(CONTACT_ID.toString());

            verifyNoInteractions(aiConfigService, aiSummaryClient);
        }

        @Test
        @DisplayName("brak konfiguracji AI → AiConfigNotFoundException")
        void generateSummary_noAiConfig_throwsAiConfigNotFoundException() {
            // given
            Contact contact = buildContact("PHONE");
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(contact));
            when(aiConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID))
                    .isInstanceOf(AiConfigNotFoundException.class);

            verifyNoInteractions(aiSummaryClient);
        }

        @Test
        @DisplayName("błąd klienta AI → AiSummaryGenerationException propagowany")
        void generateSummary_clientThrows_exceptionPropagated() {
            // given
            Contact contact = buildContact("PHONE");
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(contact));
            when(aiConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(validAiConfig));
            when(contactService.findTranscriptionContent(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of("Notatka agenta."));
            when(aiSummaryClient.summarize(any()))
                    .thenThrow(new AiSummaryGenerationException("Serwis AI zwrócił błąd HTTP 503"));

            // when / then
            assertThatThrownBy(() -> aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID))
                    .isInstanceOf(AiSummaryGenerationException.class)
                    .hasMessageContaining("HTTP 503");

            // baza nie powinna być aktualizowana
            verify(contactService, never()).saveAiSummary(any(ContactAiSummary.class));
        }
    }

    // =========================================================================
    // extractContent() – unit testy metody pomocniczej
    // =========================================================================

    @Nested
    @DisplayName("extractContent() – wyodrębnianie treści z kontaktu")
    class ExtractContentTests {

        @Test
        @DisplayName("SOCIAL_FACEBOOK: treść z channelMetadata['content']")
        void extractContent_socialFacebook_usesContentKey() {
            Contact contact = buildContact("SOCIAL_FACEBOOK");
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("content", "Cześć, kiedy wyślecie paczkę?");
            contact.setChannelMetadata(metadata);

            String result = aiSummaryService.extractContent(contact);

            assertThat(result).isEqualTo("Cześć, kiedy wyślecie paczkę?");
        }

        @Test
        @DisplayName("EMAIL bez wiadomości: fallback '[Brak treści emaila]'")
        void extractContent_emailNoBodyContent_returnsFallback() {
            Contact contact = buildContact("EMAIL");
            contact.setChannelMetadata(new HashMap<>());
            // extractContent(Contact, UUID) z tenantId=null → emailMessageRepository zwraca pustą stronę
            when(emailMessageRepository.findByContactId(eq(CONTACT_ID), any(), any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            String result = aiSummaryService.extractContent(contact);

            assertThat(result).isEqualTo("[Brak treści emaila]");
        }
    }

    // =========================================================================
    // Pomocnicza fabryka kontaktu
    // =========================================================================

    private Contact buildContact(String channel) {
        Contact contact = new Contact();
        contact.setContactId(CONTACT_ID);
        contact.setTenantId(TENANT_ID);
        contact.setChannel(channel);
        contact.setDirection("INBOUND");
        contact.setStatus("COMPLETED");
        contact.setQueuedAt(Instant.now());
        contact.setStartedAt(Instant.now());
        contact.setChannelMetadata(new HashMap<>());
        return contact;
    }
}
