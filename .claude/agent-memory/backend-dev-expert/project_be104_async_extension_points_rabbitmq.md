---
name: project_be104_async_extension_points_rabbitmq
description: BE-104 — POST_CONTACT_END/CUSTOMER_SYNC/DISPOSITION_SET przez RabbitMQ (cc.queue.plugin-invocation), PluginInvocationConsumer, wspólny PluginInvocationLogger/CircuitBreakerState z BE-102
metadata:
  type: project
---

BE-104 (EPIC-28) zrealizowane 2026-06-21: `ExtensionPointPublisherImpl.publishPostContactEnd`/`publishCustomerSync`/`publishDispositionSet`
(placeholder submit-and-forget z BE-102) zastąpione publikacją do RabbitMQ `cc.queue.plugin-invocation`.

**Why:** ARCHITECTURE.md §11.5 wymaga, żeby te 3 extension pointy były fire-and-forget przez kolejkę, nie
in-process submit — odseparowanie latencji pluginu od żądania agenta całkowicie (nie tylko async w ramach
tego samego procesu).

**Kluczowe decyzje:**
- `PluginInvocationMessage` (record) **bez `installationId`** — lookup wszystkich instalacji na extension point
  dzieje się w `PluginInvocationConsumer` w momencie konsumpcji, nie w publisherze. `eventPayload` jako
  `Map<String,Object>`, nie jako konkretny rekord SDK — Jackson nie wie w punkcie deserializacji wiadomości,
  który z 3 rekordów (`ContactEvent`/`CustomerSyncRequest`/`DispositionEvent`) zastosować, bo to zależy od
  `extensionPoint` będącego polem tego samego payloadu. Konsument robi `objectMapper.convertValue(map, Class)`
  na podstawie `extensionPoint`.
- **`PluginRegistry` (w pamięci) NIE odzwierciedla `enable()`/`disable()` w DB** — `PluginRegistrationServiceImpl.disable()`
  tylko zmienia flagę `enabled` w `tenant_plugin_installation`, nie woła `PluginRegistry.unregister()`. Dlatego
  `PluginInvocationConsumer` MUSI jawnie dociągnąć `PluginRegistrationService.getInstallation(tenantId, installationId)`
  i sprawdzić `enabled` przed wywołaniem pluginu, inaczej `SKIPPED_DISABLED` (kryterium akceptacji) nigdy by nie zaszło.
- `CircuitBreakerState` jest **współdzielony** między `ExtensionPointPublisherImpl` (blocking) i `PluginInvocationConsumer`
  (async) — ten sam bean, bo jest indeksowany tylko po `installationId`, niezależnie od ścieżki wywołania.
- Logowanie wydzielone do `PluginInvocationLogger` (statyczna metoda `record`, package-private) — wspólne dla
  publishera i konsumenta, żeby BE-105 (`PluginInvocationLogService`) podmieniał ciało w jednym miejscu.
- Retry/DLQ: **brak konfiguracji per-kolejka** — reużyty globalny `spring.rabbitmq.listener.simple.retry`
  (`application-dev.yml` max-attempts=3, `application-prod.yml` max-attempts=5), identycznie jak wszystkie inne
  konsumenty domenowe (`AuditLogConsumer` itd.). DLQ deklarowana w nowym `infrastructure/config/RabbitMqPluginConfig.java`
  (osobny plik od `RabbitMQConfig`, stałe `QUEUE_PLUGIN_INVOCATION`/`RK_PLUGIN_INVOCATION` w `RabbitMQConfig`).
- Wyjątek/`Error` rzucony PRZEZ PLUGIN jest złapany WEWNĄTRZ konsumenta (catch Throwable, jak w BE-102) — nigdy
  nie dociera do Spring AMQP jako nack. Tylko błąd `PluginRegistry.lookup`/`PluginRegistrationService.getInstallation`
  (infrastruktura) propaguje się do nack/retry/DLQ.

**Pliki:** `domain/plugin/runtime/PluginInvocationMessage.java`, `PluginInvocationConsumer.java` (extends
`TenantAwareConsumer`), `PluginInvocationLogger.java`, `infrastructure/config/RabbitMqPluginConfig.java`,
zmiany w `ExtensionPointPublisherImpl.java`/`ExtensionPointPublisher.java`/`PluginInvocationProperties.java`
(nowe pole `asyncInvocationTimeoutMs`=30000ms).

**Testy:** `ExtensionPointPublisherImplTest$FireAndForgetTests` przepisany (5, weryfikacja publikacji RabbitMQ),
`PluginInvocationConsumerTest` (nowy, 13). `mvn verify -pl app`: 1274 testy, 0 failures, BUILD SUCCESS (+16 vs BE-103: 1258→1274).

Odblokowuje BE-105 (`PluginInvocationLogService` + REST historii wywołań).
