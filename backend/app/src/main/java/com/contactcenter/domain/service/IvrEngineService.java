package com.contactcenter.domain.service;

import com.contactcenter.domain.ivr.IvrDefinition;
import com.contactcenter.domain.ivr.IvrNode;
import com.contactcenter.domain.ivr.IvrOption;
import com.contactcenter.domain.ivr.IvrSessionData;
import com.contactcenter.domain.model.IvrAudio;
import com.contactcenter.domain.model.IvrTree;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.repository.IvrAudioRepository;
import com.contactcenter.domain.repository.IvrTreeRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * Silnik IVR – orkiestruje przepływ połączenia przez drzewo IVR.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Startowanie sesji IVR przy odebraniu przychodzącego połączenia</li>
 *   <li>Przetwarzanie wejścia DTMF od dzwoniącego</li>
 *   <li>Wykonywanie węzłów (PLAY_AUDIO, MENU, COLLECT_DTMF, QUEUE_TRANSFER, HANGUP)</li>
 *   <li>Cache'owanie audio TTS w Redis (klucz {@code ivr:tts:{md5}} TTL 24h)</li>
 *   <li>Zarządzanie sesją w Redis (klucz {@code ivr:session:{callId}} TTL 30min)</li>
 *   <li>Fallback na domyślną kolejkę przy błędzie węzła</li>
 * </ul>
 *
 * <p>Sesja IVR w Redis:
 * <ul>
 *   <li>Tworzona przy {@link #startIvrSession}</li>
 *   <li>Aktualizowana przy każdym przejściu do nowego węzła</li>
 *   <li>Usuwana przy HANGUP lub QUEUE_TRANSFER</li>
 *   <li>TTL: 30 minut (auto-expire dla porzuconych połączeń)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IvrEngineService {

    private static final String SESSION_KEY_PREFIX = "ivr:session:";
    private static final String TTS_CACHE_KEY_PREFIX = "ivr:tts:";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration TTS_TTL = Duration.ofHours(24);
    private static final String ROUTING_KEY_CONTACT_QUEUED = "contact.queued";

    private final IvrTreeRepository ivrTreeRepository;
    private final IvrAudioRepository ivrAudioRepository;
    private final QueueRepository queueRepository;
    private final TelephonyAdapter telephonyAdapter;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final TaskScheduler taskScheduler;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Publiczne API
    // =========================================================================

    /**
     * Startuje sesję IVR dla przychodzącego połączenia.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Pobiera aktywne drzewo IVR dla tenanta</li>
     *   <li>Tworzy sesję w Redis z węzłem wejściowym</li>
     *   <li>Wykonuje węzeł wejściowy</li>
     * </ol>
     *
     * <p>Gdy brak aktywnego drzewa IVR: wywołuje {@link #fallbackToDefaultQueue(String, UUID)}.
     *
     * @param callId   identyfikator sesji połączenia
     * @param tenantId UUID tenanta
     * @param ivrId    UUID konkretnego drzewa IVR (null = użyj aktywnego dla tenanta)
     */
    public void startIvrSession(String callId, UUID tenantId, UUID ivrId) {
        log.info("[IVR] Startowanie sesji IVR: callId={}, tenantId={}, ivrId={}", callId, tenantId, ivrId);

        try {
            // Pobierz drzewo IVR (konkretne lub aktywne)
            Optional<IvrTree> ivrOpt = resolveIvrTree(tenantId, ivrId);

            if (ivrOpt.isEmpty()) {
                log.warn("[IVR] Brak aktywnego drzewa IVR dla tenanta: tenantId={}", tenantId);
                fallbackToDefaultQueue(callId, tenantId);
                return;
            }

            IvrTree ivr = ivrOpt.get();
            IvrDefinition definition = ivr.getDefinition();

            if (definition == null || definition.entryNodeId() == null) {
                log.warn("[IVR] Nieprawidłowa definicja IVR: ivrId={}", ivr.getIvrId());
                fallbackToDefaultQueue(callId, tenantId);
                return;
            }

            // Utwórz sesję w Redis
            IvrSessionData session = new IvrSessionData(callId, ivr.getIvrId(),
                    definition.entryNodeId(), tenantId);
            saveSession(session);

            // Wykonaj węzeł wejściowy
            IvrNode entryNode = definition.findNode(definition.entryNodeId()).orElse(null);
            if (entryNode == null) {
                log.error("[IVR] Węzeł wejściowy nie istnieje: entryNodeId={}, ivrId={}",
                        definition.entryNodeId(), ivr.getIvrId());
                fallbackToDefaultQueue(callId, tenantId);
                return;
            }

            executeNode(callId, entryNode, session);

        } catch (Exception e) {
            log.error("[IVR] Błąd podczas startowania sesji IVR: callId={}, error={}", callId, e.getMessage(), e);
            fallbackToDefaultQueue(callId, tenantId);
        }
    }

    /**
     * Przetwarza wejście DTMF od dzwoniącego.
     *
     * <p>Rozróżnia dwa tryby pracy:
     * <ul>
     *   <li><b>COLLECT_DTMF</b> – gdy {@code session.getCollectingDtmfForNodeId() != null};
     *       akumuluje cyfry w buforze, kończy zbieranie po {@code finishOnKey} lub {@code maxDigits}</li>
     *   <li><b>MENU / domyślny</b> – szuka opcji w aktualnym węźle po pojedynczym klawiszu DTMF</li>
     * </ul>
     *
     * @param callId  identyfikator sesji połączenia
     * @param dtmfKey klawisz DTMF ("0"–"9", "*", "#", "timeout", "no-input")
     */
    public void handleDtmfInput(String callId, String dtmfKey) {
        log.info("[IVR] DTMF input: callId={}, key={}", callId, dtmfKey);

        try {
            IvrSessionData session = loadSession(callId);
            if (session == null) {
                log.warn("[IVR] Brak sesji IVR dla callId={}", callId);
                return;
            }

            IvrTree ivr = resolveIvrTree(session.getTenantId(), session.getIvrId())
                    .orElse(null);
            if (ivr == null || ivr.getDefinition() == null) {
                log.error("[IVR] Nie można załadować drzewa IVR dla sesji: callId={}, ivrId={}",
                        callId, session.getIvrId());
                fallbackToDefaultQueue(callId, session.getTenantId());
                return;
            }

            // ----------------------------------------------------------------
            // Tryb COLLECT_DTMF – sesja jest w trakcie zbierania cyfr
            // ----------------------------------------------------------------
            if (session.getCollectingDtmfForNodeId() != null) {
                handleCollectDtmfInput(callId, dtmfKey, session, ivr);
                return;
            }

            // ----------------------------------------------------------------
            // Tryb MENU (domyślny) – pojedynczy klawisz DTMF
            // ----------------------------------------------------------------
            IvrNode currentNode = ivr.getDefinition().findNode(session.getCurrentNodeId())
                    .orElse(null);
            if (currentNode == null) {
                log.error("[IVR] Aktualny węzeł nie istnieje: nodeId={}, callId={}",
                        session.getCurrentNodeId(), callId);
                fallbackToDefaultQueue(callId, session.getTenantId());
                return;
            }

            // Szukaj opcji dla klawisza DTMF
            IvrOption option = currentNode.findOption(dtmfKey);

            if (option == null) {
                // Nieznany klawisz – sprawdź liczbę prób
                session.incrementRetryCount();
                if (session.getRetryCount() >= currentNode.maxRetries()) {
                    log.warn("[IVR] Przekroczono liczbę prób dla węzła: nodeId={}, callId={}",
                            currentNode.nodeId(), callId);
                    fallbackToDefaultQueue(callId, session.getTenantId());
                } else {
                    log.debug("[IVR] Nieznany klawisz '{}' dla węzła: nodeId={}, retry={}",
                            dtmfKey, currentNode.nodeId(), session.getRetryCount());
                    saveSession(session);
                    // Ponów ten sam węzeł (odtwórz komunikat ponownie)
                    executeNode(callId, currentNode, session);
                }
                return;
            }

            transitionToNextNode(callId, option.nextNodeId(), session, ivr);

        } catch (Exception e) {
            log.error("[IVR] Błąd podczas przetwarzania DTMF: callId={}, key={}, error={}",
                    callId, dtmfKey, e.getMessage(), e);
            // Wczytaj tenantId z sesji jeśli to możliwe
            IvrSessionData session = loadSession(callId);
            UUID tenantId = session != null ? session.getTenantId() : null;
            if (tenantId != null) {
                fallbackToDefaultQueue(callId, tenantId);
            }
        }
    }

    /**
     * Obsługuje wejście DTMF w trybie zbierania cyfr (węzeł COLLECT_DTMF).
     *
     * <p>Możliwe scenariusze:
     * <ul>
     *   <li>"timeout" / "no-input" – szuka opcji o tym kluczu w węźle; jeśli brak → fallback</li>
     *   <li>klawisz równy {@code finishOnKey} – kończy zbieranie i przechodzi do opcji "success"</li>
     *   <li>cyfra – dodaje do bufora; po osiągnięciu {@code maxDigits} auto-zakończenie</li>
     * </ul>
     */
    private void handleCollectDtmfInput(String callId, String dtmfKey,
                                         IvrSessionData session, IvrTree ivr) {
        String collectNodeId = session.getCollectingDtmfForNodeId();
        IvrNode collectNode = ivr.getDefinition().findNode(collectNodeId).orElse(null);

        if (collectNode == null) {
            log.error("[IVR] COLLECT_DTMF: węzeł zbierania nie istnieje: nodeId={}, callId={}",
                    collectNodeId, callId);
            fallbackToDefaultQueue(callId, session.getTenantId());
            return;
        }

        // --- Obsługa timeout / no-input ---
        if ("timeout".equals(dtmfKey) || "no-input".equals(dtmfKey)) {
            IvrOption option = collectNode.findOption(dtmfKey);
            if (option == null) {
                log.warn("[IVR] COLLECT_DTMF: brak opcji '{}' w węźle={}, fallback; callId={}",
                        dtmfKey, collectNodeId, callId);
                session.clearDtmfCollection();
                saveSession(session);
                fallbackToDefaultQueue(callId, session.getTenantId());
            } else {
                log.debug("[IVR] COLLECT_DTMF: {} – przejście do następnego węzła: callId={}",
                        dtmfKey, callId);
                session.clearDtmfCollection();
                transitionToNextNode(callId, option.nextNodeId(), session, ivr);
            }
            return;
        }

        // --- Obsługa finishOnKey (np. "#") ---
        String finishOnKey = collectNode.finishOnKey();
        if (!finishOnKey.isEmpty() && finishOnKey.equals(dtmfKey)) {
            String collected = session.getDtmfBuffer();
            log.debug("[IVR] COLLECT_DTMF: finishOnKey='{}' – zebrano='{}', callId={}",
                    finishOnKey, collected, callId);
            finishDtmfCollection(callId, collected, collectNode, session, ivr);
            return;
        }

        // --- Akumulacja cyfry ---
        session.appendDtmfDigit(dtmfKey);
        String buffer = session.getDtmfBuffer();
        log.debug("[IVR] COLLECT_DTMF: bufor='{}' ({}/{}), callId={}",
                buffer, buffer.length(), collectNode.maxDigits(), callId);

        if (buffer.length() >= collectNode.maxDigits()) {
            // Osiągnięto maxDigits – automatyczne zakończenie zbierania
            log.debug("[IVR] COLLECT_DTMF: maxDigits={} osiągnięte – kończę zbieranie; callId={}",
                    collectNode.maxDigits(), callId);
            finishDtmfCollection(callId, buffer, collectNode, session, ivr);
        } else {
            // Czekaj na kolejne cyfry
            saveSession(session);
        }
    }

    /**
     * Finalizuje zbieranie cyfr: zapisuje zmienną sesji, czyści stan COLLECT_DTMF
     * i przechodzi do opcji "success" węzła.
     */
    private void finishDtmfCollection(String callId, String collected,
                                       IvrNode collectNode, IvrSessionData session, IvrTree ivr) {
        // Zapisz zebraną wartość do zmiennych sesji
        if (collectNode.variableName() != null && !collectNode.variableName().isBlank()) {
            session.setVariable(collectNode.variableName(), collected);
            log.info("[IVR] COLLECT_DTMF: zapisano zmienną '{}' = '{}'; callId={}",
                    collectNode.variableName(), collected, callId);
        }

        session.clearDtmfCollection();

        // Przejdź do węzła "success"
        IvrOption successOption = collectNode.findOption("success");
        if (successOption == null) {
            log.warn("[IVR] COLLECT_DTMF: brak opcji 'success' w węźle={}, fallback; callId={}",
                    collectNode.nodeId(), callId);
            saveSession(session);
            fallbackToDefaultQueue(callId, session.getTenantId());
            return;
        }

        transitionToNextNode(callId, successOption.nextNodeId(), session, ivr);
    }

    /**
     * Przechodzi do następnego węzła IVR: aktualizuje sesję i wywołuje {@link #executeNode}.
     */
    private void transitionToNextNode(String callId, String nextNodeId,
                                       IvrSessionData session, IvrTree ivr) {
        IvrNode nextNode = ivr.getDefinition().findNode(nextNodeId).orElse(null);

        if (nextNode == null) {
            log.error("[IVR] Następny węzeł nie istnieje: nextNodeId={}, callId={}", nextNodeId, callId);
            fallbackToDefaultQueue(callId, session.getTenantId());
            return;
        }

        session.setCurrentNodeId(nextNodeId);
        session.setRetryCount(0);
        saveSession(session);

        executeNode(callId, nextNode, session);
    }

    // =========================================================================
    // Wykonanie węzła
    // =========================================================================

    /**
     * Wykonuje logikę węzła IVR w zależności od jego typu.
     *
     * @param callId  identyfikator sesji połączenia
     * @param node    węzeł do wykonania
     * @param session aktualny stan sesji IVR
     */
    void executeNode(String callId, IvrNode node, IvrSessionData session) {
        log.debug("[IVR] Wykonywanie węzła: callId={}, nodeId={}, type={}", callId, node.nodeId(), node.type());

        switch (node.type()) {
            case PLAY_AUDIO -> executePlayAudio(callId, node, session);
            case MENU -> executeMenu(callId, node, session);
            case COLLECT_DTMF -> executeCollectDtmf(callId, node, session);
            case QUEUE_TRANSFER -> executeQueueTransfer(callId, node, session);
            case HANGUP -> executeHangup(callId, session);
            default -> {
                log.error("[IVR] Nieznany typ węzła: type={}, callId={}", node.type(), callId);
                fallbackToDefaultQueue(callId, session.getTenantId());
            }
        }
    }

    // =========================================================================
    // Implementacje węzłów
    // =========================================================================

    private void executePlayAudio(String callId, IvrNode node, IvrSessionData session) {
        String audioUrl = null;

        if (node.audioId() != null) {
            // Próba pobrania URL z tabeli ivr_audio
            try {
                UUID audioId = UUID.fromString(node.audioId());
                Optional<IvrAudio> audioOpt = ivrAudioRepository.findByAudioIdAndTenantId(
                        audioId, session.getTenantId());

                if (audioOpt.isPresent()) {
                    IvrAudio audio = audioOpt.get();

                    if ("TTS".equals(audio.getAudioType()) && audio.getTtsText() != null) {
                        audioUrl = resolveTtsUrl(audio.getTtsText(), audio.getAudioId().toString());
                    } else {
                        audioUrl = audio.getS3Url();
                    }
                } else {
                    log.warn("[IVR] Plik audio nie znaleziony: audioId={}, callId={}", node.audioId(), callId);
                }
            } catch (IllegalArgumentException e) {
                log.warn("[IVR] Nieprawidłowy format audioId: audioId={}, callId={}", node.audioId(), callId);
            }
        }

        // Fallback: jeśli brak URL – użyj promptu tekstowego
        if (audioUrl == null) {
            log.info("[IVR] Playing audio (text prompt): callId={}, prompt={}", callId, node.prompt());
        } else {
            log.info("[IVR] Playing audio: callId={}, url={}", callId, audioUrl);
        }
        // Mock: adapter nie ma metody playAudio – logujemy operację
    }

    /**
     * Wykonuje węzeł MENU – oczekuje na pojedynczy klawisz DTMF z opcji węzła.
     *
     * <p>Logika:
     * <ol>
     *   <li>Odtwarza prompt</li>
     *   <li>Planuje timeout – po {@code timeoutSeconds} wywołuje {@link #handleDtmfInput} z "timeout"</li>
     * </ol>
     */
    private void executeMenu(String callId, IvrNode node, IvrSessionData session) {
        // Odtwórz komunikat (jeśli istnieje)
        if (node.prompt() != null || node.audioId() != null) {
            executePlayAudio(callId, node, session);
        }

        log.debug("[IVR] MENU: oczekiwanie na DTMF: callId={}, nodeId={}, timeout={}s",
                callId, node.nodeId(), node.timeoutSeconds());

        scheduleTimeoutForDtmf(callId, node);
    }

    /**
     * Wykonuje węzeł COLLECT_DTMF – zbiera sekwencję cyfr od dzwoniącego.
     *
     * <p>Różni się od MENU tym, że:
     * <ul>
     *   <li>Ustawia w sesji {@code collectingDtmfForNodeId} – silnik wie, że jest w trybie akumulacji cyfr</li>
     *   <li>Resetuje bufor DTMF</li>
     *   <li>Zbieranie kończy się po: naciśnięciu {@code finishOnKey}, zebraniu {@code maxDigits} cyfr
     *       lub timeoucie</li>
     * </ul>
     */
    private void executeCollectDtmf(String callId, IvrNode node, IvrSessionData session) {
        // Odtwórz komunikat
        if (node.prompt() != null || node.audioId() != null) {
            executePlayAudio(callId, node, session);
        }

        log.debug("[IVR] COLLECT_DTMF: start zbierania cyfr: callId={}, nodeId={}, "
                        + "variableName={}, minDigits={}, maxDigits={}, finishOnKey={}, timeout={}s",
                callId, node.nodeId(), node.variableName(),
                node.minDigits(), node.maxDigits(), node.finishOnKey(), node.timeoutSeconds());

        // Ustaw tryb zbierania cyfr – bufor jest już "" po setCollectingDtmfForNodeId
        session.setDtmfBuffer("");
        session.setCollectingDtmfForNodeId(node.nodeId());
        saveSession(session);

        scheduleTimeoutForDtmf(callId, node);
    }

    /** Współdzielona logika planowania timeout-zadania DTMF dla MENU i COLLECT_DTMF. */
    private void scheduleTimeoutForDtmf(String callId, IvrNode node) {
        TenantContext.Snapshot snapshot = TenantContext.isSet() ? TenantContext.snapshot() : null;

        taskScheduler.schedule(
                () -> {
                    if (snapshot != null) {
                        TenantContext.restore(snapshot);
                    }
                    try {
                        log.debug("[IVR] Timeout DTMF: callId={}, nodeId={}", callId, node.nodeId());
                        handleDtmfInput(callId, "timeout");
                    } catch (Exception e) {
                        log.error("[IVR] Błąd timeoutu DTMF: callId={}, error={}", callId, e.getMessage(), e);
                    } finally {
                        if (snapshot != null) {
                            TenantContext.clear();
                        }
                    }
                },
                Instant.now().plusSeconds(node.timeoutSeconds())
        );

        log.debug("[IVR] Zaplanowano timeout DTMF za {}s dla callId={}", node.timeoutSeconds(), callId);
    }

    private void executeQueueTransfer(String callId, IvrNode node, IvrSessionData session) {
        if (node.queueId() == null) {
            log.error("[IVR] Brak queueId w węźle QUEUE_TRANSFER: nodeId={}, callId={}", node.nodeId(), callId);
            fallbackToDefaultQueue(callId, session.getTenantId());
            return;
        }

        try {
            UUID queueId = UUID.fromString(node.queueId());

            // Weryfikacja kolejki
            Optional<Queue> queueOpt = queueRepository.findByIdAndTenantId(queueId, session.getTenantId());
            if (queueOpt.isEmpty()) {
                log.warn("[IVR] Kolejka nie istnieje: queueId={}, callId={}", queueId, callId);
                fallbackToDefaultQueue(callId, session.getTenantId());
                return;
            }

            // Opublikuj ContactQueuedMessage – contact_id = callId jako UUID (mock)
            // W prawdziwym przepływie contactId pochodzi z rekordu contact w DB
            UUID contactId = deriveContactId(callId);
            // Przekaż zmienne zebrane przez COLLECT_DTMF (mapa może być pusta, nigdy null)
            ContactQueuedMessage message = new ContactQueuedMessage(
                    contactId, queueId, session.getTenantId(),
                    session.getVariables().isEmpty() ? null : session.getVariables());

            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, ROUTING_KEY_CONTACT_QUEUED, message);

            log.info("[IVR] Przekazano do kolejki: callId={}, queueId={}, contactId={}",
                    callId, queueId, contactId);

            // Usuń sesję IVR – IVR przepływ zakończony
            deleteSession(callId);

        } catch (IllegalArgumentException e) {
            log.error("[IVR] Nieprawidłowy format queueId: queueId={}, callId={}", node.queueId(), callId);
            fallbackToDefaultQueue(callId, session.getTenantId());
        } catch (Exception e) {
            log.error("[IVR] Błąd QUEUE_TRANSFER: callId={}, error={}", callId, e.getMessage(), e);
            fallbackToDefaultQueue(callId, session.getTenantId());
        }
    }

    private void executeHangup(String callId, IvrSessionData session) {
        log.info("[IVR] Rozłączanie połączenia: callId={}", callId);

        try {
            telephonyAdapter.hangupCall(callId);
        } catch (Exception e) {
            log.warn("[IVR] Błąd podczas hangup (ignorowany): callId={}, error={}", callId, e.getMessage());
        }

        // Usuń sesję IVR
        deleteSession(callId);
        log.info("[IVR] Sesja IVR zakończona (HANGUP): callId={}", callId);
    }

    // =========================================================================
    // Fallback
    // =========================================================================

    /**
     * Fallback – przekazanie do domyślnej kolejki tenanta w przypadku błędu IVR.
     *
     * <p>Jeśli nie ma żadnej aktywnej kolejki, loguje error i kończy przepływ.
     *
     * @param callId   identyfikator sesji połączenia
     * @param tenantId UUID tenanta
     */
    void fallbackToDefaultQueue(String callId, UUID tenantId) {
        log.warn("[IVR] Fallback do domyślnej kolejki: callId={}, tenantId={}", callId, tenantId);

        try {
            // Wczytaj pierwszą aktywną kolejkę tenanta jako domyślną
            Optional<Queue> defaultQueue = findDefaultQueue(tenantId);

            if (defaultQueue.isPresent()) {
                UUID contactId = deriveContactId(callId);
                ContactQueuedMessage message = new ContactQueuedMessage(
                        contactId, defaultQueue.get().getQueueId(), tenantId);

                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, ROUTING_KEY_CONTACT_QUEUED, message);

                log.info("[IVR] Fallback: przekazano do kolejki: callId={}, queueId={}",
                        callId, defaultQueue.get().getQueueId());
            } else {
                log.error("[IVR] Fallback: brak aktywnej kolejki dla tenanta={}, callId={}", tenantId, callId);
                // Rozłącz połączenie jako ostateczność
                try {
                    telephonyAdapter.hangupCall(callId);
                } catch (Exception ex) {
                    log.warn("[IVR] Fallback hangup nieudany: callId={}", callId);
                }
            }

        } catch (Exception e) {
            log.error("[IVR] Błąd podczas fallback: callId={}, tenantId={}, error={}",
                    callId, tenantId, e.getMessage(), e);
        } finally {
            deleteSession(callId);
        }
    }

    // =========================================================================
    // TTS cache
    // =========================================================================

    /**
     * Pobiera URL audio TTS z cache Redis lub generuje (MVP: mock).
     *
     * <p>Klucz Redis: {@code ivr:tts:{md5(ttsText)}} TTL 24h.
     * W MVP generacja TTS jest mockowana – zapisywany jest placeholder URL.
     *
     * @param ttsText  tekst do syntezy mowy
     * @param audioId  identyfikator audio (dla logowania)
     * @return URL pliku audio TTS
     */
    String resolveTtsUrl(String ttsText, String audioId) {
        String cacheKey = TTS_CACHE_KEY_PREFIX + md5(ttsText);

        // Sprawdź cache
        String cachedUrl = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedUrl != null) {
            log.debug("[IVR] TTS cache hit: audioId={}", audioId);
            return cachedUrl;
        }

        // Cache miss – mock generacji TTS (brak prawdziwego providera)
        log.info("[IVR] TTS generation skipped (no provider configured) – using text prompt: audioId={}", audioId);
        String mockUrl = "mock-tts-url";

        // Zapisz do cache na 24h
        stringRedisTemplate.opsForValue().set(cacheKey, mockUrl, TTS_TTL);
        log.debug("[IVR] TTS URL zapisany w cache: audioId={}, key={}", audioId, cacheKey);

        return mockUrl;
    }

    // =========================================================================
    // Redis – zarządzanie sesją
    // =========================================================================

    private void saveSession(IvrSessionData session) {
        String key = SESSION_KEY_PREFIX + session.getCallId();
        try {
            String json = objectMapper.writeValueAsString(session);
            stringRedisTemplate.opsForValue().set(key, json, SESSION_TTL);
            log.trace("[IVR] Sesja zapisana: callId={}, nodeId={}", session.getCallId(), session.getCurrentNodeId());
        } catch (JsonProcessingException e) {
            log.error("[IVR] Błąd serializacji sesji: callId={}, error={}", session.getCallId(), e.getMessage());
        }
    }

    IvrSessionData loadSession(String callId) {
        String key = SESSION_KEY_PREFIX + callId;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, IvrSessionData.class);
        } catch (JsonProcessingException e) {
            log.error("[IVR] Błąd deserializacji sesji: callId={}, error={}", callId, e.getMessage());
            return null;
        }
    }

    private void deleteSession(String callId) {
        String key = SESSION_KEY_PREFIX + callId;
        stringRedisTemplate.delete(key);
        log.debug("[IVR] Sesja usunięta z Redis: callId={}", callId);
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private Optional<IvrTree> resolveIvrTree(UUID tenantId, UUID ivrId) {
        if (ivrId != null) {
            return ivrTreeRepository.findByIvrIdAndTenantId(ivrId, tenantId);
        }
        return ivrTreeRepository.findActiveByTenantId(tenantId);
    }

    /**
     * Pobiera pierwszą aktywną kolejkę tenanta jako "domyślną".
     * W produkcji powinna być konfiguracja domyślnej kolejki per tenant.
     */
    private Optional<Queue> findDefaultQueue(UUID tenantId) {
        try {
            // Używamy istniejącej metody – pobieramy pierwszą aktywną kolejkę
            return queueRepository.findAllByTenantId(tenantId, null, 0, 1)
                    .content()
                    .stream()
                    .findFirst();
        } catch (Exception e) {
            log.error("[IVR] Błąd pobierania domyślnej kolejki: tenantId={}, error={}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Tworzy deterministyczny UUID z callId (mock – w produkcji powinien być UUID kontaktu z DB).
     */
    private UUID deriveContactId(String callId) {
        return UUID.nameUUIDFromBytes(callId.getBytes());
    }

    /**
     * Oblicza MD5 hash tekstu dla klucza cache TTS.
     */
    private String md5(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // MD5 zawsze dostępne w JVM
            return Integer.toHexString(text.hashCode());
        }
    }
}
