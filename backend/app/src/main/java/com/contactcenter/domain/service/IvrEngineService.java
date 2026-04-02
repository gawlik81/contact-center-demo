package com.contactcenter.domain.service;

import com.contactcenter.domain.ivr.IvrDefinition;
import com.contactcenter.domain.ivr.IvrNode;
import com.contactcenter.domain.ivr.IvrOption;
import com.contactcenter.domain.ivr.IvrSessionData;
import com.contactcenter.domain.model.IvrAudio;
import com.contactcenter.domain.model.IvrTree;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.repository.ContactRepository;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Tymczasowy kontekst konferencji dla klienta czekającego w kolejce.
     * Wypełniany przez executeQueueTransfer, odczytywany przez handleDtmfAndBuildTwiml.
     * Klucz: callId. Wartość: contactId przekazany do konferencji Twilio.
     */
    private final ConcurrentHashMap<String, UUID> pendingConferenceContactId = new ConcurrentHashMap<>();

    @Value("${app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    private final IvrTreeRepository ivrTreeRepository;
    private final IvrAudioRepository ivrAudioRepository;
    private final QueueRepository queueRepository;
    private final ContactRepository contactRepository;
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
        startIvrSession(callId, tenantId, ivrId, false);
    }

    /**
     * Startuje sesję IVR z możliwością wyboru trybu TwiML.
     *
     * @param callId    identyfikator sesji połączenia
     * @param tenantId  UUID tenanta
     * @param ivrId     UUID konkretnego drzewa IVR (null = użyj aktywnego dla tenanta)
     * @param twimlMode gdy true, wewnętrzny timer DTMF nie jest planowany –
     *                  Twilio zarządza timeoutem przez {@code <Gather timeout="N">}
     */
    public void startIvrSession(String callId, UUID tenantId, UUID ivrId, boolean twimlMode) {
        log.info("[IVR] Startowanie sesji IVR: callId={}, tenantId={}, ivrId={}, twimlMode={}",
            callId, tenantId, ivrId, twimlMode);

        try {
            Optional<IvrTree> ivrOpt = resolveIvrTree(tenantId, ivrId);

            if (ivrOpt.isEmpty()) {
                log.warn("[IVR] Brak aktywnego drzewa IVR dla tenanta: tenantId={}", tenantId);
                fallbackToDefaultQueue(callId, tenantId);
                return;
            }

            startIvrSessionWithTree(callId, tenantId, ivrOpt.get(), twimlMode);

        }
        catch (Exception e) {
            log.error("[IVR] Błąd podczas startowania sesji IVR: callId={}, error={}", callId, e.getMessage(), e);
            fallbackToDefaultQueue(callId, tenantId);
        }
    }

    /**
     * Startuje sesję IVR na podstawie już załadowanego drzewa IVR.
     *
     * <p>Wydzielona z {@link #startIvrSession} aby uniknąć podwójnego wywołania
     * {@link #resolveIvrTree} w metodach TwiML (np. {@link #startIvrSessionAndBuildTwiml}).
     *
     * @param callId    identyfikator sesji połączenia
     * @param tenantId  UUID tenanta
     * @param ivr       już załadowane drzewo IVR (nie woła resolveIvrTree)
     * @param twimlMode gdy true, wewnętrzny timer DTMF nie jest planowany
     */
    private void startIvrSessionWithTree(String callId, UUID tenantId, IvrTree ivr, boolean twimlMode) {
        IvrDefinition definition = ivr.getDefinition();

        if (definition == null || definition.entryNodeId() == null) {
            log.warn("[IVR] Nieprawidłowa definicja IVR: ivrId={}", ivr.getIvrId());
            fallbackToDefaultQueue(callId, tenantId);
            return;
        }

        // Utwórz sesję w Redis
        IvrSessionData session = new IvrSessionData(callId, ivr.getIvrId(),
            definition.entryNodeId(), tenantId);
        session.setTwimlMode(twimlMode);
        saveSession(session);

        // Wykonaj węzeł wejściowy
        IvrNode entryNode = definition.findNode(definition.entryNodeId()).orElse(null);
        if (entryNode == null) {
            log.error("[IVR] Węzeł wejściowy nie istnieje: entryNodeId={}, ivrId={}",
                definition.entryNodeId(), ivr.getIvrId());
            fallbackToDefaultQueue(callId, tenantId);
            return;
        }

        executeNode(callId, entryNode, session, twimlMode);
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
                    // Ponów ten sam węzeł (odtwórz komunikat ponownie) – propaguj twimlMode z sesji
                    executeNode(callId, currentNode, session, session.isTwimlMode());
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

    // =========================================================================
    // TwiML – generowanie odpowiedzi XML dla Twilio webhook
    // =========================================================================

    /**
     * Startuje sesję IVR i zwraca TwiML reprezentujący aktualny węzeł IVR.
     *
     * <p>Wywołaj z Voice URL endpointu Twilio zamiast hardkodowanego TwiML.
     * Metoda wewnętrznie wywołuje {@link #startIvrSession} (zarządzanie sesją Redis),
     * następnie odczytuje sesję i buduje dynamiczny TwiML dla bieżącego węzła.
     *
     * @param callId   Twilio Call SID (identyfikator połączenia)
     * @param tenantId UUID tenanta
     * @param baseUrl  publiczny bazowy URL aplikacji (np. https://example.ngrok.io)
     *                 – używany do budowania {@code action} URL w {@code <Gather>}
     * @return TwiML string gotowy do zwrotu jako {@code application/xml}
     */
    public String startIvrSessionAndBuildTwiml(String callId, UUID tenantId, String baseUrl) {
        return startIvrSessionAndBuildTwiml(callId, tenantId, baseUrl, null);
    }

    /**
     * Startuje sesję IVR w trybie TwiML i zwraca TwiML reprezentujący aktualny węzeł IVR.
     *
     * <p>Przekazuje {@code twimlMode=true} do {@link #startIvrSession} aby wewnętrzny timer
     * DTMF nie był planowany – Twilio zarządza timeoutem przez {@code <Gather timeout="N">}.
     *
     * @param callId    Twilio Call SID
     * @param tenantId  UUID tenanta
     * @param baseUrl   publiczny bazowy URL aplikacji
     * @param contactId UUID rekordu contact w DB (może być null jeśli nie udało się utworzyć)
     * @return TwiML string
     */
    public String startIvrSessionAndBuildTwiml(String callId, UUID tenantId, String baseUrl, UUID contactId) {
        // Ładuj drzewo IVR raz – przekazywane do startIvrSessionWithTree i do budowania TwiML
        IvrTree ivr = resolveIvrTree(tenantId, null).orElse(null);
        if (ivr == null || ivr.getDefinition() == null) {
            log.warn("[IVR] Brak aktywnego drzewa IVR dla TwiML: tenantId={}", tenantId);
            return buildFallbackTwiml();
        }

        startIvrSessionWithTree(callId, tenantId, ivr, true);

        IvrSessionData session = loadSession(callId);
        // Zapisz contactId w sesji (może być null – w trybie mock brak rekordu contact)
        if (session != null && contactId != null) {
            session.setContactId(contactId);
            saveSession(session);
        }
        if (session == null) {
            // Sesja nie istnieje – fallback musiał usunąć sesję i przekazać do kolejki
            return buildFallbackTwiml();
        }

        // Użyj już załadowanego drzewa – brak drugiego wywołania resolveIvrTree()
        IvrNode currentNode = ivr.getDefinition().findNode(session.getCurrentNodeId()).orElse(null);
        if (currentNode == null) {
            return buildFallbackTwiml();
        }
        return buildTwimlForNode(currentNode, callId, tenantId, baseUrl);
    }

    /**
     * Przetwarza wejście DTMF i zwraca TwiML dla następnego węzła IVR.
     *
     * <p>Wywołaj z DTMF action URL (endpoint {@code /dtmf}).
     * Metoda wewnętrznie wywołuje {@link #handleDtmfInput} (aktualizacja stanu sesji),
     * następnie odczytuje zaktualizowaną sesję i buduje TwiML dla nowego bieżącego węzła.
     *
     * @param callId    Twilio Call SID
     * @param dtmfInput naciśnięte klawisze DTMF (np. "1", "2", "#")
     * @param tenantId  UUID tenanta (potrzebny do budowania action URL)
     * @param baseUrl   publiczny bazowy URL aplikacji
     * @return TwiML string gotowy do zwrotu jako {@code application/xml}
     */
    public String handleDtmfAndBuildTwiml(String callId, String dtmfInput, UUID tenantId, String baseUrl) {
        handleDtmfInput(callId, dtmfInput);
        IvrSessionData session = loadSession(callId);
        if (session == null) {
            // Sesja usunięta przez QUEUE_TRANSFER – sprawdź czy klient powinien czekać w konferencji
            UUID conferencContactId = pendingConferenceContactId.remove(callId);
            if (conferencContactId != null) {
                log.info("[IVR] QUEUE_TRANSFER zakończony – kieruję klienta do konferencji: callId={}, contactId={}",
                    callId, conferencContactId);
                return buildWaitInConferenceTwiml(conferencContactId, tenantId, baseUrl);
            }
            // Sesja usunięta przez HANGUP lub brak konferencji
            return buildCompletedTwiml();
        }
        IvrTree ivr = resolveIvrTree(session.getTenantId(), session.getIvrId()).orElse(null);
        if (ivr == null || ivr.getDefinition() == null) {
            return buildFallbackTwiml();
        }
        IvrNode currentNode = ivr.getDefinition().findNode(session.getCurrentNodeId()).orElse(null);
        if (currentNode == null) {
            return buildFallbackTwiml();
        }
        return buildTwimlForNode(currentNode, callId, tenantId, baseUrl);
    }

    /**
     * Buduje TwiML dla podanego węzła IVR.
     *
     * <p>Mapowanie typów węzłów na TwiML:
     * <ul>
     *   <li>MENU / COLLECT_DTMF → {@code <Gather>} z promptem i action URL wskazującym na endpoint DTMF</li>
     *   <li>PLAY_AUDIO → {@code <Play>} (URL audio) lub {@code <Say>} (tekst TTS/prompt)</li>
     *   <li>QUEUE_TRANSFER / SET / IF / SWITCH / HANGUP → {@code <Say>} + {@code <Hangup>}
     *       (węzeł już przetworzony przez silnik, sesja może być usunięta)</li>
     * </ul>
     *
     * @param node     węzeł IVR do wyrenderowania jako TwiML
     * @param callId   identyfikator połączenia (przekazywany do action URL)
     * @param tenantId UUID tenanta (przekazywany do action URL)
     * @param baseUrl  bazowy URL aplikacji (np. https://example.ngrok.io)
     * @return TwiML string
     */
    private String buildTwimlForNode(IvrNode node, String callId, UUID tenantId, String baseUrl) {
        String dtmfActionUrl = baseUrl
            + "/api/telephony/webhook/twilio/dtmf?tenantId=" + tenantId
            + "&callId=" + callId;

        return switch (node.type()) {
            case MENU -> buildGatherTwiml(node, dtmfActionUrl, false);
            case COLLECT_DTMF -> buildGatherTwiml(node, dtmfActionUrl, true);
            case PLAY_AUDIO -> buildPlayAudioTwiml(node);
            case HANGUP -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Hangup/></Response>";
            // SET / IF / SWITCH / QUEUE_TRANSFER są węzłami przejściowymi – silnik przetwarza je
            // synchronicznie i przechodzi do kolejnego węzła. Gdy sesja wciąż istnieje po ich
            // wywołaniu, currentNode wskazuje już na następny węzeł. Ten case jest safety net.
            default -> buildFallbackTwiml();
        };
    }

    /**
     * Buduje TwiML z {@code <Gather>} dla węzłów MENU i COLLECT_DTMF.
     *
     * @param node          węzeł IVR
     * @param dtmfActionUrl URL do wywołania po zebraniu DTMF
     * @param multiDigit    true = zbieranie wielu cyfr (COLLECT_DTMF), false = jeden klawisz (MENU)
     * @return TwiML string
     */
    private String buildGatherTwiml(IvrNode node, String dtmfActionUrl, boolean multiDigit) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>");
        sb.append("<Gather action=\"").append(escapeXml(dtmfActionUrl)).append("\"");
        sb.append(" method=\"POST\"");
        // Minimalny timeout to 10s – Twilio musi mieć wystarczająco czasu aby odebrać odpowiedź
        sb.append(" timeout=\"").append(Math.max(node.timeoutSeconds(), 10)).append("\"");
        if (multiDigit) {
            sb.append(" numDigits=\"").append(node.maxDigits()).append("\"");
            if (node.finishOnKey() != null && !node.finishOnKey().isEmpty()) {
                sb.append(" finishOnKey=\"").append(node.finishOnKey()).append("\"");
            }
        }
        else {
            sb.append(" numDigits=\"1\"");
        }
        sb.append(">");
        // Prompt: preferuj tekst prompt, fallback na pusty (TTS z audioId obsługiwany osobno)
        if (node.prompt() != null && !node.prompt().isBlank()) {
            sb.append("<Say language=\"pl-PL\">")
                .append(escapeXml(node.prompt()))
                .append("</Say>");
        }
        sb.append("</Gather>");
        // Twilio wywołuje action URL gdy zbieranie zakończy się timeoutem
        // Jeśli <Gather> nie zbierze żadnego wejścia, Twilio kontynuuje za <Gather>
        // – dodajemy fallback Say (cichy timeout zostanie obsłużony przez scheduleTimeoutForDtmf)
        sb.append("</Response>");
        return sb.toString();
    }

    /**
     * Buduje TwiML z {@code <Say>} lub {@code <Play>} dla węzła PLAY_AUDIO.
     */
    private String buildPlayAudioTwiml(IvrNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>");
        if (node.prompt() != null && !node.prompt().isBlank()) {
            sb.append("<Say language=\"pl-PL\">")
                .append(escapeXml(node.prompt()))
                .append("</Say>");
        }
        else {
            // Brak promptu – krótka cisza, żeby Twilio nie rozłączyło od razu
            sb.append("<Pause length=\"1\"/>");
        }
        sb.append("</Response>");
        return sb.toString();
    }

    /**
     * TwiML fallback – informuje o błędzie technicznym i rozłącza.
     */
    private String buildFallbackTwiml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Response>"
            + "<Say language=\"pl-PL\">Przepraszamy, wystąpił błąd techniczny. "
            + "Spróbuj ponownie później.</Say>"
            + "<Hangup/>"
            + "</Response>";
    }

    /**
     * TwiML sygnalizujący zakończenie przepływu IVR (połączenie przekazane do kolejki).
     * Używany jako fallback gdy brak contactId lub gdy sesja zakończona przez HANGUP.
     */
    private String buildCompletedTwiml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Response>"
            + "<Say language=\"pl-PL\">Łączymy z konsultantem. Proszę czekać.</Say>"
            + "<Pause length=\"30\"/>"
            + "</Response>";
    }

    /**
     * Buduje TwiML kierujący klienta do nazwanej konferencji Twilio w trybie oczekiwania.
     *
     * <p>Klient wchodzi do konferencji o nazwie {@code contact-{contactId}} z parametrem
     * {@code startConferenceOnEnter="false"} – konferencja nie startuje dopóki agent
     * (moderator) nie dołączy. Nagranie startuje od momentu wejścia agenta.
     *
     * <p>Agent wchodzi do tej samej konferencji przez {@link com.contactcenter.domain.telephony.TwilioTelephonyAdapter#answerCall}
     * z parametrem {@code startConferenceOnEnter="true"}.
     *
     * @param contactId UUID kontaktu – podstawa nazwy konferencji
     * @param tenantId  UUID tenanta – przekazywany jako parametr do recordingStatusCallback
     * @param baseUrl   publiczny bazowy URL aplikacji
     * @return TwiML z {@code <Conference>} w trybie oczekiwania
     */
    private String buildWaitInConferenceTwiml(UUID contactId, UUID tenantId, String baseUrl) {
        String conferenceName = "contact-" + contactId.toString();
        String recordingCallbackUrl = baseUrl
            + "/api/telephony/webhook/twilio/recording?tenantId=" + tenantId.toString();
        String conferenceStatusCallbackUrl = baseUrl
            + "/api/telephony/webhook/twilio/conference?tenantId=" + tenantId.toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Response>"
            + "<Say language=\"pl-PL\">Łączymy z konsultantem, proszę czekać.</Say>"
            + "<Dial>"
            + "<Conference"
            + " startConferenceOnEnter=\"false\""
            + " endConferenceOnExit=\"true\""
            + " record=\"record-from-start\""
            + " recordingStatusCallback=\"" + recordingCallbackUrl + "\""
            + " recordingStatusCallbackMethod=\"POST\""
            + " statusCallback=\"" + conferenceStatusCallbackUrl + "\""
            + " statusCallbackEvent=\"end\""
            + " statusCallbackMethod=\"POST\""
            + " waitUrl=\"" + appBaseUrl + "/api/telephony/hold-music\""
            + " waitMethod=\"GET\">"
            + conferenceName
            + "</Conference>"
            + "</Dial>"
            + "</Response>";
    }

    /**
     * Escapuje znaki specjalne XML w wartościach atrybutów i treści elementów.
     */
    private String escapeXml(String text) {
        if (text == null)
            return "";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
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

        // Propaguj twimlMode z sesji – zachowuje tryb przez cały przepływ IVR
        executeNode(callId, nextNode, session, session.isTwimlMode());
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
        executeNode(callId, node, session, false);
    }

    /**
     * Wykonuje logikę węzła IVR w zależności od jego typu.
     *
     * @param callId    identyfikator sesji połączenia
     * @param node      węzeł do wykonania
     * @param session   aktualny stan sesji IVR
     * @param twimlMode gdy true, węzły MENU/COLLECT_DTMF nie planują wewnętrznego timera DTMF
     *                  (Twilio zarządza timeoutem przez {@code <Gather timeout="N">})
     */
    void executeNode(String callId, IvrNode node, IvrSessionData session, boolean twimlMode) {
        log.debug("[IVR] Wykonywanie węzła: callId={}, nodeId={}, type={}, twimlMode={}",
            callId, node.nodeId(), node.type(), twimlMode);

        switch (node.type()) {
            case PLAY_AUDIO -> executePlayAudio(callId, node, session);
            case MENU -> executeMenu(callId, node, session, twimlMode);
            case COLLECT_DTMF -> executeCollectDtmf(callId, node, session, twimlMode);
            case QUEUE_TRANSFER -> executeQueueTransfer(callId, node, session);
            case HANGUP -> executeHangup(callId, session);
            case SET -> executeSet(callId, node, session);
            case IF -> executeIf(callId, node, session);
            case SWITCH -> executeSwitch(callId, node, session);
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
        executeMenu(callId, node, session, false);
    }

    private void executeMenu(String callId, IvrNode node, IvrSessionData session, boolean twimlMode) {
        // Odtwórz komunikat (jeśli istnieje)
        if (node.prompt() != null || node.audioId() != null) {
            executePlayAudio(callId, node, session);
        }

        log.debug("[IVR] MENU: oczekiwanie na DTMF: callId={}, nodeId={}, timeout={}s, twimlMode={}",
                callId, node.nodeId(), node.timeoutSeconds(), twimlMode);

        // W trybie TwiML Twilio zarządza timeoutem przez <Gather timeout="N">.
        // Nie planujemy wewnętrznego timera – uniknięcie wyścigu dwóch timerów.
        if (!twimlMode) {
            scheduleTimeoutForDtmf(callId, node);
        }
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
        executeCollectDtmf(callId, node, session, false);
    }

    private void executeCollectDtmf(String callId, IvrNode node, IvrSessionData session, boolean twimlMode) {
        // Odtwórz komunikat
        if (node.prompt() != null || node.audioId() != null) {
            executePlayAudio(callId, node, session);
        }

        log.debug("[IVR] COLLECT_DTMF: start zbierania cyfr: callId={}, nodeId={}, "
                        + "variableName={}, minDigits={}, maxDigits={}, finishOnKey={}, timeout={}s, twimlMode={}",
                callId, node.nodeId(), node.variableName(),
            node.minDigits(), node.maxDigits(), node.finishOnKey(), node.timeoutSeconds(), twimlMode);

        // Ustaw tryb zbierania cyfr – bufor jest już "" po setCollectingDtmfForNodeId
        session.setDtmfBuffer("");
        session.setCollectingDtmfForNodeId(node.nodeId());
        saveSession(session);

        // W trybie TwiML Twilio zarządza timeoutem przez <Gather timeout="N">.
        // Nie planujemy wewnętrznego timera – uniknięcie wyścigu dwóch timerów.
        if (!twimlMode) {
            scheduleTimeoutForDtmf(callId, node);
        }
    }

    /**
     * Współdzielona logika planowania timeout-zadania DTMF dla MENU i COLLECT_DTMF.
     */
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

            // Użyj contactId z sesji IVR (ustawionego przez TwilioWebhookController) lub
            // wygeneruj deterministyczny UUID jako fallback dla trybu MockTelephonyAdapter
            UUID contactId = session.getContactId() != null
                ? session.getContactId()
                : deriveContactId(callId);

            // Zapisz queue_id do DB przed publikacją eventu.
            // Bez tej aktualizacji kolumna queue_id pozostaje NULL, co trwale blokuje
            // retry routingu w RoutingService.onAgentStatusChanged() (pomija kontakty z queue_id=NULL).
            if (contactId != null) {
                try {
                    int rows = contactRepository.updateQueueId(contactId, session.getTenantId(), queueId);
                    if (rows == 0) {
                        log.warn("[IVR] updateQueueId: brak zaktualizowanych wierszy – " +
                                "kontakt może nie istnieć lub mieć inny status: contactId={}, queueId={}",
                                contactId, queueId);
                    } else {
                        log.debug("[IVR] Zaktualizowano queue_id w DB: contactId={}, queueId={}",
                                contactId, queueId);
                    }
                } catch (Exception updateEx) {
                    log.error("[IVR] Błąd aktualizacji queue_id w DB: contactId={}, queueId={}, error={}",
                            contactId, queueId, updateEx.getMessage(), updateEx);
                    // Kontynuuj – routing może działać z eventu RabbitMQ dla bieżącego wywołania,
                    // ale retry (onAgentStatusChanged) będzie zablokowany jeśli nie ma agentów
                }
            }

            // Przekaż zmienne zebrane przez COLLECT_DTMF (mapa może być pusta, nigdy null)
            ContactQueuedMessage message = new ContactQueuedMessage(
                contactId, queueId, session.getTenantId(),
                    session.getVariables().isEmpty() ? null : session.getVariables());

            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, ROUTING_KEY_CONTACT_QUEUED, message);

            log.info("[IVR] Przekazano do kolejki: callId={}, queueId={}, contactId={}",
                    callId, queueId, contactId);

            // Zapisz contactId dla handleDtmfAndBuildTwiml – klient będzie skierowany do konferencji
            if (contactId != null) {
                pendingConferenceContactId.put(callId, contactId);
            }

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

    /**
     * Wykonuje węzeł SET – ustawia zmienną sesji i natychmiast przechodzi do opcji {@code next}.
     *
     * <p>Pole {@code value} może zawierać interpolację {@code ${zmienna}} – wartości zostaną
     * rozwinięte z aktualnych zmiennych sesji przed zapisem.
     */
    private void executeSet(String callId, IvrNode node, IvrSessionData session) {
        String varName = node.variableName();
        String rawValue = node.value() != null ? node.value() : "";
        String resolvedValue = resolveVariables(rawValue, session);

        if (varName != null && !varName.isBlank()) {
            session.setVariable(varName, resolvedValue);
            log.info("[IVR] SET: {}='{}' (resolved); callId={}", varName, resolvedValue, callId);
        } else {
            log.warn("[IVR] SET: puste variableName – pomijam; callId={}", callId);
        }

        IvrTree ivr = resolveIvrTree(session.getTenantId(), session.getIvrId()).orElse(null);
        if (ivr == null) { fallbackToDefaultQueue(callId, session.getTenantId()); return; }

        IvrOption nextOpt = node.findOption("next");
        if (nextOpt == null || nextOpt.nextNodeId() == null || nextOpt.nextNodeId().isBlank()) {
            log.warn("[IVR] SET: brak opcji 'next'; callId={}", callId);
            fallbackToDefaultQueue(callId, session.getTenantId());
            return;
        }
        transitionToNextNode(callId, nextOpt.nextNodeId(), session, ivr);
    }

    /**
     * Wykonuje węzeł IF – testuje wartość zmiennej sesji przy użyciu operatora logicznego.
     *
     * <p>Obsługiwane operatory:
     * {@code EQUALS}, {@code NOT_EQUALS}, {@code CONTAINS}, {@code STARTS_WITH},
     * {@code IS_EMPTY}, {@code NOT_EMPTY}, {@code MATCHES_REGEX}.
     *
     * <p>Wyjścia: opcja {@code true} lub {@code false}.
     */
    private void executeIf(String callId, IvrNode node, IvrSessionData session) {
        String varName = node.variableName();
        String varValue = varName != null ? session.getVariable(varName) : null;
        String compareValue = resolveVariables(node.compareValue() != null ? node.compareValue() : "", session);
        String operator = node.operator() != null ? node.operator() : "EQUALS";

        boolean result = evaluateCondition(varValue, operator, compareValue);
        log.info("[IVR] IF: var='{}' value='{}' op='{}' cmp='{}' → {}; callId={}",
                varName, varValue, operator, compareValue, result, callId);

        IvrTree ivr = resolveIvrTree(session.getTenantId(), session.getIvrId()).orElse(null);
        if (ivr == null) { fallbackToDefaultQueue(callId, session.getTenantId()); return; }

        IvrOption branch = node.findOption(result ? "true" : "false");
        if (branch == null) {
            log.warn("[IVR] IF: brak opcji '{}'; callId={}", result ? "true" : "false", callId);
            fallbackToDefaultQueue(callId, session.getTenantId());
            return;
        }
        transitionToNextNode(callId, branch.nextNodeId(), session, ivr);
    }

    /**
     * Wykonuje węzeł SWITCH – routing wielowariantowy na podstawie wartości zmiennej sesji.
     *
     * <p>Szuka opcji o kluczu równym bieżącej wartości zmiennej; gdy brak dopasowania – używa
     * opcji o kluczu {@code default}. Gdy i ta nie istnieje – fallback do domyślnej kolejki.
     */
    private void executeSwitch(String callId, IvrNode node, IvrSessionData session) {
        String varName = node.variableName();
        String varValue = varName != null ? session.getVariable(varName) : null;

        IvrTree ivr = resolveIvrTree(session.getTenantId(), session.getIvrId()).orElse(null);
        if (ivr == null) { fallbackToDefaultQueue(callId, session.getTenantId()); return; }

        // Szukaj dokładnego dopasowania
        IvrOption matched = varValue != null ? node.findOption(varValue) : null;

        // Fallback do "default"
        if (matched == null) {
            matched = node.findOption("default");
        }

        if (matched == null) {
            log.warn("[IVR] SWITCH: brak dopasowania i brak 'default' dla var='{}' value='{}'; callId={}",
                    varName, varValue, callId);
            fallbackToDefaultQueue(callId, session.getTenantId());
            return;
        }

        log.info("[IVR] SWITCH: var='{}' value='{}' → opcja='{}'; callId={}",
                varName, varValue, matched.key(), callId);
        transitionToNextNode(callId, matched.nextNodeId(), session, ivr);
    }

    /**
     * Rozwiązuje interpolację zmiennych w szablonie tekstowym.
     *
     * <p>Wzorzec: {@code ${nazwaZmiennej}} zastępowany wartością z {@code session.getVariables()}.
     * Nieznane zmienne pozostawiane jako literał.
     *
     * @param template tekst zawierający opcjonalne wyrażenia {@code ${...}}
     * @param session  aktualny stan sesji IVR ze zmiennymi
     * @return tekst z rozwiązanymi zmiennymi; nigdy null
     */
    private String resolveVariables(String template, IvrSessionData session) {
        if (template == null || !template.contains("${")) return template != null ? template : "";
        String result = template;
        for (Map.Entry<String, String> entry : session.getVariables().entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * Ocenia warunek logiczny dla węzła IF.
     *
     * @param varValue     aktualna wartość zmiennej sesji (może być null)
     * @param operator     operator porównania (np. EQUALS, CONTAINS, MATCHES_REGEX)
     * @param compareValue wartość porównania (już po rozwinięciu zmiennych)
     * @return wynik ewaluacji warunku
     */
    private boolean evaluateCondition(String varValue, String operator, String compareValue) {
        return switch (operator) {
            case "EQUALS"        -> compareValue.equals(varValue != null ? varValue : "");
            case "NOT_EQUALS"    -> !compareValue.equals(varValue != null ? varValue : "");
            case "CONTAINS"      -> varValue != null && varValue.contains(compareValue);
            case "STARTS_WITH"   -> varValue != null && varValue.startsWith(compareValue);
            case "IS_EMPTY"      -> varValue == null || varValue.isBlank();
            case "NOT_EMPTY"     -> varValue != null && !varValue.isBlank();
            case "MATCHES_REGEX" -> {
                try { yield varValue != null && varValue.matches(compareValue); }
                catch (Exception e) {
                    log.warn("[IVR] IF: nieprawidłowy regex '{}': {}", compareValue, e.getMessage());
                    yield false;
                }
            }
            default -> {
                log.warn("[IVR] IF: nieznany operator '{}' – traktuję jako EQUALS", operator);
                yield compareValue.equals(varValue != null ? varValue : "");
            }
        };
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
                // Wczytaj sesję aby pobrać contactId (może być null gdy sesja już usunięta)
                IvrSessionData sessionForFallback = loadSession(callId);
                UUID contactId = (sessionForFallback != null && sessionForFallback.getContactId() != null)
                    ? sessionForFallback.getContactId()
                    : deriveContactId(callId);
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
