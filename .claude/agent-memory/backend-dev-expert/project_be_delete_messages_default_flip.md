---
name: project_be_delete_messages_default_flip
description: EPIC-30 — decyzja właściciela 2026-10-07 włącza retention.purge.delete-messages domyślnie (było false)
metadata:
  type: project
---

2026-10-07 — właściciel zdecydował (w odpowiedzi na BE-135): `retention.purge.delete-messages`
WŁĄCZONA domyślnie (`application.yml`: `${RETENTION_PURGE_DELETE_MESSAGES:true}`, było `:false`).
Flaga steruje `RetentionPurgeServiceImpl#deleteMessagesEnabled` i
`RetentionEvaluationServiceImpl#deleteMessagesEnabled` (jedna property, dwie klasy, `@Value`
default w adnotacjach Javy **pozostał `:false`** — celowo NIEZMIENIONY, bo nieosiągalny: property
jest zawsze zdefiniowana w base `application.yml`, więc ten SpEL fallback nigdy się nie wykonuje
w działającej aplikacji; zmiana jego literału byłaby zmianą kodu/logiki, nie tylko komentarza —
poza zakresem zleconej poprawki).

**Why:** przy `false` purge `CONTACT_INTERACTIONS` nigdy nie usuwał `email_message`/`social_message`
(tylko `detachContactReferences`), więc ich partycje nigdy się nie opróżniały i `PartitionReclaimJob`
(BE-135) zgłaszał WARN bez końca — retencja treści wiadomości (D1 = A, [[project_epic30_be124_message_retention_adr]])
faktycznie nie działała.

**Testy, które CICHO zależały od Java default `false` dla pola boolean (zamiast jawnego
`ReflectionTestUtils.setField`)** — poprawione, żeby jawnie ustawiać `false`, bez zmiany tego, co
sprawdzają:
- `RetentionPurgeServiceImplTest$FlagDisabledRegression#flagFalse_neverCallsNewMethods`
- `RetentionEvaluationServiceImplTest` (`MessagesInEligibleRowCount` czy podobny nested):
  `emailAlone_doNotTriggerAutoPurge_whenDeleteMessagesDisabled`,
  `socialAlone_doNotTriggerAutoPurge_whenDeleteMessagesDisabled`,
  `contactsWithMessages_triggerDecisionUsesContactsOnly_whenDeleteMessagesDisabled`.

**How to apply:** `email.attachments.pending-sweep-delete-enabled` (BE-131) jest OSOBNĄ flagą,
ten sam wzorzec bezpiecznika, ALE osobna decyzja właściciela — nie zmieniona tutaj, wciąż `false`
domyślnie w `application.yml`. Przy włączaniu TEJ flagi w przyszłości ten sam schemat: zmiana tylko
w `application.yml` + grep wszystkich Javadoc/komentarzy mówiących „domyślnie false”/„ścieżka
domyślna” + grep testów na implicit Java default przez `@Value` (nie działa poza kontenerem Springa —
testy budujące serwis przez `new` NIGDY nie honorują `@Value`, tylko Java default pola).

Żadna z `application-dev.yml`/`application-prod.yml`/`application-test.yml` nie nadpisuje tej
property — wszystkie dziedziczą nowy default `true` z base `application.yml`.

**PRZYPOMNIENIE operacyjne (nie memory, ale ważne przy następnym demo):** po przebudowie
local-demo ten default zacznie faktycznie usuwać stare wiadomości e-mail/social i obiekty S3 przy
najbliższym uruchomieniu `RetentionEvaluationJob`/`RetentionPurgeServiceImpl` na danych, które
przekroczyły retencję tenanta — nieodwracalne.
