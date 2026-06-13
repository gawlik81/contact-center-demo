package com.contactcenter.domain.service;

import com.contactcenter.domain.ivr.*;
import com.contactcenter.domain.model.IvrTree;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.contact.ContactRepository;
import com.contactcenter.domain.repository.IvrAudioRepository;
import com.contactcenter.domain.repository.IvrTreeRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.contact.ContactEventService;
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
    @Mock private QueueRepository queueRepository;
    @Mock private ContactRepository contactRepository;
    @Mock private TelephonyAdapter telephonyAdapter;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private TaskScheduler taskScheduler;
    @Mock private VoicebotClient voicebotClient;
    @Mock private ContactEventService contactEventService;

    private IvrEngineService ivrEngineService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ivrEngineService = new IvrEngineService(
                ivrTreeRepository,
                ivrAudioRepository,
                queueRepository,
                contactRepository,
                telephonyAdapter,
                rabbitTemplate,
                stringRedisTemplate,
                taskScheduler,
                objectMapper,
                contactEventService
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
            IvrEngineService spyService = spy(ivrEngineService);

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
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
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
        }
    }
}
