---
name: TwilioTelephonyAdapter persistContact — agentId=null blokuje setDisposition
description: Kontakt tworzony przez webhook Twilio ma agentId=null; ContactService.setDisposition rzuca InvalidOperationException przy próbie zapisu dyspozycji przez agenta
type: feedback
---

Gdy Twilio wysyła webhook `ringing`, `TwilioTelephonyAdapter.persistContact()` tworzy rekord
`contact` z `agentId=null` — agent nie jest znany przy połączeniu przychodzącym.

`ContactService.setDisposition()` (linia 296) sprawdza:
```java
if (isAgent && !userId.equals(contact.getAgentId())) {
    throw new InvalidOperationException("Agent może ustawiać disposition tylko na własnych kontaktach: ...");
}
```

`userId.equals(null)` = `false`, więc `!false` = `true` → wyjątek rzucony mimo że agent ma prawo.

**Why:** Walidacja własności kontaktu nie przewiduje przypadku `agentId=null` (kontakty Twilio
tworzone bez agenta przy webhook). Dotyczy tylko AGENT role — SUPERVISOR/ADMIN nie podlegają
tej walidacji.

**How to apply:** Przy naprawie `ContactService.setDisposition()` zawsze sprawdź czy
`contact.getAgentId() != null` przed porównaniem z `userId`. Poprawna walidacja:
```java
if (isAgent && contact.getAgentId() != null && !userId.equals(contact.getAgentId())) { ... }
```
Alternatywnie: zadbaj by `agent_id` był aktualizowany w kontakcie gdy agent odbiera
połączenie (zdarzenie `call.answered`).
