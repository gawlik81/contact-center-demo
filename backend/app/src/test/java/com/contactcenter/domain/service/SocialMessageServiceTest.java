package com.contactcenter.domain.service;

import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.SocialIntegration;
import com.contactcenter.domain.model.SocialMessage;
import com.contactcenter.domain.model.SocialPlatform;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.SocialIntegrationRepository;
import com.contactcenter.domain.repository.SocialMessageRepository;
import com.contactcenter.domain.social.IncomingSocialMessage;
import com.contactcenter.domain.social.SocialMediaAdapter;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.infrastructure.social.SocialAdapterRegistry;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link SocialMessageService}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Idempotentność – duplikat externalMessageId → skip (brak zapisu)</li>
 *   <li>Nowa wiadomość → nowy Contact QUEUED + ContactQueuedMessage</li>
 *   <li>Istniejący aktywny kontakt → dołącz wiadomość bez tworzenia nowego Contact</li>
 *   <li>sendMessage → wywołanie adaptera + zapis OUTBOUND</li>
 *   <li>Brak integracji dla pageId → WARN + brak wyjątku</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SocialMessageService – przetwarzanie wiadomości social media")
class SocialMessageServiceTest {

    private static final UUID TENANT_ID       = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INTEGRATION_ID  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CONTACT_ID      = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String PAGE_ID       = "TEST_PAGE_123";
    private static final String SENDER_ID     = "USER_456";
    private static final String EXTERNAL_MSG_ID = "MID_789";

    @Mock private SocialIntegrationRepository socialIntegrationRepository;
    @Mock private SocialMessageRepository socialMessageRepository;
    @Mock private ContactRepository contactRepository;
    @Mock private SocialAdapterRegistry adapterRegistry;
    @Mock private RabbitTemplate rabbitTemplate;

    private SocialMessageService service;

    @BeforeEach
    void setUp() {
        service = new SocialMessageService(
                socialIntegrationRepository,
                socialMessageRepository,
                contactRepository,
                adapterRegistry,
                rabbitTemplate
        );
    }

    @AfterEach
    void tearDown() {
        // Gwarantuj czyszczenie TenantContext po każdym teście
        TenantContext.clear();
    }

    // =========================================================================
    // Test 1: Idempotentność – duplikat externalMessageId
    // =========================================================================

    @Test
    @DisplayName("Duplikat externalMessageId → skip, brak zapisu wiadomości")
    void processIncomingMessage_duplicateExternalId_skips() {
        // Given
        SocialIntegration integration = buildIntegration();
        SocialMessage existingMessage = SocialMessage.builder()
                .messageId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .contactId(CONTACT_ID)
                .platform(SocialPlatform.FACEBOOK)
                .direction("INBOUND")
                .externalMessageId(EXTERNAL_MSG_ID)
                .build();

        when(socialIntegrationRepository.findByPlatformAndPageId(SocialPlatform.FACEBOOK, PAGE_ID))
                .thenReturn(Optional.of(integration));
        when(socialMessageRepository.findByExternalMessageId(EXTERNAL_MSG_ID, TENANT_ID))
                .thenReturn(Optional.of(existingMessage));

        IncomingSocialMessage incoming = buildIncomingMessage();

        // When
        service.processIncomingMessage(incoming);

        // Then – żadna wiadomość NIE powinna zostać zapisana
        verify(socialMessageRepository, never()).save(any());
        verify(contactRepository, never()).insert(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    // =========================================================================
    // Test 2: Nowa wiadomość → nowy Contact QUEUED
    // =========================================================================

    @Test
    @DisplayName("Nowa wiadomość bez aktywnego kontaktu → nowy Contact QUEUED + ContactQueuedMessage")
    void processIncomingMessage_noActiveContact_createsNewContact() {
        // Given
        SocialIntegration integration = buildIntegration();

        when(socialIntegrationRepository.findByPlatformAndPageId(SocialPlatform.FACEBOOK, PAGE_ID))
                .thenReturn(Optional.of(integration));
        when(socialMessageRepository.findByExternalMessageId(EXTERNAL_MSG_ID, TENANT_ID))
                .thenReturn(Optional.empty()); // brak duplikatu
        when(contactRepository.findActiveSocialContact(TENANT_ID, SENDER_ID, "SOCIAL_FACEBOOK"))
                .thenReturn(Optional.empty()); // brak aktywnego kontaktu
        when(contactRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(socialMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IncomingSocialMessage incoming = buildIncomingMessage();

        // When
        service.processIncomingMessage(incoming);

        // Then – nowy kontakt musi być utworzony
        ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
        verify(contactRepository).insert(contactCaptor.capture());
        Contact createdContact = contactCaptor.getValue();

        assertThat(createdContact.getChannel()).isEqualTo("SOCIAL_FACEBOOK");
        assertThat(createdContact.getDirection()).isEqualTo("INBOUND");
        assertThat(createdContact.getStatus()).isEqualTo("QUEUED");
        assertThat(createdContact.getRemoteAddress()).isEqualTo(SENDER_ID);
        assertThat(createdContact.getTenantId()).isEqualTo(TENANT_ID);

        // Wiadomość musi być zapisana
        verify(socialMessageRepository).save(any(SocialMessage.class));

        // ContactQueuedMessage musi być opublikowany
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_EVENTS),
                eq("contact.queued"),
                any(Object.class)
        );
    }

    // =========================================================================
    // Test 3: Istniejący aktywny kontakt → dołącz wiadomość
    // =========================================================================

    @Test
    @DisplayName("Istniejący aktywny kontakt → dołącz wiadomość bez tworzenia nowego Contact")
    void processIncomingMessage_existingActiveContact_appendsMessage() {
        // Given
        SocialIntegration integration = buildIntegration();
        Contact existingContact = Contact.builder()
                .contactId(CONTACT_ID)
                .tenantId(TENANT_ID)
                .channel("SOCIAL_FACEBOOK")
                .status("ACTIVE")
                .remoteAddress(SENDER_ID)
                .startedAt(Instant.now())
                .build();

        when(socialIntegrationRepository.findByPlatformAndPageId(SocialPlatform.FACEBOOK, PAGE_ID))
                .thenReturn(Optional.of(integration));
        when(socialMessageRepository.findByExternalMessageId(EXTERNAL_MSG_ID, TENANT_ID))
                .thenReturn(Optional.empty());
        when(contactRepository.findActiveSocialContact(TENANT_ID, SENDER_ID, "SOCIAL_FACEBOOK"))
                .thenReturn(Optional.of(existingContact));
        when(socialMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IncomingSocialMessage incoming = buildIncomingMessage();

        // When
        service.processIncomingMessage(incoming);

        // Then – kontakt NIE powinien być tworzony od nowa
        verify(contactRepository, never()).insert(any());

        // Wiadomość musi być dołączona do istniejącego kontaktu
        ArgumentCaptor<SocialMessage> msgCaptor = ArgumentCaptor.forClass(SocialMessage.class);
        verify(socialMessageRepository).save(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getContactId()).isEqualTo(CONTACT_ID);
        assertThat(msgCaptor.getValue().getDirection()).isEqualTo("INBOUND");
        assertThat(msgCaptor.getValue().getPlatform()).isEqualTo(SocialPlatform.FACEBOOK);

        // ContactQueuedMessage NIE powinien być publikowany (kontakt już w kolejce/aktywny)
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    // =========================================================================
    // Test 4: sendMessage → wywołanie adaptera + zapis OUTBOUND
    // =========================================================================

    @Test
    @DisplayName("sendMessage → wywołuje adapter + zapisuje wiadomość OUTBOUND")
    void sendMessage_callsAdapterAndSavesOutbound() {
        // Given
        Contact contact = Contact.builder()
                .contactId(CONTACT_ID)
                .tenantId(TENANT_ID)
                .channel("SOCIAL_FACEBOOK")
                .status("ACTIVE")
                .remoteAddress(SENDER_ID)
                .startedAt(Instant.now())
                .build();

        SocialIntegration integration = buildIntegration();
        SocialMediaAdapter adapter = mock(SocialMediaAdapter.class);

        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
        when(socialIntegrationRepository.findByTenantIdAndPlatform(TENANT_ID, SocialPlatform.FACEBOOK))
                .thenReturn(List.of(integration));
        when(adapterRegistry.getAdapter(SocialPlatform.FACEBOOK)).thenReturn(adapter);
        when(socialMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Ustaw TenantContext (normalnie robi TenantFilter dla żądań HTTP)
        TenantContext.setTenantId(TENANT_ID);

        // When
        service.sendMessage(CONTACT_ID, TENANT_ID, "Hej, jak mogę pomóc?", List.of());

        // Then – adapter musi być wywołany z prawidłowymi parametrami
        verify(adapter).sendMessage(
                eq(INTEGRATION_ID),
                eq(SENDER_ID),
                eq("Hej, jak mogę pomóc?"),
                eq(List.of())
        );

        // Wiadomość OUTBOUND musi być zapisana
        ArgumentCaptor<SocialMessage> msgCaptor = ArgumentCaptor.forClass(SocialMessage.class);
        verify(socialMessageRepository).save(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getDirection()).isEqualTo("OUTBOUND");
        assertThat(msgCaptor.getValue().getContactId()).isEqualTo(CONTACT_ID);
        assertThat(msgCaptor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(msgCaptor.getValue().getPlatform()).isEqualTo(SocialPlatform.FACEBOOK);
    }

    // =========================================================================
    // Test 5: Brak integracji dla pageId → WARN, brak wyjątku
    // =========================================================================

    @Test
    @DisplayName("Brak integracji dla pageId → loguje WARN, nie rzuca wyjątku")
    void processIncomingMessage_noIntegration_gracefulDegradation() {
        // Given
        when(socialIntegrationRepository.findByPlatformAndPageId(SocialPlatform.FACEBOOK, "UNKNOWN_PAGE"))
                .thenReturn(Optional.empty());

        IncomingSocialMessage incoming = new IncomingSocialMessage(
                SocialPlatform.FACEBOOK,
                "UNKNOWN_PAGE",
                SENDER_ID,
                EXTERNAL_MSG_ID,
                "Hello",
                null,
                Instant.now()
        );

        // When / Then – żaden wyjątek nie powinien być rzucony
        assertThatCode(() -> service.processIncomingMessage(incoming))
                .doesNotThrowAnyException();

        // Weryfikuj że nic nie zostało zapisane
        verify(socialMessageRepository, never()).save(any());
        verify(contactRepository, never()).insert(any());
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private SocialIntegration buildIntegration() {
        return SocialIntegration.builder()
                .integrationId(INTEGRATION_ID)
                .tenantId(TENANT_ID)
                .platform(SocialPlatform.FACEBOOK)
                .pageId(PAGE_ID)
                .webhookStatus("ACTIVE")
                .build();
    }

    private IncomingSocialMessage buildIncomingMessage() {
        return new IncomingSocialMessage(
                SocialPlatform.FACEBOOK,
                PAGE_ID,
                SENDER_ID,
                EXTERNAL_MSG_ID,
                "Cześć, mam pytanie",
                null,
                Instant.now()
        );
    }
}
