# TASKS-FRONTEND.md
# Contact Center SaaS – Zadania deweloperskie: Frontend (Angular SPA)

**Wersja:** 1.0
**Data:** 2026-03-12
**Stack:** Angular (TypeScript), WebRTC, RxJS, Angular Material / PrimeNG
**Powiązany PRD:** PRD v1.0

---

## Konwencje

- Prefiks ID: `FE-`
- Priorytety: **Must Have** (MVP), **Should Have** (kolejna iteracja)
- Rozmiary: S (< 1 dzien), M (1-2 dni), L (3-5 dni), XL (> 5 dni)
- Każde zadanie zakłada izolację komponentu/modułu – brak konfliktów merge przy pracy równoległej
- Dane mockowane przez MSW lub Angular HttpClientTestingModule do czasu gotowości backendu

---

## MODUL: Fundament aplikacji

### FE-001 – Inicjalizacja projektu Angular i konfiguracja workspace

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** brak
**Status:** ✅ Ukończone
**Czeka na BE:** brak
**Blokuje:** FE-002, FE-003
**Odniesienie PRD:** przekrojowe

**Opis:**
Wygenerowanie projektu Angular (nx monorepo lub angular/cli), konfiguracja ESLint, Prettier, Husky pre-commit hooks. Ustawienie struktury katalogów: `core/`, `shared/`, `features/`, `environments/`. Konfiguracja proxy deweloperskiego do backendu (proxy.conf.json).

**Kryteria akceptacji:**
- [ ] `ng build` i `ng test` kończą się bez błędów
- [ ] ESLint i Prettier skonfigurowane z regułami projektu
- [ ] Struktura katalogów zgodna z przyjętą architekturą modularną
- [ ] Proxy do backendu (localhost:8080) skonfigurowany w proxy.conf.json

---

### FE-002 – Konfiguracja routingu, lazy loading i guard AuthGuard

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-001
**Status:** ✅ Ukończone
**Czeka na BE:** brak
**Blokuje:** FE-004
**Odniesienie PRD:** przekrojowe

**Opis:**
Definicja głównych tras aplikacji z lazy loadingiem modułów feature. Implementacja `AuthGuard` (sprawdza ważność JWT z localStorage/sessionStorage) i `RoleGuard` (weryfikuje role: ADMIN, SUPERVISOR, AGENT). Obsługa przekierowania na `/login` przy wygaśnięciu sesji.

**Kryteria akceptacji:**
- [ ] Moduły feature ładowane lazy (widoczne w Network tab jako osobne chunk-i)
- [ ] Próba wejścia na chronioną trasę bez JWT → redirect do /login
- [ ] Próba wejścia na trasę niedostępną dla roli → redirect do /forbidden
- [ ] Refresh tokenu wykonywany transparentnie przez HTTP Interceptor

---

### FE-003 – HTTP Interceptor: JWT, refresh token, obsługa błędów 401/403

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-001
**Status:** ✅ Ukończone
**Czeka na BE:** brak
**Blokuje:** FE-004
**Odniesienie PRD:** przekrojowe, wymagania bezpieczenstwa

**Opis:**
Implementacja `AuthInterceptor` dodającego nagłówek `Authorization: Bearer <token>` do każdego żądania. Logika automatycznego odświeżenia tokenu (retry z nowym tokenem po otrzymaniu 401). Globalny handler błędów HTTP wyświetlający notyfikacje toast dla błędów 4xx/5xx.

**Kryteria akceptacji:**
- [ ] Każde żądanie HTTP zawiera nagłówek Authorization
- [ ] Po wygaśnięciu JWT interceptor wykonuje refresh i ponawia oryginalne żądanie
- [ ] Błąd 403 wyświetla komunikat "Brak uprawnień" (toast)
- [ ] Błąd 5xx wyświetla komunikat "Błąd serwera, spróbuj ponownie" (toast)

---

### FE-004 – Moduł uwierzytelniania: ekran logowania i MFA

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-002, FE-003
**Status:** ✅ Ukończone
**Czeka na BE:** BE-004 (produkcyjnie; aktualnie działa z seed data)
**Blokuje:** FE-005
**Odniesienie PRD:** US-02-04, wymagania bezpieczenstwa (MFA)

**Opis:**
Ekran logowania z polami email/hasło i walidacją reaktywną. Po poprawnym uwierzytelnieniu – obsługa kroku MFA (wprowadzenie kodu TOTP). Zapis JWT i refresh tokenu w pamięci aplikacji (access token in-memory, refresh token w httpOnly cookie jeśli backend wspiera, lub sessionStorage jako fallback). Ekran "wymuszona zmiana hasła" po flagie `password_reset_required`.

**Kryteria akceptacji:**
- [ ] Formularz logowania waliduje format email i niepuste hasło
- [ ] Po poprawnym logowaniu z MFA użytkownik trafia do dashboardu swojej roli
- [ ] Przy `password_reset_required=true` wyświetlany jest formularz zmiany hasła przed dashboardem
- [ ] Przycisk "Zaloguj" zablokowany (spinner) podczas oczekiwania na odpowiedź API

---

### FE-005 – Shell aplikacji: top navbar, sidenav, breadcrumbs, notyfikacje

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** FE-004
**Status:** ✅ Ukończone
**Czeka na BE:** brak
**Blokuje:** FE-006, FE-007, FE-008, FE-009, FE-014, FE-015, FE-018, FE-021, FE-022, FE-023, FE-024
**Odniesienie PRD:** przekrojowe (wszystkie persony)

**Opis:**
Główny layout aplikacji: responsywny sidebar z nawigacją kontekstową zależną od roli (ADMIN/SUPERVISOR/AGENT widzi inne pozycje menu), top bar z informacją o użytkowniku i tenant, system powiadomień toast (RxJS Subject singleton), komponent breadcrumb generowany z aktywnej trasy.

**Kryteria akceptacji:**
- [ ] Menu sidebar pokazuje tylko pozycje dostępne dla aktualnej roli
- [ ] Wylogowanie czyści stan aplikacji i przekierowuje na /login
- [ ] Toast notyfikacje widoczne ponad wszystkimi komponentami (z-index)
- [ ] Aplikacja jest responsywna (breakpoint tablet 1024px, desktop 1280px+)
- [ ] Spełnia WCAG 2.1 AA (kontrast, nawigacja klawiaturą, aria-labels)

---

## MODUL: Zarządzanie Tenantami (EPIC-01)

### FE-006 – Lista tenantów i formularz tworzenia tenanta

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-005
**Status:** ✅ Ukończone
**Czeka na BE:** BE-006 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-01-01, US-01-02, EPIC-01

**Opis:**
Widok listy tenantów (tabela z paginacją, filtrowaniem po nazwie i statusie) dostępny dla roli ADMIN. Formularz tworzenia nowego tenanta (pola: nazwa, plan, limity zasobów jako JSONB editor). Akcja dezaktywacji tenanta z potwierdzeniem modalnym (bez usunięcia danych).

**Kryteria akceptacji:**
- [ ] Lista tenantów paginowana po 20 rekordów
- [ ] Filtrowanie działa po stronie API (query params), nie po stronie klienta
- [ ] Formularz tworzenia waliduje unikalność nazwy (async validator → GET /api/tenants/check-name)
- [ ] Dezaktywacja wymaga potwierdzenia w modalu z nazwą tenanta
- [ ] Status tenanta prezentowany jako badge (aktywny/nieaktywny/zawieszony)

---

### FE-007 – Dashboard techniczny administratora (metryki tenantów RT)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** FE-005
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-17
**Czeka na BE:** BE-007 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-01-04, US-10-04, EPIC-01

**Opis:**
Dashboard dla roli ADMIN z kartami metryk: liczba aktywnych tenantów, łączna liczba agentów online, alerty systemowe. Dane odświeżane co 30 sekund przez polling lub WebSocket. Wykresy liniowe (CPU/mem per tenant) zaimplementowane przez bibliotekę chart (np. Chart.js / ngx-charts).

**Kryteria akceptacji:**
- [ ] Dashboard odświeża dane automatycznie co 30s bez interakcji użytkownika
- [ ] Wykresy renderują się poprawnie dla zakresu ostatnich 24h
- [ ] Brak danych (tenant bez aktywności) wyświetla stan "brak danych" zamiast pustego wykresu
- [ ] Dostępny wyłącznie dla roli ADMIN (guard)

---

## MODUL: Zarządzanie Użytkownikami i Rolami (EPIC-02)

### FE-008 – Zarządzanie agentami: lista, tworzenie, edycja, skills

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** FE-005, BE-008
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-17
**Czeka na BE:** BE-008 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-02-01, US-02-02, US-02-03, EPIC-02

**Opis:**
Widok dla roli SUPERVISOR: tabela agentów tenanta z filtrami (status, skill). Formularz tworzenia/edycji agenta z multi-select skills (tagi z autocomplete). Panel przypisywania agenta do kolejek. Akcja wymuszenia resetu hasła (POST /api/users/{id}/force-password-reset) z potwierdzeniem.

**Kryteria akceptacji:**
- [x] Supervisor widzi tylko agentów swojego tenanta
- [x] Formularz skills używa multi-select z tagami i zapisuje jako tablica string[]
- [x] Wymuszone reset hasła wysyła e-mail i zmienia status użytkownika na `password_reset_required`
- [x] Dezaktywacja agenta z aktywnymi kontaktami wyświetla ostrzeżenie (soft block – HTTP 409 guard)

---

## MODUL: Agent Desktop (EPIC-03, EPIC-05, EPIC-06, EPIC-07)

### FE-009 – Agent Desktop: główny layout i panel statusu agenta

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** FE-005, BE-012, BE-008
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-012 (lub MSW WebSocket)
**Blokuje:** FE-010, FE-011, FE-012, FE-013, FE-017
**Odniesienie PRD:** US-07-05, EPIC-03, EPIC-05, EPIC-06

**Opis:**
Główny widok agenta po zalogowaniu: panel zmiany statusu (dostępny/zajęty/przerwa/po-kontakcie), kolejka przychodzących kontaktów z liczbą oczekujących, obszar aktywnych kontaktów (max 1 telefoniczny + 3 chat/email widoczne jednocześnie jako zakładki). Integracja z WebSocket dla aktualizacji RT.

**Kryteria akceptacji:**
- [ ] Agent może zmieniać status i zmiana jest natychmiast widoczna dla supervisora
- [ ] Obszar kontaktów pokazuje max 4 zakładki (1 telefon + 3 chat/email)
- [ ] Próba otwarcia 5. kontaktu wyświetla komunikat o limicie
- [ ] WebSocket rozłączenie wyświetla baner "Utracono połączenie – próba reconnect"

---

### FE-010 – Komponent Softphone WebRTC

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależności:** FE-009, BE-009, BE-012
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-009, BE-012 (trudne do zamockowania)
**Blokuje:** brak
**Odniesienie PRD:** US-03-01, US-03-02, US-03-03, US-03-04, EPIC-03

**Opis:**
Komponent softphone w przeglądarce oparty na WebRTC (np. integracja z SIP.js lub JsSIP). Funkcje: odbieranie/rozłączanie połączenia, mute mikrofonu, hold (sygnał muzyki), blind transfer (podanie numeru docelowego), attended transfer (konsultacja przed przekazaniem). Wyświetlanie CLi (numer) i profilu klienta podczas połączenia.

**Kryteria akceptacji:**
- [ ] Połączenie przychodzące wyświetla powiadomienie z numerem dzwoniącego i przyciskiem "Odbierz"
- [ ] Mute lokalnie wycisza mikrofon (MediaStream.getAudioTracks().enabled = false)
- [ ] Hold wysyła sygnał SIP HOLD i wyświetla timer oczekiwania
- [ ] Blind transfer przekierowuje połączenie bez konsultacji po podaniu numeru
- [ ] Attended transfer otwiera drugie połączenie konsultacyjne przed przekazaniem
- [ ] Komponent działa w Chrome, Firefox, Edge (testy cross-browser)

---

### FE-011 – Panel profilu klienta podczas kontaktu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-009, BE-025, BE-011
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-025, BE-011 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-03-02, US-09-02, EPIC-09

**Opis:**
Panel boczny wyświetlany podczas aktywnego kontaktu: dane klienta (imię, nazwisko, telefon, email), historia ostatnich 10 kontaktów z danym klientem (kanał, data, disposition). Link do pełnego profilu klienta. Jeśli klient nieznany – przycisk "Utwórz profil" pre-wypełniający formularz numerem CLI.

**Kryteria akceptacji:**
- [ ] Panel ładuje dane klienta po numerze CLI w czasie < 1s (z cache jeśli możliwe)
- [ ] Historia kontaktów pokazuje ostatnie 10 wpisów z ikoną kanału
- [ ] Dla nieznanego numeru wyświetlany jest stan "Nieznany klient" z CTA tworzenia profilu
- [ ] Formularz tworzenia profilu pre-wypełnia pole telefonu numerem CLI

---

### FE-012 – Komponent obsługi kontaktu email

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** FE-009, BE-015, BE-016
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-015, BE-016 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-05-01, US-05-02, US-05-03, US-05-04, EPIC-05

**Opis:**
Widok obsługi wiadomości email: panel z treścią emaila (HTML render), edytor odpowiedzi z rich text (Quill lub TinyMCE), wybór szablonu odpowiedzi z listy rozwijanej, przeglądanie wątku (thread). Przypisanie wątku do profilu klienta (search + select).

**Kryteria akceptacji:**
- [ ] HTML treść emaila renderowana w izolowanym iframe (ochrona XSS)
- [ ] Szablony odpowiedzi ładowane z API i filtrowane autocomplete
- [ ] Odpowiedź wysłana → zakładka kontaktu zamknięta, status zmieniony na "zamknięty"
- [ ] Wątek emailowy paginowany (load more) dla konwersacji > 20 wiadomości

---

### FE-013 – Komponent obsługi kontaktu social media

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-009, BE-018
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-018 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-06-01, US-06-02, US-06-03, US-06-04, EPIC-06

**Opis:**
Widok obsługi wiadomości z social media (Facebook Messenger, Instagram, WhatsApp): chat UI z historią konwersacji, pole odpowiedzi z emoji picker, znaczniki czasu, wskaźnik "platforma" (ikona). Tryb read-only dla historii zamkniętych konwersacji.

**Kryteria akceptacji:**
- [ ] Ikona platformy widoczna obok każdej wiadomości
- [ ] Nowe wiadomości w aktywnej zakładce wyświetlane natychmiast przez WebSocket push
- [ ] Historia konwersacji scroll-paginowana (infinite scroll, load 20 wiadomości)
- [ ] Pola odpowiedzi zablokowane gdy konwersacja jest w statusie "zamknięta"

---

## MODUL: IVR i Automatyzacja (EPIC-04)

### FE-014 – Graficzny edytor drzewa IVR (drag & drop)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależności:** FE-005, BE-020, BE-013
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-020, BE-013 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-04-01, US-04-03, US-04-04, EPIC-04

**Opis:**
Edytor wizualny IVR oparty na bibliotece flow-graph (np. Angular Flow, GoJS lub React Flow jako web component). Węzły: Start, Menu (DTMF), Play Audio, TTS Prompt, Transfer to Queue, Transfer to Agent, Hangup. Drag & drop węzłów, łączenie krawędziami, konfiguracja węzła w panelu bocznym (upload audio MP3/WAV lub wprowadzenie tekstu TTS). Zapis drzewa jako JSONB.

**Kryteria akceptacji:**
- [ ] Dodawanie i usuwanie węzłów bez przeładowania
- [ ] Połączenia między węzłami rysowane interaktywnie przez drag
- [ ] Upload pliku audio (max 10MB, formaty MP3/WAV) z progress bar
- [ ] Zapis IVR generuje podgląd JSONB w panelu debug
- [ ] Walidacja: brak "wiszących" węzłów bez wyjścia, ostrzeżenie przed zapisem
- [ ] Wersjonowanie: lista wersji IVR z możliwością podglądu poprzedniej wersji

---

## MODUL: Kampanie Outbound (EPIC-08)

### FE-015 – Zarządzanie kampaniami: lista i formularz tworzenia

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** FE-005, BE-022
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-022 (lub MSW)
**Blokuje:** FE-016
**Odniesienie PRD:** US-08-01, US-08-02, US-08-06, EPIC-08

**Opis:**
Widok listy kampanii dla SUPERVISOR: tabela z kolumnami (nazwa, status, progres, start, koniec). Formularz tworzenia kampanii: nazwa, typ (inbound/outbound), dialer type (progressive/predictive), harmonogram (date picker, time range picker, dni tygodnia checkboxes). Akcje inline: uruchom, wstrzymaj, zatrzymaj (z potwierdzeniem).

**Kryteria akceptacji:**
- [ ] Status kampanii aktualizowany RT przez polling co 10s lub WebSocket
- [ ] Przyciski akcji (uruchom/wstrzymaj/zatrzymaj) zmieniają stan wg możliwych przejść
- [ ] Formularz harmonogramu waliduje: data końca > data startu, przynajmniej 1 dzień tygodnia wybrany
- [ ] Tabela sortowalna po kolumnach: nazwa, status, data startu

---

### FE-016 – Import listy kontaktów CSV do kampanii

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-015, BE-023
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-023 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-08-01, EPIC-08

**Opis:**
Komponent upload pliku CSV z mapowaniem kolumn: użytkownik wskazuje która kolumna CSV odpowiada polu systemowemu (telefon, imię, nazwisko, custom_fields). Walidacja formatu pliku przed wysłaniem (max 50MB, tylko .csv). Pasek postępu importu (polling statusu zadania async). Raport po zakończeniu: ile rekordów zaimportowano, ile odrzucono z powodu błędów.

**Kryteria akceptacji:**
- [ ] Plik > 50MB wyświetla błąd przed wysłaniem (walidacja client-side)
- [ ] Mapowanie kolumn CSV działa dla plików z nagłówkiem i bez nagłówka
- [ ] Pasek postępu odpytuje endpoint statusu co 3s i zamyka się po zakończeniu
- [ ] Raport końcowy pokazuje liczbę sukcesów i błędów z przykładowymi wadliwymi rekordami

---

### FE-017 – Panel disposition codes po zakończeniu kontaktu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależności:** FE-009, BE-027
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-027 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-08-04, US-08-05, EPIC-08

**Opis:**
Modal/panel po-kontaktowy wyświetlany automatycznie po zakończeniu połączenia. Dropdown z disposition codes (ładowanymi per tenant z API). Pole notatki (textarea, opcjonalne). Timer w panelu (czas pracy po-kontaktowej). Przycisk "Zapisz i zamknij" → zmiana statusu agenta na "dostępny".

**Kryteria akceptacji:**
- [ ] Panel otwiera się automatycznie po rozłączeniu połączenia
- [ ] Disposition codes ładowane z API per tenant (cached 5 min)
- [ ] Zapis disposition wysyła PATCH /api/contacts/{id}/disposition
- [ ] Timer wskazuje czas spędzony w stanie "po-kontaktowym" (wliczany do raportów)

---

## MODUL: Baza Klientów (EPIC-09)

### FE-018 – Wyszukiwanie i lista klientów (fuzzy search)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-005, BE-025
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-025 (lub MSW)
**Blokuje:** FE-019, FE-020
**Odniesienie PRD:** US-09-01, US-09-03, EPIC-09

**Opis:**
Strona bazy klientów z globalnym polem wyszukiwania (debounce 300ms, min 2 znaki, fuzzy search przez backend). Wyniki w tabeli z paginacją (20 rekordów). Kolumny: imię, nazwisko, telefon, email, data ostatniego kontaktu. Akcje: edytuj, wyświetl profil, usuń (RODO – soft delete z potwierdzeniem i informacją o anonimizacji).

**Kryteria akceptacji:**
- [ ] Wyszukiwanie inicjowane po 300ms debounce i min 2 znakach
- [ ] Wyniki paginowane, link "Załaduj więcej" lub numeracja stron
- [ ] Usunięcie klienta wymaga potwierdzenia z komunikatem RODO
- [ ] Tabela ma nagłówki z sortowaniem (imię, nazwisko, data kontaktu)

---

### FE-019 – Profil klienta: widok szczegółowy i historia kontaktów

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** FE-018, BE-025, BE-027
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-025, BE-027 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-09-02, US-09-03, US-09-04, EPIC-09

**Opis:**
Strona profilu klienta: sekcja danych podstawowych (imię, nazwisko, wielowartościowe pola telefon[] i email[]), sekcja custom_fields (dynamiczny formularz z pól JSONB), oś czasu historii kontaktów (kanał, agent, data, disposition, link do nagrania jeśli dostępny), status zgody RODO.

**Kryteria akceptacji:**
- [ ] Formularz edycji obsługuje wielokrotne wartości dla telefon i email (add/remove chip)
- [ ] Custom fields renderowane dynamicznie na podstawie schematu z API
- [ ] Historia kontaktów paginowana (20 per strona), sortowalna po dacie
- [ ] Link do nagrania otwiera odtwarzacz audio inline (jeśli nagranie dostępne i uprawnienia OK)
- [ ] Badge "RODO: zgoda" lub "RODO: brak zgody" z datą ostatniej zgody

---

### FE-020 – Import klientów z CSV

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-018, BE-026
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-026 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-09-05, EPIC-09

**Opis:**
Komponent importu bazy klientów z pliku CSV (analogiczny do FE-016 ale dedykowany dla modułu klientów). Mapowanie kolumn, walidacja po stronie klienta (max 50MB), async job z paskiem postępu. Deduplikacja po telefonie/emailu (opcja: pomij duplikat / nadpisz).

**Kryteria akceptacji:**
- [ ] Opcja deduplikacji widoczna w kroku mapowania kolumn
- [ ] Raport importu zawiera liczbę: dodanych, zaktualizowanych, pominiętych, błędnych
- [ ] Użytkownik może pobrać plik błędnych rekordów jako CSV

---

## MODUL: Raportowanie i Analityka (EPIC-10)

### FE-021 – Dashboard RT supervisora

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** FE-005, BE-029
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-029 (lub MSW WebSocket)
**Blokuje:** brak
**Odniesienie PRD:** US-10-01, EPIC-10

**Opis:**
Dashboard real-time dla SUPERVISOR: karty KPI (aktywne połączenia, agenci online/przerwa/dostępni, śr. czas oczekiwania, śr. czas obsługi), tabela agentów z aktualnym statusem i aktualnym kontaktem, wykres kolejek (liczba oczekujących per kolejka). Dane aktualizowane przez WebSocket lub polling co 5s.

**Kryteria akceptacji:**
- [ ] Dane odświeżane co max 5 sekund (wymaganie PRD ≤ 5s)
- [ ] Tabela agentów podświetla agentów na przerwie > 10 min (konfigurowalny próg)
- [ ] Wykresy kolejek aktualizowane animowane (bez migotania całego komponentu)
- [ ] Dashboard ma tryb pełnoekranowy (F11 lub przycisk "fullscreen")

---

### FE-022 – Raporty historyczne: filtry, tabele, eksport

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** FE-005, BE-028
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-028 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-10-02, US-10-03, US-10-05, EPIC-10

**Opis:**
Moduł raportów historycznych: filtry (zakres dat, agent, kolejka, kanał, kampania), tabela wynikowa z sortowaniem i paginacją. Raporty: wydajność agentów, statystyki kampanii outbound, ruch per kolejka. Eksport do CSV i Excel (XLSX) przez pobieranie pliku z API.

**Kryteria akceptacji:**
- [ ] Filtry zachowywane w URL query params (sharable link)
- [ ] Eksport CSV/XLSX inicjuje pobieranie pliku bez przeładowania strony (Blob URL)
- [ ] Tabela z kolumną "Nagranie" ma link otwierający odtwarzacz (jeśli uprawnienia OK)
- [ ] Brak danych dla wybranych filtrów pokazuje stan "brak wyników" z sugestią zmiany filtrów

---

## MODUL: Konfiguracja i Integracje (EPIC-01, EPIC-06)

### FE-023 – Panel konfiguracji integracji social media (OAuth flow)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-005, BE-017
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-017 (OAuth wymaga prawdziwego BE)
**Blokuje:** brak
**Odniesienie PRD:** US-06-02, EPIC-06

**Opis:**
Widok konfiguracji integracji per tenant: lista platform (Facebook, Instagram, WhatsApp) ze statusem połączenia. Przycisk "Połącz" inicjuje OAuth 2.0 flow (redirect → callback → token zapis). Przycisk "Rozłącz" (z potwierdzeniem). Status webhook (aktywny/błąd).

**Kryteria akceptacji:**
- [ ] OAuth flow działa przez redirect z powrotem do aplikacji (callback URL skonfigurowany)
- [ ] Po udanym połączeniu status zmienia się na "Połączony" z datą autoryzacji
- [ ] Błąd OAuth wyświetla czytelny komunikat (np. "Odmowa dostępu przez użytkownika")
- [ ] Rozłączenie usuwa token z backendu i zmienia status na "Niepołączony"

---

### FE-024 – Panel konfiguracji kolejek i routingu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** FE-005, BE-020
**Status:** ⬜ Nie rozpoczęte
**Czeka na BE:** BE-020 (lub MSW)
**Blokuje:** brak
**Odniesienie PRD:** US-07-01, US-07-02, US-07-03, EPIC-07

**Opis:**
Formularz tworzenia/edycji kolejki: nazwa, strategia routingu (round-robin/first-available/skill-based), wymagane skills (multi-select), sticky agent timeout (input numeryczny, domyślnie 60s). Lista kolejek z liczbą agentów i aktualnym obciążeniem.

**Kryteria akceptacji:**
- [ ] Zmiana strategii routingu na "skill-based" ujawnia sekcję wymaganych skills
- [ ] Sticky agent timeout tylko liczba całkowita > 0 (walidacja)
- [ ] Lista kolejek pokazuje aktualną liczbę oczekujących (polling co 10s)

---

---

## Zależności między zadaniami

### Kolejność obowiązkowa (blokery)

```
FE-001 → FE-002 → FE-003 → FE-004 → FE-005
FE-005 → FE-006, FE-007, FE-008, FE-015, FE-018, FE-021, FE-022, FE-023, FE-024
FE-009 (Agent Desktop) → FE-010, FE-011, FE-012, FE-013, FE-017
FE-015 (Kampanie) → FE-016
FE-018 (Lista klientów) → FE-019, FE-020
```

### Zadania możliwe do realizacji równoległej (po odblokowaniu FE-005)

| Ścieżka | Zadania |
|---------|---------|
| Admin | FE-006, FE-007 |
| Supervisor / Agenci | FE-008, FE-024 |
| Agent Desktop | FE-009 → FE-010, FE-011, FE-012, FE-013, FE-017 |
| IVR | FE-014 (niezależny od Agent Desktop) |
| Kampanie | FE-015 → FE-016 |
| Klienci | FE-018 → FE-019, FE-020 |
| Raporty | FE-021, FE-022 |
| Integracje | FE-023 |

### Blokery od Backendu (FE czeka na BE)

| Zadanie FE | Czeka na zadanie BE |
|------------|---------------------|
| FE-004 | BE-004 (auth endpoints) |
| FE-006 | BE-006 (tenant CRUD API) |
| FE-007 | BE-007 (admin metrics API) |
| FE-008 | BE-008 (users/agents API) |
| FE-010 | BE-012 (WebRTC/SIP signaling) |
| FE-012 | BE-015 (email API) |
| FE-013 | BE-018 (social media API) |
| FE-014 | BE-020 (IVR API) |
| FE-015, FE-016 | BE-022 (campaign API) |
| FE-018, FE-019 | BE-025 (customer API) |
| FE-021 | BE-029 (RT metrics WebSocket) |
| FE-022 | BE-030 (reports API) |

> Do czasu gotowości backendu zadania FE mogą używać MSW (Mock Service Worker) do mockowania odpowiedzi API zgodnie z kontraktem OpenAPI.

---

## Podsumowanie zadań Frontend

| Kategoria | Liczba zadań | Must Have | Should Have |
|-----------|-------------|-----------|-------------|
| Fundament | 5 | 5 | 0 |
| Tenants (EPIC-01) | 2 | 2 | 0 |
| Użytkownicy (EPIC-02) | 1 | 1 | 0 |
| Agent Desktop | 5 | 5 | 0 |
| IVR (EPIC-04) | 1 | 1 | 0 |
| Kampanie (EPIC-08) | 3 | 3 | 0 |
| Klienci (EPIC-09) | 3 | 3 | 0 |
| Raporty (EPIC-10) | 2 | 2 | 0 |
| Konfiguracja | 2 | 2 | 0 |
| **RAZEM** | **24** | **24** | **0** |
