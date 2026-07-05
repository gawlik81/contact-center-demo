# Contact Center SaaS — Plugin Developer Guide

> **Audytorium:** zewnętrzni dostawcy tworzący integracje (pluginy) dla platformy Contact Center SaaS.  
> Dokument opisuje **aktualny stan API** — kod jest źródłem prawdy. Wersja: SDK `1.x`, platforma `EPIC-28`.

---

## Spis treści

1. [Czym jest plugin](#1-czym-jest-plugin)
2. [Wymagania wstępne](#2-wymagania-wstępne)
3. [Konfiguracja projektu Maven](#3-konfiguracja-projektu-maven)
4. [PluginEntryPoint — kontrakt pluginu](#4-pluginentrypoint--kontrakt-pluginu)
5. [Punkty rozszerzeń](#5-punkty-rozszerzeń)
6. [PluginContext — dostęp do platformy](#6-plugincontext--dostęp-do-platformy)
7. [Modele danych SDK](#7-modele-danych-sdk)
8. [Manifest pluginu](#8-manifest-pluginu)
9. [Model uprawnień](#9-model-uprawnień)
10. [Ograniczenia bytecode (skan ASM)](#10-ograniczenia-bytecode-skan-asm)
11. [Integracja UI — panel agenta i PluginUiSdk](#11-integracja-ui--panel-agenta-i-pluginuisdk)
12. [Konfiguracja per-tenant](#12-konfiguracja-per-tenant)
13. [Budowa i obliczanie checksumu](#13-budowa-i-obliczanie-checksumu)
14. [Instalacja i zarządzanie cyklem życia](#14-instalacja-i-zarządzanie-cyklem-życia)
15. [REST API — referencja](#15-rest-api--referencja)
16. [Przykłady pluginów](#16-przykłady-pluginów)
17. [Dobre praktyki i antywzorce](#17-dobre-praktyki-i-antywzorce)
18. [Znane ograniczenia](#18-znane-ograniczenia)

---

## 1. Czym jest plugin

Plugin to plik **JAR** dostarczany przez zewnętrznego dostawcę i wgrywany przez administratora tenanta z panelu (`/supervisor/settings/plugins`). Rozszerza zachowanie platformy w pięciu, ściśle zdefiniowanych **punktach rozszerzeń** (extension points) — zdarzeniach platformy, przy których plugin może wykonać własną logikę.

**Typowe zastosowania:**
- Lookup danych klienta w zewnętrznym CRM tuż przed połączeniem agenta z kontaktem
- Zapis wyniku rozmowy i dyspozycji do zewnętrznej bazy danych
- Synchronizacja klientów między platformą a zewnętrznym systemem
- Budowanie i otwieranie URL-i CRM z kontekstem bieżącej rozmowy

**Kluczowe cechy modelu:**
- **Izolacja in-process** — plugin wykonuje się w tym samym JVM co backend, w dedykowanym `ClassLoader`, bez dostępu do klas Springa/JPA
- **Zero dostępu do `ApplicationContext`** — jedyny dostęp do platformy to obiekty SDK (`PluginContext`, `HttpEgressClient`, `DbEgressClient`)
- **Multi-tenancy** — jedna instancja pluginu per `(tenant_id, plugin_key)`; `tenantId` jest zawsze ustalany przez platformę, plugin nie ma wpływu na to, czyje dane zobaczy
- **Izolacja bezpieczeństwa przez uprawnienia** — każdy URL HTTP i każdy host:port bazy danych wymagają jawnej deklaracji w manifeście i zatwierdzenia przez administratora tenanta

---

## 2. Wymagania wstępne

| Wymaganie | Wersja |
|---|---|
| JDK | 21 lub nowszy |
| Maven | 3.8+ |
| `plugin-sdk` JAR | 1.0.0-SNAPSHOT (dostarczany przez platformę lub instalowany lokalnie z repozytorium) |

Uzyskaj `plugin-sdk` z wewnętrznego repozytorium Maven platformy (Nexus/Artifactory) lub — w trybie deweloperskim — zainstaluj go lokalnie ze źródeł:

```bash
# Z katalogu głównego repozytorium platformy:
cd backend
mvn install -pl plugin-sdk
```

Instaluje `com.contactcenter:contact-center-plugin-sdk:1.0.0-SNAPSHOT` do lokalnego `~/.m2`.

---

## 3. Konfiguracja projektu Maven

Plugin to **niezależny projekt Maven** — nie moduł aplikacji hostującej. Minimalne `pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.example</groupId>
  <artifactId>my-crm-plugin</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <!--
      Jedyna zależność deweloperska — scope=provided, bo SDK jest dostarczany przez platformę
      w runtime (ClassLoader pluginu widzi tylko com.contactcenter.pluginsdk.*).
      NIGDY nie pakuj klas SDK do własnego JAR-a.
    -->
    <dependency>
      <groupId>com.contactcenter</groupId>
      <artifactId>contact-center-plugin-sdk</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <scope>provided</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- Wymagane gdy brak <parent> — domyślna wersja ignoruje maven.compiler.release -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <!--
        addMavenDescriptor=false zapewnia reprodukowalność JAR-a (checksumSha256
        musi być stabilny między budowami na tym samym środowisku).
      -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <configuration>
          <archive><addMavenDescriptor>false</addMavenDescriptor></archive>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

**Jeśli plugin ma dodatkowe zależności** (np. biblioteka JSON), dołącz je do JAR-a jako fat JAR (maven-shade-plugin lub maven-assembly-plugin). Pamiętaj, że żadna z dołączonych klas nie może używać API z listy zabronionych (zob. [§10](#10-ograniczenia-bytecode-skan-asm)).

---

## 4. PluginEntryPoint — kontrakt pluginu

Każdy plugin **musi** zawierać klasę implementującą `com.contactcenter.pluginsdk.PluginEntryPoint`:

```java
package com.contactcenter.pluginsdk;

public interface PluginEntryPoint {

    // Wymagane — wołane przy aktywacji instalacji (enable)
    void onActivate(PluginContext context);

    // Wymagane — wołane przy dezaktywacji lub odinstalowaniu
    void onDeactivate();

    // Opcjonalne — override tylko dla deklarowanych extension pointów:
    default PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
        return PreContactConnectResult.empty();
    }
    default void onPostContactEnd(PluginContext ctx, ContactEvent e) { }
    default CustomerSyncResult onCustomerSync(PluginContext ctx, CustomerSyncRequest req) {
        return CustomerSyncResult.noop();
    }
    default void onDispositionSet(PluginContext ctx, DispositionEvent e) { }
    default ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) {
        return ManualActionResult.unsupported();
    }
}
```

**Zasady dotyczące klasy implementującej:**
- Musi posiadać **bezargumentowy konstruktor publiczny** — platforma nie obsługuje wstrzykiwania zależności
- Platforma tworzy **jedną instancję** per `(tenant_id, plugin_key)` — instancja jest współdzielona między wywołaniami tego samego tenanta
- Pola instancji mogą przechowywać stan (np. cache konfiguracji), ale muszą być thread-safe — callbacki mogą być wołane równolegle z różnych wątków
- Wskazujesz tę klasę w manifeście jako `entryPointClass` (pełna kwalifikowana nazwa, np. `com.example.MyCrmPlugin`)

### 4.1 Cykl `onActivate` / `onDeactivate`

**`onActivate(PluginContext context)`** — wołane synchronicznie, gdy administrator tenanta kliknie "Włącz". To jedyne miejsce do:
- Walidacji wymaganej konfiguracji (`context.config()`)
- Inicjalizacji zasobów (np. sprawdzenie połączenia z zewnętrznym systemem)

Rzucenie wyjątku z `onActivate` **przerywa aktywację** — instalacja pozostaje wyłączona, a komunikat wyjątku jest widoczny dla administratora. Jest to poprawne zachowanie; użyj go do sygnalizowania brakującej konfiguracji.

**`onDeactivate()`** — wołane gdy administrator wyłączy lub odinstaluje plugin. Zwolnij zasoby zajęte w `onActivate`. Brak `PluginContext` — do czasu tego wywołania facade jest uznawana za nieważną.

---

## 5. Punkty rozszerzeń

### Tabela przeglądowa

| Extension point | Callback | Tryb | Timeout (domyślny) | Zachowanie przy timeout/błędzie |
|---|---|---|---|---|
| `PRE_CONTACT_CONNECT` | `onPreContactConnect` | **blocking** | 2 000 ms | Connect następuje i tak; agent dostaje `PreContactConnectResult.empty()` |
| `MANUAL_ACTION` | `onManualAction` | **blocking** | 5 000 ms | Agent dostaje HTTP `504` z ciałem JSON |
| `POST_CONTACT_END` | `onPostContactEnd` | fire-and-forget (RabbitMQ) | 30 000 ms | Wynik tylko w logu wywołań; brak efektu dla agenta |
| `CUSTOMER_SYNC` | `onCustomerSync` | fire-and-forget (RabbitMQ) | 30 000 ms | Jak wyżej |
| `DISPOSITION_SET` | `onDispositionSet` | fire-and-forget (RabbitMQ) | 30 000 ms | Jak wyżej |

> **Reguła nadrzędna:** plugin **nigdy nie może zablokować ani spowolnić core flow platformy**. Każde wywołanie jest opakowane `catch(Throwable)` i timeoutem — rzucenie `Error` i zawieszenie wątku są obsługiwane przez hosta.

### 5.1 PRE_CONTACT_CONNECT

Wywoływany tuż **przed** połączeniem agenta z kontaktem (przychodzące połączenie, chat, e-mail). Wynik (`PreContactConnectResult`) jest prezentowany agentowi w panelu klienta.

```java
@Override
public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
    if (e.customerId() == null) {
        return PreContactConnectResult.empty(); // nieznany dzwoniący
    }
    try {
        CustomerView customer = ctx.getCustomer(e.customerId());
        return new PreContactConnectResult(
            Map.of(
                "segment", "VIP",
                "ostatniZakup", "2026-05-01",
                "notatka", customer.firstName() + " — priorytetowy klient"
            ),
            null // brak ostrzeżenia
        );
    } catch (RuntimeException ex) {
        ctx.logger().warn("Lookup klienta nie powiódł się: " + ex.getMessage());
        return PreContactConnectResult.empty();
    }
}
```

`displayData` to dowolna mapa `String → Object` (musi być serializowalna do JSON). `warning` to opcjonalny komunikat wyświetlany agentowi jako ostrzeżenie (np. `"Klient ma otwartą reklamację"`).

### 5.2 POST_CONTACT_END

Wywoływany asynchronicznie po zakończeniu kontaktu, przez kolejkę RabbitMQ (`cc.queue.plugin-invocation`). Nie blokuje pulpitu agenta.

```java
@Override
public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
    try {
        ContactView contact = ctx.getContact(e.contactId());
        // zapis do zewnętrznego systemu...
        ctx.logger().info("Zapisano wynik kontaktu: " + contact.contactId());
    } catch (RuntimeException ex) {
        ctx.logger().warn("Zapis wyniku nie powiódł się: " + ex.getMessage());
        // NIE rzucaj dalej — nack na RabbitMQ może spowodować redelivery w pętli
    }
}
```

> **At-least-once delivery:** RabbitMQ może dostarczyć ten sam event wielokrotnie (przy restarcie procesu, sieci itp.). Implementuj operacje idempotentnie — np. `INSERT ... WHERE NOT EXISTS` zamiast prostego `INSERT`.

### 5.3 CUSTOMER_SYNC

Wywoływany asynchronicznie, gdy platforma prosi o synchronizację rekordu klienta z zewnętrznym systemem. Powód (`reason`) to string — np. `"CUSTOMER_CREATED"`, `"CUSTOMER_UPDATED"`, `"MANUAL_REQUEST"`.

```java
@Override
public CustomerSyncResult onCustomerSync(PluginContext ctx, CustomerSyncRequest req) {
    try {
        CustomerView customer = ctx.getCustomer(req.customerId());
        // synchronizacja z CRM...
        return new CustomerSyncResult(true, "Zsynchronizowano z CRM (ID: crm-123)");
    } catch (RuntimeException ex) {
        return new CustomerSyncResult(false, "Błąd synchronizacji: " + ex.getMessage());
    }
}
```

Wynik (`synced=true`) może być odzwierciedlony w UI admina jako "ostatnia synchronizacja o ...".

### 5.4 DISPOSITION_SET

Wywoływany asynchronicznie, gdy agent ustawi dyspozycję (kod wyniku) kontaktu.

```java
@Override
public void onDispositionSet(PluginContext ctx, DispositionEvent e) {
    try {
        // e.dispositionCode() — kod ustawiony przez agenta, np. "RESOLVED", "CALLBACK"
        // e.agentId() — który agent ustawił dyspozycję
        // zapis do zewnętrznego systemu ticketingowego...
    } catch (RuntimeException ex) {
        ctx.logger().warn("Błąd zapisu dyspozycji: " + ex.getMessage());
    }
}
```

### 5.5 MANUAL_ACTION

Wywoływany **synchronicznie**, gdy agent kliknie przycisk pluginu w panelu UI (toolbar lub panel boczny). Agent czeka na odpowiedź. Identyfikuje akcję przez `actionId` zgodny z deklaracją `manualActions` w manifeście.

```java
@Override
public ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) {
    return switch (req.actionId()) {
        case "open-ticket" -> handleOpenTicket(ctx, req);
        case "get-crm-context" -> handleGetCrmContext(ctx, req);
        default -> ManualActionResult.unsupported();
    };
}

private ManualActionResult handleOpenTicket(PluginContext ctx, ManualActionRequest req) {
    try {
        String url = "https://helpdesk.example.com/ticket/new?customer=" + req.customerId();
        return new ManualActionResult(true, Map.of("url", url), null);
    } catch (RuntimeException ex) {
        ctx.logger().error("Błąd otwierania ticketu", ex);
        return new ManualActionResult(false, Map.of(), "Nie można otworzyć ticketu: " + ex.getMessage());
    }
}
```

---

## 6. PluginContext — dostęp do platformy

`PluginContext` to **jedyny** obiekt, przez który plugin sięga do platformy. Budowany jest na nowo przy każdym wywołaniu, z `tenantId` ustalonym przez hosta.

```java
public interface PluginContext {
    CustomerView   getCustomer(UUID customerId);
    void           updateCustomerFields(UUID customerId, Map<String, Object> customFields);
    ContactView    getContact(UUID contactId);
    void           appendContactNote(UUID contactId, String note);
    HttpEgressClient httpClient();
    DbEgressClient   dbClient();
    PluginLogger     logger();
    PluginConfig     config();
}
```

### 6.1 Odczyt i zapis danych klienta

```java
// Odczyt snapshotu klienta (niemutowalny record)
CustomerView customer = ctx.getCustomer(customerId);

// Zapis danych pluginu do namespace'u pluginu w custom_fields klienta
ctx.updateCustomerFields(customerId, Map.of(
    "lastCrmId",   "CRM-12345",
    "syncedAt",    Instant.now().toString()
));
```

`updateCustomerFields` zapisuje wyłącznie do `customer.custom_fields.plugins.<pluginKey>` — nigdy do typowanych kolumn platformy ani do namespace'u innego pluginu. Odczyt własnych danych: `customer.customFields().get("plugins").get(pluginKey)`.

### 6.2 Odczyt kontaktu i dopisywanie notatek

```java
ContactView contact = ctx.getContact(contactId);

// Dopisz notatkę do historii kontaktu (widoczna agentowi i supervisorowi)
ctx.appendContactNote(contactId, "Klient zsynchronizowany z CRM — ID: CRM-12345");
```

### 6.3 Klient HTTP (`HttpEgressClient`)

Jedyny sposób wykonania zewnętrznych żądań HTTP — tylko `GET` i `POST`, tylko do hostów zadeklarowanych jako `http:egress:<host>` w `granted_permissions`.

```java
HttpEgressClient http = ctx.httpClient();

HttpResponse resp = http.get(
    "https://api.external-crm.example/customers/" + customerId,
    Map.of("Authorization", "Bearer " + ctx.config().getOrDefault("apiKey", ""))
);

if (resp.statusCode() == 200) {
    String body = new String(resp.body(), StandardCharsets.UTF_8);
    // przetworzenie odpowiedzi...
}

// POST z ciałem JSON
byte[] requestBody = "{\"customerId\":\"...\"}" .getBytes(StandardCharsets.UTF_8);
HttpResponse postResp = http.post(
    "https://api.external-crm.example/sync",
    Map.of(
        "Content-Type", "application/json",
        "Authorization", "Bearer " + ctx.config().get("apiKey").orElseThrow()
    ),
    requestBody
);
```

Wywołanie hosta spoza listy uprawnień skutkuje `RuntimeException` przed wysłaniem żądania.

### 6.4 Klient bazy danych zewnętrznej (`DbEgressClient`)

Pozwala wykonać parametryzowany SQL (`INSERT`/`UPDATE`/`DELETE`) na zewnętrznej bazie danych tenanta — bez ujawniania credentiali pluginowi (host czyta je z `installation_config`).

```java
// Plugin NIGDY nie widzi JDBC URL ani hasła — tylko wywołuje:
int rows = ctx.dbClient().executeUpdate(
    "INSERT INTO crm_log (contact_id, event_type, occurred_at) VALUES (?, ?, ?)",
    List.of(contactId, "CONTACT_ENDED", Timestamp.valueOf(localDateTime))
);
```

Szczegóły konfiguracji kluczy `jdbcUrl`, `dbUsername`, `dbPassword` — zob. [§12](#12-konfiguracja-per-tenant).

### 6.5 Logger diagnostyczny

```java
ctx.logger().info("Akcja wykonana pomyślnie");
ctx.logger().warn("Brak wyników — kontynuuję z pustym stanem");
ctx.logger().error("Nieoczekiwany błąd", exception);
```

Logi są przechwytywane przez platformę i zapisywane odrębnie od logów aplikacji. Widoczne w historii wywołań (`/api/supervisor/plugins/{id}/invocations`).

---

## 7. Modele danych SDK

Wszystkie modele to niemutowalne `record`y Java — nigdy encje JPA.

### CustomerView

```java
record CustomerView(
    UUID           customerId,
    String         firstName,      // null jeśli nieznane
    String         lastName,       // null jeśli nieznane
    List<String>   emails,         // never null, może być pusta
    List<String>   phoneNumbers,   // E.164, never null, może być pusta
    Map<String, Object> customFields, // custom_fields z bazy (w tym plugins.<pluginKey>)
    Instant        createdAt
) {}
```

### ContactView

```java
record ContactView(
    UUID    contactId,
    UUID    customerId,   // null gdy kontakt nie jest powiązany z klientem
    String  channel,      // "VOICE", "EMAIL", "CHAT", "SOCIAL"
    String  direction,    // "INBOUND", "OUTBOUND"
    String  status,       // "QUEUED", "ACTIVE", "COMPLETED", ...
    UUID    agentId,      // null gdy nieprzypisany
    UUID    queueId,      // null gdy brak
    Instant startedAt,
    Instant endedAt       // null gdy kontakt wciąż trwa
) {}
```

### ContactEvent (PRE_CONTACT_CONNECT / POST_CONTACT_END)

```java
record ContactEvent(
    UUID    contactId,
    UUID    customerId,     // null gdy nieznany dzwoniący
    String  extensionPoint, // "PRE_CONTACT_CONNECT" lub "POST_CONTACT_END"
    Instant occurredAt
) {}
```

### DispositionEvent

```java
record DispositionEvent(
    UUID    contactId,
    UUID    customerId,      // null gdy nieznany
    String  dispositionCode, // kod wybrany przez agenta, np. "RESOLVED"
    UUID    agentId,
    Instant setAt
) {}
```

### CustomerSyncRequest

```java
record CustomerSyncRequest(
    UUID   customerId,
    String reason  // "CUSTOMER_CREATED", "CUSTOMER_UPDATED", "MANUAL_REQUEST"
) {}
```

### ManualActionRequest

```java
record ManualActionRequest(
    String             actionId,    // odpowiada manualActions[].actionId z manifestu
    UUID               contactId,   // null gdy brak aktywnego kontaktu
    UUID               customerId,  // null gdy nieznany
    Map<String, Object> parameters  // parametry z UI (never null, może być pusta)
) {}
```

### Typy wynikowe

```java
// PRE_CONTACT_CONNECT — dane do wyświetlenia agentowi
record PreContactConnectResult(Map<String, Object> displayData, String warning) {
    static PreContactConnectResult empty() { ... }
}

// MANUAL_ACTION — wynik zwracany do UI agenta
record ManualActionResult(boolean success, Map<String, Object> resultData, String message) {
    static ManualActionResult unsupported() { ... }
}

// CUSTOMER_SYNC — wynik synchronizacji
record CustomerSyncResult(boolean synced, String message) {
    static CustomerSyncResult noop() { ... }
}
```

---

## 8. Manifest pluginu

Każdy JAR **musi** zawierać plik `META-INF/plugin-manifest.json`. Jest walidowany względem JSON Schema przy każdym uploadzie.

### Pełny przykład manifestu

```json
{
  "pluginKey":       "acme-crm-sync",
  "displayName":     "Acme CRM Sync",
  "version":         "1.3.0",
  "vendor":          "Acme Sp. z o.o.",
  "vendorContact":   "support@acme.example",
  "sdkVersion":      "1.x",
  "entryPointClass": "com.acme.contactcenter.plugin.AcmeCrmPlugin",
  "extensionPoints": [
    "PRE_CONTACT_CONNECT",
    "POST_CONTACT_END",
    "CUSTOMER_SYNC",
    "DISPOSITION_SET",
    "MANUAL_ACTION"
  ],
  "permissions": [
    "customer:read",
    "customer:update",
    "contact:read",
    "contact:update",
    "http:egress:api.acme-crm.example",
    "db:egress:crm-db.internal:5432"
  ],
  "uiPanels": [
    {
      "panelId":    "acme-crm-panel",
      "mountPoint": "AGENT_DESKTOP_SIDE_PANEL",
      "url":        "classpath:/plugin-ui/index.html",
      "sandbox":    "allow-scripts allow-forms"
    }
  ],
  "manualActions": [
    {
      "actionId":   "open-in-crm",
      "label":      "Otwórz w CRM",
      "mountPoint": "AGENT_DESKTOP_TOOLBAR"
    },
    {
      "actionId":   "get-crm-context",
      "label":      "Pobierz dane CRM",
      "mountPoint": "AGENT_DESKTOP_SIDE_PANEL"
    }
  ],
  "checksumSha256": "<64-znakowy hex SHA-256 zawartości JAR bez manifestu>"
}
```

### Opis pól

| Pole | Wymagane | Format/Walidacja |
|---|---|---|
| `pluginKey` | tak | `^[a-z0-9]([a-z0-9-]{0,98}[a-z0-9])?$` — unikalny identyfikator pluginu |
| `displayName` | tak | 1–200 znaków |
| `version` | tak | string semver, 1–50 znaków |
| `vendor` | tak | 1–200 znaków (nazwa Twojej firmy) |
| `vendorContact` | nie | max 200 znaków (e-mail/URL support) |
| `sdkVersion` | tak | `"1.x"` dla bieżącej wersji platformy |
| `entryPointClass` | tak | pełna kwalifikowana nazwa klasy implementującej `PluginEntryPoint` |
| `extensionPoints` | tak, ≥1 | podzbiór 5 wartości z §5 |
| `permissions` | tak (może być `[]`) | zob. [§9](#9-model-uprawnień) |
| `uiPanels` | nie | lista `{panelId, mountPoint, url, sandbox?}` |
| `manualActions` | nie | lista `{actionId, label, mountPoint}` |
| `checksumSha256` | tak | 64-znakowy hex SHA-256 — zob. [§13](#13-budowa-i-obliczanie-checksumu) |

### mountPoint — dostępne punkty montowania

| Wartość | Opis |
|---|---|
| `AGENT_DESKTOP_SIDE_PANEL` | Panel boczny w pulpicie agenta — widoczny podczas aktywnego kontaktu i wrap-up |
| `AGENT_DESKTOP_TOOLBAR` | Przycisk w toolbarze pulpitu agenta |
| `SUPERVISOR_DASHBOARD` | Panel na dashboardzie supervisora *(zadeklarowany, lecz jeszcze niepodłączony do żadnej strony — plugin może deklarować, ale nic nie wyrenderuje)* |

---

## 9. Model uprawnień

Pole `permissions` w manifeście deklaruje, czego plugin potrzebuje. Administrator tenanta zatwierdza podzbiór tych uprawnień przy instalacji. Platforma **nigdy nie auto-grantuje** pełnego zestawu — wymagana jest jawna zgoda administratora.

### Dostępne uprawnienia

| Uprawnienie | Pozwala na |
|---|---|
| `customer:read` | `ctx.getCustomer(id)` |
| `customer:update` | `ctx.updateCustomerFields(id, fields)` |
| `contact:read` | `ctx.getContact(id)` |
| `contact:update` | `ctx.appendContactNote(id, note)` |
| `http:egress:<host>` | Wywołania HTTP do podanego hosta (np. `http:egress:api.crm.example`) |
| `db:egress:<host>:<port>` | Połączenie z zewnętrzną bazą danych na podanym `host:port` (np. `db:egress:crm-db:5432`) |

**Zasada minimalnych uprawnień:** deklaruj tylko to, czego faktycznie używasz. Uprawnienia spoza zamkniętego zbioru są odrzucane przy uploadzie. Host oraz port w `db:egress` są **obydwa wymagane**.

---

## 10. Ograniczenia bytecode (skan ASM)

Przy każdym uploadzie — **przed ładowaniem jakiejkolwiek klasy** — platforma skanuje statycznie bytecode każdej klasy w JAR-ze (Apache ASM). JAR zostaje odrzucony (status `REJECTED`) jeśli którakolwiek klasa odwołuje się do:

| Zablokowane API | Powód |
|---|---|
| `java.lang.reflect.AccessibleObject#setAccessible` i rodzina | Obejście widoczności refleksji |
| `java.lang.Thread#getContextClassLoader` / `#setContextClassLoader` | Dostęp do ClassLoader aplikacji |
| `java.util.ServiceLoader` (cała klasa) | Ładowanie implementacji przez TCCL |
| `java.lang.ProcessBuilder` (cała klasa) | Uruchamianie procesów systemowych |
| `java.nio.file.*` (cały pakiet) | Dostęp do systemu plików |
| `sun.misc.*` (cały pakiet) | Niskopoziomowe JDK internals |
| Podklasy `java.lang.ClassLoader` | Tworzenie własnych class loaderów |

**Praktyczna konsekwencja:** pisz standardowy kod biznesowy Javy. Jeśli używasz biblioteki firmy trzeciej (fat JAR), sprawdź czy nie używa ona wewnętrznie żadnego z zablokowanych API — wiele bibliotek serializacji i DI używa `setAccessible` lub `ServiceLoader`. Taki JAR zostanie odrzucony z komunikatem wskazującym naruszającą klasę.

Popularne bezpieczne biblioteki JSON: **Gson**, **Jackson** (wszystkie warianty), **minimal-json** — nie używają żadnego z zablokowanych API i mogą być bezpiecznie dołączane do JAR-a pluginu.

---

## 11. Integracja UI — panel agenta i PluginUiSdk

### 11.1 Sandboxowany iframe

Jeśli manifest deklaruje `uiPanels` z `mountPoint: "AGENT_DESKTOP_SIDE_PANEL"`, pulpit agenta renderuje Twój `plugin-ui/index.html` (rozpakowany z JAR-a) w **sandboxowanym iframe**:

```html
<iframe
  src="/plugin-assets/{installationId}/index.html"
  sandbox="allow-scripts allow-forms"
  referrerpolicy="no-referrer"
></iframe>
```

**Ważne ograniczenia:**
- **Brak `allow-same-origin`** — Twój JS w iframe nie ma dostępu do `localStorage`, cookies hosta ani do `/api/**` z JWT agenta
- **Panel widoczny tylko podczas aktywnego kontaktu lub wrap-up** — przy braku kontaktu iframe jest odmontowywany; przy każdym nowym kontakcie montowany od nowa (pełna reinicjalizacja)
- **Jedyny kanał komunikacji z hostem:** `postMessage` przez `PluginUiSdk`

### 11.2 PluginUiSdk

Wstrzyknij SDK w swoim `plugin-ui/index.html`:

```html
<script src="/plugin-ui-sdk.js"></script>
```

> Backend automatycznie zastępuje ten tag inlineowaną zawartością SDK przy serwowaniu `index.html` — nie musisz robić nic więcej.

Po załadowaniu strony dostępne jest `window.PluginUiSdk`:

```typescript
// Pobranie kontekstu bieżącego kontaktu
const context = await PluginUiSdk.getContext();
// context: { tenantId: string, contactId: string | null, customerId: string | null }

// Wywołanie akcji backendowej pluginu
const result = await PluginUiSdk.invokeManualAction('get-crm-context', {
    agentNote: 'przykładowy parametr'
});
// result: { success: boolean, resultData: object, message: string | null }

// Otwarcie URL w nowej zakładce (przez hosta — iframe nie może sam wywołać window.open)
PluginUiSdk.openUrl('https://crm.example.com/customer/123');

// Zmiana wysokości iframe (fire-and-forget)
PluginUiSdk.requestResize(400);

// Wyświetlenie powiadomienia w UI hosta (fire-and-forget)
PluginUiSdk.notify('Dane zsynchronizowane', 'info');
// severity: 'info' | 'warning' | 'error'
```

### 11.3 Przykład minimalnego panelu bocznego

```html
<!DOCTYPE html>
<html lang="pl">
<head>
  <meta charset="UTF-8">
  <style>
    body { font-family: sans-serif; padding: 12px; margin: 0; }
    #content { font-size: 14px; }
    button { margin-top: 8px; padding: 6px 12px; cursor: pointer; }
  </style>
  <script src="/plugin-ui-sdk.js"></script>
</head>
<body>
  <div id="content">Ładowanie...</div>
  <button id="btn-open">Otwórz w CRM</button>
  <script>
    async function init() {
      const ctx = await PluginUiSdk.getContext();
      if (!ctx.customerId) {
        document.getElementById('content').textContent = 'Brak klienta.';
        return;
      }

      const result = await PluginUiSdk.invokeManualAction('get-crm-context', {});
      if (result.success) {
        document.getElementById('content').textContent =
          'Klient: ' + result.resultData.customerName;
        document.getElementById('btn-open').onclick = () => {
          PluginUiSdk.openUrl(result.resultData.crmUrl);
        };
      } else {
        document.getElementById('content').textContent = 'Błąd: ' + result.message;
      }
    }
    init();
  </script>
</body>
</html>
```

Umieść plik jako `src/main/resources/plugin-ui/index.html` — zostanie rozpakowany z JAR-a i serwowany przez `/plugin-assets/{installationId}/`.

---

## 12. Konfiguracja per-tenant

Każda instalacja może mieć własną konfigurację ustawianą przez administratora tenanta. Plugin odczytuje ją przez `ctx.config()`.

### 12.1 Odczyt konfiguracji w pluginie

```java
@Override
public void onActivate(PluginContext context) {
    // Wymagana — rzuć jeśli brak
    String apiKey = context.config().get("apiKey")
        .orElseThrow(() -> new IllegalStateException(
            "Brakuje konfiguracji 'apiKey' — ustaw ją przed włączeniem pluginu."
        ));

    // Opcjonalna z wartością domyślną
    String baseUrl = context.config().getOrDefault("baseUrl", "https://api.default-crm.example");
    String tableName = context.config().getOrDefault("dbTable", "call_results");
}
```

### 12.2 Ustawianie konfiguracji przez administratora (REST API)

Administrator wywołuje `PATCH` po instalacji, przed włączeniem pluginu:

```http
PATCH /api/supervisor/plugins/installations/{installationId}/config
Content-Type: application/json
Authorization: Bearer <JWT supervisora/admina>

{
  "config": {
    "apiKey":    "sk-abc123xyz",
    "baseUrl":   "https://crm.tenant.example",
    "dbTable":   "call_results"
  }
}
```

**Semantyka REPLACE** — całkowita zamiana zestawu kluczy przy każdym `PATCH`. Wartości wrażliwe (klucze API, hasła) są szyfrowane AES-256-GCM po stronie platformy i **nigdy nie wracają w żadnej odpowiedzi API** — można je tylko nadpisać nowym `PATCH`.

### 12.3 Konfiguracja dla `DbEgressClient`

Gdy plugin deklaruje `db:egress:<host>:<port>` w uprawnieniach, administrator musi ustawić trzy klucze:

| Klucz | Opis | Przykład |
|---|---|---|
| `jdbcUrl` | JDBC URL zewnętrznej bazy | `jdbc:postgresql://crm-db:5432/crm` |
| `dbUsername` | Użytkownik bazy | `crm_plugin_user` |
| `dbPassword` | Hasło (szyfrowane) | `s3cr3tP@ssword` |

> **Ważne:** `host:port` z `jdbcUrl` musi dokładnie pasować do `db:egress:<host>:<port>` z manifestu. Niezgodność skutkuje `SecurityException` przy każdym wywołaniu `executeUpdate`.

---

## 13. Budowa i obliczanie checksumu

Platforma weryfikuje integralność JAR-a obliczając SHA-256 z zawartości wszystkich wpisów **z wyłączeniem** `META-INF/plugin-manifest.json`. Hash musi zgadzać się z polem `checksumSha256` w manifeście.

### Procedura budowy (3 kroki)

**Krok 1: pierwsza budowa z placeholderem**

Wypełnij `checksumSha256` 64 zerami w manifeście, następnie:

```bash
mvn -q package
```

**Krok 2: oblicz checksum**

Użyj klasy `ChecksumTool` dołączonej do każdego przykładowego pluginu lub napisz własną:

```bash
java -cp target/classes com.example.tooling.ChecksumTool \
    target/my-crm-plugin-1.0.0.jar
```

Wynik: 64-znakowy hex string, np. `a3f8c1...`.

Alternatywnie — algorytm w pseudokodzie:

```
sha256 = new SHA256MessageDigest
for each entry in JAR (sorted by name):
    if entry.name == "META-INF/plugin-manifest.json": skip
    sha256.update(entry.name.getBytes(UTF-8))
    sha256.update(entry.content)
return hex(sha256.digest())
```

**Krok 3: wklej checksum i przebuduj**

Zastąp `"checksumSha256": "0000...0000"` wynikiem z kroku 2 w `src/main/resources/META-INF/plugin-manifest.json`, następnie:

```bash
mvn -q package
```

Ponieważ checksum jest liczony z wyłączeniem manifestu, zmiana tylko tego pola **nie zmienia** wyniku. Wynikowy JAR jest gotowy do uploadu.

> **Uwaga o reprodukowalności:** SHA-256 JAR-a zależy od środowiska build (wersja JDK, Maven, OS). Zawsze przelicz checksum na tym samym środowisku, na którym budujesz finalny artefakt. Platforma zwróci komunikat `Checksum mismatch: obliczony=<X>` — wklej `<X>` do manifestu i przebuduj.

---

## 14. Instalacja i zarządzanie cyklem życia

### Cykl: upload → instalacja → aktywacja

```
1. POST /api/supervisor/plugins   (upload JAR)
   → PluginValidationService:
      a. Rozmiar ≤ 50 MB, magic bytes ZIP/JAR
      b. SHA-256 (bez manifestu) = manifest.checksumSha256
      c. JSON Schema manifestu
      d. Statyczny skan ASM (§10)
      e. entryPointClass istnieje w JAR i implementuje PluginEntryPoint
   → status: VALIDATED lub REJECTED

2. POST /api/supervisor/plugins/{pluginVersionId}/install
   → tworzy instalację (enabled=false)
   → administrator zatwierdza uprawnienia

3. PATCH /api/supervisor/plugins/installations/{id}/config
   → ustawia konfigurację tenanta (klucze API, JDBC URL itp.)

4. POST /api/supervisor/plugins/installations/{id}/enable
   → platforma ładuje JAR do ClassLoader
   → instancjonuje entryPointClass (konstruktor bezargumentowy)
   → woła onActivate(context)
   → jeśli onActivate rzuci: instalacja pozostaje disabled, błąd widoczny dla admina
   → jeśli sukces: plugin aktywny, gotowy do przyjmowania wywołań

5. [normalna praca — callbacki z §5]

6. POST /api/supervisor/plugins/installations/{id}/disable
   → woła onDeactivate()
   → plugin usuwany z rejestru (nie przyjmuje nowych wywołań)
```

### Panel administracyjny

Dostęp: `/supervisor/settings/plugins` (rola SUPERVISOR lub ADMIN).

Operacje dostępne przez UI:
- Upload JAR-a (drag&drop lub wybór pliku)
- Przeglądanie katalogu wersji i ich statusu (VALIDATED/REJECTED)
- Instalacja i konfiguracja
- Włączanie/wyłączanie
- Historia wywołań z paginacją i filtrem po statusie

---

## 15. REST API — referencja

Wszystkie endpointy wymagają JWT (Bearer token) z odpowiednią rolą. Odpowiedzi w formacie JSON.

### Zarządzanie wersjami (rola SUPERVISOR/ADMIN)

| Metoda | Ścieżka | Opis |
|---|---|---|
| `POST` | `/api/supervisor/plugins` | Upload JAR (multipart, pole `file`) — zwraca `PluginVersionDto` |
| `GET` | `/api/supervisor/plugins/catalog` | Lista wersji wgranych przez bieżącego tenanta |
| `DELETE` | `/api/supervisor/plugins/catalog/{pluginVersionId}` | Usuwa wersję; 409 gdy istnieje instalacja tej wersji |

### Zarządzanie instalacjami (rola SUPERVISOR/ADMIN)

| Metoda | Ścieżka | Opis |
|---|---|---|
| `GET` | `/api/supervisor/plugins` | Lista instalacji tenanta (w tym disabled) |
| `POST` | `/api/supervisor/plugins/{pluginVersionId}/install` | Instalacja wersji |
| `POST` | `/api/supervisor/plugins/installations/{id}/enable` | Aktywacja |
| `POST` | `/api/supervisor/plugins/installations/{id}/disable` | Dezaktywacja |
| `POST` | `/api/supervisor/plugins/installations/{id}/rollback/{targetId}` | Atomowe przełączenie aktywnej instalacji |
| `DELETE` | `/api/supervisor/plugins/installations/{id}` | Odinstalowanie |
| `PATCH` | `/api/supervisor/plugins/installations/{id}/config` | Ustawienie konfiguracji (REPLACE) |
| `GET` | `/api/supervisor/plugins/{installationId}/invocations` | Historia wywołań (paginowana) |

### Pulpit agenta (rola AGENT/SUPERVISOR/ADMIN)

| Metoda | Ścieżka | Opis |
|---|---|---|
| `GET` | `/api/agent/plugins` | Lista aktywnych instalacji (enabled=true) |
| `POST` | `/api/agent/plugins/{installationId}/manual-action/{actionId}` | Wywołanie MANUAL_ACTION; 504 przy timeout |

### Zasoby UI (publiczne — bez JWT)

| Metoda | Ścieżka | Opis |
|---|---|---|
| `GET` | `/plugin-assets/{installationId}/**` | Statyczne assety `plugin-ui/` z JAR-a |
| `GET` | `/plugin-ui-sdk.js` | Skrypt PluginUiSdk |

---

## 16. Przykłady pluginów

Trzy działające przykłady dostępne w katalogu `examples/plugins/` repozytorium platformy.

### 16.1 `customer-google-lookup` — lookup klienta + panel boczny

**Extension points:** `PRE_CONTACT_CONNECT`, `MANUAL_ACTION`  
**Uprawnienia:** `customer:read`, `http:egress:www.googleapis.com`  
**Demonstrowane:** `HttpEgressClient`, panel `AGENT_DESKTOP_SIDE_PANEL`, `PluginUiSdk.invokeManualAction`, `PluginUiSdk.requestResize`

Przy połączeniu z klientem automatycznie wyszukuje jego dane w Google Custom Search i prezentuje wyniki w panelu bocznym agenta. Przycisk w toolbarze pozwala odświeżyć wyszukiwanie na żądanie.

**Wymagana konfiguracja:**
```json
{
  "googleApiKey":          "TWÓJ_KLUCZ_API_GOOGLE",
  "googleSearchEngineId":  "TWOJE_CX"
}
```

### 16.2 `customer-callresult-db-sync` — zapis wyniku do zewnętrznej bazy

**Extension points:** `POST_CONTACT_END`, `DISPOSITION_SET`  
**Uprawnienia:** `contact:read`, `db:egress:crm-demo-db:5432`  
**Demonstrowane:** `DbEgressClient`, at-least-once idempotency (`INSERT WHERE NOT EXISTS`), konfiguracja JDBC

Zapisuje zakończone kontakty i dyspozycje do zewnętrznej bazy danych CRM tenanta. Zawiera `docker-compose.yml` z PostgreSQL 16 do testów lokalnych.

**Wymagany schemat tabeli docelowej:**
```sql
CREATE TABLE call_results (
    id               BIGSERIAL PRIMARY KEY,
    contact_id       UUID         NOT NULL,
    customer_id      UUID,
    event_type       VARCHAR(32)  NOT NULL,  -- 'CONTACT_ENDED' | 'DISPOSITION_SET'
    channel          VARCHAR(32),
    direction        VARCHAR(16),
    status           VARCHAR(32),
    agent_id         UUID,
    disposition_code VARCHAR(64),
    occurred_at      TIMESTAMP    NOT NULL
);
-- Wymagany partial unique index (at-least-once idempotency):
CREATE UNIQUE INDEX uq_call_results_contact_ended
    ON call_results (contact_id) WHERE event_type = 'CONTACT_ENDED';
```

**Wymagana konfiguracja:**
```json
{
  "jdbcUrl":    "jdbc:postgresql://crm-demo-db:5432/crm",
  "dbUsername": "crm_plugin_user",
  "dbPassword": "hasło"
}
```

### 16.3 `crm-url-launcher` — otwieranie URL-a CRM z kontekstem rozmowy

**Extension points:** `PRE_CONTACT_CONNECT`, `MANUAL_ACTION`  
**Uprawnienia:** `customer:read`, `contact:read`  
**Demonstrowane:** `PluginUiSdk.openUrl`, szablon URL z podstawieniem zmiennych, `AGENT_DESKTOP_SIDE_PANEL`, zero zewnętrznych zależności

Najprostszy wzorzec integracji UI: agent klika przycisk w panelu, plugin buduje URL z szablonu (z podstawieniem `{customerId}`, `{customerPhone}` itp. i opcjonalnymi polami wpisywanymi przez agenta) i otwiera profil klienta w zewnętrznym CRM w nowej zakładce.

**Zmienne automatyczne w szablonie URL:**

| Placeholder | Źródło |
|---|---|
| `{customerId}` | UUID klienta |
| `{contactId}` | UUID kontaktu |
| `{customerPhone}` | Pierwszy numer telefonu (URL-encoded) |
| `{customerEmail}` | Pierwszy adres e-mail (URL-encoded) |
| `{customerFirstName}` | Imię klienta |
| `{customerLastName}` | Nazwisko klienta |

**Wymagana konfiguracja:**
```json
{
  "crmUrlTemplate": "https://crm.example.com/customer?id={customerId}&phone={customerPhone}&ref={agentRef}",
  "agentParams":    "agentRef"
}
```

---

## 17. Dobre praktyki i antywzorce

### ✅ Dobre praktyki

**Obsługa błędów w callbackach:** zawsze łap `RuntimeException` i zwracaj wynik awaryjny zamiast propagować wyjątek. Platforma i tak opakuje wywołanie własnym `catch(Throwable)`, ale jawna obsługa czyni zachowanie przewidywalnym.

```java
@Override
public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
    if (e.customerId() == null) return PreContactConnectResult.empty();
    try {
        // logika...
    } catch (RuntimeException ex) {
        ctx.logger().warn("Błąd lookup: " + ex.getMessage());
        return PreContactConnectResult.empty(); // agent połączy się bez danych
    }
}
```

**Idempotentność w fire-and-forget:** przy `POST_CONTACT_END` i `DISPOSITION_SET` zawsze implementuj guard przed duplikatami — RabbitMQ gwarantuje at-least-once delivery.

**Walidacja konfiguracji w `onActivate`:** sprawdzaj wszystkie wymagane klucze przy starcie, nie przy każdym callbacku.

**Nie trzymaj zewnętrznych połączeń w polach instancji:** `DbEgressClient` jest bezstanowy per wywołanie — brak connection poolingu po stronie pluginu jest celowy.

**Bezpieczna interpolacja nazw tabel w SQL:** gdy pozwalasz administratorowi konfigurować nazwę tabeli, waliduj ją regexem przed interpolacją do SQL-a.

```java
private static final Pattern TABLE_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
if (!TABLE_NAME.matcher(tableName).matches()) {
    throw new IllegalStateException("Nieprawidłowa nazwa tabeli: " + tableName);
}
```

### ❌ Antywzorce

**Przechowywanie stanu tenanta w polach statycznych:** statyczne pola są współdzielone między wszystkimi instancjami JVM (w tym innymi tenantami), co narusza izolację multi-tenancy.

**Rzucanie wyjątku z `onPostContactEnd`/`onDispositionSet`:** nie blokuje agenta (fire-and-forget), ale może spowodować NACK na RabbitMQ i nieskończone redelivery. Zawsze łap i loguj.

**Używanie `Thread.sleep` lub synchronicznych blokad w callbackach blocking:** `PRE_CONTACT_CONNECT` i `MANUAL_ACTION` mają timeout — zablokowanie wątku skutkuje timeout-em i powrotem do domyślnego wyniku.

**Parsowanie `customerId` z parametrów `ManualActionRequest` zamiast `req.customerId()`:** pole `customerId` w `ManualActionRequest` jest już wypełniane przez hosta z bieżącej sesji — nie wymagaj od agenta podania UUID klienta jako parametru.

---

## 18. Znane ograniczenia

| Ograniczenie | Szczegóły |
|---|---|
| **Izolacja in-process** | Plugin wykonuje się w tym samym JVM co backend; `ClassLoader` + skan ASM to warstwy obrony, nie pełna sandbox VM. Instaluj wyłącznie pluginy z zaufanych źródeł. |
| **`logger()` nie zapisuje do bazy diagnostycznej** | `ctx.logger()` aktualnie pisze do logów aplikacji z prefiksem `[PluginLog]`, nie do tabeli `plugin_invocation_log`. Logi wywołań widoczne w UI (endpoint `/invocations`) zawierają status (`SUCCESS`/`FAILED`/`TIMED_OUT`), ale nie logi ad-hoc. |
| **Circuit breaker in-memory** | Po 5 kolejnych błędach (`FAILED`/`TIMED_OUT`) instalacja przechodzi w `DEGRADED` i kolejne wywołania są pomijane jako `CIRCUIT_OPEN` do pierwszego sukcesu. Stan resetu jest per-process — restart JVM resetuje licznik. |
| **Brak `allow-same-origin` w iframe** | Plugin UI nie ma dostępu do `localStorage`/cookies hosta. Jedyny kanał: `PluginUiSdk.invokeManualAction` (proxy przez hosta z JWT agenta). |
| **`SUPERVISOR_DASHBOARD` mountPoint** | Zadeklarowany w SDK, ale aktualnie żadna strona supervisora go nie renderuje. |
| **`db:egress` — brak connection poolingu** | Jedno połączenie JDBC per wywołanie `executeUpdate`. Wystarczające dla fire-and-forget extension pointów, ale nieodpowiednie do intensywnych zapytań. |

---

*Dokumentacja dla wersji SDK `1.x`. Kod jest źródłem prawdy — w razie rozbieżności sprawdź Javadoc klas SDK i źródła przykładowych pluginów.*
