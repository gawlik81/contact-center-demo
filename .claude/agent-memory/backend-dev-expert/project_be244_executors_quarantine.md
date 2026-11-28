---
name: project-be244-executors-quarantine
description: BE-244 (EPIC-33) - 5 executors of PluginRuntimeSyncServiceImpl, asset rate limit (W2), onActivate quarantine, unload dispose under lock; test pitfalls
metadata:
  type: project
---

BE-244 (tura 83): model wykonawców `PluginRuntimeSyncServiceImpl` = `event` (szybki unload), `load` (priorytetowy: reload/enable po zdarzeniu), `bulk` (1 wątek: reconcileAll + requestTenantSync), `asset` (publiczny lazy load + `TokenBucket` PRZED skanem), `revoke`. Pełna kolejka = `plugin.sync.dropped{reason}` (`sync_queue_full`, `load_queue_full`, `asset_queue_full`, `revoke_queue_full`) + log, rekoncyliacja nadrabia. Ticket mówił `ignored{reason}`, ale realna metryka to `dropped`.

Manager: `unload` robi detach+dispose pod JEDNYM lockiem `loadLocks` (load nie wyprzedza); `unloadByVersion` (kill switch) świadomie bez locka. `invokeWithTimeout` rozróżnia `PluginLifecycleCapacityException` (RejectedExecution albo zadanie nie wystartowało = stan `CB_NOT_STARTED`) od `PluginActivationTimeoutException` (wystartował) i `PluginQuarantinedException`; kwarantanna per instalacja kluczowana (wersja, odcisk konfiguracji), TTL x2 przy powtórzeniu, kasowana udaną aktywacją; gauge `plugin.lifecycle.orphaned`.

**Why:** review BE-178 N2/R2 + W2 z review BE-246 (publiczny endpoint głodził bulk = opóźniony kill switch).

**How to apply / pułapki:**
- Anulowane (cancel) zadanie, które nie dostało wątku, ZOSTAJE w kolejce puli (ghost) aż wątek się zwolni - kolejka 64 da się przepełnić timeoutami; test `fullQueueIsRejectedAsCapacityNotTimeout`.
- Test osieroconego wątku wymaga bytecode ignorującego interrupt (`RuntimeTestPluginBuilder.buildJarThatSpins`) - `Thread.sleep` kończy się po `cancel(true)`.
- Mockito: `thenReturn(List.of(enabledInstallation(...)))` gdzie helper robi `when(...)` = UnfinishedStubbing; wyciągnij zmienną.
- Test wątków wykonawców po nazwie (`plugin-sync-load-`) w `PluginRuntimeSyncServiceImplTest` zmieniony z bulk na load.
- Kontrola negatywna przez mutację (podmiana `loadExecutor`/`assetExecutor` na `bulkExecutor`) wykazała, że testy `Executors*` faktycznie łapią regresję.
- Rate limit globalny, nie per IP (nagłówki źródła podrabialne); odpowiedź 503 niezależna od istnienia id.
