package com.contactcenter.api;

import com.contactcenter.api.dialer.DialerController;
import com.contactcenter.api.dialer.dto.ManualCallRequest;
import com.contactcenter.api.dialer.dto.ManualCallResponse;
import com.contactcenter.domain.campaign.AgentGate;
import com.contactcenter.domain.campaign.Campaign;
import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.campaign.CampaignAssignmentService;
import com.contactcenter.domain.campaign.DialerCallbackHandler;
import com.contactcenter.domain.campaign.ProgressiveDialerService;
import com.contactcenter.domain.campaign.ScheduledCallbackService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /api/dialer/manual/call - BE-230 gate mapping (AGENT_BUSY / RECORD_UNAVAILABLE / AGENT_LOCK_TIMEOUT -> 409
 * problem+json with a machine-readable {@code error} code) and the "no call without a reservation" invariant.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DialerController - manual call gate (BE-230 NF1)")
class DialerManualCallGateTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CAMPAIGN_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID RECORD_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Mock private ProgressiveDialerService progressiveDialerService;
    @Mock private DialerCallbackHandler dialerCallbackHandler;
    @Mock private CampaignService campaignService;
    @Mock private CampaignAssignmentService campaignAssignmentService;
    @Mock private ScheduledCallbackService scheduledCallbackService;
    @Mock private TelephonyAdapter telephonyAdapter;
    @Mock private ContactService contactService;
    @Mock private UserService userService;

    @InjectMocks
    private DialerController dialerController;

    private final ManualCallRequest request = new ManualCallRequest(CAMPAIGN_ID, RECORD_ID);

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(AGENT_ID);
        Campaign campaign = Campaign.builder().campaignId(CAMPAIGN_ID).tenantId(TENANT_ID).name("manual")
                .status("RUNNING").dialerType("MANUAL").build();
        when(campaignService.findCampaignEntity(CAMPAIGN_ID, TENANT_ID)).thenReturn(Optional.of(campaign));
        when(progressiveDialerService.findRecordForManualDial(RECORD_ID, CAMPAIGN_ID, TENANT_ID))
                .thenReturn(Optional.of(Map.of("status", "PENDING", "phone", "+48500100200")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private InvalidOperationException refusedWith(AgentGate gate) {
        when(progressiveDialerService.markRecordAsDialingForAgent(RECORD_ID, CAMPAIGN_ID, TENANT_ID, AGENT_ID))
                .thenReturn(gate);
        InvalidOperationException[] holder = new InvalidOperationException[1];
        assertThatThrownBy(() -> dialerController.initiateManualCall(request))
                .isInstanceOfSatisfying(InvalidOperationException.class, e -> holder[0] = e);
        // a refused claim never reaches the telephony adapter
        verify(telephonyAdapter, never()).initiateCall(any(), any(), any(), any(), any(), any());
        return holder[0];
    }

    private ProblemDetail asProblem(InvalidOperationException e) {
        ResponseEntity<ProblemDetail> response = new GlobalExceptionHandler()
                .handleInvalidOperationException(e, mock(org.springframework.web.context.request.WebRequest.class));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        return response.getBody();
    }

    @Test
    @DisplayName("BUSY -> 409 problem+json, error=AGENT_BUSY, bez polaczenia")
    void busyAgentIsRefusedWith409() {
        InvalidOperationException e = refusedWith(AgentGate.BUSY);

        ProblemDetail problem = asProblem(e);
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("error", "AGENT_BUSY");
        assertThat(problem.getDetail()).contains("aktywne połączenie");
    }

    @Test
    @DisplayName("RECORD_UNAVAILABLE -> 409 problem+json, error=RECORD_UNAVAILABLE, bez polaczenia")
    void unavailableRecordIsRefusedWith409() {
        InvalidOperationException e = refusedWith(AgentGate.RECORD_UNAVAILABLE);

        ProblemDetail problem = asProblem(e);
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("error", "RECORD_UNAVAILABLE");
        assertThat(problem.getDetail()).contains(RECORD_ID.toString());
    }

    @Test
    @DisplayName("LOCK_TIMEOUT -> 409 problem+json, error=AGENT_LOCK_TIMEOUT")
    void lockTimeoutIsRefusedWith409() {
        ProblemDetail problem = asProblem(refusedWith(AgentGate.LOCK_TIMEOUT));
        assertThat(problem.getProperties()).containsEntry("error", "AGENT_LOCK_TIMEOUT");
    }

    @Test
    @DisplayName("FREE -> polaczenie inicjowane dokladnie raz po rezerwacji")
    void freeGateInitiatesTheCall() {
        when(progressiveDialerService.markRecordAsDialingForAgent(RECORD_ID, CAMPAIGN_ID, TENANT_ID, AGENT_ID))
                .thenReturn(AgentGate.FREE);
        CallSession session = mock(CallSession.class);
        when(session.getCallId()).thenReturn("CA1");
        when(telephonyAdapter.initiateCall(any(), any(), any(), any(), any(), any())).thenReturn(session);

        ResponseEntity<ManualCallResponse> response = dialerController.initiateManualCall(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(telephonyAdapter).initiateCall(any(), any(), any(), any(), any(), any());
    }
}
