package com.contactcenter.domain;

import com.contactcenter.api.dialer.DialerController;
import com.contactcenter.api.dialer.dto.RescheduleCallbackRequest;
import com.contactcenter.api.dialer.dto.ScheduledCallbackResponse;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.campaign.ScheduledCallback;
import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.campaign.ScheduledCallbackService;
import com.contactcenter.domain.campaign.CampaignAssignmentService;
import com.contactcenter.domain.campaign.DialerCallbackHandler;
import com.contactcenter.domain.campaign.ProgressiveDialerService;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla endpointu PUT /api/dialer/callbacks/{callbackId} (BE-039).
 *
 * <p>Scenariusze:
 * <ol>
 *   <li>Callback PENDING → zmiana scheduledAt → HTTP 200 z zaktualizowanym DTO</li>
 *   <li>Callback COMPLETED → ConflictException (HTTP 409)</li>
 *   <li>AGENT próbuje przełożyć cudzy callback → AccessDeniedException (HTTP 403)</li>
 *   <li>scheduledAt w przeszłości → naruszenie Bean Validation @Future (HTTP 400/422)</li>
 *   <li>Nieistniejący callbackId → ResourceNotFoundException (HTTP 404)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DialerCallbackRescheduleTest {

    @Mock
    private ScheduledCallbackService scheduledCallbackService;

    @Mock
    private ProgressiveDialerService progressiveDialerService;

    @Mock
    private DialerCallbackHandler dialerCallbackHandler;

    @Mock
    private CampaignService campaignService;

    @Mock
    private CampaignAssignmentService campaignAssignmentService;

    @Mock
    private TelephonyAdapter telephonyAdapter;

    @InjectMocks
    private DialerController dialerController;

    private static final UUID TENANT_ID   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CALLBACK_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID AGENT_ID    = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID OTHER_AGENT = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

    /** Walidator Bean Validation – do testu nr 4 (scheduledAt w przeszłości). */
    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();

        // Ustaw kontekst tenanta – symuluje JwtAuthFilter + TenantFilter
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Test 1: Callback PENDING → 200 OK z zaktualizowanym DTO
    // =========================================================================

    @Test
    @DisplayName("Callback PENDING – zmiana scheduledAt i notes → 200 OK")
    void rescheduleCallback_pendingCallback_returns200WithUpdatedDto() {
        // given
        Instant newScheduledAt = Instant.now().plusSeconds(3600);
        ScheduledCallback callback = buildCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, "PENDING");
        ScheduledCallback saved = buildCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, "PENDING");
        saved.setScheduledAt(newScheduledAt);
        saved.setNotes("Nowa notatka");

        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("SUPERVISOR");

        when(scheduledCallbackService.getCallbackOrThrow(CALLBACK_ID, TENANT_ID))
                .thenReturn(callback);
        when(scheduledCallbackService.saveCallback(any(ScheduledCallback.class)))
                .thenReturn(saved);

        RescheduleCallbackRequest request = new RescheduleCallbackRequest(newScheduledAt, "Nowa notatka");

        // when
        ResponseEntity<ScheduledCallbackResponse> response =
                dialerController.rescheduleCallback(CALLBACK_ID, request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().callbackId()).isEqualTo(CALLBACK_ID);
        assertThat(response.getBody().scheduledAt()).isEqualTo(newScheduledAt);
        assertThat(response.getBody().notes()).isEqualTo("Nowa notatka");
        assertThat(response.getBody().status()).isEqualTo("PENDING");

        verify(scheduledCallbackService).saveCallback(any(ScheduledCallback.class));
    }

    // =========================================================================
    // Test 2: Callback COMPLETED → ConflictException (409)
    // =========================================================================

    @Test
    @DisplayName("Callback COMPLETED – rzuca ConflictException (HTTP 409)")
    void rescheduleCallback_completedStatus_throwsConflictException() {
        // given
        Instant newScheduledAt = Instant.now().plusSeconds(3600);
        ScheduledCallback callback = buildCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, "COMPLETED");

        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("SUPERVISOR");

        when(scheduledCallbackService.getCallbackOrThrow(CALLBACK_ID, TENANT_ID))
                .thenReturn(callback);

        RescheduleCallbackRequest request = new RescheduleCallbackRequest(newScheduledAt, null);

        // when / then
        assertThatThrownBy(() -> dialerController.rescheduleCallback(CALLBACK_ID, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("COMPLETED");

        verify(scheduledCallbackService, never()).saveCallback(any());
    }

    // =========================================================================
    // Test 3: AGENT próbuje przełożyć cudzy callback → AccessDeniedException (403)
    // =========================================================================

    @Test
    @DisplayName("AGENT próbuje przełożyć cudzy callback → AccessDeniedException (HTTP 403)")
    void rescheduleCallback_agentReschedulingForeignCallback_throwsAccessDeniedException() {
        // given
        Instant newScheduledAt = Instant.now().plusSeconds(3600);
        // Callback należy do OTHER_AGENT, nie do zalogowanego AGENT_ID
        ScheduledCallback callback = buildCallback(CALLBACK_ID, TENANT_ID, OTHER_AGENT, "PENDING");

        TenantContext.setUserId(AGENT_ID);  // zalogowany agent
        TenantContext.setUserRole("AGENT");

        when(scheduledCallbackService.getCallbackOrThrow(CALLBACK_ID, TENANT_ID))
                .thenReturn(callback);

        RescheduleCallbackRequest request = new RescheduleCallbackRequest(newScheduledAt, null);

        // when / then
        assertThatThrownBy(() -> dialerController.rescheduleCallback(CALLBACK_ID, request))
                .isInstanceOf(AccessDeniedException.class);

        verify(scheduledCallbackService, never()).saveCallback(any());
    }

    // =========================================================================
    // Test 4: scheduledAt w przeszłości → naruszenie Bean Validation @Future
    // =========================================================================

    @Test
    @DisplayName("scheduledAt w przeszłości – naruszenie @Future (walidacja Bean Validation)")
    void rescheduleCallbackRequest_pastScheduledAt_failsBeanValidation() {
        // given – tworzymy DTO z datą w przeszłości i walidujemy przez Bean Validation
        Instant pastDate = Instant.now().minusSeconds(3600);
        RescheduleCallbackRequest request = new RescheduleCallbackRequest(pastDate, null);

        // when
        Set<ConstraintViolation<RescheduleCallbackRequest>> violations = validator.validate(request);

        // then
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().equals("scheduledAt") &&
                v.getMessage().contains("przyszłości"));
    }

    // =========================================================================
    // Test 5: Nieistniejący callbackId → ResourceNotFoundException (404)
    // =========================================================================

    @Test
    @DisplayName("Nieistniejący callbackId – rzuca ResourceNotFoundException (HTTP 404)")
    void rescheduleCallback_nonExistentCallbackId_throwsResourceNotFoundException() {
        // given
        UUID unknownId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        Instant newScheduledAt = Instant.now().plusSeconds(3600);

        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");

        when(scheduledCallbackService.getCallbackOrThrow(unknownId, TENANT_ID))
                .thenThrow(new ResourceNotFoundException("Callback nie istnieje: " + unknownId));

        RescheduleCallbackRequest request = new RescheduleCallbackRequest(newScheduledAt, null);

        // when / then
        assertThatThrownBy(() -> dialerController.rescheduleCallback(unknownId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(unknownId.toString());

        verify(scheduledCallbackService, never()).saveCallback(any());
    }

    // =========================================================================
    // Pomocnicze – budowanie encji testowych
    // =========================================================================

    private ScheduledCallback buildCallback(UUID callbackId, UUID tenantId, UUID agentId, String status) {
        return ScheduledCallback.builder()
                .callbackId(callbackId)
                .tenantId(tenantId)
                .agentId(agentId)
                .phone("+48123456789")
                .status(status)
                .scheduledAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now().minusSeconds(300))
                .build();
    }
}
