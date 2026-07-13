---
name: EPIC-21 Dialer – retry i callback w kampaniach wychodzących
description: Stan implementacji EPIC-21 (BE-062–BE-066): retry/callback w kampaniach wychodzących, kluczowe wzorce i decyzje.
type: project
---

EPIC-21 zakończony (BE-062–BE-066). Implementacja dotyczy Progressive Dialera i ScheduledCallbackExecutor.

**Why:** Dialer musi rozróżniać między normalną próbą dialera (inkrementuje attempt_count) a callbackiem kampanijnym (nie inkrementuje – to jest oddzwonienie zaplanowane przez agenta, nie nowa próba dialera).

**How to apply:** Przy kolejnych zmianach w obszarze dialera pamiętaj o tym rozróżnieniu.

## Kluczowe wzorce zaimplementowane w EPIC-21

### Redis klucze dialera (format)
- `dialer:call:{callSid}` = `"{recordId},{campaignId},{agentId},{tenantId}"` (CSV, TTL 1800s)
- `dialer:agent:{agentId}` = blokada agenta
- `dialer:timeout:{callSid}` = timer no-answer
- `dialer:callback-attempt:{callSid}` = marker że to był callback attempt (NIE nowa próba dialera), TTL 1800s

### Marker callback-attempt (BE-066)
ScheduledCallbackExecutor ustawia `dialer:callback-attempt:{callSid}` gdy przetwarza callback kampanijny.
DialerCallbackHandler.handleNoAnswer() sprawdza ten marker:
- marker istnieje → NO_ANSWER bez sprawdzania attempt_count, marker usunięty
- marker nie istnieje → normalny flow (sprawdza attempt_count, może ustawić NOT_REACHED)

### markAsDialingForCallback vs markAsDialing
- `markAsDialing()` → DIALING + inkrementuje attempt_count (normalny dialer)
- `markAsDialingForCallback()` → DIALING BEZ inkrementacji attempt_count (callback attempt)

### agentId null-safety w kluczu Redis
Gdy callback nie ma przypisanego agenta (agentId=null), ScheduledCallbackExecutor używa placeholder "00000000-0000-0000-0000-000000000000" żeby zachować format 4-elementowy CSV wymagany przez DialerCallbackHandler.onCallHangup().
