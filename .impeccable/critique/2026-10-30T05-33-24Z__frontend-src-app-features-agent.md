---
target: frontend/src/app/features/agent
total_score: 30
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 0
target_identity: "file:/home/pawelm/contact-center/frontend/src/app/features/agent"
timestamp: 2026-10-30T05-33-24Z
slug: frontend-src-app-features-agent
---
# Krytyka: powierzchnia Agenta (frontend/src/app/features/agent), ponowna ocena zamykająca EPIC-32 (FE-125)

Method: przegląd kodu, testów i tickety EPIC-32 (A) + detektor `impeccable detect` w trybie regex (B) + pomiary koordynatora w renderowanej aplikacji (ocena końcowa po rundzie 2 poprawek). Moja własna próba w przeglądarce nie powiodła się (backend 502 podczas restartu). Pomiary koordynatora po poprawkach: 0 naruszeń kontrastu, 0 celów < 24 px, brak poziomego scrolla. Pomiary: agent1, 1366×768 (viewport 1334×549, tryb compact), oba motywy; kontrast WCAG z computed color/background, tekst < 12 px, cele < 24 px; pulpit z kontaktem e-mail, strony Klienci i Oddzwonienia, modale. Oceny łączą kod, specyfikacje i te pomiary; NIE obejmują czytnika ekranu, prawdziwego połączenia przychodzącego/ACW (Twilio) ani emulacji `prefers-reduced-motion`. Ta sama rubryka co w krytyce wyjściowej z 2026-10-21 (10 heurystyk Nielsena, 0-4).

## Design Health Score
| # | Heurystyka | Przed | Po | Kluczowy problem |
|---|---|---|---|---|
| 1 | Widoczność stanu systemu | 2 | 3 | Status agenta w pasku na wszystkich /agent/*, znaczniki stanu kart (ACW > Wstrzymana > Rozmowa > Szkic), baner ACW; brak licznika przerwy z API (FE-130, czeka na BE-153) i znacznika „nieprzeczytane” (FE-135) |
| 2 | Zgodność z rzeczywistością | 2 | 3 | Teksty w i18n (pl/en/de/uk), KPI bez fałszywych celów; KPI „Czas pracy” nadal w formacie 225:56 (FE-129), literały PL w softphone.service (FE-128) |
| 3 | Kontrola i swoboda | 1 | 3 | × poza `role="tab"`, potwierdzenia, „Zwróć do kolejki”, Esc przez `(cancel)`, szkice per kontakt; po F5 karty wielu kontaktów nie wracają (FE-136) |
| 4 | Spójność i standardy | 2 | 3 | Tokeny OKLCH w obu motywach (pomiar: 0 naruszeń kontrastu w obu motywach), jeden przycisk przerwy; ok. 200 fallbacków `var(--x, #hex)` w 10 plikach SCSS; ok. 13 dekoracyjnych gradientów w 8 plikach (stany softphone'u, awatary, nagłówki i przyciski modali oddzwonień) łamie Flat-at-Rest |
| 5 | Zapobieganie błędom | 1 | 3 | „Odrzuć” zamiast „Zamknij”, blokada zamknięcia aktywnej rozmowy, anti-ping-pong (BE-160); ACW po F5 nieodtwarzane |
| 6 | Rozpoznawanie zamiast pamiętania | 3 | 3 | Etykiety na przyciskach, znaczniki na kartach, historia w karcie klienta |
| 7 | Elastyczność i efektywność | 0 | 3 | Skróty Ctrl+Alt+Enter/Backspace/M, `?` ściąga, `aria-keyshortcuts`; skrót „przejdź do ACW” odroczony |
| 8 | Estetyka i minimalizm | 3 | 3 | Chrome 120 px przy 1366×768 (z ~160), płaski ruch, budżet stylów pulpitu 9,0 kB; brak poziomego scrolla przy 1920/1366/1056 px (pomiar); dekoracyjne gradienty w modalach i softphonie |
| 9 | Pomoc w naprawie błędów | 2 | 3 | Ponowienie karty klienta, baner WS, jeden toast po dyspozycji; błędy ładowania kolejki/historii mało szczegółowe |
| 10 | Pomoc i dokumentacja | 1 | 3 | Ściąga skrótów pod `?`, ogłoszenia czytnika (live region), brak dłuższej pomocy kontekstowej |
| **Suma** | | **17/40** | **30/40** | **Good** |

## Werdykt: specyficzność projektu
Zachowanie wynika teraz ze sceny pracy agenta: dzwonienie przejmuje Kalendarz i fokus, skróty działają bez myszy, ACW to stan karty, a nie nakładka, chrome mieści się w 1366×768. „Well-Lit Workbench” jest spójne w obu motywach przez tokeny OKLCH. Pomiary renderowane po poprawkach: 0 naruszeń kontrastu na pulpicie, Klienci i Oddzwonienia w obu motywach (data wiadomości 5,59:1 / 5,46:1); nic poniżej 11 px; 0 celów < 24 px. Detektor (regex, bez elementów renderowanych): 6 znalezisk w features/agent (4 × overused-font „Geist Mono” = decyzja z DESIGN.md, 2 × side-tab w email-thread-message = znaczniki kierunku wiadomości) + 1 w top-navbar (Geist Mono); 3 advisory bounce-easing to fałszywe alarmy (`--ease-spring` nie ma odbicia). Przy pierwszej krytyce było 16 + 1.

## Najważniejsze problemy
- [P2] Odtwarzanie po F5: wiele kontaktów, SOCIAL, `ACTIVE` i ACW nie wracają (FE-136, czeka na BE-157). Ryzyko dla agenta z kilkoma równoległymi kontaktami. → /impeccable harden
- [P2] Znacznik „nieprzeczytane / czeka klient” na kartach e-mail/social nie istnieje (FE-135, czeka na BE-154). → /impeccable shape
- [P2] Format KPI „Czas pracy” w górnym pasku (225:56, minuty > 60) i licznik przerwy tylko „w tej sesji” (FE-129, FE-130). → /impeccable clarify
- [P2] Dług weryfikacji: brak testu na prawdziwym połączeniu przychodzącym, brak ręcznego testu czytnika ekranu dla `tablist` z `aria-owns`, znaczniki PHONE/Szkic i ACW niezweryfikowane na żywo. → /impeccable harden (z udziałem człowieka)
- [P2] Flat-at-Rest: dekoracyjne gradienty w powierzchni agenta (propozycja jednego ticketu). Gradienty dekoracyjne w powierzchni agenta (grep `linear-gradient`/`radial-gradient`, 25 wystąpień w 14 plikach): ok. 13 dekoracji w 8 plikach, reszta to skeletony/shimmer (uprawnione, stan ładowania). Dekoracje: tła stanów softphone'u (`softphone.component.scss:25–45`, 5 gradientów `*-soft`→`bg-subtle`) i poświata `:95`, awatar softphone'u `:130` (udokumentowany w DESIGN.md jako sygnaturowy, wyjątek), awatary `customer-panel.component.scss:163` (z fallbackami hex) i `agent-customer-card.component.ts:200` (inline, violet→accent), nagłówki i „shine” modali (`schedule-inbound-callback-modal` :83/:95, `reschedule-callback-modal` :33/:44), przyciski główne z gradientem (`schedule-inbound-callback-modal` `.btn-save` :474, `reschedule-callback-modal` `--primary` :406; sprzeczne ze specyfikacją przycisku: pełna Lampa robocza) oraz pasek `email-contact.component.scss:287`. Skeletony (nie liczone): `social-contact:47`, `agent-callbacks-page:373`, `transfer-queue-list:26`, `transfer-agent-list:50`, `disposition-panel:411`, `reschedule-callback-modal` `--loading` :442. → /impeccable quieter
- [P2] Dług tokenów: ok. 200 hex-fallbacków `var(--x, #hex)` w 10 plikach (agent: 114 linii w 7 plikach; powłoka: 83 linie w 3). → /impeccable polish
- [P3] Nagłówek kontaktu (`app-contact-header`) pokazuje surowy „Imię <email>” w linii identyfikatora. → /impeccable clarify
- [P3] Globalny `prefers-reduced-motion` skraca animacje do 0,01 ms (`styles.scss:332`), kasując użyteczną informację zwrotną. → /impeccable harden

## Czerwone flagi według person
Alex (power user): „przejdź do ACW” bez skrótu (odroczone); reszta kluczowej ścieżki (odbierz, rozłącz, wycisz) ze skrótów.
Sam (czytnik/klawiatura): brak potwierdzonego ręcznego testu `tablist` z `aria-owns`; kontrast daty i cele ≥ 24 px naprawione i zmierzone w FE-125; region live „Nowy kontakt” czyszczony po 7000 ms (naprawione w FE-125).
Agentka na zmianie: po F5 gubi dodatkowe kontakty i ACW; brak znacznika „klient czeka” na kartach e-mail/social.

## Drobne obserwacje
Surowe „Imię <email>”, nieużywany klucz `agent.desktop.queueWaiting`, `--color-primary` (oba na `--accent`), region live „Nowy kontakt”, kontrast daty (5,59:1), cele ≥ 24 px, płaskie awatary navbaru i szuflady klienta: naprawione w FE-125 (nie liczone). Pozostałe: `transition: all` ×4 (softphone ×2, customer-panel, disposition-panel), literały `'Geist Mono'` zamiast `var(--font-mono)` w 5 SCSS agenta + 2 shared, tekst 11 px (etykiety sekcji, badge, tenant, data) zgodny z podłogą `--fs-2xs`, stałe szerokości 320 px (agent-customers-tab) i 120 px (disposition-panel), `font-size: 11px` literał (agent-customers-tab:513), `border-left: 4px` w email-thread-message (świadomy znacznik kierunku).

## Pytania do rozważenia
1. Czy skrót „przejdź do ACW” ma wejść do ściągi razem z FE-136 (odtwarzanie ACW)?
2. Czy usunąć hex-fallbacki hurtowo (skrypt) po weryfikacji, że wszystkie tokeny są zdefiniowane w obu motywach?
3. Kto wykona ręczny test czytnika ekranu i test z prawdziwym połączeniem Twilio, żeby zamknąć dług z FE-114?
