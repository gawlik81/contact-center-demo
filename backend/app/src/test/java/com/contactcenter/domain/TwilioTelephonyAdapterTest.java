package com.contactcenter.domain;

import com.contactcenter.domain.event.TwilioConfigChangedEvent;
import com.contactcenter.domain.model.Customer;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.CustomerRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.domain.service.ContactEventService;
import com.contactcenter.domain.service.TenantTwilioConfigDecrypted;
import com.contactcenter.domain.service.TenantTwilioConfigService;
import com.contactcenter.domain.service.TwilioRecordingDownloadService;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.telephony.TelephonyEventPublisher;
import com.contactcenter.domain.telephony.TwilioTelephonyAdapter;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.CallCreator;
import com.twilio.rest.api.v2010.account.CallUpdater;
import com.twilio.rest.api.v2010.account.Conference;
import com.twilio.rest.api.v2010.account.ConferenceReader;
import com.twilio.rest.api.v2010.account.conference.Participant;
import com.twilio.rest.api.v2010.account.conference.ParticipantUpdater;
import com.twilio.base.ResourceSet;
import com.twilio.type.Twiml;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link TwilioTelephonyAdapter}.
 *
 * <p>Twilio SDK jest mockowany przez {@link MockedStatic} – żadne prawdziwe wywołania
 * API nie są wykonywane. Testy weryfikują logikę mapowania statusów, zarządzanie
 * sesjami i publikację eventów.
 *
 * <p>Adapter jest konstruowany ręcznie (bez Spring kontekstu) z mockowanymi
 * zależnościami, żeby uniknąć uruchamiania {@link TwilioTelephonyAdapter#init()},
 * który wywołuje {@code Twilio.init()} wymagające połączenia z API.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TwilioTelephonyAdapter – zarządzanie sesjami i mapowanie statusów")
class TwilioTelephonyAdapterTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_ID    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONTACT_ID  = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String FROM      = "+48123456789";
    private static final String TO        = "+48987654321";
    private static final String CALL_SID  = "CA1234567890abcdef1234567890abcdef";
    private static final String AGENT_CALL_SID = "CAagent1234567890abcdef1234567890";

    @Mock
    private TelephonyEventPublisher eventPublisher;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TwilioRecordingDownloadService recordingDownloadService;

    @Mock
    private TenantTwilioConfigService tenantTwilioConfigService;

    @Mock
    private ContactEventService contactEventService;

    private TwilioProperties twilioProperties;
    private TwilioTelephonyAdapter adapter;

    /**
     * In-memory backing store symulujący Redis.
     * Współdzielony między setSession / getSession mockowanymi przez ValueOperations.
     */
    private final Map<String, Object> redisStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        twilioProperties = new TwilioProperties();
        twilioProperties.setEnabled(true);
        twilioProperties.setAccountSid("ACtest12345678901234567890123456");
        twilioProperties.setAuthToken("testAuthToken12345678901234567890");
        twilioProperties.setPhoneNumber("+48111000111");
        twilioProperties.setStatusCallbackUrl("https://example.com/api/telephony/webhook/twilio");

        // Domyślnie tenantRepository nie zwraca per-tenant konfiguracji
        when(tenantRepository.findById(any())).thenReturn(Optional.empty());

        // Domyślnie brak per-tenant konfiguracji Twilio (fallback do globalnej)
        when(tenantTwilioConfigService.getDecryptedConfig(any())).thenReturn(Optional.empty());

        // Mock RedisTemplate z in-memory HashMap jako backing store.
        // Adapter korzysta wyłącznie z opsForValue().set/get i delete(),
        // więc symulujemy tylko te operacje.
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), any(), any(Duration.class));

        when(valueOps.get(anyString()))
                .thenAnswer(inv -> redisStore.get(inv.getArgument(0)));

        doAnswer(inv -> {
            redisStore.remove(inv.getArgument(0));
            return true;
        }).when(redisTemplate).delete(anyString());

        redisStore.clear();

        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> stringValueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
        when(stringValueOps.get(anyString())).thenReturn(null);

        // Tworzymy adapter ręcznie i wywołujemy init() – @PostConstruct nie inicjalizuje Caffeine cache przez SDK,
        // więc clientCache musi być ustawiony przed testami korzystającymi z resolveRestClient().
        adapter = new TwilioTelephonyAdapter(twilioProperties, eventPublisher,
                contactRepository, customerRepository, tenantRepository,
                redisTemplate, stringRedisTemplate, recordingDownloadService,
                tenantTwilioConfigService, contactEventService);
        // init() inicjalizuje Caffeine cache bez wywoływania Twilio.init() (usunięte w BE-058)
        // oraz bez konfigurowania statusCallbacków (tenantRepository zwraca pustą listę)
        adapter.init();
    }

    /**
     * Wstrzykuje gotową sesję bezpośrednio do backing store (in-memory HashMap symulujący Redis).
     * Używane gdy test potrzebuje sesji z polami, których handleWebhookStatusUpdate() nie ustawia
     * (np. contactId, agentCallSid). Klucz musi odpowiadać SESSION_KEY_PREFIX w adapterze.
     */
    private void putSessionIntoRedis(CallSession session) {
        redisStore.put("call-session:" + session.getCallId(), session);
    }

    /**
     * Tworzy ConferenceReader mock, który zwraca jedną konferencję z danym SID.
     * Przy każdym wywołaniu read() zwracany jest ResourceSet z nowym iteratorem,
     * dzięki czemu mock działa poprawnie przy wielokrotnych wywołaniach (hold + unhold).
     */
    @SuppressWarnings("unchecked")
    private ConferenceReader mockConferenceReader(String conferenceSid) {
        Conference mockConference = mock(Conference.class);
        when(mockConference.getSid()).thenReturn(conferenceSid);

        // ResourceSet.iterator() musi zwracać świeży iterator przy każdym wywołaniu,
        // bo po wyczerpaniu iteratora kolejne wywołanie for-each nie znajdzie elementów.
        ResourceSet<Conference> resourceSet = mock(ResourceSet.class);
        when(resourceSet.iterator()).thenAnswer(inv -> List.of(mockConference).iterator());

        ConferenceReader mockReader = mock(ConferenceReader.class);
        when(mockReader.setFriendlyName(anyString())).thenReturn(mockReader);
        when(mockReader.setStatus(any())).thenReturn(mockReader);
        when(mockReader.read(any(com.twilio.http.TwilioRestClient.class))).thenReturn(resourceSet);
        return mockReader;
    }

    // =========================================================================
    // mapTwilioStatus
    // =========================================================================

    @Nested
    @DisplayName("mapTwilioStatus() – mapowanie statusów Twilio")
    class MapTwilioStatus {

        @Test
        @DisplayName("'queued' powinien mapować na RINGING")
        void queuedShouldMapToRinging() {
            assertThat(adapter.mapTwilioStatus("queued"))
                    .isEqualTo(CallSession.CallStatus.RINGING);
        }

        @Test
        @DisplayName("'initiated' powinien mapować na RINGING")
        void initiatedShouldMapToRinging() {
            assertThat(adapter.mapTwilioStatus("initiated"))
                    .isEqualTo(CallSession.CallStatus.RINGING);
        }

        @Test
        @DisplayName("'ringing' powinien mapować na RINGING")
        void ringingShouldMapToRinging() {
            assertThat(adapter.mapTwilioStatus("ringing"))
                    .isEqualTo(CallSession.CallStatus.RINGING);
        }

        @Test
        @DisplayName("'in-progress' powinien mapować na ACTIVE")
        void inProgressShouldMapToActive() {
            assertThat(adapter.mapTwilioStatus("in-progress"))
                    .isEqualTo(CallSession.CallStatus.ACTIVE);
        }

        @Test
        @DisplayName("'completed' powinien mapować na ENDED")
        void completedShouldMapToEnded() {
            assertThat(adapter.mapTwilioStatus("completed"))
                    .isEqualTo(CallSession.CallStatus.ENDED);
        }

        @Test
        @DisplayName("'busy' powinien mapować na ENDED")
        void busyShouldMapToEnded() {
            assertThat(adapter.mapTwilioStatus("busy"))
                    .isEqualTo(CallSession.CallStatus.ENDED);
        }

        @Test
        @DisplayName("'failed' powinien mapować na ENDED")
        void failedShouldMapToEnded() {
            assertThat(adapter.mapTwilioStatus("failed"))
                    .isEqualTo(CallSession.CallStatus.ENDED);
        }

        @Test
        @DisplayName("'no-answer' powinien mapować na ENDED")
        void noAnswerShouldMapToEnded() {
            assertThat(adapter.mapTwilioStatus("no-answer"))
                    .isEqualTo(CallSession.CallStatus.ENDED);
        }

        @Test
        @DisplayName("'canceled' powinien mapować na ENDED")
        void canceledShouldMapToEnded() {
            assertThat(adapter.mapTwilioStatus("canceled"))
                    .isEqualTo(CallSession.CallStatus.ENDED);
        }

        @Test
        @DisplayName("null powinien mapować na RINGING (defensywne)")
        void nullShouldMapToRinging() {
            assertThat(adapter.mapTwilioStatus(null))
                    .isEqualTo(CallSession.CallStatus.RINGING);
        }

        @Test
        @DisplayName("nieznany status powinien mapować na RINGING z ostrzeżeniem")
        void unknownStatusShouldMapToRinging() {
            assertThat(adapter.mapTwilioStatus("unknown-status"))
                    .isEqualTo(CallSession.CallStatus.RINGING);
        }

        @Test
        @DisplayName("mapowanie powinno być case-insensitive (COMPLETED → ENDED)")
        void mappingShouldBeCaseInsensitive() {
            assertThat(adapter.mapTwilioStatus("COMPLETED"))
                    .isEqualTo(CallSession.CallStatus.ENDED);
            assertThat(adapter.mapTwilioStatus("IN-PROGRESS"))
                    .isEqualTo(CallSession.CallStatus.ACTIVE);
        }
    }

    // =========================================================================
    // answerCall
    // =========================================================================

    @Nested
    @DisplayName("answerCall()")
    class AnswerCall {

        @Test
        @DisplayName("powinien przejść sesję do stanu ACTIVE i opublikować event CALL_ANSWERED")
        void shouldTransitionToActiveAndPublishEvent() {
            // Arrange: wstaw sesję ręcznie przez webhook handler
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");

            // Act
            adapter.answerCall(CALL_SID, null);

            // Assert
            CallSession session = adapter.getCallSession(CALL_SID);
            assertThat(session.getStatus()).isEqualTo(CallSession.CallStatus.ACTIVE);
            assertThat(session.getAnsweredAt()).isNotNull();

            verify(eventPublisher).publishAnswered(CALL_SID, TENANT_ID, null, FROM, TO);
        }

        @Test
        @DisplayName("wywołanie na zakończonej sesji powinno rzucić TelephonyException")
        void shouldThrowForEndedCall() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "completed", TENANT_ID, "inbound");

            assertThatThrownBy(() -> adapter.answerCall(CALL_SID, null))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class)
                    .hasMessageContaining(CALL_SID);
        }

        @Test
        @DisplayName("wywołanie na już aktywnej sesji powinno być idempotentne")
        void shouldBeIdempotentForActiveCall() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "in-progress", TENANT_ID, "inbound");
            clearInvocations(eventPublisher);

            assertThatNoException().isThrownBy(() -> adapter.answerCall(CALL_SID, null));
            // Nie publikujemy dublowanego eventu CALL_ANSWERED
            verify(eventPublisher, never()).publishAnswered(anyString(), any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("wywołanie na nieistniejącej sesji powinno rzucić TelephonyException")
        void shouldThrowForUnknownCallId() {
            assertThatThrownBy(() -> adapter.answerCall("NIEISTNIEJACY_SID", null))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // hangupCall
    // =========================================================================

    @Nested
    @DisplayName("hangupCall()")
    class HangupCall {

        @Test
        @DisplayName("powinien zaktualizować stan sesji na ENDED i opublikować event CALL_HANGUP")
        void shouldTransitionToEndedAndPublishHangupEvent() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "in-progress", TENANT_ID, "inbound");

            try (MockedStatic<Call> mockedCall = mockStatic(Call.class)) {
                CallUpdater mockUpdater = mock(CallUpdater.class);
                when(mockUpdater.setStatus(any())).thenReturn(mockUpdater);
                when(mockUpdater.update(any(com.twilio.http.TwilioRestClient.class))).thenReturn(mock(Call.class));
                mockedCall.when(() -> Call.updater(CALL_SID)).thenReturn(mockUpdater);

                adapter.hangupCall(CALL_SID);
            }

            CallSession session = adapter.getCallSession(CALL_SID);
            assertThat(session.getStatus()).isEqualTo(CallSession.CallStatus.ENDED);
            assertThat(session.getEndedAt()).isNotNull();

            verify(eventPublisher).publishHangup(eq(CALL_SID), any(), eq(TENANT_ID), any(), eq(FROM), eq(TO), eq("completed"));
        }

        @Test
        @DisplayName("wywołanie na już zakończonej sesji powinno być idempotentne")
        void shouldBeIdempotentForEndedCall() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "completed", TENANT_ID, "inbound");
            clearInvocations(eventPublisher);

            assertThatNoException().isThrownBy(() -> adapter.hangupCall(CALL_SID));
            verify(eventPublisher, never()).publishHangup(anyString(), any(), any(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("błąd 404 od Twilio powinien być traktowany idempotentnie")
        void shouldHandleTwilio404Idempotently() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "in-progress", TENANT_ID, "inbound");

            try (MockedStatic<Call> mockedCall = mockStatic(Call.class)) {
                CallUpdater mockUpdater = mock(CallUpdater.class);
                when(mockUpdater.setStatus(any())).thenReturn(mockUpdater);
                ApiException notFound = new ApiException("Call not found", 20404, null, 404, null);
                when(mockUpdater.update(any(com.twilio.http.TwilioRestClient.class))).thenThrow(notFound);
                mockedCall.when(() -> Call.updater(CALL_SID)).thenReturn(mockUpdater);

                // Nie rzuca wyjątku – traktuje 404 jako "już zakończone"
                assertThatNoException().isThrownBy(() -> adapter.hangupCall(CALL_SID));
            }
        }

        @Test
        @DisplayName("błąd 5xx od Twilio powinien rzucić TelephonyException")
        void shouldThrowForTwilioServerError() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "in-progress", TENANT_ID, "inbound");

            try (MockedStatic<Call> mockedCall = mockStatic(Call.class)) {
                CallUpdater mockUpdater = mock(CallUpdater.class);
                when(mockUpdater.setStatus(any())).thenReturn(mockUpdater);
                ApiException serverError = new ApiException("Internal Server Error", 0, null, 500, null);
                when(mockUpdater.update(any(com.twilio.http.TwilioRestClient.class))).thenThrow(serverError);
                mockedCall.when(() -> Call.updater(CALL_SID)).thenReturn(mockUpdater);

                assertThatThrownBy(() -> adapter.hangupCall(CALL_SID))
                        .isInstanceOf(TelephonyAdapter.TelephonyException.class)
                        .hasMessageContaining("Twilio");
            }
        }

        @Test
        @DisplayName("wywołanie na nieistniejącej sesji powinno rzucić TelephonyException")
        void shouldThrowForUnknownCallId() {
            assertThatThrownBy(() -> adapter.hangupCall("NIEISTNIEJACY_SID"))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // holdCall
    // =========================================================================

    @Nested
    @DisplayName("holdCall()")
    class HoldCall {

        private static final String CONFERENCE_SID = "CFtest1234567890abcdef1234567890ab";

        /** Sesja ACTIVE z wymaganym contactId (potrzebne przez holdCall do budowy nazwy konferencji). */
        private CallSession activeSessionWithContact() {
            return CallSession.builder()
                    .callId(CALL_SID)
                    .tenantId(TENANT_ID)
                    .from(FROM)
                    .to(TO)
                    .status(CallSession.CallStatus.ACTIVE)
                    .contactId(CONTACT_ID)
                    .startedAt(java.time.Instant.now())
                    .answeredAt(java.time.Instant.now())
                    .build();
        }

        @Test
        @DisplayName("hold=true powinien przejść ACTIVE → ON_HOLD")
        void holdShouldTransitionActiveToOnHold() {
            putSessionIntoRedis(activeSessionWithContact());

            // Tworzymy mockowane zależności PRZED otwarciem MockedStatic,
            // żeby uniknąć konfliktu Mockito przy stubbowaniu wewnątrz bloku static mock.
            ConferenceReader mockReader = mockConferenceReader(CONFERENCE_SID);
            ParticipantUpdater mockParticipantUpdater = mock(ParticipantUpdater.class);
            when(mockParticipantUpdater.setHold(anyBoolean())).thenReturn(mockParticipantUpdater);
            when(mockParticipantUpdater.update(any(com.twilio.http.TwilioRestClient.class)))
                    .thenReturn(mock(Participant.class));

            try (MockedStatic<Conference> mockedConference = mockStatic(Conference.class);
                 MockedStatic<Participant> mockedParticipant = mockStatic(Participant.class)) {

                mockedConference.when(Conference::reader).thenReturn(mockReader);
                mockedParticipant.when(() -> Participant.updater(CONFERENCE_SID, CALL_SID))
                        .thenReturn(mockParticipantUpdater);

                adapter.holdCall(CALL_SID, true);
            }

            assertThat(adapter.getCallSession(CALL_SID).getStatus())
                    .isEqualTo(CallSession.CallStatus.ON_HOLD);
        }

        @Test
        @DisplayName("hold=false powinien przejść ON_HOLD → ACTIVE")
        void unholdShouldTransitionOnHoldToActive() {
            putSessionIntoRedis(activeSessionWithContact());

            ConferenceReader mockReader = mockConferenceReader(CONFERENCE_SID);
            ParticipantUpdater mockParticipantUpdater = mock(ParticipantUpdater.class);
            when(mockParticipantUpdater.setHold(anyBoolean())).thenReturn(mockParticipantUpdater);
            when(mockParticipantUpdater.update(any(com.twilio.http.TwilioRestClient.class)))
                    .thenReturn(mock(Participant.class));

            try (MockedStatic<Conference> mockedConference = mockStatic(Conference.class);
                 MockedStatic<Participant> mockedParticipant = mockStatic(Participant.class)) {

                mockedConference.when(Conference::reader).thenReturn(mockReader);
                mockedParticipant.when(() -> Participant.updater(eq(CONFERENCE_SID), anyString()))
                        .thenReturn(mockParticipantUpdater);

                adapter.holdCall(CALL_SID, true);  // → ON_HOLD
                adapter.holdCall(CALL_SID, false); // → ACTIVE
            }

            assertThat(adapter.getCallSession(CALL_SID).getStatus())
                    .isEqualTo(CallSession.CallStatus.ACTIVE);
        }

        @Test
        @DisplayName("hold=true na sesji ON_HOLD powinien rzucić TelephonyException")
        void holdOnAlreadyHeldCallShouldThrow() {
            putSessionIntoRedis(activeSessionWithContact());

            ConferenceReader mockReader = mockConferenceReader(CONFERENCE_SID);
            ParticipantUpdater mockParticipantUpdater = mock(ParticipantUpdater.class);
            when(mockParticipantUpdater.setHold(anyBoolean())).thenReturn(mockParticipantUpdater);
            when(mockParticipantUpdater.update(any(com.twilio.http.TwilioRestClient.class)))
                    .thenReturn(mock(Participant.class));

            try (MockedStatic<Conference> mockedConference = mockStatic(Conference.class);
                 MockedStatic<Participant> mockedParticipant = mockStatic(Participant.class)) {

                mockedConference.when(Conference::reader).thenReturn(mockReader);
                mockedParticipant.when(() -> Participant.updater(CONFERENCE_SID, CALL_SID))
                        .thenReturn(mockParticipantUpdater);

                adapter.holdCall(CALL_SID, true); // → ON_HOLD

                // Drugi hold=true na sesji ON_HOLD powinien rzucić – stan sesji nie odpowiada oczekiwanemu ACTIVE
                assertThatThrownBy(() -> adapter.holdCall(CALL_SID, true))
                        .isInstanceOf(TelephonyAdapter.TelephonyException.class);
            }
        }

        @Test
        @DisplayName("hold na nieaktywnym połączeniu powinien rzucić TelephonyException")
        void holdOnRingingCallShouldThrow() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");

            assertThatThrownBy(() -> adapter.holdCall(CALL_SID, true))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // muteCall
    // =========================================================================

    @Nested
    @DisplayName("muteCall()")
    class MuteCall {

        private static final String CONFERENCE_SID = "CFmute1234567890abcdef1234567890ab";

        /** Sesja ACTIVE z wymaganymi polami: contactId (nazwa konferencji) i agentCallSid (uczestnik). */
        private CallSession activeSessionWithContactAndAgent() {
            return CallSession.builder()
                    .callId(CALL_SID)
                    .tenantId(TENANT_ID)
                    .from(FROM)
                    .to(TO)
                    .status(CallSession.CallStatus.ACTIVE)
                    .contactId(CONTACT_ID)
                    .agentCallSid(AGENT_CALL_SID)
                    .startedAt(java.time.Instant.now())
                    .answeredAt(java.time.Instant.now())
                    .build();
        }

        @Test
        @DisplayName("mute na aktywnym połączeniu nie powinien rzucić wyjątku")
        void muteShouldNotThrowForActiveCall() {
            putSessionIntoRedis(activeSessionWithContactAndAgent());

            // Tworzymy mockowane zależności PRZED otwarciem MockedStatic.
            ConferenceReader mockReader = mockConferenceReader(CONFERENCE_SID);
            ParticipantUpdater mockParticipantUpdater = mock(ParticipantUpdater.class);
            when(mockParticipantUpdater.setMuted(anyBoolean())).thenReturn(mockParticipantUpdater);
            when(mockParticipantUpdater.update(any(com.twilio.http.TwilioRestClient.class)))
                    .thenReturn(mock(Participant.class));

            try (MockedStatic<Conference> mockedConference = mockStatic(Conference.class);
                 MockedStatic<Participant> mockedParticipant = mockStatic(Participant.class)) {

                mockedConference.when(Conference::reader).thenReturn(mockReader);
                mockedParticipant.when(() -> Participant.updater(CONFERENCE_SID, AGENT_CALL_SID))
                        .thenReturn(mockParticipantUpdater);

                assertThatNoException().isThrownBy(() -> adapter.muteCall(CALL_SID, true));
            }
        }

        @Test
        @DisplayName("mute na zakończonym połączeniu powinien rzucić TelephonyException")
        void muteShouldThrowForEndedCall() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "completed", TENANT_ID, "inbound");

            assertThatThrownBy(() -> adapter.muteCall(CALL_SID, true))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // handleWebhookStatusUpdate
    // =========================================================================

    @Nested
    @DisplayName("handleWebhookStatusUpdate()")
    class HandleWebhookStatusUpdate {

        @Test
        @DisplayName("nowe połączenie przychodzące powinno tworzyć sesję")
        void newCallShouldCreateSession() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");

            CallSession session = adapter.getCallSession(CALL_SID);
            assertThat(session).isNotNull();
            assertThat(session.getCallId()).isEqualTo(CALL_SID);
            assertThat(session.getFrom()).isEqualTo(FROM);
            assertThat(session.getTo()).isEqualTo(TO);
            assertThat(session.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(session.getStatus()).isEqualTo(CallSession.CallStatus.RINGING);
        }

        @Test
        @DisplayName("zmiana statusu na 'in-progress' powinna opublikować event CALL_ANSWERED")
        void inProgressShouldPublishAnsweredEvent() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");
            // Reset weryfikacji z poprzedniego eventu CALL_INCOMING
            clearInvocations(eventPublisher);

            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "in-progress", TENANT_ID, "inbound");

            verify(eventPublisher).publishAnswered(CALL_SID, TENANT_ID, null, FROM, TO);
        }

        @Test
        @DisplayName("zmiana statusu na 'completed' powinna opublikować event CALL_HANGUP")
        void completedShouldPublishHangupEvent() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");
            clearInvocations(eventPublisher);

            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "completed", TENANT_ID, "inbound");

            verify(eventPublisher).publishHangup(eq(CALL_SID), any(), eq(TENANT_ID), any(), eq(FROM), eq(TO), eq("completed"));
        }

        @Test
        @DisplayName("zmiana statusu na 'ringing' powinna opublikować event CALL_INCOMING")
        void ringingShouldPublishIncomingEvent() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");

            verify(eventPublisher).publishIncoming(eq(CALL_SID), any(), eq(TENANT_ID), any(), eq(FROM), eq(TO));
        }

        @Test
        @DisplayName("aktualizacja istniejącej sesji powinna zmieniać stan bez tworzenia nowej sesji")
        void updateExistingSessionShouldModifyState() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "in-progress", TENANT_ID, "inbound");

            assertThat(adapter.getCallSession(CALL_SID).getStatus())
                    .isEqualTo(CallSession.CallStatus.ACTIVE);
            // Redis backing store powinien mieć dokładnie 1 klucz (sesja zaktualizowana in-place)
            assertThat(redisStore).hasSize(1);
        }

        @Test
        @DisplayName("zmiana na 'in-progress' powinna ustawić answeredAt")
        void inProgressShouldSetAnsweredAt() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "in-progress", TENANT_ID, "inbound");

            assertThat(adapter.getCallSession(CALL_SID).getAnsweredAt()).isNotNull();
        }

        @Test
        @DisplayName("zmiana na 'completed' powinna ustawić endedAt")
        void completedShouldSetEndedAt() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "completed", TENANT_ID, "inbound");

            assertThat(adapter.getCallSession(CALL_SID).getEndedAt()).isNotNull();
        }
    }

    // =========================================================================
    // bridgeCalls
    // =========================================================================

    @Nested
    @DisplayName("bridgeCalls()")
    class BridgeCalls {

        private static final String CALL_SID_2 = "CB9876543210fedcba9876543210fedcba";

        @Test
        @DisplayName("powinien oznaczyć callId1 jako TRANSFERRED i callId2 jako ACTIVE")
        void shouldTransferFirstAndActivateSecond() {
            adapter.handleWebhookStatusUpdate(CALL_SID,   FROM, TO, "in-progress", TENANT_ID, "inbound");
            adapter.handleWebhookStatusUpdate(CALL_SID_2, TO, "+48111222333", "in-progress", TENANT_ID, "inbound");

            try (MockedStatic<Call> mockedCall = mockStatic(Call.class)) {
                CallUpdater mockUpdater = mock(CallUpdater.class);
                when(mockUpdater.setStatus(any())).thenReturn(mockUpdater);
                when(mockUpdater.update(any(com.twilio.http.TwilioRestClient.class))).thenReturn(mock(Call.class));
                mockedCall.when(() -> Call.updater(CALL_SID)).thenReturn(mockUpdater);

                adapter.bridgeCalls(CALL_SID, CALL_SID_2);
            }

            assertThat(adapter.getCallSession(CALL_SID).getStatus())
                    .isEqualTo(CallSession.CallStatus.TRANSFERRED);
            assertThat(adapter.getCallSession(CALL_SID_2).getStatus())
                    .isEqualTo(CallSession.CallStatus.ACTIVE);
        }

        @Test
        @DisplayName("bridge z nieistniejącym callId powinien rzucić TelephonyException")
        void shouldThrowForUnknownCallId() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "in-progress", TENANT_ID, "inbound");

            assertThatThrownBy(() -> adapter.bridgeCalls(CALL_SID, "NIEISTNIEJACY"))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }

        @Test
        @DisplayName("bridge zakończonej sesji powinien rzucić TelephonyException")
        void shouldThrowForEndedSession() {
            adapter.handleWebhookStatusUpdate(CALL_SID,   FROM, TO, "in-progress", TENANT_ID, "inbound");
            adapter.handleWebhookStatusUpdate(CALL_SID_2, TO, "+48111", "completed",   TENANT_ID, "inbound");

            assertThatThrownBy(() -> adapter.bridgeCalls(CALL_SID, CALL_SID_2))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // getCallSession
    // =========================================================================

    @Nested
    @DisplayName("getCallSession()")
    class GetCallSession {

        @Test
        @DisplayName("powinien zwrócić sesję dla istniejącego callId")
        void shouldReturnSessionForExistingCallId() {
            adapter.handleWebhookStatusUpdate(CALL_SID, FROM, TO, "ringing", TENANT_ID, "inbound");

            CallSession session = adapter.getCallSession(CALL_SID);
            assertThat(session.getCallId()).isEqualTo(CALL_SID);
        }

        @Test
        @DisplayName("powinien rzucić TelephonyException dla nieznanego callId")
        void shouldThrowForUnknownCallId() {
            assertThatThrownBy(() -> adapter.getCallSession("NIEZNANY"))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // getActiveSessionCount
    // =========================================================================

    @Nested
    @DisplayName("getActiveSessionCount()")
    class ActiveSessionCount {

        @Test
        @DisplayName("po migracji na Redis zwraca -1 (skanowanie keyspace wyłączone ze względu na wydajność)")
        void shouldReturnMinusOneAfterRedisMigration() {
            adapter.handleWebhookStatusUpdate("CA001", FROM, TO, "ringing",     TENANT_ID, "inbound");
            adapter.handleWebhookStatusUpdate("CA002", FROM, TO, "in-progress", TENANT_ID, "inbound");

            // getActiveSessionCount() zwraca -1 po migracji na Redis.
            // Skanowanie wszystkich kluczy call-session:* byłoby operacją O(N) na keyspace.
            assertThat(adapter.getActiveSessionCount()).isEqualTo(-1);
        }
    }

    // =========================================================================
    // initiateCall – walidacja konfiguracji
    // =========================================================================

    @Nested
    @DisplayName("initiateCall() – walidacja konfiguracji")
    class InitiateCallValidation {

        @Test
        @DisplayName("brak phoneNumber powinien rzucić TelephonyException")
        void shouldThrowWhenPhoneNumberMissing() {
            twilioProperties.setPhoneNumber("");

            assertThatThrownBy(() -> adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID, null, null))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class)
                    .hasMessageContaining("twilio.phone-number");
        }

        @Test
        @DisplayName("błąd Twilio API powinien być opakowany w TelephonyException")
        void shouldWrapTwilioApiExceptionInTelephonyException() {
            try (MockedStatic<Call> mockedCall = mockStatic(Call.class)) {
                CallCreator mockCreator = mock(CallCreator.class);
                when(mockCreator.setStatusCallback(any(java.net.URI.class))).thenReturn(mockCreator);
                when(mockCreator.setStatusCallbackMethod(any())).thenReturn(mockCreator);
                when(mockCreator.setStatusCallbackEvent(anyList())).thenReturn(mockCreator);
                ApiException apiError = new ApiException("Authentication failed", 20003, null, 401, null);
                // BE-058: adapter wywołuje create(TwilioRestClient), nie create()
                when(mockCreator.create(any(com.twilio.http.TwilioRestClient.class))).thenThrow(apiError);

                mockedCall.when(() -> Call.creator(any(), any(), (Twiml) any()))
                        .thenReturn(mockCreator);

                assertThatThrownBy(() -> adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID, null, null))
                        .isInstanceOf(TelephonyAdapter.TelephonyException.class)
                        .hasMessageContaining("Twilio");
            }
        }
    }

    // =========================================================================
    // resolvePhoneNumber – logika per-tenant > fallback globalny
    // =========================================================================

    @Nested
    @DisplayName("resolvePhoneNumber() – priorytet per-tenant nad globalnym")
    class ResolvePhoneNumber {

        private static final String PER_TENANT_NUMBER = "+48222333444";
        private static final String GLOBAL_NUMBER     = "+48111000111";

        @Test
        @DisplayName("tenant z per-tenant numerem → zwraca per-tenant numer")
        void shouldReturnPerTenantNumberWhenConfigured() {
            Tenant tenant = mock(Tenant.class);
            when(tenant.getTwilioPhoneNumber()).thenReturn(PER_TENANT_NUMBER);
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

            String result = adapter.resolvePhoneNumber(TENANT_ID);

            assertThat(result).isEqualTo(PER_TENANT_NUMBER);
        }

        @Test
        @DisplayName("tenant bez per-tenant numeru → zwraca globalny fallback")
        void shouldFallbackToGlobalNumberWhenPerTenantNotConfigured() {
            Tenant tenant = mock(Tenant.class);
            when(tenant.getTwilioPhoneNumber()).thenReturn(null);
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
            twilioProperties.setPhoneNumber(GLOBAL_NUMBER);

            String result = adapter.resolvePhoneNumber(TENANT_ID);

            assertThat(result).isEqualTo(GLOBAL_NUMBER);
        }

        @Test
        @DisplayName("tenant nieznaleziony → zwraca globalny fallback")
        void shouldFallbackToGlobalNumberWhenTenantNotFound() {
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());
            twilioProperties.setPhoneNumber(GLOBAL_NUMBER);

            String result = adapter.resolvePhoneNumber(TENANT_ID);

            assertThat(result).isEqualTo(GLOBAL_NUMBER);
        }

        @Test
        @DisplayName("brak per-tenant numeru i brak globalnego → rzuca TelephonyException")
        void shouldThrowWhenNoPhoneNumberAvailable() {
            Tenant tenant = mock(Tenant.class);
            when(tenant.getTwilioPhoneNumber()).thenReturn(null);
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
            twilioProperties.setPhoneNumber("");

            assertThatThrownBy(() -> adapter.resolvePhoneNumber(TENANT_ID))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class)
                    .hasMessageContaining("twilio.phone-number");
        }

        @Test
        @DisplayName("per-tenant numer ma wyższy priorytet niż globalny")
        void perTenantNumberTakesPrecedenceOverGlobal() {
            Tenant tenant = mock(Tenant.class);
            when(tenant.getTwilioPhoneNumber()).thenReturn(PER_TENANT_NUMBER);
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
            // globalny numer jest też ustawiony – powinien być ignorowany
            twilioProperties.setPhoneNumber(GLOBAL_NUMBER);

            String result = adapter.resolvePhoneNumber(TENANT_ID);

            assertThat(result).isEqualTo(PER_TENANT_NUMBER);
            assertThat(result).isNotEqualTo(GLOBAL_NUMBER);
        }

        @Test
        @DisplayName("null tenantId → pomija lookup i zwraca globalny fallback")
        void nullTenantIdShouldReturnGlobalFallback() {
            twilioProperties.setPhoneNumber(GLOBAL_NUMBER);

            String result = adapter.resolvePhoneNumber(null);

            assertThat(result).isEqualTo(GLOBAL_NUMBER);
            verify(tenantRepository, never()).findById(any());
        }
    }

    // =========================================================================
    // resolveAccountSid i onTwilioConfigChanged – per-tenant cache
    // =========================================================================

    @Nested
    @DisplayName("resolveAccountSid() i onTwilioConfigChanged() – per-tenant cache")
    class PerTenantClientCacheTests {

        @Test
        @DisplayName("resolveAccountSid() zwraca per-tenant accountSid gdy config istnieje")
        void resolveAccountSid_perTenantConfig_returnsPerTenantSid() {
            TenantTwilioConfigDecrypted config = new TenantTwilioConfigDecrypted(
                    "ACperTenant123456789012345678901234", "perTenantAuthToken",
                    null, null, null, null, null);
            when(tenantTwilioConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(config));

            String result = adapter.resolveAccountSid(TENANT_ID);

            assertThat(result).isEqualTo("ACperTenant123456789012345678901234");
        }

        @Test
        @DisplayName("resolveAccountSid() zwraca globalny accountSid gdy brak per-tenant config")
        void resolveAccountSid_noPerTenantConfig_returnsGlobal() {
            when(tenantTwilioConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.empty());

            String result = adapter.resolveAccountSid(TENANT_ID);

            assertThat(result).isEqualTo("ACtest12345678901234567890123456");
        }

        @Test
        @DisplayName("resolveAccountSid(null) zwraca globalny accountSid")
        void resolveAccountSid_nullTenantId_returnsGlobal() {
            String result = adapter.resolveAccountSid(null);
            assertThat(result).isEqualTo("ACtest12345678901234567890123456");
        }

        @Test
        @DisplayName("onTwilioConfigChanged() inwaliduje cache dla tenanta bez rzucania wyjątku")
        void onTwilioConfigChanged_invalidatesCache() {
            // Upewnij się że cache jest zainicjowany (init() wywołano w setUp)
            // onTwilioConfigChanged nie rzuca wyjątku nawet gdy wpis nie był w cache
            assertThatNoException().isThrownBy(() ->
                    adapter.onTwilioConfigChanged(new TwilioConfigChangedEvent(TENANT_ID)));
        }
    }
}
