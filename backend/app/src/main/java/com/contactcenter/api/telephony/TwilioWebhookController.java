package com.contactcenter.api.telephony;

import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.CreateContactRequest;
import com.contactcenter.domain.contact.ContactRepository;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.routing.RouteResult;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.service.IncomingCallRoutingService;
import com.contactcenter.domain.service.IvrEngineService;
import com.contactcenter.domain.service.TwilioRecordingDownloadService;
import com.contactcenter.domain.telephony.TwilioTelephonyAdapter;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.security.TenantContext;
import com.twilio.security.RequestValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kontroler webhooków dla Twilio Programmable Voice.
 *
 * <p>Odbiera zdarzenia statusu połączeń od Twilio w formacie {@code application/x-www-form-urlencoded}.
 * Twilio wysyła parametry jako form-encoded POST na URL skonfigurowany jako StatusCallback.
 *
 * <p>Endpoint jest <strong>publiczny</strong> (bez JWT) – Twilio nie wysyła tokenów JWT.
 * Autentyczność żądań weryfikowana jest przez podpis HMAC-SHA256 w nagłówku
 * {@code X-Twilio-Signature} (opcjonalna weryfikacja opisana w komentarzu).
 *
 * <p>Aktywny tylko gdy {@link TwilioTelephonyAdapter} jest w kontekście Springa
 * (tj. gdy {@code twilio.enabled=true}).
 *
 * <h2>Parametry Twilio StatusCallback</h2>
 * <ul>
 *   <li>{@code CallSid} – unikalny identyfikator połączenia (np. CAxxxxxxxx)</li>
 *   <li>{@code From}   – numer dzwoniącego w formacie E.164</li>
 *   <li>{@code To}     – numer docelowy w formacie E.164</li>
 *   <li>{@code CallStatus} – status: queued, initiated, ringing, in-progress,
 *                            completed, busy, failed, no-answer, canceled</li>
 *   <li>{@code Direction} – inbound lub outbound-api</li>
 *   <li>{@code AccountSid} – SID konta Twilio</li>
 * </ul>
 *
 * <h2>Tenant routing</h2>
 * <p>Twilio nie przesyła {@code tenantId}. Oczekujemy go jako parametru query string
 * {@code ?tenantId=UUID} w URL StatusCallback lub jako dodatkowego parametru POST.
 * Alternatywnie: mapowanie numeru telefonu (To) na tenantId przez serwis konfiguracyjny.
 * Implementacja uproszczona – pobiera tenantId z query param lub formy, z fallback na null.
 */
@Slf4j
@RestController
@RequestMapping("/api/telephony/webhook/twilio")
@ConditionalOnBean(TwilioTelephonyAdapter.class)
@Tag(name = "Twilio Webhook", description = "Webhook do odbioru zdarzeń połączeń od Twilio Programmable Voice")
public class TwilioWebhookController {

  private final TwilioTelephonyAdapter twilioAdapter;
  private final IvrEngineService ivrEngineService;
  private final IncomingCallRoutingService incomingCallRoutingService;
  private final ContactService contactService;
  private final CustomerService customerService;
  private final ContactRepository contactRepository;
  private final TwilioRecordingDownloadService recordingDownloadService;
  private final TwilioProperties twilioProperties;

  /**
   * Publiczny bazowy URL aplikacji używany do budowania action URL w TwiML
   * oraz do weryfikacji podpisu X-Twilio-Signature.
   */
  @Value("${app.base-url:http://localhost:8080}")
  private String appBaseUrl;

  /**
   * Konstruktor z opcjonalnym wstrzyknięciem adaptera.
   *
   * <p>{@code @Autowired(required = false)} – w środowisku testowym bez Twilio bean
   * może nie być dostępny. Żądania do endpointu bez skonfigurowanego adaptera
   * zwrócą 503.
   */
  public TwilioWebhookController(
      @Autowired(required = false) TwilioTelephonyAdapter twilioAdapter,
      IvrEngineService ivrEngineService,
      IncomingCallRoutingService incomingCallRoutingService,
      ContactService contactService,
      CustomerService customerService,
      ContactRepository contactRepository,
      TwilioRecordingDownloadService recordingDownloadService,
      TwilioProperties twilioProperties) {
    this.twilioAdapter = twilioAdapter;
    this.ivrEngineService = ivrEngineService;
    this.incomingCallRoutingService = incomingCallRoutingService;
    this.contactService = contactService;
    this.customerService = customerService;
    this.contactRepository = contactRepository;
    this.recordingDownloadService = recordingDownloadService;
    this.twilioProperties = twilioProperties;
  }

  @PostConstruct
  void warnIfSignatureValidationDisabled() {
    if (!twilioProperties.isSignatureValidationEnabled()) {
      log.warn("[TwilioWebhook] *** UWAGA BEZPIECZENSTWA *** " +
               "twilio.signature-validation-enabled=false — weryfikacja X-Twilio-Signature jest WYLACZONA. " +
               "Kazdy moze wyslac falszywy webhook na endpointy /voice, /status i /recording. " +
               "Uzyj wylacznie lokalnie (localhost) — NIGDY nie udostepniaj portu przez ngrok bez wlaczenia walidacji.");
    }
  }

  // =========================================================================
  // Voice URL endpoint
  // =========================================================================

  /**
   * Voice URL – wywoływany przez Twilio przy każdym połączeniu przychodzącym.
   *
   * <p>Twilio wysyła POST na ten URL natychmiast po nawiązaniu połączenia
   * i oczekuje odpowiedzi XML (TwiML) z instrukcją co zrobić z połączeniem.
   * Brak odpowiedzi lub niepoprawny Content-Type skutkuje błędem 12300 i rozłączeniem.
   *
   * <p>Endpoint jest <strong>publiczny</strong> (bez JWT) – dodany do SecurityConfig
   * i TenantFilter.PUBLIC_PATH_PREFIXES przez prefix {@code /api/telephony/webhook}.
   *
   * <p><strong>Uwaga:</strong> wyjątki obsługiwane lokalnie (try/catch) – nigdy nie
   * propagują do {@link com.contactcenter.api.GlobalExceptionHandler}, który zwróciłby
   * JSON i spowodował ponowny błąd 12300.
   *
   * <p>Konfiguracja w Twilio Console:
   * <pre>Voice URL: https://&lt;ngrok-domain&gt;/api/telephony/webhook/twilio/voice?tenantId=&lt;UUID&gt;</pre>
   *
   * @param tenantId   UUID tenanta – przekazywany jako query param w Voice URL
   * @param callSid    Twilio Call SID (np. CAxxxxxxxx)
   * @param from       numer dzwoniącego w formacie E.164
   * @param to         numer docelowy (Twilio phone number) w formacie E.164
   * @param callStatus aktualny status połączenia (np. "ringing")
   * @return TwiML instruujący Twilio aby oczekiwał na obsługę przez agenta
   */
  @PostMapping(
      value = "/voice",
      produces = MediaType.APPLICATION_XML_VALUE
  )
  @Operation(
      summary = "Voice URL – punkt wejścia połączenia przychodzącego Twilio",
      description = "Odbiera połączenie przychodzące i zwraca TwiML z instrukcją przekierowania do kolejki. " +
          "Twilio wywołuje ten endpoint natychmiast po nawiązaniu połączenia. " +
          "Odpowiedź musi być XML (TwiML) – JSON skutkuje błędem Twilio 12300.",
      responses = {
          @ApiResponse(responseCode = "200", description = "TwiML zwrócony poprawnie")
      }
  )
  public ResponseEntity<String> handleVoiceWebhook(
      HttpServletRequest httpRequest,
      @RequestParam("tenantId") UUID tenantId,
      @RequestParam(value = "CallSid", required = false) String callSid,
      @RequestParam(value = "From", required = false) String from,
      @RequestParam(value = "To", required = false) String to,
      @RequestParam(value = "CallStatus", required = false) String callStatus) {
    if (!validateTwilioSignature(httpRequest)) {
      String forbidden = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Reject/></Response>";
      return ResponseEntity.status(403).contentType(MediaType.APPLICATION_XML).body(forbidden);
    }
    try {
      log.info("[TwilioVoiceWebhook] Nowe połączenie: callSid={}, from={}, to={}, tenantId={}",
          callSid, from, to, tenantId);

      // Rozwiąż trasę PRZED utworzeniem rekordu contact – resolveRoute() jest operacją
      // tylko do odczytu (reguły harmonogramu), a wynik decyduje o statusie startowym
      // kontaktu: IVR (klient wchodzi do drzewa IVR, queuedAt=null) vs QUEUED (kierowanie
      // bezpośrednio do kolejki agentów, queuedAt=now).
      RouteResult route = incomingCallRoutingService.resolveRoute(tenantId, to, ZonedDateTime.now());
      log.info("[TwilioVoiceWebhook] Wynik routingu: callSid={}, route={}/{}", callSid, route.type(), route.targetId());

      // Utwórz rekord contact w DB przed uruchomieniem IVR, żeby RoutingService mógł
      // znaleźć kontakt po jego ID. TenantContext musi być ustawiony dla @Audited.
      UUID contactId = null;
      try {
        TenantContext.setTenantId(tenantId);
        UUID customerId = null;
        if (from != null && !from.isBlank()) {
          try {
            customerId = customerService.findByPhoneNumber(from, tenantId)
                .map(c -> c.getCustomerId())
                .orElse(null);
          }
          catch (Exception lookupEx) {
            log.warn("[TwilioVoiceWebhook] Błąd lookupa klienta dla phone={}: {}", from, lookupEx.getMessage());
          }
        }
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("sip_call_id", callSid);

        CreateContactRequest contactRequest = new CreateContactRequest(
            customerId, // customerId – lookup po numerze dzwoniącego
            null,   // agentId – nieznany, agent zostanie przypisany po odebraniu
            null,   // queueId – zostanie ustawiony przez silnik IVR/routing
            null,   // campaignId – połączenie inbound, nie kampania
            "PHONE",
            "INBOUND",
            from,   // remoteAddress – numer dzwoniącego CLI
            null,   // startedAt – domyślnie NOW()
            metadata,   // channelMetadata
            null    // callbackId – połączenie inbound, nie realizacja callbacku
        );
        // ivrEntry=true tylko gdy połączenie jest faktycznie kierowane do drzewa IVR
        // (route.isIvr()). Dla QUEUE (routing bezpośredni) i REJECT/unknown zachowujemy
        // dotychczasowe QUEUED+queuedAt=now (REJECT i tak nie trafia do IVR/kolejki,
        // ale kontakt musi mieć poprawny stan dla historii/raportów).
        boolean ivrEntry = route.isIvr();
        ContactResponse contactResponse = contactService.createContact(contactRequest, tenantId, ivrEntry);
        contactId = contactResponse.contactId();
        log.info("[TwilioVoiceWebhook] Rekord contact utworzony: contactId={}, callSid={}",
            contactId, callSid);

        // Rejestruj sesję połączenia w adapterze natychmiast po utworzeniu rekordu contact.
        // StatusCallback od Twilio może dotrzeć ze znacznym opóźnieniem lub nie dotrzeć
        // wcale przed akcją agenta (np. odebranie połączenia). Bez tej rejestracji
        // answerCall() rzuci TelephonyException "Sesja połączenia nie istnieje".
        if (twilioAdapter != null && callSid != null && contactId != null) {
          twilioAdapter.registerIncomingCall(callSid, from, to, tenantId, contactId);
        }
      }
      catch (Exception contactEx) {
        log.warn("[TwilioVoiceWebhook] Nie udało się utworzyć rekordu contact: callSid={}, error={}",
            callSid, contactEx.getMessage());
        // Nie przerywamy – IVR może działać bez rekordu contact (tryb degraded)
      }
      finally {
        TenantContext.clear();
      }

      String twiml;
      if (route.isReject() || to == null) {
        // Brak pasującej reguły lub nieznany numer – odrzuć połączenie
        twiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Reject/></Response>";
      } else if (route.isQueue()) {
        // Bezpośrednie przekierowanie do kolejki (bez IVR):
        // publikuje ContactQueuedMessage i kieruje dzwoniącego do konferencji Twilio
        twiml = ivrEngineService.routeDirectlyToQueue(callSid, tenantId, route.targetId(), contactId, appBaseUrl);
      } else {
        // IVR – uruchom konkretne drzewo IVR wskazane przez regułę routingu
        twiml = ivrEngineService.startIvrSessionAndBuildTwiml(
            callSid, tenantId, appBaseUrl, contactId, route.targetId());
      }

      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_XML)
          .body(twiml);
    }
    catch (Exception e) {
      log.error("[TwilioVoiceWebhook] Błąd uruchomienia IVR: callSid={}", callSid, e);
      // KRYTYCZNE: fallback musi być poprawnym XML – nigdy JSON
      String fallback = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say language=\"pl-PL\">Przepraszamy, wystąpił błąd techniczny. Spróbuj ponownie.</Say><Hangup/></Response>";
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(fallback);
    }
  }

  // =========================================================================
  // DTMF endpoint
  // =========================================================================

  /**
   * DTMF action URL – wywoływany przez Twilio po zebraniu cyfr w elemencie {@code <Gather>}.
   *
   * <p>Twilio wysyła POST na ten URL gdy:
   * <ul>
   *   <li>Dzwoniący naciśnie klawisze DTMF podczas trwania {@code <Gather>}</li>
   *   <li>Upłynie timeout {@code <Gather>} bez żadnego wejścia (parametry puste)</li>
   * </ul>
   *
   * <p>Endpoint jest <strong>publiczny</strong> – chroniony przez prefix
   * {@code /api/telephony/webhook} w {@link com.contactcenter.security.TenantFilter}
   * i {@link com.contactcenter.security.SecurityConfig}.
   *
   * @param tenantId UUID tenanta – przekazywany jako query param w action URL
   * @param callSid  Twilio Call SID
   * @param digits   cyfry zebrane przez {@code <Gather numDigits>} (może być null przy timeout)
   * @param digit    pojedyncza cyfra z {@code <Gather numDigits="1">} (alias Digits)
   * @return TwiML dla następnego węzła IVR
   */
  @PostMapping(value = "/dtmf", produces = MediaType.APPLICATION_XML_VALUE)
  @Operation(
      summary = "DTMF action URL – przetwarza wejście klawiszowe od dzwoniącego",
      description = "Twilio wywołuje ten endpoint po zebraniu cyfr DTMF z elementu <Gather>. " +
          "Endpoint przetwarza wejście przez silnik IVR i zwraca TwiML dla następnego węzła.",
      responses = {
          @ApiResponse(responseCode = "200", description = "TwiML zwrócony poprawnie")
      }
  )
  public ResponseEntity<String> handleDtmfWebhook(
      HttpServletRequest httpRequest,
      @RequestParam("tenantId") UUID tenantId,
      @RequestParam(value = "CallSid", required = false) String callSid,
      @RequestParam(value = "Digits", required = false) String digits,
      @RequestParam(value = "Digit", required = false) String digit) {
    if (!validateTwilioSignature(httpRequest)) {
      String forbidden = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Reject/></Response>";
      return ResponseEntity.status(403).contentType(MediaType.APPLICATION_XML).body(forbidden);
    }
    try {
      // Z actionOnEmptyResult="true" Twilio przy braku wejścia wysyła Digits="" (pusty string,
      // nie null) – w obu przypadkach (brak parametru lub pusty string) traktujemy to jako
      // "timeout" (brak wejścia), zgodnie z logiką ponawiania prób w silniku IVR.
      String rawDigits = digits != null ? digits : digit;
      String dtmfInput = (rawDigits == null || rawDigits.isEmpty()) ? "timeout" : rawDigits;
      log.info("[TwilioDtmf] DTMF input: callSid={}, input='{}', tenantId={}", callSid, dtmfInput, tenantId);
      TenantContext.setTenantId(tenantId);
      try {
        String twiml = ivrEngineService.handleDtmfAndBuildTwiml(callSid, dtmfInput, tenantId, appBaseUrl);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .body(twiml);
      }
      finally {
        TenantContext.clear();
      }
    }
    catch (Exception e) {
      log.error("[TwilioDtmf] Błąd obsługi DTMF: callSid={}, digits={}", callSid, digits, e);
      String fallback = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say language=\"pl-PL\">Nieprawidłowy wybór.</Say><Hangup/></Response>";
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(fallback);
    }
  }

  // =========================================================================
  // StatusCallback endpoint
  // =========================================================================

  /**
   * Odbiera zdarzenia statusu połączeń od Twilio (StatusCallback).
   *
   * <p>Twilio wysyła dane jako {@code application/x-www-form-urlencoded}.
   * Każda zmiana statusu połączenia (ringing, in-progress, completed itp.)
   * generuje oddzielne żądanie POST.
   *
   * @param callSid       Twilio Call SID (identyfikator połączenia)
   * @param from          numer dzwoniącego w formacie E.164
   * @param to            numer docelowy w formacie E.164
   * @param callStatus    aktualny status połączenia (np. "in-progress", "completed")
   * @param direction     kierunek połączenia ("inbound" lub "outbound-api")
   * @param tenantIdParam UUID tenanta – przekazywany jako query param lub form param w URL StatusCallback
   * @return 204 No Content przy sukcesie, 400 gdy brakuje wymaganych parametrów, 503 gdy adapter niedostępny
   */
  @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @Operation(
      summary = "Odbierz zdarzenie statusu połączenia od Twilio",
      description = "Publiczny endpoint przyjmujący zdarzenia StatusCallback od Twilio. " +
          "Dane przesyłane jako application/x-www-form-urlencoded. " +
          "Wymagane parametry: CallSid, CallStatus. Opcjonalne: From, To, Direction. " +
          "TenantId musi być podany jako parametr query string w URL StatusCallback.",
      responses = {
          @ApiResponse(responseCode = "204", description = "Zdarzenie przetworzone"),
          @ApiResponse(responseCode = "400", description = "Brakujące wymagane parametry (CallSid, CallStatus)"),
          @ApiResponse(responseCode = "503", description = "Adapter Twilio niedostępny")
      }
  )
  public ResponseEntity<Void> handleStatusCallback(
      HttpServletRequest httpRequest,
      @RequestParam(value = "CallSid", required = false) String callSid,
      @RequestParam(value = "From", required = false) String from,
      @RequestParam(value = "To", required = false) String to,
      @RequestParam(value = "CallStatus", required = false) String callStatus,
      @RequestParam(value = "Direction", required = false) String direction,
      @RequestParam(value = "ConferenceSid", required = false) String conferenceSid,
      @RequestParam(value = "tenantId", required = false) String tenantIdParam
  ) {
    if (!validateTwilioSignature(httpRequest)) {
      return ResponseEntity.status(403).build();
    }

    // Walidacja wymaganych parametrów
    if (!StringUtils.hasText(callSid)) {
      log.warn("[TwilioWebhook] Odrzucono żądanie – brakuje CallSid");
      return ResponseEntity.badRequest().build();
    }
    if (!StringUtils.hasText(callStatus)) {
      log.warn("[TwilioWebhook] Odrzucono żądanie – brakuje CallStatus. callSid={}", callSid);
      return ResponseEntity.badRequest().build();
    }
    if (twilioAdapter == null) {
      log.error("[TwilioWebhook] Adapter Twilio niedostępny – bean nie jest w kontekście");
      return ResponseEntity.status(503).build();
    }

    // Parsowanie tenantId (opcjonalne – może być null przy pierwszym połączeniu przychodzącym)
    UUID tenantId = parseTenantId(tenantIdParam, callSid);

    log.info("[TwilioWebhook] Odebrano StatusCallback: callSid={}, status={}, from={}, to={}, " +
            "direction={}, conferenceSid={}, tenantId={}",
        callSid, callStatus, from, to, direction, conferenceSid, tenantId);

    // Gdy Twilio wysyła StatusCallback dla połączenia biorącego udział w konferencji,
    // parametr ConferenceSid (CF...) pozwala powiązać nagranie konferencji z rekordem contact.
    // Zapisujemy conference_sid do channel_metadata, żeby handleRecordingCallback mógł
    // znaleźć kontakt po ConferenceSid (CallSid jest null w callbacku nagrania konferencji).
    if (StringUtils.hasText(conferenceSid) && tenantId != null) {
      try {
        TenantContext.setTenantId(tenantId);
        contactRepository.updateConferenceSidInMetadata(callSid, conferenceSid, tenantId);
      }
      catch (Exception e) {
        log.warn("[TwilioWebhook] Nie udało się zapisać conference_sid do channel_metadata: " +
                 "callSid={}, conferenceSid={}, error={}", callSid, conferenceSid, e.getMessage());
      }
      finally {
        TenantContext.clear();
      }
    }

    try {
      twilioAdapter.handleWebhookStatusUpdate(callSid, from, to, callStatus, tenantId, direction);
    }
    catch (Exception e) {
      // Logujemy błąd, ale zwracamy 204 – Twilio nie powinno ponownie wysyłać callbacku
      // przy błędach przetwarzania po naszej stronie
      log.error("[TwilioWebhook] Błąd przetwarzania statusu: callSid={}, status={}, error={}",
          callSid, callStatus, e.getMessage(), e);
    }

    return ResponseEntity.noContent().build();
  }

  // =========================================================================
  // Conference StatusCallback endpoint
  // =========================================================================

  /**
   * Odbiera callback zakończenia konferencji od Twilio (Conference StatusCallback).
   *
   * <p>Twilio wysyła POST na ten URL gdy konferencja kończy się ({@code statusCallbackEvent=end}).
   * Konfigurowany w TwiML przez atrybut {@code statusCallback} elementu {@code <Conference>}
   * generowanego przez {@link com.contactcenter.domain.service.IvrEngineService#buildWaitInConferenceTwiml}.
   *
   * <p>Używany do wykrycia porzucenia kolejki przez klienta (ABANDONED): jeśli konferencja
   * zakończyła się ({@code ConferenceStatus=completed}) a kontakt ma status {@code QUEUED} lub
   * {@code ASSIGNED}, oznacza to że klient rozłączył się przed odebraniem przez agenta.
   * Statusy {@code ACTIVE} i {@code ON_HOLD} są celowo wykluczone – konferencja dla aktywnego
   * połączenia jest zamykana przez handler hangup, który ustawia {@code COMPLETED}.
   *
   * <p>Zabezpieczenie przed duplikatami: jeśli kontakt ma już status {@code COMPLETED} lub
   * {@code ABANDONED}, nie nadpisujemy go.
   *
   * @param friendlyName    nazwa konferencji (format: {@code contact-{contactId}})
   * @param conferenceStatus status konferencji od Twilio (np. "completed")
   * @param statusCallbackEvent typ zdarzenia (np. "conference-end")
   * @param tenantIdParam   UUID tenanta z parametru query string
   * @return 204 No Content
   */
  @PostMapping(value = "/conference", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @Operation(
      summary = "Odbierz callback zakończenia konferencji od Twilio",
      description = "Twilio wysyła POST gdy konferencja kończy działanie. " +
          "Endpoint wykrywa ABANDONED – klient porzucił kolejkę przed odebraniem przez agenta.",
      responses = {
          @ApiResponse(responseCode = "204", description = "Callback przetworzony"),
          @ApiResponse(responseCode = "403", description = "Nieprawidłowy podpis X-Twilio-Signature")
      }
  )
  public ResponseEntity<Void> handleConferenceStatusCallback(
      HttpServletRequest httpRequest,
      @RequestParam(value = "FriendlyName", required = false) String friendlyName,
      @RequestParam(value = "ConferenceStatus", required = false) String conferenceStatus,
      @RequestParam(value = "StatusCallbackEvent", required = false) String statusCallbackEvent,
      @RequestParam(value = "tenantId", required = false) String tenantIdParam
  ) {
    if (!validateTwilioSignature(httpRequest)) {
      return ResponseEntity.status(403).build();
    }

    log.info("[TwilioConference] Callback zakończenia konferencji: friendlyName={}, " +
             "conferenceStatus={}, event={}, tenantId={}",
        friendlyName, conferenceStatus, statusCallbackEvent, tenantIdParam);

    // Wymagamy nazwy konferencji w formacie contact-{UUID}
    if (!org.springframework.util.StringUtils.hasText(friendlyName)
        || !friendlyName.startsWith("contact-")) {
      log.warn("[TwilioConference] FriendlyName nierozpoznany lub brakujący: {}", friendlyName);
      return ResponseEntity.noContent().build();
    }

    // Przetwarzamy tylko zdarzenie zakończenia konferencji
    boolean isConferenceEnd = "completed".equalsIgnoreCase(conferenceStatus)
        || "conference-end".equalsIgnoreCase(statusCallbackEvent);
    if (!isConferenceEnd) {
      log.debug("[TwilioConference] Pomijam event inny niż zakończenie: status={}, event={}",
          conferenceStatus, statusCallbackEvent);
      return ResponseEntity.noContent().build();
    }

    UUID tenantId = parseTenantId(tenantIdParam, friendlyName);
    if (tenantId == null) {
      log.warn("[TwilioConference] Brak tenantId – nie można zaktualizować statusu kontaktu.");
      return ResponseEntity.noContent().build();
    }

    // Wyciągnij contactId z nazwy konferencji "contact-{UUID}"
    UUID contactId;
    try {
      contactId = UUID.fromString(friendlyName.substring("contact-".length()));
    } catch (IllegalArgumentException e) {
      log.warn("[TwilioConference] Nieprawidłowy format contactId w FriendlyName='{}': {}",
          friendlyName, e.getMessage());
      return ResponseEntity.noContent().build();
    }

    try {
      TenantContext.setTenantId(tenantId);
      // Pobierz aktualny status kontaktu – zabezpieczenie przed nadpisaniem statusów terminalnych
      contactRepository.findById(contactId, tenantId).ifPresentOrElse(
          contact -> {
            String currentStatus = contact.getStatus();
            // Fix #2: TRANSFERRED dodany do listy statusów terminalnych.
            // Gdy klient był transferowany do nowej kolejki, oryginalna konferencja kończy się
            // po przekierowaniu klienta. Bez tego sprawdzenia webhook nadpisałby TRANSFERRED → ABANDONED.
            if ("COMPLETED".equals(currentStatus) || "ABANDONED".equals(currentStatus)
                || "NOT_REACHED".equals(currentStatus) || "ERROR".equals(currentStatus)
                || "TRANSFERRED".equals(currentStatus)) {
              log.debug("[TwilioConference] Kontakt już zakończony lub transferowany (status={}), pomijam: contactId={}",
                  currentStatus, contactId);
              return;
            }
            // ASSIGNED oznacza że routing przydzielił agenta, ale agent jeszcze nie odebrał.
            // ACTIVE/ON_HOLD = agent już rozmawiał → kończy go handler hangup (→ COMPLETED).
            // Conference-end nie może ustawić ABANDONED gdy agent był podłączony.
            if ("QUEUED".equals(currentStatus) || "ASSIGNED".equals(currentStatus)) {
              log.info("[TwilioConference] Konferencja zakończona, kontakt w statusie {} – " +
                       "ustawiam ABANDONED: contactId={}", currentStatus, contactId);
              // Conditional update zapobiega race z handlerem hangup ustawiającym COMPLETED.
              contactRepository.updateContactStatusIfNotTerminal(
                  contactId, tenantId, "ABANDONED", Instant.now());
            } else {
              log.debug("[TwilioConference] Kontakt w statusie {} – nie zmieniam: contactId={}",
                  currentStatus, contactId);
            }
          },
          () -> log.warn("[TwilioConference] Kontakt nie znaleziony: contactId={}, tenantId={}",
              contactId, tenantId)
      );
    } catch (Exception e) {
      log.error("[TwilioConference] Błąd aktualizacji statusu kontaktu: contactId={}, error={}",
          contactId, e.getMessage(), e);
    } finally {
      TenantContext.clear();
    }

    return ResponseEntity.noContent().build();
  }

  // =========================================================================
  // Recording callback endpoint
  // =========================================================================

  /**
   * Odbiera callback nagrania konferencji od Twilio (recordingStatusCallback).
   *
   * <p>Twilio wysyła POST na ten URL po zakończeniu nagrania konferencji.
   * Endpoint jest <strong>publiczny</strong> – objęty przez prefix
   * {@code /api/telephony/webhook} w SecurityConfig i TenantFilter.
   *
   * <p>Logika:
   * <ol>
   *   <li>Loguje odbiór nagrania.</li>
   *   <li>Gdy {@code RecordingStatus=completed} i {@code RecordingUrl} != null,
   *       wyszukuje kontakt po {@code callSid} lub {@code ConferenceSid} przez
   *       {@code channel_metadata->>'sip_call_id'} i zapisuje URL nagrania.</li>
   *   <li>Zwraca 204 No Content – Twilio nie ponawia callbacku przy 2xx.</li>
   * </ol>
   *
   * @param callSid           Twilio Call SID (może być null dla nagrań konferencji)
   * @param recordingSid      Twilio Recording SID
   * @param recordingUrl      URL nagrania (bez rozszerzenia); Twilio zwraca .mp3 po dopisaniu
   * @param recordingDuration czas trwania nagrania w sekundach
   * @param recordingStatus   status nagrania (np. "completed", "failed")
   * @param conferenceSid     SID konferencji (gdy nagranie pochodzi z konferencji)
   * @param tenantIdParam     UUID tenanta z parametru query string
   * @return 204 No Content
   */
  @PostMapping(value = "/recording", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @Operation(
      summary = "Odbierz callback nagrania konferencji od Twilio",
      description = "Twilio wysyła POST po zakończeniu nagrania. Endpoint zleca async pobieranie nagrania i zwraca 204 natychmiast.",
      responses = {
          @ApiResponse(responseCode = "204", description = "Callback przetworzony"),
          @ApiResponse(responseCode = "403", description = "Nieprawidłowy podpis X-Twilio-Signature")
      }
  )
  public ResponseEntity<Void> handleRecordingCallback(
      HttpServletRequest httpRequest,
      @RequestParam(value = "CallSid", required = false) String callSid,
      @RequestParam(value = "RecordingSid", required = false) String recordingSid,
      @RequestParam(value = "RecordingUrl", required = false) String recordingUrl,
      @RequestParam(value = "RecordingDuration", required = false) String recordingDuration,
      @RequestParam(value = "RecordingStatus", required = false) String recordingStatus,
      @RequestParam(value = "ConferenceSid", required = false) String conferenceSid,
      @RequestParam(value = "tenantId", required = false) String tenantIdParam
  ) {
    if (!validateTwilioSignature(httpRequest)) {
      return ResponseEntity.status(403).build();
    }

    log.info("[TwilioRecording] Odebrano callback nagrania: callSid={}, recordingSid={}, " +
             "recordingStatus={}, recordingDuration={}s, conferenceSid={}",
        callSid, recordingSid, recordingStatus, recordingDuration, conferenceSid);

    if (!"completed".equalsIgnoreCase(recordingStatus)) {
      log.debug("[TwilioRecording] Status nagrania != completed ({}), pomijam.", recordingStatus);
      return ResponseEntity.noContent().build();
    }

    if (!StringUtils.hasText(recordingUrl)) {
      log.warn("[TwilioRecording] Brak RecordingUrl przy status=completed. callSid={}", callSid);
      return ResponseEntity.noContent().build();
    }

    UUID tenantId = parseTenantId(tenantIdParam, callSid != null ? callSid : conferenceSid);
    if (tenantId == null) {
      log.warn("[TwilioRecording] Brak tenantId – nie można zaktualizować recording_url. " +
               "callSid={}, conferenceSid={}", callSid, conferenceSid);
      return ResponseEntity.noContent().build();
    }

    if (!StringUtils.hasText(callSid) && !StringUtils.hasText(conferenceSid)) {
      log.warn("[TwilioRecording] Brak callSid i conferenceSid – nie można znaleźć kontaktu.");
      return ResponseEntity.noContent().build();
    }

    // Twilio zwraca URL bez rozszerzenia – dopisz .mp3
    String twilioMp3Url = recordingUrl.endsWith(".mp3") ? recordingUrl : recordingUrl + ".mp3";

    // Całe rozwiązywanie contactId (w tym potencjalne wywołanie Twilio Conference API)
    // odbywa się w wątku @Async w TwilioRecordingDownloadService.
    // Webhook zwraca 204 natychmiast – timeout Twilio SDK (30s) nie blokuje Tomcata.
    log.info("[TwilioRecording] Zlecam async pobieranie nagrania: callSid={}, conferenceSid={}, " +
             "recordingSid={}, duration={}s", callSid, conferenceSid, recordingSid, recordingDuration);
    recordingDownloadService.downloadAndStore(
        twilioMp3Url, recordingSid, callSid, conferenceSid, null, tenantId);

    return ResponseEntity.noContent().build();
  }

  // =========================================================================
  // Voicebot recording endpoint
  // =========================================================================

  /**
   * Odbiera callback nagrania voicebota od Twilio ({@code <Record action="...">}).
   *
   * <p>Twilio wysyła POST na ten URL po zakończeniu nagrania wypowiedzi dzwoniącego
   * w węźle VOICEBOT. Endpoint pobiera nagranie, koduje do Base64, wywołuje serwis
   * Python ASR+NLU przez {@link IvrEngineService#handleVoicebotRecordingAndBuildTwiml}
   * i zwraca TwiML z instrukcją dla Twilio co zrobić dalej.
   *
   * <p>Endpoint jest <strong>publiczny</strong> – objęty przez prefix
   * {@code /api/telephony/webhook} w SecurityConfig i TenantFilter.
   *
   * <p>Odpowiedź musi być XML (TwiML) z {@code Content-Type: application/xml}.
   * Błąd (np. 5xx lub JSON) skutkuje rozłączeniem połączenia przez Twilio.
   *
   * @param callSid           Twilio Call SID
   * @param recordingUrl      URL nagrania (bez rozszerzenia); dopisywane jest {@code .wav}
   * @param recordingDuration czas trwania nagrania w sekundach
   * @param tenantIdParam     UUID tenanta z parametru query string (przekazany w action URL)
   * @param callId            Twilio Call SID powtórzony jako query param (dla spójności z action URL)
   * @return TwiML dla kolejnego kroku IVR lub TwiML fallback przy błędzie
   */
  @PostMapping(value = "/voicebot-recording",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.APPLICATION_XML_VALUE)
  @Operation(
      summary = "Voicebot recording callback – przetwarza nagranie wypowiedzi przez ASR+NLU",
      description = "Twilio wywołuje ten endpoint po zakończeniu nagrania dzwoniącego w węźle VOICEBOT. " +
          "Endpoint pobiera nagranie, uruchamia silnik voicebota i zwraca TwiML dla następnego węzła IVR.",
      responses = {
          @ApiResponse(responseCode = "200", description = "TwiML zwrócony poprawnie"),
          @ApiResponse(responseCode = "403", description = "Nieprawidłowy podpis X-Twilio-Signature")
      }
  )
  public ResponseEntity<String> handleVoicebotRecording(
      HttpServletRequest httpRequest,
      @RequestParam(value = "CallSid", required = false) String callSid,
      @RequestParam(value = "RecordingUrl", required = false) String recordingUrl,
      @RequestParam(value = "RecordingDuration", required = false) String recordingDuration,
      @RequestParam(value = "tenantId") String tenantIdParam,
      @RequestParam(value = "callId") String callId
  ) {
    if (!validateTwilioSignature(httpRequest)) {
      String forbidden = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Reject/></Response>";
      return ResponseEntity.status(403).contentType(MediaType.APPLICATION_XML).body(forbidden);
    }

    // Użyj callSid z form-body jako canoniczny identyfikator;
    // query param callId jest fallbackiem gdy Twilio nie wyśle CallSid w POST body
    String resolvedCallId = org.springframework.util.StringUtils.hasText(callSid) ? callSid : callId;

    log.info("[TwilioVoicebot] Odebrano callback nagrania: callSid={}, callId={}, " +
             "recordingUrl={}, duration={}s, tenantId={}",
        callSid, callId, recordingUrl, recordingDuration, tenantIdParam);

    UUID tenantId;
    try {
      tenantId = UUID.fromString(tenantIdParam);
    } catch (IllegalArgumentException e) {
      log.warn("[TwilioVoicebot] Nieprawidłowy tenantId='{}' w callbacku nagrania voicebota.", tenantIdParam);
      String fallback = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Response><Say language=\"pl-PL\">Przepraszamy, wystąpił błąd. Spróbuj ponownie.</Say><Hangup/></Response>";
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(fallback);
    }

    try {
      TenantContext.setTenantId(tenantId);
      String twiml = ivrEngineService.handleVoicebotRecordingAndBuildTwiml(
          resolvedCallId, tenantId, appBaseUrl, recordingUrl, recordingDuration);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_XML)
          .body(twiml);
    } catch (Exception e) {
      log.error("[TwilioVoicebot] Błąd obsługi nagrania voicebota: callId={}, error={}",
          resolvedCallId, e.getMessage(), e);
      String fallback = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Response><Say language=\"pl-PL\">Przepraszamy, wystąpił błąd techniczny.</Say><Hangup/></Response>";
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(fallback);
    } finally {
      TenantContext.clear();
    }
  }

  // =========================================================================
  // Metody pomocnicze
  // =========================================================================

  /**
   * Weryfikuje podpis HMAC {@code X-Twilio-Signature} żądania webhooka.
   *
   * <p>Twilio podpisuje każde żądanie używając HMAC-SHA1 z Auth Token jako kluczem.
   * Brak weryfikacji pozwala atakującemu wysłać fałszywy recording callback z dowolnym
   * {@code RecordingUrl}, co prowadzi do SSRF: serwer wyśle HTTP GET z Basic Auth
   * credentials Twilio do hosta kontrolowanego przez atakującego.
   *
   * <p>Gdy {@code twilio.signature-validation-enabled=false} (profil dev),
   * weryfikacja jest pomijana i metoda zawsze zwraca {@code true}.
   *
   * <p>URL użyty do weryfikacji musi być identyczny z URL skonfigurowanym w Twilio Console.
   * Używamy {@code app.base-url} + ścieżki żądania.
   *
   * @param request HTTP request od Twilio
   * @return {@code true} gdy podpis jest prawidłowy lub walidacja jest wyłączona
   */
  private boolean validateTwilioSignature(HttpServletRequest request) {
    if (!twilioProperties.isSignatureValidationEnabled()) {
      log.debug("[TwilioWebhook] Weryfikacja X-Twilio-Signature wyłączona (dev mode).");
      return true;
    }

    String twilioSignature = request.getHeader("X-Twilio-Signature");
    if (!StringUtils.hasText(twilioSignature)) {
      log.warn("[TwilioWebhook] Brak nagłówka X-Twilio-Signature – odrzucam żądanie.");
      return false;
    }

    // Budujemy URL identyczny z tym zarejestrowanym w Twilio Console.
    // query string musi być pominięty – Twilio nie dodaje go do podpisu dla POST.
    String requestUrl = appBaseUrl + request.getRequestURI();

    // Zbieramy parametry POST (form-encoded) jako mapę – Twilio używa ich do podpisu.
    // getParameterMap() łączy parametry POST i GET (query string). Twilio podpisuje TYLKO
    // parametry POST – parametry query string (np. ?tenantId=UUID) są częścią URL,
    // który jest osobno uwzględniany w podpisie HMAC i nie mogą być powielane w mapie params.
    java.util.Set<String> queryKeys = new java.util.HashSet<>();
    String qs = request.getQueryString();
    if (qs != null && !qs.isBlank()) {
      for (String pair : qs.split("&")) {
        int eqIdx = pair.indexOf('=');
        String key = eqIdx >= 0 ? pair.substring(0, eqIdx) : pair;
        queryKeys.add(java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8));
      }
    }

    Map<String, String> params = new HashMap<>();
    request.getParameterMap().forEach((key, values) -> {
      if (!queryKeys.contains(key) && values != null && values.length > 0) {
        params.put(key, values[0]);
      }
    });

    try {
      RequestValidator validator = new RequestValidator(twilioProperties.getAuthToken());
      boolean valid = validator.validate(requestUrl, params, twilioSignature);
      if (!valid) {
        log.warn("[TwilioWebhook] Nieprawidłowy X-Twilio-Signature dla URL={}. " +
                 "Możliwa próba sfałszowania webhooka.", requestUrl);
      }
      return valid;
    } catch (Exception e) {
      log.error("[TwilioWebhook] Błąd weryfikacji X-Twilio-Signature: {}", e.getMessage(), e);
      return false;
    }
  }

  /**
   * Parsuje tenantId z parametru query string.
   *
   * <p>Gdy parametr jest pusty lub nieprawidłowy, loguje ostrzeżenie i zwraca null.
   * TenantId null oznacza połączenie bez przypisanego tenanta – event zostanie
   * opublikowany bez tenantId, co może skutkować ominięciem przez konsumentów RabbitMQ.
   *
   * @param tenantIdParam wartość parametru tenantId (może być null)
   * @param callSid       callSid do celów logowania
   * @return UUID tenanta lub null
   */
  private UUID parseTenantId(String tenantIdParam, String callSid) {
    if (!StringUtils.hasText(tenantIdParam)) {
      log.warn("[TwilioWebhook] Brak tenantId w żądaniu dla callSid={}. " +
          "Dodaj ?tenantId=UUID do URL StatusCallback w konfiguracji Twilio.", callSid);
      return null;
    }
    try {
      return UUID.fromString(tenantIdParam);
    }
    catch (IllegalArgumentException e) {
      log.warn("[TwilioWebhook] Nieprawidłowy format tenantId='{}' dla callSid={}",
          tenantIdParam, callSid);
      return null;
    }
  }
}
