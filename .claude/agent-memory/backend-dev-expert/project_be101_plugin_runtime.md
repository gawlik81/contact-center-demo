---
name: project_be101_plugin_runtime
description: BE-101 PluginRuntimeManager/PluginClassLoader/PluginContextImpl (EPIC-28, RT-10) — jądro izolacji wykonania pluginów, classloader bug i fix
metadata:
  type: project
---

BE-101 dodał pakiet `domain.plugin.runtime` w module `app`
(`backend/app/src/main/java/com/contactcenter/domain/plugin/runtime/`): `PlatformApiClassLoader`
(singleton JVM-wide), `PluginClassLoader` (`extends URLClassLoader`, jeden per
`(tenant_id, plugin_key)`), `PluginContextImpl` (jedyny obiekt przekazywany do pluginu,
`tenantId` zamrożony w konstruktorze), `PluginRegistry`/`Impl`, `PluginRuntimeManager`/`Impl`
(`load`/`unload`), `PluginInstanceHandle` (record), `PluginHttpEgressClientImpl`/
`PluginLoggerImpl`/`PluginConfigImpl`.

**Status:** zaimplementowane, otestowane, i code review przez `senior-code-reviewer` zakończony
2026-06-20 z werdyktem **NO-GO** (2 blokujące: Critical TCCL leak, High temp file leak) — oba
naprawione tego samego dnia, status w `TASKS-BACKEND.md`/`PROGRESS.md` ✅. `mvn verify -pl app`
✅ 1218 testów (1216 + 2 nowe regresyjne). Pełny raport NO-GO + aktualizacja po fixie w
`/home/pawelm/contact-center/CR-BACKEND.md` (sekcja "Review: BE-101" + "Aktualizacja po fixie").

**Fix Critical (TCCL leak) — `PluginExecutionContext`:** `lifecycleExecutor`
(`Executors.newCachedThreadPool`) tworzy wątki, które dziedziczą domyślnie Thread-Context
ClassLoader (TCCL) od wątku tworzącego (classloader aplikacji) — `entryPoint.onActivate`/
`onDeactivate` wywoływane na tym wątku BEZ ustawienia TCCL na `PluginClassLoader` pozwalało
kodowi pluginu przez `Thread.currentThread().getContextClassLoader()` +
`Class.forName(hostClassName, true, tccl)` całkowicie obejść `PlatformApiClassLoader`. Fix: nowa
klasa `domain.plugin.runtime.PluginExecutionContext.runWithPluginClassLoader(ClassLoader,
Callable<T>)` — snapshot/set/restore TCCL (wzorzec identyczny do `TenantContext.snapshot/
restore/clear`), opakowuje WSZYSTKIE wywołania kodu pluginu na wątku roboczym w
`PluginRuntimeManagerImpl` (obecnie `onActivate`+`onDeactivate`, BE-102/`PluginInvocationExecutor`
musi reużyć tę samą metodę dla każdego hooka rozszerzeń). Defense in depth:
`PluginBytecodeScanner.BLOCKED_METHOD_CALLS` += `Thread#get/setContextClassLoader`,
`BLOCKED_OWNER_PREFIXES` += `java/util/ServiceLoader`.

**Why ważne dla przyszłych ticketów:** to jest **realna**, nie tylko teoretyczna ścieżka ucieczki
z sandboxa — zweryfikowana empirycznie testem (`onActivateRunsWithPluginClassLoaderAsThreadContextClassLoader`
w `PluginRuntimeManagerImplTest`), który faktycznie failuje bez fixu
(`"FOUND:com.contactcenter.app.ContactCenterApplication"`) i przechodzi z fixem (`"NOT_FOUND"`).
**Każdy nowy executor/wątek roboczy, który w przyszłości wywoła kod pluginu (BE-102 i dalej) MUSI
opakować wywołanie w `PluginExecutionContext.runWithPluginClassLoader`** — łatwo o tym zapomnieć
przy dodawaniu nowego punktu wejścia (np. async extension point dispatch).

**Fix High (wyciek plików tymczasowych):** `PluginInstanceHandle` (record) ma nowe pole
`localJarPath` (`Path`). `unload()`: `deleteQuietly(handle.localJarPath())` PO
`closeQuietly(handle.classLoader())` — kolejność krytyczna, `URLClassLoader` musi zwolnić uchwyt
pliku przed `Files.deleteIfExists` (inaczej ryzyko "zombie file" na niektórych OS). Ścieżki błędu
w `load()` (instancjonowanie/`onActivate` rzuca) też czyszczą plik przed propagacją wyjątku.
`deleteQuietly` best-effort — log warn, nigdy nie przerywa load/unload.

**Fix Medium (opcjonalny, zaadresowany) — `PluginLoggerImpl`:** truncate 4000 znaków + escape
`\r`/`\n` przed przekazaniem do SLF4J (mitygacja log-forging/log-flood). Pełna naprawa (zapis do
`plugin_invocation_log`) pozostaje BE-102.

**Bug krytyczny znaleziony i naprawiony podczas implementacji — `ClassCastException` przez
duplikat tożsamości typu:** pierwsza wersja `PlatformApiClassLoader.findClass()` czytała bajty
`.class` z `com.contactcenter.pluginsdk.*` przez `getResourceAsStream` na parencie i wołała
WŁASNY `defineClass(name, bytes, ...)` — to tworzyło DRUGI, odrębny obiekt `Class` dla np.
`PluginEntryPoint`, różny od tego, którego używa `PluginRuntimeManagerImpl` (zaczerpniętego z
classloadera aplikacji). Efekt: `(PluginEntryPoint) instance` w `load()` rzucał
`ClassCastException` ("loader constraint violation") przy KAŻDYM instancjonowaniu pluginu —
test ten ujawnił błąd natychmiast (`cannot be cast to class ... PluginEntryPoint is in unnamed
module of loader 'app'`).

**Fix:** `PlatformApiClassLoader.loadClass(name, resolve)` dla nazw w dozwolonym prefiksie
deleguje do `super.loadClass(name, resolve)` (czyli finalnie do classloadera aplikacji) —
**nigdy** `defineClass` własnoręcznie. To zachowuje identyczną tożsamość typu po obu stronach
granicy (plugin i `PluginRuntimeManagerImpl` widzą TEN SAM obiekt `Class` dla
`PluginEntryPoint`/`PluginContext`), a izolacja pozostaje pełna, bo `loadClass` filtruje nazwy
PRZED jakimkolwiek wywołaniem `super.loadClass` — żądanie poza `com.contactcenter.pluginsdk.*`
(i poza bootstrap JDK `java.*`/`javax.*`/`jdk.*`) nigdy dociera do parenta.

**Why:** to jest fundamentalna właściwość JVM classloaderów (nie bug specyficzny dla tego
projektu) — dwie definicje tej samej nazwy klasy w różnych classloaderach są zawsze dwoma
różnymi, niekompatybilnymi typami dla `instanceof`/cast, nawet z identycznym bytecode.

**How to apply:** BE-102 (`ExtensionPointPublisher`/`PluginInvocationExecutor`) będzie
wielokrotnie wołać `entryPoint.onXxx(pluginContext, event)` przez referencję `PluginEntryPoint`
zaczerpniętą z classloadera aplikacji — musi działać poprawnie bez dodatkowych zmian, bo fix
gwarantuje tożsamość typu raz na zawsze przy `load()`. Jeśli ktoś w przyszłości "zoptymalizuje"
`PlatformApiClassLoader` z powrotem do `defineClass`, ten sam bug wróci — test
`PlatformApiClassLoaderTest`/`PluginRuntimeManagerImplTest$LoadTests.loadsAndActivatesValidPlugin`
złapie regresję.

**Inny problem testowy (nie produkcyjny) — Mockito a WeakReference/GC test:** test
`classLoaderIsGarbageCollectibleAfterUnload` (`WeakReference` + `System.gc()` w pętli) początkowo
fałszywie wykazywał "wyciek" — przyczyną nie był kod produkcyjny, a **Mockito**: pole `@Mock
PluginRegistry pluginRegistry` przechowuje historię WSZYSTKICH inwokacji (łącznie z argumentem
`handle` przekazanym do `register(handle, ...)`) żeby `verify()` mogło działać — ta historia jest
silną referencją niezależną od `PluginRuntimeManagerImpl`. Fix: `Mockito.clearInvocations(...)`
na wszystkich mockach PO akcie load+unload, PRZED sprawdzeniem `WeakReference`. Wzorzec do
reużycia w każdym przyszłym teście GC-eligibility z mockami Mockito w tym projekcie.

**Inne decyzje architektoniczne BE-101:**
- `PluginCatalogQueryService`/`Impl` — nowy, mały publiczny port w `domain.plugin` (nie
  `domain.plugin.runtime`) z dwiema metodami (`findVersionById`, `findInstallation`),
  wprowadzony bo `PluginVersionRepository`/`TenantPluginInstallationRepository` są
  package-private w `domain.plugin` (BE-098/BE-100) — wzorzec delegacji analogiczny do
  `CustomerService.findById`/`ContactService.findContactEntity`.
- `CustomerService.updateCustomFields(customerId, tenantId, Map)` — nowa metoda (zastępuje CAŁY
  `custom_fields`, caller przygotowuje finalną mapę) — `PluginContextImpl.updateCustomerFields`
  woła ją po ręcznym scaleniu istniejących pól z nowym namespace `plugins.<pluginKey>`.
- `entryPointClass` odczytywany jako raw `Map<String,Object>` z `PluginVersion.manifestJson`
  (NIE parsowany do `PluginManifest` — ten record jest package-private w `domain.plugin`,
  niedostępny z `domain.plugin.runtime`).
- Lokalny cache JAR-a: `Files.createTempFile("plugin-runtime-", ".jar")` — wzorzec identyczny do
  `PluginValidationServiceImpl`/`RecordingServiceImpl` (brak dedykowanego katalogu scratch w
  projekcie), plik NIE jest usuwany po `load()` w tej implementacji (znane ograniczenie, do
  rozważenia w code review — możliwy wyciek plików tymczasowych przy wielu load() w czasie).
- `RuntimeTestPluginBuilder` (test-only, w `domain.plugin.runtime`) generuje przez ASM REALNE,
  ładowalne i wykonywalne klasy `PluginEntryPoint` (w odróżnieniu od `TestJarBuilder`/BE-098,
  który generuje bytecode tylko do analizy statycznej ASM, nigdy ładowany).

Zobacz [[project_be097_plugin_sdk]], [[project_be098_plugin_validation]],
[[project_be100_plugin_registration]]. Blokuje BE-102/BE-106.
