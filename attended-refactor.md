# Plan refactoru: Attended Transfer → Wariant A (osobna konferencja per kontakt)

## Cel

Ujednolicić zachowanie wszystkich typów transferów: każdy transfer (attended, blind do agenta,
blind do kolejki) tworzy nową konferencję Twilio → każdy kontakt dostaje własne nagranie.

Aktualnie attended transfer zostawia klienta i Agent2 w tej samej konferencji `contact-A`
co Agent1. Po refactorze `bridgeCalls()` przekierowuje klienta i Agent2 do **nowej**
konferencji `contact-{newContactId}`, analogicznie do blind transfer.

---

## Zakres zmian

### Zmiana 1: `CallSession.java` – nowe pole `customerCallSid`

Dodaj pole po `agentCallSid`:

```java
/**
 * Twilio Call SID oryginalnego połączenia klienta (CA...).
 * Propagowany przez łańcuch attended transfer, żeby bridgeCalls()
 * mógł przekierować klienta do nowej konferencji.
 * Null dla starszych sesji Redis (JsonIgnoreProperties obsłuży).
 */
private final String customerCallSid;
```

Klasa jest `@Builder @With @Jacksonized` → brak dodatkowego kodu.
Stare sesje Redis odczytają `customerCallSid = null` (bezpieczny fallback).

---

### Zmiana 2: Ustaw `customerCallSid` przy rejestracji połączenia

**`TwilioTelephonyAdapter.registerIncomingCall()`** – w builderze sesji:
```java
.customerCallSid(callSid)   // callSid = Twilio SID klienta
```

**`TwilioTelephonyAdapter.initiateCall()`** – po uzyskaniu SID klienta od Twilio:
```java
.customerCallSid(clientCallSid)   // SID zwrócony przez Call.creator().create()
```

---

### Zmiana 3: Propaguj `customerCallSid` przez łańcuch

**`executeAttendedTransfer()` – builder sesji `secondLeg`:**
```java
.customerCallSid(session.getCustomerCallSid())   // kopiuj z sesji Agent1
```

**`updateSessionContact()`** – pole jest zachowywane automatycznie przez `@With`
(nie wywołujemy `.withCustomerCallSid()`), więc nie wymaga zmiany.

---

### Zmiana 4: Refactor `bridgeCalls()` – redirect do nowej konferencji

**Plik:** `TwilioTelephonyAdapter.java`, metoda `bridgeCalls(String callId1, String callId2, UUID newContactId)`

Zmiana sygnatury: dodaj parametr `UUID newContactId` (generowany w `ContactService` przed wywołaniem).

Nowa sekwencja kroków:

```
1. Wyznacz customerCallSid = session1.getCustomerCallSid()
   → jeśli null: log WARN + throw TelephonyException (safe fail)
   → fallback opcjonalny: gdy session1.direction=="INBOUND" użyj session1.getCallId()

2. Wyznacz nową konferencję:
   newConferenceName = "contact-" + newContactId

3. Zbuduj TwiML nowej konferencji (wyodrębnij do buildConferenceTwiml()):
   <Response><Dial>
     <Conference startConferenceOnEnter="true" endConferenceOnExit="true"
                 record="record-from-start" recordingStatusCallback="..."
                 waitUrl="...hold-music...">
       contact-{newContactId}
     </Conference>
   </Dial></Response>

4. Redirect klienta do nowej konferencji (krytyczny):
   Call.updater(customerCallSid).setTwiml(newConferenceTwiml).update(client)
   → ApiException → throw TelephonyException (rozmowa może być stracona)

5. Redirect Agent2 do nowej konferencji (agent2CallSid = session2.getCallId()):
   Call.updater(agent2CallSid).setTwiml(newConferenceTwiml).update(client)
   → ApiException → log WARN, kontynuuj (klient już przekierowany)

6. Zakończ nogę Agent1 (agentCallSid1 = session1.getAgentCallSid()):
   Call.updater(agentCallSid1).setStatus(COMPLETED).update(client)
   → 404 ApiException → ignoruj (noga już zakończona)
   Uwaga: NIE ustawiamy już endConferenceOnExit=false – stara konferencja
   zakończy się gdy klient ją opuści (redirect), co wyzwoli recordingStatusCallback.

7. Aktualizuj sesje Redis:
   session1 → withStatus(TRANSFERRED).withEndedAt(now)  [Agent1 zakończony]
   session2 → withStatus(ACTIVE)
              .withContactId(newContactId)
              .withConferenceName(newConferenceName)
              .withCustomerCallSid(customerCallSid)       [zachowaj dla kolejnego bridgeCalls]
   saveSession(transferred)
   saveSession(active)
   Stwórz indeks Redis: CONTACT_SESSION_INDEX_PREFIX + newContactId → session2.getCallId()
```

**Uwaga o nagraniach:**
Gdy klient opuści starą konferencję (redirect), Twilio wysyła `recordingStatusCallback`
dla `contact-{originalContactId}`. `resolveContactIdFromConference()` parsuje
`FriendlyName` → nagranie R-A trafia do oryginalnego kontaktu. Działa bez zmian.

Nowa konferencja `contact-{newContactId}` z `record-from-start` → nagranie R-B
trafia do nowego kontaktu przez ten sam mechanizm. Działa bez zmian.

---

### Zmiana 5: `TelephonyAdapter.java` – nowa sygnatura `bridgeCalls()`

```java
void bridgeCalls(String callId1, String callId2, UUID newContactId);
```

Aktualizuj: `TwilioTelephonyAdapter`, `MockTelephonyAdapter`.

---

### Zmiana 6: `ContactService.java` – kolejność operacji w `bridgeCalls()`

Nowa kolejność:

```java
// Generuj newContactId PRZED wywołaniem adaptera
UUID newContactId = UUID.randomUUID();

// Utwórz kontakt dla Agent2 (z zewnętrznym UUID)
Contact newContact = createTransferContact(contact, agent2Id, secondCallId, tenantId, newContactId);

// Wywołaj adapter (redirect + terminacja + aktualizacja sesji Redis)
telephonyAdapter.bridgeCalls(callId, secondCallId, newContactId);

// Otwórz etap AGENT na nowym kontakcie
contactEventService.openAgent(newContactId, tenantId, agent2Id, null);

// Usuń wywołanie updateSessionContact() – adapter sam aktualizuje sesję w bridgeCalls()

// Powiadom Agent2 przez WS
eventPublisher.publishBridgeComplete(secondCallId, newContactId, tenantId, agent2Id, ...);
```

Zmień sygnaturę `createTransferContact()`:
```java
private Contact createTransferContact(Contact original, UUID agentId,
                                       String secondCallSid, UUID tenantId, UUID newContactId)
```
Usuń `UUID newContactId = UUID.randomUUID();` z ciała metody.

---

### Zmiana 7: Usuń workaroundy (z poprzednich fixów)

**`TwilioRecordingDownloadService.java`:**
- Usuń metodę `propagateRecordingToTransferChain()`
- Usuń wywołanie `propagateRecordingToTransferChain(resolvedContactId, tenantId)` z `downloadAsync()`
- Usuń importy `LinkedList`, `Queue` jeśli nieużywane

**`ContactService.java`:**
- Usuń `metadata.put("transfer_type", "ATTENDED")` z `createTransferContact()`

**`TwilioTelephonyAdapter.java`:**
- Usuń kod preserve `conferenceName` w `updateSessionContact()` (sesja i tak jest nadpisywana przez `bridgeCalls()`)
- `updateSessionContact()` może zostać jako metoda do tworzenia indeksu Redis, lub usunąć całkowicie i przenieść logikę indeksu do `bridgeCalls()`

---

## Race conditions i ryzyka

| Ryzyko | Opis | Mitygacja |
|--------|------|-----------|
| Redirect klienta nie powiedzie się | Klient w starej konferencji, Agent1 zakończony, Agent2 w nowej | Throw `TelephonyException` przy redirect klienta – błąd jest widoczny dla agenta |
| Brief audio gap | Klient słyszy ciszę ok. 1-2s podczas redirectu | Akceptowalne; w blind transfer identyczne zachowanie |
| Agent2 wchodzi do nowej konferencji przed klientem | Agent2 słyszy muzykę hold zanim klient dołączy | Dodać `waitUrl` z hold music do TwiML nowej konferencji |
| Legacy sesje Redis bez `customerCallSid` | Sesje sprzed deploymentu mają `null` | Log WARN + throw; alternatywny fallback: gdy `direction="INBOUND"` użyj `session1.getCallId()` |
| `MockTelephonyAdapter` z nową sygnaturą | Nie kompiluje po zmianie interfejsu | Zaktualizować Mock i testy |

---

## Pliki do modyfikacji

| Plik | Zmiana |
|------|--------|
| `CallSession.java` | Nowe pole `customerCallSid` |
| `TelephonyAdapter.java` | Sygnatura `bridgeCalls(String, String, UUID)` |
| `TwilioTelephonyAdapter.java` | `registerIncomingCall()`, `initiateCall()`, `executeAttendedTransfer()`, `bridgeCalls()`, `updateSessionContact()` |
| `MockTelephonyAdapter.java` | Nowa sygnatura `bridgeCalls()` |
| `ContactService.java` | `bridgeCalls()`, `createTransferContact()` – usuń `transfer_type=ATTENDED` |
| `TwilioRecordingDownloadService.java` | Usuń `propagateRecordingToTransferChain()` |
| `TwilioTelephonyAdapterTest.java` | Nowe testy `bridgeCalls()`, `executeAttendedTransfer()` |
| `ContactServiceTest.java` | Aktualizacja testów `bridgeCalls()` |
| `TwilioRecordingDownloadServiceTest.java` | Usuń testy propagacji |

---

## Plan testowania

### Testy jednostkowe (dodać/zaktualizować)

```
TwilioTelephonyAdapterTest:
  ✦ bridgeCalls_withCustomerCallSid_redirectsClientToNewConference
  ✦ bridgeCalls_withCustomerCallSid_redirectsAgent2ToNewConference
  ✦ bridgeCalls_withNullCustomerCallSid_throwsTelephonyException
  ✦ bridgeCalls_terminatesAgent1Leg
  ✦ bridgeCalls_updatesSession2WithNewContactIdAndConferenceName
  ✦ executeAttendedTransfer_propagatesCustomerCallSid
  ✦ registerIncomingCall_setsCustomerCallSid

ContactServiceTest:
  ✦ bridgeCalls_generatesNewContactIdBeforeCallingAdapter
  ✦ bridgeCalls_passesNewContactIdToAdapter
```

### Testy manualne E2E

```
1. INBOUND: klient dzwoni → Agent1 → konsultacja z Agent2 → bridge
   → weryfikacja: 2 kontakty, 2 nagrania w S3 (R-A i R-B)

2. OUTBOUND: Agent1 dzwoni → konsultacja z Agent2 → bridge
   → weryfikacja: j.w.

3. Łańcuch 3 transferów: Agent1 → Agent2 → Agent3
   → weryfikacja: 3 kontakty, 3 nagrania

4. Legacy sesja (symulacja null customerCallSid):
   → weryfikacja: WARN w logach, TelephonyException widoczny w UI agenta

5. Porównanie z blind transfer: identyczne zachowanie audio (brief gap ok. 1-2s)
```

---

## Szacowany nakład pracy

- Zmiana 1–3 (CallSession + propagacja): ~1h
- Zmiana 4 (refactor bridgeCalls): ~3h + testy
- Zmiana 5–6 (sygnatury + cleanup): ~1h
- Zmiana 7 (workaroundy): ~30min
- Testy jednostkowe: ~2h
- Testy E2E + debugowanie: ~2h

**Łącznie: ~10h**
