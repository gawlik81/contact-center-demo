# PROGRESS.md
# Contact Center SaaS – Postęp prac

**Ostatnia aktualizacja:** 2026-10-23 (tura 52 – zatwierdzony ticket BE-157, lista aktywnych kontaktów agenta, blokuje FE-136; wcześniej tura 51 – `/impeccable shape` dla FE-121 i FE-122 zatwierdzony przez właściciela; FE-121 → parasol z pakietami FE-131…FE-134 (WP-A…WP-D), nowe tickety FE-135, FE-136, BE-154, BE-155, BE-156; bez zmian w kodzie; wcześniej tura 50 – EPIC-32 faza 2: FE-119 ✅ teksty/KPI/marka/i18n powierzchni agenta, FE-120 ✅ domknięcia po hardeningu, FE-126 ✅ klawiatura list umiejętności; nowe tickety FE-127…FE-130 i BE-153; zmiany niezacommitowane na `feature/epic-32-agent-ux-a11y`, commit na życzenie użytkownika; wcześniej tura 49 – FE-116 ✅ i FE-118 ✅, nowy FE-126; tura 48 – FE-115 ✅ i FE-117 ✅; tura 47 – założenie EPIC-32).

Źródłem prawdy o statusach są pola `**Status:**` w plikach zadań: [TASKS-DATABASE.md](TASKS-DATABASE.md), [TASKS-BACKEND.md](TASKS-BACKEND.md), [TASKS-FRONTEND.md](TASKS-FRONTEND.md) (każdy ma na górze „Indeks wg EPIC”). Pełny dziennik tur, stare tabele ticketów z notatkami wykonania, mapa procesów, sesja 2026-04-09 i refaktor pakietu `domain` są w [PROGRESS-ARCHIVE.md](PROGRESS-ARCHIVE.md).

## Legenda statusów

| Symbol | Status |
|--------|--------|
| ✅ | Ukończone |
| 🔧 | W trakcie / wymaga poprawek |
| ⬜ | Nie rozpoczęte |
| 🚫 | N/A — zamknięte decyzją produktową (nigdy nie wejdzie do realizacji przy obecnej decyzji) |

Status epiku: ✅ Zakończony / 🔧 W toku / ⬜ Nie rozpoczęty / ⚠️ Zamknięty warunkowo / ⏸ Wstrzymany.

---

## Podsumowanie ogólne

| Obszar | ✅ | 🔧 | ⬜ | 🚫 | Razem |
|--------|---:|---:|---:|---:|------:|
| Database (DB) | 82 | 0 | 2 | 3 | 87 |
| Backend (BE) | 143 | 0 | 15 | 3 | 161 |
| Frontend (FE) | 120 | 0 | 19 | 1 | 140 |
| **RAZEM** | **345** | **0** | **36** | **7** | **388** |

Liczniki przeliczone ze statusów w `TASKS-*.md` (2026-10-23, tura 52: nowy ⬜ BE-157 (BE 143 ✅ + 15 ⬜ + 3 🚫 = 161, RAZEM 388 = 345 ✅ + 36 ⬜ + 7 🚫), DB/FE bez zmian; poprzednio tura 51: bez zmian statusów istniejących ticketów; nowe ⬜: FE-131, FE-132, FE-133, FE-134 (pakiety parasola FE-121, który zostaje ⬜), FE-135, FE-136 (FE +6) oraz BE-154, BE-155, BE-156 (BE +3), czyli FE 120 ✅ + 15 ⬜ EPIC-32 + 4 ⬜ FE-T00x + 1 🚫 = 140, BE 143 ✅ + 14 ⬜ + 3 🚫 = 160, RAZEM 387 = 345 ✅ + 35 ⬜ + 7 🚫; DB bez zmian; skrypt na `###`: FE 120/15/1, BE 142/10/3 (+BE-108/109 bez nagłówka, +4 BE-T00x, BE-067 `[x]`); poprzednio tura 50: FE-119, FE-120 i FE-126 → ✅ (+3 ✅ / −3 ⬜) oraz nowe ⬜: FE-127, FE-128, FE-129, FE-130 (FE +4) i BE-153 (BE +1), czyli FE 120 ✅ + 9 ⬜ EPIC-32 + 4 ⬜ FE-T00x + 1 🚫 = 134, BE 143 ✅ + 11 ⬜ + 3 🚫 = 157 (skrypt na `###` widzi 141 ✅ — BE-108/BE-109 bez nagłówka `###` dodają +2), RAZEM 378 = 345 ✅ + 26 ⬜ + 7 🚫; DB bez zmian; poprzednio tura 49: FE-116 i FE-118 → ✅ (+2 ✅ / −2 ⬜) oraz nowy FE-126 ⬜ (+1 w mianowniku), czyli FE 117 ✅ + 8 ⬜ EPIC-32 + 4 ⬜ FE-T00x + 1 🚫 = 130, RAZEM 373; DB/BE bez zmian; poprzednio tura 48: FE-115 i FE-117 → ✅, czyli FE i RAZEM +2 ✅ / −2 ⬜, DB/BE bez zmian; FE: 115 ✅ + 9 ⬜ EPIC-32 + 4 ⬜ FE-T00x + 1 🚫 = 129; w turze 47 dodano 12 ticketów FE EPIC-32); 🚫 (7): DB-063, DB-068, DB-075, BE-130, BE-136, BE-140, FE-111 — tickety warunkowe zamknięte decyzjami D1 = A, D4 = A, D6 = `archived_at` (nie są „ukończone” ani otwartym backlogiem).

**Rozbieżność względem poprzedniej wersji tego pliku (BE 152 / FE 113, razem 352):** obecnie BE 156 i FE 117 (razem 360). Różnica to po 4 tickety testowe EPIC-18 — BE-T001…BE-T004 i FE-T001…FE-T004 (status w `TASKS-*.md`: „🔲 Do zrobienia”, liczone jako ⬜), których stary licznik nie obejmował. Dodatkowo BE-108/BE-109 (bez nagłówka `###`) są liczone w BE (stara notka dodawała „+2” osobno). Statusy żadnego ticketu nie zostały zmienione; DB bez zmian (87). Wcześniej podane „⬜ 8” obejmowało DB 2 + BE 6 + FE 0; teraz ⬜ = 16 (DB 2, BE 10, FE 4) właśnie przez te 8 ticketów EPIC-18.

---

## Przegląd wg EPIC

Format komórek DB/BE/FE: ukończone/razem (— = brak ticketów w obszarze).

| EPIC | Nazwa | DB | BE | FE | Status epiku | Uwagi |
|------|-------|----|----|----|--------------|-------|
| EPIC-01 | Onboarding i konfiguracja tenanta | — | 2/2 | 2/2 | ✅ Zakończony |  |
| EPIC-02 | Zarządzanie użytkownikami i rolami | — | 1/1 | 1/1 | ✅ Zakończony |  |
| EPIC-03 | Kanał telefoniczny (inbound) | — | 5/5 | 3/3 | ✅ Zakończony |  |
| EPIC-04 | IVR i Automatyzacja głosowa | — | 2/2 | 1/1 | ✅ Zakończony |  |
| EPIC-05 | Kanał email | — | 2/2 | 1/1 | ✅ Zakończony |  |
| EPIC-06 | Kanał Social Media | — | 2/2 | 2/2 | ✅ Zakończony |  |
| EPIC-07 | Silnik routingu | — | 3/3 | 1/1 | ✅ Zakończony |  |
| EPIC-08 | Zarządzanie kampaniami | — | 4/4 | 3/3 | ✅ Zakończony |  |
| EPIC-09 | Profil i historia klienta | — | 2/2 | 5/5 | ✅ Zakończony |  |
| EPIC-10 | Dashboardy i raporty | — | 5/5 | 2/2 | ✅ Zakończony |  |
| EPIC-11 | Routing numerów telefonicznych | 1/1 | 3/3 | 2/2 | ✅ Zakończony |  |
| EPIC-12 | Prezentacja Kontaktów | 1/1 | 2/2 | 3/3 | ✅ Zakończony |  |
| EPIC-13 | Zaplanowane oddzwonienia | 1/1 | 5/5 | 4/4 | ✅ Zakończony |  |
| EPIC-14 | Zarządzanie przypisaniem agentów do kolejek | 3/3 | 5/5 | 4/4 | ✅ Zakończony |  |
| EPIC-15 | Zakładka Klienci w Agent Desktop | 1/1 | 1/1 | 2/2 | ✅ Zakończony |  |
| EPIC-16 | Kalendarz Agenta | 1/1 | 4/4 | 4/4 | ✅ Zakończony |  |
| EPIC-17 | Powiadomienia o połączeniu (Incoming Call Alert) | — | — | 3/3 | ✅ Zakończony |  |
| EPIC-18 | Testy jednostkowe | — | 0/4 | 0/4 | ⬜ Nie rozpoczęty | Testy jednostkowe BE-T001…T004, FE-T001…T004 — otwarte, patrz „Otwarte zadania”. |
| EPIC-19 | Wielojęzyczność | 1/1 | 1/1 | 17/17 | ✅ Zakończony |  |
| EPIC-20 | Per-tenant konfiguracja Twilio | 2/2 | 7/7 | 3/3 | ✅ Zakończony |  |
| EPIC-21 | Retry i callback w kampaniach wychodzących | 2/2 | 5/5 | 2/2 | ✅ Zakończony |  |
| EPIC-22 | Notatki do kontaktów | 1/1 | 2/2 | 2/2 | ✅ Zakończony |  |
| EPIC-23 | Historia etapów kontaktu | 1/1 | 3/3 | 1/1 | ✅ Zakończony |  |
| EPIC-24 | Transfer połączenia: agent i kolejka | — | 5/5 | 5/5 | ✅ Zakończony |  |
| EPIC-25 | Przypisywanie agentów do kampanii | 2/2 | 7/7 | 5/5 | ✅ Zakończony |  |
| EPIC-26 | AI-Powered Conversation Summary | 2/2 | 6/6 | 4/4 | ✅ Zakończony |  |
| EPIC-27 | Własne dyspozycje per kampania i kolejka | 2/2 | 5/5 | 7/7 | ✅ Zakończony |  |
| EPIC-28 | Per-Tenant Plugin (Extension) System | 4/4 | 12/12 | 6/6 | ✅ Zakończony |  |
| EPIC-29 | Partycjonowanie i retencja danych z obsługi kontaktów | 9/9 | 9/9 | 7/7 | ✅ Zakończony |  |
| EPIC-30 | Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości | 27/31 | 22/26 | 3/4 | ⚠️ Zamknięty warunkowo | Kod i testy ukończone; DB-069/BE-137 odłożone z wyzwalaczem wolumenowym; 7 × 🚫 (DB-063/068/075, BE-130/136/140, FE-111); WP-4 opcjonalne. |
| EPIC-31 | Hardening RLS – rola aplikacji bez BYPASSRLS (cc_app) | 0/1 | 0/4 | — | ⏸ Wstrzymany | Wydzielony z EPIC-30 (tura 40); wstrzymany decyzją właściciela, 5 ⬜ (DB-083, BE-139, BE-148, BE-149, BE-150). |
| EPIC-32 | UX i dostępność stanowiska agenta | — | 0/5 | 8/23 | 🔧 W toku | FE 15 ⬜ (FE-121 parasol, FE-122…FE-125, FE-127…FE-136) + BE 5 ⬜ (BE-153 `statusSince`, BE-154 zdarzenie WS nowej wiadomości, BE-155 bug social, BE-156 limity routingu, BE-157 lista aktywnych kontaktów agenta); brief FE-121/FE-122 zatwierdzony 2026-10-23 (tura 51); ✅ FE-119 (teksty/KPI/marka/i18n powierzchni agenta), FE-120 (domknięcia po hardeningu) i FE-126 (klawiatura list umiejętności) z 2026-10-23 (tura 50, niezacommitowane); ✅ FE-114 (hardening momentu dzwonienia, `322db0c1`), FE-115 (kontrast tokenów), FE-116 (palety hex → tokeny, ramki kontrolek, `--on-*`), FE-117 (landmarki i fokus) i FE-118 (skala typografii) — FE-115…118 z 2026-10-23, niezacommitowane do chwili zapisu. FE-126 (klawiatura list umiejętności) z niespełnionego kryterium FE-116 (d); FE-127…FE-130 i BE-153 z odkryć tury 50. Źródło: krytyka 17/40 i audyt 12/20 (Impeccable). |
| Bez epiku | Fundament, RODO, ad hoc połączenia/email, porządki infrastruktury i silnika partycji | 21/21 | 11/12 | 7/7 | 🔧 W toku | Otwarty tylko BE-144 (blokada licencji AIStor). |

Uwaga: „ukończone/razem” liczy razem tickety 🚫, np. EPIC-30 DB 27/31 = 27 ✅ + 1 ⬜ + 3 🚫.

---

## Otwarte zadania (nie ✅)

### EPIC-18 – Testy jednostkowe

Tickety testowe jednostkowe, status w `TASKS-*.md` „🔲 Do zrobienia” (liczone jako ⬜); nierozpoczęte, bez blokady.

| ID | Nazwa | Status |
|----|-------|--------|
| BE-T001 | Testy jednostkowe CampaignService | ⬜ |
| BE-T002 | Testy jednostkowe AdminUserService | ⬜ |
| BE-T003 | Testy jednostkowe EmailSendService | ⬜ |
| BE-T004 | Testy jednostkowe IvrService | ⬜ |
| FE-T001 | (zob. TASKS) | ⬜ |
| FE-T002 | (zob. TASKS) | ⬜ |
| FE-T003 | (zob. TASKS) | ⬜ |
| FE-T004 | (zob. TASKS) | ⬜ |

### EPIC-30 – Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości

DB-069 i BE-137 — ⬜ **odłożone z wyzwalaczem wolumenowym** (decyzja właściciela 2026-10-22), NIE zrealizowane. Wyzwalacz: ≥ 50–100 mln wierszy w `campaign_contact_archive` LUB problem z WAL/lagiem repliki od purge LUB globalny DELETE „ogona” > 30 s; decyduje właściciel; pomiar: zapytanie z notatki DB-069 / wzorzec `scripts/epic-30/db-066-email-message-volume.sql`, przy przeglądach i przed wdrożeniem produkcyjnym archiwum. Stan dziś: 0 wierszy w archiwum, flaga `retention.campaign-archive.enabled` wyłączona (zostaje). DB-087/V143 (purge po PK) zmniejsza presję na partycjonowanie, ale jej nie usuwa. 🚫 N/A (7): DB-063, BE-130, FE-111 (D1 = A, 2026-09-30), DB-068, BE-136 (D4 = A, 2026-10-07), DB-075, BE-140 (D6 = `archived_at`, 2026-10-11) — tickety warunkowe, nigdy nie wejdą do realizacji przy obecnych decyzjach.

| ID | Nazwa | Status |
|----|-------|--------|
| BE-130 | [WARUNKOWY: D1 = C] Kategoria `MESSAGE_CONTENT` w silniku retencji | 🚫 |
| BE-136 | [WARUNKOWY: D4 = B] Deduplikacja e-mail przez tabelę `email_message_dedup` | 🚫 |
| BE-137 | [BRAMKOWANY] `campaign_contact_archive` w maszynerii partycji | ⬜ |
| BE-140 | [WARUNKOWY: D6 = koniec kampanii] `CAMPAIGN_DATA` liczone od końca kampanii (warstwa Java) (🚫 N/A) | 🚫 |
| DB-063 | [WARUNKOWY: D1 = C] Kategoria `MESSAGE_CONTENT` w schemacie polityk retencji | 🚫 |
| DB-068 | [WARUNKOWY: D4 = B] Tabela deduplikacji `email_message_dedup` | 🚫 |
| DB-069 | [BRAMKOWANY] Partycjonowanie `campaign_contact_archive` RANGE(`archived_at`) | ⬜ |
| DB-075 | [WARUNKOWY: D6 = koniec kampanii] Baza czasowa `CAMPAIGN_DATA` w archiwum (🚫 N/A) | 🚫 |
| FE-111 | (zob. TASKS) | 🚫 |

### EPIC-31 – Hardening RLS – rola aplikacji bez BYPASSRLS (cc_app)

Wstrzymany decyzją właściciela, nie ruszany; niezależny od zamknięcia EPIC-30. Kolejność: DB-085/DB-086 [EPIC-30, ✅] PRZED DB-083; BE-139 → BE-148 (parasol; WP-1 ✅ tura 34) → DB-083/BE-149/BE-150. Podstawa: `DESIGN-be148-wp1-analiza-db.md`, `DESIGN-be148-wp1-inwentarz-java.md`.

| ID | Nazwa | Status |
|----|-------|--------|
| BE-139 | Testy integracyjne pod rolą bez BYPASSRLS + weryfikacja roli połączenia produkcyjnego | ⬜ |
| BE-148 | Przejście produkcji na rolę połączenia aplikacji BEZ BYPASSRLS i bez superusera (+ rola migracyjna + mechanizm dla jobów krzyżujących tenanty) | ⬜ |
| BE-149 | Ścieżki HTTP, audyt, logowanie i lookupy pod rolą aplikacji bez BYPASSRLS (`cc_app`) | ⬜ |
| BE-150 | Joby retencji/partycji, ETL i routing/dialer pod rolą aplikacji bez BYPASSRLS (`cc_app`) | ⬜ |
| DB-083 | Role i uprawnienia pod rolę aplikacji `cc_app` (bez BYPASSRLS): GRANT-y, funkcje `SECURITY DEFINER`, REVOKE na `flyway_schema_history` | ⬜ |

### EPIC-32 – UX i dostępność stanowiska agenta

Założony 2026-10-23 (tura 47) na podstawie krytyki `/impeccable critique` (2026-10-21, 17/40), audytu `/impeccable audit` (2026-10-22, 12/20), review `CR-FRONTEND.md` (2026-10-21) i `PRODUCT.md`/`DESIGN.md`. Głównie frontend; tickety BE: BE-153 (`statusSince`, tura 50), BE-154, BE-155, BE-156 (tura 51) oraz BE-157 (tura 52), bez ticketów DB (ewentualna migracja do ustalenia w BE-153/BE-155). Cel zamknięcia: audyt ≥ 16/20, krytyka ≥ 28/40, 0 × P0/P1 (FE-125). Decyzje właściciela: priorytet „Moment dzwonienia” (FE-114 ✅), ACW jako niemodalny stan karty kontaktu (FE-122), zakres „Wszystko”. Decyzja D1 (KPI „cel 90%”/„z 8:00”): wariant (A) — usunięte (FE-119 ✅, tura 50). Pytanie dla FE-122 o semantykę `AFTER_CONTACT` przy wielu kontaktach rozstrzygnięte w turze 51 (ACW dokańcza otwarte kontakty, bez zmian BE). Otwarte pytanie o backend: FE-136 (lista aktywnych kontaktów agenta do odtwarzania po przeładowaniu). FE-114 ✅ jest na gałęzi `fix/agent-incoming-call-hardening` (przed merge do develop); otwarte kryterium: test z prawdziwym połączeniem przychodzącym.

Postęp tury 48 (2026-10-23): FE-115 ✅ i FE-117 ✅ (code review `CR-FRONTEND.md` 4/5; `/verify`: lint 0 błędów, FE 420 testów, BE `mvn verify` OK; WP-5 dla FE-117 w przeglądarce). Odblokowane FE-116 i FE-118. Do FE-116 przeniesiono: ramki kontrolek komponentów → `--border-control`, migrację `--on-danger`/`--on-success` (w tym ok. 20 miejsc supervisor/admin — zakres FE-116 rozszerzony, złożoność M/L), legacy `--color-text-primary`, listboxy bez `:focus-visible`, pigułkę „Available” w ciemnym motywie; do FE-119/FE-120/FE-124 dopisano drobne punkty z `add-break-modal` i `adhoc-email-modal`.

Postęp tury 49 (2026-10-23): FE-116 ✅ i FE-118 ✅ — faza 1 (fundament tokenów) zamknięta. FE-116: statusy agenta jako `tone` + `data-status-tone` (zamiast hex), tokeny zamiast hex w social-contact/toast/kalendarzu/skeletonie/modalu przerwy/banerze, nowe tokeny `--on-warning` i `--border-control-hover`, ramki kontrolek → `--border-control` (~70 plików), `--on-danger`/`--on-success` w supervisor/admin, `--color-text-primary` → oklch; **niespełnione (d)** — opcje `.skill-dropdown__item` bez obsługi klawiatury → nowy **FE-126**. FE-118: tokeny `--fs-2xs`/`--fs-xs`, 24 wartości < 11 px → token, Geist Mono zamiast `monospace`, zero tekstu < 11 px; pas KPI zmierzony w pl przy 1366 px (`de` niezweryfikowane — przeniesione do FE-119). Code review `CR-FRONTEND.md` 3,5/5; `/verify`: lint 0 błędów, FE 34 pliki / 424 testy, BE `mvn verify` OK. WP-5 w przeglądarce (1366×768, oba motywy): menu i pigułka statusu, `ws-banner`, modal „Dodaj przerwę”, kalendarz; **niezweryfikowane:** supervisor/admin (MFA), kategorie wydarzeń kalendarza, toasty, social-contact. Dopisano: FE-119 (`aria-label` „Wybierz język”, pas KPI w `de`) i FE-124 (pozostałe `rgba`, rozmiary 0.7rem/0.72rem/0.71875rem, fallbacki hex).

Postęp tury 50 (2026-10-23): FE-119 ✅, FE-120 ✅ i FE-126 ✅ — faza 2 zamknięta. FE-119: usunięte KPI „cel 90%”/„z 8:00” i pasek czasu pracy (D1 = A), marka z `core/brand.ts`, placeholder CHAT, usunięty martwy `agent-dashboard.component.ts`, jeden przycisk „Dodaj przerwę”, pełne i18n powierzchni agenta (parytet 4 języków 0 różnic), pas KPI zweryfikowany w przeglądarce w `de` przy 1334 px (domyka dług z FE-118). FE-120: ochrona szkicu `manual-callback-modal`, naprawiony istniejący błąd `confirm-dialog` (`onBackdropClick` niepodpięty, 9 użytkowników), ściąga bez zawijania, licznik przerwy `cc-status-elapsed` (czas startu po stronie klienta — API nie zwraca czasu zmiany statusu), jeden toast po dyspozycji, ponowienie karty klienta, domyślne wartości przerwy, listbox WAI-ARIA w typie przerwy. FE-126: wspólna dyrektywa `listbox-nav.directive.ts` w `admin-user-form`, `queue-form`, `user-form` i `add-break-modal` (WP-5 ograniczone — MFA, tylko testy). Code review `CR-FRONTEND.md` sekcja „EPIC-32 faza 2 — FE-119, FE-120, FE-126 — 2026-10-23” 4/5, wszystkie punkty Wymagane i Zalecane poprawione; `/verify`: lint 0 błędów (10 starych ostrzeżeń `no-console`), format OK, FE 47 plików / 497 testów; BE `mvn verify` OK (2670 testów, 0 błędów; uruchomione z JDK 21 — systemowa domyślna Java to teraz 25, na której Mockito/Byte Buddy nie działają). **Nowe tickety:** FE-127 (bug: `AdminUserFormComponent` nie ładuje `availableSkills`), FE-128 (i18n poza powierzchnią agenta: `admin-user-form`, `queue-form`, `softphone.service`), FE-129 (format KPI „Czas pracy” 225:56), FE-130 (licznik przerwy z `statusSince`, czeka na BE-153), BE-153 (`statusSince` w `GET /users/me` i zdarzeniu WS). Do FE-124 dopisano: `$ease-spring` (bounce) w `add-break-modal` i `manual-callback-modal`, gradient accent→violet w nagłówku `manual-callback-modal`, „side-tab” 3 px w `login.component.scss`. FE-125 zależy teraz także od FE-127…FE-129 (FE-130/BE-153 nie blokują zamknięcia audytu).

Postęp tury 51 (2026-10-23): wykonano `/impeccable shape` dla FE-121 i FE-122; brief ZATWIERDZONY przez właściciela, zapisany w obu ticketach („Brief (impeccable shape, zatwierdzony 2026-10-23)”). Decyzje: ACW dokańcza otwarte kontakty (bez zmian BE; routing bierze tylko `AVAILABLE`), znaczniki kart tylko ze stanów znanych frontowi, × na e-mail/social = „Zwróć do kolejki” przez `abandonContact`, CTA `/supervisor` ukryte dla agenta, nowy kontakt nie przejmuje aktywnej karty. **Podział:** FE-121 zostaje ticketem-parasolem (jak BE-148; domknięcie procesowe), pakiety: FE-131 (WP-A magazyn szkiców, bez ponownego `acceptContact`), FE-132 (WP-B bezpieczne zamykanie, po FE-131), FE-133 (WP-C znaczniki kart ACW > Wstrzymana > Rozmowa > Szkic, po FE-131), FE-134 (WP-D status agenta w pasku + CTA, bez zależności). FE-122 zależy teraz od FE-131 i FE-133 (zamiast całego FE-121), ryzyko R1 rozstrzygnięte; FE-123 zależy od FE-121, FE-131…FE-134, FE-122; FE-125 od pakietów. **Nowe tickety poza briefem:** BE-154 (zdarzenie WS nowej wiadomości w otwartym kontakcie; blokuje FE-135), BE-155 (BUG, rekomendowany Must Have: kanał social prawdopodobnie nigdy nie trafia do agenta — kolejka `null` → `ERROR`, kanał `SOCIAL_*` vs `'SOCIAL'`, martwe `COMPLETED`), BE-156 (routing nie egzekwuje `max_concurrent_contacts_per_agent`), FE-135 (znacznik „nieprzeczytane/czeka klient”, po BE-154 i FE-133), FE-136 (odtwarzanie kart po przeładowaniu — pytanie o endpoint listy aktywnych kontaktów otwarte). Bez zmian w kodzie i bez commita. Liczniki: DB 82/87, BE 143/160, FE 120/140 — RAZEM 345/387.

Postęp tury 52 (2026-10-23): właściciel zatwierdził ticket BE-157 (pytanie otwarte z FE-136). BE-157: `GET /api/agent/me/active-contacts` w `AgentSelfController` zwraca tablicę wszystkich aktywnych kontaktów agenta (`ASSIGNED`/`ACTIVE`/`ON_HOLD` + ACW) z polami do odbudowania karty (`phase`, `assignedAt`, `wrapUpStartedAt`, `queueName`, `customerName`, `subject`), kolejność `assigned_at ASC`, maks. 20; stary `assigned-contact` zostaje i jest deprecated po FE-136. Odkrycia z kodu: ACW nie istnieje w backendzie (stan pochodny: `COMPLETED` + brak `disposition_code` + `ended_at`), `AgentSelfController` nie ma żadnych testów, `customerName` w `assigned-contact` to zawsze `remoteAddress`. FE-136 `Zależy od` BE-157. Bez zmian w kodzie. Liczniki: DB 82/87, BE 143/161, FE 120/140 — RAZEM 345/388.

Kolejność faz: (1) fundament tokenów FE-115 ✅, FE-116 ✅, FE-117 ✅, FE-118 ✅ — zamknięta; (2) treść i domknięcia FE-119 ✅, FE-120 ✅, FE-126 ✅ — zamknięta (tura 50), plus wynikowe FE-127, FE-128, FE-129; (3) wiele kontaktów — brief zatwierdzony (tura 51): FE-131 (WP-A) → FE-132 (WP-B) i FE-133 (WP-C) równolegle → FE-122; FE-134 (WP-D) w dowolnym momencie; FE-121 = parasol; (4) układ i wydajność FE-123 → FE-124; (5) zamknięcie FE-125; poza zamknięciem: BE-153 → FE-130 (`statusSince`).

| ID | Nazwa | Status |
|----|-------|--------|
| FE-114 | Hardening momentu dzwonienia: widoczny baner, skróty klawiszowe, ściąga, Esc w modalach (faza 0) | ✅ |
| FE-115 | Kontrast tokenów: tekst wyciszony, tekst na akcencie/danger/success, obramowania kontrolek (faza 1) | ✅ |
| FE-116 | Palety hex → tokeny (motyw ciemny) i Lamp Rule na powierzchni agenta (faza 1) | ✅ |
| FE-117 | Landmarki i fokus: jeden `<main>`, unikalny `#main-content`, widoczny fokus, breadcrumb (faza 1) | ✅ |
| FE-118 | Skala typografii: minimalny rozmiar tekstu i etykiety KPI (faza 1) | ✅ |
| FE-119 | Teksty i dane na sztywno: KPI, marka, placeholder CHAT, i18n, martwy komponent, duplikat „Dodaj przerwę” (faza 2) | ✅ |
| FE-120 | Domknięcia po hardeningu: szkic manual-callback, Esc w ściądze, formularz przerwy, jeden toast, ponowienie karty klienta (faza 2) | ✅ |
| FE-121 | Szkice per kontakt i stan kart (PARASOL: FE-131…FE-134; brief zatwierdzony 2026-10-23) (faza 3) | ⬜ |
| FE-122 | ACW (dyspozycja) jako niemodalny stan karty kontaktu (faza 3) | ⬜ |
| FE-123 | Układ 1366×768: elastyczne kolumny, mniejszy chrome, breakpointy z tokenów (faza 4) | ⬜ |
| FE-124 | Optymalizacja stylów: budżet `agent-desktop.scss`, fallbacki hex, `will-change` i animacje (faza 4) | ⬜ |
| FE-125 | Ponowny audyt i krytyka, aktualizacja `UI-REVIEW.md` i `DESIGN.md` (faza 5) | ⬜ |
| FE-126 | Obsługa klawiatury list umiejętności (`.skill-dropdown`) w admin-user-form, queue-form, user-form (faza 2) | ✅ |
| FE-127 | [BUG] `AdminUserFormComponent` nie ładuje `availableSkills` — lista podpowiedzi umiejętności zawsze pusta (wynik FE-126) | ⬜ |
| FE-128 | i18n poza powierzchnią agenta: literały PL w `admin-user-form`, `queue-form`, `softphone.service` (wynik FE-119) | ⬜ |
| FE-129 | Format KPI „Czas pracy” w top-navbar: 225:56 (minuty > 60 w formacie mm:ss) (wynik FE-119) | ⬜ |
| FE-130 | Licznik przerwy `cc-status-elapsed` z `statusSince` z API; usunięcie adnotacji „w tej sesji” (czeka na BE-153) | ⬜ |
| BE-153 | `statusSince`: czas rozpoczęcia bieżącego statusu agenta w `GET /users/me` i zdarzeniu WS zmiany statusu (blokuje FE-130) | ⬜ |
| FE-131 | WP-A (FE-121): magazyn szkiców per kontakt (e-mail, social, notatka PHONE), bez ponownego `acceptContact` (faza 3) | ⬜ |
| FE-132 | WP-B (FE-121): bezpieczne zamykanie kart — × poza `role="tab"`, Delete, potwierdzenia, „Zwróć do kolejki” (faza 3) | ⬜ |
| FE-133 | WP-C (FE-121): znaczniki stanu kart (ACW > Wstrzymana > Rozmowa > Szkic), nowy kontakt bez przejmowania aktywnej karty (faza 3) | ⬜ |
| FE-134 | WP-D (FE-121): status agenta w górnym pasku na `/agent/*`, CTA „Utwórz profil”/„Pełny profil” ukryte dla agenta (faza 3) | ⬜ |
| FE-135 | Znacznik „nieprzeczytane / czeka klient” na kartach e-mail/social (czeka na BE-154 i FE-133) | ⬜ |
| FE-136 | Odtwarzanie kart po przeładowaniu: wiele kontaktów, SOCIAL, `ACTIVE`, ACW (czeka na BE-157) | ⬜ |
| BE-154 | Zdarzenie WS o nowej wiadomości w otwartym kontakcie e-mail/social (blokuje FE-135) | ⬜ |
| BE-155 | [BUG] Kontakt social prawdopodobnie nigdy nie trafia do agenta (kolejka `null` → `ERROR`, kanał `SOCIAL_*`, `COMPLETED`) | ⬜ |
| BE-156 | Routing egzekwuje `max_concurrent_contacts_per_agent` i obciążenie agenta | ⬜ |
| BE-157 | Lista aktywnych kontaktów agenta `GET /api/agent/me/active-contacts` (blokuje FE-136) | ⬜ |

### Bez epiku – porządki infrastruktury

BE-144 (porządkowy, przepięcie obrazów MinIO z Docker Hub na quay.io z przypiętym tagiem) — **BLOKOWANY zewnętrznie** (weryfikacja 2026-10-11): anonimowo niedostępne `quay.io/minio/minio`, `quay.io/minio/mc:latest` i `minio/minio:latest`; próba AIStor (tura 32) negatywna — bez licencji operacje S3 odrzucane. NIE przepinać na AIStor bez licencji. Decyzja właściciela (np. mirror do rejestru zespołu) nadal otwarta.

| ID | Nazwa | Status |
|----|-------|--------|
| BE-144 | [PORZĄDKOWY] Przepięcie obrazów MinIO z Docker Hub na quay.io z przypiętym tagiem | ⬜ |

### Weryfikacje opcjonalne po zamknięciu EPIC-30 (WP-4; nie blokują zamknięcia)

| Ticket | Co zweryfikować | Kto | Status |
|--------|-----------------|-----|--------|
| BE-120 | Flaga `retention.campaign-archive.enabled` (zostaje WYŁĄCZONA), ewentualny jednorazowy przebieg archiwizacji w demo po ustawieniu okresu ≤ 90–120 dni | właściciel (zgoda) + backend | NIEWYKONANE, opcjonalne |
| BE-123 | Log INFO `PartitionReclaimJob` dla `audit_log`/`plugin_invocation_log` (horyzont platformowy) | backend | NIEWYKONANE, opcjonalne |
| DB-078 | Weryfikacja po zastosowaniu V127/V128 (sweep+drop `contacts_dw.remote_address`) — zastosowane 2026-10-09 poza procedurą zgody | właściciel | NIEWYKONANE, opcjonalne |
| BE-131 | Sweep porzuconych załączników `pending` w MinIO (oczekiwany 1 z 9) | backend + zgoda właściciela | NIEWYKONANE, opcjonalne |
| FE-113 | Lista A–H w przeglądarce | właściciel | WYKONANE 2026-10-22 (ogólnie „sprawdzone, jest OK”, bez wyników per punkt); brak formalnego przeglądu tłumaczeń de/uk przez native speakera, czytnik ekranu niepotwierdzony; dług: format daty niezależny od języka |
| FE-110, BE-122, DB-087 (V143) | — | — | WYKONANE (FE-110 2026-10-10; BE-122 tura 29; V143 zastosowana 2026-10-22 19:21:32 UTC, niezmierzony plan na żywej bazie — archiwum puste) |

Pozostałe niedomknięcia EPIC-30 (z kryteriów): starsze nieodhaczone `[ ]` w ✅ ticketach (m.in. DB-064, DB-065, BE-132, BE-133, BE-142, FE-112) nie były weryfikowane — zostawione bez zmian. Kryterium (b) (decyzje D1–D10) spełnione; kryterium (a) (Must Have ✅) spełnione.

---

## Następne kroki

1. Opcjonalne WP-4 (tabela powyżej). V142 i V143 są już zastosowane.
2. DB-069: po spełnieniu wyzwalacza wolumenowego i zmianie PK — twardy punkt: weryfikacja `purge_campaign_contact_archive` (V143), zachować oba `SET enable_*=off`. BE-137 — dopiero po wyzwalaczu.
3. EPIC-31 wstrzymany (decyzja właściciela); BE-144 — decyzja właściciela (licencja AIStor) otwarta.
4. EPIC-18 (BE-T001…T004, FE-T001…T004) — testy jednostkowe do zaplanowania.
5. EPIC-32 (UX i dostępność stanowiska agenta): FE-114…FE-120 i FE-126 ✅ (fazy 0–2 zamknięte, tura 50); `/impeccable shape` FE-121/FE-122 zatwierdzony (tura 51) — zacommitować zmiany z gałęzi `feature/epic-32-agent-ux-a11y` (na życzenie użytkownika); kolejność otwartych: (1) faza 3: FE-131 (WP-A) → FE-132 (WP-B) i FE-133 (WP-C) równolegle → FE-122; FE-134 (WP-D) w dowolnym momencie; nowe tickety BE równolegle (`backend-dev-expert`): BE-155 (bug social, rekomendowany Must Have — ocena właściciela), BE-154 (zdarzenie WS → FE-135), BE-156 (limity routingu); FE-136 po FE-122, FE-131 i BE-157 (endpoint listy aktywnych kontaktów — `backend-dev-expert`), (2) FE-123 (układ 1366×768), (3) FE-124 (styły; z dopiskami z tury 50), (4) FE-125 (ponowny audyt, po FE-127…FE-129), (5) nowe: FE-127 (bug `availableSkills`, S), FE-128 (i18n poza powierzchnią agenta, S), FE-129 (format „Czas pracy”, S) — mogą iść równolegle z fazą 3; BE-153 → FE-130 (`statusSince`) poza zamknięciem audytu. Otwarte kryteria FE-114: merge `fix/agent-incoming-call-hardening` i test z prawdziwym połączeniem.

## Znane problemy

Brak otwartych pozycji w dawnej tabeli „Znane problemy i naprawione błędy” (wszystkie naprawione — pełna tabela w archiwum). Jedyne znane długi: format daty w FE-113 niezależny od języka oraz niezweryfikowane starsze `[ ]` w ✅ ticketach EPIC-30 (patrz wyżej).
