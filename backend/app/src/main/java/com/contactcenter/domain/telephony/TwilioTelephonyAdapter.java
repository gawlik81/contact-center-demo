package com.contactcenter.domain.telephony;

import com.contactcenter.domain.event.TwilioConfigChangedEvent;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.Customer;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.CustomerRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.domain.routing.DirectAgentAssignmentMessage;
import com.contactcenter.domain.service.CliLookupService;
import com.contactcenter.domain.service.ContactEventService;
import com.contactcenter.domain.service.CustomerCliResult;
import com.contactcenter.domain.service.TenantTwilioConfigDecrypted;
import com.contactcenter.domain.service.TenantTwilioConfigService;
import com.contactcenter.domain.service.TwilioRecordingDownloadService;
import com.contactcenter.domain.service.UserService;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.infrastructure.config.RedisConfig;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.security.TenantContext;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import com.twilio.base.ResourceSet;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.Conference;
import com.twilio.rest.api.v2010.account.IncomingPhoneNumber;
import com.twilio.rest.api.v2010.account.call.Recording;
import com.twilio.rest.api.v2010.account.conference.Participant;
import com.twilio.type.PhoneNumber;
import com.twilio.type.Twiml;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Implementacja adaptera telefonii oparta na Twilio Programmable Voice REST API.
 *
 * <p>Aktywna gdy {@code twilio.enabled=true}. Oznaczona jako {@code @Primary},
 * dzięki czemu zastępuje {@link MockTelephonyAdapter} gdy Twilio jest włączone.
 * Gdy {@code twilio.enabled=false} (domyślnie), ten bean nie jest tworzony,
 * a {@link MockTelephonyAdapter} pozostaje jedyną implementacją.
 *
 * <h2>Stan sesji</h2>
 * <p>Stan sesji połączeń jest przechowywany w Redis (klucz: {@code call-session:{callSid}}, TTL 24h).
 * Twilio jest źródłem prawdy o statusie połączenia – zmiany statusu docierają
 * przez webhook ({@code POST /api/telephony/webhook/twilio}) i aktualizują sesję w Redis.
 * Przechowywanie w Redis zapewnia przeżycie restartu aplikacji i poprawną obsługę
 * callbacków Twilio docierających do ~2 min po zakończeniu rozmowy.
 *
 * <h2>Hold/Mute</h2>
 * <p>Hold realizowany przez Twilio Conference Participant API ({@code hold=true/false} na nodze klienta).
 * Mute realizowany przez Twilio Conference Participant API ({@code muted=true/false} na nodze agenta).
 * Obie operacje wyszukują aktywną konferencję po friendly name ({@code contact-{contactId}})
 * i aktualizują odpowiedniego uczestnika przez {@link com.twilio.rest.api.v2010.account.conference.Participant#updater(String, String)}.
 *
 * <h2>Transfer</h2>
 * <p>Blind transfer: przekierowanie przez aktualizację TwiML z {@code <Dial>}.
 * Attended transfer: inicjacja nowego połączenia wychodzącego do target,
 * następnie {@link #bridgeCalls} łączy obie nogi przez {@code <Conference>}.
 *
 * <h2>Bezpieczeństwo wątków</h2>
 * <p>Twilio SDK jest thread-safe. Operacje Redis przez {@code RedisTemplate} są thread-safe.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
public class TwilioTelephonyAdapter implements TelephonyAdapter {

  /** Prefix klucza Redis dla sesji połączeń: {@code call-session:{callSid}}. */
  private static final String SESSION_KEY_PREFIX = "call-session:";

  /**
   * Indeks odwrotny: {@code contact-session-index:{contactId}} → Twilio CallSid (String).
   *
   * <p>Pozwala znaleźć sesję Redis po UUID kontaktu (DB) gdy frontend przekazuje {@code contactId}
   * zamiast {@code callSid}. Indeks jest tworzony atomowo w {@link #saveSession} za każdym razem
   * gdy {@link CallSession#getContactId()} nie jest null. Dzięki temu {@link #requireSession}
   * może znaleźć właściwy {@code callSid} nawet gdy {@code channel_metadata->>'sip_call_id'}
   * jest tymczasowo null w bazie danych (race condition / błąd zapisu DB).
   */
  private static final String CONTACT_SESSION_INDEX_PREFIX = "contact-session-index:";

  private final TwilioProperties twilioProperties;
  private final TelephonyEventPublisher eventPublisher;
  private final ContactRepository contactRepository;
  private final CustomerRepository customerRepository;
  private final TenantRepository tenantRepository;
  private final RedisTemplate<String, Object> redisTemplate;
  /** Używany wyłącznie dla prostych kluczy String (indeks odwrotny contactId → callSid). */
  private final StringRedisTemplate stringRedisTemplate;
  private final TwilioRecordingDownloadService recordingDownloadService;
  private final TenantTwilioConfigService tenantTwilioConfigService;
  private final ContactEventService contactEventService;
  /** Repozytorium użytkowników – wymagane do lookup agenta przy transferze AGENT. */
  private final AppUserRepository appUserRepository;
  /** Repozytorium kolejek – wymagane do lookup kolejki przy transferze QUEUE. */
  private final QueueRepository queueRepository;
  /** Używany do publikacji eventu contact.queued po transferze do kolejki. */
  private final RabbitTemplate rabbitTemplate;
  /** CLI lookup – rozpoznawanie klienta po numerze telefonu (użyty przy CALL_TRANSFER_CONSULT). */
  private final CliLookupService cliLookupService;
  /**
   * Wymagany do ustawiania statusu Agent2 na AVAILABLE po anulowaniu konsultacji.
   *
   * <p>Wstrzyknięty przez setter z {@code @Lazy} aby uniknąć circular dependency:
   * TwilioTelephonyAdapter → UserService → ContactService → TelephonyAdapter (→ TwilioTelephonyAdapter).
   */
  private UserService userService;

  /**
   * Bazowy URL aplikacji (np. https://example.com) – wymagany do budowania TwiML
   * dla transferu do kolejki (URL callbacków konferencji i muzyki oczekiwania).
   */
  @Value("${app.base-url:http://localhost:8080}")
  private String appBaseUrl;

  /**
   * Cache per-tenant TwilioRestClient (max 100, TTL 15 min).
   * Inicjowany w {@link #init()} – nie jest final, żeby można było wywołać init() w testach.
   */
  private Cache<UUID, TwilioRestClient> clientCache;

  // =========================================================================
  // Inicjalizacja
  // =========================================================================

  /**
   * Inicjalizuje Twilio SDK przy starcie beana.
   * Weryfikuje obecność wymaganych konfiguracji przed inicjalizacją.
   *
   * @throws IllegalStateException gdy accountSid lub authToken jest pusty
   */
  @PostConstruct
  public void init() {
    if (!StringUtils.hasText(twilioProperties.getAccountSid())) {
      throw new IllegalStateException(
          "[TwilioAdapter] twilio.account-sid jest wymagany gdy twilio.enabled=true");
    }
    if (!StringUtils.hasText(twilioProperties.getAuthToken())) {
      throw new IllegalStateException(
          "[TwilioAdapter] twilio.auth-token jest wymagany gdy twilio.enabled=true");
    }

    clientCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .build();

    log.info("[TwilioAdapter] Zainicjalizowany z per-tenant TwilioRestClient cache (max=100, ttl=15min). " +
             "accountSid={}..., phoneNumber={}",
        maskSid(twilioProperties.getAccountSid()),
        twilioProperties.getPhoneNumber());

    configureStatusCallbacksForAllTenants();
  }

  /**
   * Setter injection z {@code @Lazy} rozwiązuje circular dependency:
   * TwilioTelephonyAdapter → UserService → ContactService → TelephonyAdapter → TwilioTelephonyAdapter.
   */
  @Lazy
  @Autowired
  public void setUserService(UserService userService) {
    this.userService = userService;
  }

  /**
   * Konfiguruje StatusCallback URL i StatusCallbackEvent dla wszystkich numerów Twilio
   * przypisanych do aktywnych tenantów w bazie danych.
   *
   * <p>Wywoływana przy starcie beana, po inicjalizacji SDK. Operacja jest idempotentna –
   * wielokrotne uruchomienie nadpisuje te same wartości w Twilio bez skutków ubocznych.
   *
   * <p>Błąd konfiguracji dla pojedynczego numeru jest logowany jako WARN i nie przerywa
   * przetwarzania pozostałych numerów. Błąd krytyczny (np. utrata połączenia z Twilio API)
   * jest logowany jako ERROR, ale nie blokuje startu aplikacji.
   *
   * <p>Metoda pobiera tenantów cross-tenant (bez filtrowania RLS), ponieważ operuje
   * w kontekście startu aplikacji, a nie w kontekście konkretnego tenanta.
   */
  private void configureStatusCallbacksForAllTenants() {
    if (!twilioProperties.isEnabled()) {
      return;
    }

    log.info("[TwilioAdapter] Rozpoczynam konfigurację StatusCallback dla wszystkich aktywnych tenantów...");

    try {
      List<Tenant> activeTenants = tenantRepository.findAllByOptionalFilters(null,
          Tenant.TenantStatus.ACTIVE.name());

      if (activeTenants.isEmpty()) {
        log.info("[TwilioAdapter] Brak aktywnych tenantów – pomijam konfigurację StatusCallback.");
        return;
      }

      int configured = 0;
      int skipped = 0;

      for (Tenant tenant : activeTenants) {
        String phoneNumber = tenant.getTwilioPhoneNumber();
        if (!StringUtils.hasText(phoneNumber)) {
          log.debug("[TwilioAdapter] Tenant {} nie ma skonfigurowanego numeru Twilio – pomijam.",
              tenant.getId());
          skipped++;
          continue;
        }

        try {
          String callbackUrl = buildStatusCallbackUrl(tenant.getId());
          if (!StringUtils.hasText(callbackUrl)) {
            log.warn("[TwilioAdapter] Brak StatusCallback URL dla tenanta {} (numer: {}) – pomijam.",
                tenant.getId(), phoneNumber);
            skipped++;
            continue;
          }

          // Wyszukaj numer w koncie Twilio po numerze E.164
          TwilioRestClient tenantClient = resolveRestClient(tenant.getId());
          var phoneNumbers = IncomingPhoneNumber.reader()
              .setPhoneNumber(new PhoneNumber(phoneNumber))
              .read(tenantClient);

          if (phoneNumbers == null || !phoneNumbers.iterator().hasNext()) {
            log.warn("[TwilioAdapter] Numer {} (tenant {}) nie znaleziony w koncie Twilio – pomijam.",
                phoneNumber, tenant.getId());
            skipped++;
            continue;
          }

          IncomingPhoneNumber found = phoneNumbers.iterator().next();
          String sid = found.getSid();

          // Twilio Java SDK (10.x) nie obsługuje StatusCallbackEvent na IncomingPhoneNumberUpdater.
          // Ustawiamy URL przez SDK, a eventy przez bezpośrednie wywołanie REST API (Java HttpClient).
          IncomingPhoneNumber.updater(sid)
              .setStatusCallback(URI.create(callbackUrl))
              .setStatusCallbackMethod(com.twilio.http.HttpMethod.POST)
              .update(tenantClient);

          setStatusCallbackEvents(sid, callbackUrl, tenant.getId());

          log.info("[TwilioAdapter] Skonfigurowano StatusCallback + StatusCallbackEvents dla numeru {}, tenant {}",
              phoneNumber, tenant.getId());
          configured++;

        } catch (ApiException e) {
          log.warn("[TwilioAdapter] Błąd Twilio API dla numeru {} (tenant {}): code={}, message={} – kontynuuję.",
              phoneNumber, tenant.getId(), e.getCode(), e.getMessage());
        } catch (Exception e) {
          log.warn("[TwilioAdapter] Nieoczekiwany błąd dla numeru {} (tenant {}): {} – kontynuuję.",
              phoneNumber, tenant.getId(), e.getMessage());
        }
      }

      log.info("[TwilioAdapter] Konfiguracja StatusCallback zakończona: skonfigurowano={}, pominięto={}",
          configured, skipped);

    } catch (Exception e) {
      log.error("[TwilioAdapter] Krytyczny błąd podczas konfiguracji StatusCallback – start aplikacji kontynuowany: {}",
          e.getMessage(), e);
    }
  }

  // =========================================================================
  // TelephonyAdapter implementation
  // =========================================================================

  /**
   * {@inheritDoc}
   *
   * <p>Inicjuje wychodzące połączenie przez Twilio REST API.
   * Numer {@code from} jest zastępowany przez numer Twilio z konfiguracji
   * ({@code twilio.phone-number}) – Twilio wymaga numeru zweryfikowanego w konsoli.
   * Przekazany parametr {@code from} jest logowany, ale ignorowany w wywołaniu API.
   *
   * <p>Odpowiedź TwiML pod {@code statusCallbackUrl} definiuje zachowanie po odebraniu.
   *
   * @throws TelephonyException gdy Twilio API zwróci błąd lub brak numeru telefonu w konfiguracji
   */
  @Override
  public CallSession initiateCall(UUID tenantId, String from, String to, UUID agentId, UUID campaignId, UUID callbackId) {
    log.info("[TwilioAdapter] Inicjuję połączenie wychodzące: tenantId={}, from={}, to={}, agentId={}, campaignId={}, callbackId={}",
        tenantId, from, to, agentId, campaignId, callbackId);

    // contactId wyciągnięte poza try – potrzebne w catch do oznaczenia Contact jako ERROR
    // gdy Twilio API rzuci ApiException po tym jak rekord kontaktu już istnieje w DB.
    UUID contactId = null;

    try {
      String twilioFrom = resolvePhoneNumber(tenantId);

      // contactId jest generowany PRZED wywołaniem Twilio API, żeby nazwa konferencji
      // była identyczna z tą używaną przez dialAgentIntoConference() ("contact-{contactId}").
      // Bez tej spójności klient ląduje w innej konferencji niż agent → brak audio.
      //
      // Kolejność: generate contactId → build TwiML z "contact-{contactId}" → Twilio API
      // (uzyskujemy callSid) → persistOutboundContact() z gotowym callSid i pre-wygenerowanym contactId.
      // Dzięki temu:
      // 1. sip_call_id jest ustawiony atomowo podczas INSERT (brak stanu "null callSid").
      // 2. handleWebhookStatusUpdate() zawsze znajdzie contact po callSid → nie tworzy
      //    duplikatu INBOUND nawet gdy webhook dotrze przed powrotem z creator.create().
      // 3. Wyścig eliminowany: direction=outbound-api z Twilio + sesja Redis zabezpieczają
      //    przed INBOUND niezależnie od czasu dostarczenia pierwszego StatusCallback.
      contactId = java.util.UUID.randomUUID();
      String tempConferenceName = "contact-" + contactId;

      String rawBase = buildRawWebhookBaseUrl(tenantId);
      StringBuilder conferenceAttrs = new StringBuilder();
      // endConferenceOnExit="true" – klient rozłączając się (lub po rozłączeniu przez hangupCall)
      // kończy konferencję, co wyzwala callbacki /conference i /recording po stronie Twilio.
      // Bez tego atrybutu konferencja trwałaby do czasu wyjścia ostatniego uczestnika.
      conferenceAttrs.append("startConferenceOnEnter=\"false\" endConferenceOnExit=\"true\"");
      if (rawBase != null) {
        // URL callbacku konferencji — konferencja nosi nazwę "contact-{contactId}" (już wygenerowane wyżej).
        String confStatusCallbackUrl = rawBase + "/conference?tenantId=" + tenantId;
        conferenceAttrs.append(" statusCallback=\"").append(confStatusCallbackUrl).append("\"");
        conferenceAttrs.append(" statusCallbackEvent=\"end\"");
        conferenceAttrs.append(" statusCallbackMethod=\"POST\"");
      }

      String outboundTwiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Response><Dial>"
          + "<Conference " + conferenceAttrs + ">"
          + tempConferenceName
          + "</Conference>"
          + "</Dial></Response>";

      var creator = Call.creator(
          new PhoneNumber(to),
          new PhoneNumber(twilioFrom),
          new Twiml(outboundTwiml)
      );

      if (rawBase != null) {
        creator.setStatusCallback(URI.create(rawBase + "?tenantId=" + tenantId));
        creator.setStatusCallbackMethod(com.twilio.http.HttpMethod.POST);
        // Dozwolone eventy dla outbound calls: initiated, ringing, answered, completed.
        // "canceled" jest niedozwolony dla połączeń wychodzących (Twilio error 21626) –
        // obowiązuje wyłącznie dla inbound (klient rozłącza się w kolejce → ABANDONED).
        creator.setStatusCallbackEvent(java.util.List.of(
            "initiated", "ringing", "answered", "completed"));
      }

      Call call = creator.create(resolveRestClient(tenantId));
      String callSid = call.getSid();

      // Utwórz rekord Contact PO uzyskaniu callSid z Twilio API, żeby sip_call_id
      // był dostępny atomowo od razu w INSERT. Eliminuje to okno czasowe gdy
      // StatusCallback mógłby dotrzeć zanim backfill uzupełni callSid w DB.
      // contactId zostało wygenerowane wcześniej i użyte jako nazwa konferencji w TwiML —
      // persistOutboundContact() używa tego samego UUID, zapewniając spójność z dialAgentIntoConference().
      UUID persistResult = persistOutboundContact(tenantId, from, to, agentId, campaignId, callbackId, callSid, contactId);
      if (persistResult == null) {
        // Nadpisz tylko gdy persist się nie powiódł (null oznacza błąd DB), żeby zmienna
        // contactId w catch była null — analogicznie jak dotychczas.
        contactId = null;
      }

      log.info("[TwilioAdapter] Połączenie wychodzące zainicjowane: callSid={}, status={}, to={}, contactId={}",
          callSid, call.getStatus(), to, contactId);

      CallSession session = CallSession.builder()
          .callId(callSid)
          .tenantId(tenantId)
          .agentId(agentId)
          .from(from)
          .to(to)
          .status(mapTwilioStatus(call.getStatus()))
          .startedAt(Instant.now())
          .contactId(contactId)
          .direction("OUTBOUND")
          .customerCallSid(callSid)  // SID wychodzącego połączenia klienta (CA...)
          .build();

      saveSession(session);

      // Publikuj CALL_OUTBOUND (nie CALL_INCOMING) – frontend odróżnia kierunek po typie eventu
      eventPublisher.publishOutbound(callSid, contactId, tenantId, agentId, from, to);

      return session;

    }
    catch (ApiException e) {
      log.error("[TwilioAdapter] Błąd Twilio API przy initiateCall: to={}, code={}, message={}, contactId={}",
          to, e.getCode(), e.getMessage(), contactId, e);
      // Jeśli rekord Contact został już zapisany (contactId != null), oznacz go jako ERROR,
      // aby nie pozostał na zawsze w statusie QUEUED (co fałszuje metryki kolejki).
      if (contactId != null) {
        try {
          contactRepository.updateContactStatusOnTelephonyEvent(contactId, tenantId, "ERROR", null);
          log.info("[TwilioAdapter] Kontakt {} oznaczony jako ERROR po błędzie Twilio API (code={})",
              contactId, e.getCode());
        } catch (Exception updateEx) {
          log.warn("[TwilioAdapter] Nie udało się zaktualizować statusu kontaktu {} na ERROR: {}",
              contactId, updateEx.getMessage());
        }
      }
      throw new TelephonyException(contactId != null ? contactId.toString() : null,
          "Nie można zainicjować połączenia przez Twilio: " + e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Odebranie połączenia realizowane jest dwuetapowo:
   * <ol>
   *   <li>Aktualizacja lokalnego stanu sesji na ACTIVE.</li>
   *   <li>Wywołanie Twilio REST API – agent wchodzi do nazwanej konferencji Twilio
   *       jako moderator ({@code startConferenceOnEnter="true"}), co powoduje start
   *       konferencji i połączenie audio z klientem czekającym w tej samej konferencji.</li>
   * </ol>
   *
   * <p>Nazwa konferencji: {@code contact-{contactId}} – zgodna z TwiML generowanym przez
   * {@link com.contactcenter.domain.service.IvrEngineService#buildWaitInConferenceTwiml}.
   *
   * <p>Błąd zestawiania połączenia z agentem jest logowany jako ERROR, ale nie przerywa
   * przepływu – lokalny stan sesji pozostaje ACTIVE.
   *
   * @throws TelephonyException gdy sesja nie istnieje lub połączenie jest już zakończone
   */
  @Override
  public void answerCall(String callId, UUID agentId) {
    CallSession session = requireSession(callId);

    if (session.getStatus() == CallSession.CallStatus.ENDED) {
      throw new TelephonyException(callId, "Nie można odebrać zakończonego połączenia: " + callId);
    }
    if (session.getStatus() == CallSession.CallStatus.ACTIVE) {
      log.debug("[TwilioAdapter] Połączenie {} już aktywne, ignoruję answerCall", callId);
      return;
    }

    // Uzupełnij agentId w sesji – może być null dla połączeń przychodzących przed przydziałem
    CallSession updated = session
        .withStatus(CallSession.CallStatus.ACTIVE)
        .withAnsweredAt(Instant.now())
        .withAgentId(agentId != null ? agentId : session.getAgentId());
    saveSession(updated);

    log.info("[TwilioAdapter] Połączenie odebrane (lokalny stan): callId={}, tenant={}, agentId={}",
        callId, updated.getTenantId(), updated.getAgentId());

    // Zestawianie audio przez Twilio Conference – agent wchodzi do konferencji jako moderator.
    // Wymaga contactId (do nazwy konferencji) i agentId (do tożsamości Twilio Client).
    //
    // OUTBOUND race condition fix:
    // Dla połączeń wychodzących klient może jeszcze dzwonić gdy agent klika "Odbierz".
    // Twilio nie pozwala wejść do konferencji zanim klient fizycznie odbierze — klient
    // pozostałby w pętli muzyki oczekiwania (startConferenceOnEnter="false").
    // Dlatego dla OUTBOUND dialAgentIntoConference() jest wywoływana DOPIERO w
    // handleWebhookStatusUpdate() gdy Twilio potwierdzi odebranie przez klienta (in-progress).
    // agentId musi być zapisany w sesji JUŻ TERAZ – sessions.put() powyżej to gwarantuje.
    boolean isOutbound = "OUTBOUND".equals(updated.getDirection());

    // Blind transfer do agenta: sesja ma direction=OUTBOUND (z oryginalnego połączenia wychodzącego),
    // ale poprzedni status to TRANSFERRED – oznacza to, że klient jest już w nowej konferencji
    // (transferToAgentViaConference przekierował go przez TwiML <Conference startConferenceOnEnter="false">).
    // W tym przypadku agent powinien dołączyć natychmiast, analogicznie do INBOUND.
    // Czekanie na webhook in-progress nie ma tu sensu – ten webhook dotyczy odebrania przez klienta,
    // a klient już jest w konferencji (połączenie Twilio jest w stanie in-progress od dawna).
    boolean isTransferredSession = session.getStatus() == CallSession.CallStatus.TRANSFERRED;

    if (isOutbound && !isTransferredSession) {
      // Sprawdź czy klient już odebrał (status ACTIVE w poprzedniej sesji oznacza że
      // webhook in-progress dotarł PRZED answerCall — rzadki przypadek gdy klient szybko odbiera).
      // Sprawdzamy session (stan PRZED aktualizacją) bo klient-webhook aktualizuje status na ACTIVE.
      boolean clientAlreadyAnswered = session.getStatus() == CallSession.CallStatus.ACTIVE
          || (session.getAnsweredAt() != null);
      if (clientAlreadyAnswered && updated.getContactId() != null && updated.getAgentId() != null) {
        log.info("[TwilioAdapter] OUTBOUND answerCall: klient już odebrał wcześniej (status={}), " +
                 "wchodzę do konferencji natychmiast. callId={}",
            session.getStatus(), callId);
        dialAgentIntoConference(updated);
      } else {
        log.info("[TwilioAdapter] OUTBOUND answerCall: agent {} gotowy i czeka na odebranie przez klienta. " +
                 "dialAgentIntoConference() nastąpi gdy webhook in-progress dotrze. callId={}",
            updated.getAgentId(), callId);
      }
    } else {
      // INBOUND lub OUTBOUND po transferze: klient już czeka w konferencji — wejdź natychmiast.
      // Przypadek transferu: session.status=TRANSFERRED oznacza że transferToAgentViaConference()
      // przekierował klienta do nowej konferencji contact-{newContactId} i czeka na agenta.
      if (updated.getContactId() != null && updated.getAgentId() != null) {
        if ("CONSULTATION".equals(updated.getDirection())) {
          // Agent2 joins conference automatically via second-leg TwiML — no additional dial needed
          log.info("[TwilioAdapter] Consultation leg answered by Agent2 – joins conference via TwiML: callId={}",
                   callId);
        } else {
          if (isTransferredSession) {
            log.info("[TwilioAdapter] OUTBOUND-TRANSFER answerCall: sesja po blind transferze, " +
                     "klient czeka w konferencji – wchodzę natychmiast. callId={}, contactId={}, agentId={}",
                callId, updated.getContactId(), updated.getAgentId());
          }
          dialAgentIntoConference(updated);
        }
      } else {
        log.warn("[TwilioAdapter] INBOUND/TRANSFER: brak contactId lub agentId w sesji – pomijam dial agenta do konferencji: " +
                 "callId={}, contactId={}, agentId={}",
            callId, updated.getContactId(), updated.getAgentId());
      }
    }

    eventPublisher.publishAnswered(callId, updated.getTenantId(),
        updated.getAgentId(), updated.getFrom(), updated.getTo());
  }

  /**
   * Inicjuje połączenie Twilio do agenta (Twilio Client SDK) i wchodzi go do konferencji.
   *
   * <p>Agent dołącza jako moderator ({@code startConferenceOnEnter="true"}), co powoduje
   * start konferencji i zestawienie audio z klientem czekającym z parametrem
   * {@code startConferenceOnEnter="false"}.
   *
   * @param session aktywna sesja połączenia z ustawionym contactId i agentId
   */
  private void dialAgentIntoConference(CallSession session) {
    String conferenceName = "contact-" + session.getContactId().toString();
    String agentClientId = "agent-" + session.getAgentId().toString();

    // Buduj atrybuty <Conference> dynamicznie – nagrywanie jest opcjonalne.
    // record="record-from-start" powoduje że Twilio nagrywa całą konferencję
    // i po jej zakończeniu wysyła POST na recordingStatusCallback z ConferenceSid (CF...)
    // i RecordingUrl. Bez tego atrybutu handleRecordingCallback nigdy nie dostanie
    // CF... powiązanego z kontaktem i nie będzie mógł zapisać recording_url do DB.
    StringBuilder conferenceAttrs = new StringBuilder();
    conferenceAttrs.append("startConferenceOnEnter=\"true\" endConferenceOnExit=\"true\"");

    if (twilioProperties.isRecordingEnabled()) {
      conferenceAttrs.append(" record=\"record-from-start\"");
      String callbackBase = buildRawWebhookBaseUrl(session.getTenantId());
      if (StringUtils.hasText(callbackBase)) {
        // URL musi zawierać tenantId – controller parsuje go z query param
        String recordingCallbackUrl = callbackBase + "/recording?tenantId="
            + session.getTenantId().toString();
        conferenceAttrs.append(" recordingStatusCallback=\"")
            .append(recordingCallbackUrl)
            .append("\"");
        conferenceAttrs.append(" recordingStatusCallbackMethod=\"POST\"");
        log.debug("[TwilioAdapter] Nagrywanie konferencji włączone: conference={}, recordingCallbackUrl={}",
            conferenceName, recordingCallbackUrl);
      } else {
        log.warn("[TwilioAdapter] recording-enabled=true, ale twilio.status-callback-url nie jest " +
                 "skonfigurowany – nagranie nie będzie mogło być zapisane do DB. " +
                 "Ustaw twilio.status-callback-url w konfiguracji.");
      }
    }

    String agentTwiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Response><Dial>"
        + "<Conference " + conferenceAttrs + ">"
        + conferenceName
        + "</Conference>"
        + "</Dial></Response>";

    log.info("[TwilioAdapter] Dzwonię do agenta przez Twilio Client: agentClientId={}, conference={}",
        agentClientId, conferenceName);

    try {
      String agentCallSid = Call.creator(
          new PhoneNumber("client:" + agentClientId),
          new PhoneNumber(resolvePhoneNumber(session.getTenantId())),
          new Twiml(agentTwiml)
      ).create(resolveRestClient(session.getTenantId())).getSid();

      log.info("[TwilioAdapter] Połączenie do agenta zainicjowane: agentClientId={}, conference={}, agentCallSid={}",
          agentClientId, conferenceName, agentCallSid);

      // Zapisz SID nogi agenta i nazwę konferencji w sesji Redis.
      // conferenceName musi być utrwalone, żeby executeAttendedTransfer() i bridgeCalls()
      // znalazły właściwą konferencję nawet po updateSessionContact() (która zmienia contactId).
      saveSession(session.withAgentCallSid(agentCallSid).withConferenceName(conferenceName));

      // Przejście ASSIGNED → ACTIVE: faktyczne zestawienie audio potwierdzono przez Twilio API.
      // ContactAssignmentMonitor przestaje monitorować ten kontakt (sprawdza tylko status=ASSIGNED).
      if (session.getContactId() != null) {
        try {
          contactRepository.updateContactStatusOnTelephonyEvent(
              session.getContactId(), session.getTenantId(), "ACTIVE", null);
          log.debug("[TwilioAdapter] Status kontaktu ASSIGNED→ACTIVE po dialAgentIntoConference: " +
                    "contactId={}", session.getContactId());
        } catch (Exception ex) {
          log.warn("[TwilioAdapter] Nie udało się zaktualizować statusu kontaktu ASSIGNED→ACTIVE: " +
                   "contactId={}, error={}", session.getContactId(), ex.getMessage());
        }
      }
    } catch (ApiException e) {
      log.error("[TwilioAdapter] Błąd Twilio API przy zestawianiu połączenia do agenta: " +
                "agentClientId={}, conference={}, code={}, message={}",
          agentClientId, conferenceName, e.getCode(), e.getMessage(), e);
      // Nie rzucamy wyjątku – lokalny stan sesji pozostaje ACTIVE,
      // a błąd jest widoczny w logach dla diagnostyki
    } catch (Exception e) {
      log.error("[TwilioAdapter] Nieoczekiwany błąd przy dial agenta do konferencji: " +
                "agentClientId={}, conference={}, error={}",
          agentClientId, conferenceName, e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Rozłącza połączenie przez Twilio REST API (ustawia status na {@code completed}).
   * Idempotentne – wywołanie na już zakończonym połączeniu nie rzuca wyjątku.
   *
   * @throws TelephonyException gdy sesja nie istnieje lub Twilio API zwróci błąd
   */
  @Override
  public void hangupCall(String callId) {
    CallSession session = requireSession(callId);

    if (session.getStatus() == CallSession.CallStatus.ENDED) {
      log.debug("[TwilioAdapter] Połączenie {} już zakończone, ignoruję hangupCall", callId);
      return;
    }

    String twilioCallSid = session.getCallId();
    log.info("[TwilioAdapter] Rozłączam połączenie: callId={}, twilioCallSid={}, tenant={}", callId, twilioCallSid, session.getTenantId());

    try {
      Call.updater(twilioCallSid)
          .setStatus(Call.UpdateStatus.COMPLETED)
          .update(resolveRestClient(session.getTenantId()));

    }
    catch (ApiException e) {
      // Status 20404 = call already completed – traktuj idempotentnie
      if (e.getCode() == 20404 || e.getStatusCode() == 404) {
        log.debug("[TwilioAdapter] Połączenie {} już zakończone po stronie Twilio ({})",
            twilioCallSid, e.getCode());
      }
      else {
        log.error("[TwilioAdapter] Błąd Twilio API przy hangupCall: callId={}, twilioCallSid={}, code={}, message={}",
            callId, twilioCallSid, e.getCode(), e.getMessage(), e);
        throw new TelephonyException(callId,
            "Nie można rozłączyć połączenia przez Twilio: " + e.getMessage(), e);
      }
    }

    // Rozłącz nogę agenta (CA_agent) – noga klienta (CA_klient) jest już zakończona powyżej.
    // CA_agent ma endConferenceOnExit="true", więc bez jej jawnego zakończenia konferencja
    // pozostałaby aktywna po stronie Twilio i callbacki /conference + /recording nigdy by nie dotarły.
    String agentCallSid = session.getAgentCallSid();
    if (agentCallSid != null) {
      try {
        Call.updater(agentCallSid)
            .setStatus(Call.UpdateStatus.COMPLETED)
            .update(resolveRestClient(session.getTenantId()));
        log.info("[TwilioAdapter] Noga agenta rozłączona: agentCallSid={}, contactId={}",
            agentCallSid, session.getContactId());
      } catch (ApiException e) {
        if (e.getCode() == 20404 || e.getStatusCode() == 404) {
          log.debug("[TwilioAdapter] Noga agenta {} już zakończona ({})", agentCallSid, e.getCode());
        } else {
          log.warn("[TwilioAdapter] Błąd przy rozłączeniu nogi agenta: agentCallSid={}, code={}, msg={}",
              agentCallSid, e.getCode(), e.getMessage());
        }
      }
    } else {
      log.warn("[TwilioAdapter] Brak agentCallSid w sesji – noga agenta nie zostanie rozłączona przez REST API. callId={}", callId);
    }

    Instant endedAt = Instant.now();
    CallSession updated = session
        .withStatus(CallSession.CallStatus.ENDED)
        .withEndedAt(endedAt);
    saveSession(updated);

    if (updated.getContactId() != null && !"CONSULTATION".equals(updated.getDirection())) {
      String contactDbStatus = resolveContactEndStatus(updated);
      contactRepository.updateContactStatusOnTelephonyEvent(
          updated.getContactId(), updated.getTenantId(), contactDbStatus, endedAt);
      contactEventService.closeAgent(updated.getContactId(), updated.getTenantId());
      contactEventService.closeHold(updated.getContactId(), updated.getTenantId());
    }

    if ("CONSULTATION".equals(updated.getDirection()) && updated.getContactId() != null) {
      // Zamknij etap CONSULTING i ponownie otwórz AGENT dla Agent1 (wraca do rozmowy z klientem).
      contactEventService.closeConsulting(updated.getContactId(), updated.getTenantId());
      contactRepository.findById(updated.getContactId(), updated.getTenantId())
          .ifPresent(contact -> {
            if (contact.getAgentId() != null) {
              contactEventService.openAgent(
                  updated.getContactId(), updated.getTenantId(), contact.getAgentId(), null);
            }
          });
    }

    if ("CONSULTATION".equals(updated.getDirection()) && updated.getAgentId() != null) {
      // Konsultacja anulowana przed bridge – Agent2 wraca do AVAILABLE bez ekranu ACW.
      // Nie publikujemy CALL_HANGUP żeby frontend Agent2 nie przechodził do ACW.
      try {
        userService.setAgentAvailableAfterConsultCancelled(updated.getAgentId(), updated.getTenantId());
      } catch (Exception e) {
        log.warn("[TwilioAdapter] Nie udało się ustawić AVAILABLE dla Agent2 po anulowaniu konsultacji: " +
                 "agentId={}, error={}", updated.getAgentId(), e.getMessage());
      }
      eventPublisher.publishConsultCancelled(callId, updated.getTenantId(), updated.getAgentId(),
          updated.getContactId(), updated.getFrom(), updated.getTo());
    } else {
      eventPublisher.publishHangup(callId, updated.getContactId(),
          updated.getTenantId(), updated.getAgentId(),
          updated.getFrom(), updated.getTo(), "completed");
    }

    // Naprawa 3: Fallback pobierania nagrania po 90 sekundach.
    // Twilio wysyła recordingStatusCallback asynchronicznie (~2 min po zakończeniu).
    // Jeśli callback nie dotrze (restart, 502, timeout), nagranie nigdy nie trafia do DB.
    // Rozwiązanie: po hangup planujemy sprawdzenie nagrania przez Twilio REST API po 90s.
    if (twilioProperties.isRecordingEnabled() && updated.getContactId() != null) {
      scheduleRecordingFallback(callId, updated.getContactId(), updated.getTenantId());
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Hold realizowany przez Twilio Conference Participant API ({@code hold=true/false}).
   * Algorytm identyczny jak {@link #muteCall} – wyszukujemy aktywną konferencję po friendly name
   * ({@code contact-{contactId}}), następnie aktualizujemy uczestnika (noga klienta) przez
   * {@link Participant#updater(String, String)} z parametrem {@code hold=true/false}.
   *
   * <p>UWAGA: hold jest ustawiany na nodze <strong>klienta</strong> (callId z sesji, nie agentCallSid).
   * Klient słyszy muzykę hold (holdUrl Twilio lub domyślna muzyka Twilio), agent pozostaje
   * w konferencji ale nie słyszy klienta. Pozwala to agentowi np. konsultować się z supervisorem.
   *
   * <p>Poprzednia implementacja używała {@code Call.updater(callId).setStatus(CANCELED/COMPLETED)},
   * co fizycznie kończyło nogę połączenia w konferencji i wywoływało {@code conference-end} callback
   * → kontakt był błędnie klasyfikowany jako ABANDONED.
   *
   * @throws TelephonyException gdy sesja jest w złym stanie, brak wymaganych danych sesji,
   *                            konferencja nie istnieje lub Twilio API zwróci błąd
   */
  @Override
  public void holdCall(String callId, boolean hold) {
    CallSession session = requireSession(callId);

    CallSession.CallStatus expectedStatus = hold
        ? CallSession.CallStatus.ACTIVE
        : CallSession.CallStatus.ON_HOLD;

    if (session.getStatus() != expectedStatus) {
      throw new TelephonyException(callId,
          String.format("Nie można %s połączenia %s w stanie %s",
              hold ? "wstrzymać" : "wznowić", callId, session.getStatus()));
    }

    if (session.getContactId() == null) {
      throw new TelephonyException(callId,
          "Nie można wykonać hold – brak contactId w sesji (nie można odtworzyć nazwy konferencji). callId=" + callId);
    }

    log.info("[TwilioAdapter] Hold: callId={}, hold={}", callId, hold);

    String conferenceName = session.getConferenceName() != null
        ? session.getConferenceName()
        : "contact-" + session.getContactId().toString();
    TwilioRestClient client = resolveRestClient(session.getTenantId());

    try {
      // Krok 1: znajdź aktywną konferencję po friendly name (nazewnictwo: "contact-{contactId}")
      ResourceSet<Conference> conferences = Conference.reader()
          .setFriendlyName(conferenceName)
          .setStatus(Conference.Status.IN_PROGRESS)
          .read(client);

      Conference conference = null;
      for (Conference c : conferences) {
        conference = c;
        break;
      }
      if (conference == null) {
        throw new TelephonyException(callId,
            "Nie znaleziono aktywnej konferencji Twilio dla contactId=" + session.getContactId()
            + " (conferenceName=" + conferenceName + ")");
      }

      String conferenceSid = conference.getSid();
      log.debug("[TwilioAdapter] Znaleziono konferencję do hold: conferenceName={}, conferenceSid={}",
          conferenceName, conferenceSid);

      // Krok 2: ustaw hold na nodze klienta (callId) przez Participant API.
      // Noga klienta identyfikowana jest przez callId przekazane do holdCall (CA... klienta).
      // Klient słyszy muzykę hold; agent pozostaje aktywny w konferencji.
      Participant.updater(conferenceSid, callId)
          .setHold(hold)
          .update(client);

      log.info("[TwilioAdapter] Hold uczestnika przez Twilio Participant API: " +
               "conferenceSid={}, clientCallSid={}, hold={}",
          conferenceSid, callId, hold);

    } catch (TelephonyException te) {
      throw te;
    } catch (ApiException e) {
      log.error("[TwilioAdapter] Błąd Twilio API przy holdCall: callId={}, hold={}, code={}, message={}",
          callId, hold, e.getCode(), e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wykonać hold przez Twilio: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("[TwilioAdapter] Nieoczekiwany błąd przy holdCall: callId={}, hold={}, error={}",
          callId, hold, e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wykonać hold: " + e.getMessage(), e);
    }

    CallSession.CallStatus newStatus = hold
        ? CallSession.CallStatus.ON_HOLD
        : CallSession.CallStatus.ACTIVE;
    saveSession(session.withStatus(newStatus));

    UUID contactId = session.getContactId();
    UUID tenantId = session.getTenantId();
    if (contactId != null) {
      if (hold) {
        contactEventService.closeAgent(contactId, tenantId);
        contactEventService.openHold(contactId, tenantId);
      } else {
        contactEventService.closeHold(contactId, tenantId);
        contactEventService.openAgent(contactId, tenantId, session.getAgentId(), "");
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Wyciszenie/odciszenie uczestnika przez Twilio Conference Participant API.
   * Połączenia oparte są na konferencji Twilio (nazwa: {@code contact-{contactId}}),
   * dlatego mute realizowany jest przez {@link Participant#updater(String, String)}
   * z parametrem {@code muted=true/false}.
   *
   * <p>Algorytm:
   * <ol>
   *   <li>Wyszukaj aktywną konferencję Twilio po friendly name ({@code conferenceName}).
   *   <li>Zaktualizuj uczestnika ({@code agentCallSid}) przez Participant API.
   * </ol>
   *
   * @throws TelephonyException gdy sesja nie jest aktywna, brak agentCallSid/contactId,
   *                            konferencja nie istnieje lub Twilio API zwróci błąd
   */
  @Override
  public void muteCall(String callId, boolean mute) {
    CallSession session = requireSession(callId);

    if (session.getStatus() != CallSession.CallStatus.ACTIVE
        && session.getStatus() != CallSession.CallStatus.ON_HOLD) {
      throw new TelephonyException(callId,
          "Nie można wyciszyć nieaktywnego połączenia: " + callId);
    }

    log.info("[TwilioAdapter] Mute: callId={}, mute={}", callId, mute);

    String agentCallSid = session.getAgentCallSid();
    if (agentCallSid == null) {
      throw new TelephonyException(callId,
          "Nie można wyciszyć połączenia – brak agentCallSid w sesji (agent nie dołączył jeszcze do konferencji). callId=" + callId);
    }

    if (session.getContactId() == null) {
      throw new TelephonyException(callId,
          "Nie można wyciszyć połączenia – brak contactId w sesji (nie można odtworzyć nazwy konferencji). callId=" + callId);
    }

    String conferenceName = session.getConferenceName() != null
        ? session.getConferenceName()
        : "contact-" + session.getContactId().toString();
    TwilioRestClient client = resolveRestClient(session.getTenantId());

    try {
      // Krok 1: znajdź aktywną konferencję po friendly name (nazewnictwo: "contact-{contactId}")
      ResourceSet<Conference> conferences = Conference.reader()
          .setFriendlyName(conferenceName)
          .setStatus(Conference.Status.IN_PROGRESS)
          .read(client);

      Conference conference = null;
      for (Conference c : conferences) {
        conference = c;
        break;
      }
      if (conference == null) {
        throw new TelephonyException(callId,
            "Nie znaleziono aktywnej konferencji Twilio dla contactId=" + session.getContactId()
            + " (conferenceName=" + conferenceName + ")");
      }

      String conferenceSid = conference.getSid();
      log.debug("[TwilioAdapter] Znaleziono konferencję: conferenceName={}, conferenceSid={}",
          conferenceName, conferenceSid);

      // Krok 2: wycisz/odcisz uczestnika (noga agenta) przez Participant API
      Participant.updater(conferenceSid, agentCallSid)
          .setMuted(mute)
          .update(client);

      log.info("[TwilioAdapter] Uczestnik wyciszony przez Twilio Participant API: " +
               "conferenceSid={}, agentCallSid={}, mute={}",
          conferenceSid, agentCallSid, mute);

    } catch (TelephonyException te) {
      throw te;
    } catch (ApiException e) {
      log.error("[TwilioAdapter] Błąd Twilio API przy muteCall: callId={}, mute={}, code={}, message={}",
          callId, mute, e.getCode(), e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wyciszyć połączenia przez Twilio: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("[TwilioAdapter] Nieoczekiwany błąd przy muteCall: callId={}, mute={}, error={}",
          callId, mute, e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wyciszyć połączenia: " + e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Transfer blind realizowany przez aktualizację TwiML z {@code <Dial>} do {@code target}.
   * Transfer attended tworzy nowe połączenie wychodzące do {@code target} (druga noga),
   * a oryginalne połączenie jest wstrzymane – bridge realizowany przez {@link #bridgeCalls}.
   *
   * @throws TelephonyException gdy sesja nie jest aktywna, brak numeru telefonu Twilio lub błąd API
   */
  @Override
  public CallSession transferCall(String callId, String target, TransferType transferType) {
    CallSession session = requireSession(callId);

    if (session.getStatus() != CallSession.CallStatus.ACTIVE
        && session.getStatus() != CallSession.CallStatus.ON_HOLD) {
      throw new TelephonyException(callId,
          "Przekazanie możliwe tylko dla połączenia ACTIVE lub ON_HOLD. Aktualny status: "
              + session.getStatus());
    }

    log.info("[TwilioAdapter] Transfer: callId={}, target={}, type={}", callId, target, transferType);

    if (transferType == TransferType.BLIND) {
      return executeBlindTransfer(callId, target, session);
    }
    else {
      return executeAttendedTransfer(callId, target, session);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Dla {@link TransferTargetType#PHONE} deleguje do {@link #transferCall}.
   *
   * <p>Dla {@link TransferTargetType#AGENT}:
   * <ul>
   *   <li>Pobiera agenta po {@code request.agentId()} z {@link AppUserRepository}.</li>
   *   <li>Ustala cel Twilio: {@code client:agent-{agentId}} (Twilio Client SDK),
   *       taki sam format jak używany w {@link #dialAgentIntoConference}.</li>
   *   <li>Deleguje do {@link #transferCall} – obsługuje BLIND i ATTENDED.</li>
   * </ul>
   *
   * <p>Dla {@link TransferTargetType#QUEUE} (tylko BLIND – walidacja w {@link TransferRequest#validate()}):
   * <ul>
   *   <li>Pobiera kolejkę po {@code request.queueId()} z {@link QueueRepository}.</li>
   *   <li>Redirectuje bieżące połączenie klienta przez TwiML {@code <Conference>}
   *       z nową nazwą konferencji ({@code contact-{newContactId}}), analogicznie do
   *       {@code IvrEngineService.buildWaitInConferenceTwiml}.</li>
   *   <li>Publikuje event transferu.</li>
   * </ul>
   *
   * @throws TelephonyException gdy agent/kolejka nie istnieje, sesja jest w złym stanie
   *                            lub Twilio API zwróci błąd
   */
  @Override
  public CallSession initiateTransfer(String callId, TransferRequest request) {
    request.validate();
    return switch (request.targetType()) {
      case PHONE -> transferCall(callId, request.phoneNumber(), request.transferType());
      case AGENT -> transferToAgent(callId, request);
      case QUEUE -> transferToQueue(callId, request);
    };
  }

  /**
   * Transfer do agenta.
   *
   * <p>Dla {@link TransferType#BLIND} używa konferencji (analogicznie do transferu do kolejki):
   * klient trafia do nowej konferencji Twilio {@code contact-{newContactId}}, a backend
   * publikuje {@link DirectAgentAssignmentMessage} do RabbitMQ, by RoutingService
   * przypisał agenta – co wywoła {@code dialAgentIntoConference()} i powiadomi softphone.
   *
   * <p>Dla {@link TransferType#ATTENDED} używa dotychczasowej ścieżki przez Twilio Client SDK.
   */
  private CallSession transferToAgent(String callId, TransferRequest request) {
    UUID tenantId = TenantContext.getTenantId();
    UUID agentId = request.agentId();

    // Weryfikacja agenta – musi należeć do tego samego tenanta
    AppUser agent = appUserRepository
        .findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)
        .orElseThrow(() -> new TelephonyException(callId,
            "Agent nie istnieje lub należy do innego tenanta: agentId=" + agentId));

    log.info("[TwilioAdapter] Transfer do agenta: callId={}, agentId={}, agent={} {}, type={}",
        callId, agentId, agent.getFirstName(), agent.getLastName(), request.transferType());

    if (request.transferType() == TransferType.BLIND) {
      return transferToAgentViaConference(callId, agentId, agent, tenantId);
    }

    // ATTENDED – istniejące zachowanie przez Twilio Client SDK
    String target = "client:agent-" + agentId.toString();
    return transferCall(callId, target, request.transferType());
  }

  /**
   * Blind transfer do konkretnego agenta przez Twilio Conference.
   *
   * <p>Klient trafia do nowej konferencji {@code contact-{newContactId}} (analogicznie do
   * transferu do kolejki). Backend publikuje {@link DirectAgentAssignmentMessage}, którą
   * RoutingService konsumuje i wywołuje {@code ContactService.assignAgent()} – to uruchamia
   * {@code dialAgentIntoConference()} i wysyła {@code CONTACT_ASSIGNED} WebSocket event
   * do softphone'u docelowego agenta.
   */
  private CallSession transferToAgentViaConference(String callId, UUID targetAgentId,
                                                    AppUser agent, UUID tenantId) {
    CallSession session = requireSession(callId);

    if (session.getStatus() != CallSession.CallStatus.ACTIVE
        && session.getStatus() != CallSession.CallStatus.ON_HOLD) {
      throw new TelephonyException(callId,
          "Transfer do agenta możliwy tylko dla połączenia ACTIVE lub ON_HOLD. Status: "
              + session.getStatus());
    }

    UUID newContactId = UUID.randomUUID();
    String newConferenceName = "contact-" + newContactId;

    log.info("[TwilioAdapter] Transfer do agenta via conference: callId={}, targetAgentId={}, newContactId={}",
        callId, targetAgentId, newContactId);

    // Buduj TwiML analogicznie do transferToQueue() – bez queueId w waitUrl
    String rawBase = buildRawWebhookBaseUrl(tenantId);
    String conferenceStatusCallbackUrl = rawBase != null
        ? rawBase + "/conference?tenantId=" + tenantId
        : null;
    String recordingCallbackUrl = rawBase != null
        ? rawBase + "/recording?tenantId=" + tenantId
        : null;
    String waitUrl = appBaseUrl + "/api/telephony/hold-music";

    StringBuilder conferenceAttrs = new StringBuilder();
    conferenceAttrs.append("startConferenceOnEnter=\"false\"");
    conferenceAttrs.append(" endConferenceOnExit=\"true\"");
    if (twilioProperties.isRecordingEnabled() && recordingCallbackUrl != null) {
      conferenceAttrs.append(" record=\"record-from-start\"");
      conferenceAttrs.append(" recordingStatusCallback=\"").append(recordingCallbackUrl).append("\"");
      conferenceAttrs.append(" recordingStatusCallbackMethod=\"POST\"");
    }
    if (conferenceStatusCallbackUrl != null) {
      conferenceAttrs.append(" statusCallback=\"").append(conferenceStatusCallbackUrl).append("\"");
      conferenceAttrs.append(" statusCallbackEvent=\"end\"");
      conferenceAttrs.append(" statusCallbackMethod=\"POST\"");
    }
    conferenceAttrs.append(" waitUrl=\"").append(waitUrl).append("\"");
    conferenceAttrs.append(" waitMethod=\"GET\"");

    String conferenceTwiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Response>"
        + "<Say language=\"pl-PL\">Przekazuję do agenta, proszę czekać.</Say>"
        + "<Dial>"
        + "<Conference " + conferenceAttrs + ">"
        + newConferenceName
        + "</Conference>"
        + "</Dial>"
        + "</Response>";

    // Przekieruj klienta do nowej konferencji
    String twilioCallSid = session.getCallId();
    try {
      Call.updater(twilioCallSid)
          .setTwiml(new Twiml(conferenceTwiml))
          .update(resolveRestClient(tenantId));
    } catch (ApiException e) {
      log.error("[TwilioAdapter] Błąd Twilio API przy transfer do agenta via conference: " +
          "callId={}, twilioCallSid={}, targetAgentId={}, code={}, msg={}",
          callId, twilioCallSid, targetAgentId, e.getCode(), e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wykonać transferu do agenta: " + e.getMessage(), e);
    }

    Instant now = Instant.now();

    // Oznacz oryginalny kontakt jako TRANSFERRED
    UUID originalContactId = session.getContactId();
    if (originalContactId != null) {
      try {
        contactRepository.updateContactStatusOnTelephonyEvent(originalContactId, tenantId, "TRANSFERRED", now);
        log.info("[TwilioAdapter] Oryginalny kontakt oznaczony jako TRANSFERRED (agent via conf): " +
            "contactId={}", originalContactId);
      } catch (Exception e) {
        log.error("[TwilioAdapter] Błąd aktualizacji statusu TRANSFERRED: contactId={}, error={}",
            originalContactId, e.getMessage(), e);
      }
    }

    // Pobierz queueId/queueName z oryginalnego kontaktu — nowy kontakt powinien dziedziczyć kolejkę
    UUID originalQueueId = null;
    String originalQueueName = null;
    if (originalContactId != null) {
      try {
        var origOpt = contactRepository.findById(originalContactId, tenantId);
        if (origOpt.isPresent()) {
          UUID qId = origOpt.get().getQueueId();
          if (qId != null) {
            originalQueueId = qId;
            originalQueueName = queueRepository.findByIdAndTenantId(qId, tenantId)
                .map(q -> q.getName()).orElse(null);
          }
        }
      } catch (Exception e) {
        log.warn("[TwilioAdapter] Nie udało się pobrać kolejki oryginalnego kontaktu: {}", e.getMessage());
      }
    }

    // Zaktualizuj sesję – nowy contactId dla nowej konferencji
    CallSession transferred = session
        .withStatus(CallSession.CallStatus.TRANSFERRED)
        .withContactId(newContactId)
        .withAgentId(null)
        .withAgentCallSid(null)
        .withAnsweredAt(null)
        .withEndedAt(null);
    saveSession(transferred);

    // Utwórz nowy rekord Contact w DB
    final UUID finalQueueId = originalQueueId;
    final String finalQueueName = originalQueueName;
    try {
      // Numer klienta: dla OUTBOUND klient jest w session.getTo() (firma dzwoniła do klienta),
      // dla INBOUND klient jest w session.getFrom() (klient dzwonił do firmy).
      String customerAddress = "OUTBOUND".equals(session.getDirection())
          ? session.getTo()
          : session.getFrom();
      // Zachowaj direction z oryginalnej sesji (OUTBOUND pozostaje OUTBOUND po transferze).
      String contactDirection = session.getDirection() != null ? session.getDirection() : "INBOUND";
      Contact newContact = Contact.builder()
          .contactId(newContactId)
          .tenantId(tenantId)
          .channel("PHONE")
          .direction(contactDirection)
          .status("QUEUED")
          .queueId(finalQueueId)
          .remoteAddress(customerAddress)
          .transferredFromContactId(originalContactId)
          .queuedAt(now)
          .startedAt(now)
          .createdAt(now)
          .updatedAt(now)
          .channelMetadata(new HashMap<>(java.util.Map.<String, Object>of("sip_call_id", twilioCallSid)))
          .build();
      contactRepository.insert(newContact);
      // Nie otwieramy etapu QUEUE dla direct transfer do agenta — klient trafia bezpośrednio
      // do agenta, nie przechodzi przez kolejkę. RoutingService.onDirectAgentAssignment()
      // wywołuje closeQueue() tylko gdy etap QUEUE jest otwarty; bez openQueue() nie powstaje
      // wpis QUEUE 0s w timeline.
      log.info("[TwilioAdapter] Nowy kontakt po transferze do agenta utworzony: " +
          "newContactId={}, targetAgentId={}, queueId={}, direction={}", newContactId, targetAgentId, finalQueueId, contactDirection);
    } catch (Exception e) {
      log.error("[TwilioAdapter] Błąd tworzenia nowego kontaktu po transferze do agenta: " +
          "newContactId={}, error={}", newContactId, e.getMessage(), e);
    }

    // Opublikuj wiadomość dla RoutingService aby przypisał konkretnego agenta
    try {
      String agentFullName = agent.getFirstName() + " " + agent.getLastName();
      DirectAgentAssignmentMessage msg = new DirectAgentAssignmentMessage(
          newContactId, targetAgentId, tenantId, agentFullName, finalQueueId, finalQueueName);
      rabbitTemplate.convertAndSend(
          RabbitMQConfig.EXCHANGE_EVENTS, RabbitMQConfig.RK_AGENT_DIRECT_ASSIGNMENT, msg);
      log.info("[TwilioAdapter] Opublikowano DirectAgentAssignment: newContactId={}, targetAgentId={}",
          newContactId, targetAgentId);
    } catch (Exception e) {
      log.error("[TwilioAdapter] Błąd publikacji DirectAgentAssignment: error={}", e.getMessage(), e);
    }

    eventPublisher.publishTransferred(
        callId, tenantId, session.getAgentId(),
        session.getFrom(), session.getTo(),
        "agent:" + targetAgentId, TransferType.BLIND.name()
    );

    return transferred;
  }

  /**
   * Blind transfer do kolejki przez redirect TwiML z {@code <Conference>}.
   *
   * <p>Klient zostaje przekierowany do nowej konferencji Twilio o nazwie
   * {@code contact-{newContactId}}, która obsługuje oczekiwanie na agenta z danej kolejki.
   * Format TwiML identyczny jak {@code IvrEngineService.buildWaitInConferenceTwiml}.
   *
   * <p>Nowy {@code contactId} jest generowany deterministycznie jako losowy UUID,
   * ponieważ oryginalny kontakt przechodzi w stan TRANSFERRED i nie może być
   * ponownie użyty dla nowego oczekiwania w kolejce.
   */
  private CallSession transferToQueue(String callId, TransferRequest request) {
    CallSession session = requireSession(callId);

    if (session.getStatus() != CallSession.CallStatus.ACTIVE
        && session.getStatus() != CallSession.CallStatus.ON_HOLD) {
      throw new TelephonyException(callId,
          "Transfer do kolejki możliwy tylko dla połączenia ACTIVE lub ON_HOLD. Status: "
              + session.getStatus());
    }

    UUID tenantId = session.getTenantId();
    UUID queueId = request.queueId();

    // Weryfikacja kolejki – musi należeć do tego samego tenanta
    Queue queue = queueRepository
        .findByIdAndTenantId(queueId, tenantId)
        .orElseThrow(() -> new TelephonyException(callId,
            "Kolejka nie istnieje lub należy do innego tenanta: queueId=" + queueId));

    if (!queue.isActive()) {
      throw new TelephonyException(callId,
          "Nie można transferować do nieaktywnej kolejki: queueId=" + queueId
              + ", queue=" + queue.getName());
    }

    // Nowy contactId dla oczekiwania w kolejce po transferze
    UUID newContactId = UUID.randomUUID();
    String newConferenceName = "contact-" + newContactId;

    log.info("[TwilioAdapter] Transfer do kolejki: callId={}, queueId={}, queueName={}, newContactId={}",
        callId, queueId, queue.getName(), newContactId);

    // Buduj TwiML analogicznie do IvrEngineService.buildWaitInConferenceTwiml
    String rawBase = buildRawWebhookBaseUrl(tenantId);
    String conferenceStatusCallbackUrl = rawBase != null
        ? rawBase + "/conference?tenantId=" + tenantId
        : null;
    String recordingCallbackUrl = rawBase != null
        ? rawBase + "/recording?tenantId=" + tenantId
        : null;
    String waitUrl = appBaseUrl + "/api/telephony/hold-music?queueId=" + queueId;

    StringBuilder conferenceAttrs = new StringBuilder();
    conferenceAttrs.append("startConferenceOnEnter=\"false\"");
    conferenceAttrs.append(" endConferenceOnExit=\"true\"");
    if (twilioProperties.isRecordingEnabled() && recordingCallbackUrl != null) {
      conferenceAttrs.append(" record=\"record-from-start\"");
      conferenceAttrs.append(" recordingStatusCallback=\"").append(recordingCallbackUrl).append("\"");
      conferenceAttrs.append(" recordingStatusCallbackMethod=\"POST\"");
    }
    if (conferenceStatusCallbackUrl != null) {
      conferenceAttrs.append(" statusCallback=\"").append(conferenceStatusCallbackUrl).append("\"");
      conferenceAttrs.append(" statusCallbackEvent=\"end\"");
      conferenceAttrs.append(" statusCallbackMethod=\"POST\"");
    }
    conferenceAttrs.append(" waitUrl=\"").append(waitUrl).append("\"");
    conferenceAttrs.append(" waitMethod=\"GET\"");

    String queueTwiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Response>"
        + "<Say language=\"pl-PL\">Przekazuję do kolejki, proszę czekać.</Say>"
        + "<Dial>"
        + "<Conference " + conferenceAttrs + ">"
        + newConferenceName
        + "</Conference>"
        + "</Dial>"
        + "</Response>";

    String twilioCallSid = session.getCallId();
    try {
      Call.updater(twilioCallSid)
          .setTwiml(new Twiml(queueTwiml))
          .update(resolveRestClient(tenantId));
    } catch (ApiException e) {
      log.error("[TwilioAdapter] Błąd Twilio API przy transfer do kolejki: callId={}, twilioCallSid={}, queueId={}, code={}, msg={}",
          callId, twilioCallSid, queueId, e.getCode(), e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wykonać transferu do kolejki przez Twilio: " + e.getMessage(), e);
    }

    Instant now = Instant.now();

    // Fix #3: Oznacz oryginalny kontakt jako TRANSFERRED w DB zanim nadejdzie
    // conference-end callback (który w przeciwnym razie ustawiłby go na ABANDONED).
    UUID originalContactId = session.getContactId();
    if (originalContactId != null) {
      try {
        contactRepository.updateContactStatusOnTelephonyEvent(
            originalContactId, tenantId, "TRANSFERRED", now);
        log.info("[TwilioAdapter] Oryginalny kontakt oznaczony jako TRANSFERRED: contactId={}", originalContactId);
      } catch (Exception e) {
        log.error("[TwilioAdapter] Błąd przy aktualizacji statusu oryginalnego kontaktu na TRANSFERRED: " +
                  "contactId={}, error={}", originalContactId, e.getMessage(), e);
        // Nie przerywamy – transfer do Twilio już się powiódł
      }
    }

    // Zaktualizuj sesję: contactId → newContactId, aby answerCall() użył właściwej konferencji
    // (conferenceName = "contact-{contactId}"). Bez tej zmiany agent dołączałby do starej
    // konferencji, która już nie istnieje. endedAt = null, bo fizyczne połączenie Twilio
    // (callSid) nadal trwa — klient jest w nowej konferencji oczekując na agenta.
    CallSession transferred = session
        .withStatus(CallSession.CallStatus.TRANSFERRED)
        .withContactId(newContactId)
        .withAgentId(null)
        .withAgentCallSid(null)
        .withAnsweredAt(null)
        .withEndedAt(null);
    saveSession(transferred);

    // Fix #1a: Utwórz nowy rekord Contact w DB dla nowego contactId.
    // Bez niego RoutingService nie znajdzie kontaktu i żaden agent nie odbierze połączenia.
    try {
      // Numer klienta: dla OUTBOUND klient jest w session.getTo() (firma dzwoniła do klienta),
      // dla INBOUND klient jest w session.getFrom() (klient dzwonił do firmy).
      String customerAddress = "OUTBOUND".equals(session.getDirection())
          ? session.getTo()
          : session.getFrom();
      // Zachowaj direction z oryginalnej sesji (OUTBOUND pozostaje OUTBOUND po transferze kolejkowym).
      String contactDirection = session.getDirection() != null ? session.getDirection() : "INBOUND";
      Contact newContact = Contact.builder()
          .contactId(newContactId)
          .tenantId(tenantId)
          .channel("PHONE")
          .direction(contactDirection)
          .status("QUEUED")
          .queueId(queueId)
          .remoteAddress(customerAddress)
          .queuedAt(now)
          .startedAt(now)
          .createdAt(now)
          .updatedAt(now)
          .channelMetadata(new HashMap<>(java.util.Map.<String, Object>of("sip_call_id", session.getCallId())))
          .transferredFromContactId(originalContactId)
          .build();
      contactRepository.insert(newContact);
      log.info("[TwilioAdapter] Nowy kontakt po transferze kolejkowym utworzony: newContactId={}, queueId={}, direction={}",
          newContactId, queueId, contactDirection);
      contactEventService.openQueue(newContactId, tenantId, queueId, queue.getName(), now);
    } catch (Exception e) {
      log.error("[TwilioAdapter] Błąd przy tworzeniu nowego kontaktu po transferze: " +
                "newContactId={}, queueId={}, error={}", newContactId, queueId, e.getMessage(), e);
      // Nie przerywamy – klient jest już w nowej konferencji; routing ponowi próbę po zmianie statusu agenta
    }

    // Fix #1b: Opublikuj event contact.queued żeby RoutingService natychmiast przydzielił agenta.
    try {
      ContactQueuedMessage routingMessage = new ContactQueuedMessage(newContactId, queueId, tenantId);
      rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, RabbitMQConfig.RK_CONTACT_QUEUED, routingMessage);
      log.info("[TwilioAdapter] Event contact.queued opublikowany po transferze kolejkowym: " +
               "newContactId={}, queueId={}", newContactId, queueId);
    } catch (Exception e) {
      log.error("[TwilioAdapter] Błąd publikacji eventu contact.queued po transferze: " +
                "newContactId={}, queueId={}, error={}", newContactId, queueId, e.getMessage(), e);
      // Nie przerywamy – RoutingService ponowi próbę po zmianie statusu agenta (fallback)
    }

    log.info("[TwilioAdapter] Transfer do kolejki wykonany: callId={}, queueId={}, queueName={}, newContactId={}",
        callId, queueId, queue.getName(), newContactId);

    eventPublisher.publishTransferred(
        callId, tenantId, session.getAgentId(),
        session.getFrom(), session.getTo(),
        "queue:" + queueId, TransferType.BLIND.name()
    );

    return transferred;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Bridge (Wariant A): przekierowuje klienta i Agent2 do nowej, dedykowanej konferencji
   * {@code contact-{newContactId}} przez {@code Call.updater().setTwiml()}, analogicznie do
   * blind transfer. Stara konferencja kończy się gdy klient ją opuści, co wyzwala nagranie R-A.
   * Nowa konferencja generuje nagranie R-B dla nowego kontaktu.
   *
   * <p>Kolejność operacji:
   * <ol>
   *   <li>Wyznacz customerCallSid z sesji1 (fallback: callId gdy INBOUND i null).</li>
   *   <li>Zbuduj TwiML nowej konferencji z record-from-start.</li>
   *   <li>Redirect klienta do nowej konferencji (krytyczny – rzuca TelephonyException przy błędzie).</li>
   *   <li>Redirect Agent2 do nowej konferencji (WARN przy błędzie, kontynuuj).</li>
   *   <li>Zakończ nogę Agent1 (ignoruj 404).</li>
   *   <li>Aktualizuj sesje Redis + utwórz indeks dla newContactId.</li>
   * </ol>
   *
   * @throws TelephonyException gdy któraś z sesji nie istnieje, jest w złym stanie,
   *                            lub redirect klienta nie powiódł się
   */
  @Override
  public void bridgeCalls(String callId1, String callId2, UUID newContactId) {
    CallSession session1 = requireSession(callId1);
    CallSession session2 = requireSession(callId2);

    validateBridgeable(session1);
    validateBridgeable(session2);

    UUID tenantId = session1.getTenantId();
    TwilioRestClient client = resolveRestClient(tenantId);

    log.info("[TwilioAdapter] Bridge (Wariant A): callId1={}, callId2={}, newContactId={}",
        callId1, callId2, newContactId);

    // ---- Krok 1: wyznacz customerCallSid ----
    String customerCallSid = session1.getCustomerCallSid();
    if (customerCallSid == null) {
      // Fallback dla sesji zarejestrowanych przed wdrożeniem pola customerCallSid.
      // INBOUND: callId to SID połączenia klienta (bezpośredni fallback).
      // OUTBOUND: session1.getCallId() to SID wychodzącego połączenia klienta — identyczne znaczenie.
      if ("INBOUND".equals(session1.getDirection()) || session1.getDirection() == null
          || "OUTBOUND".equals(session1.getDirection())) {
        customerCallSid = session1.getCallId();
        log.warn("[TwilioAdapter] Bridge: brak customerCallSid w sesji1 – używam callId jako fallback " +
                 "(sesja sprzed deploymentu lub race condition): callId1={}, fallbackSid={}, direction={}",
                 callId1, customerCallSid, session1.getDirection());
      } else {
        throw new TelephonyException(callId1,
            "Brak customerCallSid w sesji – nie można przekierować klienta do nowej konferencji. " +
            "callId1=" + callId1 + ", direction=" + session1.getDirection());
      }
    }

    // ---- Krok 2: wyznacz nową konferencję ----
    String newConferenceName = "contact-" + newContactId;

    // ---- Krok 3: zbuduj TwiML nowej konferencji ----
    String rawBase = buildRawWebhookBaseUrl(tenantId);
    String waitUrl = appBaseUrl + "/api/telephony/hold-music";

    StringBuilder conferenceAttrs = new StringBuilder();
    conferenceAttrs.append("startConferenceOnEnter=\"true\"");
    conferenceAttrs.append(" endConferenceOnExit=\"true\"");
    if (twilioProperties.isRecordingEnabled() && rawBase != null) {
      String recordingCallbackUrl = rawBase + "/recording?tenantId=" + tenantId;
      conferenceAttrs.append(" record=\"record-from-start\"");
      conferenceAttrs.append(" recordingStatusCallback=\"").append(recordingCallbackUrl).append("\"");
      conferenceAttrs.append(" recordingStatusCallbackMethod=\"POST\"");
    }
    if (rawBase != null) {
      String confStatusCallbackUrl = rawBase + "/conference?tenantId=" + tenantId;
      conferenceAttrs.append(" statusCallback=\"").append(confStatusCallbackUrl).append("\"");
      conferenceAttrs.append(" statusCallbackEvent=\"end\"");
      conferenceAttrs.append(" statusCallbackMethod=\"POST\"");
    }
    conferenceAttrs.append(" waitUrl=\"").append(waitUrl).append("\"");
    conferenceAttrs.append(" waitMethod=\"GET\"");

    String newConferenceTwiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<Response><Dial>"
        + "<Conference " + conferenceAttrs + ">"
        + newConferenceName
        + "</Conference>"
        + "</Dial></Response>";

    // ---- Krok 4: oznacz kontakt TRANSFERRED zanim zaczną się redirect-y ----
    // REQUIRES_NEW: conference-end callback może nadejść w innym wątku podczas trwania
    // zewnętrznej transakcji ContactService.bridgeCalls(). Bez REQUIRES_NEW update TRANSFERRED
    // nie byłby widoczny dla callbacku i nadpisałby status na ABANDONED.
    Instant now = Instant.now();
    if (session1.getContactId() != null) {
      try {
        contactRepository.updateContactStatusRequiresNew(
            session1.getContactId(), tenantId, "TRANSFERRED", now);
        log.info("[TwilioAdapter] Bridge: oryginalny kontakt oznaczony jako TRANSFERRED (przed redirect): contactId={}",
            session1.getContactId());
      } catch (Exception e) {
        log.warn("[TwilioAdapter] Bridge: nie udało się zaktualizować statusu kontaktu na TRANSFERRED: {}",
            e.getMessage());
      }
    }

    // ---- Krok 5: redirect Agent2 (pierwsza kolejność — Agent2 musi dołączyć do nowej konferencji
    //              zanim klient ją opuści; noga konsultacyjna ma endConferenceOnExit=false,
    //              więc Agent2 opuszczając starą konferencję nie kończy jej) ----
    String agent2CallSid = session2.getCallId();
    try {
      Call.updater(agent2CallSid)
          .setTwiml(new Twiml(newConferenceTwiml))
          .update(client);
      log.info("[TwilioAdapter] Bridge: Agent2 przekierowany do nowej konferencji: " +
               "agent2CallSid={}, newConferenceName={}", agent2CallSid, newConferenceName);
    } catch (ApiException e) {
      log.warn("[TwilioAdapter] Bridge: błąd redirect Agent2 (kontynuuję z redirect klienta): " +
               "agent2CallSid={}, code={}, msg={}", agent2CallSid, e.getCode(), e.getMessage());
    }

    // ---- Krok 6: redirect klienta (krytyczny, po Agent2 – klient opuszcza starą konferencję) ----
    try {
      Call.updater(customerCallSid)
          .setTwiml(new Twiml(newConferenceTwiml))
          .update(client);
      log.info("[TwilioAdapter] Bridge: klient przekierowany do nowej konferencji: " +
               "customerCallSid={}, newConferenceName={}", customerCallSid, newConferenceName);
    } catch (ApiException e) {
      log.error("[TwilioAdapter] Bridge: KRYTYCZNY błąd redirect klienta: " +
                "customerCallSid={}, newConferenceName={}, code={}, msg={}",
          customerCallSid, newConferenceName, e.getCode(), e.getMessage(), e);
      throw new TelephonyException(callId1,
          "Nie można przekierować klienta do nowej konferencji: " + e.getMessage(), e);
    }

    // ---- Krok 7: zakończ nogę Agent1 ----
    String agentCallSid1 = session1.getAgentCallSid();
    if (agentCallSid1 != null) {
      try {
        Call.updater(agentCallSid1)
            .setStatus(Call.UpdateStatus.COMPLETED)
            .update(client);
        log.info("[TwilioAdapter] Bridge: noga Agent1 zakończona: agentCallSid1={}", agentCallSid1);
      } catch (ApiException e) {
        if (e.getCode() == 20404 || e.getStatusCode() == 404) {
          log.debug("[TwilioAdapter] Bridge: noga Agent1 już zakończona (404 ignorowane): agentCallSid1={}",
              agentCallSid1);
        } else {
          log.warn("[TwilioAdapter] Bridge: błąd zakończenia nogi Agent1 (kontynuuję): " +
                   "agentCallSid1={}, code={}, msg={}", agentCallSid1, e.getCode(), e.getMessage());
        }
      }
    } else {
      log.warn("[TwilioAdapter] Bridge: brak agentCallSid w sesji1 – noga Agent1 nie zostanie zakończona. callId1={}",
          callId1);
    }

    // ---- Krok 8: aktualizuj sesje Redis ----
    // Sesja1 → TRANSFERRED (Agent1 skończył)
    CallSession transferred = session1
        .withStatus(CallSession.CallStatus.TRANSFERRED)
        .withEndedAt(now);
    saveSession(transferred);

    // Sesja2 → ACTIVE, nowy contactId, nowa konferencja, zachowany customerCallSid
    CallSession active = session2
        .withStatus(CallSession.CallStatus.ACTIVE)
        .withContactId(newContactId)
        .withConferenceName(newConferenceName)
        .withDirection(null)                          // usuń flagę CONSULTATION
        .withCustomerCallSid(customerCallSid);        // zachowaj dla kolejnego bridgeCalls w łańcuchu
    saveSession(active);

    // Indeks Redis: newContactId → session2.getCallId() (nadpisujemy – nowy wpis dla nowego contactId)
    String indexKey = CONTACT_SESSION_INDEX_PREFIX + newContactId.toString();
    stringRedisTemplate.opsForValue().set(indexKey, session2.getCallId(),
        java.time.Duration.ofHours(24));
    log.info("[TwilioAdapter] Bridge: indeks Redis utworzony: newContactId={} → callId={}",
        newContactId, session2.getCallId());

    eventPublisher.publishTransferred(
        callId1, tenantId, session1.getAgentId(),
        session1.getFrom(), session1.getTo(),
        session2.getTo(), TransferType.ATTENDED.name()
    );

    log.info("[TwilioAdapter] Bridge zakończony: callId1={} (TRANSFERRED), callId2={} (ACTIVE), " +
             "newConferenceName={}, customerCallSid={}",
        callId1, callId2, newConferenceName, customerCallSid);
  }

  /**
   * {@inheritDoc}
   *
   * @throws TelephonyException gdy sesja nie istnieje w lokalnym cache
   */
  @Override
  public CallSession getCallSession(String callId) {
    return requireSession(callId);
  }

  /**
   * {@inheritDoc}
   *
   * <p>No-op od czasu refactoru attended transfer (Wariant A).
   * Aktualizacja sesji Redis i indeksu odwrotnego odbywa się wewnątrz
   * {@link #bridgeCalls(String, String, UUID)} – nie ma potrzeby wywoływać tej metody osobno.
   * Zachowana dla kompatybilności interfejsu.
   */
  @Override
  public void updateSessionContact(String callId, UUID newContactId) {
    log.debug("[TwilioAdapter] updateSessionContact: no-op (sesja aktualizowana przez bridgeCalls). " +
              "callId={}, newContactId={}", callId, newContactId);
  }

  // =========================================================================
  // Metody publiczne dla webhook handlera
  // =========================================================================

  /**
   * Aktualizuje lokalny stan sesji na podstawie statusu odebranego z webhooka Twilio.
   *
   * <p>Wywoływane przez {@code TwilioWebhookController} gdy Twilio wysyła callback
   * o zmianie statusu połączenia (initiated, ringing, answered, completed, failed itp.).
   *
   * @param callSid         Twilio Call SID
   * @param from            numer dzwoniącego
   * @param to              numer docelowy
   * @param callStatus      status połączenia od Twilio (np. "in-progress", "completed")
   * @param tenantId        tenant powiązany z połączeniem (wymagany do publikacji eventu)
   * @param twilioDirection kierunek połączenia od Twilio ("inbound", "outbound-api", "outbound-dial")
   */
  public void handleWebhookStatusUpdate(String callSid, String from, String to,
      String callStatus, UUID tenantId, String twilioDirection) {
    log.info("[TwilioAdapter] Webhook status update: callSid={}, status={}, from={}, to={}, tenant={}, direction={}",
        callSid, callStatus, from, to, tenantId, twilioDirection);

    CallSession.CallStatus mappedStatus = mapTwilioStatus(callStatus);

    CallSession existing = loadSessionFromRedis(callSid);

    // Połączenie wychodzące gdy:
    // 1) Twilio podaje kierunek outbound-api / outbound-dial w polu Direction, LUB
    // 2) Sesja istniała już w Redis (wstawiona przez initiateCall) – dotychczasowa logika
    boolean isOutbound = "outbound-api".equalsIgnoreCase(twilioDirection)
        || "outbound-dial".equalsIgnoreCase(twilioDirection)
        || existing != null;

    CallEvent.EventType eventType = mapTwilioStatusToEventType(callStatus, isOutbound);

    if (existing == null) {
      if (isOutbound) {
        // Twilio wysłał webhook outbound zanim sesja Redis została zarejestrowana.
        // Może się zdarzyć gdy StatusCallback dotrze przed powrotem z Call.creator().
        // NIE tworzymy tu rekordu INBOUND contact — contact OUTBOUND już istnieje lub
        // zostanie zaraz stworzony przez initiateCall(). Próbujemy odtworzyć sesję z DB.
        log.warn("[TwilioAdapter] Outbound webhook bez sesji Redis: callSid={}, status={}, direction={}",
            callSid, callStatus, twilioDirection);
        UUID recoveredContactId = null;
        if (tenantId != null) {
          try {
            recoveredContactId = contactRepository.findContactIdByCallSid(callSid, tenantId)
                .orElse(null);
          } catch (Exception e) {
            log.warn("[TwilioAdapter] Nie udało się odszukać outbound contactId z DB: callSid={}, error={}",
                callSid, e.getMessage());
          }
        }
        existing = CallSession.builder()
            .callId(callSid)
            .tenantId(tenantId)
            .from(from)
            .to(to)
            .status(mappedStatus)
            .startedAt(Instant.now())
            .contactId(recoveredContactId)
            .direction("OUTBOUND")
            .customerCallSid(callSid)  // for OUTBOUND the customer leg SID == the outbound call SID
            .build();
        saveSession(existing);
        log.info("[TwilioAdapter] Minimalna sesja outbound odtworzona z webhooka: callSid={}, contactId={}, status={}",
            callSid, recoveredContactId, mappedStatus);
      } else {
        // Pierwsze powiadomienie o połączeniu przychodzącym – tworzymy rekord contact w DB
        // i sesję połączenia. contactId z DB jest kluczowy dla frontendu (PATCH /api/contacts/{contactId}/...).
        // Sprawdzamy czy contact nie został już utworzony przez webhook /voice (unikamy duplikatów).
        UUID contactId = contactRepository.findContactIdByCallSid(callSid, tenantId)
            .orElseGet(() -> persistContact(tenantId, from, to, callSid));
        existing = CallSession.builder()
            .callId(callSid)
            .tenantId(tenantId)
            .from(from)
            .to(to)
            .status(mappedStatus)
            .startedAt(Instant.now())
            .contactId(contactId)
            .build();
        saveSession(existing);
        log.debug("[TwilioAdapter] Nowa sesja z webhooka: callSid={}, contactId={}, status={}",
            callSid, contactId, mappedStatus);

        // Jeśli webhook przyszedł gdy sesja nie istniała w mapie i status jest już końcowy
        // (np. klient rozłączył się przed rejestracją sesji lub callback dotarł po restarcie),
        // zapisujemy ABANDONED – agent nigdy nie odebrał, skoro nie było aktywnej sesji.
        if (mappedStatus == CallSession.CallStatus.ENDED && contactId != null) {
          log.info("[TwilioAdapter] Sesja nieznana – webhook ENDED bez wcześniejszej sesji, " +
                   "zapisuję ABANDONED: callSid={}, contactId={}", callSid, contactId);
          contactRepository.updateContactStatusOnTelephonyEvent(
              contactId, tenantId, "ABANDONED", Instant.now());
          contactEventService.closeQueue(contactId, tenantId);
          contactEventService.closeAgent(contactId, tenantId);
        }
      }
    }
    else {
      // Aktualizacja istniejącej sesji
      CallSession updated = existing.withStatus(mappedStatus);
      if (mappedStatus == CallSession.CallStatus.ACTIVE && existing.getAnsweredAt() == null) {
        updated = updated.withAnsweredAt(Instant.now());
      }
      // Dla połączeń OUTBOUND: oznacz moment faktycznego odebrania przez klienta.
      // answeredAt jest ustawiane przez answerCall() (agent kliknął "Odbierz"),
      // ale clientAnsweredAt ustawiamy dopiero gdy Twilio potwierdza in-progress –
      // tylko wtedy rozmowa naprawdę się odbyła i status końcowy powinien być COMPLETED.
      if (mappedStatus == CallSession.CallStatus.ACTIVE
          && "OUTBOUND".equals(existing.getDirection())
          && existing.getClientAnsweredAt() == null) {
        updated = updated.withClientAnsweredAt(Instant.now());
        log.debug("[TwilioAdapter] OUTBOUND: klient odebrał (in-progress), clientAnsweredAt ustawione: callSid={}", callSid);
      }
      Instant webhookEndedAt = null;
      if (mappedStatus == CallSession.CallStatus.ENDED && existing.getEndedAt() == null) {
        webhookEndedAt = Instant.now();
        updated = updated.withEndedAt(webhookEndedAt);
      }
      saveSession(updated);

      // OUTBOUND race condition fix:
      // Gdy klient odbierze telefon, Twilio wysyła StatusCallback z CallStatus=in-progress.
      // Dopiero teraz jest właściwy moment na wejście agenta do konferencji (klient nasłuchuje).
      // agentId musi być w sesji – ustawiony wcześniej przez answerCall().
      if ("OUTBOUND".equals(updated.getDirection())
          && mappedStatus == CallSession.CallStatus.ACTIVE
          && updated.getAgentId() != null
          && updated.getContactId() != null) {
        log.info("[TwilioAdapter] OUTBOUND in-progress: klient odebrał, inicjuję wejście agenta do konferencji: " +
                 "callSid={}, agentId={}, contactId={}",
            callSid, updated.getAgentId(), updated.getContactId());
        // Klient odebrał – anuluj ring timeout aby checkRingTimeouts() nie rozłączył aktywnej rozmowy
        stringRedisTemplate.delete("dialer:timeout:" + callSid);
        // Ustaw flagę "answered" – checkRingTimeouts() rozróżnia wygasły timeout od celowego usunięcia klucza
        stringRedisTemplate.opsForValue().set(
            "dialer:answered:" + callSid, "1", java.time.Duration.ofMinutes(60));
        log.debug("[TwilioAdapter] Połączenie oznaczone jako odebrane przez klienta: callSid={}", callSid);
        dialAgentIntoConference(updated);
      }

      // Backfill sip_call_id dla połączeń wychodzących.
      // persistOutboundContact() tworzy rekord contact PRZED wywołaniem Twilio API,
      // więc channel_metadata.sip_call_id jest null (callSid jeszcze nieznany).
      // Przy pierwszym StatusCallback (initiated/ringing) uzupełniamy sip_call_id,
      // aby findContactIdByCallSid, updateConferenceSidInMetadata i inne metody
      // mogły znaleźć rekord kontaktu po callSid.
      if (updated.getContactId() != null && tenantId != null) {
        try {
          contactRepository.backfillCallSidInMetadata(updated.getContactId(), callSid, tenantId);
        } catch (Exception backfillEx) {
          log.warn("[TwilioAdapter] Nie udało się backfillować sip_call_id: " +
                   "contactId={}, callSid={}, error={}",
              updated.getContactId(), callSid, backfillEx.getMessage());
        }
      }

      // Jeśli persistOutboundContact() zwróciła null (błąd DB) i contactId nie ma w sesji,
      // spróbuj odtworzyć contactId z DB po właśnie wgryziony callSid.
      // Dzięki temu answerCall() będzie mógł wywołać dialAgentIntoConference().
      if (updated.getContactId() == null && tenantId != null) {
        try {
          UUID recoveredContactId = contactRepository.findContactIdByCallSid(callSid, tenantId)
              .orElse(null);
          if (recoveredContactId != null) {
            updated = updated.withContactId(recoveredContactId);
            saveSession(updated);
            log.info("[TwilioAdapter] Odtworzono contactId z DB dla outbound: callSid={}, contactId={}",
                callSid, recoveredContactId);
          }
        } catch (Exception recoverEx) {
          log.warn("[TwilioAdapter] Nie udało się odtworzyć contactId z DB: callSid={}, error={}",
              callSid, recoverEx.getMessage());
        }
      }

      if (webhookEndedAt != null && updated.getContactId() != null
              && !"CONSULTATION".equals(updated.getDirection())) {
        // Jeśli klient rozłączył się zanim agent odebrał (answeredAt == null),
        // status kontaktu to ABANDONED (inbound) lub NOT_REACHED (outbound), a nie COMPLETED.
        String contactDbStatus = resolveContactEndStatus(updated);
        log.info("[TwilioAdapter] Aktualizacja statusu kontaktu na {}: contactId={}, answeredAt={}",
            contactDbStatus, updated.getContactId(), updated.getAnsweredAt());
        contactRepository.updateContactStatusOnTelephonyEvent(
            updated.getContactId(), updated.getTenantId(), contactDbStatus, webhookEndedAt);
        contactEventService.closeAgent(updated.getContactId(), updated.getTenantId());
        contactEventService.closeHold(updated.getContactId(), updated.getTenantId());
        if ("ABANDONED".equals(contactDbStatus)) {
          contactEventService.closeQueue(updated.getContactId(), updated.getTenantId());
        }
      }
    }

    if (eventType != null) {
      UUID effectiveTenantId = (tenantId != null) ? tenantId : existing.getTenantId();
      if (effectiveTenantId == null) {
        log.warn("[TwilioAdapter] Brak tenantId dla callSid={} – event {} nie zostanie wysłany do WebSocket",
            callSid, eventType);
      }

      // Bug 1a: Gdy klient rozłączył się przed odebraniem przez agenta, sesja Redis nie ma agentId
      // (routing ustawia agentId tylko w Contact DB, nie w CallSession).
      // Pobieramy agentId z DB aby CALL_HANGUP dotarł do właściwego agenta i jego softfon przestał dzwonić.
      UUID effectiveAgentId = existing.getAgentId();
      if (eventType == CallEvent.EventType.CALL_HANGUP
          && effectiveAgentId == null
          && existing.getContactId() != null
          && effectiveTenantId != null) {
        try {
          Optional<Contact> contactOpt = contactRepository.findById(existing.getContactId(), effectiveTenantId);
          if (contactOpt.isPresent() && contactOpt.get().getAgentId() != null) {
            effectiveAgentId = contactOpt.get().getAgentId();
            log.debug("[TwilioAdapter] Uzupełniono agentId z Contact DB dla CALL_HANGUP: " +
                      "callSid={}, contactId={}, agentId={}",
                callSid, existing.getContactId(), effectiveAgentId);
          }
        } catch (Exception e) {
          log.warn("[TwilioAdapter] Nie udało się pobrać agentId z Contact DB dla CALL_HANGUP: " +
                   "callSid={}, contactId={}, error={}",
              callSid, existing.getContactId(), e.getMessage());
        }
      }

      // Bug 1b: Gdy klient rozłącza się zanim agent odebrał (softfon dzwoni), noga agenta
      // (agentCallSid) musi być aktywnie rozłączona przez Twilio REST API.
      // Bez tego softfon agenta pozostaje w stanie RINGING w nieskończoność.
      if (eventType == CallEvent.EventType.CALL_HANGUP && existing.getAgentCallSid() != null) {
        try {
          Call.updater(existing.getAgentCallSid())
              .setStatus(Call.UpdateStatus.COMPLETED)
              .update(resolveRestClient(effectiveTenantId));
          log.info("[TwilioAdapter] Noga agenta anulowana po rozłączeniu klienta: " +
                   "agentCallSid={}, callSid={}", existing.getAgentCallSid(), callSid);
        } catch (ApiException e) {
          if (e.getCode() == 20404 || e.getStatusCode() == 404) {
            log.debug("[TwilioAdapter] Noga agenta już zakończona (idempotentne): " +
                      "agentCallSid={}, code={}", existing.getAgentCallSid(), e.getCode());
          } else {
            log.warn("[TwilioAdapter] Błąd przy anulowaniu nogi agenta: " +
                     "agentCallSid={}, code={}, msg={}",
                existing.getAgentCallSid(), e.getCode(), e.getMessage());
          }
        } catch (Exception e) {
          log.warn("[TwilioAdapter] Nieoczekiwany błąd przy anulowaniu nogi agenta: " +
                   "agentCallSid={}, error={}", existing.getAgentCallSid(), e.getMessage());
        }
      }

      // Guard: dla nogi CONSULTATION zakończonej przez hangupCall() – pomijamy CALL_HANGUP.
      // hangupCall() ustawia endedAt i publikuje CALL_CONSULT_CANCELLED (lub CALL_HANGUP gdy brak agentId).
      // Twilio wyśle późniejszy status callback "completed" – bez tego guardu wysłalibyśmy
      // CALL_HANGUP do Agent2 i otworzyli ekran ACW mimo że konsultacja była już obsłużona.
      if ("CONSULTATION".equals(existing.getDirection())
              && eventType == CallEvent.EventType.CALL_HANGUP
              && existing.getEndedAt() != null) {
        log.debug("[TwilioAdapter] Skip CALL_HANGUP dla już obsłużonej nogi CONSULTATION: callSid={}", callSid);
        return;
      }

      publishWebhookEvent(eventType, callSid, effectiveTenantId,
          effectiveAgentId, from, to, existing.getContactId(), callStatus);
    }
  }

  /**
   * Zwraca liczbę aktywnych sesji (dla testów i monitoringu).
   *
   * <p>Po migracji na Redis skanowanie wszystkich kluczy {@code call-session:*} byłoby
   * kosztowną operacją O(N) na całym keyspace. Metoda zwraca -1 jako sygnał że
   * metryka nie jest dostępna po stronie aplikacji – monitoring powinien używać
   * dedykowanego endpointu lub Redis SCAN na potrzeby testów.
   */
  public int getActiveSessionCount() {
    return -1;
  }

  /**
   * Rejestruje połączenie przychodzące w lokalnym cache sesji.
   *
   * <p>Idempotentne – jeśli sesja dla danego callSid już istnieje, nie nadpisuje jej.
   * Wywoływane przez {@code TwilioWebhookController.handleVoiceWebhook()} bezpośrednio
   * po utworzeniu rekordu contact w DB, zanim StatusCallback od Twilio dotrze do serwera.
   *
   * @param callSid   Twilio Call SID (np. CAxxxxxxxx)
   * @param from      numer dzwoniącego w formacie E.164
   * @param to        numer docelowy (Twilio phone number)
   * @param tenantId  UUID tenanta powiązanego z połączeniem
   * @param contactId UUID rekordu contact utworzonego w DB
   */
  public void registerIncomingCall(String callSid, String from, String to,
                                    UUID tenantId, UUID contactId) {
    // Idempotentne – nie nadpisujemy istniejącej sesji (computeIfAbsent semantics przez Redis)
    if (loadSessionFromRedis(callSid) == null) {
      CallSession session = CallSession.builder()
          .callId(callSid)
          .tenantId(tenantId)
          .from(from)
          .to(to)
          .status(CallSession.CallStatus.RINGING)
          .startedAt(Instant.now())
          .contactId(contactId)
          .customerCallSid(callSid)  // callSid klienta = SID przychodzącego połączenia
          .build();
      saveSession(session);
      log.info("[TwilioAdapter] Zarejestrowano połączenie przychodzące: callSid={}, " +
               "contactId={}, tenant={}", callSid, contactId, tenantId);
    }
  }

  // =========================================================================
  // Prywatne metody pomocnicze
  // =========================================================================

  // =========================================================================
  // Fallback nagrania (Naprawa 3)
  // =========================================================================

  /**
   * Wyznacza końcowy status rekordu contact na podstawie stanu sesji.
   *
   * <ul>
   *   <li>Połączenie OUTBOUND, klient odebrał ({@code clientAnsweredAt != null}) → {@code COMPLETED}</li>
   *   <li>Połączenie OUTBOUND, klient nie odebrał ({@code clientAnsweredAt == null}) → {@code NOT_REACHED}</li>
   *   <li>Połączenie INBOUND, agent odebrał ({@code answeredAt != null}) → {@code COMPLETED}</li>
   *   <li>Połączenie INBOUND, nikt nie odebrał → {@code ABANDONED}</li>
   * </ul>
   *
   * <p>Dla OUTBOUND używamy {@link CallSession#getClientAnsweredAt()} zamiast {@link CallSession#getAnsweredAt()},
   * ponieważ {@code answeredAt} jest ustawiane gdy agent kliknie "Odbierz" (nawet zanim klient podniesie
   * słuchawkę), a {@code clientAnsweredAt} dopiero gdy Twilio wyśle {@code StatusCallback in-progress}
   * — czyli gdy rozmowa naprawdę się nawiązała.
   */
  private String resolveContactEndStatus(CallSession session) {
    if ("OUTBOUND".equals(session.getDirection())) {
      // Dla OUTBOUND: COMPLETED tylko gdy klient faktycznie odebrał (Twilio in-progress webhook)
      return session.getClientAnsweredAt() != null ? "COMPLETED" : "NOT_REACHED";
    }
    // Dla INBOUND: COMPLETED gdy agent odebrał, ABANDONED gdy nie
    return session.getAnsweredAt() != null ? "COMPLETED" : "ABANDONED";
  }

  /**
   * Planuje asynchroniczne sprawdzenie nagrania przez Twilio REST API po 90 sekundach.
   *
   * <p>Twilio wysyła {@code recordingStatusCallback} asynchronicznie (~2 min po zakończeniu).
   * Jeśli callback nie dotrze (restart aplikacji, 502, timeout sieci), nagranie nigdy
   * nie trafi do DB. Ta metoda stanowi zabezpieczenie fallback: po 90s odpytuje
   * Twilio Recording API i jeśli znajdzie nagranie a {@code recording_url} w DB jest null,
   * pobiera je i zapisuje.
   *
   * <p>TenantContext jest przechwytywany przed fork wątku (snapshot), a odtwarzany
   * w wątku roboczym ({@code restore}) z obowiązkowym {@code clear()} w {@code finally}.
   *
   * @param callSid   Twilio Call SID
   * @param contactId UUID kontaktu powiązanego z połączeniem
   * @param tenantId  UUID tenanta
   */
  private void scheduleRecordingFallback(String callSid, UUID contactId, UUID tenantId) {
    TenantContext.Snapshot snapshot = TenantContext.snapshot();

    CompletableFuture.delayedExecutor(90, TimeUnit.SECONDS).execute(() -> {
      TenantContext.restore(snapshot);
      try {
        log.debug("[TwilioAdapter] Fallback nagrania: sprawdzam Twilio Recording API dla callSid={}, " +
                 "contactId={}, tenantId={}", callSid, contactId, tenantId);

        // Sprawdź czy nagranie nie zostało już zapisane przez normalny webhook
        boolean alreadySaved = contactRepository.findRecordingUrl(contactId, tenantId)
            .filter(url -> url != null && !url.isBlank())
            .isPresent();
        if (alreadySaved) {
          log.debug("[TwilioAdapter] Fallback nagrania: recording_url już istnieje, pomijam. " +
                   "callSid={}, contactId={}", callSid, contactId);
          return;
        }

        // Pobierz nagrania przez Twilio call.Recording REST API
        Iterable<Recording> recordings;
        try {
          recordings = Recording.reader(callSid).read(resolveRestClient(tenantId));
        } catch (ApiException e) {
          // Nagranie może nie istnieć gdy rozmowa trwała <1s lub nagrywanie było wyłączone
          log.info("[TwilioAdapter] Fallback nagrania: brak nagrań w Twilio API dla callSid={} " +
                   "(code={}, message={})", callSid, e.getCode(), e.getMessage());
          return;
        }

        if (recordings == null || !recordings.iterator().hasNext()) {
          log.info("[TwilioAdapter] Fallback nagrania: Twilio nie zwrócił nagrań dla callSid={}. " +
                   "Rozmowa była zbyt krótka lub nagrywanie wyłączone.", callSid);
          return;
        }

        Recording recording = recordings.iterator().next();
        String recordingSid = recording.getSid();
        // Twilio Recording URL – dopisanie .mp3 daje bezpośredni link do pliku audio
        String recordingUrl = "https://api.twilio.com/2010-04-01/Accounts/"
            + resolveAccountSid(tenantId)
            + "/Recordings/" + recordingSid + ".mp3";

        log.info("[TwilioAdapter] Fallback nagrania: znaleziono nagranie – pobieranie. " +
                 "callSid={}, recordingSid={}, contactId={}", callSid, recordingSid, contactId);

        // Deleguj pobranie i upload do istniejącego serwisu (ten sam flow co normalny webhook)
        recordingDownloadService.downloadAndStore(
            recordingUrl, recordingSid, callSid, null, contactId, tenantId);

      } catch (Exception e) {
        log.error("[TwilioAdapter] Fallback nagrania: nieoczekiwany błąd dla callSid={}, " +
                  "contactId={}: {}", callSid, contactId, e.getMessage(), e);
      } finally {
        TenantContext.clear();
      }
    });
  }

  // =========================================================================
  // Metody zarządzania sesjami w Redis
  // =========================================================================

  /**
   * Zapisuje sesję połączenia w Redis z TTL 24h.
   *
   * <p>TTL 24h pokrywa najdłuższe możliwe połączenie (Twilio max ~4h) plus bufor
   * na callbacki docierające do ~2 min po zakończeniu rozmowy.
   *
   * <p>Gdy sesja zawiera {@code contactId}, atomowo tworzy też indeks odwrotny
   * {@code contact-session-index:{contactId}} → {@code callSid}. Indeks umożliwia
   * {@link #requireSession} znalezienie sesji gdy frontend przekazuje UUID kontaktu
   * zamiast Twilio CallSid, nawet gdy {@code channel_metadata->>'sip_call_id'} jest
   * tymczasowo null w bazie danych.
   *
   * @param session sesja do zapisania
   */
  private void saveSession(CallSession session) {
    String key = SESSION_KEY_PREFIX + session.getCallId();
    redisTemplate.opsForValue().set(key, session, RedisConfig.TTL_CALL_SESSION);

    // Indeks odwrotny contactId → callSid: pozwala requireSession() znaleźć sesję
    // gdy AgentCallController przekazuje UUID kontaktu zamiast CA-SID.
    // StringRedisTemplate (nie GenericJackson2JsonRedisSerializer) – wartość przechowywana
    // jako czysty String bez cudzysłowów JSON. Nie nadpisujemy istniejącego wpisu –
    // pierwszy callSid wygrywa (idempotentne).
    if (session.getContactId() != null) {
      String indexKey = CONTACT_SESSION_INDEX_PREFIX + session.getContactId().toString();
      stringRedisTemplate.opsForValue().setIfAbsent(indexKey, session.getCallId(), RedisConfig.TTL_CALL_SESSION);
    }
  }

  /**
   * Odczytuje sesję połączenia z Redis.
   *
   * @param callId Twilio Call SID
   * @return sesja lub {@code null} gdy nie istnieje
   */
  private CallSession loadSessionFromRedis(String callId) {
    Object raw = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + callId);
    if (raw instanceof CallSession session) {
      return session;
    }
    return null;
  }

  /**
   * Implementacja interfejsowej metody {@link TelephonyAdapter#getSession(String)}.
   *
   * <p>Zwraca Optional zamiast rzucania wyjątku – używane do weryfikacji cross-tenant
   * przy bridge/transfer bez konieczności łapania TelephonyException w warstwie domenowej.
   *
   * @param callId Twilio Call SID
   * @return Optional z sesją lub empty gdy sesja nie istnieje w Redis
   */
  @Override
  public java.util.Optional<CallSession> getSession(String callId) {
    return java.util.Optional.ofNullable(loadSessionFromRedis(callId));
  }

  /**
   * Usuwa sesję z Redis. Gdy sesja zawiera {@code contactId}, usuwa też indeks odwrotny.
   *
   * @param callId Twilio Call SID
   */
  private void deleteSession(String callId) {
    CallSession session = loadSessionFromRedis(callId);
    redisTemplate.delete(SESSION_KEY_PREFIX + callId);
    if (session != null && session.getContactId() != null) {
      stringRedisTemplate.delete(CONTACT_SESSION_INDEX_PREFIX + session.getContactId().toString());
    }
  }

  private CallSession executeBlindTransfer(String callId, String target, CallSession session) {
    String twilioCallSid = session.getCallId();
    try {
      // Twilio Client wymaga <Client>identity</Client>, a nie <Number>client:identity</Number>
      String dialNoun = target.startsWith("client:")
          ? "<Client>" + target.substring("client:".length()) + "</Client>"
          : "<Number>" + target + "</Number>";
      String twiml = "<Response><Dial>" + dialNoun + "</Dial></Response>";

      Call.updater(twilioCallSid)
          .setTwiml(new Twiml(twiml))
          .update(resolveRestClient(session.getTenantId()));

    }
    catch (ApiException e) {
      log.error("[TwilioAdapter] Błąd Twilio API przy blind transfer callId={}, twilioCallSid={}: {}",
          callId, twilioCallSid, e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wykonać blind transfer: " + e.getMessage(), e);
    }

    Instant now = Instant.now();

    UUID originalContactId = session.getContactId();
    if (originalContactId != null) {
      try {
        contactRepository.updateContactStatusOnTelephonyEvent(
            originalContactId, session.getTenantId(), "TRANSFERRED", now);
        log.info("[TwilioAdapter] Kontakt oznaczony jako TRANSFERRED (blind agent): contactId={}", originalContactId);
      } catch (Exception e) {
        log.error("[TwilioAdapter] Błąd aktualizacji statusu na TRANSFERRED: contactId={}, error={}",
            originalContactId, e.getMessage(), e);
      }
    }

    CallSession transferred = session
        .withStatus(CallSession.CallStatus.TRANSFERRED)
        .withEndedAt(now);
    saveSession(transferred);

    log.info("[TwilioAdapter] Blind transfer wykonany: callId={}, target={}", callId, target);

    eventPublisher.publishTransferred(
        callId, session.getTenantId(), session.getAgentId(),
        session.getFrom(), session.getTo(), target, TransferType.BLIND.name()
    );

    return transferred;
  }

  private CallSession executeAttendedTransfer(String callId, String target, CallSession session) {
    // Wstrzymaj oryginalne połączenie
    saveSession(session.withStatus(CallSession.CallStatus.ON_HOLD));

    try {
      // TwiML drugiej nogi: agent2 dołącza do konferencji klienta w trybie oczekiwania
      // (startConferenceOnEnter="false") — konferencja nie startuje dopóki nie zostanie
      // wywołany bridgeCalls(), który zakończy nogę agenta1 i pozwoli klientowi usłyszeć
      // agent2. Nazwa konferencji jest taka sama jak konferencja klienta, więc po bridge
      // obaj będą w jednym pokoju audio.
      // Używamy conferenceName z sesji (ustalonego w dialAgentIntoConference), żeby nie zgubić
      // właściwej konferencji po updateSessionContact(), która zmienia session.getContactId().
      String conferenceName = session.getConferenceName() != null
          ? session.getConferenceName()
          : "contact-" + session.getContactId().toString();
      String consultTwiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Response><Dial>"
          + "<Conference startConferenceOnEnter=\"false\" endConferenceOnExit=\"false\">"
          + conferenceName
          + "</Conference>"
          + "</Dial></Response>";

      // Inicjuj nowe połączenie do target (druga noga)
      var secondLegCreator = Call.creator(
          new PhoneNumber(target),
          new PhoneNumber(resolvePhoneNumber(session.getTenantId())),
          new Twiml(consultTwiml)
      );
      String rawBase = buildRawWebhookBaseUrl(session.getTenantId());
      if (rawBase != null) {
        secondLegCreator.setStatusCallback(URI.create(rawBase + "?tenantId=" + session.getTenantId()));
        secondLegCreator.setStatusCallbackMethod(com.twilio.http.HttpMethod.POST);
        secondLegCreator.setStatusCallbackEvent(java.util.List.of("completed", "canceled"));
      }
      Call secondLegCall = secondLegCreator.create(resolveRestClient(session.getTenantId()));

      String secondLegSid = secondLegCall.getSid();
      Instant now = Instant.now();

      // Wyciągnij targetAgentId gdy cel to Twilio Client (format: "client:agent-{uuid}")
      UUID targetAgentId = null;
      if (target != null && target.startsWith("client:agent-")) {
        try {
          targetAgentId = UUID.fromString(target.substring("client:agent-".length()));
        } catch (IllegalArgumentException ignored) {
          log.warn("[TwilioAdapter] Nie można sparsować targetAgentId z target={}", target);
        }
      }

      // Druga noga (consultation leg): contactId powiązany z oryginalnym kontaktem – potrzebny
      // do wyszukania konferencji po nazwie ("contact-{contactId}") w bridgeCalls() i pomijania
      // dialAgentIntoConference dla tej nogi. direction="CONSULTATION" zapobiega nadpisywaniu
      // statusu kontaktu w hangupCall() i handleWebhookStatusUpdate().
      // customerCallSid kopiowany z sesji Agent1 – bridgeCalls() użyje go do redirect klienta.
      // (targetAgentId null dla transferu na numer zewnętrzny – relay użyje broadcastu do tenanta)
      CallSession secondLeg = CallSession.builder()
          .callId(secondLegSid)
          .tenantId(session.getTenantId())
          .agentId(targetAgentId)
          .contactId(session.getContactId())          // needed for conference lookup in bridgeCalls
          .from(session.getFrom())                    // preserve customer's number through transfer chain
          .to(target)
          .status(mapTwilioStatus(secondLegCall.getStatus()))
          .startedAt(now)
          .direction("CONSULTATION")                  // prevents contact status overwrite for this leg
          .conferenceName(conferenceName)             // preserve actual conference for bridge chain
          .customerCallSid(session.getCustomerCallSid()) // propagate for bridgeCalls() redirect
          .build();

      saveSession(secondLeg);

      log.info("[TwilioAdapter] Attended transfer – 2nd leg: callId={}, secondLegSid={}, target={}, " +
               "originalContactId={}, targetAgentId={}",
          callId, secondLegSid, target, session.getContactId(), targetAgentId);

      // CLI lookup – rozpoznaj klienta po numerze telefonu, żeby CALL_TRANSFER_CONSULT
      // zawierał customerName zamiast fallbacku "Nieznany (numer)".
      String customerPhone = session.getFrom();
      CustomerCliResult customerInfo = cliLookupService
          .lookupCustomer(customerPhone, session.getTenantId())
          .orElse(null);

      // Publikuj CALL_TRANSFER_CONSULT (nie CALL_INCOMING) – IvrCallListener jest zbindowany
      // tylko do call.incoming, więc IVR nie zostanie uruchomiony dla tej nogi.
      // RabbitToWebSocketRelay przekaże event do docelowego agenta z kontekstem konsultacji.
      eventPublisher.publishTransferConsult(
          secondLegSid,
          session.getTenantId(),
          targetAgentId,
          session.getAgentId(),      // agent inicjujący konsultację
          session.getContactId(),    // oryginalny kontakt (klient)
          customerPhone,             // customer's phone number (original call's from)
          target,
          customerInfo);             // CLI lookup result – nazwa klienta dla Agent2

      return secondLeg;

    }
    catch (ApiException e) {
      // Przywróć oryginalne połączenie do ACTIVE gdy inicjacja 2nd leg się nie udała
      saveSession(session.withStatus(CallSession.CallStatus.ACTIVE));
      log.error("[TwilioAdapter] Błąd Twilio API przy attended transfer callId={}: {}",
          callId, e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wykonać attended transfer: " + e.getMessage(), e);
    }
  }

  private CallSession requireSession(String callId) {
    CallSession session = loadSessionFromRedis(callId);
    if (session == null) {
      // Fallback 1: callId may be a contactId (UUID from DB) sent by the frontend.
      // First try the Redis reverse index (contact-session-index:{contactId} → callSid).
      // This is faster and more reliable than a DB lookup because it is written atomically
      // in saveSession() whenever a session with contactId is persisted. The DB lookup
      // (channel_metadata->>'sip_call_id') can return null when:
      //   a) an OUTBOUND contact was created before Twilio returned a callSid (backfill pending)
      //   b) the DB write failed while the Redis session was already registered
      try {
        UUID contactId = UUID.fromString(callId);

        // Fallback 1a: Redis reverse index (no DB roundtrip)
        // Używamy StringRedisTemplate – wartość to czysty String CA... bez cudzysłowów JSON.
        String indexKey = CONTACT_SESSION_INDEX_PREFIX + contactId.toString();
        String resolvedCallSid = stringRedisTemplate.opsForValue().get(indexKey);
        if (resolvedCallSid != null) {
          session = loadSessionFromRedis(resolvedCallSid);
          if (session != null) {
            log.debug("[TwilioAdapter] Sesja znaleziona przez indeks Redis: contactId={}, callSid={}",
                contactId, resolvedCallSid);
          }
        }

        // Fallback 1b: DB lookup (sip_call_id in channel_metadata)
        if (session == null) {
          UUID tenantId = TenantContext.getTenantId();
          if (tenantId != null) {
            session = contactRepository.findCallSidByContactId(contactId, tenantId)
                .map(this::loadSessionFromRedis)
                .orElse(null);
            if (session != null) {
              log.debug("[TwilioAdapter] Sesja znaleziona przez DB lookup sip_call_id: contactId={}", contactId);
            }
          }
        }
      } catch (IllegalArgumentException ignored) {
        // callId is not a valid UUID — not a contactId, skip fallback
      } catch (Exception e) {
        log.debug("[TwilioAdapter] Błąd lookup callSid po contactId: callId={}, error={}",
            callId, e.getMessage());
      }
    }
    // Fallback 2: sesja może nie istnieć w Redis gdy StatusCallback nie dotarł jeszcze.
    // Próbujemy odtworzyć sesję na podstawie callSid z DB (tabela contact, pole channelMetadata).
    if (session == null && callId.startsWith("CA")) {
      session = tryRestoreSessionFromDb(callId);
    }
    if (session == null) {
      throw new TelephonyException(callId, "Sesja połączenia nie istnieje: " + callId);
    }
    return session;
  }

  /**
   * Próbuje odtworzyć sesję połączenia z bazy danych gdy nie ma jej w Redis.
   *
   * <p>Sytuacja może wystąpić gdy:
   * <ul>
   *   <li>StatusCallback od Twilio nie dotarł jeszcze przed akcją agenta</li>
   *   <li>Redis był niedostępny przez krótki czas i sesja wygasła (TTL 24h)</li>
   * </ul>
   *
   * @param callSid Twilio Call SID (CA...)
   * @return odtworzona {@link CallSession} lub {@code null} gdy nie znaleziono w DB
   */
  private CallSession tryRestoreSessionFromDb(String callSid) {
    try {
      UUID tenantId = TenantContext.getTenantId();
      if (tenantId == null) return null;
      return contactRepository.findContactIdByCallSid(callSid, tenantId)
          .map(contactId -> {
            CallSession restored = CallSession.builder()
                .callId(callSid)
                .tenantId(tenantId)
                .status(CallSession.CallStatus.RINGING)
                .startedAt(Instant.now())
                .contactId(contactId)
                .build();
            saveSession(restored);
            log.warn("[TwilioAdapter] Sesja odtworzona z DB (brak w Redis): callSid={}, " +
                     "contactId={}, tenant={}", callSid, contactId, tenantId);
            return restored;
          })
          .orElse(null);
    } catch (Exception e) {
      log.debug("[TwilioAdapter] Nie udało się odtworzyć sesji z DB: callSid={}, error={}",
                callSid, e.getMessage());
      return null;
    }
  }

  private void validateBridgeable(CallSession session) {
    if (session.getStatus() != CallSession.CallStatus.ACTIVE
        && session.getStatus() != CallSession.CallStatus.ON_HOLD) {
      throw new TelephonyException(session.getCallId(),
          "Sesja nie może być bridgowana w stanie: " + session.getStatus());
    }
  }

  /**
   * Zwraca numer telefonu Twilio dla danego tenanta.
   *
   * <p>Priorytet: per-tenant (config JSONB {@code twilio_phone_number})
   * → globalny fallback ({@code twilio.phone-number}).
   *
   * @param tenantId UUID tenanta
   * @return numer telefonu w formacie E.164
   * @throws TelephonyException gdy ani per-tenant, ani globalny numer nie jest skonfigurowany
   */
  public String resolvePhoneNumber(UUID tenantId) {
    if (tenantId != null) {
      try {
        String perTenantNumber = tenantRepository.findById(tenantId)
            .map(Tenant::getTwilioPhoneNumber)
            .orElse(null);
        if (StringUtils.hasText(perTenantNumber)) {
          log.debug("[TwilioAdapter] Używam per-tenant numeru Twilio: tenantId={}", tenantId);
          return perTenantNumber;
        }
      } catch (Exception e) {
        log.warn("[TwilioAdapter] Błąd odczytu per-tenant numeru Twilio dla tenantId={}: {} – " +
                 "fallback do konfiguracji globalnej", tenantId, e.getMessage());
      }
    }

    String globalNumber = twilioProperties.getPhoneNumber();
    if (!StringUtils.hasText(globalNumber)) {
      throw new TelephonyException(
          null, "Brak skonfigurowanego numeru Twilio (twilio.phone-number) " +
                "ani per-tenant (config.twilio_phone_number)");
    }
    log.debug("[TwilioAdapter] Używam globalnego numeru Twilio: tenantId={}", tenantId);
    return globalNumber;
  }

  // =========================================================================
  // Per-tenant TwilioRestClient – cache + fallback
  // =========================================================================

  /**
   * Zwraca TwilioRestClient dla danego tenanta – per-tenant lub globalny fallback.
   * Wynik jest cache'owany w Caffeine (max 100 wpisów, TTL 15 min).
   */
  private TwilioRestClient resolveRestClient(UUID tenantId) {
    if (tenantId == null) {
      return buildGlobalClient();
    }
    TwilioRestClient cached = clientCache.getIfPresent(tenantId);
    if (cached != null) {
      return cached;
    }
    TwilioRestClient client = buildClientForTenant(tenantId);
    clientCache.put(tenantId, client);
    return client;
  }

  private TwilioRestClient buildClientForTenant(UUID tenantId) {
    try {
      Optional<TenantTwilioConfigDecrypted> configOpt =
              tenantTwilioConfigService.getDecryptedConfig(tenantId);
      if (configOpt.isPresent()) {
        TenantTwilioConfigDecrypted cfg = configOpt.get();
        if (StringUtils.hasText(cfg.apiKeySid()) && StringUtils.hasText(cfg.apiKeySecret())
                && StringUtils.hasText(cfg.accountSid())) {
          log.debug("[Twilio] tenant={} używa per-tenant konfiguracji (API key)", tenantId);
          return new TwilioRestClient.Builder(cfg.apiKeySid(), cfg.apiKeySecret())
                  .accountSid(cfg.accountSid())
                  .build();
        }
        if (StringUtils.hasText(cfg.accountSid()) && StringUtils.hasText(cfg.authToken())) {
          log.debug("[Twilio] tenant={} używa per-tenant konfiguracji (authToken)", tenantId);
          return new TwilioRestClient.Builder(cfg.accountSid(), cfg.authToken()).build();
        }
      }
    } catch (Exception e) {
      log.warn("[Twilio] Błąd pobrania per-tenant config dla tenant={}: {} – fallback do globalnej konfiguracji",
              tenantId, e.getMessage());
    }
    log.debug("[Twilio] tenant={} używa globalnej konfiguracji", tenantId);
    return buildGlobalClient();
  }

  private TwilioRestClient buildGlobalClient() {
    if (StringUtils.hasText(twilioProperties.getApiKeySid())
            && StringUtils.hasText(twilioProperties.getApiKeySecret())) {
      return new TwilioRestClient.Builder(
              twilioProperties.getApiKeySid(),
              twilioProperties.getApiKeySecret())
              .accountSid(twilioProperties.getAccountSid())
              .build();
    }
    return new TwilioRestClient.Builder(
            twilioProperties.getAccountSid(),
            twilioProperties.getAuthToken())
            .build();
  }

  /**
   * Zwraca accountSid dla tenanta (per-tenant lub globalny fallback).
   * Używany do budowania URL nagrań i TwiML callbacków.
   */
  public String resolveAccountSid(UUID tenantId) {
    if (tenantId != null) {
      try {
        Optional<TenantTwilioConfigDecrypted> cfg =
                tenantTwilioConfigService.getDecryptedConfig(tenantId);
        if (cfg.isPresent() && StringUtils.hasText(cfg.get().accountSid())) {
          return cfg.get().accountSid();
        }
      } catch (Exception e) {
        log.warn("[Twilio] Błąd odczytu per-tenant accountSid dla tenant={} – fallback",
                tenantId, e.getMessage());
      }
    }
    return twilioProperties.getAccountSid();
  }

  /**
   * Inwaliduje cache TwilioRestClient po zmianie konfiguracji tenanta.
   */
  @EventListener
  public void onTwilioConfigChanged(TwilioConfigChangedEvent event) {
    if (clientCache != null) {
      clientCache.invalidate(event.tenantId());
      log.info("[TwilioAdapter] Cache TwilioRestClient zinwalidowany dla tenant={}",
              event.tenantId());
    }
  }

  private String buildStatusCallbackUrl(UUID tenantId) {
    String base = buildRawWebhookBaseUrl(tenantId);
    if (base == null) return null;
    if (tenantId != null && !base.contains("tenantId=")) {
      String separator = base.contains("?") ? "&" : "?";
      return base + separator + "tenantId=" + tenantId;
    }
    return base;
  }

  /**
   * Zwraca bazowy URL webhooka Twilio BEZ doklejonego query param {@code tenantId}.
   * Używany gdy caller sam buduje sub-ścieżkę (np. /recording, /conference).
   */
  private String buildRawWebhookBaseUrl(UUID tenantId) {
    if (tenantId != null && twilioProperties.isPerTenantCallbackUrlEnabled()) {
      try {
        String perTenantUrl = tenantRepository.findById(tenantId)
            .map(Tenant::getTwilioStatusCallbackUrl)
            .orElse(null);
        if (StringUtils.hasText(perTenantUrl)) {
          log.debug("[TwilioAdapter] Używam per-tenant callback URL: tenantId={}", tenantId);
          // Strip query string so callers can append their own sub-paths cleanly
          int q = perTenantUrl.indexOf('?');
          return q >= 0 ? perTenantUrl.substring(0, q) : perTenantUrl;
        }
      } catch (Exception e) {
        log.warn("[TwilioAdapter] Błąd odczytu per-tenant callback URL dla tenantId={}: {} – " +
                 "fallback do konfiguracji globalnej", tenantId, e.getMessage());
      }
    }
    String globalUrl = twilioProperties.getStatusCallbackUrl();
    if (!StringUtils.hasText(globalUrl)) return null;
    int q = globalUrl.indexOf('?');
    return q >= 0 ? globalUrl.substring(0, q) : globalUrl;
  }

  /**
   * Mapuje status Twilio (string z API lub webhooka) na domenowy {@link CallSession.CallStatus}.
   *
   * <p>Statusy Twilio: {@code queued}, {@code initiated}, {@code ringing},
   * {@code in-progress}, {@code completed}, {@code busy}, {@code failed},
   * {@code no-answer}, {@code canceled}.
   */
  public CallSession.CallStatus mapTwilioStatus(Object twilioStatus) {
    if (twilioStatus == null) {
      return CallSession.CallStatus.RINGING;
    }

    String status = twilioStatus.toString().toLowerCase();
    return switch (status) {
      case "queued", "initiated" -> CallSession.CallStatus.RINGING;
      case "ringing" -> CallSession.CallStatus.RINGING;
      case "in-progress" -> CallSession.CallStatus.ACTIVE;
      case "completed", "busy",
           "failed", "no-answer",
           "canceled" -> CallSession.CallStatus.ENDED;
      default -> {
        log.warn("[TwilioAdapter] Nieznany status Twilio: '{}' – mapuję na RINGING", status);
        yield CallSession.CallStatus.RINGING;
      }
    };
  }

  /**
   * Mapuje status Twilio na typ eventu domenowego do publikacji na RabbitMQ.
   *
   * <p>Dla połączeń wychodzących (isOutbound=true) statusy ringing/initiated zwracają
   * {@code null} – kierunek OUTBOUND został już opublikowany przez {@code initiateCall}
   * przez {@code publishOutbound}. Ponowna publikacja CALL_INCOMING lub CALL_OUTBOUND
   * byłaby duplikatem i myliłaby frontend (np. wyświetlanie podwójnej karty).
   *
   * @param callStatus status Twilio
   * @param isOutbound true gdy połączenie wychodzące (sesja istniała przed webhookiem)
   * @return typ eventu lub {@code null} gdy status nie generuje eventu
   */
  private CallEvent.EventType mapTwilioStatusToEventType(String callStatus, boolean isOutbound) {
    if (callStatus == null)
      return null;

    return switch (callStatus.toLowerCase()) {
      case "ringing", "initiated", "queued" ->
          // Dla outbound – event CALL_OUTBOUND już opublikowany przez initiateCall; nie duplikuj.
          // Dla inbound – publikuj CALL_INCOMING aby frontend wyświetlił dzwonek.
          isOutbound ? null : CallEvent.EventType.CALL_INCOMING;
      case "in-progress" -> CallEvent.EventType.CALL_ANSWERED;
      case "completed", "busy",
           "failed", "no-answer",
           "canceled" -> CallEvent.EventType.CALL_HANGUP;
      default -> null;
    };
  }

  private void publishWebhookEvent(CallEvent.EventType eventType, String callSid,
      UUID tenantId, UUID agentId,
      String from, String to, UUID contactId, String callStatus) {
    switch (eventType) {
      case CALL_INCOMING -> eventPublisher.publishIncoming(callSid, contactId, tenantId, agentId, from, to);
      case CALL_ANSWERED -> eventPublisher.publishAnswered(callSid, tenantId, agentId, from, to);
      case CALL_HANGUP -> eventPublisher.publishHangup(callSid, contactId, tenantId, agentId, from, to, callStatus);
      default -> log.debug("[TwilioAdapter] Brak publikacji dla eventType={}", eventType);
    }
  }

  /**
   * Creates a contact record in the database for an outbound (dialer-initiated) Twilio call.
   *
   * <p>Called by {@link #initiateCall} BEFORE the Twilio API call, so that the contactId
   * is immediately available in the {@link CallSession}. This is critical for:
   * <ul>
   *   <li>Naming the Twilio Conference correctly ({@code contact-{contactId}}).</li>
   *   <li>Allowing {@link #dialAgentIntoConference} to join the right conference when the
   *       customer answers.</li>
   *   <li>Giving the agent desktop the contactId needed to save a disposition via
   *       {@code PATCH /api/contacts/{contactId}/disposition}.</li>
   * </ul>
   *
   * <p>Direction is set to {@code OUTBOUND}; status starts as {@code QUEUED} (dialing).
   * The {@code callSid} is stored atomically in {@code channelMetadata.sip_call_id} during INSERT,
   * eliminating the race condition where a StatusCallback could arrive before back-fill.
   *
   * <p>On DB error: logs ERROR and returns {@code null}. The call is not blocked, but
   * the agent will not be able to save a disposition for this contact.
   *
   * @param tenantId UUID of the tenant
   * @param from     outbound number (Twilio phone number, the "from" side)
   * @param to       customer's number (the "to" side)
   * @param agentId  UUID of the agent assigned to the call
   * @param callSid  Twilio Call SID obtained from Twilio API before calling this method
   * @return UUID of the newly created contact record, or null on DB error
   */
  private UUID persistOutboundContact(UUID tenantId, String from, String to, UUID agentId, UUID campaignId, UUID callbackId, String callSid, UUID preGeneratedContactId) {
    try {
      // Use the pre-generated contactId so the conference name in TwiML ("contact-{contactId}")
      // matches the name used in dialAgentIntoConference() — both sides join the same conference.
      UUID contactId = preGeneratedContactId;
      Instant now = Instant.now();

      HashMap<String, Object> metadata = new HashMap<>();
      // callSid is now known at insert time — set atomically to avoid back-fill race condition
      if (callSid != null) {
        metadata.put("sip_call_id", callSid);
      }

      TenantContext.Snapshot snapshot = TenantContext.snapshot();
      try {
        TenantContext.setTenantId(tenantId);
        // Lookup customer by destination phone number for outbound calls
        UUID customerId = resolveCustomerId(to, tenantId);

        Contact contact = Contact.builder()
            .contactId(contactId)
            .tenantId(tenantId)
            .customerId(customerId)
            .agentId(agentId)
            .campaignId(campaignId) // UUID kampanii wychodzących – null dla połączeń ad-hoc
            .callbackId(callbackId) // UUID oddzwonienia – null gdy połączenie nie jest callbackiem
            .channel("PHONE")
            .direction("OUTBOUND")
            .status("QUEUED")
            .remoteAddress(to)
            .queuedAt(now)
            .startedAt(now)
            .channelMetadata(metadata)
            .createdAt(now)
            .build();

        contactRepository.insert(contact);
      } finally {
        TenantContext.clear();
        TenantContext.restore(snapshot);
      }

      log.debug("[TwilioAdapter] Rekord contact OUTBOUND utworzony: contactId={}, to={}, campaignId={}, tenant={}",
          contactId, to, campaignId, tenantId);

      return contactId;

    } catch (Exception e) {
      log.error("[TwilioAdapter] Błąd tworzenia rekordu contact OUTBOUND dla to={}, tenant={}: {}. " +
                "Agent nie będzie mógł zapisać dyspozycji.",
          to, tenantId, e.getMessage(), e);
      return null;
    }
  }

  /**
   * Creates a contact record in the database for an incoming Twilio call.
   *
   * <p>Analogous to {@link MockTelephonyAdapter#persistMockContact}.
   * The returned UUID is stored in the {@link CallSession} and sent to the frontend
   * via WebSocket as {@code contactId}, enabling {@code PATCH /api/contacts/{contactId}/disposition}.
   *
   * <p>On DB error: logs ERROR and returns {@code null} – the call is not blocked,
   * but the frontend will receive the Twilio Call SID instead of a UUID,
   * causing 422 when the agent tries to save a disposition.
   *
   * @param tenantId UUID of the tenant
   * @param from     caller number (CLI)
   * @param to       called number (Twilio phone number)
   * @param callSid  Twilio Call SID – stored in channelMetadata.sip_call_id
   * @return UUID of the newly created contact record, or null on DB error
   */
  private UUID persistContact(UUID tenantId, String from, String to, String callSid) {
    try {
      UUID contactId = UUID.randomUUID();
      Instant now = Instant.now();

      HashMap<String, Object> metadata = new HashMap<>();
      metadata.put("sip_call_id", callSid);

      // Twilio webhooks hit a public endpoint — TenantFilter does not set TenantContext.
      // ContactRepository.insert() calls assertSameTenant() which reads TenantContext from
      // ThreadLocal and throws IllegalStateException when it is empty.
      // Solution: temporarily populate TenantContext for the duration of the DB write,
      // then restore the previous state (empty snapshot) in the finally block.
      // resolveCustomerId must also run inside this block so RLS is active during the lookup.
      TenantContext.Snapshot snapshot = TenantContext.snapshot();
      try {
        TenantContext.setTenantId(tenantId);
        // Lookup customer by caller phone number to link the contact to a customer profile
        UUID customerId = resolveCustomerId(from, tenantId);

        Contact contact = Contact.builder()
            .contactId(contactId)
            .tenantId(tenantId)
            .customerId(customerId)
            .channel("PHONE")
            .direction("INBOUND")
            .status("QUEUED")
            .remoteAddress(from)
            .queuedAt(now)
            .startedAt(now)
            .channelMetadata(metadata)
            .createdAt(now)
            .build();

        contactRepository.insert(contact);
      } finally {
        TenantContext.clear();
        TenantContext.restore(snapshot);
      }

      log.debug("[TwilioAdapter] Rekord contact utworzony: contactId={}, callSid={}, tenant={}",
          contactId, callSid, tenantId);

      return contactId;

    } catch (Exception e) {
      log.error("[TwilioAdapter] Błąd tworzenia rekordu contact dla callSid={}: {}. " +
                "Frontend otrzyma callSid zamiast UUID – setDisposition zwróci 422.",
          callSid, e.getMessage(), e);
      return null;
    }
  }

  /**
   * Looks up the customer UUID by caller phone number.
   *
   * <p>Defensive – returns {@code null} on any error so that the call is never blocked
   * by a failed customer lookup.
   *
   * @param phoneNumber caller CLI
   * @param tenantId    tenant UUID
   * @return customer UUID or null if not found / lookup failed
   */
  private UUID resolveCustomerId(String phoneNumber, UUID tenantId) {
    if (phoneNumber == null || phoneNumber.isBlank()) {
      log.debug("[TwilioAdapter] Pominięto lookup klienta – pusty numer telefonu");
      return null;
    }
    try {
      return customerRepository.findByPhoneNumber(phoneNumber, tenantId)
          .map(Customer::getCustomerId)
          .orElseGet(() -> {
            log.debug("[TwilioAdapter] Klient nie znaleziony dla phone={}, tenant={} – customerId=null",
                phoneNumber, tenantId);
            return null;
          });
    } catch (Exception e) {
      log.debug("[TwilioAdapter] Błąd lookup klienta dla phone={}, tenant={}: {} – kontynuuję bez customerId",
          phoneNumber, tenantId, e.getMessage());
      return null;
    }
  }

  /**
   * Ustawia StatusCallbackEvent dla numeru przychodzącego przez bezpośrednie wywołanie
   * Twilio REST API. SDK 10.x nie udostępnia tej opcji przez {@code IncomingPhoneNumberUpdater},
   * dlatego używamy {@link java.net.http.HttpClient} z Basic Auth (accountSid:authToken).
   *
   * <p>Eventy: initiated, ringing, answered, completed, canceled.
   * {@code canceled} jest kluczowy – Twilio wysyła go gdy klient rozłączy się w trakcie
   * oczekiwania w kolejce (przed odpowiedzią agenta), co pozwala ustawić status ABANDONED.
   */
  private void setStatusCallbackEvents(String phoneNumberSid, String callbackUrl, UUID tenantId) {
    try {
      String accountSid = resolveAccountSid(tenantId);
      String authToken;
      try {
        Optional<TenantTwilioConfigDecrypted> cfg =
                tenantTwilioConfigService.getDecryptedConfig(tenantId);
        authToken = cfg.isPresent() && StringUtils.hasText(cfg.get().authToken())
                ? cfg.get().authToken()
                : twilioProperties.getAuthToken();
      } catch (Exception e) {
        log.warn("[Twilio] Błąd pobrania authToken per-tenant dla setStatusCallbackEvents, tenant={} – fallback", tenantId);
        authToken = twilioProperties.getAuthToken();
      }

      String body = "StatusCallback=" + java.net.URLEncoder.encode(callbackUrl, java.nio.charset.StandardCharsets.UTF_8)
          + "&StatusCallbackMethod=POST"
          + "&StatusCallbackEvent=initiated"
          + "&StatusCallbackEvent=ringing"
          + "&StatusCallbackEvent=answered"
          + "&StatusCallbackEvent=completed"
          + "&StatusCallbackEvent=canceled";

      String credentials = java.util.Base64.getEncoder()
          .encodeToString((accountSid + ":" + authToken).getBytes(java.nio.charset.StandardCharsets.UTF_8));

      java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
          .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + accountSid
              + "/IncomingPhoneNumbers/" + phoneNumberSid + ".json"))
          .header("Authorization", "Basic " + credentials)
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
          .build();

      java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
          .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        log.debug("[TwilioAdapter] StatusCallbackEvent skonfigurowany dla SID {}", maskSid(phoneNumberSid));
      } else {
        log.warn("[TwilioAdapter] Nieoczekiwany status HTTP {} przy ustawianiu StatusCallbackEvent dla SID {}: {}",
            response.statusCode(), maskSid(phoneNumberSid), response.body());
      }
    } catch (Exception e) {
      log.warn("[TwilioAdapter] Błąd przy ustawianiu StatusCallbackEvent dla SID {}: {}",
          maskSid(phoneNumberSid), e.getMessage());
    }
  }

  /**
   * Maskuje SID do logów (pokazuje pierwsze 8 znaków + "...").
   */
  private String maskSid(String sid) {
    if (sid == null || sid.length() <= 8)
      return "***";
    return sid.substring(0, 8) + "...";
  }
}
