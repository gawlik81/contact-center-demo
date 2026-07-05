---
name: project_be102_extension_point_publisher
description: BE-102 ExtensionPointPublisher/PluginInvocationExecutor/CircuitBreakerState — dispatch i fault containment dla pluginów per tenant (EPIC-28)
metadata:
  type: project
---

BE-102 (EPIC-28) zaimplementowany 2026-06-21: pakiet `domain.plugin.runtime` rozszerzony o
`ExtensionPointPublisher`/`Impl` (jedyny punkt dispatchu `PluginEntryPoint` w backendzie),
`PluginInvocationExecutor` (`@Configuration`, bean `pluginInvocationExecutor` — `ThreadPoolExecutor`
core=8/max=32/queue=200, `CallerRunsPolicy`, wątki daemon, ODRĘBNY od `applicationTaskExecutor`
(`AsyncConfig`) i od `lifecycleExecutor` lokalnego `PluginRuntimeManagerImpl`/BE-101),
`PluginInvocationProperties` (`@ConfigurationProperties(prefix="plugin.invocation")`:
`preContactConnectTimeoutMs`=2000, `manualActionTimeoutMs`=5000, `maxTimeoutMs`=60000 cap),
`CircuitBreakerState` (`ConcurrentHashMap<UUID, AtomicInteger>` w pamięci, próg `FAILURE_THRESHOLD=5`,
"closed on first success" bez half-open — brak Resilience4j w `pom.xml`), `InvocationStatus`
(enum lokalny SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED).

**Wzorzec wywołania blocking** (`publishPreContactConnect`/`publishManualAction`):
circuit breaker check → `TenantContext.snapshot()` na wątku wywołującym →
`pluginInvocationExecutor.submit(...)` → na wątku roboczym `TenantContext.restore(snapshot)` w
`try`, `PluginExecutionContext.runWithPluginClassLoader(handle.classLoader(), ...)` wokół wywołania
pluginu, `catch(Throwable)` (nie tylko `Exception` — plugin może rzucić `Error`),
`finally TenantContext.clear()` → `future.get(timeoutMs, MILLISECONDS)` na wątku WYWOŁUJĄCYM,
na `TimeoutException`: `future.cancel(true)` best-effort + zwrot wyniku domyślnego SDK
(`*.empty()`/`*.unsupported()`), NIGDY wyjątek do wołającego.

**Fire-and-forget** (`publishPostContactEnd`/`publishCustomerSync`/`publishDispositionSet`): w
tym tickecie tylko submit-and-forget na `pluginInvocationExecutor`, bez RabbitMQ — integracja
`cc.queue.plugin-invocation` jest zakresem BE-104 (kolejny ticket), nie zaimplementowana tutaj.

**Decyzja merge wyników wielu instalacji** (`publishPreContactConnect`): SDK
(`PreContactConnectResult`) nie definiuje semantyki łączenia wyników z wielu pluginów — zamiast
niejawnego merge mapy (ryzyko nadpisania danych jednego pluginu danymi innego), zwracany jest
wynik **pierwszej instalacji w porządku rejestracji, której wywołanie zwróciło wynik niepusty**
(`!displayData.isEmpty() || warning != null`). Każda próbowana instalacja jest mimo to w pełni
zarejestrowana w circuit breakerze/logu — "pierwszy niepusty wygrywa" determinuje tylko co
dostaje agent.

**Logowanie wywołań jest PLACEHOLDEREM SLF4J** — `recordInvocation` (private,
`ExtensionPointPublisherImpl`) loguje tylko przez `log.info`/`log.warn`. `PluginInvocationLogService`
(BE-105) JESZCZE NIE ISTNIEJE — BE-105 podmieni ciało tej jednej metody bez zmiany sygnatury czy
miejsc wołających. NIE twórz `PluginInvocationLogService` przy pracy na BE-103/BE-104 — czekać
na BE-105.

Dodano do BE-100 (`TenantPluginInstallationRepository`/`PluginRegistrationService`/`Impl`):
`updateHealthStatus(tenantId, installationId, healthStatus, consecutiveFailureCount)` — natywny SQL
UPDATE, wołane wyłącznie przez `CircuitBreakerState`, best-effort (nie rzuca gdy instalacja nie
istnieje, loguje warn i zwraca `false`).

Testy: `CircuitBreakerStateTest` (6), `ExtensionPointPublisherImplTest` (12 — **executor realny
(`Executors.newCachedThreadPool()`), NIE mockowany**, bo kryteria akceptacji (timeout ~2s,
brak leaku TenantContext między tenantami na tym samym executorze) wymagają faktycznego przejścia
przez granicę wątku). Projekt NIE ma Awaitility — do polling w testach asynchronicznych użyj
prostej pętli `while` z `Thread.sleep` (helper `awaitTrue` w teście), nie dodawaj nowej zależności
dla jednego testu.

Weryfikacja: `mvn verify -pl app` ✅ (1236 testów, 0 failures, 0 errors, BUILD SUCCESS).

Blokuje BE-103 (integracja w przepływie połączenia), BE-104 (RabbitMQ fire-and-forget), BE-105
(`PluginInvocationLogService`).
