---
name: project-be244-review-fixes-tura83
description: BE-244 po review (tura 83) - PluginRevocation (odwolanie kontekstu), limity osieroconych per (tenant, wtyczka), klucz koalescencji do konca zadania, R1-R4, N1-N4; plus markDegradedByCircuit CAS
metadata:
  type: project
---

Poprawki po review BE-244 (3,5/5) i review `degraded_reason` (tura 83, 2026-11-28).

- **W2 `PluginRevocation`** (public final, flaga jednokierunkowa) jest KOMPONENTEM `PluginInstanceHandle` (kompaktowy ctor podstawia swieza, gdy null; stare ctory zgodne wstecz). Wspoldzielona przez `PluginContextImpl`, `PluginHttpEgressClientImpl`, `PluginDbEgressClientImpl`, `PluginLoggerImpl`, `PluginConfigImpl`; kazda metoda API robi `requireActive()` -> `PluginRevokedException`. Ustawiana: `dispose` (po onDeactivate, PRZED closeQuietly), faza 1 `unloadByVersion` (przed onDeactivate), catch onActivate/ekstrakcji w `doLoad`. `PluginConfigImpl.peek` = odczyt platformowy dla ctora kontekstu (nie rzuca).
- **W3**: `orphansByPlugin` kluczowany `tenant:pluginKey` (NIE odciskiem configu); `max-orphans-per-plugin` 2, `max-orphans-total` 8 (laczny odrzuca tylko wtyczki z >=1 sierota); odrzucenie = `PluginQuarantinedException` + `quarantine{result=orphan_limit}`; timeout onDeactivate tez zaklada kwarantanne. Autowired ctor managera ma 13 argumentow, 11-argumentowy to wygoda testowa.
- **W1**: `pendingInstallations` to `ConcurrentHashMap<String,Boolean>` (wartosc = dirty), klucz do KONCA zadania, `remove(key)` w finally zwraca dirty -> jeden dodatkowy przebieg. `pendingLoads` koalescuje ladowania.
- **R3**: `cancelAndDequeue` = cancel + `lifecycleExecutor.remove` -> stary test "kolejka sie przepelnia po timeoutach" przestal dzialac; nowy test wypelnia kolejke WSPOLBIEZNYMI loadami (czekaj `lifecycleActiveThreads()==1` zanim zasubmitujesz reszte, inaczej szybkie zadania oprozniaja kolejke).
- **N1** shed/asset_queue_full -> PENDING (503); **N2** token po wygranym putIfAbsent + `TokenBucket.release()`; joiner dostaje `RejectedExecutionException` jako przyczyne -> PENDING.
- **degraded_reason**: `updateHealthStatus` USUNIETY, jest `markDegradedByCircuit` z `AND health_status <> 'DISABLED_BY_ADMIN'`; `insert` DEGRADED bez przyczyny -> IAE.

**Why:** orphaned thread = bezpieczenstwo (kill switch nie odcinal API) i DoS wspolnej puli sterowany przez admina tenanta.
**How to apply:** nowe API platformy dostepne dla wtyczki MUSI sprawdzac `PluginRevocation`; nie kluczuj limitow odcisk-konfiguracji. Pulapka testow: testy AssertJ na kolejce wymagaja `lifecycleQueueDepth()`/`lifecycleActiveThreads()` (package-private).
