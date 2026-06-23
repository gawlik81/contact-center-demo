# Customer Google Lookup — przykładowy plugin

Przykładowy, w pełni działający plugin dla systemu rozszerzeń Contact Center (EPIC-28),
demonstrujący kompletny przepływ SDK opisany w
[`documentation/10-plugin-development.md`](../../../documentation/10-plugin-development.md).

**Co robi:** panel boczny w pulpicie agenta, aktywowany automatycznie w momencie rozmowy z
klientem — wyszukuje dane klienta (imię + nazwisko, awaryjnie numer telefonu) w
[Google Custom Search API](https://developers.google.com/custom-search/v1/overview) i
prezentuje wyniki agentowi. Dodatkowo: przycisk w toolbarze do odświeżenia wyszukiwania na
żądanie.

Ten katalog jest **niezależnym projektem Maven** — symuluje repozytorium zewnętrznego
dostawcy pluginu. Nie jest częścią reaktora `backend/pom.xml` i nie jest budowany przez
`mvn package -pl app`.

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
cd examples/plugins/customer-google-lookup
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
java -cp target/classes com.acme.contactcenter.plugin.googlelookup.tooling.ChecksumTool \
    target/customer-google-lookup-plugin-1.0.0.jar
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
`target/customer-google-lookup-plugin-1.0.0.jar` jest gotowy do uploadu.

## Krok 4 — wgraj i zainstaluj w panelu supervisora

1. Zaloguj się jako `SUPERVISOR`/`ADMIN`, przejdź do **Ustawienia → Pluginy**
   (`/supervisor/settings/plugins`).
2. Wgraj `customer-google-lookup-plugin-1.0.0.jar` (drag&drop lub wybór pliku).
3. Po statusie `VALIDATED` kliknij **Zainstaluj**, zatwierdź uprawnienia
   (`customer:read`, `http:egress:www.googleapis.com`).
4. Skonfiguruj klucze API — **patrz ograniczenie poniżej**, ta funkcjonalność nie jest
   jeszcze dostępna w UI.
5. Kliknij **Włącz**.

---

## ⚠️ Znane ograniczenie: brak UI/API do ustawienia `installation_config`

Ten plugin wymaga dwóch wartości konfiguracyjnych tenanta (klucz API Google Custom Search i
identyfikator Custom Search Engine, `cx`) odczytywanych przez `PluginContext.config()`. **W
chwili pisania tego przykładu backend nie eksponuje żadnego endpointu REST do ustawienia
`tenant_plugin_installation.installation_config`** —
`PluginRegistrationServiceImpl.install()` na trwałe ustawia tę kolumnę na `null`
(`installation.setInstallationConfig(null)`), a żaden inny serwis jej nie modyfikuje. To
oznacza, że `onActivate()` tego pluginu **zawsze rzuci** `IllegalStateException` przy próbie
`enable`, dopóki ta funkcjonalność nie zostanie dodana do backendu (np. nowy endpoint
`PATCH /api/supervisor/plugins/installations/{id}/config` + pole w dialogu instalacji we
froncie).

**Tymczasowy sposób przetestowania lokalnie** (tylko środowisko dev, nie produkcja): ustaw
kolumnę ręcznie w bazie po instalacji, przed `enable`:

```sql
UPDATE tenant_plugin_installation
SET installation_config = '{"googleApiKey": "TWÓJ_KLUCZ_API", "googleSearchEngineId": "TWOJE_CX"}'
WHERE id = '<installationId>';
```

Jak uzyskać te wartości: [Google Custom Search JSON API](https://developers.google.com/custom-search/v1/overview)
(klucz API z Google Cloud Console) i [Programmable Search Engine](https://programmablesearchengine.google.com/)
(identyfikator `cx` Twojej wyszukiwarki — skonfiguruj ją do przeszukiwania całego internetu,
nie konkretnej domeny).

---

## Struktura projektu

```
customer-google-lookup/
  pom.xml
  README.md
  src/main/java/com/acme/contactcenter/plugin/googlelookup/
    CustomerGoogleLookupPlugin.java   – PluginEntryPoint (onActivate/onPreContactConnect/onManualAction)
    GoogleCustomSearchClient.java     – wywołanie Google Custom Search API przez HttpEgressClient
    MinimalJson.java                  – parser JSON bez zależności (plugin-sdk nie dostarcza żadnego)
    SearchResultItem.java             – wynik wyszukiwania (title/link/snippet)
    tooling/ChecksumTool.java         – replika algorytmu checksumu platformy (do budowy)
  src/main/resources/
    META-INF/plugin-manifest.json
    plugin-ui/index.html              – panel boczny: auto-wyszukiwanie + przycisk odświeżenia
```

## Dlaczego brak zewnętrznych zależności (np. biblioteki JSON)?

`plugin-sdk` dostarcza zero bibliotek poza JDK — deweloper jest wolny w doborze zależności we
własnym JAR-ze, ale **żadna klasa w JAR-ze nie może odwoływać się do API zablokowanych przez
statyczny skan bytecode platformy** (`Thread#getContextClassLoader`/`setContextClassLoader`,
`ServiceLoader`, `setAccessible`, `ProcessBuilder`, `java.nio.file.*`, `sun.misc.*`, podklasy
`ClassLoader` — pełna lista w `documentation/10-plugin-development.md`, §7). Ten przykład
celowo nie dodaje żadnej zależności (stąd `MinimalJson`), żeby zademonstrować plugin w
najprostszej, samodzielnej formie — produkcyjny plugin z bardziej złożonymi potrzebami JSON
może bezpiecznie dołączyć sprawdzoną bibliotekę (Gson, Jackson — zshade'owaną do własnego
JAR-a), bo żadna z popularnych bibliotek JSON nie używa zablokowanych API.
