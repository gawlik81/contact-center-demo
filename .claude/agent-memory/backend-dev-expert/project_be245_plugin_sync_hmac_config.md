---
name: project-be245-plugin-sync-hmac-config
description: BE-245 (EPIC-33, tura 83) - HMAC koperty plugin-sync (wspolny signer z WS), odcisk konfiguracji w handle, installationConfigChanged, R4/R6; decyzje i pulapki
metadata:
  type: project
---

Decyzje (2026-11-28, wlasciciel): sekret WSPOLNY z `cc:ws:*` (`WebSocketChannelSigner`/`WebSocketHmacProperties`, bez nowej zmiennej; signer NIE przeniesiony - `PluginSyncBus` wola go z `infrastructure.websocket`, tag metryki `channel=plugin-sync`); zmiana `installationConfig` = przeladowanie wtyczki na KAZDEJ instancji; rolling deploy tylko dokumentacja (BE-246).

- `PluginSyncEnvelope.sig` + `canonicalForSigning()`; w `onMessage` kolejnosc: rozmiar -> JSON -> wersja -> ksztalt (src bez NUL) -> self -> verify -> replay (`WebSocketReplayGuard`, teraz public) -> listener. Odrzucenia: `plugin.sync.ignored{reason}` + wspolne `ws.channel.signature.rejected`.
- Rewizja konfiguracji = `PluginConfigFingerprint` (SHA-256 odszyfrowanego `installation_config`) w `PluginInstanceHandle.configFingerprint` (11. pole, konstruktor zgodny wstecz z 10 argumentami, null = nie porownuj). NIE `updated_at` (przesuwane przez health_status) i NIE szyfrogram (losowy IV). Bez migracji.
- `PluginRuntimeSync.installationConfigChanged` (wolane przez `PluginAdminController.updateConfig`): reset lokalny + po commicie publish + `scheduleReconcile` lokalnie (wlasne zdarzenie jest ignorowane jako `self`, wiec instancja admina musi przeladowac sama).
- R6: `afterCommit(...)` rejestruje tez `afterCompletion(STATUS_COMMITTED)` z `AtomicBoolean` (Spring iteruje snapshot synchronizacji - rejestracja z afterCommit innej nie dostaje afterCommit, ale dostaje afterCompletion). Test na prawdziwym `AbstractPlatformTransactionManager`.
- R4: w `reportUnloadableInstallations` limit raportu przed `deserializePayload` (test ze spy ObjectMapper).

**Why:** pulapki testowe: (1) w IT wspolne repliki - asercje na licznikach tylko jako delta; (2) w teście jednostkowym z mockiem managera `unload` nie usuwa handle, wiec druga rekoncyliacja (bulk) wola unload ponownie - stubuj `findActiveHandle` po unload; (3) `PluginSyncBus` wymaga teraz beana `WebSocketChannelSigner` (+ `WebSocketHmacProperties`) w kazdym kontekscie IT.
**How to apply:** nowe pole konfiguracji/uprawnien wplywajace na `onActivate` -> dolacz do odcisku; kolejne tickety w tych klasach (BE-246, BE-244) nie lamia konstruktora handle.
