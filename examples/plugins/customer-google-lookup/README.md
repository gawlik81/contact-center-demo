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

> ⚠️ **`checksumSha256` zacommitowany w `plugin-manifest.json` jest tylko z JEDNEGO,
> konkretnego builda** (zweryfikowanego w trakcie pisania tego przykładu). Bajtowa
> reprodukowalność JAR-a jest zagwarantowana TYLKO między budowami na tym samym środowisku
> (ta sama wersja JDK/Maven, ten sam OS) — patrz Krok 2-3 poniżej. **Zawsze przelicz checksum
> samodzielnie przed uploadem** (Krok 2), nie ufaj wartości już wpisanej w pliku — inny
> JDK/Maven/OS może wyprodukować JAR o innych bajtach (mimo identycznego kodu źródłowego),
> a backend i tak odrzuci upload z czytelnym błędem `Checksum mismatch: ...obliczony=<X>` —
> w takim wypadku wklej `<X>` z komunikatu błędu do manifestu i przebuduj.

> ⚠️ **Google Custom Search JSON API ma znaną, niezależną od konfiguracji awarię dostępu.**
> Wywołania mogą zwracać `403 PERMISSION_DENIED: "This project does not have the access to
> Custom Search JSON API"` mimo że w Google Cloud Console API jest enabled, billing aktywny i
> klucz API bez żadnych restrykcji — dotyczy to wielu projektów GCP od lutego 2026, zgłaszane
> masowo w [wątku Google Support](https://support.google.com/programmable-search/thread/411852630)
> (rozwiązywane tam wyłącznie indywidualnie, przez "Send feedback" w panelu Programmable Search
> Engine z podaniem Project ID/Number i `cx`). Dodatkowo Google **zamknął to API dla nowych
> rejestracji** (ogłoszenie ze stycznia 2026:
> [Updates to our Web Search Products](https://programmablesearchengine.googleblog.com/2026/01/updates-to-our-web-search-products.html)),
> więc traktuj ten plugin jako demonstrację integracji z SDK (`HttpEgressClient`, konfiguracja
> per-tenant, manifest uprawnień), nie jako gotowe do produkcji źródło danych — dla realnego
> wdrożenia rozważ inny dostawca wyszukiwania.

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

## Konfiguracja: klucze Google Custom Search

Ten plugin wymaga dwóch wartości konfiguracyjnych tenanta, odczytywanych przez
`PluginContext.config()`: `googleApiKey` (klucz API) i `googleSearchEngineId` (parametr `cx`).
Ustaw je **po** instalacji, **przed** `enable`:

```http
PATCH /api/supervisor/plugins/installations/{installationId}/config
Content-Type: application/json
Authorization: Bearer <JWT supervisora/admina>

{
  "config": {
    "googleApiKey": "TWÓJ_KLUCZ_API",
    "googleSearchEngineId": "TWOJE_CX"
  }
}
```

Wartość jest szyfrowana po stronie backendu (AES-256-GCM, ten sam wzorzec co
`tenant_ai_config`/`tenant_twilio_config`) — nigdy nie wraca w żadnej odpowiedzi API, więc nie
ma sposobu odczytać już zapisany klucz przez REST (tylko nadpisać nowym wywołaniem `PATCH`,
semantyka REPLACE — pełen zestaw kluczy zastępowany przy każdym wywołaniu).

Jak uzyskać wartości: [Google Custom Search JSON API](https://developers.google.com/custom-search/v1/overview)
(klucz API z Google Cloud Console) i [Programmable Search Engine](https://programmablesearchengine.google.com/)
(identyfikator `cx` Twojej wyszukiwarki — skonfiguruj ją do przeszukiwania całego internetu,
nie konkretnej domeny).

> Brak jeszcze pola do tego w dialogu instalacji panelu supervisora (`/supervisor/settings/plugins`,
> FE-098) — na razie wywołaj `PATCH` bezpośrednio (np. przez Swagger UI lub `curl`/Postman).
> Dodanie pola konfiguracji do UI to naturalny follow-up frontendowy.

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
