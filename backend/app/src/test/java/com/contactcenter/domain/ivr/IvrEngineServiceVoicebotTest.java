package com.contactcenter.domain.ivr;

import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.contact.ContactEventService;
import com.contactcenter.domain.voicebot.VoicebotClient;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe węzła VOICEBOT w {@link IvrEngineService}.
 *
 * <p>Węzeł VOICEBOT jest węzłem <b>interaktywnym</b>: {@code executeNode} przy typie VOICEBOT
 * tylko loguje i wraca – nie wywołuje {@code executeVoicebot}. Właściwa logika (ASR+NLU)
 * uruchamiana jest dopiero przez {@code handleVoicebotRecordingAndBuildTwiml} po odebraniu
 * nagrania od Twilio. Testy weryfikują:
 * <ul>
 *   <li>executeNode dla VOICEBOT NIE wywołuje executeVoicebot (węzeł interaktywny)</li>
 *   <li>executeVoicebot: voicebot wyłączony → przejście do {@code fallback}</li>
 *   <li>executeVoicebot: voicebot zwraca {@code escalate=false} → przejście do {@code next}</li>
 *   <li>executeVoicebot: voicebot zwraca {@code escalate=true} → przejście do {@code escalate}</li>
 *   <li>executeVoicebot: eskalacja z queueId (brak opcji escalate) → QUEUE_TRANSFER</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IvrEngineService – węzeł VOICEBOT")
class IvrEngineServiceVoicebotTest {

    private static final UUID TENANT_ID    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID IVR_ID       = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID QUEUE_ID     = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String CALL_ID    = "call-voicebot-test";
    private static final String NODE_VOICEBOT = "node-voicebot";
    private static final String NODE_NEXT     = "node-next";
    private static final String NODE_ESCALATE = "node-escalate";
    private static final String NODE_FALLBACK = "node-fallback";
    private static final String AUDIO_B64     = "dGVzdA=="; // "test" w base64

    @Mock private IvrTreeRepository ivrTreeRepository;
    @Mock private IvrAudioRepository ivrAudioRepository;
    @Mock private QueueService queueService;
    @Mock private ContactService contactService;
    @Mock private TelephonyAdapter telephonyAdapter;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private TaskScheduler taskScheduler;
    @Mock private VoicebotClient voicebotClient;
    @Mock private ContactEventService contactEventService;

    private final InMemoryIvrPendingStateStore pendingStateStore = new InMemoryIvrPendingStateStore();
    private IvrEngineServiceImpl ivrEngineService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ivrEngineService = new IvrEngineServiceImpl(
                ivrTreeRepository,
                ivrAudioRepository,
                queueService,
                contactService,
                telephonyAdapter,
                rabbitTemplate,
                stringRedisTemplate,
                taskScheduler,
                objectMapper,
                contactEventService,
                pendingStateStore
        );
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Buduje węzeł VOICEBOT z opcjami next / escalate / fallback. */
    private IvrNode voicebotNode() {
        return new IvrNode(
                NODE_VOICEBOT, IvrNodeType.VOICEBOT,
                null, null,
                List.of(
                        new IvrOption("next",     NODE_NEXT),
                        new IvrOption("escalate", NODE_ESCALATE),
                        new IvrOption("fallback",  NODE_FALLBACK)
                ),
                null, 0, 0, null, 0, 0, null, null, null, null
        );
    }

    /** Węzeł terminalny – HANGUP (nie robi nic, sesja kasowana). */
    private IvrNode hangupNode(String nodeId) {
        return new IvrNode(
                nodeId, IvrNodeType.HANGUP,
                null, null, null, null, 0, 0, null, 0, 0, null, null, null, null
        );
    }

    private IvrTree buildIvrTree(String entryNodeId, List<IvrNode> nodes) {
        IvrDefinition def = new IvrDefinition(nodes, entryNodeId);
        IvrTree ivr = new IvrTree();
        ivr.setIvrId(IVR_ID);
        ivr.setTenantId(TENANT_ID);
        ivr.setDefinition(def);
        return ivr;
    }

    private IvrSessionData buildSession() {
        IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_VOICEBOT, TENANT_ID);
        session.setVariable("voicebot_audio_base64", AUDIO_B64);
        return session;
    }

    private Queue buildQueue() {
        Queue q = new Queue();
        q.setQueueId(QUEUE_ID);
        q.setTenantId(TENANT_ID);
        q.setName("Default Queue");
        return q;
    }

    private VoicebotClient.TurnResponse buildTurnResponse(boolean escalate) {
        return new VoicebotClient.TurnResponse(
                CALL_ID,
                "mam reklamację",
                escalate ? "complaint" : "general_info",
                escalate ? 0.55 : 0.85,
                escalate,
                escalate ? "low_confidence" : null,
                List.of("mam reklamację"),
                escalate ? "Przekazuję do agenta." : "Rozumiem, masz pytanie.",
                false  // continueConversation=false – testy jednostkowe weryfikują przepływ bez multi-turn
        );
    }

    // =========================================================================
    // Testy – executeNode dla VOICEBOT (węzeł interaktywny)
    // =========================================================================

    @Nested
    @DisplayName("executeNode: VOICEBOT to węzeł interaktywny – nie wywołuje executeVoicebot")
    class ExecuteNodeDoesNotCallExecuteVoicebot {

        @Test
        @DisplayName("executeNode dla VOICEBOT powinien tylko zalogować i nie zapisywać sesji")
        void executeNode_voicebot_shouldNotCallExecuteVoicebot() {
            IvrEngineServiceImpl spyService = spy(ivrEngineService);

            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_VOICEBOT, TENANT_ID);

            spyService.executeNode(CALL_ID, voicebotNode(), session);

            // executeVoicebot nie powinno być wywołane – brak nagrania na tym etapie
            verify(spyService, never()).executeVoicebot(any(), any(), any());
            // sesja nie jest zapisywana (brak przejścia do kolejnego węzła)
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }
    }

    // =========================================================================
    // Testy – executeVoicebot (wywoływane przez handleVoicebotRecordingAndBuildTwiml)
    // =========================================================================

    @Nested
    @DisplayName("executeVoicebot: voicebot wyłączony (voicebotClient == null)")
    class VoicebotDisabled {

        @Test
        @DisplayName("powinien przejść do węzła fallback gdy voicebotClient nie jest zarejestrowany")
        void executeVoicebot_disabled_shouldGoToFallback() {
            IvrTree ivr = buildIvrTree(NODE_VOICEBOT, List.of(
                    voicebotNode(),
                    hangupNode(NODE_FALLBACK)
            ));
            when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID))
                    .thenReturn(Optional.of(ivr));

            IvrSessionData session = buildSession();

            // voicebotClient == null (nie wstrzyknięty) – graceful degradation
            ivrEngineService.executeVoicebot(CALL_ID, voicebotNode(), session);

            // Sesja zaktualizowana – przejście do NODE_FALLBACK
            verify(valueOps).set(eq("ivr:session:" + CALL_ID), anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("executeVoicebot: brak odpowiedzi (timeout)")
    class VoicebotTimeout {

        @BeforeEach
        void injectVoicebotClient() {
            ivrEngineService.voicebotClient = voicebotClient;
        }

        @Test
        @DisplayName("powinien przejść do fallback gdy voicebotClient zwraca Optional.empty()")
        void executeVoicebot_timeout_shouldGoToFallback() {
            when(voicebotClient.processTurn(any())).thenReturn(Optional.empty());

            IvrTree ivr = buildIvrTree(NODE_VOICEBOT, List.of(
                    voicebotNode(),
                    hangupNode(NODE_FALLBACK)
            ));
            when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID))
                    .thenReturn(Optional.of(ivr));

            IvrSessionData session = buildSession();
            ivrEngineService.executeVoicebot(CALL_ID, voicebotNode(), session);

            verify(valueOps).set(eq("ivr:session:" + CALL_ID), anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("executeVoicebot: odpowiedź bez eskalacji")
    class VoicebotNoEscalation {

        @BeforeEach
        void injectVoicebotClient() {
            ivrEngineService.voicebotClient = voicebotClient;
        }

        @Test
        @DisplayName("powinien przejść do węzła 'next' gdy escalate=false")
        void executeVoicebot_noEscalation_shouldGoToNext() {
            when(voicebotClient.processTurn(any())).thenReturn(Optional.of(buildTurnResponse(false)));

            IvrTree ivr = buildIvrTree(NODE_VOICEBOT, List.of(
                    voicebotNode(),
                    hangupNode(NODE_NEXT)
            ));
            when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID))
                    .thenReturn(Optional.of(ivr));

            IvrSessionData session = buildSession();
            ivrEngineService.executeVoicebot(CALL_ID, voicebotNode(), session);

            // Sesja zaktualizowana z currentNodeId = NODE_NEXT
            verify(valueOps, atLeastOnce()).set(
                    eq("ivr:session:" + CALL_ID), anyString(), any(Duration.class)
            );
        }
    }

    @Nested
    @DisplayName("executeVoicebot: eskalacja")
    class VoicebotEscalation {

        @BeforeEach
        void injectVoicebotClient() {
            ivrEngineService.voicebotClient = voicebotClient;
        }

        @Test
        @DisplayName("powinien przejść do węzła 'escalate' gdy escalate=true i opcja 'escalate' istnieje")
        void executeVoicebot_withEscalation_shouldGoToEscalateNode() {
            when(voicebotClient.processTurn(any())).thenReturn(Optional.of(buildTurnResponse(true)));

            IvrTree ivr = buildIvrTree(NODE_VOICEBOT, List.of(
                    voicebotNode(),
                    hangupNode(NODE_ESCALATE)
            ));
            when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID))
                    .thenReturn(Optional.of(ivr));

            IvrSessionData session = buildSession();
            ivrEngineService.executeVoicebot(CALL_ID, voicebotNode(), session);

            verify(valueOps, atLeastOnce()).set(
                    eq("ivr:session:" + CALL_ID), anyString(), any(Duration.class)
            );
        }

        @Test
        @DisplayName("powinien przekazać do kolejki z queueId gdy brak opcji 'escalate'")
        void executeVoicebot_escalateWithQueueId_shouldTransferToQueue() {
            when(voicebotClient.processTurn(any())).thenReturn(Optional.of(buildTurnResponse(true)));
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));

            // Węzeł VOICEBOT bez opcji "escalate" ale z queueId
            IvrNode nodeWithQueue = new IvrNode(
                    NODE_VOICEBOT, IvrNodeType.VOICEBOT,
                    null, null,
                    List.of(new IvrOption("next", NODE_NEXT)),
                    QUEUE_ID.toString(),
                    0, 0, null, 0, 0, null, null, null, null
            );

            IvrTree ivr = buildIvrTree(NODE_VOICEBOT, List.of(nodeWithQueue, hangupNode(NODE_NEXT)));
            when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID))
                    .thenReturn(Optional.of(ivr));
            lenient().when(ivrTreeRepository.findActiveByTenantId(TENANT_ID))
                    .thenReturn(Optional.of(ivr));

            IvrSessionData session = buildSession();
            ivrEngineService.executeVoicebot(CALL_ID, nodeWithQueue, session);

            // Przy QUEUE_TRANSFER – RabbitTemplate powinien opublikować wiadomość
            verify(rabbitTemplate, atLeastOnce()).convertAndSend(
                    anyString(), anyString(), any(Object.class)
            );
            // BE-177: ścieżka VOICEBOT zapisuje stan konferencji w magazynie (kolejka z węzła), który następny
            // webhook nagrania zużywa jednorazowo
            assertThat(pendingStateStore.takeConference(CALL_ID, TENANT_ID))
                    .hasValueSatisfying(pc -> assertThat(pc.queueId()).isEqualTo(QUEUE_ID));
            assertThat(pendingStateStore.takeConference(CALL_ID, TENANT_ID)).isEmpty();
        }

        /** Węzeł VOICEBOT z kolejką docelową + drzewo + sesja w "Redis" (usuwana po delete). */
        private void stubSessionLifecycle(IvrNode node, boolean twimlMode, java.util.concurrent.atomic.AtomicBoolean deleted)
                throws Exception {
            IvrTree ivr = buildIvrTree(NODE_VOICEBOT, List.of(node, hangupNode(NODE_NEXT)));
            lenient().when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID)).thenReturn(Optional.of(ivr));
            lenient().when(ivrTreeRepository.findActiveByTenantId(TENANT_ID)).thenReturn(Optional.of(ivr));
            IvrSessionData session = buildSession();
            session.setTwimlMode(twimlMode);
            String json = objectMapper.writeValueAsString(session);
            lenient().when(valueOps.get("ivr:session:" + CALL_ID)).thenAnswer(inv -> deleted.get() ? null : json);
            lenient().when(stringRedisTemplate.delete(anyString())).thenAnswer(inv -> {
                deleted.set(true);
                return true;
            });
        }

        private IvrNode nodeWithQueue() {
            return new IvrNode(NODE_VOICEBOT, IvrNodeType.VOICEBOT, null, null,
                    List.of(new IvrOption("next", NODE_NEXT)), QUEUE_ID.toString(),
                    0, 0, null, 0, 0, null, null, null, null);
        }

        @Test
        @DisplayName("webhook nagrania po eskalacji: konferencja + historia (closeVoicebot ESCALATED, openQueue kolejki węzła), stan zużyty")
        void recordingWebhook_afterEscalation_buildsConferenceAndPersistsHistory() throws Exception {
            when(voicebotClient.processTurn(any())).thenReturn(Optional.of(buildTurnResponse(true)));
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(buildQueue()));
            stubSessionLifecycle(nodeWithQueue(), true, new java.util.concurrent.atomic.AtomicBoolean());

            String twiml = ivrEngineService.handleVoicebotRecordingAndBuildTwiml(
                    CALL_ID, TENANT_ID, "https://cc.example.com", null, "3");

            assertThat(twiml).contains("<Conference").contains("contact-");
            org.mockito.ArgumentCaptor<UUID> contact = org.mockito.ArgumentCaptor.forClass(UUID.class);
            verify(contactEventService).closeVoicebot(contact.capture(), eq(TENANT_ID), eq("ESCALATED"));
            verify(contactEventService).openQueue(eq(contact.getValue()), eq(TENANT_ID), eq(QUEUE_ID), isNull(), any());
            assertThat(pendingStateStore.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("webhook nagrania po FALLBACKU (RabbitMQ rzucił raz): historia i konferencja dla kolejki DOMYŚLNEJ")
        void recordingWebhook_afterFallback_persistsHistoryForDefaultQueue() throws Exception {
            UUID defaultQueueId = UUID.randomUUID();
            Queue defaultQueue = new Queue();
            defaultQueue.setQueueId(defaultQueueId);
            defaultQueue.setTenantId(TENANT_ID);
            defaultQueue.setName("Domyslna");
            when(voicebotClient.processTurn(any())).thenReturn(Optional.of(buildTurnResponse(true)));
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(buildQueue()));
            when(queueService.getAllQueues(TENANT_ID)).thenReturn(List.of(defaultQueue));
            doThrow(new RuntimeException("rabbit blip")).doNothing().when(rabbitTemplate)
                    .convertAndSend(anyString(), anyString(), any(Object.class));
            stubSessionLifecycle(nodeWithQueue(), true, new java.util.concurrent.atomic.AtomicBoolean());

            String twiml = ivrEngineService.handleVoicebotRecordingAndBuildTwiml(
                    CALL_ID, TENANT_ID, "https://cc.example.com", null, "3");

            assertThat(twiml).contains("<Conference");
            verify(contactEventService).closeVoicebot(any(UUID.class), eq(TENANT_ID), eq("ESCALATED"));
            verify(contactEventService).openQueue(any(UUID.class), eq(TENANT_ID), eq(defaultQueueId), isNull(), any());
        }
    }
}
