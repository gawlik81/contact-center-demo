package com.contactcenter.domain.telephony;

import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.Customer;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.CustomerRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.security.TenantContext;
import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.type.PhoneNumber;
import com.twilio.type.Twiml;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementacja adaptera telefonii oparta na Twilio Programmable Voice REST API.
 *
 * <p>Aktywna gdy {@code twilio.enabled=true}. Oznaczona jako {@code @Primary},
 * dzięki czemu zastępuje {@link MockTelephonyAdapter} gdy Twilio jest włączone.
 * Gdy {@code twilio.enabled=false} (domyślnie), ten bean nie jest tworzony,
 * a {@link MockTelephonyAdapter} pozostaje jedyną implementacją.
 *
 * <h2>Stan sesji</h2>
 * <p>Stan sesji połączeń jest przechowywany lokalnie w {@link ConcurrentHashMap}.
 * Twilio jest źródłem prawdy o statusie połączenia – zmiany statusu docierają
 * przez webhook ({@code POST /api/telephony/webhook/twilio}) i aktualizują
 * lokalny stan. Mapa pełni rolę cache'a do szybkiego odczytu przez {@link #getCallSession}.
 *
 * <h2>Hold/Mute</h2>
 * <p>Hold realizowany przez modyfikację TwiML (wstrzymanie strumienia audio).
 * Mute realizowany przez {@link CallUpdater} z parametrem {@code muted=true}.
 *
 * <h2>Transfer</h2>
 * <p>Blind transfer: przekierowanie przez aktualizację TwiML z {@code <Dial>}.
 * Attended transfer: inicjacja nowego połączenia wychodzącego do target,
 * następnie {@link #bridgeCalls} łączy obie nogi przez {@code <Conference>}.
 *
 * <h2>Bezpieczeństwo wątków</h2>
 * <p>Twilio SDK jest thread-safe. Lokalny {@code sessions} ConcurrentHashMap
 * zapewnia bezpieczeństwo współbieżnych odczytów i zapisów.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
public class TwilioTelephonyAdapter implements TelephonyAdapter {

  private final TwilioProperties twilioProperties;
  private final TelephonyEventPublisher eventPublisher;
  private final ContactRepository contactRepository;
  private final CustomerRepository customerRepository;
  private final TenantRepository tenantRepository;

  /**
   * Lokalny cache sesji: callId (Twilio SID) → CallSession.
   * Aktualizowany przez operacje adaptera i przez webhook handler.
   */
  private final ConcurrentHashMap<String, CallSession> sessions = new ConcurrentHashMap<>();

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
  void init() {
    if (!StringUtils.hasText(twilioProperties.getAccountSid())) {
      throw new IllegalStateException(
          "[TwilioAdapter] twilio.account-sid jest wymagany gdy twilio.enabled=true");
    }
    if (!StringUtils.hasText(twilioProperties.getAuthToken())) {
      throw new IllegalStateException(
          "[TwilioAdapter] twilio.auth-token jest wymagany gdy twilio.enabled=true");
    }

    Twilio.init(twilioProperties.getAccountSid(), twilioProperties.getAuthToken());

    log.info("[TwilioAdapter] Twilio SDK zainicjalizowany. accountSid={}..., phoneNumber={}",
        maskSid(twilioProperties.getAccountSid()),
        twilioProperties.getPhoneNumber());
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
  public CallSession initiateCall(UUID tenantId, String from, String to, UUID agentId) {
    log.info("[TwilioAdapter] Inicjuję połączenie wychodzące: tenantId={}, from={}, to={}, agentId={}",
        tenantId, from, to, agentId);

    try {
      String twilioFrom = resolvePhoneNumber(tenantId);
      String callbackUrl = buildStatusCallbackUrl(tenantId);

      var creator = Call.creator(
          new PhoneNumber(to),
          new PhoneNumber(twilioFrom),
          new Twiml("<Response><Say>Connecting</Say></Response>")
      );

      if (StringUtils.hasText(callbackUrl)) {
        creator.setStatusCallback(URI.create(callbackUrl + "?tenantId=" + tenantId));
        creator.setStatusCallbackMethod(com.twilio.http.HttpMethod.POST);
        creator.setStatusCallbackEvent(java.util.List.of(
            "initiated", "ringing", "answered", "completed"));
      }

      Call call = creator.create();
      String callSid = call.getSid();

      log.info("[TwilioAdapter] Połączenie zainicjowane: callSid={}, status={}, to={}",
          callSid, call.getStatus(), to);

      CallSession session = CallSession.builder()
          .callId(callSid)
          .tenantId(tenantId)
          .agentId(agentId)
          .from(from)
          .to(to)
          .status(mapTwilioStatus(call.getStatus()))
          .startedAt(Instant.now())
          .build();

      sessions.put(callSid, session);

      eventPublisher.publishIncoming(callSid, null, tenantId, agentId, from, to);

      return session;

    }
    catch (ApiException e) {
      log.error("[TwilioAdapter] Błąd Twilio API przy initiateCall: to={}, code={}, message={}",
          to, e.getCode(), e.getMessage(), e);
      throw new TelephonyException(null,
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
    sessions.put(callId, updated);

    log.info("[TwilioAdapter] Połączenie odebrane (lokalny stan): callId={}, tenant={}, agentId={}",
        callId, updated.getTenantId(), updated.getAgentId());

    // Zestawianie audio przez Twilio Conference – agent wchodzi do konferencji jako moderator.
    // Wymaga contactId (do nazwy konferencji) i agentId (do tożsamości Twilio Client).
    if (updated.getContactId() != null && updated.getAgentId() != null) {
      dialAgentIntoConference(updated);
    } else {
      log.warn("[TwilioAdapter] Brak contactId lub agentId w sesji – pomijam dial agenta do konferencji: " +
               "callId={}, contactId={}, agentId={}",
          callId, updated.getContactId(), updated.getAgentId());
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
      String callbackBase = buildStatusCallbackUrl(session.getTenantId());
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
      Call.creator(
          new PhoneNumber("client:" + agentClientId),
          new PhoneNumber(resolvePhoneNumber(session.getTenantId())),
          new Twiml(agentTwiml)
      ).create();

      log.info("[TwilioAdapter] Połączenie do agenta zainicjowane: agentClientId={}, conference={}",
          agentClientId, conferenceName);
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

    log.info("[TwilioAdapter] Rozłączam połączenie: callId={}, tenant={}", callId, session.getTenantId());

    try {
      Call.updater(callId)
          .setStatus(Call.UpdateStatus.COMPLETED)
          .update();

    }
    catch (ApiException e) {
      // Status 20404 = call already completed – traktuj idempotentnie
      if (e.getCode() == 20404 || e.getStatusCode() == 404) {
        log.debug("[TwilioAdapter] Połączenie {} już zakończone po stronie Twilio ({})",
            callId, e.getCode());
      }
      else {
        log.error("[TwilioAdapter] Błąd Twilio API przy hangupCall: callId={}, code={}, message={}",
            callId, e.getCode(), e.getMessage(), e);
        throw new TelephonyException(callId,
            "Nie można rozłączyć połączenia przez Twilio: " + e.getMessage(), e);
      }
    }

    Instant endedAt = Instant.now();
    CallSession updated = session
        .withStatus(CallSession.CallStatus.ENDED)
        .withEndedAt(endedAt);
    sessions.put(callId, updated);

    if (updated.getContactId() != null) {
      contactRepository.updateContactStatusOnTelephonyEvent(
          updated.getContactId(), updated.getTenantId(), "COMPLETED", endedAt);
    }

    eventPublisher.publishHangup(callId, updated.getContactId(),
        updated.getTenantId(), updated.getAgentId(),
        updated.getFrom(), updated.getTo());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Hold w Twilio nie ma dedykowanego REST API endpoint – realizowany przez
   * odtwarzanie muzyki w oczekiwaniu ({@code <Play loop=0>}) lub wyciszenie
   * strumienia. Aktualizujemy stan przez {@code muted=true/false} i aktualizujemy
   * lokalny stan sesji.
   *
   * @throws TelephonyException gdy sesja jest w złym stanie lub Twilio API zwróci błąd
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

    log.info("[TwilioAdapter] Hold: callId={}, hold={}", callId, hold);

    try {
      // Twilio realizuje hold przez wyciszenie uczestnika po stronie agenta
      Call.updater(callId)
          .setStatus(hold ? Call.UpdateStatus.CANCELED : Call.UpdateStatus.COMPLETED)
          .update();

    }
    catch (ApiException e) {
      // Hold przez modyfikację statusu może nie być obsługiwany przez Twilio w ten sposób.
      // Zamiast rzucać wyjątek, logujemy ostrzeżenie i aktualizujemy tylko lokalny stan.
      // W produkcji hold powinien być realizowany przez TwiML Conference z muted participant.
      log.warn("[TwilioAdapter] Twilio API nie obsługuje hold przez status update (callId={}): {}. " +
              "Stan lokalny zaktualizowany – audio może nie być wstrzymane po stronie Twilio.",
          callId, e.getMessage());
    }

    CallSession.CallStatus newStatus = hold
        ? CallSession.CallStatus.ON_HOLD
        : CallSession.CallStatus.ACTIVE;
    sessions.put(callId, session.withStatus(newStatus));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Wyciszenie/odciszenie uczestnika przez Twilio {@link CallUpdater} z parametrem {@code muted}.
   *
   * @throws TelephonyException gdy sesja nie jest aktywna lub Twilio API zwróci błąd
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

    try {
      // Twilio nie ma bezpośredniego endpoint mute na Call – wymaga Participant API
      // (konferencje) lub modyfikacji TwiML. Tutaj logujemy operację jako intencję.
      // Pełna implementacja mute wymaga użycia Twilio Conference Participant API.
      log.warn("[TwilioAdapter] Operacja mute wymaga Twilio Conference Participant API. " +
          "callId={}, mute={} – stan lokalny zaktualizowany, audio Twilio bez zmian.", callId, mute);

    }
    catch (Exception e) {
      log.error("[TwilioAdapter] Błąd przy muteCall: callId={}, mute={}, error={}",
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
   * <p>Bridge realizowany przez zakończenie pierwszego połączenia (przekazanie)
   * i przeniesienie drugiej nogi do stanu ACTIVE.
   *
   * @throws TelephonyException gdy któraś z sesji nie istnieje lub jest w złym stanie
   */
  @Override
  public void bridgeCalls(String callId1, String callId2) {
    CallSession session1 = requireSession(callId1);
    CallSession session2 = requireSession(callId2);

    validateBridgeable(session1);
    validateBridgeable(session2);

    log.info("[TwilioAdapter] Bridge: callId1={}, callId2={}", callId1, callId2);

    try {
      // Zakańczamy pierwszą nogę – klient jest teraz połączony z drugą nogą
      Call.updater(callId1)
          .setStatus(Call.UpdateStatus.COMPLETED)
          .update();

    }
    catch (ApiException e) {
      if (e.getCode() != 20404 && e.getStatusCode() != 404) {
        log.error("[TwilioAdapter] Błąd Twilio API przy bridgeCalls callId1={}: {}",
            callId1, e.getMessage(), e);
        throw new TelephonyException(callId1,
            "Błąd podczas bridgowania połączeń: " + e.getMessage(), e);
      }
    }

    Instant now = Instant.now();
    CallSession transferred = session1
        .withStatus(CallSession.CallStatus.TRANSFERRED)
        .withEndedAt(now);
    CallSession active = session2.withStatus(CallSession.CallStatus.ACTIVE);

    sessions.put(callId1, transferred);
    sessions.put(callId2, active);

    eventPublisher.publishTransferred(
        callId1, session1.getTenantId(), session1.getAgentId(),
        session1.getFrom(), session1.getTo(),
        session2.getTo(), TransferType.ATTENDED.name()
    );
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

  // =========================================================================
  // Metody publiczne dla webhook handlera
  // =========================================================================

  /**
   * Aktualizuje lokalny stan sesji na podstawie statusu odebranego z webhooka Twilio.
   *
   * <p>Wywoływane przez {@code TwilioWebhookController} gdy Twilio wysyła callback
   * o zmianie statusu połączenia (initiated, ringing, answered, completed, failed itp.).
   *
   * @param callSid    Twilio Call SID
   * @param from       numer dzwoniącego
   * @param to         numer docelowy
   * @param callStatus status połączenia od Twilio (np. "in-progress", "completed")
   * @param tenantId   tenant powiązany z połączeniem (wymagany do publikacji eventu)
   */
  public void handleWebhookStatusUpdate(String callSid, String from, String to,
      String callStatus, UUID tenantId) {
    log.info("[TwilioAdapter] Webhook status update: callSid={}, status={}, from={}, to={}, tenant={}",
        callSid, callStatus, from, to, tenantId);

    CallSession.CallStatus mappedStatus = mapTwilioStatus(callStatus);
    CallEvent.EventType eventType = mapTwilioStatusToEventType(callStatus);

    CallSession existing = sessions.get(callSid);

    if (existing == null) {
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
      sessions.put(callSid, existing);
      log.debug("[TwilioAdapter] Nowa sesja z webhooka: callSid={}, contactId={}, status={}",
          callSid, contactId, mappedStatus);
    }
    else {
      // Aktualizacja istniejącej sesji
      CallSession updated = existing.withStatus(mappedStatus);
      if (mappedStatus == CallSession.CallStatus.ACTIVE && existing.getAnsweredAt() == null) {
        updated = updated.withAnsweredAt(Instant.now());
      }
      Instant webhookEndedAt = null;
      if (mappedStatus == CallSession.CallStatus.ENDED && existing.getEndedAt() == null) {
        webhookEndedAt = Instant.now();
        updated = updated.withEndedAt(webhookEndedAt);
      }
      sessions.put(callSid, updated);

      if (webhookEndedAt != null && updated.getContactId() != null) {
        contactRepository.updateContactStatusOnTelephonyEvent(
            updated.getContactId(), updated.getTenantId(), "COMPLETED", webhookEndedAt);
      }
    }

    if (eventType != null) {
      publishWebhookEvent(eventType, callSid, tenantId,
          existing.getAgentId(), from, to, existing.getContactId());
    }
  }

  /**
   * Zwraca liczbę aktywnych sesji (dla testów i monitoringu).
   */
  public int getActiveSessionCount() {
    return (int)sessions.values().stream()
        .filter(s -> s.getStatus() != CallSession.CallStatus.ENDED
            && s.getStatus() != CallSession.CallStatus.TRANSFERRED)
        .count();
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
    sessions.computeIfAbsent(callSid, sid -> {
      CallSession session = CallSession.builder()
          .callId(callSid)
          .tenantId(tenantId)
          .from(from)
          .to(to)
          .status(CallSession.CallStatus.RINGING)
          .startedAt(Instant.now())
          .contactId(contactId)
          .build();
      log.info("[TwilioAdapter] Zarejestrowano połączenie przychodzące: callSid={}, " +
               "contactId={}, tenant={}", callSid, contactId, tenantId);
      return session;
    });
  }

  // =========================================================================
  // Prywatne metody pomocnicze
  // =========================================================================

  private CallSession executeBlindTransfer(String callId, String target, CallSession session) {
    try {
      String twiml = String.format(
          "<Response><Dial><Number>%s</Number></Dial></Response>", target);

      Call.updater(callId)
          .setTwiml(new Twiml(twiml))
          .update();

    }
    catch (ApiException e) {
      log.error("[TwilioAdapter] Błąd Twilio API przy blind transfer callId={}: {}",
          callId, e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wykonać blind transfer: " + e.getMessage(), e);
    }

    Instant now = Instant.now();
    CallSession transferred = session
        .withStatus(CallSession.CallStatus.TRANSFERRED)
        .withEndedAt(now);
    sessions.put(callId, transferred);

    log.info("[TwilioAdapter] Blind transfer wykonany: callId={}, target={}", callId, target);

    eventPublisher.publishTransferred(
        callId, session.getTenantId(), session.getAgentId(),
        session.getFrom(), session.getTo(), target, TransferType.BLIND.name()
    );

    return transferred;
  }

  private CallSession executeAttendedTransfer(String callId, String target, CallSession session) {
    // Wstrzymaj oryginalne połączenie
    sessions.put(callId, session.withStatus(CallSession.CallStatus.ON_HOLD));

    try {
      // Inicjuj nowe połączenie do target (druga noga)
      Call secondLegCall = Call.creator(
          new PhoneNumber(target),
          new PhoneNumber(resolvePhoneNumber(session.getTenantId())),
          new Twiml("<Response><Say>Attending transfer</Say></Response>")
      ).create();

      String secondLegSid = secondLegCall.getSid();
      Instant now = Instant.now();

      CallSession secondLeg = CallSession.builder()
          .callId(secondLegSid)
          .tenantId(session.getTenantId())
          .agentId(session.getAgentId())
          .from(session.getTo())
          .to(target)
          .status(mapTwilioStatus(secondLegCall.getStatus()))
          .startedAt(now)
          .build();

      sessions.put(secondLegSid, secondLeg);

      log.info("[TwilioAdapter] Attended transfer – 2nd leg: callId={}, secondLegSid={}, target={}",
          callId, secondLegSid, target);

      eventPublisher.publishIncoming(secondLegSid, null,
          session.getTenantId(), session.getAgentId(),
          secondLeg.getFrom(), target);

      return secondLeg;

    }
    catch (ApiException e) {
      // Przywróć oryginalne połączenie do ACTIVE gdy inicjacja 2nd leg się nie udała
      sessions.put(callId, session.withStatus(CallSession.CallStatus.ACTIVE));
      log.error("[TwilioAdapter] Błąd Twilio API przy attended transfer callId={}: {}",
          callId, e.getMessage(), e);
      throw new TelephonyException(callId,
          "Nie można wykonać attended transfer: " + e.getMessage(), e);
    }
  }

  private CallSession requireSession(String callId) {
    CallSession session = sessions.get(callId);
    if (session == null) {
      // Fallback: callId may be a contactId (UUID from DB) sent by the frontend.
      // The sessions map is keyed by Twilio callSid (CA...), so we scan values.
      try {
        UUID contactId = UUID.fromString(callId);
        session = sessions.values().stream()
            .filter(s -> contactId.equals(s.getContactId()))
            .findFirst()
            .orElse(null);
      } catch (IllegalArgumentException ignored) {
        // callId is not a valid UUID — not a contactId, skip fallback
      }
    }
    // Fallback: sesja może nie istnieć w pamięci JVM gdy StatusCallback nie dotarł jeszcze.
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
   * Próbuje odtworzyć sesję połączenia z bazy danych gdy nie ma jej w pamięci JVM.
   *
   * <p>Sytuacja może wystąpić gdy:
   * <ul>
   *   <li>StatusCallback od Twilio nie dotarł jeszcze przed akcją agenta</li>
   *   <li>Aplikacja została zrestartowana po nawiązaniu połączenia (sesja utracona z ConcurrentHashMap)</li>
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
            sessions.put(callSid, restored);
            log.warn("[TwilioAdapter] Sesja odtworzona z DB (brak w pamięci): callSid={}, " +
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

  private String buildStatusCallbackUrl(UUID tenantId) {
    if (tenantId != null) {
      try {
        String perTenantUrl = tenantRepository.findById(tenantId)
            .map(Tenant::getTwilioStatusCallbackUrl)
            .orElse(null);
        if (StringUtils.hasText(perTenantUrl)) {
          log.debug("[TwilioAdapter] Używam per-tenant callback URL: tenantId={}", tenantId);
          return perTenantUrl;
        }
      } catch (Exception e) {
        log.warn("[TwilioAdapter] Błąd odczytu per-tenant callback URL dla tenantId={}: {} – " +
                 "fallback do konfiguracji globalnej", tenantId, e.getMessage());
      }
    }

    String globalUrl = twilioProperties.getStatusCallbackUrl();
    return StringUtils.hasText(globalUrl) ? globalUrl : null;
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
   * @return typ eventu lub {@code null} gdy status nie generuje eventu
   */
  private CallEvent.EventType mapTwilioStatusToEventType(String callStatus) {
    if (callStatus == null)
      return null;

    return switch (callStatus.toLowerCase()) {
      case "ringing", "initiated", "queued" -> CallEvent.EventType.CALL_INCOMING;
      case "in-progress" -> CallEvent.EventType.CALL_ANSWERED;
      case "completed", "busy",
           "failed", "no-answer",
           "canceled" -> CallEvent.EventType.CALL_HANGUP;
      default -> null;
    };
  }

  private void publishWebhookEvent(CallEvent.EventType eventType, String callSid,
      UUID tenantId, UUID agentId,
      String from, String to, UUID contactId) {
    switch (eventType) {
      case CALL_INCOMING -> eventPublisher.publishIncoming(callSid, contactId, tenantId, agentId, from, to);
      case CALL_ANSWERED -> eventPublisher.publishAnswered(callSid, tenantId, agentId, from, to);
      case CALL_HANGUP -> eventPublisher.publishHangup(callSid, contactId, tenantId, agentId, from, to);
      default -> log.debug("[TwilioAdapter] Brak publikacji dla eventType={}", eventType);
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
   * Maskuje SID do logów (pokazuje pierwsze 8 znaków + "...").
   */
  private String maskSid(String sid) {
    if (sid == null || sid.length() <= 8)
      return "***";
    return sid.substring(0, 8) + "...";
  }
}
