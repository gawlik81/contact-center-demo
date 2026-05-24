package com.contactcenter.domain;

import com.contactcenter.domain.exception.AiConfigNotFoundException;
import com.contactcenter.domain.exception.AiSummaryGenerationException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.AiProvider;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.service.AiSummaryClient;
import com.contactcenter.domain.service.AiSummaryService;
import com.contactcenter.domain.service.TenantAiConfigDecrypted;
import com.contactcenter.domain.service.TenantAiConfigService;
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

import java.time.Instant;
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
    private ContactRepository contactRepository;

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
            contact.setNotes("Klient pytał o status zamówienia nr 12345.");

            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(contact));
            when(aiConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(validAiConfig));
            AiSummaryClient.AiSummarizeResponse clientResponse =
                    new AiSummaryClient.AiSummarizeResponse("Klient pytał o status zamówienia.", "gpt-4o", 120);
            when(aiSummaryClient.summarize(any(AiSummaryClient.AiSummarizeRequest.class)))
                    .thenReturn(clientResponse);
            doNothing().when(contactRepository)
                    .updateAiSummary(eq(CONTACT_ID), eq(TENANT_ID), anyString(), anyString(), any(Instant.class));

            // when
            AiSummaryService.AiSummaryResult result = aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID);

            // then
            assertThat(result.summary()).isEqualTo("Klient pytał o status zamówienia.");
            assertThat(result.modelUsed()).isEqualTo("gpt-4o");
            assertThat(result.tokensUsed()).isEqualTo(120);

            // updateAiSummary musi zostać wywołane raz z poprawnymi argumentami
            ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> modelCaptor   = ArgumentCaptor.forClass(String.class);
            verify(contactRepository, times(1))
                    .updateAiSummary(eq(CONTACT_ID), eq(TENANT_ID),
                            summaryCaptor.capture(), modelCaptor.capture(), any(Instant.class));
            assertThat(summaryCaptor.getValue()).isEqualTo("Klient pytał o status zamówienia.");
            assertThat(modelCaptor.getValue()).isEqualTo("gpt-4o");
        }

        @Test
        @DisplayName("EMAIL: treść pobierana z channelMetadata['body']")
        void generateSummary_emailChannel_usesChannelMetadataBody() {
            // given
            Contact contact = buildContact("EMAIL");
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("body", "Dzień dobry, chciałem zgłosić reklamację.");
            contact.setChannelMetadata(metadata);

            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(contact));
            when(aiConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(validAiConfig));
            when(aiSummaryClient.summarize(any()))
                    .thenReturn(new AiSummaryClient.AiSummarizeResponse("Reklamacja klienta.", "gpt-4o", 80));
            doNothing().when(contactRepository)
                    .updateAiSummary(any(), any(), any(), any(), any());

            // when
            aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID);

            // then: weryfikacja że client dostał treść z body
            ArgumentCaptor<AiSummaryClient.AiSummarizeRequest> reqCaptor =
                    ArgumentCaptor.forClass(AiSummaryClient.AiSummarizeRequest.class);
            verify(aiSummaryClient).summarize(reqCaptor.capture());
            assertThat(reqCaptor.getValue().content())
                    .isEqualTo("Dzień dobry, chciałem zgłosić reklamację.");
        }

        @Test
        @DisplayName("PHONE bez notatek: content = '[Brak transkrypcji/notatek]'")
        void generateSummary_phoneNoNotes_usesFallbackContent() {
            // given
            Contact contact = buildContact("PHONE");
            contact.setNotes(null);

            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(contact));
            when(aiConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(validAiConfig));
            when(aiSummaryClient.summarize(any()))
                    .thenReturn(new AiSummaryClient.AiSummarizeResponse("Brak treści.", "gpt-4o", 20));
            doNothing().when(contactRepository)
                    .updateAiSummary(any(), any(), any(), any(), any());

            // when
            aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID);

            // then
            ArgumentCaptor<AiSummaryClient.AiSummarizeRequest> reqCaptor =
                    ArgumentCaptor.forClass(AiSummaryClient.AiSummarizeRequest.class);
            verify(aiSummaryClient).summarize(reqCaptor.capture());
            assertThat(reqCaptor.getValue().content()).isEqualTo("[Brak transkrypcji/notatek]");
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
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
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
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
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
            contact.setNotes("Notatka agenta.");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(contact));
            when(aiConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(validAiConfig));
            when(aiSummaryClient.summarize(any()))
                    .thenThrow(new AiSummaryGenerationException("Serwis AI zwrócił błąd HTTP 503"));

            // when / then
            assertThatThrownBy(() -> aiSummaryService.generateSummary(CONTACT_ID, TENANT_ID))
                    .isInstanceOf(AiSummaryGenerationException.class)
                    .hasMessageContaining("HTTP 503");

            // baza nie powinna być aktualizowana
            verify(contactRepository, never())
                    .updateAiSummary(any(), any(), any(), any(), any());
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
        @DisplayName("EMAIL bez body i content: fallback '[Brak treści kontaktu]'")
        void extractContent_emailNoBodyContent_returnsFallback() {
            Contact contact = buildContact("EMAIL");
            contact.setChannelMetadata(new HashMap<>());

            String result = aiSummaryService.extractContent(contact);

            assertThat(result).isEqualTo("[Brak treści kontaktu]");
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
