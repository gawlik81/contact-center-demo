---
name: project_be132_social_message_idclass
description: BE-132 (EPIC-30) – migracja SocialMessage na @IdClass po partycjonowaniu V100/DB-065; wzorzec ON CONFLICT DO NOTHING zamiast catch(DataIntegrityViolationException); naprawa źródła sent_at dla FB/IG
metadata:
  type: project
---

BE-132 (EPIC-30, trzeci ticket łańcucha DB-064 → DB-065 → BE-132 → BE-133) ukończone
2026-10-01. `SocialMessage` (`@Id @GeneratedValue @UuidGenerator messageId`, zapis przez `em.merge`)
przeszła na `@IdClass(SocialMessageId.class)` (`messageId`, `sentAt`) + natywny INSERT — wzorzec
1:1 z [[project_epic29_be117_idclass_partitioned_entities]] (`ContactEvent`/`ContactEventId`).

**Nowość względem BE-117: `ON CONFLICT ON CONSTRAINT ... DO NOTHING RETURNING message_id` zamiast
`catch (DataIntegrityViolationException)`.** Uzasadnienie zapisane w Javadoc
`SocialMessageRepository#INSERT_SQL`: złapanie wyjątku z natywnego zapytania w TEJ SAMEJ transakcji
Springa oznacza ją jako rollback-only (Hibernate unieważnia sesję po błędzie SQL) — dalszy kod
metody i tak by się nie powiódł. `ON CONFLICT DO NOTHING` unika tego problemu u źródła, bez
wyjątku. Celowo TARGETOWANY na nazwany constraint (`ON CONSTRAINT uq_social_message_external_id`),
nie bezwarunkowy `ON CONFLICT DO NOTHING` — kolizja PRIMARY KEY (praktycznie niemożliwa przy
losowym `UUID.randomUUID()`) oznaczałaby błąd aplikacji i powinna rzucić wyjątek głośno. `save()`
zwraca `Optional<SocialMessage>` — `empty()` = idempotentny duplikat (DRUGA linia obrony po dedupie
aplikacyjnym `findByExternalMessageId`), wołający MUSI to sprawdzić i potraktować jako no-op, NIE
błąd. **Wzorzec do powtórzenia przy BE-134 (`EmailMessage` → `@IdClass`, D4 = A też zakłada
unikalność złożoną jako druga linia obrony).**

**Naprawa źródła `sent_at` dla Facebook/Instagram (warunek wejścia z nagłówka V100 — migracja sama
w sobie NIE była bezpieczna produkcyjnie dla FB/IG dopóki to nie zostało naprawione):** Meta
Messenger Platform / Instagram Messaging API webhook ma pole `timestamp` jako SIBLING węzła
`message` w `entry[].messaging[]` — Unix epoch MILISEKUND (WhatsApp Cloud API: `messages[].timestamp`
w SEKUNDACH — różne jednostki, nie pomylić). To ustalony czas zdarzenia wysyłany PONOWNIE przy
redelivery tego samego `mid` — ekstrakcja tego pola (zamiast `Instant.now()` w chwili przetworzenia
webhooka) czyni `sent_at` deterministycznym, więc unikalność złożona `(tenant_id,
external_message_id, sent_at)` z V100 łapie teraz redelivery FB/IG tak samo jak już łapała WhatsApp.
Fallback `Instant.now()` tylko gdy pole nieobecne/nieprawidłowe (`SocialWebhookController#
extractMetaEventTimestamp`). Pole JEST dostępne i wiarygodne w standardowym payloadzie Meta — NIE
była potrzebna tabela dedup zastępcza (ticket dawał taki wybór warunkowo, D4-social = A pozostaje).

**Self-invocation naprawiona przy okazji:** `SocialMessageServiceImpl#loadSendContext` i
`#saveOutboundMessage` miały `@Transactional`, ale wołane przez `this.` z tej samej instancji
(`sendMessage()`) — klasyczna pułapka Spring AOP proxy-based, adnotacje były nieskuteczne. Usunięto
je (nie dodano `@Lazy self` — niepotrzebne tutaj): obie metody tylko orkiestrują wywołania na INNYCH
bean-ach, każdy z własną poprawną granicą transakcyjną przez swój własny proxy.

**`purgeByContactIds` (BE-125) i `deleteOrphansByIds`/`findOrphansOlderThan` (BE-127) NIE wymagały
zmian** mimo partycjonowania — obie filtrują po `tenant_id`+`contact_id` (nie po PK) albo
`tenant_id`+`message_id` (bez `sent_at`); `message_id` jest unikalny w PRAKTYCE (losowy UUID), więc
Postgres poprawnie skanuje wszystkie partycje przez lokalny indeks PK. Potwierdzone testami
`SocialMessagePurgeIntegrationTest`/`SocialMessageOrphanPurgeIntegrationTest` (bez zmian, zielone).

**Znany, udokumentowany, POZA ZAKRESEM edge case:** przy prawdziwym race dwóch równoległych
deliveries tego samego zdarzenia, obie przechodzą dedup aplikacyjny PRZED commitem → obie tworzą
WŁASNY kontakt (`SocialMessageServiceImpl#createSocialContact`) → "przegrana" strona (jej `save()`
zwraca `empty()`) NIE publikuje `contact.queued`, ale jej kontakt pozostaje osierocony (bez
wiadomości). Istniał PRZED BE-132 (niezależny od partycjonowania), udokumentowany w komentarzu przy
`processIncomingWithTenantContext`, nie naprawiany w tym tickecie.

Testy nowe: `SocialWebhookControllerTimestampTest` (jednostkowy, bez Springa/DB — redelivery
IDENTYCZNEGO payloadu → identyczny `sentAt`, fallback, brak regresji WhatsApp sekund),
`SocialMessageRepositorySaveIntegrationTest` (Testcontainers, pod restricted-role `app_user` z
[[feedback_repository_tests]]-podobnym wzorcem `JpaTestContext` — partycja po `tableoid`, druga
linia obrony ON CONFLICT, kolizja PK wciąż rzuca, RLS cross-tenant).

`mvn verify -pl app`: 2201 testów, 0 failures, BUILD SUCCESS (2026-10-01).
