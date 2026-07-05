---
name: project_be105_invocation_log
description: BE-105 PluginInvocationLogService + REST historii wywołań pluginu — złożony PK partycjonowanej tabeli, redakcja PII, usunięcie placeholdera PluginInvocationLogger (EPIC-28)
metadata:
  type: project
---

BE-105 (EPIC-28) zaimplementowany 2026-06-22 — domknięcie logowania wywołań pluginu
zapoczątkowanego placeholderem SLF4J w BE-102/104.

**Złożony PK partycjonowanej tabeli (`plugin_invocation_log`, V077, DB-045):**
`PluginInvocationLog` (`domain.plugin`) używa `@IdClass(PluginInvocationLogId.class)` z dwoma
polami `@Id` (`id` UUID, `invokedAt` Instant) — wzorzec 1:1 skopiowany z `AuditLog`/`AuditLogId`
(V004), jedynej innej encji partycjonowanej po kolumnie czasowej w projekcie (zob.
[[feedback_partitioned_table_jpa]]). Zapis przez natywny `INSERT` w
`PluginInvocationLogRepository.insert` (JPA nie wspiera standardowych INSERT-ów na tabelach
partycjonowanych z PK obejmującym kolumnę partycjonowania), odczyt przez JPQL z `PageImpl` +
`setFirstResult`/`setMaxResults` (Hibernate odpytuje tabelę nadrzędną, automatyczne
przekierowanie do partycji) — wzorzec paginacji 1:1 skopiowany z `EmailMessageRepository`
(`COUNT` + content query osobno, nie `Page<T>` natywnie ze Spring Data, bo JPQL na encji
z `@IdClass` w tym projekcie nie korzysta z `JpaRepository`, tylko z ręcznego `EntityManager`).

**Widoczność repozytorium — wyjątek od wzorca BE-100:** `PluginInvocationLogRepository` jest
**publiczne** (w przeciwieństwie do `TenantPluginInstallationRepository`, package-private) —
wymuszone przez strukturę plików ticketu: repo żyje w `domain.plugin`, ale jedyny konsument
(`PluginInvocationLogServiceImpl`) żyje w `domain.plugin.runtime` (inny pakiet). Reguła do
zapamiętania: widoczność repo zależy od tego, gdzie leży jedyny konsument, nie od domyślnej
konwencji package-private.

**Usunięcie `PluginInvocationLogger` (placeholder SLF4J, BE-102/104):** zastąpiony bezpośrednim
DI `PluginInvocationLogService` w `ExtensionPointPublisherImpl` i `PluginInvocationConsumer` —
żadna pośrednia klasa nie została zachowana. Sygnatura `record(...)` rozszerzona względem
placeholdera o `relatedContactId`/`requestPayload` — wymagało przeniesienia payloadu
(`ContactEvent`/`ManualActionRequest`/`DispositionEvent`) przez `invokeBlocking`/
`processOneInstallation` do punktu wołania `record`. W `PluginInvocationConsumer`
deserializacja payloadu (`deserializePayload`) musiała zostać przesunięta na SAM POCZĄTEK
`processOneInstallation` (wcześniej działa się tylko bezpośrednio przed wywołaniem pluginu) —
inaczej `relatedContactId`/`requestPayload` nie byłyby dostępne dla ścieżek
`SKIPPED_DISABLED`/`CIRCUIT_OPEN`, które kończą metodę przed tym punktem.

**`PiiRedactor` (nowa klasa, package-private, `domain.plugin.runtime`):** brak istniejącego
mechanizmu redakcji PII gdzie indziej w projekcie (zweryfikowano — `audit_log` przechowuje
surowe `old_value`/`new_value`, bo dostęp ograniczony do ADMIN, inny model ryzyka — NIE reużywać
jako prejudykatu, że redakcja jest niepotrzebna). Rekurencyjne przejście `Map`/`List` (wejście:
wynik `ObjectMapper.convertValue(payload, Object.class)`), normalizacja klucza
(`toLowerCase()` + usunięcie `_`/`-`) przed dopasowaniem do statycznej listy `PII_KEYS`. Ryzyko
PII leży głównie w `ManualActionRequest.parameters()` (dowolna `Map<String,Object>` z UI agenta)
— rekordy SDK (`ContactEvent`/`CustomerSyncRequest`/`DispositionEvent`) niosą tylko
UUID/kody/timestampy, zero PII na poziomie własnych pól nazwanych.

**Decyzja 404 vs 403 (trzeci raz w epiku, po BE-103):** kontynuacja konwencji — `installationId`
innego tenanta → 404 (RLS na `tenant_plugin_installation` czyni "nie istnieje" i "innego
tenanta" nieodróżnialne bez bypassu RLS, którego projekt nie ma). Świadome odejście od
literalnego 403 z kryterium ticketu, jak w [[project_be103_pre_contact_connect_integration]].
**Zasada utrwalona dla kolejnych tickietów EPIC-28:** nie implementować bypassu RLS tylko żeby
dosłownie spełnić podobne kryterium 403 — pytać użytkownika explicite, jeśli wymóg się powtórzy.

Pliki: `domain/plugin/PluginInvocationLog.java`, `PluginInvocationLogId.java`,
`PluginInvocationLogRepository.java` (publiczne), `domain/plugin/dto/PluginInvocationLogDto.java`,
`domain/plugin/runtime/PluginInvocationLogService(Impl).java`, `PiiRedactor.java` (nowy,
package-private), `api/plugin/PluginInvocationLogController.java`. Usunięty:
`domain/plugin/runtime/PluginInvocationLogger.java`. `InvocationStatus` zmieniony z
package-private na public (potrzebny w DTO/serwisie cross-package).

Testy: `PiiRedactorTest` (10), `PluginInvocationLogServiceImplTest` (9 — redakcja PII
zweryfikowana asercją na TREŚCI JSON wynikowego, nie tylko wywołaniem metody — ważne dla
przyszłych podobnych kryteriów "dane nie zawierają X"), `PluginInvocationLogControllerTest` (5,
wzorzec wywołania kontrolera bezpośrednio + `mockStatic(TenantContext.class)`, identyczny jak
`PluginManualActionControllerTest`). Zaktualizowane `ExtensionPointPublisherImplTest`/
`PluginInvocationConsumerTest` (dodatkowy mock `PluginInvocationLogService` w konstruktorze +
`verify(...).record(...)` dla każdej z 5 ścieżek statusu już istniejącej w tych klasach).

Weryfikacja: `mvn verify -pl app` ✅ (1323 testy, 0 failures, 0 errors, BUILD SUCCESS, +49 vs
BE-104: 1274→1323).

Odblokowuje BE-107 (ostatni ticket BE epiku — serwowanie `plugin-ui/` + manual-action proxy).
Zobacz [[project_be102_extension_point_publisher]], [[project_be104_async_extension_points_rabbitmq]],
[[project_be103_pre_contact_connect_integration]].
