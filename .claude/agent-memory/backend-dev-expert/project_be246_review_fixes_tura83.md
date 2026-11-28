---
name: project-be246-review-fixes-tura83
description: Poprawki po review BE-246 (tura 83) - Permit/tryAcquire/release w CircuitBreakerState, korelacja próba-wynik, jitter TTL, weryfikacja JAR przed unload, limit czekających, Caffeine dla cache negatywnego
metadata:
  type: project
---

Zmiany po review BE-246 (3,5/5), uzupelniaja [[project-be246-circuit-halfopen-jar-checksum]] (ten opis `isOpen` z efektem ubocznym jest NIEAKTUALNY):

- `CircuitBreakerState`: `isOpen` USUNIETE. `tryAcquire(id)` -> `Permit(installationId, kind CLOSED|PROBE|DENIED, probeToken)`; `isOpenReadOnly(id)` bez efektu; `release(permit)` idempotentne (zwalnia tylko wlasna dzierzawe po tokenie); `recordResult(Permit, tenant, success)`. Skrot `recordResult(tenant, id, success)` = permit CLOSED. Wynik CLOSED przy juz otwartym obwodzie (stary in-flight) NIE zamyka ani nie odnawia TTL; wynik PROBE z nieaktualnym tokenem ignorowany. Zamyka obwod TYLKO udana proba.
- Jitter TTL: `plugins.circuit-breaker.open-ttl-jitter` (0.1), tylko w ctorze @Autowired i 5-arg; ctor testowy `(pcs, bus, Duration, LongSupplier)` ma jitter 0 (deterministyczne testy). W IT z TTL=2s ustawiaj `open-ttl-jitter=0`.
- Wolajacy (ExtensionPointPublisherImpl.invokeBlocking, PluginInvocationConsumer): `permit = tryAcquire`, caly kawalek po tym w try/finally z `release(permit)` bezwarunkowo (idempotentne po recordResult).
- W3: `PluginRuntimeManager.verifyJarBeforeReload` (default no-op) - pobiera+weryfikuje checksum, zachowuje JAR w `preparedJars` (zuzywany przez nastepny `load`, bez 2. pobrania; niezgodny z katalogiem w chwili load = pobranie od nowa). Sync: `newJarVerified` PRZED unload w galezi reload; odmowa = stary handle + `plugin.sync.reload.refused` + backoff `reloadRefusedUntil` (kasowany w resetLocalState). REVOKED/disable nie przechodza weryfikacji.
- N: `forgetLocalState` (syncLoaded, jarStatAccepted, reloadRefusedUntil, circuitBreaker.forget) we WSZYSTKICH galeziach unload; `circuitBreaker.retainOnly(loaded)` w doReconcileAll; `onCircuitEvent` juz wczesniej ignorowal zdarzenia dla niezaladowanych (review mylil sie co do tworzenia bramki - zostala tylko wyscig po unload, lata go retainOnly). Cache negatywny = Caffeine (maximumSize 10k, expireAfterWrite=lazy-sync-cooldown). Limit czekajacych `assetLazyLoadMaxWaiters` (4) per id -> od razu PENDING (503), metryka `result=waiters_shed`.

**Why:** pulapki: (1) w testach Mockito `mock(ExecutorService)` + `doReturn(cancelledFuture).when(exec).submit(any(Callable))` symuluje CancellationException; (2) test jittera: binary search po czasie z `release(permit)` po kazdym tryAcquire, inaczej wlasna dzierzawa blokuje; (3) test integracyjny z baza (Testcontainers) dla `findEnabledInstallationAcrossTenants` pominiety (zakaz dockera) - zastapiony testem jednostkowym petli + testem repo, ze set_tenant_context dostaje tenantId z argumentu.
**How to apply:** W2 (osobny executor/rate limit lazy load assetow) zostaje dla BE-244 - nadal dzieli `bulkExecutor` z rekoncyliacja. Nowe sciezki wolajace obwod MUSZA uzywac `tryAcquire` + `release` w finally.

Tura 83 (re-review BE-246, 4/5): W-A `discardPreparedJar` (default no-op w interfejsie) wolane w `finally` galezi reload w sync + `@PreDestroy`; CELOWO NIE w `unload` (reload = verify->unload->load zuzywa plik). N-1 waiters: `ConcurrentHashMap<UUID,Integer>` + `compute` (`tryAcquireWaiter/releaseWaiter`). N-4: CAS na dzierzawie PRZED ustawieniem ttl/openedAt w recordProbeResult. Porazka probe ma licznik FAILURE_THRESHOLD+1 (counter nie jest zerowany przy otwarciu).
