---
name: project_be097_plugin_sdk
description: BE-097 nowy moduł Maven plugin-sdk (EPIC-28) — kontrakt SDK dla pluginów firm trzecich, struktura, decyzje
metadata:
  type: project
---

BE-097 dodał nowy, samodzielny moduł Maven `backend/plugin-sdk` (artifactId
`contact-center-plugin-sdk`, packaging `jar`) — jedyna zależność compile-time dla dewelopera
pluginu firmy trzeciej w ramach EPIC-28 (Per-Tenant Plugin/Extension System).

**Struktura:**
- `backend/plugin-sdk/pom.xml` — parent = root `contact-center-backend` POM, ZERO sekcji
  `<dependencies>` (moduł zależy wyłącznie od JDK 21, nic poza tym)
- `com.contactcenter.pluginsdk` (pakiet główny): `PluginEntryPoint`, `PluginContext`,
  `HttpEgressClient`, `HttpResponse`, `PluginLogger`, `PluginConfig`
- `com.contactcenter.pluginsdk.model`: `CustomerView`, `ContactView`, `ContactEvent`,
  `CustomerSyncRequest`, `CustomerSyncResult`, `DispositionEvent`, `ManualActionRequest`,
  `ManualActionResult`, `PreContactConnectResult` — wszystkie `record`

**Why:** plugin musi być fizycznie odcięty od Springa/JPA/Hibernate przez sam classpath —
`ClassLoader` pluginu (BE-098+) ma jako parenta wąski "platform-api" classloader, który
eksponuje TYLKO `com.contactcenter.pluginsdk.*`. Stąd zero zależności w `plugin-sdk` to wymóg
architektoniczny (ARCHITECTURE.md §11.3/§11.6), nie tylko styl.

**Decyzje projektowe (rozsądne domyślne, nie 1:1 z ticketu — bo ticket nie podawał pól DTO):**
- `CustomerView`/`ContactView` — pełne, ale minimalne pola (id, podstawowe dane, status,
  `customFields`/timestamps); zawsze `record`, nigdy encja JPA
- Statyczne fabryki wymagane jako default w `PluginEntryPoint`: `PreContactConnectResult.empty()`,
  `CustomerSyncResult.noop()`, `ManualActionResult.unsupported()`
- `HttpResponse` ma pole `byte[] body` — domyślny `equals`/`hashCode` record jest referencyjny
  dla tablic (akceptowalne dla SDK, brak logiki biznesowej na tym typie)

**How to apply:** BE-098 (kolejny w kolejce, koduje `Plugin`/`PluginVersion` + walidację
manifestu) będzie zależał od `plugin-sdk` przy weryfikacji, że `entryPointClass` implementuje
`PluginEntryPoint`. BE-101 (implementacja `PluginContextImpl` w `app`) musi pisać
`updateCustomerFields` WYŁĄCZNIE do `customer.custom_fields.plugins.<pluginKey>` — nigdy flat
merge, nigdy istniejąca kolumna (reguła anti-overloaded-column, CLAUDE.md). Root
`backend/pom.xml` ma `<module>plugin-sdk</module>` PRZED `<module>app</module>`.

Weryfikacja przeszła: `mvn package -pl plugin-sdk`, `mvn dependency:tree -pl plugin-sdk` (puste
drzewo — brak jakichkolwiek zależności), `mvn package -pl app -DskipTests` (regresja OK),
`mvn package` z roota (reactor poprawnie buduje plugin-sdk → app w tej kolejności).
