---
name: be228-shedlock-observability
description: BE-228 - MeteredLockProvider (metryki ShedLock), wersja argMax ze zrodla w ClickHouseDwWriter, sprzatanie wierszy shedlock; pulapki providera JDBC
metadata:
  type: project
---

`MeteredLockProvider` (infrastructure/lock) dekoruje JdbcTemplateLockProvider: `scheduler.lock.attempts{lock,result=acquired|busy|error}` i `scheduler.lock.expired{lock,outcome=taken_over|not_taken|unknown}`. Tag `lock` = typ (UUID na koncu odciety). Provider JDBC budowany z `withThrowUnexpectedException(true)` TYLKO po to, by dekorator widzial polykane bledy (DataIntegrity/BadSqlGrammar/UncategorizedSQL/TransactionSystem) - mapuje je z powrotem na "nie zalozono"; inne wyjatki propaguje jak dotad.

**Why:** goly provider przy `throwUnexpectedException=false` zamienia awarie INSERT/UPDATE wiersza blokady w to samo co "zajeta" (tylko log). Przejecie po wygasnieciu wykrywane u pierwotnego wlasciciela przy unlock (held > lockAtMostFor, odczyt locked_by), bez zapytania na kazda probe.

**How to apply:**
- `scheduler.lock.skipped{type}` (BE-173) zostaje: poziom PRZEBIEGU (dialer = advisory lock, nie ShedLock); `attempts{busy}` liczy PROBY (retry routingu co 50 ms) - nie dublowac.
- `ShedLockConfig.jdbcLockProvider(ds)` = goly provider (testy), `meteredLockProvider(ds, registry)` = produkcyjny; bean uzywa ObjectProvider<MeterRegistry> (fallback SimpleMeterRegistry dla malych kontekstow testowych).
- argMax: `ContactDwRow/CampaignDwRow.sourceUpdatedAt` (konstruktor wsteczny bez niego = zegar zapisu); DateTime64(3) = remis w tej samej ms; wymiary zostaja na snapshot_at=now().
- `ShedLockOrphanCleanupJob`: kasuje RoutingQueue_%/EmailPolling_pollInbox_% starsze niz 7 dni; po DELETE `clearCache()` providera (StorageBasedLockProvider trzyma WeakHashMap "wiersz istnieje" - bez tego zapamietana nazwa nie robi INSERT). Bez migracji (V149 ma DELETE).
- Nowy `@Scheduled` => aktualizuj: wiersz w tabeli `documentation/tech/04-backend.md` 4.6, `FullApplicationContextStartupTest` (lista beanow + liczba 27), `SchedulerLockCoverageTest` sprawdza tabele.
