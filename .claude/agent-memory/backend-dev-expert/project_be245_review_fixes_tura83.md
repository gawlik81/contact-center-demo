---
name: be245-review-fixes-tura83
description: Poprawki po review BE-245 (tura 83) - kanoniczny sig HMAC, reload pod jednym lockiem, DEGRADED po nieudanej zmianie konfiguracji
metadata:
  type: project
---

- `WebSocketChannelSigner.decodeCanonical` odrzuca niekanoniczny base64url (bez trim); `WebSocketReplayGuard` kluczuje `canonicalSignature(sig)` - wspolne dla cc:ws:* i plugin-sync.
- R2: `applyDesiredState` przy `allowLoad=false` zwraca true BEZ unload; unload+load w jednym przebiegu na bulk executorze (stara instancja obsluguje ruch do podmiany). Nie dotyka modelu wykonawcow (BE-244).
- R1 (NIEAKTUALNE co do mechaniki, patrz W2 nizej): jeden 7-arg konstruktor z `PluginRegistrationService` (6-arg usuniety, testy z mockiem). Brak wpisu w plugin_invocation_log (wymaga extension point).
- W2 (NIEAKTUALNE co do kodowania przyczyny licznikiem - patrz project_be245_degraded_reason_v153; mechanika CAS aktualna) (tura 83, re-review 4/5): DEGRADED koordynowany W DB przez CAS, przyczyna = `consecutive_failure_count` (obwod >= `TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD`=5, config-load = 0; BEZ migracji; opcjonalna alternatywa V153 `degraded_reason`). Repo: `markDegradedByConfigLoad` (tylko HEALTHY->DEGRADED:0, KAZDA nieudana proba), `clearDegraded(includeCircuit)`, `clearCircuitDegraded`. Sync: `circuitResetPending` (resetLocalState + galaz reload) => po udanym load zdejmuje DEGRADED kazdej przyczyny; zwykly load tylko config; `observeHealthOfLoaded` raz na handle (restart). `configReloadInstallations` to Map<id,ReloadKey(versionId,checksum)>; metryki `config_reload.failed` raz/serie + `.retry`. Obwod: HEALTHY tylko `clearCircuitDegraded`, zapis DB best-effort PO publish (BE-246 R-A).
- W3: `mapRowsIsolated` -> metryka `plugin.sync.installation.skipped{reason}` (repo dostaje MeterRegistry), throw `InstallationConfigUnreadableException` gdy pominieto wszystkie(>=1) lub >50%; `findAllEnabledAcrossAllTenants` pomija nieczytelnego tenanta, rzuca gdy wynik pusty; StartupLoader lapie i loguje ERROR (start nie pada).
- Pulapki testow: `List.of(Object[])` w Javie = varargs (uzyj Arrays.asList); `plugin_version` ma tenant_id NOT NULL (V078) - IT musi wstawiac wersje per tenant; `canonicalSignature` bez trim (testy wariantow bialych znakow zmienione na padding).
- Mockito: `doNothing()` na metodzie zwracajacej wartosc (manager.load) -> `doReturn(null)`.

**Why:** review BE-245 3,5/5 (CR-BACKEND.md). **How to apply:** zmieniajac sciezke reload/sygnature pamietaj o tych kontraktach.
