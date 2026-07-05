# Tworzenie pluginów (EPIC-28)

> Dokument onboardingowy dla systemu rozszerzeń (pluginów) per tenant. Opisuje **stan
> faktyczny implementacji** (kod, nie pierwotny plan) — pełny projekt architektoniczny i
> uzasadnienia decyzji znajdują się w `ARCHITECTURE.md`, sekcja 11 (ADR-09…ADR-13,
> RT-09…RT-14). Plan wykonania i lista ticketów: `EPIC-28-PLAN.md`.
>
> Audytorium tego dokumentu: (a) zespół platformy — jak działa mechanizm i jak go
> rozwijać, (b) deweloperzy pluginów (wewnętrzni lub zewnętrzni dostawcy integracji) —
> jak napisać, zbudować i wgrać plugin.

---

## 1. Czym jest plugin w tym systemie

Plugin to plik **JAR**, wgrywany przez administratora tenanta z panelu (`/supervisor/settings/plugins`),
który rozszerza zachowanie platformy w pięciu, z góry zdefiniowanych **punktach rozszerzeń**
(extension points) — np. "tuż przed połączeniem agenta z kontaktem" albo "agent kliknął
przycisk w toolbarze". Typowe zastosowanie: integracja z zewnętrznym CRM klienta — lookup
danych przed połączeniem, synchronizacja klienta, zapis notatki po zakończeniu kontaktu.

Kluczowe właściwości modelu (szczegóły w `ARCHITECTURE.md` §11.3):

- **Tabela `plugin` (tożsamość pluginu po `pluginKey`) jest globalna** — bez `tenant_id`,
  bez RLS. Identyfikuje plugin niezależnie od tego, kto go wgrał.
- **Tabela `plugin_version` jest per-tenant** (V078) — każdy upload JAR-a należy do tenanta,
  który go wgrał (`tenant_id NOT NULL`). Tenant widzi w katalogu tylko swoje wersje.
  Klucz S3: `plugins/{tenantId}/{pluginKey}/{version}/{plik}.jar`.
- **Instalacja (`tenant_plugin_installation`) jest per tenant** — z RLS, z własnym zestawem
  zatwierdzonych uprawnień (`granted_permissions`) i konfiguracją (`installation_config`).
- **Izolacja wykonania: in-process, jeden dedykowany `ClassLoader` per `(tenant_id, plugin_key)`**
  — w tym samym JVM co backend, nie osobny proces/kontener. To świadomy wybór z mitygacjami
  warstwowymi, nie pełną sandboksacją (RT-10) — patrz [§9](#9-bezpieczeństwo-i-znane-ograniczenia).
- **Żadnego dostępu do Springa/JPA** — plugin widzi wyłącznie interfejsy/DTO z modułu
  `plugin-sdk`, nigdy encje, repozytoria czy `ApplicationContext`.

---

## 2. Moduł `plugin-sdk` — jedyna zależność deweloperska

```
backend/plugin-sdk/
  pom.xml
  src/main/java/com/contactcenter/pluginsdk/
    PluginEntryPoint.java
    PluginContext.java
    HttpEgressClient.java
    HttpResponse.java
    DbEgressClient.java
    PluginLogger.java
    PluginConfig.java
    model/
      ContactEvent.java
      ContactView.java
      CustomerView.java
      CustomerSyncRequest.java
      CustomerSyncResult.java
      DispositionEvent.java
      ManualActionRequest.java
      ManualActionResult.java
      PreContactConnectResult.java
```

Koordynaty Maven:

```xml
<dependency>
  <groupId>com.contactcenter</groupId>
  <artifactId>contact-center-plugin-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>provided</scope> <!-- nie pakuj SDK do własnego JAR-a -->
</dependency>
```

Moduł **nie ma żadnych zależności** poza JDK 21 — zero `spring-*`, zero
`jakarta.persistence.*`/`hibernate-*` (wymuszone i weryfikowane przez `mvn dependency:tree -pl plugin-sdk`
w CI tego repo). Deweloper pluginu kompiluje swój kod z tym jednym, lekkim JAR-em jako
zależnością — w runtime SDK jest dostarczany przez platformę (dedykowany `ClassLoader`
pluginu ma dostęp tylko do pakietu `com.contactcenter.pluginsdk.*`), więc **nie pakuj
klas SDK do własnego JAR-a** (`scope=provided`).

---

## 3. Kontrakt `PluginEntryPoint`

```java
public interface PluginEntryPoint {
    void onActivate(PluginContext context);
    void onDeactivate();
    default PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) { return PreContactConnectResult.empty(); }
    default void onPostContactEnd(PluginContext ctx, ContactEvent e) { }
    default CustomerSyncResult onCustomerSync(PluginContext ctx, CustomerSyncRequest req) { return CustomerSyncResult.noop(); }
    default void onDispositionSet(PluginContext ctx, DispositionEvent e) { }
    default ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) { return ManualActionResult.unsupported(); }
}
```

Klasa implementująca ten interfejs to **jedyna klasa pluginu instancjonowana przez hosta** —
zawsze przez **konstruktor bezargumentowy** (żadnego DI, żadnych parametrów). Wskazujesz ją w
manifeście jako `entryPointClass`. Minimalny plugin musi nadpisać tylko `onActivate`/`onDeactivate`
— resztę można zaimplementować selektywnie, w zależności od tego, które punkty rozszerzeń
deklarujesz w `manifest.extensionPoints`.

### 3.1 Pięć punktów rozszerzeń

| Extension point | Callback | Tryb | Timeout domyślny | Zachowanie przy timeout/błędzie |
|---|---|---|---|---|
| `PRE_CONTACT_CONNECT` | `onPreContactConnect` | **blocking** | 2000 ms | Connect i tak następuje; agent dostaje `PreContactConnectResult.empty()` |
| `MANUAL_ACTION` | `onManualAction` | **blocking** | 5000 ms | Agent dostaje HTTP `504` z ciałem JSON (nie wyjątek) |
| `POST_CONTACT_END` | `onPostContactEnd` | fire-and-forget (RabbitMQ) | 30 000 ms | Brak efektu dla agenta — wynik tylko w `plugin_invocation_log` |
| `CUSTOMER_SYNC` | `onCustomerSync` | fire-and-forget (RabbitMQ) | 30 000 ms | Jak wyżej |
| `DISPOSITION_SET` | `onDispositionSet` | fire-and-forget (RabbitMQ) | 30 000 ms | Jak wyżej |

Timeouty są konfigurowalne przez `application.yml` (prefiks `plugin.invocation.*`), capped na
**60 000 ms** (`plugin.invocation.max-timeout-ms`, zgodne z CHECK constraintem
`chk_tenant_plugin_extension_binding_timeout` na tabeli `tenant_plugin_extension_binding`).

**Reguła obowiązująca dla każdego callbacku:** plugin nigdy nie może zablokować ani spowolnić
core flow platformy. Każde wywołanie jest opakowane timeoutem i `catch (Throwable)` (nie tylko
`Exception` — rzucenie `Error` przez plugin też jest zawierane). Jeśli Twój kod zawiesi wątek
na zawsze, host nie próbuje go zabić (`Future.cancel(true)` jest tylko best-effort interrupt) —
po prostu przestaje czekać na wynik i kontynuuje.

---

## 4. `PluginContext` — jedyny dostęp do platformy

```java
public interface PluginContext {
    CustomerView getCustomer(UUID customerId);
    void updateCustomerFields(UUID customerId, Map<String, Object> customFields);
    ContactView getContact(UUID contactId);
    void appendContactNote(UUID contactId, String note);
    HttpEgressClient httpClient();
    DbEgressClient dbClient();
    PluginLogger logger();
    PluginConfig config();
}
```

`PluginContext` jest budowany **na nowo przy każdym wywołaniu**, z `tenantId` ustalonym przez
hosta na podstawie wątku wywołującego (`TenantContext` żądania agenta) — żadna metoda SDK nie
przyjmuje `tenantId` jako parametr, więc plugin nie ma sposobu wpłynąć na to, czyje dane
zobaczy. `getCustomer`/`getContact` zwracają niemutowalne `record`y (`CustomerView`/`ContactView`)
— nigdy encję JPA, nigdy obiekt zarządzany przez Hibernate.

| Metoda | Przeznaczenie | Ograniczenia |
|---|---|---|
| `getCustomer(UUID)` | Odczyt snapshotu klienta | Tylko klient bieżącego tenanta; rzuca przy braku dostępu |
| `updateCustomerFields(UUID, Map)` | **Jedyny** sposób zapisu danych o kliencie | Host pisze wyłącznie do `customer.custom_fields.plugins.<pluginKey>` — nigdy do typowanej kolumny, nigdy do namespace'u innego pluginu |
| `getContact(UUID)` | Odczyt snapshotu kontaktu (rozmowa/e-mail/chat) | Tylko kontakt bieżącego tenanta |
| `appendContactNote(UUID, String)` | Dopisanie notatki do historii kontaktu | Host atrybutuje notatkę do nazwy pluginu |
| `httpClient()` | Ograniczony klient HTTP (`get`/`post`) | Tylko hosty zadeklarowane jako `http:egress:<host>` w `granted_permissions` — patrz [§6](#6-model-uprawnień) |
| `dbClient()` | Ograniczony klient bazy danych zewnętrznej | Tylko `host:port` zadeklarowane jako `db:egress:<host>:<port>` w `granted_permissions`; plugin nigdy nie widzi JDBC URL ani credentiali — host czyta je z `installation_config` (`jdbcUrl`, `dbUsername`, `dbPassword`). Jedyna metoda: `int executeUpdate(String sql, List<Object> params)` (parametryzowane INSERT/UPDATE/DELETE, `?` placeholdery). Patrz [§6.1](#61-dbegressclient--konfiguracja-kluczy-installation_config) |
| `logger()` | Logger diagnostyczny pluginu | **Stan aktualny:** pisze do logów aplikacji przez SLF4J z prefiksem `[PluginLog]`, NIE jeszcze do `plugin_invocation_log` — patrz known limitation w [§9](#9-bezpieczeństwo-i-znane-ograniczenia) |
| `config()` | Odczyt konfiguracji tenanta (`get`/`getOrDefault`) | Wartości z `tenant_plugin_installation.installation_config`, deszyfrowane przed podaniem pluginowi |

### 4.1 `CustomerView` / `ContactView` (pola)

```java
record CustomerView(UUID customerId, String firstName, String lastName,
                    List<String> emails, List<String> phoneNumbers,
                    Map<String, Object> customFields, Instant createdAt) {}

record ContactView(UUID contactId, UUID customerId, String channel, String direction,
                   String status, UUID agentId, UUID queueId,
                   Instant startedAt, Instant endedAt) {}
```

`customFields` w `CustomerView` to odczyt tego samego namespace'u, do którego piszesz przez
`updateCustomerFields` — możesz odczytać własne, wcześniej zapisane dane spod
`customFields.get("plugins").get(pluginKey)` (struktura mapy, nie typowany obiekt).

---

## 5. Manifest pluginu — `META-INF/plugin-manifest.json`

Każdy JAR **musi** zawierać ten plik. Schema (JSON Schema, walidowana przy uploadzie):
`backend/app/src/main/resources/plugin/plugin-manifest.schema.json`.

```json
{
  "pluginKey": "acme-crm-sync",
  "displayName": "Acme CRM Sync",
  "version": "1.3.0",
  "vendor": "Acme Sp. z o.o.",
  "vendorContact": "support@acme.example",
  "sdkVersion": "1.x",
  "entryPointClass": "com.acme.contactcenter.plugin.AcmeCrmPlugin",
  "extensionPoints": ["PRE_CONTACT_CONNECT", "POST_CONTACT_END", "CUSTOMER_SYNC", "DISPOSITION_SET", "MANUAL_ACTION"],
  "permissions": ["customer:read", "customer:update", "contact:read", "http:egress:api.acme-crm.example"],
  "uiPanels": [
    { "panelId": "acme-crm-side-panel", "mountPoint": "AGENT_DESKTOP_SIDE_PANEL", "url": "classpath:/plugin-ui/index.html", "sandbox": "allow-scripts" }
  ],
  "manualActions": [
    { "actionId": "open-in-crm", "label": "Otwórz w CRM", "mountPoint": "AGENT_DESKTOP_TOOLBAR" }
  ],
  "checksumSha256": "<SHA-256 zawartości JAR-a, BEZ pliku manifestu samego — patrz uwaga niżej>"
}
```

| Pole | Wymagane | Walidacja |
|---|---|---|
| `pluginKey` | tak | `^[a-z0-9]([a-z0-9-]{0,98}[a-z0-9])?$`, unikalny w tabeli `plugin` (globalnej tożsamości pluginu); każdy tenant może mieć własne wersje (`plugin_version`) pod tym samym `pluginKey` |
| `displayName` | tak | 1–200 znaków |
| `version` | tak | string semver, 1–50 znaków |
| `vendor` | tak | 1–200 znaków |
| `vendorContact` | nie | max 200 znaków |
| `sdkVersion` | tak | sprawdzane wobec wspieranego zakresu wersji SDK hosta |
| `entryPointClass` | tak | musi istnieć w JAR-ze i implementować `PluginEntryPoint` (weryfikacja przez ASM, bez ładowania klasy) |
| `extensionPoints` | tak, ≥1 | podzbiór 5 wartości z [§3.1](#31-pięć-punktów-rozszerzeń) |
| `permissions` | tak (może być `[]`) | podzbiór z [§6](#6-model-uprawnień) |
| `uiPanels` | nie | lista `{panelId, mountPoint, url, sandbox?}` |
| `manualActions` | nie | lista `{actionId, label, mountPoint}` |
| `checksumSha256` | tak | 64 znaki hex |

**Uwaga o `checksumSha256`:** liczony jest z zawartości JAR-a **z wyłączeniem** samego pliku
`META-INF/plugin-manifest.json` (analogicznie do `MANIFEST.MF` w standardowych JAR-ach Javy) —
hash całego pliku zawierającego pole z tym samym hashem byłby matematycznie niespełnialny.
Wylicz checksum swoim build toolem **po** spakowaniu wszystkich plików oprócz manifestu, potem
dopisz manifest z tym checksumem jako ostatni krok budowy.

`mountPoint` to dowolny string rozpoznawany przez frontend — obecnie zaimplementowane:
`AGENT_DESKTOP_SIDE_PANEL` (panel boczny agenta), `AGENT_DESKTOP_TOOLBAR` (przycisk w
toolbarze agenta). `SUPERVISOR_DASHBOARD` jest wspierany przez komponent hosta
(`cc-plugin-panel-host`), ale nie jest jeszcze podłączony do żadnej konkretnej strony
supervisora (brak ticketu mountującego — możliwy follow-up).

---

## 6. Model uprawnień

`manifest.permissions` musi być podzbiorem zamkniętego zbioru rozpoznawanego przez platformę
(`PluginPermission.EXACT_PERMISSIONS` + kategoria `http:egress:`):

```
customer:read
customer:update
contact:read
contact:update
http:egress:<host>       (host: litery/cyfry/./-, opcjonalnie :port — dowolna ilość wpisów)
db:egress:<host>:<port>  (host i port WYMAGANE, w przeciwieństwie do http:egress)
```

Żądanie permission spoza tego zbioru jest odrzucane już przy **walidacji JAR-a** (REJECTED,
nie tylko ignorowane). Przy **instalacji** (nie uploadzie) administrator tenanta zatwierdza
podzbiór `permissions` z manifestu jako `grantedPermissions` — host **nigdy nie auto-grantuje**
pełnego zestawu z manifestu; żądanie na wejściu instalacji, które nie jest w manifeście, jest
po prostu odfiltrowane (przecięcie żądanych ∩ zadeklarowanych), bez błędu.

`http:egress:<host>` jest jednocześnie: (a) allow-listą dla `PluginContext.httpClient()`, i
(b) źródłem nagłówka `Content-Security-Policy: connect-src` dla zasobów UI pluginu
(`/plugin-assets/**`) — patrz [§8](#8-integracja-ui-iframe--pluginuisdk).

### 6.1 `DbEgressClient` — konfiguracja kluczy `installation_config`

`DbEgressClient` pozwala pluginowi wykonać parametryzowany SQL (`INSERT`/`UPDATE`/`DELETE`) na
zewnętrznej bazie danych tenanta — bez ujawniania credentiali pluginowi. Działa tak:

1. **Manifest** deklaruje uprawnienie: `"db:egress:<host>:<port>"` (np. `"db:egress:crm-demo-db:5432"`).
2. **Administrator tenanta** zatwierdza to uprawnienie przy instalacji i ustawia w konfiguracji
   instalacji (zakładka "Konfiguracja" w UI) trzy klucze:

   | Klucz w `installation_config` | Opis | Przykład |
   |---|---|---|
   | `jdbcUrl` | JDBC URL zewnętrznej bazy | `jdbc:postgresql://crm-demo-db:5432/crm` |
   | `dbUsername` | Nazwa użytkownika bazy | `crm_plugin_user` |
   | `dbPassword` | Hasło bazy — szyfrowane AES-256-GCM w bazie platformy | `s3cr3t` |

3. **Host** przy każdym wywołaniu `executeUpdate`:
   - Wyciąga `host:port` z `jdbcUrl` (parsuje jako URI po strippingu prefiksu `jdbc:`).
   - Sprawdza, czy `host:port` pasuje do `db:egress:<host>:<port>` w `granted_permissions` —
     jeśli nie, rzuca `SecurityException` **przed** jakimkolwiek połączeniem z bazą.
   - Otwiera połączenie JDBC (`DriverManager.getConnection`), wykonuje `PreparedStatement` z
     wartościami z `params` i zamyka połączenie — **brak connection poolingu** (jedno połączenie
     per wywołanie; wystarczające dla fire-and-forget extension pointów).

Plugin wywołuje wyłącznie:

```java
// Przykład z guardem idempotentności (INSERT ... WHERE NOT EXISTS):
ctx.dbClient().executeUpdate(
    "INSERT INTO call_results (contact_id, event_type, occurred_at)"
    + " SELECT ?, ?, ? WHERE NOT EXISTS"
    + " (SELECT 1 FROM call_results WHERE contact_id = ? AND event_type = 'CONTACT_ENDED')",
    List.of(e.contactId(), "CONTACT_ENDED", Timestamp.valueOf(localNow), e.contactId())
);
```

Nigdy nie podaje URL ani credentiali — nie ma do nich dostępu.

> **Ważne:** `jdbcUrl` musi wskazywać dokładnie na ten sam `host:port`, który zadeklarowano
> w `db:egress:<host>:<port>`. `crm-demo-db:5432` w URL i `db:egress:crm-demo-db:5432`
> w manifeście — muszą się zgadzać co do znaku. Jeśli JDBC URL używa IP zamiast hostnamu
> (lub innego portu niż zadeklarowany w manifeście), wywołanie zakończy się `SecurityException`.

---

## 7. Ograniczenia bytecode (statyczny skan ASM)

Przy uploadzie, **przed jakimkolwiek ładowaniem klasy**, każda klasa w JAR-ze jest skanowana
statycznie (ASM, bez wykonania kodu). JAR jest odrzucany, jeśli którakolwiek klasa odwołuje się
do:

```
java.lang.reflect.{AccessibleObject,Method,Field,Constructor}#setAccessible
java.lang.Thread#getContextClassLoader / #setContextClassLoader
java.util.ServiceLoader (cała klasa)
java.lang.ProcessBuilder (cała klasa)
java.nio.file.* (cały pakiet)
sun.misc.* (cały pakiet)
```

…lub jeśli jakaś klasa jest **podklasą `java.lang.ClassLoader`**. To są warstwy obrony przed
ucieczką z izolacji `ClassLoader`a (RT-10) — w szczególności blokada `Thread#getContextClassLoader`
domyka furtkę, przez którą plugin mógłby próbować dosięgnąć classloadera aplikacji przez
Thread-Context ClassLoader, niezależnie od tego, czy host poprawnie resetuje TCCL wokół
wywołania (defense in depth, nie zastępstwo dla resetu TCCL po stronie hosta).

**Praktyczna konsekwencja dla autora pluginu:** pisz zwyczajny, "biznesowy" kod Javy — odczyt
danych przez `PluginContext`, wywołania HTTP przez `httpClient()`, logika domenowa. Jeśli
korzystasz z biblioteki firmy trzeciej w swoim JAR-ze (fat JAR), sprawdź, czy nie używa ona
wewnętrznie żadnej z zablokowanych klas (np. wiele bibliotek serializacji/DI używa
`setAccessible` lub `ServiceLoader`) — taki JAR zostanie odrzucony przy uploadzie z konkretnym
komunikatem wskazującym naruszoną klasę.

---

## 8. Cykl życia pluginu — upload → instalacja → wykonanie

```
Administrator tenanta (panel /supervisor/settings/plugins)
  │  POST /api/supervisor/plugins  (multipart, pole "file")
  ▼
PluginValidationService — gate przed dotknięciem klasy:
  1. Rozmiar ≤50MB, magic bytes ZIP/JAR
  2. SHA-256 (bez manifestu) vs manifest.checksumSha256
  3. JSON Schema manifestu
  4. Statyczny skan ASM (§7) + entryPointClass implementuje PluginEntryPoint
  5. (zarezerwowane na podpis kryptograficzny — niezaimplementowane, OQ-28-1)
  → VALIDATED lub REJECTED (PENDING_REVIEW zarezerwowane, nieużywane bez podpisu)
  ▼  (tylko gdy VALIDATED)
PluginStorageService — zapis do MinIO/S3 (plugins/{tenantId}/{pluginKey}/{version}/{plik}.jar,
  klucz zawiera tenantId — katalog per-tenant od V078) + insert plugin_version (tenant_id)
  │
  ▼  administrator klika "Zainstaluj" → POST /api/supervisor/plugins/{pluginVersionId}/install
PluginRegistrationService.install — insert tenant_plugin_installation (enabled=false,
  granted_permissions = żądane ∩ manifest.permissions)
  │
  ▼  administrator klika "Włącz" → POST .../installations/{id}/enable
PluginRuntimeManager.load — pobiera JAR, tworzy PluginClassLoader (parent: PlatformApiClassLoader,
  eksponujący WYŁĄCZNIE com.contactcenter.pluginsdk.*), instancjonuje entryPointClass
  (konstruktor bezargumentowy), woła onActivate(context), rejestruje w PluginRegistry
  │
  ▼  ... normalna praca platformy ...
ExtensionPointPublisher.publishXxx → PluginRegistry.lookup(tenantId, extensionPoint)
  → wywołanie callbacku z §3.1, z timeoutem i circuit breakerem
  → wynik zapisany do plugin_invocation_log (status: SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED)
```

### 8.1 Circuit breaker

Każda instalacja ma licznik kolejnych niepowodzeń (`TIMED_OUT`/`FAILED`) **w pamięci procesu**,
wspólny dla ścieżki blocking i async. Po **5 kolejnych błędach** instalacja przechodzi w stan
`health_status=DEGRADED` w bazie, a kolejne wywołania są pomijane jako `CIRCUIT_OPEN` (bez
próby wywołania pluginu) do pierwszego sukcesu, który resetuje licznik ("closed on first
success" — brak pełnego half-open state).

### 8.2 REST API — referencja

| Metoda + ścieżka | Rola | Opis |
|---|---|---|
| `POST /api/supervisor/plugins` | SUPERVISOR/ADMIN | Upload JAR-a (multipart, pole `file`) → `PluginVersionDto`; wersja przypisana do tenanta uploaderów (V078) |
| `GET /api/supervisor/plugins/catalog` | SUPERVISOR/ADMIN | Lista wersji pluginów wgranych przez bieżącego tenanta (per-tenant od V078) |
| `DELETE /api/supervisor/plugins/catalog/{pluginVersionId}` | SUPERVISOR/ADMIN | Usuwa wersję z katalogu; blokowane gdy istnieje instalacja tej wersji (409) |
| `GET /api/supervisor/plugins` | SUPERVISOR/ADMIN | Lista wszystkich instalacji tenanta (w tym disabled) |
| `POST /api/supervisor/plugins/{pluginVersionId}/install` | SUPERVISOR/ADMIN | Instalacja wersji dla tenanta; 404 gdy wersja należy do innego tenanta |
| `POST /api/supervisor/plugins/installations/{id}/enable` | SUPERVISOR/ADMIN | Aktywacja (ładuje `ClassLoader`, jeśli jeszcze nieaktywny) |
| `POST /api/supervisor/plugins/installations/{id}/disable` | SUPERVISOR/ADMIN | Dezaktywacja — `PluginRegistry` aktualizowany natychmiast |
| `POST /api/supervisor/plugins/installations/{id}/rollback/{targetId}` | SUPERVISOR/ADMIN | Atomowe przełączenie `enabled` między dwiema instalacjami |
| `DELETE /api/supervisor/plugins/installations/{id}` | SUPERVISOR/ADMIN | Uninstall — fizyczny `DELETE` wiersza (log wywołań przetrwa, FK `SET NULL`) |
| `PATCH /api/supervisor/plugins/installations/{id}/config` | SUPERVISOR/ADMIN | Ustawia `installation_config` (sekrety tenanta, np. klucze API) — **REPLACE** całego zestawu, szyfrowane AES-256-GCM w bazie, nigdy nie zwracane w odpowiedzi |
| `GET /api/supervisor/plugins/{installationId}/invocations` | SUPERVISOR/ADMIN | Historia wywołań, paginowana, filtr po `status` |
| `GET /api/agent/plugins` | AGENT/SUPERVISOR/ADMIN | Lista instalacji **tylko `enabled=true`** — używana przez pulpit agenta |
| `POST /api/agent/plugins/{installationId}/manual-action/{actionId}` | AGENT/SUPERVISOR/ADMIN | Wywołanie `MANUAL_ACTION`; `504` przy przekroczeniu budżetu, `403` gdy instalacja disabled/`DISABLED_BY_ADMIN`/`REVOKED` |
| `POST /api/admin/plugins/versions/{pluginVersionId}/revoke` | ADMIN ⚠️ | Globalny kill switch — `REVOKED`, odładowuje wersję u WSZYSTKICH tenantów (patrz ograniczenie w §9) |
| `GET /plugin-assets/{installationId}/**` | publiczny (bez JWT) | Statyczne assety `plugin-ui/` z JAR-a, z nagłówkiem CSP |
| `GET /plugin-ui-sdk.js` | publiczny (bez JWT) | Skrypt `PluginUiSdk` wstrzykiwany w UI pluginu — patrz §8.4 |

---

## 8.3 Integracja UI — `uiPanels`/`manualActions` w pulpicie agenta

Jeśli manifest deklaruje `uiPanels` z `mountPoint: "AGENT_DESKTOP_SIDE_PANEL"`, panel agenta
montuje zakładkę z komponentem `cc-plugin-panel-host` (`frontend/src/app/shared/components/plugin-panel-host/`)
renderującym **sandboxowany iframe**:

```html
<iframe
  [src]="trustedPluginPanelUrl()"
  sandbox="allow-scripts allow-forms"
  referrerpolicy="no-referrer"
></iframe>
```

`src` wskazuje na `/plugin-assets/{installationId}/index.html` — Twój `plugin-ui/index.html`
(rozpakowany z JAR-a przy `enable`) jest tym, co się renderuje. **Brak `allow-same-origin`** —
Twój kod JS w iframe nie ma dostępu do `localStorage`/cookies hosta i nie może wywołać
`/api/**` z JWT agenta. Jedynym kanałem komunikacji z hostem jest `postMessage`.

> **Kiedy panel jest widoczny:** `AGENT_DESKTOP_SIDE_PANEL` pojawia się **wyłącznie** gdy
> agent ma aktywny kontakt (trwająca rozmowa) lub jest w fazie wrap-up/dyspozycji po jego
> zakończeniu. W stanie bezczynności panel nie jest renderowany — iframe jest odmontowywany,
> a przy każdym nowym kontakcie montowany od nowa (pełna reinicjalizacja). Projektuj swój
> plugin UI pod kątem tego cyklu: każde otwarcie panelu to świeży start skryptu.

### 8.4 `PluginUiSdk` — komunikacja iframe ↔ host

Wstrzyknij w swoim `plugin-ui/index.html`:

```html
<script src="/plugin-ui-sdk.js"></script>
```

> **Uwaga implementacyjna:** choć w kodzie pluginu używasz standardowego tagu `<script src>`,
> backend (`PluginAssetController`) przy serwowaniu `index.html` **automatycznie zastępuje** ten
> tag inlineowaną zawartością SDK. Eliminuje to osobny request przeglądarki do zasobu
> zewnętrznego — w środowiskach z reverse proxy (np. ngrok w trybie deweloperskim) sandboxowany
> iframe z opaque origin ma ograniczony dostęp do cookies sesji, co mogłoby spowodować
> zablokowanie zewnętrznego skryptu. Nie musisz robić nic specjalnego — wystarczy standardowy
> tag `<script src="/plugin-ui-sdk.js">` i platform zajmuje się resztą.

API dostępne jako `window.PluginUiSdk`:

```typescript
PluginUiSdk.getContext(): Promise<{ tenantId: string; contactId: string | null; customerId: string | null }>
PluginUiSdk.invokeManualAction(actionId: string, payload: unknown): Promise<ManualActionResult>
PluginUiSdk.requestResize(height: number): void   // fire-and-forget
PluginUiSdk.notify(message: string, severity: 'info' | 'warning' | 'error'): void  // fire-and-forget
PluginUiSdk.openUrl(url: string): void            // fire-and-forget — otwiera URL w nowej zakładce przez hosta
```

`invokeManualAction` z poziomu iframe woła **ten sam** `POST /api/agent/plugins/{installationId}/manual-action/{actionId}`,
ale wykonany przez host (Angular `HttpClient`, z JWT agenta dodanym automatycznie przez
interceptor) — iframe nigdy nie widzi tokenu. `getContext()` zwraca **wyłącznie**
`tenantId`/`contactId`/`customerId` — nigdy pełny obiekt klienta/kontaktu (jeśli potrzebujesz
więcej danych, wywołaj `PluginContext.getCustomer`/`getContact` z poziomu backendu pluginu, w
ramach `onPreContactConnect`/`onManualAction`, i przekaż wynik do iframe przez własny mechanizm
— np. `displayData` w `PreContactConnectResult`).

`openUrl` prosi hosta o otwarcie URL w nowej zakładce przeglądarki (fire-and-forget, host
waliduje schemat `https://` lub `http://` i wywołuje `window.open(url, '_blank', 'noopener,noreferrer')`).
Sandbox iframe bez `allow-popups` nie może samodzielnie wywołać `window.open` — zawsze używaj
`PluginUiSdk.openUrl` zamiast bezpośredniego wywołania.

Implementacja SDK (`backend/app/src/main/resources/static/plugin-ui-sdk.js`) zawiera w
komentarzu na początku pliku pełną specyfikację formatu wiadomości `postMessage` — przydatne,
jeśli chcesz zaimplementować własny SDK w innym języku (np. plugin UI napisany bez `<script>`,
w Web Components z innym tooling).

---

## 9. Bezpieczeństwo i znane ograniczenia

Przeczytaj `ARCHITECTURE.md` §11.3 (model izolacji) i tabelę ryzyk RT-09…RT-14 przed
podejmowaniem decyzji architektonicznych zależnych od tego systemu. Skrót najważniejszych
ograniczeń **aktualnie obecnych w kodzie** (nie tylko teoretycznych):

- **Izolacja in-process, nie pełna sandboksacja.** `PlatformApiClassLoader` + blacklista ASM +
  reset Thread-Context ClassLoader to warstwy obrony, nie gwarancja niemożności ucieczki na
  poziomie JVM. Instaluj wyłącznie pluginy od zaufanych dostawców — to nie jest model
  bezpieczeństwa odpowiedni dla w pełni nieznanego/wrogiego kodu.
- **`PlatformApiClassLoader` w Java 9+ wymaga platformowego classloadera dla modułów spoza
  `java.base`.** Klasy takie jak `java.sql.Timestamp`, `java.sql.Connection` itp. nie są ładowane
  przez bootstrap classloader — są w module `java.sql`, który w Java 9+ obsługuje platform
  classloader (`ClassLoader.getPlatformClassLoader()`). `findBootstrapClassOrNull` ma fallback
  do platform classloadera, więc `DbEgressClient.executeUpdate()` z parametrami `Timestamp`
  działa poprawnie.
- **`PluginContext.logger()` nie zapisuje jeszcze do `plugin_invocation_log`.** Mimo że Javadoc
  SDK deklaruje separację od logów aplikacji, implementacja (`PluginLoggerImpl`) wciąż pisze
  przez SLF4J z prefiksem `[PluginLog]` — nie trafia do historii wywołań widocznej w UI
  supervisora. Tylko wynik samego wywołania extension pointu (SUCCESS/FAILED/...) jest
  persystowany, nie ad-hoc logi pluginu.
- **`POST /api/admin/plugins/versions/{id}/revoke` używa zwykłej roli tenantowej `ADMIN`,
  nie roli administratora platformy.** System nie ma odrębnej roli "platform admin" — każdy
  `ADMIN` jakiegokolwiek tenanta może globalnie wycofać wersję pluginu wpływając na wszystkich
  innych tenantów. To świadome, udokumentowane ograniczenie (decyzja produktowa), nie błąd —
  do adresacji przy wprowadzeniu modelu ról platformowych.
- **`uiPanels`/`manualActions` z `mountPoint: "SUPERVISOR_DASHBOARD"`** są wspierane przez
  `cc-plugin-panel-host`, ale żadna strona supervisora nie montuje ich jeszcze — tylko pulpit
  agenta (`AGENT_DESKTOP_SIDE_PANEL`/`AGENT_DESKTOP_TOOLBAR`) ma realną integrację.
  Plugin może zadeklarować ten mount point, ale obecnie nic go nie wyrenderuje.
  Wartość ta jest jednak zdefiniowana w SDK na potrzeby przyszłej integracji.
- **`/plugin-assets/**` i `/plugin-ui-sdk.js` są serwowane pod tą samą originą co reszta API**,
  nie z dedykowanej subdomeny — `sandbox` iframe (bez `allow-same-origin`) jest jedyną warstwą
  izolacji przeglądarkowej. Dedykowana origin (np. `plugins.<tenant-domain>`) wymaga zmiany
  infrastruktury (DNS/reverse proxy) poza zakresem backendu — patrz `TASKS-BACKEND.md`, BE-107.
- **Brak testu integracyjnego z prawdziwym MinIO/S3** w CI (port niepublikowany na hosta w
  środowisku lokalnym) — testy storage są jednostkowe z mockiem `S3Client`.

---

## 10. Minimalny przykład end-to-end

> **Trzy pełne, działające przykłady** (skompilowane, zweryfikowane przez realny
> `PluginValidationService` z wynikiem `VALIDATED`) w katalogu `examples/plugins/`:
>
> - [`customer-google-lookup/`](../examples/plugins/customer-google-lookup/) — panel boczny
>   agenta aktywowany podczas rozmowy, prezentujący wyniki Google Custom Search dla bieżącego
>   klienta. Demonstruje `http:egress`, `PRE_CONTACT_CONNECT` i `MANUAL_ACTION`.
>
> - [`customer-callresult-db-sync/`](../examples/plugins/customer-callresult-db-sync/) —
>   zapis wyniku zakończonego kontaktu i dyspozycji do zewnętrznej bazy danych CRM tenanta.
>   Demonstruje `DbEgressClient` (`db:egress:<host>:<port>`), `POST_CONTACT_END` i
>   `DISPOSITION_SET`. Zawiera gotowy `docker-compose.yml` z PostgreSQL 16 dołączającym do
>   sieci `contact-center-network`.
>
> - [`crm-url-launcher/`](../examples/plugins/crm-url-launcher/) — najprostszy wzorzec
>   integracji UI: agent klika przycisk w panelu bocznym, plugin buduje URL (z podstawieniem
>   zmiennych `{customerId}`, `{contactId}` itd. oraz opcjonalnymi parametrami wpisywanymi
>   przez agenta) i otwiera go w nowej zakładce przez `PluginUiSdk.openUrl`. Demonstruje
>   `MANUAL_ACTION`, panel `AGENT_DESKTOP_SIDE_PANEL`, `PluginUiSdk.invokeManualAction`,
>   `PluginUiSdk.openUrl` i konfigurację szablonu URL przez `installation_config`.
>   **Dobry punkt startowy** dla pluginów, których jedynym zadaniem jest uruchomienie
>   zewnętrznego URL-a w CRM/helpdesku z kontekstem bieżącej rozmowy.
>
> Ten rozdział pokazuje skróconą wersję mechanizmu; instrukcje budowy (dwuetapowa procedura
> `checksumSha256`) i konfiguracja są w `README.md` każdego przykładu.

Struktura projektu pluginu (Maven, niezależny od tego repozytorium):

```
acme-crm-sync/
  pom.xml
  src/main/java/com/acme/contactcenter/plugin/AcmeCrmPlugin.java
  src/main/resources/META-INF/plugin-manifest.json
  src/main/resources/plugin-ui/index.html      (opcjonalnie, jeśli deklarujesz uiPanels)
```

```java
package com.acme.contactcenter.plugin;

import com.contactcenter.pluginsdk.*;
import com.contactcenter.pluginsdk.model.*;
import java.util.Map;

public class AcmeCrmPlugin implements PluginEntryPoint {

    private PluginContext context;

    @Override
    public void onActivate(PluginContext context) {
        this.context = context;
        context.logger().info("Acme CRM Sync aktywowany");
    }

    @Override
    public void onDeactivate() {
        // zwolnij zasoby, jeśli jakieś trzymasz w polach instancji
    }

    @Override
    public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
        if (e.customerId() == null) {
            return PreContactConnectResult.empty();
        }
        CustomerView customer = ctx.getCustomer(e.customerId());
        return new PreContactConnectResult(
                Map.of("ostatniZakup", "2026-05-01", "segment", "VIP"),
                null);
    }

    @Override
    public ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) {
        if (!"open-in-crm".equals(req.actionId())) {
            return ManualActionResult.unsupported();
        }
        return new ManualActionResult(true, Map.of("url", "https://crm.acme.example/customer/" + req.customerId()), null);
    }
}
```

Budowa: spakuj zwykłym `mvn package` (lub `jar`/`maven-assembly-plugin`, jeśli masz dodatkowe
zależności inne niż `plugin-sdk`), policz `SHA-256` zawartości **bez** manifestu, dopisz
`META-INF/plugin-manifest.json` z tym checksumem jako ostatni krok, i wgraj wynikowy JAR przez
`/supervisor/settings/plugins` (UI) lub bezpośrednio `POST /api/supervisor/plugins`.

### 10.1 Przykład z `DbEgressClient` — zapis wyniku kontaktu

Plugin reagujący na zakończenie kontaktu (`POST_CONTACT_END`) i ustawienie dyspozycji
(`DISPOSITION_SET`) zapisujący wynik do zewnętrznej bazy CRM:

```java
public class CrmDbSyncPlugin implements PluginEntryPoint {

    private static final ZoneId ZONE_LOCAL = ZoneId.of("Europe/Warsaw");

    @Override
    public void onActivate(PluginContext ctx) { }

    @Override
    public void onDeactivate() { }

    @Override
    public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
        ContactView contact = ctx.getContact(e.contactId());
        // INSERT ... WHERE NOT EXISTS zamiast prostego INSERT VALUES:
        // - RabbitMQ gwarantuje at-least-once delivery — ten sam event może dotrzeć wielokrotnie.
        // - Platforma może emitować POST_CONTACT_END dwukrotnie w pewnych scenariuszach
        //   rozłączenia. WHERE NOT EXISTS jest przenośne między PostgreSQL, MySQL i SQL Server
        //   (Oracle wymaga dodatku "FROM DUAL" po SELECT).
        // - NIE upsert (ON CONFLICT) — składnia różni się między silnikami JDBC.
        Instant endedAt = contact.endedAt() != null ? contact.endedAt() : e.occurredAt();
        Timestamp ts = Timestamp.valueOf(endedAt.atZone(ZONE_LOCAL).toLocalDateTime());
        ctx.dbClient().executeUpdate(
            "INSERT INTO call_results"
            + " (contact_id, customer_id, event_type, channel, direction, status,"
            + "  agent_id, disposition_code, occurred_at)"
            + " SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?"
            + " WHERE NOT EXISTS ("
            + "   SELECT 1 FROM call_results"
            + "   WHERE contact_id = ? AND event_type = 'CONTACT_ENDED')",
            List.of(
                contact.contactId(), contact.customerId(), "CONTACT_ENDED",
                contact.channel(), contact.direction(), contact.status(),
                contact.agentId(), null, ts,
                contact.contactId()   // parametr WHERE NOT EXISTS
            )
        );
    }

    @Override
    public void onDispositionSet(PluginContext ctx, DispositionEvent e) {
        Timestamp ts = Timestamp.valueOf(e.setAt().atZone(ZONE_LOCAL).toLocalDateTime());
        ctx.dbClient().executeUpdate(
            "INSERT INTO call_results"
            + " (contact_id, customer_id, event_type, agent_id, disposition_code, occurred_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
            List.of(e.contactId(), e.customerId(), "DISPOSITION_SET",
                    e.agentId(), e.dispositionCode(), ts)
        );
    }
}
```

**Kluczowe decyzje projektowe:**

- `occurred_at` jest zapisywany w **czasie lokalnym** (`Europe/Warsaw`) przez
  `Timestamp.valueOf(instant.atZone(zone).toLocalDateTime())` zamiast `Timestamp.from(instant)`.
  `Timestamp.from` przechowuje epoch-millis; przy kolumnie `TIMESTAMP WITHOUT TIME ZONE`
  wyświetla wartości UTC (o 1–2h wcześniej niż czas Polski).
- `contact.endedAt()` jest preferowane nad `e.occurredAt()` — jest to znacznik z DB ustalony
  przez event webhooka Twilio, nie czas publikacji na kolejkę. Gdy oba są null (kontakt bez
  znacznika czasu zakończenia), plugin loguje ostrzeżenie z `contactId` i pomija INSERT zamiast
  propagować naruszenie NOT NULL do RabbitMQ jako NACK.
- **Partial unique index** `uq_call_results_contact_ended` (`contact_id WHERE event_type =
  'CONTACT_ENDED'`) jest wymagany w bazie docelowej jako bariera bezpieczeństwa przy równoległych
  konsumentach. `WHERE NOT EXISTS` jest "fast-path" w normalnym przepływie (sekwencyjny
  redelivery), ale bez unique index concurrent duplikat nadal przejdzie. Patrz `init.sql` w
  katalogu `crm-demo-db/`.

Wymagany manifest (fragment):

```json
{
  "extensionPoints": ["POST_CONTACT_END", "DISPOSITION_SET"],
  "permissions": ["contact:read", "db:egress:crm-demo-db:5432"]
}
```

Administrator tenanta musi ustawić w konfiguracji instalacji (`installation_config`):
`jdbcUrl = jdbc:postgresql://crm-demo-db:5432/crm`, `dbUsername`, `dbPassword`.
Pełny, działający przykład: [`customer-callresult-db-sync/`](../examples/plugins/customer-callresult-db-sync/).

### 10.2 Wzorzec: uruchamianie URL-a CRM z kontekstem rozmowy

Najprostszy i bardzo częsty przypadek użycia pluginu UI: agent pracujący na połączeniu klika
jeden przycisk, a otwiera się karta CRM/helpdesku z profilem bieżącego klienta lub
rozmowy. Cały flow to trzy kroki:

1. **Backend pluginu** implementuje `onManualAction` z akcją `build-crm-url` — przyjmuje
   `contactId`/`customerId` z `ManualActionRequest` (wypełniane przez hosta z bieżącej sesji)
   i opcjonalne pola wpisane przez agenta (`agentFields`), buduje URL z szablonu
   konfigurowalnego przez `installation_config` (klucz `urlTemplate`) i zwraca go w
   `ManualActionResult.resultData`.

2. **Frontend pluginu** (`plugin-ui/index.html`) przy montowaniu wywołuje
   `PluginUiSdk.invokeManualAction('get-crm-context', {})` żeby pobrać dane klienta i wyrenderować
   formularz dla opcjonalnych pól agenta. Po kliknięciu "Otwórz w CRM" wywołuje
   `PluginUiSdk.invokeManualAction('build-crm-url', agentFields)` i na wyniku wywołuje
   `PluginUiSdk.openUrl(response.resultData.url)`.

3. **Host Angular** (`cc-plugin-panel-host`) proxy'uje oba wywołania do backendu z JWT agenta
   — iframe nigdy nie widzi tokenu ani URL API.

Szablon URL może zawierać zmienne w klamrach:

| Zmienna | Wartość |
|---|---|
| `{customerId}` | UUID klienta z bieżącej sesji |
| `{contactId}` | UUID kontaktu (rozmowy) |
| `{customerPhone}` | Numer telefonu (z `CustomerView`) |
| `{customerName}` | Imię i nazwisko klienta |
| Dowolna inna | Pobrana z `installation_config` lub podana przez agenta w formularzu |

Pełny przykład z obsługą `agentParams` (pól wpisywanych przez agenta) i konfiguracją
szablonu URL przez UI admina: [`crm-url-launcher/`](../examples/plugins/crm-url-launcher/).

---

## 11. Powiązane materiały

- **[Plugin Developer Guide](plugin/plugin-development-guide.md)** ([HTML](plugin/plugin-development-guide.html))
  — kompletna referencja techniczna dla zewnętrznych dostawców pluginów: pełne API SDK,
  wszystkie extension pointy, manifest, model uprawnień, integracja UI, procedura checksumu,
  REST API, trzy przykłady z omówieniem. Czytaj ten dokument jeśli budujesz plugin — niniejszy
  rozdział 10 opisuje mechanizm od strony platformy, guide opisuje go od strony dostawcy.
- `ARCHITECTURE.md`, sekcja 11 — pełny projekt architektoniczny, ADR-09…ADR-13, RT-09…RT-14
- `EPIC-28-PLAN.md` — plan wykonania i pełna lista ticketów (DB-042…045, BE-097…107, FE-097…100)
- `TASKS-BACKEND.md`/`TASKS-FRONTEND.md` — szczegółowe specyfikacje per ticket, z notatkami o
  decyzjach podjętych podczas implementacji (odejścia od pierwotnej specyfikacji, znane gapy)
- [Backend – dokumentacja techniczna](04-backend.md) — konwencje pakietów domenowych, wzorce
  repozytoriów (`domain.plugin` stosuje te same zasady co inne domeny)
- [Frontend – dokumentacja techniczna](05-frontend.md) — konwencje komponentów standalone,
  `cc-plugin-panel-host` w `shared/components/`
