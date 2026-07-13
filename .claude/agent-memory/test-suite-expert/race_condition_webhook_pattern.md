---
name: race-condition-webhook-pattern
description: Wzorzec testu race condition dla TwilioWebhookController i atomowych conditional UPDATE w ContactRepository
metadata:
  type: project
---

## Wzorzec testów race condition: hangup handler vs conference-end webhook

### Problem
Race condition: exec-4 (hangup) i exec-8 (conference-end webhook) czytają ACTIVE równocześnie.
exec-4 zapisuje COMPLETED, exec-8 3ms później nadpisuje ABANDONED.

### Naprawka (zweryfikowana testami)
1. Kontroler: tylko QUEUED/ASSIGNED mogą przejść do ABANDONED (ACTIVE i ON_HOLD wykluczone)
2. Repozytorium: `updateContactStatusIfNotTerminal` — atomowy SQL z `AND status NOT IN (...)`

### Wzorzec testu kontrolera (TwilioWebhookControllerConferenceTest)

**Konfiguracja:** `twilioProperties.setSignatureValidationEnabled(false)` — wyłącza HMAC, testy skupiają się na logice biznesowej.

**Kluczowe assercje dla regresji (ACTIVE/ON_HOLD):**
```java
verify(contactRepository, never()).updateContactStatusIfNotTerminal(any(), any(), any(), any());
verify(contactRepository, never()).updateContactStatusOnTelephonyEvent(any(), any(), any(), any());
```
Obie metody muszą być zweryfikowane przez `never()` — istnieje stara bezwarunkowa metoda (`updateContactStatusOnTelephonyEvent`) i nowa (`updateContactStatusIfNotTerminal`).

**Statusy terminalne testowane przez `@ParameterizedTest`:** COMPLETED, ABANDONED, NOT_REACHED, ERROR, TRANSFERRED

### Wzorzec testu repozytorium (ContactRepositoryUpdateIfNotTerminalTest)

**Setup:** `ContactRepository` tworzone ręcznie, `JdbcTemplate` mockowany, `EntityManager` mockowany przez `ReflectionTestUtils.setField(repo, "em", mock)`.

**Kluczowy test SQL:** `ArgumentCaptor<String>` przechwytuje SQL i weryfikuje obecność `NOT IN` + wszystkich 5 statusów terminalnych.

**Zwracana wartość:** `true` gdy `jdbcTemplate.update()` zwróci 1, `false` gdy 0.

**Why:** JdbcTemplate mock symuluje zachowanie WHERE — baza sama decyduje o filtrowaniu. To prostsze niż Testcontainers i weryfikuje kontrakt metody.

**How to apply:** Ten wzorzec (mock JdbcTemplate + ArgumentCaptor na SQL) działa dla dowolnej metody ContactRepository używającej `jdbcTemplate.update()` z warunkowym WHERE.
