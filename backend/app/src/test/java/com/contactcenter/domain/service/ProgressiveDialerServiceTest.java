package com.contactcenter.domain.service;

import com.contactcenter.domain.tenant.TenantTwilioConfigService;
import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.user.AppUserRepository;
import com.contactcenter.domain.repository.CampaignAssignmentRepository;
import com.contactcenter.domain.repository.CampaignContactRepository;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link ProgressiveDialerService} (BE-065).
 *
 * <p>Scenariusze:
 * <ol>
 *   <li>Rekord NO_ANSWER z next_attempt_at w przeszłości → dialer go pobiera i inicjuje połączenie</li>
 *   <li>Rekord NO_ANSWER z next_attempt_at w przyszłości → dialer go pomija</li>
 *   <li>Brak kampanii RUNNING → zwolnienie blokady agenta, brak połączenia</li>
 *   <li>Kampania poza harmonogramem → pomijana, brak połączenia</li>
 *   <li>Kontakt bez numeru telefonu → oznaczony SKIPPED, brak połączenia</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProgressiveDialerServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID    = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID CAMPAIGN_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID RECORD_ID   = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private CampaignAssignmentRepository campaignAssignmentRepository;

    @Mock
    private CampaignContactRepository campaignContactRepository;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private TelephonyAdapter telephonyAdapter;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TenantTwilioConfigService tenantTwilioConfigService;

    @InjectMocks
    private ProgressiveDialerService dialerService;

    @BeforeEach
    void setUp() {
        // self-reference przez ReflectionTestUtils (normalnie wstrzykiwany przez @Lazy @Autowired)
        ReflectionTestUtils.setField(dialerService, "self", dialerService);
        ReflectionTestUtils.setField(dialerService, "defaultOutboundNumber", "+48000000000");

        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(AGENT_ID);

        // Stub Redis ValueOperations (wymagany przez setIfAbsent i set)
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Blokada agenta: domyślnie udana akwizycja (lock wolny) – testy weryfikują logikę po przejściu blokady
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        // Stub JdbcTemplate.execute (setTenantContextInJdbc)
        when(jdbcTemplate.execute(anyString(), any(PreparedStatementCallback.class))).thenReturn(null);

        // BE-081: domyślnie agent jest kwalifikowany (allAgents=false, ale w zbiorze eligible)
        when(campaignAssignmentRepository.resolveEligibleAgentIds(eq(CAMPAIGN_ID), eq(TENANT_ID)))
                .thenReturn(Set.of(AGENT_ID));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Pomocnicze – budowanie obiektów testowych
    // =========================================================================

    private Campaign buildRunningCampaign() {
        return Campaign.builder()
                .campaignId(CAMPAIGN_ID)
                .tenantId(TENANT_ID)
                .name("Test Campaign")
                .status("RUNNING")
                .dialerType("PROGRESSIVE")
                .queueId(null)
                .schedule(Collections.emptyMap())
                .build();
    }

    private Map<String, Object> buildContactRow(String phone, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("record_id", RECORD_ID.toString());
        row.put("phone", phone);
        row.put("first_name", "Jan");
        row.put("last_name", "Kowalski");
        row.put("attempt_count", 1);
        row.put("last_attempt_at", null);
        row.put("status", status);
        return row;
    }

    private CallSession buildCallSession() {
        return CallSession.builder()
                .callId("CA-test-" + UUID.randomUUID())
                .build();
    }

    // =========================================================================
    // Scenariusz 1: NO_ANSWER z next_attempt_at w przeszłości → dialer pobiera
    // =========================================================================

    @Nested
    @DisplayName("Rekordy NO_ANSWER")
    class NoAnswerRetry {

        @Test
        @DisplayName("NO_ANSWER z next_attempt_at w przeszłości → dialer inicjuje połączenie")
        void noAnswerContact_withPastNextAttemptAt_shouldBeDialed() throws Exception {
            // given
            Campaign campaign = buildRunningCampaign();
            when(campaignRepository.findRunningProgressiveByTenantId(TENANT_ID))
                    .thenReturn(List.of(campaign));

            // Kontakt NO_ANSWER z numerem telefonu (next_attempt_at jest w przeszłości –
            // filtrowane przez SQL WHERE next_attempt_at <= NOW(), więc JdbcTemplate zwraca go)
            Map<String, Object> contact = buildContactRow("+48123456789", "NO_ANSWER");
            when(jdbcTemplate.queryForList(
                    contains("status IN ('PENDING', 'NO_ANSWER')"),
                    eq(CAMPAIGN_ID.toString()),
                    eq(TENANT_ID.toString())))
                    .thenReturn(List.of(contact));

            CallSession session = buildCallSession();
            when(telephonyAdapter.initiateCall(any(), anyString(), anyString(), any(), any(), any()))
                    .thenReturn(session);

            when(tenantTwilioConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.empty());

            // when
            dialerService.initiateDialForAgent(AGENT_ID, TENANT_ID);

            // then – połączenie zainicjowane
            verify(telephonyAdapter).initiateCall(
                    eq(TENANT_ID),
                    anyString(),
                    eq("+48123456789"),
                    eq(AGENT_ID),
                    eq(CAMPAIGN_ID),   // BE-082: campaignId zamiast queueId
                    eq(null)
            );

            // Kontakt oznaczony jako DIALING
            verify(jdbcTemplate).update(
                    contains("SET status = 'DIALING'"),
                    eq(RECORD_ID.toString()),
                    eq(CAMPAIGN_ID.toString()),
                    eq(TENANT_ID.toString())
            );
        }

        @Test
        @DisplayName("NO_ANSWER z next_attempt_at w przyszłości → SQL nie zwraca rekordu → brak połączenia")
        void noAnswerContact_withFutureNextAttemptAt_shouldBeSkipped() throws Exception {
            // given
            Campaign campaign = buildRunningCampaign();
            when(campaignRepository.findRunningProgressiveByTenantId(TENANT_ID))
                    .thenReturn(List.of(campaign));

            // SQL filtruje next_attempt_at > NOW() – JdbcTemplate zwraca pustą listę
            when(jdbcTemplate.queryForList(
                    contains("status IN ('PENDING', 'NO_ANSWER')"),
                    eq(CAMPAIGN_ID.toString()),
                    eq(TENANT_ID.toString())))
                    .thenReturn(Collections.emptyList());

            // when
            dialerService.initiateDialForAgent(AGENT_ID, TENANT_ID);

            // then – brak połączenia
            verify(telephonyAdapter, never()).initiateCall(any(), any(), any(), any(), any(), any());
        }
    }

    // =========================================================================
    // Scenariusz 3: Brak kampanii RUNNING → zwolnienie blokady
    // =========================================================================

    @Test
    @DisplayName("Brak kampanii RUNNING → brak połączenia, blokada agenta zwolniona")
    void noCampaigns_shouldReleaseAgentLock() {
        // given
        when(campaignRepository.findRunningProgressiveByTenantId(TENANT_ID))
                .thenReturn(Collections.emptyList());

        // when
        dialerService.initiateDialForAgent(AGENT_ID, TENANT_ID);

        // then
        verify(telephonyAdapter, never()).initiateCall(any(), any(), any(), any(), any(), any());
        verify(redisTemplate).delete(eq("dialer:agent:" + AGENT_ID));
    }

    // =========================================================================
    // Scenariusz 4: Kampania poza harmonogramem → pomijana
    // =========================================================================

    @Test
    @DisplayName("Kampania poza harmonogramem → brak połączenia")
    void campaignOutOfSchedule_shouldSkipCampaign() {
        // given – schedule z active_hours wykluczającym obecną chwilę (północ–00:01)
        Map<String, Object> activeHours = new HashMap<>();
        activeHours.put("from", "00:00");
        activeHours.put("to", "00:01");
        Map<String, Object> schedule = new HashMap<>();
        schedule.put("active_hours", activeHours);

        Campaign campaign = Campaign.builder()
                .campaignId(CAMPAIGN_ID)
                .tenantId(TENANT_ID)
                .name("Night Campaign")
                .status("RUNNING")
                .dialerType("PROGRESSIVE")
                .queueId(null)
                .schedule(schedule)
                .build();

        when(campaignRepository.findRunningProgressiveByTenantId(TENANT_ID))
                .thenReturn(List.of(campaign));

        // when
        dialerService.initiateDialForAgent(AGENT_ID, TENANT_ID);

        // then – brak wywołania fetchNextPendingContact i brak połączenia
        verify(telephonyAdapter, never()).initiateCall(any(), any(), any(), any(), any(), any());
        verify(jdbcTemplate, never()).queryForList(anyString(), anyString(), anyString());
    }

    // =========================================================================
    // Scenariusz 5: Kontakt bez numeru telefonu → SKIPPED
    // =========================================================================

    @Test
    @DisplayName("Kontakt bez numeru telefonu → oznaczony SKIPPED, brak połączenia")
    void contactWithoutPhone_shouldBeMarkedSkipped() {
        // given
        Campaign campaign = buildRunningCampaign();
        when(campaignRepository.findRunningProgressiveByTenantId(TENANT_ID))
                .thenReturn(List.of(campaign));

        Map<String, Object> contact = buildContactRow(null, "PENDING");
        when(jdbcTemplate.queryForList(
                contains("status IN ('PENDING', 'NO_ANSWER')"),
                eq(CAMPAIGN_ID.toString()),
                eq(TENANT_ID.toString())))
                .thenReturn(List.of(contact));

        // when
        dialerService.initiateDialForAgent(AGENT_ID, TENANT_ID);

        // then – kontakt oznaczony SKIPPED
        verify(jdbcTemplate).update(
                contains("SET status = ?"),
                eq("SKIPPED"),
                eq(RECORD_ID.toString()),
                eq(CAMPAIGN_ID.toString()),
                eq(TENANT_ID.toString())
        );
        verify(telephonyAdapter, never()).initiateCall(any(), any(), any(), any(), any(), any());
    }

    // =========================================================================
    // Weryfikacja: metoda isCalledTooRecently nie istnieje w klasie
    // =========================================================================

    @Test
    @DisplayName("Metoda isCalledTooRecently nie istnieje – guard przez next_attempt_at jest autorytatywny")
    void isCalledTooRecently_methodDoesNotExist() {
        // Weryfikacja przez refleksję że zbędna metoda została usunięta
        boolean methodExists = java.util.Arrays.stream(ProgressiveDialerService.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("isCalledTooRecently"));
        assertThat(methodExists)
                .as("Metoda isCalledTooRecently powinna być usunięta – next_attempt_at jest autorytatywne")
                .isFalse();
    }
}
