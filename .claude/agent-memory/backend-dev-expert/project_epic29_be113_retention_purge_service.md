---
name: project_epic29_be113_retention_purge_service
description: EPIC-29 BE-113 – RetentionPurgeService, silnik usuwania Poziom 1 dla CONTACT_INTERACTIONS/TRANSCRIPTS; wzorzec self-injection async i bezpieczny batch DELETE na partycjach
metadata:
  type: project
---

BE-113 (EPIC-29 „Partycjonowanie i retencja danych z obsługi kontaktów") ukończone 2026-08-11.
Silnik usuwania per-tenant, batchowany, asynchroniczny dla kategorii `CONTACT_INTERACTIONS`
(`contact` + `contact_event`, plus odcięcie FK `email_message`/`social_message`) i `TRANSCRIPTS`
(`contact_transcription` + `contact_ai_summary`). `RECORDINGS` (BE-116) i `CAMPAIGN_DATA` (BE-119)
poza zakresem — `purge()` rzuca `UnsupportedOperationException` dla tych kategorii.

**Nowe pliki:** `domain/retention/{RetentionPurgeService,RetentionPurgeServiceImpl,
RetentionPurgeLog,RetentionPurgeLogRepository,PurgeTriggerType}.java`,
`domain/retention/dto/PurgeResultDto.java`.

**Rozszerzenia poza pierwotną listę plików (konieczne, nie scope creep):** repozytoria w tym
projekcie są `package-private`, więc `RetentionPurgeServiceImpl` (pakiet `domain.retention`) nie
mógł wstrzyknąć `ContactRepository`/`EmailMessageRepository`/`SocialMessageRepository` itd.
bezpośrednio. Logika usuwania per tabela trafiła do PUBLICZNYCH serwisów domenowych, wzorem
istniejącego „encapsulation pass" w `ContactService` (patrz `saveAiSummary`,
`findTranscriptionContent`): nowe metody `purgeContactsOlderThan`/`purgeTranscriptionsOlderThan`/
`purgeAiSummariesOlderThan` (ContactService), `purgeOlderThan` (ContactEventService),
`detachContactReferences` (EmailMessageService/SocialMessageService) — każda deleguje 1:1 do
nowej metody `deleteBatchOlderThan`/`detachContactReferences` w odpowiednim repozytorium.

**Self-invocation @Async:** `purgeAsync` MUSIAŁA zostać dodana do interfejsu `RetentionPurgeService`
(nie tylko do impl) — self-injected `@Autowired @Lazy private RetentionPurgeService self` w
`purge()` woła `self.purgeAsync(...)`, wzorzec identyczny do `ProgressiveDialerServiceImpl.self`
(patrz [[feedback_self_invocation_transactional]], dotyczy też `@Async` nie tylko `@Transactional`).
Przy okazji zauważono, że `CustomerImportServiceImpl.initiateImport` woła `processImportAsync`
przez zwykłe self-invocation (metoda NIE jest częścią interfejsu `CustomerImportService`) —
prawdopodobnie pre-existing bug, `@Async` tam nigdy nie jest honorowane w produkcji. Poza zakresem
BE-113, nieodnaleziony/niepoprawiony — do weryfikacji przy przyszłej pracy nad importem klientów.

**Krytyczne odkrycie — `ctid` na tabelach partycjonowanych:** patrz osobna memory
[[feedback_partitioned_table_ctid_delete_pitfall]] — zweryfikowano empirycznie na żywym Postgresie
że `DELETE ... WHERE ctid IN (subquery LIMIT N)` usuwa wiersze z INNYCH partycji/tenantów przez
kolizję `ctid`. Zastąpione bezpiecznym wzorcem `WITH batch AS (SELECT pk, partition_col ... LIMIT N)
DELETE ... USING batch WHERE pk=batch.pk AND partition_col=batch.partition_col` — identyfikacja przez
PEŁNY klucz główny. Zastosowane konsekwentnie we wszystkich 4 metodach `deleteBatchOlderThan`
(`ContactRepository`, `ContactEventRepository`, `ContactAiSummaryRepository`,
`ContactTranscriptionRepository`).

**Cutoff:** `LocalDate.now(ZoneOffset.UTC).minusMonths(retentionMonths)` (z `RetentionPolicyService.
getRetentionMonths`), konwertowane na `Instant` przez `cutoffDate.atStartOfDay(ZoneOffset.UTC).
toInstant()` do porównań z kolumnami `started_at`/`generated_at`/`created_at`.

**Testy:** `RetentionPurgeServiceImplTest` (20 scenariuszy, mockowane wszystkie zależności,
self-invocation testowane przez `ReflectionTestUtils.setField(service, "self", service)` —
identyczny wzorzec do `ProgressiveDialerServiceTest`) + `ContactRepositoryPurgeTest` (6 scenariuszy,
weryfikuje treść SQL — brak `ctid`, pełny PK). Projekt nie ma Testcontainers/H2 dla testów
repozytoriów — poprawność cross-tenant na partycjach zweryfikowana MANUALNIE bezpośrednio na
`cc-postgres` (transakcje z ROLLBACK), nie jest to część automatycznego `mvn verify`.

`mvn verify -pl app`: BUILD SUCCESS, 1622 testy (1596 przed BE-113 + 20 + 6), 0 failures.
