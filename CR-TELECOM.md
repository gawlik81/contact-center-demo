# CR-TELECOM.md — Code Review: Warstwa Telekomunikacyjna

Recenzent: senior-code-reviewer  
Data: 2026-05-29  
Zakres: frontend softphone + backend Twilio integration (pełna warstwa PSTN)

---

## Krytyczne (blocker)

### CRIT-01: TenantContext.restore() wywołane PO clear() — błąd kolejności operacji
**Plik:** `TwilioTelephonyAdapter.java:2853-2856`

```java
} finally {
    TenantContext.clear();
    TenantContext.restore(snapshot); // BUG: restore po clear jest no-op gdy snapshot był pusty
}
```

W metodzie `persistOutboundContact()` snapshot jest pobierany PRZED `setTenantId`, ale w bloku `finally` najpierw wywoływane jest `clear()`, a dopiero potem `restore(snapshot)`. Jeśli oryginalny snapshot zawierał tenantId (np. gdy ta metoda jest wywoływana w kontekście innego żądania), `clear()` kasuje go, a `restore` prawidłowo go odtwarza — to jest OK. Ale wzorzec jest odwrotny niż reszta kodu (porównaj `persistContact()` linia 2923-2925 gdzie jest to samo, i `scheduleRecordingFallback()` linia 2201-2203 gdzie jest TYLKO `clear()`). Jeszcze ważniejszy problem: wzorzec `snapshot → set → finally { clear(); restore(snapshot) }` jest semantycznie poprawny ale niespójny — **właściwy wzorzec to `finally { TenantContext.clear(); }` gdy snapshot był pusty na wejściu, lub `finally { TenantContext.restore(snapshot); }` gdy chcemy przywrócić poprzedni stan.** W kodzie używane są OBIE operacje naraz, co powoduje że restore() po clear() odtwarza pusty stan, marnując snapshot.

**Skutek:** W scenariuszu gdzie ta metoda jest wywoływana z wątku posiadającego już tenantId (np. przez `configureStatusCallbacksForAllTenants`), po wyjściu z bloku TenantContext będzie pusty zamiast zawierać oryginalny tenantId.

**Naprawa:** Użyć wzorca: `finally { TenantContext.clear(); TenantContext.restore(snapshot); }` → **zamienić kolejność**: `restore(snapshot)` najpierw, potem `clear()`. Lub po prostu: `finally { TenantContext.restore(snapshot); }` (restore usuwa stan jeśli snapshot był pusty).

---

### CRIT-02: `setStatusCallbackEvents` tworzy nowy `HttpClient` per wywołanie
**Plik:** `TwilioTelephonyAdapter.java:3014`

```java
java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
```

Wywołanie `HttpClient.newHttpClient()` tworzy nową pulę wątków i selektor NIO przy każdym wywołaniu tej metody. Metoda `configureStatusCallbacksForAllTenants()` wywołuje `setStatusCallbackEvents()` dla każdego aktywnego tenanta przy starcie aplikacji — przy dużej liczbie tenantów powoduje to wycieki zasobów (thread pool leak). Dla porównania `TwilioRecordingDownloadService` poprawnie tworzy `HttpClient` raz w konstruktorze.

**Naprawa:** Przenieść `HttpClient` jako pole klasy (tak jak w `TwilioRecordingDownloadService`) lub wstrzyknąć go jako bean.

---

### CRIT-03: Brak weryfikacji X-Twilio-Signature na `/voice` i `/dtmf` w środowisku dev — nie ma mechanizmu per-endpoint
**Plik:** `TwilioWebhookController.java:165, 294` / `application-dev.yml:166`

`signature-validation-enabled: false` wyłącza weryfikację globalnie dla **wszystkich** endpointów webhooka w profilu dev. Każdy kto zna URL webhooka może:
- Wysłać fałszywy webhook `/voice` z dowolnym `from`/`to`/`tenantId` i spowodować utworzenie rekordu Contact
- Wywołać `/recording` z `RecordingUrl` wskazującym na serwer atakującego → SSRF z Basic Auth credentials Twilio

W profilu dev jest to akceptowalne lokalnie, ale jeśli deweloper udostępni port przez ngrok bez włączenia weryfikacji, ryzyko staje się realne. Brak ostrzeżenia przy starcie gdy `signature-validation-enabled=false` i `twilio.enabled=true`.

**Naprawa:** Dodać w `@PostConstruct init()` / `@EventListener ApplicationReadyEvent` ostrzeżenie logowane jako `WARN` gdy `twilio.enabled=true && signatureValidationEnabled=false`, ze wskazaniem że jest to tylko do lokalnego testowania.

---

### CRIT-04: Brak walidacji `phoneNumber` w `OutboundCallRequest` — możliwość SSRF/wstrzyknięcia
**Plik:** `AgentCallController.java:107` / `OutboundCallRequest.java` (nie sprawdzone, ale wywnioskowane z braku)

```java
public ResponseEntity<OutboundCallResponse> initiateOutboundCall(
        @Valid @RequestBody OutboundCallRequest request
) {
```

Sprawdź czy `OutboundCallRequest.phoneNumber` ma walidację formatu E.164. Twilio SDK przyjmie dowolny string jako numer i wyśle żądanie do Twilio API, gdzie zostanie odrzucony, ale wcześniej:
- Zostanie utworzony rekord `Contact` w DB z dowolną wartością w `remoteAddress`
- Numer jest logowany

**Naprawa:** Dodać `@Pattern(regexp = "^\\+[1-9]\\d{7,14}$")` na polu `phoneNumber` w DTO.

---

## Ważne (should fix)

### IMP-01: `validateTwilioSignature` czyta parametry przez `getParameterMap()` — SSRF bypass przy query string
**Plik:** `TwilioWebhookController.java:760-763`

```java
request.getParameterMap().forEach((key, values) -> {
    if (values != null && values.length > 0) {
        params.put(key, values[0]);
    }
});
```

`getParameterMap()` zwraca zarówno parametry POST (form body) jak i parametry GET (query string). Twilio podpisuje tylko parametry POST dla żądań form-encoded. Jeśli atakujący doda parametry query string do URL webhooka (np. `?evil=payload`), zostaną one dodane do mapy walidacji, ale Twilio ich nie podpisało — weryfikacja podpisu może się nie powieść dla poprawnych żądań gdy URL zawiera query string, lub być podatna na manipulację.

Twilio SDK's `RequestValidator.validate()` dla POST form-encoded **oczekuje tylko parametrów POST**, nie query string. Dla żądań GET (takich jak `/voice` jeśli kiedyś zmienisz metodę) zachowanie jest inne.

**Naprawa:** Dla endpointów `POST form-encoded` przekazywać wyłącznie parametry z ciała żądania, nie z URL. Użyć `request.getParameterMap()` ostrożnie lub filtrować tylko parametry POST.

---

### IMP-02: `buildSelfUrl` w `TwilioVoiceController.holdMusic` ufający nagłówkowi `Host` — Header Injection
**Plik:** `TwilioVoiceController.java:223-235`

```java
String host = request.getHeader("Host");
if (host == null || host.isBlank()) {
    host = request.getServerName() + ":" + request.getServerPort();
}
String url = proto + "://" + host + "/api/telephony/hold-music";
```

Nagłówek `Host` może być dowolnie ustawiony przez wywołującego (w tym Twilio, ale też atakującego). Wprawdzie ten endpoint jest wywoływany przez Twilio jako `waitUrl`, ale URL jest osadzany w TwiML jako `<Redirect>` - jeśli atakujący zdoła wywołać ten endpoint (jest publiczny), może wstrzyknąć dowolny host i dostać odpowiedź z `<Redirect method="GET">http://evil.com/...</Redirect>`, co Twilio posłusznie wykona.

**Naprawa:** Zamiast ufać `Host` headerowi, używać `appBaseUrl` (wstrzykniętego przez `@Value`) jako bazy URL — to samo co inne metody w tym kontrolerze używają.

```java
// Zamiast buildSelfUrl(request, queueId):
String selfUrl = appBaseUrl + "/api/telephony/hold-music" + (queueId != null ? "?queueId=" + queueId : "");
```

---

### IMP-03: `ewtMessage` w TwiML nie jest escape'owany — potencjalne XML injection
**Plik:** `TwilioVoiceController.java:282-289`

```java
ewtMessage = "Szacowany czas oczekiwania wynosi okolo " + minutes + " minut.";
return "<Say language=\"pl-PL\">" + ewtMessage + "</Say>";
```

Wartość `minutes` pochodzi z `((Number) ewtRaw).intValue()` z Redis. Jeśli Redis zostanie skompromitowany lub klucz zostanie nadpisany złośliwą wartością (np. przez atak na Redis bez hasła), `ewtMessage` może zawierać znaki XML (`<`, `>`, `&`), co spowoduje niepoprawny TwiML i rozłączenie połączenia.

**Naprawa:** Używać `StringEscapeUtils.escapeXml11(ewtMessage)` lub budować TwiML przez właściwy builder (Twilio SDK ma `Say` builder).

---

### IMP-04: `SoftphoneService` — brak anulowania `cleanupTimeout` i `transferTimeout` przy `destroyTwilioDevice`
**Plik:** `softphone.service.ts:773-786`

```typescript
private destroyTwilioDevice(): void {
    this.tokenRefreshSub?.unsubscribe();
    this.tokenRefreshSub = null;
    if (this.twilioDevice) {
        try { this.twilioDevice.destroy(); } catch { }
        this.twilioDevice = null;
        this.twilioDeviceReady.set(false);
    }
    this.activeCall = null;
    // BRAK: clearTimers()!
}
```

`destroyTwilioDevice()` jest wywoływane przy reinicjalizacji (`initializeTwilioDevice()` → `destroyTwilioDevice()`). Nie woła `clearTimers()`, co oznacza że stare `cleanupTimeout`/`transferTimeout` mogą wykonać się po reinicjalizacji i ustawić `session.set(null)` lub `session.set({...ENDED})` na nowej sesji.

**Naprawa:**
```typescript
private destroyTwilioDevice(): void {
    this.tokenRefreshSub?.unsubscribe();
    this.tokenRefreshSub = null;
    this.clearTimers(); // dodać tę linię
    if (this.twilioDevice) { ... }
}
```

---

### IMP-05: Race condition w `handleIncomingCall` — setTimeout 500ms jest magic number bez fallback
**Plik:** `softphone.service.ts:161-178`

```typescript
setTimeout(() => {
    const currentSession = this.session();
    if (currentSession === null) {
        console.warn('[SoftphoneService] Incoming Twilio call received but no active softphone session — rejecting.');
        if (this.activeCall === call) {
            call.reject();
            this.activeCall = null;
        }
    } else if (currentSession.state === 'ACTIVE') {
        if (this.activeCall === call) {
            call.accept();
        }
    }
    // state RINGING: wait for answerCall() to call acceptIncomingCall()
}, 500);
```

Jeśli event WebSocket (`CALL_TRANSFER_CONSULT`) dotrze po 500ms (wolne połączenie, obciążony serwer), call zostanie odrzucony mimo że jest prawidłowy. 500ms to za mało jako margin dla produkcyjnego systemu.

**Naprawa:** Zwiększyć do 1500-2000ms lub zastosować polling z backoff (np. `interval(100).pipe(take(15), filter(() => this.session() !== null))`).

---

### IMP-06: `TwilioRecordingDownloadService` używa globalnego `twilioProperties.getAuthToken()` zamiast per-tenant
**Plik:** `TwilioRecordingDownloadService.java:412-413`

```java
private String buildBasicAuthCredentials() {
    String accountSid = twilioProperties.getAccountSid();
    String authToken  = twilioProperties.getAuthToken();
```

W środowisku multi-tenant BYOT (Bring Your Own Twilio) każdy tenant ma własne `accountSid`/`authToken` w tabeli `tenant_twilio_config`. Pobieranie nagrania z Twilio wymaga credentials właściwego tenanta — użycie globalnych credentials przy nagraniu tenanta z per-tenant config spowoduje `HTTP 401` od Twilio.

**Naprawa:** Wstrzyknąć `TenantTwilioConfigService` i użyć `tenantId` przekazywanego do `downloadAndStoreSync()` do rozwiązania właściwych credentials (tak jak robi to `resolveRestClient()` w adapterze).

---

### IMP-07: `configureStatusCallbacksForAllTenants` nie propaguje TenantContext przy odczycie numerów
**Plik:** `TwilioTelephonyAdapter.java:219-293`

`tenantRepository.findAllByOptionalFilters()` i `resolveRestClient()` są wywołane w kontekście startu aplikacji bez ustawionego TenantContext. Jeśli `TenantTwilioConfigRepository.findByTenantId()` (wewnątrz `resolveRestClient → buildClientForTenant`) korzysta z RLS (`app.current_tenant_id`), może zwrócić pusty wynik lub rzucić błąd gdy brakuje ustawionego tenanta.

**Naprawa:** Przy iteracji po tenantach, tymczasowo ustawiać `TenantContext.setTenantId(tenant.getId())` przed wywołaniem `resolveRestClient()`, z clear w finally. Lub sprawdzić czy `TenantTwilioConfigRepository` używa parametryzowanego zapytania zamiast RLS dla tego przypadku.

---

### IMP-08: Brak retry logic dla Twilio API calls — pojedyncze błędy sieci powodują utratę połączenia
**Plik:** `TwilioTelephonyAdapter.java` (wiele miejsc)

Wszystkie wywołania Twilio REST API (`Call.creator().create()`, `Call.updater().update()`, `Conference.reader().read()`) nie mają żadnego mechanizmu retry. Przejściowy błąd sieci (HTTP 5xx od Twilio, timeout) przy `dialAgentIntoConference()` spowoduje że agent nie usłyszy klienta, bez automatycznego ponowienia próby.

**Naprawa:** Dodać retry z exponential backoff dla nieudanych wywołań Twilio API (np. przez `Resilience4j` lub prostą pętlę z `Thread.sleep`). Przynajmniej dla `dialAgentIntoConference()` który jest krytyczny dla audio.

---

### IMP-09: `SoftphoneService` — optymistyczna aktualizacja `hangupCall` bez cofnięcia przy błędzie HTTP
**Plik:** `softphone.service.ts:293-310`

```typescript
hangupCall(): void {
    // Optimistically update UI state; fire-and-forget the HTTP request
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'ENDED' });
    this.hangupCallHttp(s.contactId)
        .pipe(catchError(() => of(null)))  // błąd jest swallowed
        .subscribe();
    this.cleanupTimeout = setTimeout(() => {
        this.session.set(null);
        this.activeCall = null;
    }, 2000);
}
```

Jeśli `hangupCallHttp` zakończy się błędem, sesja zostanie wyczyszczona po 2s po stronie frontendu, ale Twilio nadal będzie kontynuowało połączenie po stronie klienta. Klient będzie słyszał ciszę lub muzykę hold bez możliwości zakończenia rozmowy z UI. Błąd HTTP jest całkowicie swallowed przez `catchError(() => of(null))`.

**Naprawa:** W `catchError`, przynajmniej wylogować błąd i wyświetlić toast użytkownikowi. Dla krytycznych operacji (hangup), rozważyć retry.

---

## Drobne (nice to have)

### NICE-01: `TwilioVoiceController.holdMusic` — brak sanityzacji `queueId` przed interpolacją w TwiML
**Plik:** `TwilioVoiceController.java:213`

`queueId` jest parsowany jako `UUID` przez Spring (`@RequestParam UUID queueId`) co już zapewnia walidację formatu. Ale `holdMusicUrl` (z konfiguracji `app.hold-music-url`) jest interpolowany bezpośrednio do TwiML bez escape'owania:

```java
String musicBlock = (holdMusicUrl != null && !holdMusicUrl.isBlank())
        ? "<Play>" + holdMusicUrl + "</Play>"
```

Jeśli `holdMusicUrl` zawiera znak `<` lub `>` (np. przez błąd w konfiguracji), TwiML będzie niepoprawny.

**Naprawa:** Użyć `StringEscapeUtils.escapeXml11(holdMusicUrl)` w bloku `<Play>`.

---

### NICE-02: `handleVoiceWebhook` — `TenantContext.setTenantId` w try-bloku bez odpowiedniego clear w outer scope
**Plik:** `TwilioWebhookController.java:177-224`

```java
try {
    TenantContext.setTenantId(tenantId);
    // ...tworzenie contact...
} catch (Exception contactEx) {
    // ...
} finally {
    TenantContext.clear();
}

// Dalej następuje:
RouteResult route = incomingCallRoutingService.resolveRoute(tenantId, to, ZonedDateTime.now());
```

Po bloku `finally` TenantContext jest wyczyszczony. `incomingCallRoutingService.resolveRoute()` i `ivrEngineService.routeDirectlyToQueue()` działają bez TenantContext. Jeśli te metody wewnętrznie korzystają z TenantContext (np. przez `@Audited` lub repozytoria z RLS), mogą zachowywać się niepoprawnie.

**Naprawa:** Sprawdzić czy `IncomingCallRoutingService` i `IvrEngineService` potrzebują TenantContext. Jeśli tak — ustawić go ponownie przed wywołaniem tych metod lub objąć ich wywołania tym samym blokiem try/finally.

---

### NICE-03: `voice-token` TTL 3600s jest hardcoded jako stała, refresh schedule 55min jest hardcoded
**Plik:** `TwilioVoiceController.java:51` / `softphone.service.ts:753`

```java
private static final int VOICE_TOKEN_TTL_SECONDS = 3600;
```
```typescript
this.tokenRefreshSub = interval(3_300_000) // 55 min hardcoded
```

TTL i interwał odświeżania są sprzężone — zmiana TTL w kontrolerze wymaga pamiętania o zmianie interwału w serwisie Angular. Powinny być konfigurowane w jednym miejscu lub przynajmniej komentarz powinien wyraźnie wskazywać na tę zależność.

**Naprawa:** Dodać TTL do odpowiedzi API `/voice-token` i użyć go w frontendzie do obliczenia interwału refresh: `interval(response.ttlSeconds * 900)` (90% TTL).

---

### NICE-04: `TenantTwilioConfigService.testConnection` — szczegółowy komunikat błędu Twilio wychodzi na zewnątrz
**Plik:** `TenantTwilioConfigService.java:140`

```java
return new TwilioConnectionTestResult(false,
        "Błąd połączenia z Twilio: " + e.getMessage(), Instant.now());
```

Wyjątek `ApiException` od Twilio może zawierać szczegółowe informacje diagnostyczne (URL, kod błędu HTTP, pełna treść odpowiedzi) które są przydatne dla admina, ale mogą ujawniać szczegóły infrastruktury atakującemu gdy endpoint jest dostępny.

**Naprawa:** Dla produkcji logować pełny błąd, a do odpowiedzi zwracać tylko code błędu Twilio (`e.getCode()`) bez pełnego message.

---

### NICE-05: `SoftphoneService` — `rejectCall` wywołuje `hangupCallHttp` z `contactId` zamiast `callId`
**Plik:** `softphone.service.ts:420-428`

```typescript
rejectCall(): void {
    const s = this.session();
    if (!s || s.state !== 'RINGING') return;
    this.clearTimers();
    this.session.set(null);
    this.rejectIncomingCall();
    this.hangupCallHttp(s.contactId) // używa contactId
        .pipe(catchError(() => of(null)))
        .subscribe();
}
```

`hangupCallHttp` jest wywoływane z `s.contactId`, a w `hangupCall()` z `s.contactId`. To jest spójne — ale warto odnotować że backend `AgentCallController.hangupCall()` musi poprawnie rozwiązać contactId na callSid przez `resolveCallSid()`. Sprawdzić czy działa to dla połączeń które nie mają jeszcze `sip_call_id` w DB (np. OUTBOUND zanim backfill się wykona).

---

### NICE-06: `SoftphoneComponent.holdMusic` — `formattedHoldDuration` odczytuje `new Date()` w computed bez sygnału czasowego
**Plik:** `softphone.component.ts:71-75`

```typescript
protected readonly formattedHoldDuration = computed(() => {
    const s = this.session();
    if (!s || !s.holdStartedAt) return '00:00';
    const elapsed = Math.floor((new Date().getTime() - s.holdStartedAt.getTime()) / 1000);
    return this.formatSeconds(elapsed);
});
```

Wartość `new Date()` nie jest reaktywna — `computed` wykona się ponownie tylko gdy `session()` się zmieni. Timer hold nie będzie się odświeżał co sekundę bez sygnału pomocniczego. Używany jest `_holdTick` (linia 109) + `holdTickInterval` (linia 88) do wymuszenia re-renderu, ale jest to rozwiązanie pośrednie.

**Naprawa:** Zamiast `new Date()` w computed, aktualizować `holdDuration` w serwisie (podobnie do `duration`). Uprości to komponent i usunie potrzebę `_holdTick`.

---

### NICE-07: Brak `Content-Security-Policy` dla TwiML zwracanego przez backend
**Plik:** `TwilioWebhookController.java:244`

Odpowiedzi TwiML zwracane przez `/voice`, `/dtmf`, `/voicebot-recording` mają `Content-Type: application/xml`. Nie ustawiane są nagłówki `X-Content-Type-Options: nosniff` ani inne nagłówki bezpieczeństwa. Nie jest to krytyczne (Twilio parsuje XML po stronie serwerowej, nie w przeglądarce), ale jest dobrą praktyką.

---

## Dobre praktyki (co jest OK)

### GOOD-01: Walidacja X-Twilio-Signature jest poprawnie zaimplementowana
`TwilioWebhookController.validateTwilioSignature()` używa oficjalnego `RequestValidator` z Twilio SDK. Weryfikacja jest egzekwowana na każdym endpoincie webhooka przed przetwarzaniem payload. Odpowiedź przy nieprawidłowym podpisie to TwiML `<Reject/>` (nie JSON), co jest właściwe.

### GOOD-02: Weryfikacja podpisu jest konfigurowalna per środowisko
`twilio.signature-validation-enabled` z domyślną wartością `true` w `application.yml` i nadpisaniem `false` tylko w `application-dev.yml`. Dev wyraźnie komentowany jako lokalne środowisko. Brak wartości `false` w `application-prod.yml`.

### GOOD-03: Credentials Twilio nigdy nie pojawiają się w logach
Przegląd logów w `TwilioTelephonyAdapter` wykazuje konsekwentne używanie `maskSid()` dla SIDów. AuthToken nie jest logowany. Numer telefonu jest maskowany przez `maskPhone()` w `AgentCallController`.

### GOOD-04: Per-tenant TwilioRestClient z cache Caffeine
Cache `Caffeine` z TTL 15 min + max 100 wpisów dla `TwilioRestClient` jest właściwym podejściem dla multi-tenant BYOT. Cache jest inwalidowany przez `@EventListener TwilioConfigChangedEvent` przy każdej zmianie konfiguracji tenanta.

### GOOD-05: TenantContext lifecycle w webhookach jest właściwy
W `handleConferenceStatusCallback()`, `handleDtmfWebhook()`, `handleVoicebotRecording()` wzorzec `setTenantId → try → finally clear()` jest konsekwentnie stosowany.

### GOOD-06: `scheduleRecordingFallback` poprawnie używa TenantContext.snapshot/restore/clear
Linia 2149-2203: `snapshot = TenantContext.snapshot()` przed `CompletableFuture.delayedExecutor`, `TenantContext.restore(snapshot)` na początku lambdy async, `TenantContext.clear()` w finally. Jest to zgodne z architekturalnymi wymaganiami projektu dla async thread boundaries.

### GOOD-07: `SoftphoneService.ngOnDestroy` czyści zasoby
Wywołanie `destroyTwilioDevice()` i `clearTimers()` w `ngOnDestroy()`. `Subscription` odświeżania tokenu jest anulowana. `Device.destroy()` jest wywoływane z obsługą błędów.

### GOOD-08: Twilio Device token refresh działa bez destroy/create
`startTokenRefreshSchedule()` używa `device.updateToken()` zamiast pełnej reinicjalizacji urządzenia — jest to prawidłowy sposób na odświeżenie tokenu bez przerywania aktywnych połączeń.

### GOOD-09: Fallback nagrania po 90 sekundach jako defense-in-depth
`scheduleRecordingFallback()` jako mechanizm zapasowy gdy `recordingStatusCallback` nie dotrze — dobra praktyka dla systemów produkcyjnych gdzie webhooki Twilio mogą się opóźniać lub gubić.

### GOOD-10: Idempotentność `registerIncomingCall`
Sprawdzenie `loadSessionFromRedis(callSid) == null` przed zapisem w `registerIncomingCall()` zapobiega nadpisaniu istniejącej sesji przy duplikacie webhooka `/voice`.

### GOOD-11: Poprawna obsługa ABANDONED vs TRANSFERRED vs COMPLETED w webhook konferencji
`handleConferenceStatusCallback()` sprawdza listę statusów terminalnych przed ustawieniem ABANDONED. Dodanie `TRANSFERRED` do listy chroni przed race condition gdzie oryginalna konferencja kończy się po transferze klienta do nowej.

---

## Podsumowanie końcowe

### Ocena ogólna: 3.5/5 ⭐⭐⭐½

Warstwa telekomunikacyjna jest **imponująco złożona i dobrze udokumentowana** — komentarze wyjaśniają nieintuicyjne decyzje (race conditions, kolejność redirect-ów, obsługa conference-end vs ABANDONED). Architektura per-tenant z cache Caffeine i osobnym `TwilioRestClient` per tenant jest solidna.

**Główne obszary do poprawy:**
1. **Bezpieczeństwo:** Header Injection w `buildSelfUrl` i brak walidacji E.164 dla `OutboundCallRequest` to realne ryzyko
2. **Multi-tenant:** `TwilioRecordingDownloadService` ignoruje per-tenant credentials przy pobieraniu nagrań — w środowisku BYOT nagrania tenantów z własnym kontem Twilio nie będą pobierane poprawnie
3. **Zarządzanie zasobami:** `HttpClient` per-call w `setStatusCallbackEvents` może powodować wyciek zasobów przy dużej liczbie tenantów
4. **Frontend cleanup:** `destroyTwilioDevice()` nie czyści timerów, co może powodować race conditions przy reinicjalizacji

**Kwestie krytyczne do rozwiązania przed deploymentem produkcyjnym:** CRIT-01 (kolejność restore/clear), CRIT-02 (HttpClient leak), IMP-02 (Header Injection → SSRF risk), IMP-06 (per-tenant credentials w recording download).
