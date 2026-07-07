# CRM URL Launcher — przykładowy plugin

Przykładowy, w pełni działający plugin dla systemu rozszerzeń Contact Center (EPIC-28),
demonstrujący budowanie dynamicznych URL-i z szablonu konfigurowanego per-tenant.

**Co robi:** panel boczny w pulpicie agenta, aktywowany automatycznie w momencie rozmowy z
klientem. Jednym kliknięciem otwiera profil klienta w zewnętrznym systemie CRM — URL budowany
jest z szablonu konfigurowanego przez administratora, z automatycznym podstawieniem danych
klienta (UUID, telefon, email, imię, nazwisko) oraz opcjonalnych parametrów wpisywanych ręcznie
przez agenta. Demonstruje `PRE_CONTACT_CONNECT` (podgląd danych przy połączeniu),
`MANUAL_ACTION` (budowanie URL i otwieranie CRM z panelu) i `PluginUiSdk.openUrl()`.

Ten katalog jest **niezależnym projektem Maven** — symuluje repozytorium zewnętrznego
dostawcy pluginu. Nie jest częścią reaktora `backend/pom.xml` i nie jest budowany przez
`mvn package -pl app`.

> **Uwaga:** `checksumSha256` zacommitowany w `plugin-manifest.json` jest placeholderem
> (64 zera). Zawsze przelicz checksum samodzielnie przed uploadem (Krok 2) — inny
> JDK/Maven/OS może wyprodukować JAR o innych bajtach, a backend odrzuci upload z komunikatem
> `Checksum mismatch: ...obliczony=<X>`. W takim wypadku wklej `<X>` do manifestu i przebuduj.

---

## Krok 0 — zbuduj i zainstaluj `plugin-sdk` lokalnie

Ten projekt zależy od `com.contactcenter:contact-center-plugin-sdk:1.0.0-SNAPSHOT`, który nie
jest publikowany do żadnego publicznego repozytorium Maven. Do celów lokalnych/demo:

```bash
cd ../../../backend
mvn install -pl plugin-sdk
```

To zainstaluje JAR `contact-center-plugin-sdk-1.0.0-SNAPSHOT.jar` do Twojego lokalnego
repozytorium Maven (`~/.m2`), skąd ten projekt go odczyta.

## Krok 1 — pierwsza budowa (z placeholderem checksumu)

```bash
cd examples/plugins/crm-url-launcher
mvn -q package
```

Manifest (`src/main/resources/META-INF/plugin-manifest.json`) zawiera placeholder
`checksumSha256` (64 zera) — JAR z tego kroku **nie przejdzie walidacji przy uploadzie**,
ale potrzebujemy go, żeby policzyć prawdziwy checksum.

## Krok 2 — policz checksum

Backend liczy `checksumSha256` jako SHA-256 z konkatenacji (nazwa wpisu + zawartość wpisu) dla
wszystkich wpisów JAR-a **z wyłączeniem** `META-INF/plugin-manifest.json`. Ten projekt zawiera
narzędzie replikujące dokładnie ten algorytm:

```bash
java -cp target/classes com.acme.contactcenter.plugin.crmurlauncher.tooling.ChecksumTool \
    target/crm-url-launcher-plugin-1.1.0.jar
```

Wypisze 64-znakowy hex string.

## Krok 3 — wklej checksum do manifestu i przebuduj

Zastąp placeholder w `src/main/resources/META-INF/plugin-manifest.json` wynikiem z kroku 2,
potem:

```bash
mvn -q package
```

Ponieważ checksum jest liczony z wyłączeniem wpisu manifestu, zmiana TYLKO tego pola nie
zmienia hashu innych wpisów. Wynikowy plik: `target/crm-url-launcher-plugin-1.1.0.jar` jest
gotowy do uploadu.

## Krok 4 — wgraj i zainstaluj w panelu supervisora

1. Zaloguj się jako `SUPERVISOR`/`ADMIN`, przejdź do **Ustawienia → Pluginy**
   (`/supervisor/settings/plugins`).
2. Wgraj `crm-url-launcher-plugin-1.1.0.jar` (drag&drop lub wybór pliku).
3. Po statusie `VALIDATED` kliknij **Zainstaluj**, zatwierdź uprawnienia
   (`customer:read`, `contact:read`).
4. Skonfiguruj klucze `crmUrlTemplate` i opcjonalnie `agentParams` — patrz sekcja poniżej.
5. Kliknij **Włącz**.

---

## Konfiguracja

Plugin wymaga co najmniej jednej wartości konfiguracyjnej tenanta. Ustaw je po instalacji,
przed `enable`:

```http
PATCH /api/supervisor/plugins/installations/{installationId}/config
Content-Type: application/json
Authorization: Bearer <JWT supervisora/admina>

{
  "config": {
    "crmUrlTemplate": "https://crm.example.com/customer?id={customerId}&phone={customerPhone}&agent_ref={agentRef}&case={agentCaseNumber}",
    "agentParams": "agentRef,agentCaseNumber"
  }
}
```

### `crmUrlTemplate` (wymagany)

Szablon URL z placeholderami w formacie `{nazwaZmiennej}`. Zmienne automatyczne są
podstawiane z danych kontaktu/klienta (patrz tabela poniżej); zmienne agenta muszą być
wpisane ręcznie w formularzu panelu.

Przykład:
```
https://crm.example.com/customer?id={customerId}&phone={customerPhone}&ref={agentRef}
```

### `agentParams` (opcjonalny)

Lista nazw zmiennych, które agent musi wypełnić ręcznie (oddzielone przecinkiem, spacje
dozwolone). Muszą odpowiadać placeholderom używanym w `crmUrlTemplate`. Gdy puste lub
nieustawione, panel nie wyświetla żadnego formularza — przycisk "Otwórz w CRM" działa
od razu po załadowaniu danych klienta.

Przykład:
```
agentRef,agentCaseNumber
```

---

## Zmienne automatyczne

Następujące placeholdery są automatycznie podstawiane z danych kontaktu/klienta
(nie wymagają wypełnienia przez agenta):

| Placeholder          | Źródło                                         | Przykład                                 |
|----------------------|------------------------------------------------|------------------------------------------|
| `{customerId}`       | UUID klienta z platformy                       | `550e8400-e29b-41d4-a716-446655440000`   |
| `{contactId}`        | UUID kontaktu (bieżącej rozmowy)               | `7c9e6679-7425-40de-944b-e07fc1f90ae7`   |
| `{customerPhone}`    | Pierwszy numer telefonu klienta (E.164)        | `%2B48501234567`                         |
| `{customerEmail}`    | Pierwszy adres e-mail klienta                  | `jan%40example.com`                      |
| `{customerFirstName}`| Imię klienta                                   | `Jan`                                    |
| `{customerLastName}` | Nazwisko klienta                               | `Kowalski`                               |
| `{customerExternalId}` | Zewnętrzny identyfikator klienta z systemu CRM | `CRM-123`                                 |

> **Uwaga:** wartości są URL-enkodowane (RFC 3986, spacja → `%20`) — telefon `+48501234567`
> trafi do URL jako `%2B48501234567`. Jeśli Twój CRM nie obsługuje URL-enkodowania, użyj
> parametru zapytania z dekodowaniem po stronie serwera CRM lub rozważ odczyt wartości przez
> dedykowaną integrację backendową.

---

## Zmienne agenta

Zmienne, które agent wpisuje ręcznie, deklarujesz w `agentParams`. Każda nazwa w tej liście
musi odpowiadać placeholder-owi użytemu w `crmUrlTemplate`. Jeśli agent nie wypełni
któregoś pola, panel wyświetli błąd i zablokuje otwarcie URL — host nigdy nie otworzy URL
z nierozwiązanymi placeholderami.

Przykładowa konfiguracja z dwoma polami agenta:

```
crmUrlTemplate = https://crm.example.com/search?cid={customerId}&phone={customerPhone}&ref={agentRef}&case={agentCaseNumber}
agentParams    = agentRef,agentCaseNumber
```

W panelu bocznym agent zobaczy:
- dane klienta (read-only): imię/nazwisko, telefon, e-mail
- dwa pola tekstowe: `agentRef` i `agentCaseNumber`
- przycisk **Otwórz w CRM** — aktywny dopiero po wypełnieniu obu pól

---

## Struktura projektu

```
crm-url-launcher/
  pom.xml
  README.md
  src/main/java/com/acme/contactcenter/plugin/crmurlauncher/
    CrmUrlLauncherPlugin.java      – PluginEntryPoint (onActivate/onPreContactConnect/onManualAction)
    UrlTemplateBuilder.java        – podstawianie zmiennych w szablonie URL (package-private)
    tooling/ChecksumTool.java      – replika algorytmu checksumu platformy (do budowy)
  src/main/resources/
    META-INF/plugin-manifest.json
    plugin-ui/index.html           – panel boczny: dane klienta + formularz + przycisk CRM
```

## Dlaczego brak zewnętrznych zależności?

Ten plugin nie wykonuje żadnych wywołań HTTP — URL budowany jest lokalnie z danych dostępnych
przez `PluginContext`. Jedyne zależności to `plugin-sdk` (scope=provided, dostarczany przez
platformę) i JDK. Szablon URL z URL-enkodowaniem jest zaimplementowany w `UrlTemplateBuilder`
z użyciem wyłącznie `java.net.URLEncoder` i `java.util.regex.Pattern` z biblioteki standardowej.
