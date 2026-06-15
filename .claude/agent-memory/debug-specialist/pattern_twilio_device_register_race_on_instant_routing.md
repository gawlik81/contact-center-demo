---
name: pattern_twilio_device_register_race_on_instant_routing
description: Klient słyszy hold music mimo CALL_ANSWERED/status ACTIVE — agent Device nie zdążył się zarejestrować przed dialAgentIntoConference
metadata:
  type: project
---

Scenariusz: agent A jest AVAILABLE z oczekującym kontaktem w kolejce (klient już w konferencji
z `startConferenceOnEnter="false"`, słucha waitUrl). Agent A się wylogowuje, agent B loguje się
i NATYCHMIAST (RoutingServiceImpl.handleAgentStatusChanged reaguje na event AVAILABLE w ~85ms)
dostaje przydzielony ten oczekujący kontakt. Agent B klika "Odbierz" ~1.5-2s po zalogowaniu.

**Root cause:** `AgentShellComponent.twilioDeviceEffect` (frontend/src/app/features/agent/agent-shell.component.ts)
inicjuje `softphoneService.initializeTwilioDevice()` (softphone.service.ts) ASYNCHRONICZNIE w momencie
przejścia statusu na AVAILABLE — GET /voice-token → new Device(token) → await device.register().
To trwa 1-3s. Jeśli `TwilioTelephonyAdapter.dialAgentIntoConference()` (Call.creator do
`client:agent-{agentId}`) wykona się ZANIM device.register() się zakończy (twilioDeviceReady()===false),
Twilio REST zwraca poprawny agentCallSid, ale nic nie odbiera tej nogi po stronie Twilio Client.
Konferencja klienta nigdy nie "startuje" (nikt nie wchodzi jako moderator startConferenceOnEnter=true),
klient zostaje w hold music na zawsze. Backend nie ma żadnej weryfikacji że agent faktycznie odebrał —
loguje CALL_ANSWERED i status=ACTIVE mimo cichego no-answer na Twilio.

**Scenariusz "agent loguje się i czeka na NOWE połączenie" działa OK** — Device ma czas się
zarejestrować przed nadejściem połączenia.

**Why:** to pre-existing bug (nie regresja refaktorów domain.*), wynika z braku gatingu
"status AVAILABLE" na "Twilio Device ready" — backend routuje/dialuje natychmiast po AVAILABLE,
frontend ustawia AVAILABLE PRZED zakończeniem device.register().

**How to apply:** przy diagnozowaniu "klient słyszy hold music / cisza mimo ACTIVE/CALL_ANSWERED
w logach" — sprawdź timing: czy w logu pojawia się "[TwilioVoice] Access Token wygenerowany dla
identity=agent-{agentId}" PRZED "Dzwonię do agenta przez Twilio Client: agentClientId=...".
Brak tego loga lub zbyt mały odstęp czasowy (<1-2s) między AVAILABLE i dialAgentIntoConference
wskazuje na ten race. Proponowany fix: odwrócić kolejność w AgentShellComponent — ustawiać status
AVAILABLE w backendzie/UI dopiero PO device.on('registered'), nie przed. Alternatywnie: backend
powinien czekać na sygnał "device-ready" od frontendu przed Call.creator, albo weryfikować
status konferencji po dialAgentIntoConference i retry/cofnąć przydział jeśli agent nie dołączył.

Powiązane: [[pattern_twilio_session_key_mismatch]], [[pattern_outbound_conference_music_instead_of_agent]]
