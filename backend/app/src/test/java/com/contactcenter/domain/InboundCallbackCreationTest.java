package com.contactcenter.domain;

import com.contactcenter.api.dialer.DialerController;
import com.contactcenter.api.dialer.dto.CreateInboundCallbackRequest;
import com.contactcenter.api.dialer.dto.ScheduledCallbackResponse;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.repository.CampaignContactRepository;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.service.DialerCallbackHandler;
import com.contactcenter.domain.service.ProgressiveDialerService;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.security.TenantContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla endpointu POST /api/contacts/{contactId}/callback (BE-040).
 *
 * <p>Scenariusze:
 * <ol>
 *   <li>Poprawne żądanie → HTTP 201, sourceType=INBOUND_CALLBACK, originContactId=contactId</li>
 *   <li>Nieistniejący contactId → ResourceNotFoundException (HTTP 404)</li>
 *   <li>AGENT dla cudzego kontaktu (contact.agentId != null i różny) → AccessDeniedException (HTTP 403)</li>
 *   <li>AGENT dla kontaktu bez agenta (contact.agentId=null) → HTTP 201 (zezwól)</li>
 *   <li>scheduledAt w przeszłości → naruszenie Bean Validation @Future (HTTP 422)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class InboundCallbackCreationTest {

    @Mock
    private ScheduledCallbackRepository scheduledCallbackRepository;

    @Mock
    private ProgressiveDialerService progressiveDialerService;

    @Mock
    private DialerCallbackHandler dialerCallbackHandler;

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private CampaignContactRepository campaignContactRepository;

    @Mock
    private TelephonyAdapter telephonyAdapter;

    @Mock
    private ContactRepository contactRepository;

    @InjectMocks
    private DialerController dialerController;

    private static final UUID TENANT_ID   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CONTACT_ID  = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID AGENT_ID    = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID OTHER_AGENT = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
    private static final UUID CALLBACK_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000005");

    /** Walidator Bean Validation – do testu nr 5 (scheduledAt w przeszłości). */
    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();

        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Test 1: Poprawne żądanie → 201, sourceType=INBOUND_CALLBACK, originContactId=contactId
    // =========================================================================

    @Test
    @DisplayName("Poprawne żądanie AGENT → HTTP 201, sourceType=INBOUND_CALLBACK, originContactId ustawione")
    void createInboundCallback_validRequest_returns201WithCorrectDto() {
        // given
        Instant scheduledAt = Instant.now().plusSeconds(3600);

        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");

        // Kontakt przypisany do tego samego agenta
        Contact contact = buildContact(CONTACT_ID, TENANT_ID, AGENT_ID);
        ScheduledCallback saved = buildSavedCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, scheduledAt, CONTACT_ID);

        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
        when(scheduledCallbackRepository.save(any(ScheduledCallback.class))).thenReturn(saved);

        CreateInboundCallbackRequest request = new CreateInboundCallbackRequest(
                "+48123456789", "Jan", "Kowalski", scheduledAt, "Oddzwonić po 15:00", null);

        // when
        ResponseEntity<ScheduledCallbackResponse> response =
                dialerController.createInboundCallback(CONTACT_ID, request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sourceType()).isEqualTo("INBOUND_CALLBACK");
        assertThat(response.getBody().originContactId()).isEqualTo(CONTACT_ID);
        assertThat(response.getBody().callbackId()).isEqualTo(CALLBACK_ID);

        verify(scheduledCallbackRepository).save(any(ScheduledCallback.class));
    }

    // =========================================================================
    // Test 2: Nieistniejący contactId → 404
    // =========================================================================

    @Test
    @DisplayName("Nieistniejący contactId → ResourceNotFoundException (HTTP 404)")
    void createInboundCallback_nonExistentContact_throwsResourceNotFoundException() {
        // given
        UUID unknownContactId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        Instant scheduledAt = Instant.now().plusSeconds(3600);

        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");

        when(contactRepository.findById(unknownContactId, TENANT_ID)).thenReturn(Optional.empty());

        CreateInboundCallbackRequest request = new CreateInboundCallbackRequest(
                "+48123456789", null, null, scheduledAt, null, null);

        // when / then
        assertThatThrownBy(() -> dialerController.createInboundCallback(unknownContactId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(unknownContactId.toString());

        verify(scheduledCallbackRepository, never()).save(any());
    }

    // =========================================================================
    // Test 3: AGENT dla cudzego kontaktu (agentId != null i różny) → 403
    // =========================================================================

    @Test
    @DisplayName("AGENT próbuje zaplanować callback z cudzej rozmowy → AccessDeniedException (HTTP 403)")
    void createInboundCallback_agentForForeignContact_throwsAccessDeniedException() {
        // given
        Instant scheduledAt = Instant.now().plusSeconds(3600);

        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");

        // Kontakt przypisany do INNEGO agenta
        Contact contact = buildContact(CONTACT_ID, TENANT_ID, OTHER_AGENT);
        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

        CreateInboundCallbackRequest request = new CreateInboundCallbackRequest(
                "+48123456789", null, null, scheduledAt, null, null);

        // when / then
        assertThatThrownBy(() -> dialerController.createInboundCallback(CONTACT_ID, request))
                .isInstanceOf(AccessDeniedException.class);

        verify(scheduledCallbackRepository, never()).save(any());
    }

    // =========================================================================
    // Test 4: AGENT dla kontaktu bez agenta (agentId=null) → 201 (zezwól)
    // =========================================================================

    @Test
    @DisplayName("AGENT dla kontaktu bez agenta (agentId=null) → HTTP 201 (zezwól)")
    void createInboundCallback_agentForContactWithoutAgent_returns201() {
        // given
        Instant scheduledAt = Instant.now().plusSeconds(3600);

        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");

        // Kontakt BEZ przypisanego agenta
        Contact contact = buildContact(CONTACT_ID, TENANT_ID, null);
        ScheduledCallback saved = buildSavedCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, scheduledAt, CONTACT_ID);

        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
        when(scheduledCallbackRepository.save(any(ScheduledCallback.class))).thenReturn(saved);

        CreateInboundCallbackRequest request = new CreateInboundCallbackRequest(
                "+48123456789", null, null, scheduledAt, null, null);

        // when
        ResponseEntity<ScheduledCallbackResponse> response =
                dialerController.createInboundCallback(CONTACT_ID, request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sourceType()).isEqualTo("INBOUND_CALLBACK");

        verify(scheduledCallbackRepository).save(any(ScheduledCallback.class));
    }

    // =========================================================================
    // Test 5: scheduledAt w przeszłości → naruszenie @Future (422)
    // =========================================================================

    @Test
    @DisplayName("scheduledAt w przeszłości – naruszenie @Future (Bean Validation, HTTP 422)")
    void createInboundCallbackRequest_pastScheduledAt_failsBeanValidation() {
        // given – tworzymy DTO z datą w przeszłości i walidujemy przez Bean Validation
        Instant pastDate = Instant.now().minusSeconds(3600);
        CreateInboundCallbackRequest request = new CreateInboundCallbackRequest(
                "+48123456789", null, null, pastDate, null, null);

        // when
        Set<ConstraintViolation<CreateInboundCallbackRequest>> violations = validator.validate(request);

        // then
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().equals("scheduledAt"));
    }

    // =========================================================================
    // Pomocnicze – budowanie encji testowych
    // =========================================================================

    private Contact buildContact(UUID contactId, UUID tenantId, UUID agentId) {
        return Contact.builder()
                .contactId(contactId)
                .tenantId(tenantId)
                .agentId(agentId)
                .channel("PHONE")
                .direction("INBOUND")
                .status("ACTIVE")
                .queuedAt(Instant.now().minusSeconds(300))
                .startedAt(Instant.now().minusSeconds(300))
                .build();
    }

    private ScheduledCallback buildSavedCallback(UUID callbackId, UUID tenantId, UUID agentId,
                                                  Instant scheduledAt, UUID originContactId) {
        return ScheduledCallback.builder()
                .callbackId(callbackId)
                .tenantId(tenantId)
                .agentId(agentId)
                .phone("+48123456789")
                .firstName("Jan")
                .lastName("Kowalski")
                .scheduledAt(scheduledAt)
                .sourceType("INBOUND_CALLBACK")
                .originContactId(originContactId)
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
    }
}
