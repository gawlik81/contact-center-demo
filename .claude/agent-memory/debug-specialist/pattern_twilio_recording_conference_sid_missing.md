---
name: Twilio recording callback — brak conference_sid w channel_metadata
description: handleRecordingCallback nie może powiązać nagrania konferencji z kontaktem bo conference_sid nigdy nie jest zapisywany do channel_metadata; dotyczy każdego połączenia Twilio
type: feedback
---

Nagrania konferencji Twilio zawsze gubią się z powodu brakującego `conference_sid` w `channel_metadata`.

**Mechanizm:**
1. `dialAgentIntoConference()` tworzy konferencję Twilio z `record="record-from-start"` i `recordingStatusCallback`
2. Twilio po zakończeniu wysyła recording callback z `callSid=null, conferenceSid=CF...`
3. `handleRecordingCallback` szuka kontaktu przez `findContactIdByConferenceSid(CF...)` — wymaga `conference_sid` w `channel_metadata`
4. `conference_sid` miał być zapisany przez `handleStatusCallback` → `updateConferenceSidInMetadata()`, ale StatusCallback dla połączenia agenta nie jest skonfigurowany w `dialAgentIntoConference()`, więc Twilio go nie wysyła
5. Lookup zwraca `Optional.empty()` → WARN → nagranie porzucone

**Drugi problem (naprawiony):** Stara wersja `findContactIdByCallSid` zawierała `AND is_deleted = FALSE` w natywnym SQL, ale tabela `contact` nie ma tej kolumny → `InvalidDataAccessResourceUsageException`. Naprawka: usunięcie warunku (widoczne w `git diff HEAD ContactRepository.java`).

**Why:** Architektura powiązania nagrania konferencji z kontaktem wymaga 2-etapowego zapisywania ConferenceSid, ale etap 1 (StatusCallback nogi agenta) nigdy nie jest wyzwalany.

**How to apply:** Przy każdym błędzie `Nagranie nie zostanie zapisane` dla `conferenceSid=CF...` — sprawdź czy `channel_metadata` kontaktu zawiera klucz `conference_sid`. Jeśli nie, przyczyna leży w braku StatusCallback dla nogi agenta w `dialAgentIntoConference()`. Rekomendowana naprawa: lookup przez Twilio API `Conference.fetcher(conferenceSid).fetch().getFriendlyName()` w `handleRecordingCallback` (nazwa konferencji to deterministycznie `contact-{UUID}`).
