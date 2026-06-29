# Customer Call Result DB Sync — przykładowy plugin

Przykładowy, w pełni działający plugin dla systemu rozszerzeń Contact Center (EPIC-28),
demonstrujący kompletny przepływ SDK opisany w
[`documentation/10-plugin-development.md`](../../../documentation/10-plugin-development.md), w
szczególności `DbEgressClient` — jedyny dozwolony kanał dostępu pluginu do zewnętrznej bazy
danych.

**Co robi:** po zakończeniu kontaktu (`POST_CONTACT_END`) i po ustawieniu dyspozycji
(`DISPOSITION_SET`) zapisuje wynik rozmowy do zewnętrznej bazy danych tenanta, append-only.
Dla `CONTACT_ENDED` stosuje `INSERT … WHERE NOT EXISTS` — guard idempotentności zabezpieczający
przed duplikatami przy at-least-once delivery RabbitMQ lub podwójnym evencie platformy. Dla
`DISPOSITION_SET` (naturalnie wielokrotny per kontakt) pozostaje zwykły `INSERT`. Kolumna
`event_type` rozróżnia `CONTACT_ENDED`/`DISPOSITION_SET`. Znaczniki czasu są zapisywane w
**lokalnym czasie polskim** (`Europe/Warsaw`, UTC+1/UTC+2 z DST), nie UTC.

Ten katalog jest **niezależnym projektem Maven** — symuluje repozytorium zewnętrznego
dostawcy pluginu. Nie jest częścią reaktora `backend/pom.xml` i nie jest budowany przez
`mvn package -pl app`.

---

## Jak działa `db:egress:<host>:<port>`

W przeciwieństwie do `http:egress:<host>` (gdzie port jest opcjonalny), kategoria
`db:egress:<host>:<port>` wymaga **obu** — host i port — w manifeście. Host:port musi być
ustalony przez dostawcę pluginu **przed buildem** (w tym przykładzie: `db:egress:crm-demo-db:5432`,
dedykowany kontener CRM-postgres w sieci `contact-center-network` — patrz `crm-demo-db/`). Tenant instalujący plugin musi w `installation_config` podać
`jdbcUrl` wskazujący na **TEN SAM** host:port — inaczej `PluginDbEgressClientImpl` (host) odrzuci
połączenie z `SecurityException`, zanim nawiąże się jakiekolwiek połączenie. Analogicznie do
`http:egress:www.googleapis.com` w przykładzie `customer-google-lookup`.

Plugin sam nigdy nie widzi JDBC URL/credentiali — host (`backend/app`) zarządza połączeniem w
całości, na podstawie `installation_config` tej instalacji. Plugin podaje wyłącznie SQL z `?`
placeholderami i parametry bindowane pozycyjnie, przez `ctx.dbClient().executeUpdate(sql, params)`.

---

## Zewnętrzna baza CRM — uruchomienie

Plugin wymaga zewnętrznej bazy PostgreSQL dostępnej pod `crm-demo-db:5432` w sieci Docker
`contact-center-network`. W katalogu `crm-demo-db/` znajduje się gotowy `docker-compose.yml`
z PostgreSQL 16 i skryptem `init.sql` tworzącym tabelę `call_results` (+ indeksy).

```bash
# Uruchomienie (z katalogu głównego projektu):
docker compose -f examples/plugins/customer-callresult-db-sync/crm-demo-db/docker-compose.yml up -d

# Weryfikacja — tabela musi istnieć:
docker exec crm-demo-db psql -U crm_demo_user -d crm_demo -c "\dt"

# Zatrzymanie:
docker compose -f examples/plugins/customer-callresult-db-sync/crm-demo-db/docker-compose.yml down

# Reset danych:
docker compose -f examples/plugins/customer-callresult-db-sync/crm-demo-db/docker-compose.yml down -v
```

Kontener dołącza automatycznie do sieci `contact-center-network` (musi już istnieć — tworzy ją
główny `docker compose up`). Baza jest również dostępna z hosta pod `localhost:5433`.

Dane połączenia (do konfiguracji instalacji pluginu):

| Parametr | Wartość |
|---|---|
| `jdbcUrl` | `jdbc:postgresql://crm-demo-db:5432/crm_demo` |
| `dbUsername` | `crm_demo_user` |
| `dbPassword` | `crm_demo_pass` |
| `dbTable` | `call_results` *(default)* |

> **Uwaga:** `jdbcUrl` używa nazwy serwisu Docker `crm-demo-db:5432` — backend CC widzi ten host
> w sieci `contact-center-network`. Manifest pluginu deklaruje `db:egress:crm-demo-db:5432` —
> `jdbcUrl` musi wskazywać dokładnie ten sam host:port.

---

## Krok 0 — zbuduj i zainstaluj `plugin-sdk` lokalnie

Ten projekt zależy od `com.contactcenter:contact-center-plugin-sdk:1.0.0-SNAPSHOT`, który nie
jest publikowany do żadnego publicznego repozytorium Maven (w realnym wdrożeniu byłby
dystrybuowany przez wewnętrzny Nexus/Artifactory platformy). Do celów lokalnych/demo:

```bash
cd ../../../backend
mvn install -pl plugin-sdk
```

To zainstaluje JAR `contact-center-plugin-sdk-1.0.0-SNAPSHOT.jar` do Twojego lokalnego
repozytorium Maven (`~/.m2`), skąd ten projekt go odczyta.

## Krok 1 — pierwsza budowa (z placeholderem checksumu)

```bash
cd examples/plugins/customer-callresult-db-sync
mvn -q package
```

Manifest (`src/main/resources/META-INF/plugin-manifest.json`) zawiera placeholder
`checksumSha256` (64 zera) — JAR z tego kroku **nie przejdzie walidacji przy uploadzie**,
ale potrzebujemy go, żeby policzyć prawdziwy checksum.

## Krok 2 — policz checksum

Backend liczy `checksumSha256` jako SHA-256 z konkatenacji (nazwa wpisu + zawartość wpisu) dla
wszystkich wpisów JAR-a **z wyłączeniem** `META-INF/plugin-manifest.json` (analogia do
`MANIFEST.MF` — hash, który zawierałby sam siebie, byłby matematycznie niespełnialny). Ten
projekt zawiera narzędzie replikujące dokładnie ten algorytm:

```bash
java -cp target/classes com.acme.contactcenter.plugin.callresultdbsync.tooling.ChecksumTool \
    target/customer-callresult-db-sync-plugin-1.2.0.jar
```

Wypisze 64-znakowy hex string.

## Krok 3 — wklej checksum do manifestu i przebuduj

Zastąp placeholder w `src/main/resources/META-INF/plugin-manifest.json` wynikiem z kroku 2,
potem:

```bash
mvn -q package
```

Ponieważ checksum jest liczony z wyłączeniem wpisu manifestu, zmiana TYLKO tego pola nie
zmienia hashu innych wpisów (klasy/zasoby pluginu są nieruszone między tymi dwiema budowami,
więc checksum z kroku 2 wciąż jest poprawny). Wynikowy plik:
`target/customer-callresult-db-sync-plugin-1.2.0.jar` jest gotowy do uploadu.

> ⚠️ **Niedeterminizm budowy:** bajtowa reprodukowalność JAR-a jest zagwarantowana TYLKO między
> budowami na tym samym środowisku (ta sama wersja JDK/Maven, ten sam OS) — inny JDK/Maven/OS
> może wyprodukować JAR o innych bajtach (mimo identycznego kodu źródłowego), a backend i tak
> odrzuci upload z czytelnym błędem `Checksum mismatch: ...obliczony=<X>` — w takim wypadku
> wklej `<X>` z komunikatu błędu do manifestu i przebuduj.

## Krok 4 — wgraj i zainstaluj w panelu supervisora

1. Zaloguj się jako `SUPERVISOR`/`ADMIN`, przejdź do **Ustawienia → Pluginy**
   (`/supervisor/settings/plugins`).
2. Wgraj `customer-callresult-db-sync-plugin-1.2.0.jar` (drag&drop lub wybór pliku).
3. Po statusie `VALIDATED` kliknij **Zainstaluj**, zatwierdź uprawnienia
   (`contact:read`, `db:egress:crm-demo-db:5432`).
4. Skonfiguruj połączenie z bazą docelową — **patrz sekcja "Konfiguracja" poniżej**.
5. Kliknij **Włącz**.

---

## Konfiguracja: połączenie z bazą docelową

Ten plugin wymaga konfiguracji tenanta odczytywanej przez `PluginContext.config()`:

| Klucz | Wymagany | Opis |
|---|---|---|
| `jdbcUrl` | tak | JDBC URL bazy docelowej, np. `jdbc:postgresql://localhost:5432/crm_demo`. **Host:port musi być identyczny** z `db:egress:<host>:<port>` zatwierdzonym przy instalacji — inaczej każde wywołanie zostanie odrzucone z `SecurityException` |
| `dbUsername` | tak | nazwa użytkownika bazy docelowej |
| `dbPassword` | tak | hasło użytkownika bazy docelowej |
| `dbTable` | nie (default `call_results`) | nazwa tabeli docelowej — musi być prostą nazwą identyfikatora (`^[a-zA-Z_][a-zA-Z0-9_]*$`); plugin waliduje to przed budową SQL, bo nazwa tabeli jest interpolowana w SQL identifier position (nie da się parametryzować przez `?`) |

Ustaw je **po** instalacji, **przed** `enable`:

```http
PATCH /api/supervisor/plugins/installations/{installationId}/config
Content-Type: application/json
Authorization: Bearer <JWT supervisora/admina>

{
  "config": {
    "jdbcUrl": "jdbc:postgresql://crm-demo-db:5432/crm_demo",
    "dbUsername": "crm_demo_user",
    "dbPassword": "crm_demo_pass",
    "dbTable": "call_results"
  }
}
```

Wartości są szyfrowane po stronie backendu (AES-256-GCM, ten sam wzorzec co
`tenant_ai_config`/`tenant_twilio_config`) — nigdy nie wracają w żadnej odpowiedzi API, więc nie
ma sposobu odczytać już zapisane hasło przez REST (tylko nadpisać nowym wywołaniem `PATCH`,
semantyka REPLACE — pełen zestaw kluczy zastępowany przy każdym wywołaniu).

> Brak jeszcze pola do tego w dialogu instalacji panelu supervisora (`/supervisor/settings/plugins`,
> FE-098) — na razie wywołaj `PATCH` bezpośrednio (np. przez Swagger UI lub `curl`/Postman).
> Dodanie pola konfiguracji do UI to naturalny follow-up frontendowy.

### DDL tabeli docelowej

```sql
CREATE TABLE call_results (
    id               BIGSERIAL PRIMARY KEY,
    contact_id       UUID NOT NULL,
    customer_id      UUID,
    event_type       VARCHAR(32) NOT NULL,   -- 'CONTACT_ENDED' lub 'DISPOSITION_SET'
    channel          VARCHAR(32),            -- NULL dla event_type='DISPOSITION_SET'
    direction        VARCHAR(16),            -- NULL dla event_type='DISPOSITION_SET'
    status           VARCHAR(32),            -- NULL dla event_type='DISPOSITION_SET'
    agent_id         UUID,
    disposition_code VARCHAR(64),            -- NULL dla event_type='CONTACT_ENDED'
    occurred_at      TIMESTAMP NOT NULL      -- czas lokalny Europe/Warsaw (nie UTC)
);

-- Composite index optymalizujący subquery WHERE NOT EXISTS (contact_id = ? AND event_type = ?)
CREATE INDEX idx_call_results_contact_id     ON call_results (contact_id);
CREATE INDEX idx_call_results_occurred_at    ON call_results (occurred_at);
CREATE INDEX idx_call_results_event_type     ON call_results (event_type);
CREATE INDEX idx_call_results_contact_event  ON call_results (contact_id, event_type);

-- Partial unique index: gwarantuje co najwyżej jeden wiersz CONTACT_ENDED per kontakt.
-- WHERE NOT EXISTS w pluginie jest "fast-path" omijającym wyjątek w normalnym przepływie;
-- ten index jest barierą bezpieczeństwa przy równoległych konsumentach kolejki (at-least-once).
CREATE UNIQUE INDEX uq_call_results_contact_ended
    ON call_results (contact_id)
    WHERE event_type = 'CONTACT_ENDED';
```

Ta tabela (z indeksami) musi istnieć w bazie docelowej **przed** pierwszym wywołaniem pluginu —
plugin nigdy nie tworzy ani nie migrowuje schematu (poza zakresem `DbEgressClient`, kontrakt SDK
to tylko `executeUpdate`).

> **Dlaczego partial unique index, skoro jest już `WHERE NOT EXISTS`?**
> `WHERE NOT EXISTS` jest atomowy w sekwencyjnym przepływie (redelivery RabbitMQ), ale przy
> wielu równoległych konsumentach dwa wywołania mogą oba przejść subquery przed commitem
> któregokolwiek z nich. Unique index zamyka ten scenariusz: concurrent duplikat generuje
> `RuntimeException` → catch-blok go loguje, kontakt ACK-owany — brak NACK i brak retry.

### Known limitation: tylko silniki JDBC dostępne na classpath backendu

`PluginDbEgressClientImpl` (host) używa `java.sql.DriverManager` bez poolingu — sterownik JDBC
musi być na classpath **backendu** (`backend/app`), nie pluginu (plugin nigdy nie dostarcza
własnego sterownika — `ServiceLoader` jest zablokowany przez skan ASM, patrz
[§7 dokumentacji](../../../documentation/10-plugin-development.md#7-ograniczenia-bytecode-statyczny-skan-asm)).
PostgreSQL jest już obecny na classpath backendu (główny stos platformy), więc demo z `jdbcUrl`
PostgreSQL działa od razu. Jeśli tenant chce skonfigurować inny silnik (MySQL, Oracle, SQL
Server, ...), administrator platformy musi dodać odpowiedni sterownik JDBC do classpath
backendu — to nie jest coś, co plugin lub tenant może zrobić samodzielnie. Pooling połączeń
(np. HikariCP) jest naturalnym follow-upem, jeśli wolumen wywołań to uzasadni — nie jest
blokerem w tym przykładzie.

---

## Struktura projektu

```
customer-callresult-db-sync/
  pom.xml
  README.md
  src/main/java/com/acme/contactcenter/plugin/callresultdbsync/
    CustomerCallResultDbSyncPlugin.java   – PluginEntryPoint (onActivate/onPostContactEnd/onDispositionSet)
    tooling/ChecksumTool.java             – replika algorytmu checksumu platformy (do budowy)
  src/main/resources/
    META-INF/plugin-manifest.json
  src/test/java/com/acme/contactcenter/plugin/callresultdbsync/
    CustomerCallResultDbSyncPluginTest.java – testy budowy SQL/walidacji nazwy tabeli (DbEgressClient zamockowany)
```

## Dlaczego brak zewnętrznych zależności?

`plugin-sdk` dostarcza zero bibliotek poza JDK — deweloper jest wolny w doborze zależności we
własnym JAR-ze, ale **żadna klasa w JAR-ze nie może odwoływać się do API zablokowanych przez
statyczny skan bytecode platformy** (`Thread#getContextClassLoader`/`setContextClassLoader`,
`ServiceLoader`, `setAccessible`, `ProcessBuilder`, `java.nio.file.*`, `sun.misc.*`, podklasy
`ClassLoader` — pełna lista w `documentation/10-plugin-development.md`, §7). To jest właśnie
powód, dla którego ten plugin **nie** łączy się z bazą surowym JDBC wewnątrz własnego JAR-a:
sterowniki JDBC zwykle rejestrują się przez `ServiceLoader` (blokowane), a nawet gdyby nie —
plugin omijałby cały model allow-listy egress. Stąd `DbEgressClient`: cała obsługa JDBC żyje po
stronie hosta (`backend/app`, normalny classpath, gdzie `ServiceLoader` sterowników JDBC działa
bez przeszkód, bo host nie jest skanowany/sandboksowany), a plugin tylko podaje SQL + parametry.
