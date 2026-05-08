package com.contactcenter.domain;

import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.service.DialerCallbackHandler;
import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import com.contactcenter.domain.model.ScheduledCallback;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link DialerCallbackHandler#onCallHangup(CallEvent)}.
 *
 * <p>Scenariusze BE-062:
 * <ol>
 *   <li>callOutcome = "no-answer" → handleNoAnswer() wywołany</li>
 *   <li>callOutcome = "busy"      → handleNoAnswer() wywołany</li>
 *   <li>callOutcome = "completed" → updateCampaignContact z "COMPLETED"</li>
 *   <li>callOutcome = null        → updateCampaignContact z "COMPLETED" (graceful fallback)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DialerCallbackHandler – rozróżnianie callOutcome przy call.hangup")
class DialerCallbackHandlerTest {

    private static final UUID TENANT_ID    = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CAMPAIGN_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RECORD_ID    = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AGENT_ID     = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String CALL_SID   = "CA_test_001";

    /** Format stanu Redis: campaignContactId,campaignId,agentId,tenantId */
    private static final String REDIS_STATE = RECORD_ID + "," + CAMPAIGN_ID + "," + AGENT_ID + "," + TENANT_ID;

    @Mock private ScheduledCallbackRepository scheduledCallbackRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private DialerCallbackHandler handler;

    @BeforeEach
    void setUp() {
        // Ręczna instancja – @RequiredArgsConstructor; kolejność pól musi być zgodna z deklaracją w klasie
        handler = new DialerCallbackHandler(
                scheduledCallbackRepository,
                campaignRepository,
                redisTemplate,
                jdbcTemplate
        );

        // Redis – klucz dialer:call:{callSid} zawsze zwraca stan połączenia dialera
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("dialer:call:" + CALL_SID)).thenReturn(REDIS_STATE);

        // JdbcTemplate – domyślnie nic nie rzuca
        when(jdbcTemplate.execute(anyString(), any(PreparedStatementCallback.class))).thenReturn(null);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        // CampaignRepository – domyślnie zwraca kampanię z maxAttempts=3
        Campaign campaign = Campaign.builder()
                .campaignId(CAMPAIGN_ID)
                .tenantId(TENANT_ID)
                .maxAttempts(3)
                .retryDelayMinutes(60)
                .build();
        when(campaignRepository.findById(eq(CAMPAIGN_ID), eq(TENANT_ID))).thenReturn(Optional.of(campaign));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Scenariusze no-answer / busy → handleNoAnswer
    // =========================================================================

    @Nested
    @DisplayName("callOutcome powodujące próbę ponowną")
    class NoAnswerOutcomes {

        @Test
        @DisplayName("callOutcome='no-answer' powinien wywołać handleNoAnswer (status NO_ANSWER)")
        void noAnswerOutcomeShouldTriggerHandleNoAnswer() {
            CallEvent event = buildHangupEvent("no-answer");

            handler.onCallHangup(event);

            assertStatusParamUsed("NO_ANSWER");
            assertStatusParamNeverUsed("COMPLETED");
        }

        @Test
        @DisplayName("callOutcome='busy' powinien wywołać handleNoAnswer (status NO_ANSWER)")
        void busyOutcomeShouldTriggerHandleNoAnswer() {
            CallEvent event = buildHangupEvent("busy");

            handler.onCallHangup(event);

            assertStatusParamUsed("NO_ANSWER");
            assertStatusParamNeverUsed("COMPLETED");
        }

        @Test
        @DisplayName("callOutcome='NO-ANSWER' (uppercase) powinien wywołać handleNoAnswer (case-insensitive)")
        void noAnswerUppercaseShouldTriggerHandleNoAnswer() {
            CallEvent event = buildHangupEvent("NO-ANSWER");

            handler.onCallHangup(event);

            assertStatusParamUsed("NO_ANSWER");
        }
    }

    // =========================================================================
    // Scenariusze completed / canceled / failed / null → COMPLETED
    // =========================================================================

    @Nested
    @DisplayName("callOutcome powodujące status COMPLETED")
    class CompletedOutcomes {

        @Test
        @DisplayName("callOutcome='completed' powinien zapisać status COMPLETED")
        void completedOutcomeShouldUpdateToCompleted() {
            CallEvent event = buildHangupEvent("completed");

            handler.onCallHangup(event);

            assertStatusParamUsed("COMPLETED");
            assertStatusParamNeverUsed("NO_ANSWER");
        }

        @Test
        @DisplayName("callOutcome=null powinien zapisać status COMPLETED (graceful fallback)")
        void nullOutcomeShouldUpdateToCompleted() {
            CallEvent event = buildHangupEvent(null);

            handler.onCallHangup(event);

            assertStatusParamUsed("COMPLETED");
            assertStatusParamNeverUsed("NO_ANSWER");
        }

        @Test
        @DisplayName("callOutcome='canceled' powinien zapisać status COMPLETED")
        void canceledOutcomeShouldUpdateToCompleted() {
            CallEvent event = buildHangupEvent("canceled");

            handler.onCallHangup(event);

            assertStatusParamUsed("COMPLETED");
            assertStatusParamNeverUsed("NO_ANSWER");
        }

        @Test
        @DisplayName("callOutcome='failed' powinien zapisać status COMPLETED")
        void failedOutcomeShouldUpdateToCompleted() {
            CallEvent event = buildHangupEvent("failed");

            handler.onCallHangup(event);

            assertStatusParamUsed("COMPLETED");
            assertStatusParamNeverUsed("NO_ANSWER");
        }
    }

    // =========================================================================
    // BE-064: handleCallbackDisposition – status CALLBACK, campaignContactRecordId
    // =========================================================================

    @Nested
    @DisplayName("handleCallbackDisposition – status CALLBACK i powiązanie z rekordem kampanii (BE-064)")
    class HandleCallbackDisposition {

        private static final Instant SCHEDULED_AT = Instant.parse("2026-05-10T10:00:00Z");

        @BeforeEach
        void stubSave() {
            when(scheduledCallbackRepository.save(any(ScheduledCallback.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("status campaign_contact powinien być CALLBACK, nie COMPLETED")
        void shouldSetCallbackStatusNotCompleted() {
            handler.handleCallbackDisposition(TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID,
                    "+48123456789", "Jan", "Kowalski", SCHEDULED_AT, null);

            assertStatusParamUsed("CALLBACK");
            assertStatusParamNeverUsed("COMPLETED");
        }

        @Test
        @DisplayName("next_attempt_at powinien być ustawiony na scheduledAt")
        void shouldSetNextAttemptAtToScheduledAt() {
            handler.handleCallbackDisposition(TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID,
                    "+48123456789", "Jan", "Kowalski", SCHEDULED_AT, null);

            ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate, atLeastOnce()).update(anyString(), captor.capture());

            boolean hasTimestamp = captor.getAllValues().stream()
                    .flatMap(java.util.Arrays::stream)
                    .anyMatch(arg -> arg instanceof java.sql.Timestamp ts
                            && ts.toInstant().equals(SCHEDULED_AT));
            assertThat(hasTimestamp)
                    .as("next_attempt_at powinien być równy scheduledAt=%s", SCHEDULED_AT)
                    .isTrue();
        }

        @Test
        @DisplayName("ScheduledCallback powinien mieć campaignContactRecordId = recordId")
        void savedCallbackShouldHaveCampaignContactRecordId() {
            ArgumentCaptor<ScheduledCallback> captor = ArgumentCaptor.forClass(ScheduledCallback.class);

            handler.handleCallbackDisposition(TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID,
                    "+48123456789", "Jan", "Kowalski", SCHEDULED_AT, null);

            verify(scheduledCallbackRepository).save(captor.capture());
            assertThat(captor.getValue().getCampaignContactRecordId()).isEqualTo(RECORD_ID);
        }

        @Test
        @DisplayName("attempt_count nie powinien być inkrementowany (brak kolumny attempt_count w UPDATE)")
        void shouldNotIncrementAttemptCount() {
            handler.handleCallbackDisposition(TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID,
                    "+48123456789", "Jan", "Kowalski", SCHEDULED_AT, null);

            boolean attemptCountModified = Mockito.mockingDetails(jdbcTemplate).getInvocations().stream()
                    .filter(inv -> inv.getMethod().getName().equals("update"))
                    .anyMatch(inv -> {
                        Object[] args = inv.getArguments();
                        if (args.length > 0 && args[0] instanceof String sql) {
                            return sql.contains("attempt_count");
                        }
                        return false;
                    });
            assertThat(attemptCountModified)
                    .as("attempt_count nie powinien być modyfikowany przez handleCallbackDisposition")
                    .isFalse();
        }
    }

    // =========================================================================
    // BE-063: handleNoAnswer – retry vs NOT_REACHED
    // =========================================================================

    @Nested
    @DisplayName("handleNoAnswer – logika retry i NOT_REACHED (BE-063)")
    class HandleNoAnswerLogic {

        @Test
        @DisplayName("attempt_count < max_attempts → status NO_ANSWER z next_attempt_at")
        void belowMaxAttemptsShouldScheduleRetry() {
            // attempt_count = 1, maxAttempts = 3 → retry
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

            handler.handleNoAnswer(CALL_SID, TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID, 3, 60);

            assertStatusParamUsed("NO_ANSWER");
            assertStatusParamNeverUsed("NOT_REACHED");
            assertStatusParamNeverUsed("FAILED");
        }

        @Test
        @DisplayName("attempt_count >= max_attempts → status NOT_REACHED (finalny)")
        void atMaxAttemptsShouldSetNotReached() {
            // attempt_count = 3, maxAttempts = 3 → wyczerpano
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(3);

            handler.handleNoAnswer(CALL_SID, TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID, 3, 60);

            assertStatusParamUsed("NOT_REACHED");
            assertStatusParamNeverUsed("NO_ANSWER");
            assertStatusParamNeverUsed("FAILED");
        }

        @Test
        @DisplayName("status FAILED nie jest nigdy używany")
        void failedStatusShouldNeverBeUsed() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(3);

            handler.handleNoAnswer(CALL_SID, TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID, 3, 60);

            assertStatusParamNeverUsed("FAILED");
        }
    }

    // =========================================================================
    // BE-066: handleNoAnswer – obsługa callback attempt
    // =========================================================================

    @Nested
    @DisplayName("handleNoAnswer – obsługa callback attempt (BE-066)")
    class HandleNoAnswerCallbackAttempt {

        @Test
        @DisplayName("dialer:callback-attempt:{callSid} istnieje → status NO_ANSWER bez odczytu attempt_count, marker usunięty")
        void callbackAttemptMarkerPresent_shouldScheduleNoAnswerWithoutAttemptCount() {
            // given – klucz callback-attempt istnieje (callback kampanijny)
            when(redisTemplate.hasKey("dialer:callback-attempt:" + CALL_SID)).thenReturn(Boolean.TRUE);

            // when
            handler.handleNoAnswer(CALL_SID, TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID, 3, 60);

            // then – status NO_ANSWER ustawiony
            assertStatusParamUsed("NO_ANSWER");
            assertStatusParamNeverUsed("NOT_REACHED");

            // attempt_count NIE jest odczytywany (brak zapytania SELECT attempt_count)
            verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Integer.class), any(Object[].class));

            // Marker callback-attempt usunięty (może być 1-2 razy: raz jawnie w handleNoAnswer,
            // raz przez cleanupRedisKeys – obie ścieżki są poprawne, Redis delete nieistniejącego klucza jest no-op)
            verify(redisTemplate, atLeastOnce()).delete("dialer:callback-attempt:" + CALL_SID);
        }

        @Test
        @DisplayName("dialer:callback-attempt:{callSid} NIE istnieje → normalny flow (attempt_count odczytany)")
        void callbackAttemptMarkerAbsent_shouldUseNormalFlow() {
            // given – klucz nie istnieje (normalny dialer call)
            when(redisTemplate.hasKey("dialer:callback-attempt:" + CALL_SID)).thenReturn(Boolean.FALSE);
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

            // when
            handler.handleNoAnswer(CALL_SID, TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID, 3, 60);

            // then – attempt_count odczytany (normalny flow)
            verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), any(Object[].class));
            assertStatusParamUsed("NO_ANSWER");
            assertStatusParamNeverUsed("NOT_REACHED");
        }

        @Test
        @DisplayName("dialer:callback-attempt:{callSid} NIE istnieje + attempt_count >= maxAttempts → NOT_REACHED")
        void callbackAttemptMarkerAbsent_atMaxAttempts_shouldSetNotReached() {
            // given – normalny flow, wyczerpano próby
            when(redisTemplate.hasKey("dialer:callback-attempt:" + CALL_SID)).thenReturn(Boolean.FALSE);
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(3);

            // when
            handler.handleNoAnswer(CALL_SID, TENANT_ID, CAMPAIGN_ID, RECORD_ID, AGENT_ID, 3, 60);

            // then – normalny flow: NOT_REACHED gdy wyczerpano próby
            assertStatusParamUsed("NOT_REACHED");
            assertStatusParamNeverUsed("NO_ANSWER");
        }
    }

    // =========================================================================
    // Scenariusz braku klucza Redis (nie jest połączenie dialera)
    // =========================================================================

    @Nested
    @DisplayName("Połączenia nierozpoznane przez dialer")
    class NonDialerCalls {

        @Test
        @DisplayName("brak klucza Redis → event powinien być ignorowany (brak aktualizacji DB)")
        void missingRedisKeyShouldBeIgnored() {
            when(valueOps.get("dialer:call:" + CALL_SID)).thenReturn(null);
            CallEvent event = buildHangupEvent("completed");

            handler.onCallHangup(event);

            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        }
    }

    // =========================================================================
    // Helper – asercje na parametry SQL
    // =========================================================================

    /**
     * Weryfikuje że jdbcTemplate.update() był wywołany z podanym statusem jako pierwszym parametrem.
     * Przechwytuje inwokacje przez Mockito API.
     */
    private void assertStatusParamUsed(String expectedStatus) {
        boolean found = Mockito.mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("update"))
                .anyMatch(inv -> {
                    Object[] args = inv.getArguments();
                    // update(String sql, Object... params) -> args[0]=sql, args[1]=params array
                    // Mockito 5 unpackuje varargs: args[0]=sql, args[1..n]=params
                    for (int i = 1; i < args.length; i++) {
                        if (expectedStatus.equals(args[i])) return true;
                    }
                    return false;
                });
        assertThat(found)
                .as("Oczekiwano wywołania jdbcTemplate.update() z parametrem statusu '%s'", expectedStatus)
                .isTrue();
    }

    /**
     * Weryfikuje że jdbcTemplate.update() NIE był wywołany z podanym statusem jako parametrem.
     */
    private void assertStatusParamNeverUsed(String unexpectedStatus) {
        boolean found = Mockito.mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("update"))
                .anyMatch(inv -> {
                    Object[] args = inv.getArguments();
                    for (int i = 1; i < args.length; i++) {
                        if (unexpectedStatus.equals(args[i])) return true;
                    }
                    return false;
                });
        assertThat(found)
                .as("Nie oczekiwano wywołania jdbcTemplate.update() z parametrem statusu '%s'", unexpectedStatus)
                .isFalse();
    }

    // =========================================================================
    // Helper – budowanie eventów
    // =========================================================================

    private CallEvent buildHangupEvent(String callOutcome) {
        return CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_HANGUP)
                .callId(CALL_SID)
                .tenantId(TENANT_ID)
                .agentId(AGENT_ID)
                .callOutcome(callOutcome)
                .build();
    }
}
