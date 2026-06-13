package com.contactcenter.api;

import com.contactcenter.api.dialer.DialerController;
import com.contactcenter.api.dialer.dto.CallbackListItemResponse;
import com.contactcenter.api.dialer.dto.UpdateCallbackRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.campaign.ScheduledCallback;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.campaign.CampaignContactRepository;
import com.contactcenter.domain.campaign.CampaignRepository;
import com.contactcenter.domain.contact.ContactRepository;
import com.contactcenter.domain.campaign.ScheduledCallbackRepository;
import com.contactcenter.domain.campaign.DialerCallbackHandler;
import com.contactcenter.domain.campaign.ProgressiveDialerService;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla PATCH i DELETE /api/dialer/callbacks/{callbackId} (BE-042).
 *
 * <p>Weryfikuje: autoryzację ról, patch semantics, soft-delete, obsługę błędów (403/404/409).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DialerController – Callback Management (BE-042)")
class DialerCallbackManagementTest {

    private static final UUID TENANT_ID    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_ID     = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OTHER_AGENT  = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CALLBACK_ID  = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Mock private ProgressiveDialerService progressiveDialerService;
    @Mock private DialerCallbackHandler dialerCallbackHandler;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignContactRepository campaignContactRepository;
    @Mock private ScheduledCallbackRepository scheduledCallbackRepository;
    @Mock private TelephonyAdapter telephonyAdapter;
    @Mock private ContactRepository contactRepository;
    @Mock private UserService userService;

    @InjectMocks
    private DialerController dialerController;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private ScheduledCallback buildPendingCallback(UUID agentId) {
        return ScheduledCallback.builder()
                .callbackId(CALLBACK_ID)
                .tenantId(TENANT_ID)
                .agentId(agentId)
                .phone("+48111222333")
                .firstName("Jan")
                .lastName("Kowalski")
                .scheduledAt(Instant.now().plusSeconds(3600))
                .notes("Notatka testowa")
                .status("PENDING")
                .sourceType("CAMPAIGN_CALLBACK")
                .build();
    }

    private AppUser buildAppUser(UUID userId, String firstName, String lastName) {
        AppUser user = new AppUser();
        user.setId(userId);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return user;
    }

    // =========================================================================
    // PATCH /api/dialer/callbacks/{callbackId}
    // =========================================================================

    @Test
    @DisplayName("PATCH z phone → numer telefonu zaktualizowany")
    void patch_updatesPhone_whenPhoneProvided() {
        // given
        TenantContext.setUserRole("SUPERVISOR");
        TenantContext.setUserId(OTHER_AGENT);

        ScheduledCallback callback = buildPendingCallback(AGENT_ID);
        UpdateCallbackRequest request = new UpdateCallbackRequest(
                "+48999000111", null, null, null, null, null);

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.of(callback));
        when(scheduledCallbackRepository.save(any(ScheduledCallback.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userService.findUserById(AGENT_ID))
                .thenReturn(Optional.of(buildAppUser(AGENT_ID, "Jan", "Kowalski")));

        // when
        ResponseEntity<CallbackListItemResponse> result =
                dialerController.updateCallback(CALLBACK_ID, request);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().phone()).isEqualTo("+48999000111");
        assertThat(result.getBody().agentId()).isEqualTo(AGENT_ID);
        verify(scheduledCallbackRepository).save(argThat(cb -> "+48999000111".equals(cb.getPhone())));
    }

    @Test
    @DisplayName("PATCH z agentId przez SUPERVISOR → reassign do nowego agenta")
    void patch_reassignsAgent_whenSupervisorProvidesAgentId() {
        // given
        TenantContext.setUserRole("SUPERVISOR");
        TenantContext.setUserId(OTHER_AGENT);

        ScheduledCallback callback = buildPendingCallback(AGENT_ID);
        UUID newAgent = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UpdateCallbackRequest request = new UpdateCallbackRequest(
                null, null, null, null, null, newAgent);

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.of(callback));
        when(scheduledCallbackRepository.save(any(ScheduledCallback.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userService.findUserById(newAgent))
                .thenReturn(Optional.of(buildAppUser(newAgent, "Anna", "Nowak")));

        // when
        ResponseEntity<CallbackListItemResponse> result =
                dialerController.updateCallback(CALLBACK_ID, request);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().agentId()).isEqualTo(newAgent);
        assertThat(result.getBody().agentName()).isEqualTo("Anna Nowak");
        verify(scheduledCallbackRepository).save(argThat(cb -> newAgent.equals(cb.getAgentId())));
    }

    @Test
    @DisplayName("PATCH z agentId przez AGENT → agentId ignorowane, agent bez zmian")
    void patch_ignoresAgentId_whenRoleIsAgent() {
        // given
        TenantContext.setUserRole("AGENT");
        TenantContext.setUserId(AGENT_ID);

        ScheduledCallback callback = buildPendingCallback(AGENT_ID);
        UUID newAgent = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UpdateCallbackRequest request = new UpdateCallbackRequest(
                null, "Zmienione", null, null, null, newAgent);

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.of(callback));
        when(scheduledCallbackRepository.save(any(ScheduledCallback.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userService.findUserById(AGENT_ID))
                .thenReturn(Optional.of(buildAppUser(AGENT_ID, "Jan", "Kowalski")));

        // when
        ResponseEntity<CallbackListItemResponse> result =
                dialerController.updateCallback(CALLBACK_ID, request);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        // agentId NIE zmieniony na newAgent – pole ignorowane dla AGENT
        assertThat(result.getBody().agentId()).isEqualTo(AGENT_ID);
        // firstName zaktualizowane
        assertThat(result.getBody().firstName()).isEqualTo("Zmienione");
        verify(scheduledCallbackRepository).save(argThat(cb ->
                AGENT_ID.equals(cb.getAgentId()) && "Zmienione".equals(cb.getFirstName())));
    }

    @Test
    @DisplayName("PATCH dla callbacku w statusie COMPLETED → HTTP 409")
    void patch_throws409_whenStatusIsCompleted() {
        // given
        TenantContext.setUserRole("SUPERVISOR");
        TenantContext.setUserId(OTHER_AGENT);

        ScheduledCallback callback = buildPendingCallback(AGENT_ID);
        callback.setStatus("COMPLETED");

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.of(callback));

        UpdateCallbackRequest request = new UpdateCallbackRequest(
                null, null, null, null, "nowa notatka", null);

        // when / then
        assertThatThrownBy(() -> dialerController.updateCallback(CALLBACK_ID, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("COMPLETED");

        verify(scheduledCallbackRepository, never()).save(any());
    }

    @Test
    @DisplayName("PATCH przez AGENT dla cudzego callbacku → HTTP 403")
    void patch_throws403_whenAgentAccessesOtherAgentCallback() {
        // given
        TenantContext.setUserRole("AGENT");
        TenantContext.setUserId(AGENT_ID);

        // Callback należy do OTHER_AGENT, nie do AGENT_ID
        ScheduledCallback callback = buildPendingCallback(OTHER_AGENT);

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.of(callback));

        UpdateCallbackRequest request = new UpdateCallbackRequest(
                null, null, null, null, "zmiana", null);

        // when / then
        assertThatThrownBy(() -> dialerController.updateCallback(CALLBACK_ID, request))
                .isInstanceOf(AccessDeniedException.class);

        verify(scheduledCallbackRepository, never()).save(any());
    }

    // =========================================================================
    // DELETE /api/dialer/callbacks/{callbackId}
    // =========================================================================

    @Test
    @DisplayName("DELETE własnego callbacku przez AGENT → HTTP 204, status=CANCELLED")
    void delete_returnsNoContent_whenAgentDeletesOwnCallback() {
        // given
        TenantContext.setUserRole("AGENT");
        TenantContext.setUserId(AGENT_ID);

        ScheduledCallback callback = buildPendingCallback(AGENT_ID);

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.of(callback));
        when(scheduledCallbackRepository.cancelCallback(CALLBACK_ID, TENANT_ID))
                .thenReturn(1);

        // when
        ResponseEntity<Void> result = dialerController.cancelCallback(CALLBACK_ID);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(scheduledCallbackRepository).cancelCallback(CALLBACK_ID, TENANT_ID);
    }

    @Test
    @DisplayName("DELETE cudzego callbacku przez AGENT → HTTP 403")
    void delete_throws403_whenAgentDeletesOtherAgentCallback() {
        // given
        TenantContext.setUserRole("AGENT");
        TenantContext.setUserId(AGENT_ID);

        // Callback należy do OTHER_AGENT
        ScheduledCallback callback = buildPendingCallback(OTHER_AGENT);

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.of(callback));

        // when / then
        assertThatThrownBy(() -> dialerController.cancelCallback(CALLBACK_ID))
                .isInstanceOf(AccessDeniedException.class);

        verify(scheduledCallbackRepository, never()).cancelCallback(any(), any());
    }

    @Test
    @DisplayName("DELETE callbacku w statusie PROCESSING → HTTP 409")
    void delete_throws409_whenStatusIsProcessing() {
        // given
        TenantContext.setUserRole("SUPERVISOR");
        TenantContext.setUserId(OTHER_AGENT);

        ScheduledCallback callback = buildPendingCallback(AGENT_ID);
        callback.setStatus("PROCESSING");

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.of(callback));

        // when / then
        assertThatThrownBy(() -> dialerController.cancelCallback(CALLBACK_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("przetwarzany");

        verify(scheduledCallbackRepository, never()).cancelCallback(any(), any());
    }

    @Test
    @DisplayName("DELETE przez SUPERVISOR dla callbacku dowolnego agenta → HTTP 204")
    void delete_returnsNoContent_whenSupervisorDeletesAnyCallback() {
        // given
        TenantContext.setUserRole("SUPERVISOR");
        TenantContext.setUserId(OTHER_AGENT);

        // Callback należy do AGENT_ID, a wywołuje SUPERVISOR (OTHER_AGENT)
        ScheduledCallback callback = buildPendingCallback(AGENT_ID);

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.of(callback));
        when(scheduledCallbackRepository.cancelCallback(CALLBACK_ID, TENANT_ID))
                .thenReturn(1);

        // when
        ResponseEntity<Void> result = dialerController.cancelCallback(CALLBACK_ID);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(scheduledCallbackRepository).cancelCallback(CALLBACK_ID, TENANT_ID);
    }

    @Test
    @DisplayName("DELETE nieistniejącego callbacku → ResourceNotFoundException (404)")
    void delete_throws404_whenCallbackNotFound() {
        // given
        TenantContext.setUserRole("SUPERVISOR");
        TenantContext.setUserId(OTHER_AGENT);

        when(scheduledCallbackRepository.findById(CALLBACK_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> dialerController.cancelCallback(CALLBACK_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(scheduledCallbackRepository, never()).cancelCallback(any(), any());
    }
}
