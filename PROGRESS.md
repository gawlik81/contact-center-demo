# PROGRESS.md
# Contact Center SaaS – Postęp prac

**Ostatnia aktualizacja:** 2026-10-23 (tura 47 – założenie EPIC-32 „UX i dostępność stanowiska agenta”: FE-114…FE-125, FE-114 ✅ wstecznie).

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
| Backend (BE) | 143 | 0 | 10 | 3 | 156 |
| Frontend (FE) | 113 | 0 | 15 | 1 | 129 |
| **RAZEM** | **338** | **0** | **27** | **7** | **372** |

Liczniki przeliczone skryptem ze statusów w `TASKS-*.md` (2026-10-23, tura 47; tura 47 dodała 12 ticketów FE EPIC-32: FE-114 ✅ + 11 ⬜, DB/BE bez zmian; FE: 113 ✅ + 11 ⬜ EPIC-32 + 4 ⬜ FE-T00x + 1 🚫 = 129); 🚫 (7): DB-063, DB-068, DB-075, BE-130, BE-136, BE-140, FE-111 — tickety warunkowe zamknięte decyzjami D1 = A, D4 = A, D6 = `archived_at` (nie są „ukończone” ani otwartym backlogiem).

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
| EPIC-32 | UX i dostępność stanowiska agenta | — | — | 1/12 | 🔧 W toku | Wyłącznie FE; 11 ⬜ (FE-115…FE-125); FE-114 ✅ (hardening momentu dzwonienia, `322db0c1`, przed merge do develop). Źródło: krytyka 17/40 i audyt 12/20 (Impeccable). |
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

Założony 2026-10-23 (tura 47) na podstawie krytyki `/impeccable critique` (2026-10-21, 17/40), audytu `/impeccable audit` (2026-10-22, 12/20), review `CR-FRONTEND.md` (2026-10-21) i `PRODUCT.md`/`DESIGN.md`. Tylko frontend, bez ticketów BE/DB. Cel zamknięcia: audyt ≥ 16/20, krytyka ≥ 28/40, 0 × P0/P1 (FE-125). Decyzje właściciela: priorytet „Moment dzwonienia” (FE-114 ✅), ACW jako niemodalny stan karty kontaktu (FE-122), zakres „Wszystko”. Otwarte pytania: D1 — KPI „cel 90%”/„z 8:00” usunąć czy zasilać z konfiguracji (FE-119; rekomendacja: usunąć); FE-122 — czy semantyka statusu agenta `AFTER_CONTACT` przy wielu kontaktach wymaga zmian po stronie API (nie zakładano ticketu BE). FE-114 ✅ jest na gałęzi `fix/agent-incoming-call-hardening` (przed merge do develop); otwarte kryterium: test z prawdziwym połączeniem przychodzącym.

Kolejność faz: (1) fundament tokenów FE-115, FE-117 (równolegle) → FE-116, FE-118; (2) treść i domknięcia FE-119, FE-120; (3) wiele kontaktów FE-121 (wymaga wcześniej `/impeccable shape`) → FE-122; (4) układ i wydajność FE-123 → FE-124; (5) zamknięcie FE-125.

| ID | Nazwa | Status |
|----|-------|--------|
| FE-114 | Hardening momentu dzwonienia: widoczny baner, skróty klawiszowe, ściąga, Esc w modalach (faza 0) | ✅ |
| FE-115 | Kontrast tokenów: tekst wyciszony, tekst na akcencie/danger/success, obramowania kontrolek (faza 1) | ⬜ |
| FE-116 | Palety hex → tokeny (motyw ciemny) i Lamp Rule na powierzchni agenta (faza 1) | ⬜ |
| FE-117 | Landmarki i fokus: jeden `<main>`, unikalny `#main-content`, widoczny fokus, breadcrumb (faza 1) | ⬜ |
| FE-118 | Skala typografii: minimalny rozmiar tekstu i etykiety KPI (faza 1) | ⬜ |
| FE-119 | Teksty i dane na sztywno: KPI, marka, placeholder CHAT, i18n, martwy komponent, duplikat „Dodaj przerwę” (faza 2) | ⬜ |
| FE-120 | Domknięcia po hardeningu: szkic manual-callback, Esc w ściądze, formularz przerwy, jeden toast, ponowienie karty klienta (faza 2) | ⬜ |
| FE-121 | Szkice per kontakt i stan kart: znaczniki, bezpieczne zamykanie, przydział e-mail/social i status agenta w powłoce (faza 3) | ⬜ |
| FE-122 | ACW (dyspozycja) jako niemodalny stan karty kontaktu (faza 3) | ⬜ |
| FE-123 | Układ 1366×768: elastyczne kolumny, mniejszy chrome, breakpointy z tokenów (faza 4) | ⬜ |
| FE-124 | Optymalizacja stylów: budżet `agent-desktop.scss`, fallbacki hex, `will-change` i animacje (faza 4) | ⬜ |
| FE-125 | Ponowny audyt i krytyka, aktualizacja `UI-REVIEW.md` i `DESIGN.md` (faza 5) | ⬜ |

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
5. EPIC-32 (UX i dostępność stanowiska agenta): najpierw merge `fix/agent-incoming-call-hardening` (FE-114 ✅) i test z prawdziwym połączeniem; start FE-115 + FE-117 (równolegle); przed FE-121 wykonać `/impeccable shape`; decyzja właściciela D1 (KPI) przed FE-119.

## Znane problemy

Brak otwartych pozycji w dawnej tabeli „Znane problemy i naprawione błędy” (wszystkie naprawione — pełna tabela w archiwum). Jedyne znane długi: format daty w FE-113 niezależny od języka oraz niezweryfikowane starsze `[ ]` w ✅ ticketach EPIC-30 (patrz wyżej).
