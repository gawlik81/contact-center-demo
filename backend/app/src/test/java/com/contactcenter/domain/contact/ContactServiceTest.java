package com.contactcenter.domain.contact;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.contact.dto.ActiveContactResponse;
import com.contactcenter.api.contact.dto.ContactFilterParams;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.api.contact.dto.ContactRecordingUrlResponse;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.CreateContactRequest;
import com.contactcenter.api.contact.dto.DispositionRequest;
import com.contactcenter.api.contact.dto.UpdateContactRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.email.EmailMessage;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.disposition.CustomDispositionService;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.email.EmailThread;
import com.contactcenter.domain.plugin.runtime.ExtensionPointPublisher;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.recording.RecordingException;
import com.contactcenter.domain.recording.RecordingService;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.telephony.TelephonyEventPublisher;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.contactcenter.api.contact.dto.EmailPreviewResponse;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link ContactService}.
 *
 * <p>Weryfikuje logikę biznesową: tworzenie, odczyt, listowanie z filtrami,
 * aktualizację, ustawianie disposition oraz kontrolę uprawnień AGENT vs SUPERVISOR.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContactService – CRUD historii kontaktów")
class ContactServiceTest {

    private static final UUID TENANT_ID    = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID     = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CUSTOMER_ID  = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID OTHER_AGENT  = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private ContactRepository contactRepository;
    @Mock private RecordingService recordingService;
    @Mock private EmailMessageService emailMessageService;
    @Mock private UserService userService;
    @Mock private QueueService queueService;
    @Mock private CustomerService customerService;
    @Mock private CampaignService campaignService;
    @Mock private CustomDispositionService customDispositionService;
    @Mock private ContactEventService contactEventService;
    @Mock private TelephonyAdapter telephonyAdapter;
    @Mock private TelephonyEventPublisher eventPublisher;
    @Mock private ExtensionPointPublisher extensionPointPublisher;

    @InjectMocks
    private ContactServiceImpl contactService;

    @BeforeEach
    void setUp() {
        // Setter-injected @Lazy dependencies — @InjectMocks nie wywołuje setterów
        // gdy użyto konstruktora; ustawiamy ręcznie dla obu cykli zależności.
        contactService.setUserService(userService);
        contactService.setExtensionPointPublisher(extensionPointPublisher);

        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");

        // Domyślnie brak custom dyspozycji – fallback nie znajduje etykiety.
        when(customDispositionService.findLabel(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Tworzenie kontaktu
    // =========================================================================

    @Nested
    @DisplayName("createContact")
    class CreateContactTests {

        @Test
        @DisplayName("tworzy kontakt z wymaganymi polami i statusem QUEUED")
        void createContact_createsWithRequiredFieldsAndQueuedStatus() {
            // given
            CreateContactRequest request = new CreateContactRequest(
                    CUSTOMER_ID, AGENT_ID, null, null,
                    "PHONE", "INBOUND", "+48501234567", null, null, null
            );
            Contact saved = buildContact(CONTACT_ID, "QUEUED");
            when(contactRepository.insert(any(Contact.class))).thenReturn(saved);

            // when
            ContactResponse response = contactService.createContact(request, TENANT_ID);

            // then
            assertThat(response.contactId()).isEqualTo(CONTACT_ID);
            assertThat(response.status()).isEqualTo("QUEUED");
            assertThat(response.channel()).isEqualTo("PHONE");

            verify(contactRepository).insert(argThat(c ->
                    TENANT_ID.equals(c.getTenantId())
                    && "PHONE".equals(c.getChannel())
                    && "INBOUND".equals(c.getDirection())
                    && "QUEUED".equals(c.getStatus())
                    && c.getContactId() != null
                    && c.getStartedAt() != null
                    && c.getQueuedAt() != null
            ));
        }

        @Test
        @DisplayName("tworzy kontakt voice kierowany do IVR ze statusem IVR i queuedAt=null")
        void createContact_withIvrEntry_createsWithIvrStatusAndNullQueuedAt() {
            // given
            CreateContactRequest request = new CreateContactRequest(
                    CUSTOMER_ID, null, null, null,
                    "PHONE", "INBOUND", "+48501234567", null, null, null
            );
            Contact saved = buildContact(CONTACT_ID, "IVR");
            saved.setQueuedAt(null);
            when(contactRepository.insert(any(Contact.class))).thenReturn(saved);

            // when
            ContactResponse response = contactService.createContact(request, TENANT_ID, true);

            // then
            assertThat(response.contactId()).isEqualTo(CONTACT_ID);
            assertThat(response.status()).isEqualTo("IVR");

            verify(contactRepository).insert(argThat(c ->
                    "IVR".equals(c.getStatus())
                    && c.getQueuedAt() == null
                    && c.getStartedAt() != null
            ));
        }

        @Test
        @DisplayName("używa startedAt z żądania gdy podano")
        void createContact_usesProvidedStartedAt() {
            // given
            Instant providedStartedAt = Instant.parse("2026-03-15T10:00:00Z");
            CreateContactRequest request = new CreateContactRequest(
                    null, null, null, null,
                    "EMAIL", "INBOUND", null, providedStartedAt, null, null
            );
            Contact saved = buildContact(CONTACT_ID, "QUEUED");
            saved.setStartedAt(providedStartedAt);
            when(contactRepository.insert(any(Contact.class))).thenReturn(saved);

            // when
            contactService.createContact(request, TENANT_ID);

            // then
            verify(contactRepository).insert(argThat(c ->
                    providedStartedAt.equals(c.getStartedAt())
            ));
        }

        @Test
        @DisplayName("inicjalizuje channelMetadata jako pustą mapę gdy null")
        void createContact_initializesEmptyChannelMetadataWhenNull() {
            // given
            CreateContactRequest request = new CreateContactRequest(
                    null, null, null, null, "PHONE", "OUTBOUND", null, null, null, null
            );
            Contact saved = buildContact(CONTACT_ID, "QUEUED");
            when(contactRepository.insert(any(Contact.class))).thenReturn(saved);

            // when
            contactService.createContact(request, TENANT_ID);

            // then
            verify(contactRepository).insert(argThat(c ->
                    c.getChannelMetadata() != null && c.getChannelMetadata().isEmpty()
            ));
        }
    }

    // =========================================================================
    // Odczyt kontaktu
    // =========================================================================

    @Nested
    @DisplayName("getContact")
    class GetContactTests {

        @Test
        @DisplayName("zwraca DTO gdy kontakt istnieje")
        void getContact_returnsResponseWhenFound() {
            // given
            Contact contact = buildContact(CONTACT_ID, "ACTIVE");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            // when – SUPERVISOR (isAgent=false)
            ContactResponse response = contactService.getContact(CONTACT_ID, TENANT_ID, AGENT_ID, false);

            // then
            assertThat(response.contactId()).isEqualTo(CONTACT_ID);
            assertThat(response.tenantId()).isEqualTo(TENANT_ID);
            assertThat(response.status()).isEqualTo("ACTIVE");
            verify(contactRepository).findById(CONTACT_ID, TENANT_ID);
        }

        @Test
        @DisplayName("AGENT widzi własny kontakt")
        void getContact_agentSeesOwnContact() {
            // given
            Contact contact = buildContact(CONTACT_ID, "ACTIVE");
            contact.setAgentId(AGENT_ID); // ten sam agent
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            // when
            ContactResponse response = contactService.getContact(CONTACT_ID, TENANT_ID, AGENT_ID, true);

            // then
            assertThat(response.contactId()).isEqualTo(CONTACT_ID);
        }

        @Test
        @DisplayName("AGENT nie może pobrać kontaktu przypisanego innemu agentowi")
        void getContact_agentCannotSeeOtherAgentContact() {
            // given
            Contact contact = buildContact(CONTACT_ID, "ACTIVE");
            contact.setAgentId(OTHER_AGENT); // inny agent
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            // when / then
            assertThatThrownBy(() -> contactService.getContact(CONTACT_ID, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining(CONTACT_ID.toString());
        }

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy kontakt nie istnieje")
        void getContact_throwsWhenNotFound() {
            // given
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> contactService.getContact(CONTACT_ID, TENANT_ID, AGENT_ID, false))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(CONTACT_ID.toString());
        }

        @Test
        @DisplayName("rozwiązuje dispositionLabel z custom dyspozycji tenanta gdy brak w campaign.dispositionCodes")
        void getContact_resolvesDispositionLabelFromCustomDisposition() {
            // given – kontakt z disposition code nieobecnym w dyspozycjach kampanii
            UUID campaignId = UUID.fromString("66666666-6666-6666-6666-666666666666");
            UUID queueId = UUID.fromString("77777777-7777-7777-7777-777777777777");

            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setCampaignId(campaignId);
            contact.setQueueId(queueId);
            contact.setDispositionCode("CUSTOM_ERR");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            com.contactcenter.domain.campaign.Campaign campaign =
                    com.contactcenter.domain.campaign.Campaign.builder()
                            .campaignId(campaignId)
                            .tenantId(TENANT_ID)
                            .dispositionCodes(List.of(Map.<String, Object>of("code", "SALE", "label", "Sprzedaż")))
                            .build();
            when(campaignService.findCampaignEntity(campaignId, TENANT_ID)).thenReturn(Optional.of(campaign));

            // brak w campaign.dispositionCodes -> fallback do custom_disposition
            when(customDispositionService.findLabel(campaignId, queueId, "CUSTOM_ERR", TENANT_ID))
                    .thenReturn(Optional.of("Błąd niestandardowy"));

            // when
            ContactResponse response = contactService.getContact(CONTACT_ID, TENANT_ID, AGENT_ID, false);

            // then
            assertThat(response.dispositionLabel()).isEqualTo("Błąd niestandardowy");
            verify(customDispositionService).findLabel(campaignId, queueId, "CUSTOM_ERR", TENANT_ID);
        }
    }

    // =========================================================================
    // Lista kontaktów
    // =========================================================================

    @Nested
    @DisplayName("listContacts")
    class ListContactsTests {

        private static final UUID QUEUE_ID    = UUID.fromString("66666666-6666-6666-6666-666666666666");
        private static final UUID CAMPAIGN_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

        /** Tworzy ContactFilterParams bez nowych filtrów BE-036 (wartości null). */
        private ContactFilterParams basicParams(int page, int size) {
            return new ContactFilterParams(null, null, null, null, null, null,
                    null, null, null, null, null, page, size);
        }

        @Test
        @DisplayName("SUPERVISOR widzi wszystkie kontakty – brak wymuszenia filtra agentId")
        void listContacts_supervisorSeesAllContacts() {
            // given
            ContactFilterParams params = basicParams(0, 20);
            List<Contact> contacts = List.of(buildContact(CONTACT_ID, "COMPLETED"));
            when(contactRepository.findContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                isNull(), isNull(), isNull(), isNull(), isNull(),
                                                isNull(), isNull(), eq(0), eq(20)))
                    .thenReturn(contacts);
            when(contactRepository.countContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                  isNull(), isNull(), isNull(), isNull(), isNull(),
                                                  isNull(), isNull()))
                    .thenReturn(1L);

            // when
            PagedResponse<ContactResponse> result = contactService.listContacts(
                    params, TENANT_ID, AGENT_ID, false /* isAgent=false = SUPERVISOR */);

            // then
            assertThat(result.content()).hasSize(1);
            assertThat(result.totalElements()).isEqualTo(1L);
            verify(contactRepository).findContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                   isNull(), isNull(), isNull(), isNull(), isNull(),
                                                   isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("AGENT widzi tylko własne kontakty – agentId wymuszony na userId")
        void listContacts_agentOnlySeesOwnContacts() {
            // given
            ContactFilterParams params = basicParams(0, 20);
            List<Contact> contacts = List.of(buildContact(CONTACT_ID, "ACTIVE"));
            when(contactRepository.findContacts(eq(TENANT_ID), eq(AGENT_ID), isNull(), isNull(), isNull(),
                                                isNull(), isNull(), isNull(), isNull(), isNull(),
                                                isNull(), isNull(), eq(0), eq(20)))
                    .thenReturn(contacts);
            when(contactRepository.countContacts(eq(TENANT_ID), eq(AGENT_ID), isNull(), isNull(), isNull(),
                                                  isNull(), isNull(), isNull(), isNull(), isNull(),
                                                  isNull(), isNull()))
                    .thenReturn(1L);

            // when
            PagedResponse<ContactResponse> result = contactService.listContacts(
                    params, TENANT_ID, AGENT_ID, true /* isAgent=true */);

            // then
            assertThat(result.content()).hasSize(1);
            verify(contactRepository).findContacts(eq(TENANT_ID), eq(AGENT_ID), isNull(), isNull(), isNull(),
                                                   isNull(), isNull(), isNull(), isNull(), isNull(),
                                                   isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("rozmiar strony ograniczony do max 100")
        void listContacts_capsPageSizeAtHundred() {
            // given
            ContactFilterParams params = basicParams(0, 999);
            when(contactRepository.findContacts(any(), any(), any(), any(), any(), any(), any(),
                                                any(), any(), any(), any(), any(), anyInt(), eq(100)))
                    .thenReturn(List.of());
            when(contactRepository.countContacts(any(), any(), any(), any(), any(), any(), any(),
                                                  any(), any(), any(), any(), any()))
                    .thenReturn(0L);

            // when
            contactService.listContacts(params, TENANT_ID, AGENT_ID, false);

            // then
            verify(contactRepository).findContacts(any(), any(), any(), any(), any(), any(), any(),
                                                   any(), any(), any(), any(), any(), anyInt(), eq(100));
        }

        @Test
        @DisplayName("zwraca metadane paginacji")
        void listContacts_returnsPaginationMetadata() {
            // given
            ContactFilterParams params = basicParams(0, 10);
            List<Contact> contacts = List.of(buildContact(CONTACT_ID, "COMPLETED"));
            when(contactRepository.findContacts(any(), any(), any(), any(), any(), any(), any(),
                                                any(), any(), any(), any(), any(), eq(0), eq(10)))
                    .thenReturn(contacts);
            when(contactRepository.countContacts(any(), any(), any(), any(), any(), any(), any(),
                                                  any(), any(), any(), any(), any()))
                    .thenReturn(35L);

            // when
            PagedResponse<ContactResponse> result = contactService.listContacts(
                    params, TENANT_ID, AGENT_ID, false);

            // then
            assertThat(result.page()).isEqualTo(0);
            assertThat(result.size()).isEqualTo(10);
            assertThat(result.totalElements()).isEqualTo(35L);
            assertThat(result.totalPages()).isEqualTo(4);
            assertThat(result.first()).isTrue();
            assertThat(result.last()).isFalse();
        }

        // =====================================================================
        // BE-036: testy nowych filtrów
        // =====================================================================

        @Test
        @DisplayName("BE-036: filtrowanie po queueId przekazuje UUID do repozytorium")
        void listContacts_filterByQueueId_passesQueueIdToRepository() {
            // given
            String queueIdStr = QUEUE_ID.toString();
            ContactFilterParams params = new ContactFilterParams(
                    null, null, null, null, null, null,
                    queueIdStr, null, null, null, null, 0, 20);
            List<Contact> contacts = List.of(buildContact(CONTACT_ID, "COMPLETED"));
            when(contactRepository.findContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                isNull(), isNull(), eq(queueIdStr), isNull(), isNull(),
                                                isNull(), isNull(), eq(0), eq(20)))
                    .thenReturn(contacts);
            when(contactRepository.countContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                  isNull(), isNull(), eq(queueIdStr), isNull(), isNull(),
                                                  isNull(), isNull()))
                    .thenReturn(1L);

            // when
            PagedResponse<ContactResponse> result = contactService.listContacts(
                    params, TENANT_ID, AGENT_ID, false);

            // then
            assertThat(result.content()).hasSize(1);
            verify(contactRepository).findContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                   isNull(), isNull(), eq(queueIdStr), isNull(), isNull(),
                                                   isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("BE-036: filtrowanie po durationMin przekazuje wartość do repozytorium")
        void listContacts_filterByDurationMin_passesValueToRepository() {
            // given
            Integer durationMin = 60;
            ContactFilterParams params = new ContactFilterParams(
                    null, null, null, null, null, null,
                    null, null, null, durationMin, null, 0, 20);
            List<Contact> contacts = List.of(buildContact(CONTACT_ID, "COMPLETED"));
            when(contactRepository.findContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                isNull(), isNull(), isNull(), isNull(), isNull(),
                                                eq(durationMin), isNull(), eq(0), eq(20)))
                    .thenReturn(contacts);
            when(contactRepository.countContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                  isNull(), isNull(), isNull(), isNull(), isNull(),
                                                  eq(durationMin), isNull()))
                    .thenReturn(1L);

            // when
            PagedResponse<ContactResponse> result = contactService.listContacts(
                    params, TENANT_ID, AGENT_ID, false);

            // then
            assertThat(result.content()).hasSize(1);
            verify(contactRepository).findContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                   isNull(), isNull(), isNull(), isNull(), isNull(),
                                                   eq(durationMin), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("BE-036: kombinacja filtrów queueId + campaignId + durationMin przekazywana łącznie (AND)")
        void listContacts_combinedFilters_passedToRepositoryTogether() {
            // given
            String queueIdStr    = QUEUE_ID.toString();
            String campaignIdStr = CAMPAIGN_ID.toString();
            Integer durationMin  = 30;
            ContactFilterParams params = new ContactFilterParams(
                    null, null, null, null, null, null,
                    queueIdStr, campaignIdStr, null, durationMin, null, 0, 20);
            when(contactRepository.findContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                isNull(), isNull(), eq(queueIdStr), eq(campaignIdStr), isNull(),
                                                eq(durationMin), isNull(), eq(0), eq(20)))
                    .thenReturn(List.of());
            when(contactRepository.countContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                  isNull(), isNull(), eq(queueIdStr), eq(campaignIdStr), isNull(),
                                                  eq(durationMin), isNull()))
                    .thenReturn(0L);

            // when
            PagedResponse<ContactResponse> result = contactService.listContacts(
                    params, TENANT_ID, AGENT_ID, false);

            // then – wszystkie trzy filtry przekazane jednocześnie do repozytorium
            assertThat(result.totalElements()).isEqualTo(0L);
            verify(contactRepository).findContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                   isNull(), isNull(), eq(queueIdStr), eq(campaignIdStr), isNull(),
                                                   eq(durationMin), isNull(), eq(0), eq(20));
            verify(contactRepository).countContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                    isNull(), isNull(), eq(queueIdStr), eq(campaignIdStr), isNull(),
                                                    eq(durationMin), isNull());
        }

        // =====================================================================
        // Regresja: NPE gdy żaden kontakt na stronie nie ma przypisanego agenta
        // (produkcyjny 500 – resolveAgentNamesForContacts zwracał Map.of(), a
        // Map.of().get(null) rzuca NullPointerException; HashMap/Collections.emptyMap
        // zwracają po prostu null)
        // =====================================================================

        @Test
        @DisplayName("BUGFIX: nie rzuca NPE gdy żaden kontakt na liście nie ma przypisanego agenta (np. QUEUED)")
        void listContacts_allContactsWithoutAgent_doesNotThrowAndAgentNameIsNull() {
            // given – kontakty w kolejce, jeszcze bez przypisania agenta
            ContactFilterParams params = basicParams(0, 20);
            Contact queuedContact = buildContact(CONTACT_ID, "QUEUED");
            queuedContact.setAgentId(null);
            List<Contact> contacts = List.of(queuedContact);
            when(contactRepository.findContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                isNull(), isNull(), isNull(), isNull(), isNull(),
                                                isNull(), isNull(), eq(0), eq(20)))
                    .thenReturn(contacts);
            when(contactRepository.countContacts(eq(TENANT_ID), isNull(), isNull(), isNull(), isNull(),
                                                  isNull(), isNull(), isNull(), isNull(), isNull(),
                                                  isNull(), isNull()))
                    .thenReturn(1L);

            // when – przed poprawką rzucało NullPointerException na agentNames.get(null)
            PagedResponse<ContactResponse> result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                    contactService.listContacts(params, TENANT_ID, AGENT_ID, false));

            // then
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).agentId()).isNull();
            assertThat(result.content().get(0).agentName()).isNull();
            verifyNoInteractions(userService);
        }
    }

    // =========================================================================
    // Aktualizacja kontaktu
    // =========================================================================

    @Nested
    @DisplayName("updateContact")
    class UpdateContactTests {

        @Test
        @DisplayName("SUPERVISOR może aktualizować dowolny kontakt")
        void updateContact_supervisorCanUpdateAnyContact() {
            // given
            Contact existing = buildContact(CONTACT_ID, "ACTIVE");
            existing.setAgentId(OTHER_AGENT); // przypisany inny agent
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(existing));
            when(contactRepository.patchIfUnchanged(any(Contact.class), any(ContactPatch.class))).thenReturn(1);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(existing));

            UpdateContactRequest request = new UpdateContactRequest(
                    null, "COMPLETED", null, Instant.now(), null, null);

            // when – isAgent=false = SUPERVISOR
            assertThatNoException().isThrownBy(() ->
                    contactService.updateContact(CONTACT_ID, request, TENANT_ID, AGENT_ID, false));

            verify(contactRepository).patchIfUnchanged(any(Contact.class), any(ContactPatch.class));
        }

        @Test
        @DisplayName("AGENT może aktualizować własny kontakt")
        void updateContact_agentCanUpdateOwnContact() {
            // given
            Contact existing = buildContact(CONTACT_ID, "ACTIVE");
            existing.setAgentId(AGENT_ID); // ten sam agent
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(existing));
            when(contactRepository.patchIfUnchanged(any(Contact.class), any(ContactPatch.class))).thenReturn(1);

            UpdateContactRequest request = new UpdateContactRequest(null, "ON_HOLD", null, null, null, null);

            // when
            assertThatNoException().isThrownBy(() ->
                    contactService.updateContact(CONTACT_ID, request, TENANT_ID, AGENT_ID, true));
        }

        @Test
        @DisplayName("AGENT rzuca InvalidOperationException przy próbie aktualizacji cudzego kontaktu")
        void updateContact_agentCannotUpdateOtherAgentContact() {
            // given
            Contact existing = buildContact(CONTACT_ID, "ACTIVE");
            existing.setAgentId(OTHER_AGENT); // przypisany INNY agent
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(existing));

            UpdateContactRequest request = new UpdateContactRequest(null, "COMPLETED", null, null, null, null);

            // when / then
            assertThatThrownBy(() ->
                    contactService.updateContact(CONTACT_ID, request, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining(CONTACT_ID.toString());
        }

        @Test
        @DisplayName("aktualizuje tylko podane pola – PATCH semantics")
        void updateContact_updatesOnlyProvidedFields() {
            // given
            Contact existing = buildContact(CONTACT_ID, "ACTIVE");
            existing.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(existing));
            when(contactRepository.patchIfUnchanged(any(Contact.class), any(ContactPatch.class))).thenReturn(1);

            UpdateContactRequest request = new UpdateContactRequest(
                    null, "ON_HOLD", null, null, null, null
            );

            // when
            contactService.updateContact(CONTACT_ID, request, TENANT_ID, AGENT_ID, true);

            // then – status zmieniony, agentId bez zmian
            // (the snapshot is synced with the written values afterwards, so only the patch is asserted)
            verify(contactRepository).patchIfUnchanged(any(Contact.class),
                    argThat(p -> "ON_HOLD".equals(p.status()) && p.agentId() == null && p.endedAt() == null));
        }

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy kontakt nie istnieje")
        void updateContact_throwsWhenNotFound() {
            // given
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());
            UpdateContactRequest request = new UpdateContactRequest(null, "ACTIVE", null, null, null, null);

            // when / then
            assertThatThrownBy(() ->
                    contactService.updateContact(CONTACT_ID, request, TENANT_ID, AGENT_ID, false))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // =========================================================================
    // Ustawianie disposition
    // =========================================================================

    @Nested
    @DisplayName("setDisposition")
    class SetDispositionTests {

        @Test
        @DisplayName("ustawia disposition na zakończonym kontakcie")
        void setDisposition_setsDispositionOnCompletedContact() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(contactRepository.setDispositionIfNotActive(any(), any(), any(), any(), any(), any())).thenReturn(1);

            DispositionRequest request = new DispositionRequest("SALE", null);

            // when
            contactService.setDisposition(CONTACT_ID, request, TENANT_ID, AGENT_ID, true);

            // then
            verify(contactRepository).setDispositionIfNotActive(eq(CONTACT_ID), eq(TENANT_ID), any(), eq(AGENT_ID),
                    eq("SALE"), isNull());
        }

        @Test
        @DisplayName("AGENT rzuca InvalidOperationException przy disposition na cudzym kontakcie")
        void setDisposition_agentCannotSetOnOtherAgentContact() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setAgentId(OTHER_AGENT);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            DispositionRequest request = new DispositionRequest("DECLINED", null);

            // when / then
            assertThatThrownBy(() ->
                    contactService.setDisposition(CONTACT_ID, request, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining(CONTACT_ID.toString());
        }

        @Test
        @DisplayName("rzuca InvalidOperationException gdy kontakt jest ACTIVE")
        void setDisposition_throwsWhenContactIsActive() {
            // given
            Contact contact = buildContact(CONTACT_ID, "ACTIVE");
            contact.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            DispositionRequest request = new DispositionRequest("SALE", null);

            // when / then
            assertThatThrownBy(() ->
                    contactService.setDisposition(CONTACT_ID, request, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("ACTIVE");
        }

        @Test
        @DisplayName("rzuca InvalidOperationException gdy kontakt jest QUEUED")
        void setDisposition_throwsWhenContactIsQueued() {
            // given
            Contact contact = buildContact(CONTACT_ID, "QUEUED");
            contact.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            DispositionRequest request = new DispositionRequest("NO_ANSWER", null);

            // when / then
            assertThatThrownBy(() ->
                    contactService.setDisposition(CONTACT_ID, request, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("QUEUED");
        }

        @Test
        @DisplayName("SUPERVISOR może ustawiać disposition na cudzym kontakcie")
        void setDisposition_supervisorCanSetOnAnyContact() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setAgentId(OTHER_AGENT);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(contactRepository.setDispositionIfNotActive(any(), any(), any(), any(), any(), any())).thenReturn(1);

            DispositionRequest request = new DispositionRequest("CALLBACK", null);

            // when – isAgent=false = SUPERVISOR
            assertThatNoException().isThrownBy(() ->
                    contactService.setDisposition(CONTACT_ID, request, TENANT_ID, AGENT_ID, false));
        }
    }

    // =========================================================================
    // BE-209: zachowanie przy 0 zaktualizowanych wierszy (zapis warunkowy przegrał wyścig)
    // =========================================================================

    @Nested
    @DisplayName("BE-209: zapisy warunkowe - 0 wierszy")
    class ConditionalWriteZeroRowsTests {

        @Test
        @DisplayName("updateContact: 0 wierszy, kontakt istnieje -> ConflictException (409), bez efektow ubocznych")
        void updateContact_zeroRowsExistingContact_isConflict() {
            Contact existing = buildContact(CONTACT_ID, "ASSIGNED");
            existing.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(existing));
            when(contactRepository.patchIfUnchanged(any(), any())).thenReturn(0);

            UpdateContactRequest request = new UpdateContactRequest(null, "COMPLETED", null, null, null, null);

            assertThatThrownBy(() -> contactService.updateContact(CONTACT_ID, request, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(ConflictException.class);
            verifyNoInteractions(contactEventService);
        }

        @Test
        @DisplayName("updateContact: 0 wierszy, kontakt zniknal -> EntityNotFoundException (404)")
        void updateContact_zeroRowsMissingContact_isNotFound() {
            Contact existing = buildContact(CONTACT_ID, "ACTIVE");
            existing.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(existing), Optional.empty());
            when(contactRepository.patchIfUnchanged(any(), any())).thenReturn(0);

            UpdateContactRequest request = new UpdateContactRequest(null, "ON_HOLD", null, null, null, null);

            assertThatThrownBy(() -> contactService.updateContact(CONTACT_ID, request, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("updateContact: PATCH nie niesie agent_id/status, gdy request ich nie zawiera")
        void updateContact_patchOmitsUntouchedColumns() {
            Contact existing = buildContact(CONTACT_ID, "ACTIVE");
            existing.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(existing));
            when(contactRepository.patchIfUnchanged(any(), any())).thenReturn(1);

            contactService.updateContact(CONTACT_ID,
                    new UpdateContactRequest(null, null, null, null, "+48123456789", null),
                    TENANT_ID, AGENT_ID, true);

            verify(contactRepository).patchIfUnchanged(any(), argThat(p ->
                    p.agentId() == null && p.status() == null && "+48123456789".equals(p.remoteAddress())));
        }

        @Test
        @DisplayName("setDisposition: 0 wierszy (kontakt zmieniony rownolegle) -> ConflictException, bez publikacji")
        void setDisposition_zeroRows_isConflict() {
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(contactRepository.setDispositionIfNotActive(any(), any(), any(), any(), any(), any())).thenReturn(0);

            assertThatThrownBy(() -> contactService.setDisposition(CONTACT_ID, new DispositionRequest("SALE", null),
                    TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(ConflictException.class);
            verifyNoInteractions(extensionPointPublisher);
        }

        @Test
        @DisplayName("abandonContact: ERROR/NOT_REACHED to stan koncowy -> idempotentnie, bez zapisu i bez zamykania zdarzen")
        void abandonContact_errorAndNotReached_areIdempotentNoWrite() {
            for (String terminal : List.of("ERROR", "NOT_REACHED")) {
                Contact contact = buildContact(CONTACT_ID, terminal);
                contact.setAgentId(AGENT_ID);
                when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

                assertThatNoException().isThrownBy(
                        () -> contactService.abandonContact(CONTACT_ID, TENANT_ID, AGENT_ID));
            }
            verify(contactRepository, never()).abandonIfOpen(any(), any(), any(), any(), any());
            verifyNoInteractions(contactEventService);
        }

        @Test
        @DisplayName("abandonContact: 0 wierszy, kontakt przeszedl w ERROR rownolegle -> idempotentnie (nie 409)")
        void abandonContact_zeroRowsNowError_isIdempotent() {
            Contact open = buildContact(CONTACT_ID, "ACTIVE");
            open.setAgentId(AGENT_ID);
            Contact error = buildContact(CONTACT_ID, "ERROR");
            error.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(open), Optional.of(error), Optional.of(error));
            when(contactRepository.abandonIfOpen(any(), any(), any(), any(), any())).thenReturn(0);

            assertThatNoException().isThrownBy(() -> contactService.abandonContact(CONTACT_ID, TENANT_ID, AGENT_ID));
            verifyNoInteractions(contactEventService);
        }

        @Test
        @DisplayName("abandonContact: 0 wierszy, kontakt juz w stanie koncowym -> idempotentnie, bez zamykania zdarzen")
        void abandonContact_zeroRowsNowTerminal_isIdempotent() {
            Contact open = buildContact(CONTACT_ID, "ACTIVE");
            open.setAgentId(AGENT_ID);
            Contact completed = buildContact(CONTACT_ID, "COMPLETED");
            completed.setAgentId(AGENT_ID);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(open), Optional.of(completed), Optional.of(completed));
            when(contactRepository.abandonIfOpen(any(), any(), any(), any(), any())).thenReturn(0);

            assertThatNoException().isThrownBy(() -> contactService.abandonContact(CONTACT_ID, TENANT_ID, AGENT_ID));
            verifyNoInteractions(contactEventService);
        }

        @Test
        @DisplayName("abandonContact: 0 wierszy, kontakt nadal otwarty (zmiana wlasciciela) -> ConflictException")
        void abandonContact_zeroRowsStillOpen_isConflict() {
            Contact open = buildContact(CONTACT_ID, "ACTIVE");
            open.setAgentId(AGENT_ID);
            Contact reassigned = buildContact(CONTACT_ID, "ACTIVE");
            reassigned.setAgentId(OTHER_AGENT);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(open), Optional.of(reassigned));
            when(contactRepository.abandonIfOpen(any(), any(), any(), any(), any())).thenReturn(0);

            assertThatThrownBy(() -> contactService.abandonContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                    .isInstanceOf(ConflictException.class);
            verifyNoInteractions(contactEventService);
        }

        @Test
        @DisplayName("terminateStaleQueuedContacts: kontakt juz nie QUEUED (0 wierszy) jest pominiety, reszta przetworzona")
        void terminateStale_skipsContactsNoLongerQueued() {
            Contact lost = buildContact(UUID.randomUUID(), "QUEUED");
            Contact won = buildContact(UUID.randomUUID(), "QUEUED");
            when(contactRepository.findStaleQueuedContacts(eq(TENANT_ID), any())).thenReturn(List.of(lost, won));
            when(contactRepository.abandonIfStillQueued(eq(lost.getContactId()), eq(TENANT_ID), any(), any(), any()))
                    .thenReturn(0);
            when(contactRepository.abandonIfStillQueued(eq(won.getContactId()), eq(TENANT_ID), any(), any(), any()))
                    .thenReturn(1);

            assertThatNoException().isThrownBy(() -> contactService.terminateStaleQueuedContacts(TENANT_ID));

            verify(contactRepository).abandonIfStillQueued(eq(lost.getContactId()), eq(TENANT_ID), any(), any(), any());
            verify(contactRepository).abandonIfStillQueued(eq(won.getContactId()), eq(TENANT_ID), any(), any(), any());
        }
    }

    // =========================================================================
    // Historia klienta
    // =========================================================================

    @Nested
    @DisplayName("getCustomerHistory")
    class CustomerHistoryTests {

        @Test
        @DisplayName("zwraca paginowaną historię klienta")
        void getCustomerHistory_returnsPaginatedHistory() {
            // given
            List<Contact> contacts = List.of(
                    buildContact(CONTACT_ID, "COMPLETED"),
                    buildContact(UUID.randomUUID(), "ABANDONED")
            );
            when(contactRepository.findByCustomerId(CUSTOMER_ID, TENANT_ID, 0, 10)).thenReturn(contacts);
            when(contactRepository.countByCustomerId(CUSTOMER_ID, TENANT_ID)).thenReturn(12L);

            // when
            PagedResponse<ContactResponse> result = contactService.getCustomerHistory(
                    CUSTOMER_ID, TENANT_ID, 0, 10);

            // then
            assertThat(result.content()).hasSize(2);
            assertThat(result.totalElements()).isEqualTo(12L);
            assertThat(result.totalPages()).isEqualTo(2);
            assertThat(result.first()).isTrue();
        }

        @Test
        @DisplayName("zwraca pustą listę gdy klient nie ma kontaktów")
        void getCustomerHistory_returnsEmptyWhenNoContacts() {
            // given
            when(contactRepository.findByCustomerId(CUSTOMER_ID, TENANT_ID, 0, 20)).thenReturn(List.of());
            when(contactRepository.countByCustomerId(CUSTOMER_ID, TENANT_ID)).thenReturn(0L);

            // when
            PagedResponse<ContactResponse> result = contactService.getCustomerHistory(
                    CUSTOMER_ID, TENANT_ID, 0, 20);

            // then
            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isEqualTo(0L);
            assertThat(result.totalPages()).isEqualTo(0);
            assertThat(result.first()).isTrue();
            assertThat(result.last()).isTrue();
        }

        @Test
        @DisplayName("ogranicza rozmiar strony do max 100")
        void getCustomerHistory_capsPageSizeAtHundred() {
            // given
            when(contactRepository.findByCustomerId(CUSTOMER_ID, TENANT_ID, 0, 100)).thenReturn(List.of());
            when(contactRepository.countByCustomerId(CUSTOMER_ID, TENANT_ID)).thenReturn(0L);

            // when
            contactService.getCustomerHistory(CUSTOMER_ID, TENANT_ID, 0, 999);

            // then
            verify(contactRepository).findByCustomerId(CUSTOMER_ID, TENANT_ID, 0, 100);
        }

        @Test
        @DisplayName("BUGFIX: nie rzuca NPE gdy żaden kontakt w historii klienta nie ma przypisanego agenta")
        void getCustomerHistory_allContactsWithoutAgent_doesNotThrowAndAgentNameIsNull() {
            // given – kontakty zakończone bez przypisania agenta (np. porzucone w kolejce/IVR)
            Contact abandoned = buildContact(CONTACT_ID, "ABANDONED");
            abandoned.setAgentId(null);
            Contact notReached = buildContact(UUID.randomUUID(), "NOT_REACHED");
            notReached.setAgentId(null);
            List<Contact> contacts = List.of(abandoned, notReached);
            when(contactRepository.findByCustomerId(CUSTOMER_ID, TENANT_ID, 0, 10)).thenReturn(contacts);
            when(contactRepository.countByCustomerId(CUSTOMER_ID, TENANT_ID)).thenReturn(2L);

            // when – przed poprawką rzucało NullPointerException na agentNames.get(null)
            PagedResponse<ContactResponse> result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                    contactService.getCustomerHistory(CUSTOMER_ID, TENANT_ID, 0, 10));

            // then
            assertThat(result.content()).hasSize(2);
            assertThat(result.content()).allSatisfy(c -> {
                assertThat(c.agentId()).isNull();
                assertThat(c.agentName()).isNull();
            });
            verifyNoInteractions(userService);
        }
    }

    // =========================================================================
    // Nagranie kontaktu – presigned URL (BE-037)
    // =========================================================================

    @Nested
    @DisplayName("getRecordingUrl")
    class GetRecordingUrlTests {

        private static final String S3_KEY =
                "11111111-1111-1111-1111-111111111111/2026/04/22222222-2222-2222-2222-222222222222.mp3";
        private static final String PRESIGNED_URL =
                "https://minio.example.com/" + S3_KEY + "?X-Amz-Expires=900&X-Amz-Signature=abc";

        @Test
        @DisplayName("zwraca ContactRecordingUrlResponse gdy kontakt ma nagranie")
        void getRecordingUrl_returnsResponseWhenRecordingExists() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setAgentId(AGENT_ID);
            contact.setRecordingUrl(S3_KEY);
            contact.setDurationSeconds(185);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(recordingService.generatePresignedUrlForKey(eq(S3_KEY), any(Duration.class)))
                    .thenReturn(PRESIGNED_URL);

            // when – SUPERVISOR (isAgent=false)
            ContactRecordingUrlResponse response =
                    contactService.getRecordingUrl(CONTACT_ID, TENANT_ID, AGENT_ID, false);

            // then
            assertThat(response.presignedUrl()).isEqualTo(PRESIGNED_URL);
            assertThat(response.fileName()).isEqualTo(S3_KEY);
            assertThat(response.durationSeconds()).isEqualTo(185);
            assertThat(response.expiresAt()).isAfter(Instant.now());
            assertThat(response.expiresAt()).isBefore(Instant.now().plusSeconds(16 * 60));

            verify(recordingService).generatePresignedUrlForKey(eq(S3_KEY), any(Duration.class));
        }

        @Test
        @DisplayName("AGENT może pobrać URL nagrania własnego kontaktu")
        void getRecordingUrl_agentCanGetOwnContactRecording() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setAgentId(AGENT_ID); // ten sam agent
            contact.setRecordingUrl(S3_KEY);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(recordingService.generatePresignedUrlForKey(eq(S3_KEY), any(Duration.class)))
                    .thenReturn(PRESIGNED_URL);

            // when
            ContactRecordingUrlResponse response =
                    contactService.getRecordingUrl(CONTACT_ID, TENANT_ID, AGENT_ID, true);

            // then
            assertThat(response.presignedUrl()).isEqualTo(PRESIGNED_URL);
        }

        @Test
        @DisplayName("AGENT rzuca InvalidOperationException przy próbie pobrania nagrania cudzego kontaktu")
        void getRecordingUrl_agentCannotGetOtherAgentContactRecording() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setAgentId(OTHER_AGENT); // inny agent
            contact.setRecordingUrl(S3_KEY);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            // when / then
            assertThatThrownBy(() ->
                    contactService.getRecordingUrl(CONTACT_ID, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining(CONTACT_ID.toString());

            verifyNoInteractions(recordingService);
        }

        @Test
        @DisplayName("rzuca ResponseStatusException 404 gdy kontakt nie ma nagrania")
        void getRecordingUrl_throwsNotFoundWhenNoRecording() {
            // given – kontakt bez nagrania (recordingUrl = null)
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setAgentId(AGENT_ID);
            contact.setRecordingUrl(null);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            // when / then
            assertThatThrownBy(() ->
                    contactService.getRecordingUrl(CONTACT_ID, TENANT_ID, AGENT_ID, false))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Brak nagrania");

            verifyNoInteractions(recordingService);
        }

        @Test
        @DisplayName("rzuca ResponseStatusException 404 gdy recordingUrl jest pustym stringiem")
        void getRecordingUrl_throwsNotFoundWhenRecordingUrlIsBlank() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setRecordingUrl("   ");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            // when / then
            assertThatThrownBy(() ->
                    contactService.getRecordingUrl(CONTACT_ID, TENANT_ID, AGENT_ID, false))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Brak nagrania");

            verifyNoInteractions(recordingService);
        }

        @Test
        @DisplayName("rzuca ResponseStatusException 503 gdy MinIO/S3 jest niedostępny")
        void getRecordingUrl_throwsServiceUnavailableWhenS3Fails() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setRecordingUrl(S3_KEY);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(recordingService.generatePresignedUrlForKey(eq(S3_KEY), any(Duration.class)))
                    .thenThrow(new RecordingException("S3 connection failed",
                            new RuntimeException("Connection refused")));

            // when / then
            assertThatThrownBy(() ->
                    contactService.getRecordingUrl(CONTACT_ID, TENANT_ID, AGENT_ID, false))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("niedostępna");
        }

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy kontakt nie istnieje")
        void getRecordingUrl_throwsWhenContactNotFound() {
            // given
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    contactService.getRecordingUrl(CONTACT_ID, TENANT_ID, AGENT_ID, false))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(CONTACT_ID.toString());

            verifyNoInteractions(recordingService);
        }

        @Test
        @DisplayName("expiresAt jest ustawiony na ~15 minut od teraz")
        void getRecordingUrl_setsExpiresAtToFifteenMinutes() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED");
            contact.setRecordingUrl(S3_KEY);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(recordingService.generatePresignedUrlForKey(eq(S3_KEY), any(Duration.class)))
                    .thenReturn(PRESIGNED_URL);

            Instant before = Instant.now().plusSeconds(14 * 60);
            Instant after  = Instant.now().plusSeconds(16 * 60);

            // when
            ContactRecordingUrlResponse response =
                    contactService.getRecordingUrl(CONTACT_ID, TENANT_ID, AGENT_ID, false);

            // then
            assertThat(response.expiresAt()).isBetween(before, after);
        }
    }

    // =========================================================================
    // Podgląd całej rozmowy e-mail + EML per wiadomość
    // =========================================================================

    @Nested
    @DisplayName("getEmailPreview / getEmailMessageEmlUrl (cała rozmowa, EML per wiadomość)")
    class GetEmailPreviewTests {

        private static final UUID MSG_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
        private static final UUID MSG_2 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
        private static final UUID MSG_3 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");
        private static final Instant AT_1 = Instant.parse("2026-04-17T10:30:00Z");
        private static final Instant AT_2 = Instant.parse("2026-04-17T11:30:00Z");
        private static final Instant AT_3 = Instant.parse("2026-04-17T12:30:00Z");
        private static final String PRESIGNED_URL = "https://minio.example.com/signed-eml";
        private static final String LEGACY_EML = TENANT_ID + "/2026/04/" + CONTACT_ID + ".eml";
        private static final String MSG_EML_KEY = TENANT_ID + "/2026/04/" + CONTACT_ID + "/" + MSG_2 + ".eml";

        private void givenThread(Contact contact, List<EmailMessage> messages, long total, boolean truncated) {
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageService.findThreadByContactId(CONTACT_ID, TENANT_ID, 100))
                    .thenReturn(new EmailThread(messages, total, truncated));
        }

        @Test
        @DisplayName("zwraca całą rozmowę chronologicznie z messageAt, direction i emlAvailable per wiadomość")
        void returnsWholeConversation() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            contact.setRecordingUrl(LEGACY_EML);
            EmailMessage first = message(MSG_1, AT_1, "INBOUND", null);
            EmailMessage reply = message(MSG_2, AT_2, "OUTBOUND", MSG_EML_KEY);
            EmailMessage followUp = message(MSG_3, AT_3, "INBOUND", null);
            givenThread(contact, List.of(first, reply, followUp), 3, false);

            EmailPreviewResponse response = contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true);

            assertThat(response.totalMessages()).isEqualTo(3);
            assertThat(response.truncated()).isFalse();
            assertThat(response.messages()).extracting(EmailPreviewResponse.MessageDto::messageId)
                    .containsExactly(MSG_1, MSG_2, MSG_3);
            assertThat(response.messages()).extracting(EmailPreviewResponse.MessageDto::messageAt)
                    .containsExactly(AT_1, AT_2, AT_3);
            assertThat(response.messages()).extracting(EmailPreviewResponse.MessageDto::direction)
                    .containsExactly("INBOUND", "OUTBOUND", "INBOUND");
            EmailPreviewResponse.MessageDto dto = response.messages().get(0);
            assertThat(dto.from()).isEqualTo("klient@example.com");
            assertThat(dto.to()).isEqualTo("support@firma.pl");
            assertThat(dto.subject()).isEqualTo("Prośba o pomoc");
            assertThat(dto.bodyHtml()).isEqualTo("<p>Treść HTML</p>");
            assertThat(dto.bodyText()).isEqualTo("Treść tekstowa");
            assertThat(dto.receivedAt()).isEqualTo(AT_1);
        }

        @Test
        @DisplayName("legacy fallback do recording_url (.eml) dotyczy WYŁĄCZNIE najwcześniejszej wiadomości bez klucza")
        void legacyFallbackOnlyOnEarliest() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            contact.setRecordingUrl(LEGACY_EML);
            givenThread(contact, List.of(
                    message(MSG_1, AT_1, "INBOUND", null),       // najwcześniejsza, legacy -> true
                    message(MSG_2, AT_2, "INBOUND", null),       // późniejsza bez klucza -> false
                    message(MSG_3, AT_3, "INBOUND", MSG_EML_KEY) // własny klucz -> true
            ), 3, false);

            EmailPreviewResponse response = contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true);

            assertThat(response.messages()).extracting(EmailPreviewResponse.MessageDto::emlAvailable)
                    .containsExactly(true, false, true);
        }

        @Test
        @DisplayName("recording_url inne niż .eml (np. .mp3) nie daje fallbacku nawet dla najwcześniejszej")
        void noFallbackWhenRecordingUrlIsNotEml() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            contact.setRecordingUrl(TENANT_ID + "/2026/04/" + CONTACT_ID + ".mp3");
            givenThread(contact, List.of(message(MSG_1, AT_1, "INBOUND", null)), 1, false);

            assertThat(contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true)
                    .messages().get(0).emlAvailable()).isFalse();
        }

        @Test
        @DisplayName("lista ucięta (truncated): przekazuje totalMessages/truncated i NIE stosuje fallbacku (najwcześniejszej nie ma na liście)")
        void truncatedThreadHasNoLegacyFallback() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            contact.setRecordingUrl(LEGACY_EML);
            givenThread(contact, List.of(
                    message(MSG_2, AT_2, "INBOUND", null),
                    message(MSG_3, AT_3, "INBOUND", null)), 150, true);

            EmailPreviewResponse response = contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true);

            assertThat(response.truncated()).isTrue();
            assertThat(response.totalMessages()).isEqualTo(150);
            assertThat(response.messages()).extracting(EmailPreviewResponse.MessageDto::emlAvailable)
                    .containsExactly(false, false);
        }

        @Test
        @DisplayName("OUTBOUND: brak wierszy po contact_id -> fallback do channelMetadata.emailMessageId, lista jednoelementowa")
        void outboundFallbackReturnsSingleMessage() {
            Contact contact = buildEmailContact(CONTACT_ID, "COMPLETED");
            contact.setDirection("OUTBOUND");
            contact.setRecordingUrl(LEGACY_EML);
            contact.getChannelMetadata().put("emailMessageId", MSG_1.toString());
            givenThread(contact, List.of(), 0, false);
            when(emailMessageService.findById(MSG_1)).thenReturn(Optional.of(message(MSG_1, AT_1, "OUTBOUND", null)));

            EmailPreviewResponse response = contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true);

            assertThat(response.messages()).hasSize(1);
            assertThat(response.totalMessages()).isEqualTo(1);
            assertThat(response.truncated()).isFalse();
            assertThat(response.messages().get(0).messageId()).isEqualTo(MSG_1);
            assertThat(response.messages().get(0).emlAvailable()).as("jedyna = najwcześniejsza -> legacy fallback").isTrue();
        }

        @Test
        @DisplayName("fallback OUTBOUND ignoruje wiadomość z innego tenanta (404)")
        void outboundFallbackRejectsForeignTenantMessage() {
            Contact contact = buildEmailContact(CONTACT_ID, "COMPLETED");
            contact.getChannelMetadata().put("emailMessageId", MSG_1.toString());
            givenThread(contact, List.of(), 0, false);
            EmailMessage foreign = message(MSG_1, AT_1, "OUTBOUND", null);
            foreign.setTenantId(UUID.randomUUID());
            when(emailMessageService.findById(MSG_1)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("rzuca 400 gdy kontakt nie jest kanałem EMAIL")
        void throwsBadRequestWhenNotEmailChannel() {
            Contact contact = buildContact(CONTACT_ID, "COMPLETED"); // PHONE
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            assertThatThrownBy(() -> contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getReason()).contains("EMAIL");
                    });
            verifyNoInteractions(emailMessageService);
        }

        @Test
        @DisplayName("rzuca 404 gdy kontakt EMAIL nie ma wiadomości ani fallbacku")
        void throwsNotFoundWhenNoEmailMessages() {
            givenThread(buildEmailContact(CONTACT_ID, "COMPLETED"), List.of(), 0, false);

            assertThatThrownBy(() -> contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.getReason()).contains(CONTACT_ID.toString());
                    });
        }

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy kontakt nie istnieje")
        void throwsWhenContactNotFound() {
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(CONTACT_ID.toString());
            verifyNoInteractions(emailMessageService);
        }

        @Test
        @DisplayName("AGENT cudzego kontaktu -> InvalidOperationException (409), bez odczytu wiadomości")
        void otherAgentIsRejected() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            contact.setAgentId(OTHER_AGENT);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            assertThatThrownBy(() -> contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(InvalidOperationException.class);
            verifyNoInteractions(emailMessageService);
        }

        @Test
        @DisplayName("kontakt bez agenta (w kolejce) jest dostępny dla AGENT; SUPERVISOR/ADMIN widzi cudzy kontakt")
        void unassignedContactAndPrivilegedRolesAreAllowed() {
            Contact queued = buildEmailContact(CONTACT_ID, "QUEUED");
            queued.setAgentId(null);
            givenThread(queued, List.of(message(MSG_1, AT_1, "INBOUND", null)), 1, false);
            assertThat(contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, true).messages()).hasSize(1);

            Contact foreign = buildEmailContact(CONTACT_ID, "ACTIVE");
            foreign.setAgentId(OTHER_AGENT);
            givenThread(foreign, List.of(message(MSG_1, AT_1, "INBOUND", null)), 1, false);
            assertThat(contactService.getEmailPreview(CONTACT_ID, TENANT_ID, AGENT_ID, false).messages()).hasSize(1);
        }

        // ---- GET /contacts/{id}/email-messages/{messageId}/eml --------------------------------

        @Test
        @DisplayName("EML: wiadomość z własnym kluczem -> presigned URL, message/rfc822, durationSeconds null")
        void emlUrlHappyPath() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageService.findByIdAndContactId(MSG_2, CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(message(MSG_2, AT_2, "INBOUND", MSG_EML_KEY)));
            when(recordingService.generatePresignedUrlForKey(eq(MSG_EML_KEY), any(Duration.class)))
                    .thenReturn(PRESIGNED_URL);

            ContactRecordingUrlResponse response =
                    contactService.getEmailMessageEmlUrl(CONTACT_ID, MSG_2, TENANT_ID, AGENT_ID, true);

            assertThat(response.presignedUrl()).isEqualTo(PRESIGNED_URL);
            assertThat(response.fileName()).isEqualTo(MSG_EML_KEY);
            assertThat(response.contentType()).isEqualTo("message/rfc822");
            assertThat(response.durationSeconds()).isNull();
            assertThat(response.expiresAt()).isBetween(
                    Instant.now().plusSeconds(14 * 60), Instant.now().plusSeconds(16 * 60));
            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(recordingService).generatePresignedUrlForKey(eq(MSG_EML_KEY), ttl.capture());
            assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("EML: najwcześniejsza wiadomość bez klucza używa recording_url (legacy); późniejsza bez klucza -> 404")
        void emlUrlLegacyFallbackOnlyForEarliest() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            contact.setRecordingUrl(LEGACY_EML);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageService.findEarliestMessageId(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(MSG_1));
            when(emailMessageService.findByIdAndContactId(MSG_1, CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(message(MSG_1, AT_1, "INBOUND", null)));
            when(emailMessageService.findByIdAndContactId(MSG_3, CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(message(MSG_3, AT_3, "INBOUND", null)));
            when(recordingService.generatePresignedUrlForKey(eq(LEGACY_EML), any(Duration.class)))
                    .thenReturn(PRESIGNED_URL);

            assertThat(contactService.getEmailMessageEmlUrl(CONTACT_ID, MSG_1, TENANT_ID, AGENT_ID, true).fileName())
                    .isEqualTo(LEGACY_EML);

            assertThatThrownBy(() -> contactService.getEmailMessageEmlUrl(CONTACT_ID, MSG_3, TENANT_ID, AGENT_ID, true))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("EML: wiadomość nienależąca do kontaktu/tenanta -> 404 (bez presign)")
        void emlUrlForeignMessageIs404() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageService.findByIdAndContactId(MSG_3, CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());
            when(emailMessageService.findEarliestMessageId(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(MSG_1));

            assertThatThrownBy(() -> contactService.getEmailMessageEmlUrl(CONTACT_ID, MSG_3, TENANT_ID, AGENT_ID, true))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            verifyNoInteractions(recordingService);
        }

        @Test
        @DisplayName("EML: klucz spoza prefiksu tenanta -> 404 (obrona w głąb), bez presign")
        void emlUrlKeyOutsideTenantPrefixIs404() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageService.findByIdAndContactId(MSG_2, CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(message(MSG_2, AT_2, "INBOUND", UUID.randomUUID() + "/2026/04/x.eml")));

            assertThatThrownBy(() -> contactService.getEmailMessageEmlUrl(CONTACT_ID, MSG_2, TENANT_ID, AGENT_ID, true))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            verifyNoInteractions(recordingService);
        }

        @Test
        @DisplayName("EML: cudzy AGENT -> InvalidOperationException (409); kanał inny niż EMAIL -> 400")
        void emlUrlOwnershipAndChannel() {
            Contact foreign = buildEmailContact(CONTACT_ID, "ACTIVE");
            foreign.setAgentId(OTHER_AGENT);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(foreign));
            assertThatThrownBy(() -> contactService.getEmailMessageEmlUrl(CONTACT_ID, MSG_2, TENANT_ID, AGENT_ID, true))
                    .isInstanceOf(InvalidOperationException.class);

            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildContact(CONTACT_ID, "COMPLETED")));
            assertThatThrownBy(() -> contactService.getEmailMessageEmlUrl(CONTACT_ID, MSG_2, TENANT_ID, AGENT_ID, true))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
            verifyNoInteractions(emailMessageService);
        }

        @Test
        @DisplayName("EML: awaria S3/presign -> 503")
        void emlUrlPresignFailureIs503() {
            Contact contact = buildEmailContact(CONTACT_ID, "ACTIVE");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageService.findByIdAndContactId(MSG_2, CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(message(MSG_2, AT_2, "INBOUND", MSG_EML_KEY)));
            when(recordingService.generatePresignedUrlForKey(any(), any(Duration.class)))
                    .thenThrow(new RecordingException("s3 down", new RuntimeException()));

            assertThatThrownBy(() -> contactService.getEmailMessageEmlUrl(CONTACT_ID, MSG_2, TENANT_ID, AGENT_ID, true))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }

        @Test
        @DisplayName("EML: OUTBOUND bez wierszy po contact_id -> wiadomość z channelMetadata (z legacy fallbackiem)")
        void emlUrlOutboundFallback() {
            Contact contact = buildEmailContact(CONTACT_ID, "COMPLETED");
            contact.setRecordingUrl(LEGACY_EML);
            contact.getChannelMetadata().put("emailMessageId", MSG_1.toString());
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageService.findByIdAndContactId(MSG_1, CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());
            when(emailMessageService.findEarliestMessageId(CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());
            when(emailMessageService.findById(MSG_1)).thenReturn(Optional.of(message(MSG_1, AT_1, "OUTBOUND", null)));
            when(recordingService.generatePresignedUrlForKey(eq(LEGACY_EML), any(Duration.class)))
                    .thenReturn(PRESIGNED_URL);

            assertThat(contactService.getEmailMessageEmlUrl(CONTACT_ID, MSG_1, TENANT_ID, AGENT_ID, true).fileName())
                    .isEqualTo(LEGACY_EML);
        }

        private EmailMessage message(UUID id, Instant at, String direction, String emlKey) {
            return EmailMessage.builder()
                    .id(id)
                    .messageAt(at)
                    .tenantId(TENANT_ID)
                    .contactId(CONTACT_ID)
                    .direction(direction)
                    .fromAddress("klient@example.com")
                    .toAddress("support@firma.pl")
                    .subject("Prośba o pomoc")
                    .bodyHtml("<p>Treść HTML</p>")
                    .bodyText("Treść tekstowa")
                    .receivedAt(at)
                    .createdAt(at)
                    .emlS3Key(emlKey)
                    .build();
        }

        private Contact buildEmailContact(UUID contactId, String status) {
            Instant now = Instant.now();
            return Contact.builder()
                    .contactId(contactId)
                    .tenantId(TENANT_ID)
                    .customerId(CUSTOMER_ID)
                    .agentId(AGENT_ID)
                    .channel("EMAIL")
                    .direction("INBOUND")
                    .status(status)
                    .remoteAddress("klient@example.com")
                    .queuedAt(now)
                    .startedAt(now)
                    .channelMetadata(new HashMap<>())
                    .createdAt(now)
                    .build();
        }
    }

    // =========================================================================
    // BE-157: findActiveContactsForAgent / resolveCustomerDisplayName
    // =========================================================================

    @Nested
    @DisplayName("findActiveContactsForAgent (BE-157)")
    class FindActiveContactsForAgent {

        private final UUID queueId = UUID.randomUUID();

        private Contact contact(String status, UUID customerId, UUID qId) {
            Contact c = buildContact(UUID.randomUUID(), status);
            c.setCustomerId(customerId);
            c.setQueueId(qId);
            return c;
        }

        @Test
        @DisplayName("mapuje status na phase: ASSIGNED->RINGING, ACTIVE/ON_HOLD->IN_PROGRESS, COMPLETED->WRAP_UP")
        void mapsStatusToPhase() {
            Contact assigned = contact("ASSIGNED", null, null);
            Contact active = contact("ACTIVE", null, null);
            Contact onHold = contact("ON_HOLD", null, null);
            Contact completed = contact("COMPLETED", null, null);
            Instant ended = Instant.parse("2026-10-30T10:00:00Z");
            completed.setEndedAt(ended);
            active.setEndedAt(ended); // endedAt set on non-ACW must NOT leak into wrapUpStartedAt
            when(contactRepository.findActiveContactsForAgent(eq(AGENT_ID), eq(TENANT_ID), any(), eq(20)))
                    .thenReturn(List.of(assigned, active, onHold, completed));

            List<ActiveContactResponse> result = contactService.findActiveContactsForAgent(AGENT_ID, TENANT_ID);

            assertThat(result).extracting(r -> r.phase().name())
                    .containsExactly("RINGING", "IN_PROGRESS", "IN_PROGRESS", "WRAP_UP");
            assertThat(result).extracting(ActiveContactResponse::status)
                    .containsExactly("ASSIGNED", "ACTIVE", "ON_HOLD", "COMPLETED");
            assertThat(result).extracting(ActiveContactResponse::wrapUpStartedAt)
                    .containsExactly(null, null, null, ended);
        }

        @Test
        @DisplayName("okno ACW: wrapUpSince = now - 12h domyslnie, a konfiguracja je zmienia")
        void wrapUpWindowIsConfigurable() {
            when(contactRepository.findActiveContactsForAgent(any(), any(), any(), anyInt())).thenReturn(List.of());
            ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);

            Instant before = Instant.now();
            contactService.findActiveContactsForAgent(AGENT_ID, TENANT_ID);
            verify(contactRepository).findActiveContactsForAgent(eq(AGENT_ID), eq(TENANT_ID), since.capture(), eq(20));
            assertThat(since.getValue()).isBetween(
                    before.minus(Duration.ofHours(12)).minusSeconds(1), Instant.now().minus(Duration.ofHours(12)));

            contactService.setActiveContactsWrapUpWindow("30m");
            contactService.findActiveContactsForAgent(AGENT_ID, TENANT_ID);
            verify(contactRepository, times(2))
                    .findActiveContactsForAgent(eq(AGENT_ID), eq(TENANT_ID), since.capture(), eq(20));
            assertThat(since.getValue()).isAfter(Instant.now().minus(Duration.ofMinutes(31)));
        }

        @Test
        @DisplayName("fail-fast: okno ACW niepoprawne lub <= 0 jest odrzucane")
        void invalidWrapUpWindowRejected() {
            assertThatThrownBy(() -> contactService.setActiveContactsWrapUpWindow("abc"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> contactService.setActiveContactsWrapUpWindow("0s"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> contactService.setActiveContactsWrapUpWindow("-5m"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("puste wyniki: [] bez zapytan o kolejki i klientow")
        void emptyResult_noLookups() {
            when(contactRepository.findActiveContactsForAgent(any(), any(), any(), anyInt())).thenReturn(List.of());

            assertThat(contactService.findActiveContactsForAgent(AGENT_ID, TENANT_ID)).isEmpty();

            verifyNoInteractions(queueService, customerService);
        }

        @Test
        @DisplayName("nazwy zbiorczo: po jednym wywolaniu na typ niezaleznie od liczby kontaktow")
        void bulkLookup_oneCallPerType() {
            UUID customerId = UUID.randomUUID();
            List<Contact> contacts = List.of(
                    contact("ASSIGNED", customerId, queueId),
                    contact("ACTIVE", customerId, queueId),
                    contact("ON_HOLD", UUID.randomUUID(), queueId));
            when(contactRepository.findActiveContactsForAgent(any(), any(), any(), anyInt())).thenReturn(contacts);
            when(queueService.findQueueNamesByIds(eq(TENANT_ID), any())).thenReturn(Map.of(queueId, "Sales PL"));
            when(customerService.findDisplayNamesByIds(eq(TENANT_ID), any()))
                    .thenReturn(Map.of(customerId, "Jan Kowalski"));

            List<ActiveContactResponse> result = contactService.findActiveContactsForAgent(AGENT_ID, TENANT_ID);

            verify(queueService, times(1)).findQueueNamesByIds(eq(TENANT_ID), any());
            verify(customerService, times(1)).findDisplayNamesByIds(eq(TENANT_ID), any());
            verify(queueService, never()).findQueueEntity(any(), any());
            assertThat(result).extracting(ActiveContactResponse::queueName).containsOnly("Sales PL");
            assertThat(result).extracting(ActiveContactResponse::customerName)
                    .containsExactly("Jan Kowalski", "Jan Kowalski", "+48501234567"); // unknown -> identifier
        }

        @Test
        @DisplayName("blad rozwiazywania nazw nie psuje odpowiedzi: queueName='', customerName=identyfikator")
        void lookupFailure_fallsBack() {
            when(contactRepository.findActiveContactsForAgent(any(), any(), any(), anyInt()))
                    .thenReturn(List.of(contact("ACTIVE", CUSTOMER_ID, queueId)));
            when(queueService.findQueueNamesByIds(any(), any())).thenThrow(new RuntimeException("db"));
            when(customerService.findDisplayNamesByIds(any(), any())).thenThrow(new RuntimeException("db"));

            List<ActiveContactResponse> result = contactService.findActiveContactsForAgent(AGENT_ID, TENANT_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).queueName()).isEmpty();
            assertThat(result.get(0).customerName()).isEqualTo("+48501234567");
            assertThat(result.get(0).customerIdentifier()).isEqualTo("+48501234567");
        }

        @Test
        @DisplayName("subject z channel_metadata (EMAIL), kanal SOCIAL_* zwiniety do SOCIAL, pola identyfikatorow")
        void subjectChannelAndIds() {
            Contact email = contact("ACTIVE", null, queueId);
            email.setChannel("EMAIL");
            email.setChannelMetadata(new HashMap<>(Map.of("subject", "Reklamacja")));
            Contact social = contact("ASSIGNED", null, null);
            social.setChannel("SOCIAL_FACEBOOK");
            when(contactRepository.findActiveContactsForAgent(any(), any(), any(), anyInt()))
                    .thenReturn(List.of(email, social));

            List<ActiveContactResponse> result = contactService.findActiveContactsForAgent(AGENT_ID, TENANT_ID);

            assertThat(result.get(0).subject()).isEqualTo("Reklamacja");
            assertThat(result.get(0).channel()).isEqualTo("EMAIL");
            assertThat(result.get(0).queueId()).isEqualTo(queueId.toString());
            assertThat(result.get(1).subject()).isNull();
            assertThat(result.get(1).channel()).isEqualTo("SOCIAL");
            assertThat(result.get(1).queueId()).isNull();
            assertThat(result.get(1).queueName()).isEmpty();
        }

        @Test
        @DisplayName("resolveCustomerDisplayName: prawdziwa nazwa, a przy braku/pustej nazwie fallback na remoteAddress")
        void resolveCustomerDisplayName_fallback() {
            UUID known = UUID.randomUUID();
            when(customerService.findDisplayNamesByIds(eq(TENANT_ID), any())).thenReturn(Map.of(known, "Anna Nowak"));

            assertThat(contactService.resolveCustomerDisplayName(contact("ASSIGNED", known, null), TENANT_ID))
                    .isEqualTo("Anna Nowak");
            assertThat(contactService.resolveCustomerDisplayName(contact("ASSIGNED", UUID.randomUUID(), null), TENANT_ID))
                    .isEqualTo("+48501234567");
            assertThat(contactService.resolveCustomerDisplayName(contact("ASSIGNED", null, null), TENANT_ID))
                    .isEqualTo("+48501234567");
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Contact buildContact(UUID contactId, String status) {
        Instant now = Instant.now();
        return Contact.builder()
                .contactId(contactId)
                .tenantId(TENANT_ID)
                .customerId(CUSTOMER_ID)
                .agentId(AGENT_ID)
                .channel("PHONE")
                .direction("INBOUND")
                .status(status)
                .remoteAddress("+48501234567")
                .queuedAt(now)
                .startedAt(now)
                .channelMetadata(new HashMap<>())
                .createdAt(now)
                .build();
    }
}
