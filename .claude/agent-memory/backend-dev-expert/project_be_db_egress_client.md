---
name: project_be_db_egress_client
description: DbEgressClient (db:egress:<host>:<port>) + przykładowy plugin customer-callresult-db-sync (EPIC-28)
metadata:
  type: project
---

Plugin SDK rozszerzony o `DbEgressClient` — drugi (po `HttpEgressClient`) restricted egress
channel dla pluginów. Decyzja architektoniczna (po konsultacji z użytkownikiem): pluginy NIE
łączą się surowym JDBC wewnątrz własnego JAR-a (ServiceLoader sterowników JDBC jest blokowany
przez skan ASM, §7 documentation/tech/10-plugin-development.md) — cała obsługa JDBC żyje po stronie
hosta (`backend/app`, normalny classpath, gdzie ServiceLoader działa bez przeszkód).

**Why:** sterowniki JDBC rejestrują się przez `ServiceLoader`, zablokowany w pluginach przez
ASM scan; host nie jest sandboksowany, więc tam `DriverManager`/`ServiceLoader` działa normalnie.

**How to apply:** każdy przyszły "plugin potrzebuje dostępu do zasobu X poza HTTP" powinien
najpierw sprawdzić, czy X wymaga API blokowanego przez ASM scan (§7) — jeśli tak, wzorzec to
"nowa kategoria `<resource>:egress:<target>` + Impl po stronie hosta", analogicznie do tego
ticketu i [[project_be108_plugin_installation_config_encryption]].

## Pliki kluczowe

- `backend/plugin-sdk/.../DbEgressClient.java` — interfejs SDK, jedna metoda `executeUpdate(String sql, List<Object> params)`
- `backend/plugin-sdk/.../PluginContext.java` — `dbClient()` dodany analogicznie do `httpClient()`
- `backend/app/.../domain/plugin/PluginPermission.java` — kategoria `db:egress:` z OBOWIĄZKOWYM portem (w przeciwieństwie do `http:egress:`, gdzie port jest opcjonalny); pattern `^[a-zA-Z0-9.\-]+:\d{1,5}$`
- `backend/app/.../domain/plugin/runtime/PluginDbEgressClientImpl.java` — `DriverManager.getConnection` per wywołanie (BEZ poolingu, świadome uproszczenie), allow-list `host:port` sprawdzana PRZED połączeniem; wyciąga host:port z JDBC URL przez `new URI(jdbcUrl.substring("jdbc:".length()))`
- `backend/app/.../domain/plugin/runtime/PluginContextImpl.java` — `dbEgressClient` budowany z `this.config.get("jdbcUrl"/"dbUsername"/"dbPassword")` (reużywa już zdeszyfrowany `PluginConfigImpl`, brak duplikacji parsowania JSON)

## Przykładowy plugin

`examples/plugins/customer-callresult-db-sync/` — struktura identyczna jak
[[project_be097_plugin_sdk]] wzorzec `customer-google-lookup`: pom.xml, ChecksumTool, README z
krokami 0-4. `extensionPoints`: POST_CONTACT_END + DISPOSITION_SET (oba fire-and-forget).
`permissions`: `contact:read`, `db:egress:localhost:5432`.

Append-only INSERT (NIE upsert — różne silniki JDBC mają różną składnię ON CONFLICT/MERGE).
Nazwa tabeli (`dbTable` z config) interpolowana w SQL identifier position — walidowana regexem
`^[a-zA-Z_][a-zA-Z0-9_]*$` w DWÓCH miejscach: `onActivate` (fail-fast) i przed każdym INSERT
(bo `PATCH .../config` może zmienić `dbTable` bez re-enable/re-activate).

Known limitation dokumentowany w README: tylko silniki JDBC z driverem już na classpath
backendu (PostgreSQL obecny, główny stos) — brak HikariCP/connection pooling celowo (świadome
uproszczenie tego ticketu, nie bloker).

## Status testów

`mvn test -pl app`: 1415 testów, 0 failures, 2 errors — błędy w `ContactCenterApplicationIT`
(Tomcat/EntityManagerFactory startup), **pre-existing, niezwiązane z tą zmianą** (plik nie
modyfikowany, błąd dotyczy DataSourceAutoConfiguration wyłączonego w teście IT, prawdopodobnie
wymaga Docker/Testcontainers niedostępnych w tym środowisku sandboxowym). Weryfikowane
2026-06-25.

`mvn -q package` w `examples/plugins/customer-callresult-db-sync`: 8 testów, 0 failures.
ChecksumTool zweryfikowany — checksum stabilny między przebudowami (zgodnie z procedurą
README kroki 1-3).
