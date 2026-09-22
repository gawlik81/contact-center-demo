---
name: project_epic30_be126_message_deletion_integration
description: EPIC-30 BE-126 — integracja usuwania wiadomości w RetentionPurgeServiceImpl pod flagą retention.purge.delete-messages; strategia H-1 (keyset), drugi przebieg BE125-02, punkt (b) odłożony
metadata:
  type: project
---

BE-126 (2026-09-22) — `RetentionPurgeServiceImpl#purgeContactInteractions` rozdzielone na dwie ścieżki:
`purgeContactInteractionsLegacy` (dziś, `detachContactReferences`) i
`purgeContactInteractionsWithMessageDeletion` (nowe, `EmailMessageService`/`SocialMessageService#purgeByContactIds`
+ `ContactService#deleteContacts`), przełączane flagą `retention.purge.delete-messages`
(`@Value`, domyślnie **`false`** w każdym profilu — świadomie nieustawiona `true` bez decyzji właściciela o D1).

**Nowe API (`domain.contact`):**
- `ContactPurgeCandidate(UUID contactId, Instant startedAt)` — top-level public record.
- `ContactService#findContactIdsOlderThan(tenantId, cutoff, cursor, batchSize)` — SELECT-only, strona
  posortowana `(started_at, contact_id)`, stronicowanie keyset (`cursor` = ostatni kandydat poprzedniej
  strony, `null` dla pierwszej). Repo: `ContactRepository` z dwoma SQL-ami (`FIRST_PAGE`/`NEXT_PAGE`,
  bez CAST-owania nullowego kursora — unikanie niejednoznaczności typu parametru NULL w Hibernate).
- `ContactService#deleteContacts(tenantId, ids)` — `DELETE ... WHERE contact_id IN (...) AND tenant_id=...
  RETURNING contact_id` **bez `started_at`** w WHERE (bezpieczne mimo braku partition pruning — `contact_id`
  to logiczny, globalnie unikalny UUID, NIE fizyczny `ctid`; identyczny wzorzec już istniał w
  `ContactRepository#assignAgent`).

**Strategia H-1 (head-of-line blocking, code review BE-125) — decyzja wykonawcy:** ZAMIAST guardu
„faktycznie usunięte kontakty w iteracji > 0" (pierwotne doprecyzowanie BE-124), pętla używa stronicowania
keyset: kursor przesuwa się o CAŁĄ stronę niezależnie od liczby zablokowanych w niej kontaktów. Terminacja
zależy WYŁĄCZNIE od wyczerpania kandydatów (`page.isEmpty()`/`page.size() < batchSize`), nie od liczby
usunięć — strukturalnie eliminuje ryzyko, że ≥ `batchSize` trwale zablokowanych kontaktów zatrzyma purge
młodszych. Test: `RetentionPurgeServiceImplTest$MessageDeletionEnabled$HeadOfLineBlockingDefense`.

**Drugi przebieg (BE125-02):** po `deleteContacts` dla KAŻDEJ strony wołany jest ponowny
`purgeByContactIds` (email+social) wyłącznie dla kontaktów faktycznie usuniętych w TEJ iteracji — łapie
wiadomość dopisaną w oknie SELECT→DELETE. Tanie (≤ `batchSize` ID).

**`s3Failures > 0` → status `COMPLETED` z `error_message` (NIE `FAILED`):** nowy
`RetentionPurgeLogRepository#markCompleted(purgeId, tenantId, rowsDeleted, warningMessage)` (4-arg, stary
3-arg zachowany, deleguje z `warningMessage=null`). Audyt: `buildNewValueJson` rozszerzone o
`"breakdown":{"contacts","events","emailMessages","socialMessages","s3ObjectsDeleted","s3Failures","s3Rejected"}`.

**Punkt (b) z Zakresu ticketu (usuwanie `contact.recording_url` w fazie „S3 przed wierszem") CELOWO
ODŁOŻONY** — decyzja zlecającego, kolizja z równoległym BE-143 (allow-lista kluczy). `contact.recording_url`
nadal nietknięty przez purge (znana luka, BE-124 ryzyko (a)). BE-143 (scalone równolegle) dodał
`EmailAttachmentKeys#isRecordingKeyOwnedByTenant` (public) właśnie z myślą o tym follow-upie — zobacz
[[project_epic30_be143_attachment_key_validation]].

**Why (nieoczywiste — testowanie na granicy pakietów):** `ContactRepository`, `EmailMessageServiceImpl`/
`EmailMessageRepository`, `SocialMessageRepository` są package-private w SWOICH pakietach
(`domain.contact`/`domain.email`/`domain.social`); `RetentionPurgeServiceImpl` jest package-private w
`domain.retention`. Żaden pojedynczy plik testowy NIE może mieć widoczności do wszystkich naraz bez
`@SpringBootTest` (świadomie odrzucone w tym projekcie jako zbyt ciężkie dla testów integracyjnych —
patrz Javadoc `JpaTestContext` i [[feedback-jpa-real-db-integration-test-harness]]). Rozwiązanie: nowy
natywny SQL (`findContactIdsOlderThan`/`deleteContacts`) ma dedykowany test Testcontainers we WŁASNYM
pakiecie (`ContactRepositoryPurgeCandidatesIntegrationTest`, `domain.contact`), a orkiestracja
(`RetentionPurgeServiceImpl`) jest testowana na mockach kolaboratorów w `RetentionPurgeServiceImplTest`
(wzorzec identyczny jak dla ścieżki legacy) — BEZ jednego testu na żywej bazie obejmującego
contact+email+social naraz. Poprawność `purgeByContactIds` na żywej bazie (S3, RLS, allow-lista) jest
już pokryta przez `EmailMessagePurgeIntegrationTest`/`EmailMessagePurgeRlsIntegrationTest` z BE-125.

**How to apply:** przy BE-127/BE-129/BE-130/BE-131/FE-110 — ten sam wzorzec 2-poziomowy (Testcontainers
per-pakiet dla natywnego SQL + mocki dla orkiestracji cross-package) jest właściwym podejściem, NIE próbować
budować jednego testu integracyjnego spinającego wszystkie domeny. `rowsDeleted` = suma
`contact+contact_event+email_message+social_message`. Social (`SocialMessageService#purgeByContactIds`
zwraca tylko `int`, brak `contactIdsBlocked` — BE125-05) pozostaje POZA blokowaniem kontaktów — świadoma,
udokumentowana luka do naprawy w osobnym tickecie (kolejność DB-064 przed DB-074).
