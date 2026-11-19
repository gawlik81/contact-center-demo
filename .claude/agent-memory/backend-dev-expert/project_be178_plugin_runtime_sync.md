---
name: project-be178-plugin-runtime-sync
description: BE-178 (EPIC-33) - sync runtime wtyczek (registry/handles/circuit breaker) między instancjami; decyzje, pułapki, co celowo NIE zrobione
metadata:
  type: project
---

Synchronizacja `PluginRegistry`/`PluginRuntimeManager`/`CircuitBreakerState` między replikami (pakiet `domain.plugin.runtime`:
`PluginSyncBus`, `PluginSyncEnvelope`, `PluginSyncProperties`, `PluginSyncConfig`, `PluginRuntimeSync` + `PluginRuntimeSyncServiceImpl`).

- DB = źródło prawdy; Redis pub/sub (`cc:plugin:sync:<env>`) to tylko podpowiedź "przeczytaj DB" (stan, nie delta) -> zgubienie/duplikat/zmiana kolejności nieszkodliwe.
- Rekoncyliacja `@Scheduled syncTick` na KAŻDEJ instancji BEZ ShedLock (wpis w `UNLOCKED_BY_DESIGN` w `SchedulerLockCoverageTest` + wiersz #26 klasy I w `documentation/tech/04-backend.md` §4.6 - test czyta ten dok!).
- Circuit breaker: licznik lokalny + propagacja OPEN/CLOSED; NIE adoptujemy `DEGRADED` z DB (obwód nie ma half-open, restart to jedyne odblokowanie - **follow-up: half-open/probe**).
- `unload()` wyrejestrowuje PRZED `onDeactivate`; `load()` idempotentne + striped lock per instalacja; `unloadByVersion` dwufazowe + lokalny zbiór odwołanych wersji (wyścig z load w toku).
- Leniwe ładowanie: konsument RabbitMQ synchronicznie (`ensureTenantLoaded`, cooldown per tenant, backoff błędów), ścieżki z budżetem 2 s tylko `requestTenantSync` w tle. Niezaładowalna instalacja = kontrolowany FAILED w logu, bez requeue (DLQ flood).
- `TenantContext` w pracy sync: `withTenant` ZAPISUJE i PRZYWRACA kontekst wołającego (konsument RabbitMQ ma własny - bare `clear()` zabronione).
- `PluginRegistryImpl` na `CopyOnWriteArrayList` (wcześniej ArrayList mutowana + `List.copyOf` bez locka = CME przy współbieżności).

**Why:** ticket P0 bramki ">1 replika"; **How to apply:** przy kolejnych zmianach cyklu życia wtyczki publikuj `PluginRuntimeSync.installationChanged` PO commicie DB (kontrolery); nowy @Scheduled => sprawdź SchedulerLockCoverageTest i tabelę w 04-backend.md.
