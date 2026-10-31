# UI Review — powierzchnia agenta i powłoka

**Data oceny:** 2026-10-30 (FE-125, zamknięcie EPIC-32)
**Zakres:** `frontend/src/app/features/agent` + powłoka (`shared/components/app-shell`, `sidenav`, `top-navbar`)
**Metoda:** `impeccable audit` (5 wymiarów, 0-4), `impeccable critique` (10 heurystyk Nielsena, 0-4), `impeccable detect` (tryb regex). Snapshot krytyki: `.impeccable/critique/2026-10-30T05-33-24Z__frontend-src-app-features-agent.md`.

**Dowody (ocena końcowa, po rundzie 2 poprawek):** kod, testy, tickety EPIC-32 oraz pomiary w renderowanej aplikacji (agent1, 1366×768, viewport 1334×549, tryb compact, oba motywy; kontrast WCAG z computed styles, tekst < 12 px, cele < 24 px łącznie z `::before`; pulpit z kontaktem e-mail, Klienci z wynikiem wyszukiwania, Oddzwonienia, modale). Moja własna próba w przeglądarce nie powiodła się (backend 502 podczas restartu); pomiary wykonał koordynator. Wynik pomiaru po poprawkach: 0 naruszeń kontrastu w obu motywach, 0 celów < 24 px, brak poziomego scrolla (1920/1366/1056 px). **Nie wykonano:** ręcznego testu czytnika ekranu, testu z prawdziwym połączeniem przychodzącym / ACW (Twilio), emulacji `prefers-reduced-motion`.

## Wyniki: przed i po EPIC-32

| Ocena | Przed (2026-10-21/22) | Po (2026-10-30) | Cel FE-125 |
|---|---|---|---|
| Audyt techniczny | 12/20 (Acceptable) | 15/20 (Good) | ≥ 16/20 — NIEspełniony o 1 pkt |
| Krytyka UX | 17/40 (Poor) | 30/40 (Good) | ≥ 28/40 — spełniony |
| Znaleziska P0 / P1 | audyt 0 / 4, krytyka 2 / 3 | 0 / 0 | 0 / 0 — spełniony |
| P2 / P3 (audyt) | — | 7 / 6 | bez celu |

### Audyt (5 wymiarów)

| # | Wymiar | Przed | Po | Kluczowe |
|---|---|---|---|---|
| 1 | Dostępność | — | 3 | Pomiar: 0 naruszeń kontrastu (data wiadomości 5,59:1 ciemny / 5,46:1 jasny), 0 celów < 24 px, fokus modali i edytorów OK; brak testu czytnika, emulacji `prefers-reduced-motion`, a globalna reguła skraca animacje do 0,01 ms |
| 2 | Wydajność | — | 3 | Style pulpitu 18,1 → 9,0 kB, płaski ruch; bundle initial 567 kB > 500 kB, 4 × `transition: all` |
| 3 | Motywy / tokeny | — | 3 | OKLCH jasny i ciemny, pomiar kontrastu czysty w obu motywach; ok. 200 hex-fallbacków w 10 plikach |
| 4 | Responsywność | — | 3 | Skala breakpointów, chrome 120 px, brak poziomego scrolla przy 1920/1366/1056 px, cele ≥ 24 px (pomiar); nietestowane poniżej 1056 px i gesty dotykowe, kilka stałych szerokości |
| 5 | Integralność implementacji | — | 3 | Detektor: 6 znalezisk, świadome lub nieblokujące; ale ok. 13 dekoracyjnych gradientów w 8 plikach i ok. 200 hex-fallbacków to powtarzalny dryf od Flat-at-Rest / Token-Only (na granicy 2) |
| | **Suma** | **12/20** | **15/20** | |

Rozbicie wyjściowego audytu na wymiary nie zostało zapisane, stąd „—” w kolumnie „Przed”. Runda 2 poprawek zamknęła zmierzone defekty (kontrast daty 4,50 → 5,59:1, 4 cele < 24 px, gradienty awatarów w navbarze i szufladzie klienta, region live, nazwa w panelu kolejki), więc znaleziska P1 i graniczne P2 zniknęły. Ocen NIE podnoszę: oceny wymiarów są zgrubne (0-4), a każdy z pięciu ma nadal weryfikowalną lukę — dostępność: niewykonany test czytnika i emulacja `prefers-reduced-motion`, globalny kill 0,01 ms; wydajność: bundle 567 kB, `transition: all` ×4; tokeny i integralność: ok. 200 fallbacków hex oraz ok. 13 dekoracyjnych gradientów; responsywność: poniżej 1056 px i dotyk nietestowane. Zmierzony brak defektów to różnica między „dobrze” a „doskonale”, której bez testu czytnika i reduced-motion nie przyznaję. Wynik 15/20 to niedobór 1 pkt względem celu 16/20; decyzja należy do właściciela (ryzyko R1 w FE-125).

### Krytyka (10 heurystyk)

Przed → po: widoczność stanu 2→3, zgodność z rzeczywistością 2→3, kontrola i swoboda 1→3, spójność 2→3, zapobieganie błędom 1→3, rozpoznawanie 3→3, elastyczność 0→3, estetyka 3→3, pomoc w błędach 2→3, pomoc i dokumentacja 1→3.

## Co zamknęły tickety EPIC-32
Baner i fokus przy dzwonieniu, skróty klawiszowe i ściąga (FE-114); kontrast tokenów (FE-115); palety hex → tokeny i Lamp Rule (FE-116); landmarki, skip-link, fokus (FE-117); skala typografii (FE-118); teksty i i18n (FE-119, FE-120, FE-126); szkice per kontakt, znaczniki stanu kart, bezpieczne zamykanie, status w powłoce (FE-121, FE-131…FE-134); ACW jako stan karty (FE-122); układ 1366×768 i skala breakpointów (FE-123); budżet stylów, płaski ruch (FE-124).

## Nierozwiązane (propozycje kolejnych ticketów)

**P2**
- Odtwarzanie wielu kontaktów, SOCIAL, `ACTIVE` i ACW po F5 (FE-136, czeka na BE-157).
- Znacznik „nieprzeczytane / czeka klient” na kartach e-mail/social (FE-135, czeka na BE-154).
- Format KPI „Czas pracy” (225:56) i licznik przerwy z `statusSince` (FE-129, FE-130).
- Flat-at-Rest: usunięcie dekoracyjnych gradientów z powierzchni agenta (jeden ticket). Gradienty dekoracyjne w powierzchni agenta (grep `linear-gradient`/`radial-gradient`, 25 wystąpień w 14 plikach): ok. 13 dekoracji w 8 plikach, reszta to skeletony/shimmer (uprawnione, stan ładowania). Dekoracje: tła stanów softphone'u (`softphone.component.scss:25–45`, 5 gradientów `*-soft`→`bg-subtle`) i poświata `:95`, awatar softphone'u `:130` (udokumentowany w DESIGN.md jako sygnaturowy, wyjątek), awatary `customer-panel.component.scss:163` (z fallbackami hex) i `agent-customer-card.component.ts:200` (inline, violet→accent), nagłówki i „shine” modali (`schedule-inbound-callback-modal` :83/:95, `reschedule-callback-modal` :33/:44), przyciski główne z gradientem (`schedule-inbound-callback-modal` `.btn-save` :474, `reschedule-callback-modal` `--primary` :406; sprzeczne ze specyfikacją przycisku: pełna Lampa robocza) oraz pasek `email-contact.component.scss:287`. Skeletony (nie liczone): `social-contact:47`, `agent-callbacks-page:373`, `transfer-queue-list:26`, `transfer-agent-list:50`, `disposition-panel:411`, `reschedule-callback-modal` `--loading` :442. Skeletony zostają. Awatar softphone'u wymaga decyzji: albo wyjątek w DESIGN.md (jest), albo spłaszczenie.
- Hurtowe usunięcie ok. 200 hex-fallbacków `var(--x, #hex)` w 10 plikach (agent: 114 linii w 7 plikach; powłoka: 83 linie w 3). `--color-primary` już usunięty w FE-125 (część A).
- Budżety: bundle initial 567 kB i komponenty > 12 kB (FE-138).
- Dług weryfikacji: test z prawdziwym połączeniem przychodzącym / ACW (Twilio), ręczny test czytnika ekranu dla `tablist`, emulacja `prefers-reduced-motion`, żywa weryfikacja znaczników PHONE/Szkic.

**P3**
- Globalny `prefers-reduced-motion` skraca animacje do 0,01 ms (`styles.scss:332`), co kasuje także użyteczną informację zwrotną; rubryka audytu każe zachować zmianę stanu. Zaplanować łagodniejszy wariant (np. bez ruchu, z natychmiastową zmianą koloru) i emulację.
- Nagłówek kontaktu (`app-contact-header`) pokazuje obok nazwy surowy identyfikator „Imię <email>” (brak normalizacji w linii identyfikatora; panel kolejki naprawiony w FE-125).
- `transition: all` ×4 (softphone ×2, customer-panel, disposition-panel) na konkretne właściwości.
- Literały `'Geist Mono'` zamiast `var(--font-mono)` (5 plików agenta, 2 shared, top-navbar).
- Stałe szerokości 320 px i 120 px, literał `font-size: 11px` w `agent-customers-tab` / `disposition-panel`.
- Migracja `px` w `@media` poza agentem i powłoką (FE-137); skrót „przejdź do ACW”.

## Naprawione w FE-125 (część A i runda 2)
`--color-primary` (customer-panel i `agent-customers-tab`, oba na `--accent`); nieużywany klucz `agent.desktop.queueWaiting`; surowe „Imię <email>” w panelu kolejki (z fallbackiem do identyfikatora); czyszczenie regionu live „Nowy kontakt” (7000 ms, bez osieroconych timerów); kontrast daty `.thread-message__time` (`--text-3`: 5,59:1 ciemny, 5,46:1 jasny); cele ≥ 24 px (odświeżanie kampanii, „Collapse thread” przez `::before`, uchwyt panelu klienta, okruszek „Agent”); płaskie awatary `navbar__user-avatar` i `.customer-drawer__avatar`.

## Przed EPIC-32: 6.2/10 (2026-03-26)

Poprzednia wersja tego pliku oceniała cały frontend (LoginComponent, dashboardy, tabele) na 6.2/10: poprawna semantyka i WCAG AA, ale brak spójnego systemu projektowego (zmienne SCSS per komponent, hex w każdym pliku), brak `prefers-reduced-motion`, płaskie karty KPI, aktywna pozycja sidenavu twardym kolorem. Większość tych punktów rozwiązał nowy system OKLCH z motywem ciemnym i globalnym `prefers-reduced-motion` (stan opisany w `DESIGN.md`). Ocena obejmowała inne kryteria (wygląd „premium”) i nie jest wprost porównywalna z wynikami powyżej. Powierzchnie supervisora i admina nie były objęte EPIC-32 i nie zostały tu ponownie ocenione.
