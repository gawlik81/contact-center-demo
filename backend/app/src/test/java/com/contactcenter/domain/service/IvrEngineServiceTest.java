package com.contactcenter.domain.service;

import com.contactcenter.domain.ivr.*;
import com.contactcenter.domain.model.IvrAudio;
import com.contactcenter.domain.model.IvrTree;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.repository.IvrAudioRepository;
import com.contactcenter.domain.repository.IvrTreeRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link IvrEngineService}.
 *
 * <p>Weryfikuje logikę wykonywania węzłów IVR, zarządzania sesją w Redis
 * i obsługi fallback.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IvrEngineService – silnik IVR")
class IvrEngineServiceTest {

    private static final UUID TENANT_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID IVR_ID     = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID QUEUE_ID   = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AUDIO_ID   = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String CALL_ID  = "call-abc-123";
    private static final String NODE_ENTRY = "node-entry";
    private static final String NODE_MENU  = "node-menu";
    private static final String NODE_AUDIO = "node-audio";
    private static final String NODE_QUEUE = "node-queue";
    private static final String NODE_HANGUP = "node-hangup";

    @Mock private IvrTreeRepository ivrTreeRepository;
    @Mock private IvrAudioRepository ivrAudioRepository;
    @Mock private QueueRepository queueRepository;
    @Mock private TelephonyAdapter telephonyAdapter;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private TaskScheduler taskScheduler;

    private IvrEngineService ivrEngineService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ivrEngineService = new IvrEngineService(
                ivrTreeRepository,
                ivrAudioRepository,
                queueRepository,
                telephonyAdapter,
                rabbitTemplate,
                stringRedisTemplate,
                taskScheduler,
                objectMapper
        );
        // Domyślny mock dla ValueOperations
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // =========================================================================
    // startIvrSession
    // =========================================================================

    @Nested
    @DisplayName("startIvrSession")
    class StartIvrSession {

        @Test
        @DisplayName("powinien wykonać węzeł wejściowy po załadowaniu aktywnego drzewa IVR")
        void startIvrSession_shouldExecuteEntryNode() {
            IvrTree ivr = buildIvrTree(buildPlayAudioDefinition());
            when(ivrTreeRepository.findActiveByTenantId(TENANT_ID)).thenReturn(Optional.of(ivr));

            ivrEngineService.startIvrSession(CALL_ID, TENANT_ID, null);

            // Weryfikuj że sesja została zapisana w Redis
            verify(valueOps).set(eq("ivr:session:" + CALL_ID), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("powinien wywołać fallback gdy brak aktywnego drzewa IVR")
        void startIvrSession_withNoActiveIvr_shouldFallbackToDefaultQueue() {
            when(ivrTreeRepository.findActiveByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(queueRepository.findAllByTenantId(eq(TENANT_ID), isNull(), eq(0), eq(1)))
                    .thenReturn(buildQueuePage(buildQueue()));

            ivrEngineService.startIvrSession(CALL_ID, TENANT_ID, null);

            // Fallback powinien opublikować ContactQueuedMessage
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS),
                    eq("contact.queued"),
                    any(ContactQueuedMessage.class)
            );
        }

        @Test
        @DisplayName("powinien używać konkretnego ivrId gdy podano")
        void startIvrSession_shouldUseSpecificIvrId() {
            IvrTree ivr = buildIvrTree(buildPlayAudioDefinition());
            when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID)).thenReturn(Optional.of(ivr));

            ivrEngineService.startIvrSession(CALL_ID, TENANT_ID, IVR_ID);

            verify(ivrTreeRepository).findByIvrIdAndTenantId(IVR_ID, TENANT_ID);
            verify(ivrTreeRepository, never()).findActiveByTenantId(any());
        }
    }

    // =========================================================================
    // handleDtmfInput
    // =========================================================================

    @Nested
    @DisplayName("handleDtmfInput")
    class HandleDtmfInput {

        @Test
        @DisplayName("powinien przejść do następnego węzła po naciśnięciu klawisza '1'")
        void handleDtmfInput_shouldNavigateToNextNode() throws Exception {
            // Sesja IVR w Redis wskazuje na węzeł MENU
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_MENU, TENANT_ID);
            String sessionJson = objectMapper.writeValueAsString(session);

            IvrTree ivr = buildIvrTree(buildMenuDefinition());
            // Konfiguracja: pierwsze get("ivr:session:...") zwraca sesję, pozostałe null (dla TTS itp.)
            when(valueOps.get(anyString())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                if (key.equals("ivr:session:" + CALL_ID)) return sessionJson;
                return null;
            });
            when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID)).thenReturn(Optional.of(ivr));

            ivrEngineService.handleDtmfInput(CALL_ID, "1");

            // Sesja powinna być zaktualizowana z nowym węzłem
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, atLeastOnce()).set(
                    eq("ivr:session:" + CALL_ID), jsonCaptor.capture(), any(Duration.class));

            // Zweryfikuj że currentNodeId zmienił się na węzeł po klawiszu "1"
            IvrSessionData updatedSession = objectMapper.readValue(
                    jsonCaptor.getAllValues().get(jsonCaptor.getAllValues().size() - 1),
                    IvrSessionData.class);
            assertThat(updatedSession.getCurrentNodeId()).isEqualTo(NODE_AUDIO);
        }

        @Test
        @DisplayName("powinien przejść do gałęzi 'timeout' po timeoucie")
        void handleDtmfInput_timeout_shouldNavigateToTimeoutBranch() throws Exception {
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_MENU, TENANT_ID);
            String sessionJson = objectMapper.writeValueAsString(session);

            IvrTree ivr = buildIvrTree(buildMenuWithTimeoutDefinition());
            when(valueOps.get(anyString())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                if (key.equals("ivr:session:" + CALL_ID)) return sessionJson;
                return null;
            });
            when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID)).thenReturn(Optional.of(ivr));
            // HANGUP node will call telephonyAdapter
            doNothing().when(telephonyAdapter).hangupCall(anyString());

            ivrEngineService.handleDtmfInput(CALL_ID, "timeout");

            // Powinien wykonać węzeł HANGUP (po "timeout" idziemy do hangup w tym drzewie)
            verify(telephonyAdapter).hangupCall(CALL_ID);
        }

        @Test
        @DisplayName("nieznany klawisz po wyczerpaniu maxRetries powinien wywołać fallback")
        void handleDtmfInput_unknownKey_shouldRetryOrFallback() throws Exception {
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_MENU, TENANT_ID);
            session.setRetryCount(2); // maxRetries = 3, więc następna próba = fallback
            String sessionJson = objectMapper.writeValueAsString(session);

            IvrTree ivr = buildIvrTree(buildMenuDefinition()); // maxRetries = 3
            when(valueOps.get(anyString())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                if (key.equals("ivr:session:" + CALL_ID)) return sessionJson;
                return null;
            });
            when(ivrTreeRepository.findByIvrIdAndTenantId(IVR_ID, TENANT_ID)).thenReturn(Optional.of(ivr));
            when(queueRepository.findAllByTenantId(eq(TENANT_ID), isNull(), eq(0), eq(1)))
                    .thenReturn(buildQueuePage(buildQueue()));

            ivrEngineService.handleDtmfInput(CALL_ID, "9"); // nieznany klawisz

            // Fallback: opublikuj ContactQueuedMessage
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS),
                    eq("contact.queued"),
                    any(ContactQueuedMessage.class)
            );
        }
    }

    // =========================================================================
    // executeNode – QUEUE_TRANSFER
    // =========================================================================

    @Nested
    @DisplayName("executeNode – QUEUE_TRANSFER")
    class ExecuteNodeQueueTransfer {

        @Test
        @DisplayName("powinien opublikować ContactQueuedMessage i usunąć sesję")
        void executeNode_QUEUE_TRANSFER_shouldPublishContactQueuedMessage() throws Exception {
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_QUEUE, TENANT_ID);

            IvrNode queueNode = new IvrNode(NODE_QUEUE, IvrNodeType.QUEUE_TRANSFER,
                    null, null, List.of(), QUEUE_ID.toString(), 10, 3);

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));

            ivrEngineService.executeNode(CALL_ID, queueNode, session);

            ArgumentCaptor<ContactQueuedMessage> messageCaptor =
                    ArgumentCaptor.forClass(ContactQueuedMessage.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS),
                    eq("contact.queued"),
                    messageCaptor.capture()
            );

            ContactQueuedMessage msg = messageCaptor.getValue();
            assertThat(msg.queueId()).isEqualTo(QUEUE_ID);
            assertThat(msg.tenantId()).isEqualTo(TENANT_ID);
            assertThat(msg.contactId()).isNotNull();

            // Sesja powinna być usunięta z Redis
            verify(stringRedisTemplate).delete("ivr:session:" + CALL_ID);
        }

        @Test
        @DisplayName("brak kolejki powinien wywołać fallback")
        void executeNode_QUEUE_TRANSFER_withMissingQueue_shouldFallback() {
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_QUEUE, TENANT_ID);

            IvrNode queueNode = new IvrNode(NODE_QUEUE, IvrNodeType.QUEUE_TRANSFER,
                    null, null, List.of(), QUEUE_ID.toString(), 10, 3);

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.empty());
            when(queueRepository.findAllByTenantId(eq(TENANT_ID), isNull(), eq(0), eq(1)))
                    .thenReturn(buildQueuePage(buildQueue()));

            ivrEngineService.executeNode(CALL_ID, queueNode, session);

            // Fallback powinien być wywołany
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS),
                    eq("contact.queued"),
                    any(ContactQueuedMessage.class)
            );
        }
    }

    // =========================================================================
    // executeNode – HANGUP
    // =========================================================================

    @Nested
    @DisplayName("executeNode – HANGUP")
    class ExecuteNodeHangup {

        @Test
        @DisplayName("powinien wywołać hangup i usunąć sesję IVR z Redis")
        void executeNode_HANGUP_shouldCallHangupAndCleanSession() {
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_HANGUP, TENANT_ID);
            IvrNode hangupNode = new IvrNode(NODE_HANGUP, IvrNodeType.HANGUP,
                    null, null, List.of(), null, 10, 3);

            doNothing().when(telephonyAdapter).hangupCall(CALL_ID);

            ivrEngineService.executeNode(CALL_ID, hangupNode, session);

            verify(telephonyAdapter).hangupCall(CALL_ID);
            verify(stringRedisTemplate).delete("ivr:session:" + CALL_ID);
        }

        @Test
        @DisplayName("błąd hangup nie powinien przerywać usuwania sesji")
        void executeNode_HANGUP_withHangupError_shouldStillCleanSession() {
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_HANGUP, TENANT_ID);
            IvrNode hangupNode = new IvrNode(NODE_HANGUP, IvrNodeType.HANGUP,
                    null, null, List.of(), null, 10, 3);

            doThrow(new TelephonyAdapter.TelephonyException(CALL_ID, "Connection error"))
                    .when(telephonyAdapter).hangupCall(CALL_ID);

            // Nie powinien rzucać wyjątku
            assertThatCode(() -> ivrEngineService.executeNode(CALL_ID, hangupNode, session))
                    .doesNotThrowAnyException();

            // Sesja powinna być usunięta mimo błędu hangup
            verify(stringRedisTemplate).delete("ivr:session:" + CALL_ID);
        }
    }

    // =========================================================================
    // executeNode – PLAY_AUDIO
    // =========================================================================

    @Nested
    @DisplayName("executeNode – PLAY_AUDIO")
    class ExecuteNodePlayAudio {

        @Test
        @DisplayName("powinien zalogować odtwarzanie audio z URL z S3")
        void executeNode_PLAY_AUDIO_shouldLogAudioPlay() {
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_AUDIO, TENANT_ID);
            IvrNode audioNode = new IvrNode(NODE_AUDIO, IvrNodeType.PLAY_AUDIO,
                    "Witamy w systemie", AUDIO_ID.toString(), List.of(), null, 10, 3);

            IvrAudio audio = IvrAudio.builder()
                    .audioId(AUDIO_ID)
                    .tenantId(TENANT_ID)
                    .filename("welcome.mp3")
                    .s3Url("https://s3.example.com/tenant/welcome.mp3")
                    .audioType("UPLOADED")
                    .build();

            when(ivrAudioRepository.findByAudioIdAndTenantId(AUDIO_ID, TENANT_ID))
                    .thenReturn(Optional.of(audio));

            // Nie powinien rzucać wyjątku i nie powinien odpytywać kolejki
            assertThatCode(() -> ivrEngineService.executeNode(CALL_ID, audioNode, session))
                    .doesNotThrowAnyException();

            verify(ivrAudioRepository).findByAudioIdAndTenantId(AUDIO_ID, TENANT_ID);
        }

        @Test
        @DisplayName("brak audioId powinien używać tekstu promptu jako fallback")
        void executeNode_PLAY_AUDIO_withoutAudioId_shouldUseTextPrompt() {
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_AUDIO, TENANT_ID);
            IvrNode audioNode = new IvrNode(NODE_AUDIO, IvrNodeType.PLAY_AUDIO,
                    "Witamy w systemie", null, List.of(), null, 10, 3);

            assertThatCode(() -> ivrEngineService.executeNode(CALL_ID, audioNode, session))
                    .doesNotThrowAnyException();

            // Nie powinien odpytywać ivrAudioRepository
            verify(ivrAudioRepository, never()).findByAudioIdAndTenantId(any(), any());
        }
    }

    // =========================================================================
    // executeNode – błąd węzła
    // =========================================================================

    @Nested
    @DisplayName("executeNode – obsługa błędów")
    class ExecuteNodeErrors {

        @Test
        @DisplayName("nieistniejący nextNodeId w QUEUE_TRANSFER powinien wywołać fallback")
        void executeNode_withInvalidNextNodeId_shouldFallbackToDefaultQueue() {
            IvrSessionData session = new IvrSessionData(CALL_ID, IVR_ID, NODE_QUEUE, TENANT_ID);

            // queueId z nieprawidłowym formatem UUID
            IvrNode queueNode = new IvrNode(NODE_QUEUE, IvrNodeType.QUEUE_TRANSFER,
                    null, null, List.of(), "invalid-uuid", 10, 3);

            when(queueRepository.findAllByTenantId(eq(TENANT_ID), isNull(), eq(0), eq(1)))
                    .thenReturn(buildQueuePage(buildQueue()));

            ivrEngineService.executeNode(CALL_ID, queueNode, session);

            // Fallback: ContactQueuedMessage do domyślnej kolejki
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS),
                    eq("contact.queued"),
                    any(ContactQueuedMessage.class)
            );
        }
    }

    // =========================================================================
    // TTS cache
    // =========================================================================

    @Nested
    @DisplayName("TTS caching")
    class TtsCaching {

        @Test
        @DisplayName("powinien cache'ować wygenerowane audio TTS i zwracać cached URL")
        void ttsCaching_shouldCacheGeneratedAudio() {
            String ttsText = "Witamy w systemie Contact Center";

            // Cache miss – brak wartości w Redis
            when(valueOps.get(startsWith("ivr:tts:"))).thenReturn(null);

            String url1 = ivrEngineService.resolveTtsUrl(ttsText, AUDIO_ID.toString());

            // Powinien zapisać do cache
            verify(valueOps).set(startsWith("ivr:tts:"), eq("mock-tts-url"), any(Duration.class));
            assertThat(url1).isEqualTo("mock-tts-url");
        }

        @Test
        @DisplayName("cache hit powinien zwracać cached URL bez generacji")
        void ttsCaching_cacheHit_shouldReturnCachedUrl() {
            String ttsText = "Witamy w systemie";
            String cachedUrl = "https://s3.example.com/tts/cached.mp3";

            when(valueOps.get(startsWith("ivr:tts:"))).thenReturn(cachedUrl);

            String result = ivrEngineService.resolveTtsUrl(ttsText, AUDIO_ID.toString());

            assertThat(result).isEqualTo(cachedUrl);
            // Nie powinien zapisywać do cache przy hit
            verify(valueOps, never()).set(anyString(), eq("mock-tts-url"), any(Duration.class));
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private IvrTree buildIvrTree(IvrDefinition definition) {
        return IvrTree.builder()
                .ivrId(IVR_ID)
                .tenantId(TENANT_ID)
                .name("Test IVR")
                .definition(definition)
                .version(1)
                .active(true)
                .build();
    }

    /** Definicja z węzłem PLAY_AUDIO jako entry. */
    private IvrDefinition buildPlayAudioDefinition() {
        IvrNode audioNode = new IvrNode(NODE_ENTRY, IvrNodeType.PLAY_AUDIO,
                "Witamy", null, List.of(), null, 10, 3);
        return new IvrDefinition(List.of(audioNode), NODE_ENTRY);
    }

    /** Definicja MENU z opcją '1' → node-audio i timeout → node-hangup. */
    private IvrDefinition buildMenuDefinition() {
        IvrNode hangupNode = new IvrNode(NODE_HANGUP, IvrNodeType.HANGUP,
                null, null, List.of(), null, 10, 3);
        IvrNode audioNode = new IvrNode(NODE_AUDIO, IvrNodeType.PLAY_AUDIO,
                "Wybrano opcję 1", null, List.of(), null, 10, 3);
        IvrNode menuNode = new IvrNode(NODE_MENU, IvrNodeType.MENU,
                "Naciśnij 1", null,
                List.of(new IvrOption("1", NODE_AUDIO), new IvrOption("timeout", NODE_HANGUP)),
                null, 10, 3);
        return new IvrDefinition(List.of(menuNode, audioNode, hangupNode), NODE_MENU);
    }

    /** Definicja MENU z opcją timeout → node-hangup. */
    private IvrDefinition buildMenuWithTimeoutDefinition() {
        IvrNode hangupNode = new IvrNode(NODE_HANGUP, IvrNodeType.HANGUP,
                null, null, List.of(), null, 10, 3);
        IvrNode menuNode = new IvrNode(NODE_MENU, IvrNodeType.MENU,
                "Naciśnij klawisz", null,
                List.of(new IvrOption("timeout", NODE_HANGUP)),
                null, 10, 3);
        return new IvrDefinition(List.of(menuNode, hangupNode), NODE_MENU);
    }

    private Queue buildQueue() {
        return Queue.builder()
                .queueId(QUEUE_ID)
                .tenantId(TENANT_ID)
                .name("Domyślna kolejka")
                .routingStrategy("ROUND_ROBIN")
                .active(true)
                .build();
    }

    @SuppressWarnings("unchecked")
    private com.contactcenter.api.PagedResponse<Queue> buildQueuePage(Queue queue) {
        return new com.contactcenter.api.PagedResponse<>(
                List.of(queue), 0, 1, 1L, 1, true, true);
    }
}
