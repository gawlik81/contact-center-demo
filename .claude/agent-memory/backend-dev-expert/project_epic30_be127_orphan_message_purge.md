---
name: project_epic30_be127_orphan_message_purge
description: EPIC-30 BE-127 — sweep wiadomości osieroconych (contact_id IS NULL) wg wieku; scalenie fetch+purge w EmailMessageService/SocialMessageService.purgeOrphansOlderThan, dangling pominięty (uzasadnienie), filtr resztkowy tylko dla email
metadata:
  type: project
---

BE-127 (2026-09-26) — sweep sierot podpięty za TĄ SAMĄ flagą co BE-126 (`retention.purge.delete-messages`,
`deleteMessagesEnabled` w `RetentionPurgeServiceImpl`), PO pętli kontaktów i PO pętli `contact_event`
w `purgeContactInteractionsWithMessageDeletion` — DWIE niezależne pętle keyset (email, potem social).

**Nowe API — wzorzec „scalone fetch+purge w JEDNYM wywołaniu serwisu"** (różni się od kontaktów
BE-126, gdzie fetch/`findContactIdsOlderThan` i akcja/`deleteContacts` są DWOMA odrębnymi wywołaniami
orkiestrowanymi przez `RetentionPurgeServiceImpl` — sierota nie wpływa na żadną INNĄ decyzję, więc nie
trzeba rozdzielać):
- `EmailMessageService#purgeOrphansOlderThan(tenantId, EmailOrphanCursor cursor, Instant cutoff, int batchSize)
  → OrphanEmailPurgeBatch(PurgedMessages purgedMessages, int candidatesFound, EmailOrphanCursor nextCursor)`.
  Wewnątrz: `EmailMessageRepository#findOrphansOlderThan` (SELECT, zwraca `OrphanCandidate(messageId,
  messageAt, attachmentsJson)`, package-private) → mapowanie na `AttachmentsRow(messageId, null,
  attachmentsJson)` → **reużycie `EmailMessageServiceImpl#purgeRows` 1:1 z BE-125** (S3+DELETE, allow-lista,
  potwierdzenie RETURNING — zero duplikacji).
- `SocialMessageService#purgeOrphansOlderThan(...) → OrphanSocialPurgeBatch(int deletedRows, int
  candidatesFound, SocialOrphanCursor nextCursor)` — bez S3, nowa `SocialMessageRepository#deleteOrphansByIds`
  (DELETE…RETURNING, jak `EmailMessageRepository#deleteByIds`).
- `countOrphansOlderThan(tenantId, cutoff) → long` na obu serwisach — dry-run/BE-128, kryterium
  IDENTYCZNE z purge (współdzielona SQL-owa definicja wieku).
- `EmailOrphanCursor(Instant messageAt, UUID messageId)`/`SocialOrphanCursor` — publiczne top-level
  rekordy (jak `ContactPurgeCandidate`), bo `RetentionPurgeServiceImpl` (domain.retention) trzyma kursor
  między iteracjami. NIE reużywają wspólnego typu między email/social — świadoma duplikacja, ten sam
  wzorzec co istniejące `IN_LIST_CHUNK_SIZE` duplikowane w obu repozytoriach.

**Kryterium wieku (DOKŁADNIE z DB-059/V097, index `idx_email_message_tenant_orphan_age`/
`idx_social_message_tenant_orphan_sent`):** email = `COALESCE(received_at, sent_at, created_at)`,
social = `sent_at`. W `EmailMessageRepository` SQL-e (`COUNT_ORPHANS_SQL`/`FIND_ORPHANS_FIRST_PAGE_SQL`/
`FIND_ORPHANS_NEXT_PAGE_SQL`) budowane z JEDNEJ stałej `ORPHAN_AGE_EXPR` przez `.formatted(...)` —
MECHANICZNA (nie konwencjonalna) gwarancja identycznego wyrażenia w 3 zapytaniach + dopasowania do
indeksu.

**Filtr resztkowy `created_at < now() − 1 dzień` TYLKO dla email** (`ORPHAN_RESIDUAL_MARGIN`, liczony w
Javie jako `Instant.now().minus(...)`, NIE `now()` w SQL): chroni świeżo zapisaną, jeszcze nieprzypisaną
wiadomość INBOUND, której INTERNALDATE IMAP jest historyczne (skrzynka zmigrowana/nowo podłączona) przed
usunięciem w trakcie routingu. **Dla social świadomie POMINIĘTY** — `contact_id` jest ZAWSZE ustawiany
SYNCHRONICZNIE przed `save` w `SocialMessageServiceImpl#processIncomingWithTenantContext` (kontakt
tworzony/dobierany PRZED zapisem, ta sama transakcja) — świeża nieprzypisana wiadomość social z
`contact_id IS NULL` nie istnieje strukturalnie; jedyne źródło sierot social to `detachContactReferences`
na kontakcie JUŻ starszym niż cutoff (legacy purge), więc nigdy nie jest „świeża" w sensie, przed którym
broni filtr e-mail.

**Decyzja: wariant „dangling" (`NOT EXISTS`) POMINIĘTY, świadoma luka (nie zaimplementowany).**
Weryfikacja PRZED decyzją (grep całego `backend/app/src/main/java` + wszystkich migracji SQL): `contact`
jest usuwany WYŁĄCZNIE przez `ContactRepository#deleteContacts` (BE-126) i `#deleteBatchOlderThan`
(legacy) — zero innych ścieżek. W obu trybach purge (flaga true/false) odcięcie/usunięcie wiadomości
powiązanych z kontaktem następuje w TYM SAMYM synchronicznym wywołaniu co usunięcie kontaktu (BE125-02
drugi przebieg dla flag=true; `detachContactReferences` od razu po `deleteBatchOlderThan` dla flag=false)
— brak zewnętrznego okna czasowego na powstanie dangling. Koszt `NOT EXISTS` bez `started_at` (PK
`contact` = `(contact_id, started_at)`) = probing PK KAŻDEJ partycji per kandydat, bez partition pruning
— nietrywialny narzut przy potwierdzonym 0 dangling live. Rewizja potrzebna TYLKO jeśli powstanie inna
ścieżka usuwania `contact`.

**Breakdown audytu:** `orphanEmailMessages`/`orphanSocialMessages` jako pola OSOBNE od
`emailMessages`/`socialMessages` (nie zsumowane) — decyzja: obserwowalność (BE-128, audyt) > minimalizm
JSON. Liczniki S3 zostają WSPÓLNE (kontakt-tied + sieroty).

**EXPLAIN na scratch (metodologia, do reużycia przez BE-128/DB-067):** baza scratch tworzona jako
DODATKOWA baza WEWNĄTRZ działającego kontenera `cc-postgres` (`CREATE DATABASE scratch_xxx` jako
superuser `ccapp`, NIE nowy kontener) — `pg_dump -s` z `contact_center` + ręczne zastosowanie migracji
jeszcze niewdrożonych na demo, potem `DROP DATABASE`/`DROP ROLE` po pracy. **Pułapka generatora danych
(z DB-059, potwierdzona) — `g % N` i `g % M` są SKORELOWANE, gdy N jest wielokrotnością M** (np. 60 i 5)
— dawało 100%/0% osieroconych per tenant mimo poprawnego odsetka globalnego. Naprawa: niezależne
`hashtext('etykieta-' || g) % N`.

**Why (nieoczywiste — testowanie Social bez pełnego kontekstu domenowego):** `SocialMessageServiceImpl`
ma 5 zależności konstruktorowych (`@RequiredArgsConstructor`); metody BE-127 używają WYŁĄCZNIE
`socialMessageRepository`. W teście integracyjnym (`SocialMessageOrphanPurgeIntegrationTest`) 4
pozostałe zależności (`SocialIntegrationRepository`, `ContactService`, `SocialAdapterRegistry`,
`RabbitTemplate`) rejestrowane jako Mockito mocki (`registerSingleton`) w `JpaTestContext` — Spring
potrzebuje tylko OBECNOŚCI bean-a właściwego typu do skonstruowania beana przez DI, nie realnej
implementacji. Odróżnij od `EmailMessageServiceImpl` (tylko 2 zależności, obie realnie potrzebne i
zarejestrowane jako prawdziwe/fake).

**How to apply:** BE-128 (dashboard/badge) — użyj `countOrphansOlderThan` bezpośrednio, ten sam `cutoff`
co retencja `CONTACT_INTERACTIONS`. BE-130 (D1=C warunkowy) — obie pętle sweepu są już wyodrębnione jako
samodzielne `do-while` w `purgeContactInteractionsWithMessageDeletion`, łatwe do przeniesienia do
`purgeMessageContent`. DB-067/BE-135 (partycjonowanie `email_message`) — `EmailOrphanCursor`/
`OrphanCandidate` JUŻ niosą `messageAt`, przygotowane pod złożony PK. Powiązane:
[[project_epic30_be126_message_deletion_integration]], [[project_epic30_be125_message_purge]],
[[project-epic30-be124-message-retention-adr]] (D1, filtr resztkowy §7), [[feedback_uuid_compareto_vs_postgres_order]]
(dotyczy keyset po UUID w testach porządku).
