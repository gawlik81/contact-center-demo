package com.contactcenter.domain;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.contact.dto.ContactFilterParams;
import com.contactcenter.api.contact.dto.ContactRecordingUrlResponse;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.CreateContactRequest;
import com.contactcenter.api.contact.dto.DispositionRequest;
import com.contactcenter.api.contact.dto.UpdateContactRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.EmailMessageRepository;
import com.contactcenter.domain.service.ContactService;
import com.contactcenter.domain.service.RecordingService;
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
    @Mock private EmailMessageRepository emailMessageRepository;

    @InjectMocks
    private ContactService contactService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");
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
            when(contactRepository.update(any(Contact.class))).thenReturn(1);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(existing));

            UpdateContactRequest request = new UpdateContactRequest(
                    null, "COMPLETED", null, Instant.now(), null, null);

            // when – isAgent=false = SUPERVISOR
            assertThatNoException().isThrownBy(() ->
                    contactService.updateContact(CONTACT_ID, request, TENANT_ID, AGENT_ID, false));

            verify(contactRepository).update(any(Contact.class));
        }

        @Test
        @DisplayName("AGENT może aktualizować własny kontakt")
        void updateContact_agentCanUpdateOwnContact() {
            // given
            Contact existing = buildContact(CONTACT_ID, "ACTIVE");
            existing.setAgentId(AGENT_ID); // ten sam agent
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(existing));
            when(contactRepository.update(any(Contact.class))).thenReturn(1);

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
            when(contactRepository.update(any(Contact.class))).thenReturn(1);

            UpdateContactRequest request = new UpdateContactRequest(
                    null, "ON_HOLD", null, null, null, null
            );

            // when
            contactService.updateContact(CONTACT_ID, request, TENANT_ID, AGENT_ID, true);

            // then – status zmieniony, agentId bez zmian
            verify(contactRepository).update(argThat(c ->
                    "ON_HOLD".equals(c.getStatus())
                    && AGENT_ID.equals(c.getAgentId())
            ));
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
            when(contactRepository.update(any(Contact.class))).thenReturn(1);

            DispositionRequest request = new DispositionRequest("SALE", null);

            // when
            contactService.setDisposition(CONTACT_ID, request, TENANT_ID, AGENT_ID, true);

            // then
            verify(contactRepository).update(argThat(c -> "SALE".equals(c.getDispositionCode())));
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
            when(contactRepository.update(any(Contact.class))).thenReturn(1);

            DispositionRequest request = new DispositionRequest("CALLBACK", null);

            // when – isAgent=false = SUPERVISOR
            assertThatNoException().isThrownBy(() ->
                    contactService.setDisposition(CONTACT_ID, request, TENANT_ID, AGENT_ID, false));
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
                    .thenThrow(new RecordingService.RecordingException("S3 connection failed",
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
    // Podgląd treści wiadomości email
    // =========================================================================

    @Nested
    @DisplayName("getEmailPreview")
    class GetEmailPreviewTests {

        private static final UUID MESSAGE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        private static final Instant RECEIVED_AT = Instant.parse("2026-04-17T10:30:00Z");

        @Test
        @DisplayName("zwraca EmailPreviewResponse gdy kontakt EMAIL ma wiadomość")
        void getEmailPreview_returnsResponseWhenEmailContactAndMessageExists() {
            // given
            Contact contact = buildEmailContact(CONTACT_ID, "COMPLETED");
            EmailMessage message = buildEmailMessage(MESSAGE_ID, contactId -> contact.getContactId());
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageRepository.findByContactId(eq(CONTACT_ID), eq(TENANT_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(message)));

            // when
            EmailPreviewResponse response = contactService.getEmailPreview(CONTACT_ID, TENANT_ID);

            // then
            assertThat(response.from()).isEqualTo("klient@example.com");
            assertThat(response.to()).isEqualTo("support@firma.pl");
            assertThat(response.subject()).isEqualTo("Prośba o pomoc");
            assertThat(response.bodyHtml()).isEqualTo("<p>Treść HTML</p>");
            assertThat(response.bodyText()).isEqualTo("Treść tekstowa");
            assertThat(response.receivedAt()).isEqualTo(RECEIVED_AT);
            assertThat(response.direction()).isEqualTo("INBOUND");
        }

        @Test
        @DisplayName("rzuca ResponseStatusException 400 gdy kontakt nie jest kanałem EMAIL")
        void getEmailPreview_throwsBadRequestWhenNotEmailChannel() {
            // given
            Contact contact = buildContact(CONTACT_ID, "COMPLETED"); // kanał PHONE
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            // when / then
            assertThatThrownBy(() -> contactService.getEmailPreview(CONTACT_ID, TENANT_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> {
                        ResponseStatusException rse = (ResponseStatusException) ex;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(rse.getReason()).contains("EMAIL");
                    });

            verifyNoInteractions(emailMessageRepository);
        }

        @Test
        @DisplayName("rzuca ResponseStatusException 404 gdy kontakt EMAIL nie ma wiadomości")
        void getEmailPreview_throwsNotFoundWhenNoEmailMessages() {
            // given
            Contact contact = buildEmailContact(CONTACT_ID, "COMPLETED");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageRepository.findByContactId(eq(CONTACT_ID), eq(TENANT_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            // when / then
            assertThatThrownBy(() -> contactService.getEmailPreview(CONTACT_ID, TENANT_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> {
                        ResponseStatusException rse = (ResponseStatusException) ex;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(rse.getReason()).contains(CONTACT_ID.toString());
                    });
        }

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy kontakt nie istnieje")
        void getEmailPreview_throwsWhenContactNotFound() {
            // given
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> contactService.getEmailPreview(CONTACT_ID, TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(CONTACT_ID.toString());

            verifyNoInteractions(emailMessageRepository);
        }

        @Test
        @DisplayName("zwraca null dla pól cc i bodyHtml gdy są null w encji")
        void getEmailPreview_returnsNullFieldsWhenAbsentInMessage() {
            // given
            Contact contact = buildEmailContact(CONTACT_ID, "COMPLETED");
            EmailMessage message = EmailMessage.builder()
                    .id(MESSAGE_ID)
                    .tenantId(TENANT_ID)
                    .contactId(CONTACT_ID)
                    .direction("INBOUND")
                    .fromAddress("klient@example.com")
                    .toAddress("support@firma.pl")
                    .ccAddress(null)
                    .subject("Test")
                    .bodyHtml(null)
                    .bodyText("Tylko tekst")
                    .receivedAt(RECEIVED_AT)
                    .createdAt(Instant.now())
                    .build();
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(emailMessageRepository.findByContactId(eq(CONTACT_ID), eq(TENANT_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(message)));

            // when
            EmailPreviewResponse response = contactService.getEmailPreview(CONTACT_ID, TENANT_ID);

            // then
            assertThat(response.cc()).isNull();
            assertThat(response.bodyHtml()).isNull();
            assertThat(response.bodyText()).isEqualTo("Tylko tekst");
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

        private EmailMessage buildEmailMessage(UUID messageId,
                java.util.function.Function<UUID, UUID> contactIdSupplier) {
            return EmailMessage.builder()
                    .id(messageId)
                    .tenantId(TENANT_ID)
                    .contactId(contactIdSupplier.apply(messageId))
                    .direction("INBOUND")
                    .fromAddress("klient@example.com")
                    .toAddress("support@firma.pl")
                    .ccAddress(null)
                    .subject("Prośba o pomoc")
                    .bodyHtml("<p>Treść HTML</p>")
                    .bodyText("Treść tekstowa")
                    .receivedAt(RECEIVED_AT)
                    .createdAt(Instant.now())
                    .build();
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
