---
name: project_plugin_version_overwrite_fix
description: fix/plugin-version-overwrite (EPIC-28) — admin nie mógł zastąpić treści tej samej wersji pluginu; UPDATE w miejscu zamiast delete+insert, afterCommit S3 cleanup, reużycie ConflictException
metadata:
  type: project
---

## Problem
Upload JAR-a tej samej wersji tego samego pluginu dla tego samego tenanta zawsze kończył się
generycznym 409 "Naruszenie integralności danych" (`GlobalExceptionHandler
.handleDataIntegrityViolationException`, fallback dla `DataIntegrityViolationException`) — bez
możliwości zastąpienia treści (np. poprawka buga bez bumpu numeru wersji).

## Rozwiązanie
`PluginStorageServiceImpl#storeValidatedJar` dostał parametr `overwrite` (domyślnie `false` przez
**default method** na interfejsie `PluginStorageService`, delegujący do nowego 6-arg abstract
method — zero zmian w istniejących wywołaniach przez konkretną klasę, bo default methods są
dziedziczone i dynamicznie dispatchowane do przesłonięcia).

Pre-check przez nową `PluginVersionRepository.findByPluginIdAndVersionAndTenantId(pluginId,
version, tenantId)` (dokładny odpowiednik `uq_plugin_version_plugin_version_tenant`, V078) PRZED
jakimkolwiek INSERT/UPDATE:
- `overwrite=false` + istnieje → `ConflictException` (REUŻYTA istniejąca klasa z
  `domain.exception`, nie nowa — ma już dedykowany handler w `GlobalExceptionHandler` mapujący na
  409 ProblemDetail z `ex.getMessage()` jako detail; wzorzec identyczny jak w
  `PluginRegistrationServiceImpl`, `DispositionSetServiceImpl`, `PhoneNumberServiceImpl` itd.)
- `overwrite=true` + istnieje → **UPDATE wiersza w miejscu** (ten sam `id`), NIE delete+insert —
  wymóg FK `tenant_plugin_installation.plugin_version_id ... ON DELETE RESTRICT` (V075): delete
  zawiodłoby, gdyby jakikolwiek tenant miał aktywną instalację tej wersji.
- Usunięto starą, nieużywaną i niebezpieczną `findByPluginIdAndVersion(UUID, String)` (bez
  filtra tenantId — niedeterministyczna przy wielu tenantach na tę samą wersję tego samego
  pluginu). Zero callerów w repo przed usunięciem.

## `uploaded_at` / `updatable = false`
Usunięto `updatable = false` z `PluginVersion.uploadedAt` (Hibernate ignorowałby UPDATE tego
pola). Decyzja: `uploaded_at` = "kiedy TA treść została wgrana", nie "kiedy wiersz powstał" —
semantyka zmienia się przy overwrite. Brak migracji DB potrzebnej (sama kolumna nie ma
DB-level immutability, tylko JPA mapping).

**Napięcie z komentarzem V074** (`COMMENT ON TABLE plugin_version IS 'niemutowalne po
VALIDATED... nigdy edycja'`): overwrite jest świadomym, wąskim wyjątkiem od tej reguły,
udokumentowanym w Javadoc (`PluginVersion.uploadedAt`, `PluginStorageServiceImpl#applyOverwrite`),
ale NIE zmieniono samej migracji (CLAUDE.md: nigdy nie edytuj zastosowanej migracji) ani nie
dodano nowej migracji tylko do update'u tekstu komentarza (kosmetyczne, zero wpływu
funkcjonalnego) — zaproponowane jako opcjonalny follow-up, nie zrobione bez pytania.

## S3 cleanup vs transakcja — wzorzec `afterCommit`
Usuwanie starego obiektu S3 przy `overwrite=true`:
1. Upload NOWEJ treści do S3 ZAWSZE przed modyfikacją DB (insert i update) — porażka DB po
   udanym uploadzie daje w najgorszym razie osierocony NOWY obiekt (nieszkodliwy).
2. Usunięcie STAREGO obiektu S3 rejestrowane przez
   `TransactionSynchronizationManager.registerSynchronization(... afterCommit() { delete } ...)`
   — NIGDY przed commitem (gdyby transakcja się wycofała, wiersz wróciłby do starego klucza,
   który już by nie istniał — utrata danych gorsza niż osierocony obiekt).
3. Brak aktywnej transakcji (testy jednostkowe wywołujące klasę bez proxy AOP Springa) →
   fallback: usuń natychmiast — czyni to też łatwo testowalnym bez symulacji Springa.
4. Porażka usunięcia po commicie → log ERROR, **nie propagowana** (transakcja już zacommitowana,
   Spring i tak by to połknął w `afterCompletion`). Brak dziś sweep joba dla osieroconych
   obiektów katalogu pluginów (BE-127 sweep istnieje tylko dla załączników e-mail) —
   potencjalny follow-up, niezaimplementowany.

To pierwszy użyty w repo `TransactionSynchronizationManager.registerSynchronization` wzorzec
(grep nie znalazł wcześniejszego użycia) — rozważ to miejsce jako referencję, jeśli inny serwis
będzie potrzebował "S3 delete bezpieczny względem rollbacku".

## BLOCKER znaleziony przez code review (naprawiony w tej samej gałęzi, po pierwszym handbacku)
Pierwsza wersja budowała klucz S3 przy overwrite przez deterministyczny `buildS3Key(tenantId,
pluginKey, version, originalFilename)` — **identyczny jak istniejący**, gdy admin wgrywał
poprawkę z TĄ SAMĄ nazwą pliku (najczęstszy use case tego ficha: "poprawka buga bez bumpu
wersji"). Sekwencja: `uploadToS3` leci PRZED `save()` w ramach `@Transactional` — PUT pod
identycznym kluczem nadpisywał bajty W MIEJSCU, PRZED commitem. Gdyby `save()`/commit zawiódł
POTEM (deadlock, constraint, cokolwiek) → ROLLBACK DB wracał do starego checksumu/statusu, ale
fizyczna treść pod tym kluczem S3 była już nieodwracalnie nowa (S3 nie jest transakcyjne, bucket
bez versioning, `downloadJar` nie rewaliduje checksumu przy odczycie). Mój komentarz "najgorszy
przypadek to nieszkodliwy osierocony NOWY obiekt" był prawdziwy TYLKO dla insert i
overwrite-z-inną-nazwą — fałszywy dla overwrite-z-tą-samą-nazwą (tam "nowy" i "stary" to ten sam,
już referencjonowany klucz).

**Fix:** nowa `buildOverwriteS3Key(tenantId, pluginKey, version, originalFilename)` dopisuje
`UUID.randomUUID()` jako segment ścieżki — klucz przy overwrite jest ZAWSZE unikalny, niezależnie
od tego, czy `originalFilename` się zmienił. Insert (brak istniejącej wersji) nadal używa
deterministycznego `buildS3Key` bez zmian. Efekt: `scheduleOldS3ObjectCleanup(previousS3Key)` w
`applyOverwrite` jest teraz wołane BEZWARUNKOWO (usunięto `if (!previousS3Key.equals(newS3Key))`
— zawsze `true` po tym fixie, warunek byłby dead code) — stary klucz przetrwa nietknięty aż do
commitu niezależnie od scenariusza.

**Major (ten sam przebieg):** dodano audit log (`[PluginStorage][AUDIT]`) w `applyOverwrite` z
checksum/s3Key/status PRZED→PO — `domain.plugin` nie używa dziś `AuditAspect`/`AuditLogService`,
więc to jedyny trwały zapis "co działało wcześniej pod tym `plugin_version.id`" po nadpisaniu w
miejscu.

**Regresja (code review wymógł):** `overwriteWithSameFilenameNeverOverwritesOldKeyInPlaceWhenDbSaveFails`
— mock `pluginVersionRepository.save()` rzuca `DataIntegrityViolationException` PO udanym
`s3Client.putObject()`; weryfikuje, że PUT poszedł pod inny klucz niż stary i że `deleteObject`
nigdy nie jest wołane (stary obiekt nietknięty, mimo "rollbacku").

## `mvn verify -pl app` — pułapka zabrudzonego `target/`
Pierwszy przebieg (bez `mvn clean`) dał 7 niepowodzeń w zupełnie innym obszarze (EPIC-30
`social_message` EXPLAIN-plan/definicje indeksów) — zgłosiłem je błędnie jako "pre-existing,
środowiskowe" na podstawie reprodukcji w izolacji. Koordynator poprawił: to był zabrudzony
`target/classes/db/migration` z innej równolegle pracującej gałęzi w tym samym katalogu roboczym
— `mvn clean -pl app` przed `verify` dał 2212/2212 zielone. Zobacz
[[feedback_stale_target_classes_migrations_false_failures]] — reprodukcja w izolacji NIE jest
dowodem "niezwiązane", gdy `target/` jest współdzielony między gałęziami/agentami.

## Testy
`PluginStorageServiceImplTest$Overwrite` (7 testów po poprawce blockera, w tym regresja save()-fails)
+ `PluginUploadControllerTest$UploadPluginOverwrite` (3 testy). Istniejące testy (9 + 5) przeszły
BEZ ZMIAN treści logiki — tylko kontroler zmienił sygnaturę Javy (`uploadPlugin(file, overwrite)`),
więc 5 wywołań w `PluginUploadControllerTest` wymagało dopisania `, false` (kontrakt HTTP sam jest
w pełni wstecznie kompatybilny przez `@RequestParam(defaultValue = "false")` — to tylko test woła
metodę Javy bezpośrednio, nie przez Spring MVC).

Zobacz też [[project_epic28_plugin_system]], [[project_be100_plugin_registration]],
[[feedback_stale_target_classes_migrations_false_failures]].
