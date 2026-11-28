---
name: project-be246-circuit-halfopen-jar-checksum
description: BE-246 (EPIC-33, tura 83) - half-open obwodu, lazy load PluginAssetController po id, checksum JAR przy load/rekoncyliacji, rolling deploy tylko w DEPLOYMENT.md; decyzje i pulapki
metadata:
  type: project
---

Decyzje (2026-11-28, wlasciciel): half-open po `plugins.circuit-breaker.open-ttl` (60s, `@Value` String + `DurationStyle.detectAndParse` - dziala w kazdym kontekscie, bez beana properties); BEZ adopcji DEGRADED z DB; half-open NIE dotyczy REVOKED; rolling deploy = tylko DEPLOYMENT.md (bez wersjonowania koperty).

- `CircuitBreakerState`: jedna mapa `gates` (Gate: openedAtNanos + AtomicLong dzierzawa proby) zastapila `remoteOpen`; `isOpen` (USUNIETE po review - patrz project_be246_review_fixes_tura83: tryAcquire/Permit) mialo efekt uboczny; wynik proby = `recordResult` (porazka: reopen + publish OPENED; sukces: remove + CLOSED + HEALTHY takze gdy obwod otworzyla INNA instancja). Dzierzawa wygasa po kolejnym TTL. Konstruktor testowy z `LongSupplier` zegara.
- Checksum: `PluginJarIntegrity` (sha256 bajtow / pliku, `statOf`, `localCopyChanged`). `load()` odmawia JAR niezgodnego z `plugin_version.checksum_sha256` PRZED zapisem na dysk. Handle ma +3 pola (jarChecksumSha256, jarSizeBytes, jarModifiedMillis; 11-arg ctor zgodny wstecz). `applyOverwrite` zmienia checksum + jar_object_key tego samego wiersza -> wykrycie = porownanie napisow handle vs katalog (bez I/O); stat lokalnej kopii co przebieg, hash tylko przy zmianie stat (`jarStatAccepted`).
- `PluginRuntimeSync.ensureInstallationLoaded(installationId)` -> `InstallationLoad{LOADED|NOT_FOUND|PENDING}`; tenant z `PluginCatalogQueryService.findEnabledInstallationAcrossTenants` (pętla po tenantach, O(tenanci) - ograniczone cache negatywnym, in-flight cap 8, wspolne CompletableFuture per id, timeout 3s -> 503 Retry-After). Wykonuje na `bulkExecutor` przez `reconcileInstallation(..., allowLoad=true, honorGrace=false)`.

**Why:** pulapki: (1) fixtury z `checksumSha256("0".repeat(64))` przestaja dzialac - fixture MUSI miec prawdziwy SHA-256 bajtow zwracanych przez mock `downloadJar`; (2) test half-open miedzy instancjami: pierwszy `isOpen` po TTL zabiera dzierzawe, wiec "zamkniete" asercjuj dwoma kolejnymi odczytami; (3) `PluginRuntimeSync` ma nowa metode - mocki OK, ale kazda reczna implementacja `PluginCatalogQueryService` (FakeCatalog w IT) musi dodac `findEnabledInstallationAcrossTenants`.
**How to apply:** BE-244 (wykonawcy/dispose) dotyka tych samych klas - nie lamie ctora handle ani semantyki `isOpen`; nowe sciezki wolajace `isOpen` MUSZA zawsze zarejestrowac wynik (`recordResult`).
