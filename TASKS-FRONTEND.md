# TASKS-FRONTEND.md
# Contact Center SaaS – Zadania deweloperskie: Frontend (Angular SPA)

**Wersja:** 1.1
**Data:** 2026-03-21
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
**Zależy od:** brak
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
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
**Zależy od:** FE-001
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
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
**Zależy od:** FE-001
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
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
**Zależy od:** FE-002, FE-003
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-18
**Czeka na BE:** BE-004 ✅
**Blokuje:** FE-005
**Odniesienie PRD:** US-02-04, wymagania bezpieczenstwa (MFA)

**Opis:**
Ekran logowania zrealizowany jako flow "email-first": krok 1 – użytkownik wpisuje e-mail, frontend wywołuje `POST /api/public/tenants-by-email` i wykrywa organizacje powiązane z tym adresem; krok 2 – wyświetlane jest pole hasła (i opcjonalny dropdown organizacji gdy >1 trafień); krok 3 – opcjonalny krok MFA (TOTP 6-cyfr). Zapis JWT i refresh tokenu w pamięci aplikacji. Ekran "wymuszona zmiana hasła" po flagie `password_reset_required`. Formularz 3-krokowy z sygnałami Angular (step, matchedTenants, loading, errorMessage).

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
**Zależy od:** FE-004
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-14
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
**Zależy od:** FE-005
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-14
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
**Zależy od:** FE-005
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
**Zależy od:** FE-005, BE-008
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
**Zależy od:** FE-005, BE-012, BE-008
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-18
**Czeka na BE:** BE-012 ✅
**Blokuje:** FE-010, FE-011, FE-012, FE-013, FE-017
**Odniesienie PRD:** US-07-05, EPIC-03, EPIC-05, EPIC-06

**Opis:**
Główny widok agenta po zalogowaniu: panel zmiany statusu (dostępny/zajęty/przerwa/po-kontakcie), kolejka przychodzących kontaktów z liczbą oczekujących, obszar aktywnych kontaktów (max 1 telefoniczny + 3 chat/email widoczne jednocześnie jako zakładki). Integracja z WebSocket dla aktualizacji RT.

**Kryteria akceptacji:**
- [x] Agent może zmieniać status i zmiana jest natychmiast widoczna dla supervisora
- [x] Obszar kontaktów pokazuje max 4 zakładki (1 telefon + 3 chat/email)
- [x] Próba otwarcia 5. kontaktu wyświetla komunikat o limicie
- [x] WebSocket rozłączenie wyświetla baner "Utracono połączenie – próba reconnect"

---

### FE-010 – Komponent Softphone WebRTC

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależy od:** FE-009, BE-009, BE-012
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-18
**Czeka na BE:** BE-009 ✅, BE-012 ✅
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
**Zależy od:** FE-009, BE-025, BE-011
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-20
**Czeka na BE:** BE-025 ✅, BE-011 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-03-02, US-09-02, EPIC-09

**Opis:**
Panel boczny wyświetlany podczas aktywnego kontaktu: dane klienta (imię, nazwisko, telefon, email), historia ostatnich 10 kontaktów z danym klientem (kanał, data, disposition). Link do pełnego profilu klienta. Jeśli klient nieznany – przycisk "Utwórz profil" pre-wypełniający formularz numerem CLI.

**Kryteria akceptacji:**
- [x] Panel ładuje dane klienta po numerze CLI w czasie < 1s (z cache jeśli możliwe)
- [x] Historia kontaktów pokazuje ostatnie 10 wpisów z ikoną kanału
- [x] Dla nieznanego numeru wyświetlany jest stan "Nieznany klient" z CTA tworzenia profilu
- [x] Formularz tworzenia profilu pre-wypełnia pole telefonu numerem CLI

---

### FE-012 – Komponent obsługi kontaktu email

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** FE-009, BE-015, BE-016
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-26
**Czeka na BE:** BE-015 ✅, BE-016 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-05-01, US-05-02, US-05-03, US-05-04, EPIC-05

**Opis:**
Widok obsługi wiadomości email: panel z treścią emaila (HTML render), edytor odpowiedzi z rich text (Quill lub TinyMCE), wybór szablonu odpowiedzi z listy rozwijanej, przeglądanie wątku (thread). Przypisanie wątku do profilu klienta (search + select).

Zrealizowane: `EmailContactComponent` (cc-email-contact, sygnały, paginacja wątku load-more), `EmailThreadMessageComponent` (pojedyncza wiadomość w wątku), `EmailService` (agent, GET wiadomości, POST reply, GET templates), `EmailSettingsComponent` (supervisor settings – formularz konfiguracji IMAP/SMTP per tenant, test połączenia), `EmailConfigService` (supervisor, konfiguracja emaila), integracja z `agent-desktop.component` i `customer-panel.component`.

**Kryteria akceptacji:**
- [x] HTML treść emaila renderowana w izolowanym iframe (ochrona XSS)
- [x] Szablony odpowiedzi ładowane z API i filtrowane autocomplete
- [x] Odpowiedź wysłana → zakładka kontaktu zamknięta, status zmieniony na "zamknięty"
- [x] Wątek emailowy paginowany (load more) dla konwersacji > 20 wiadomości

---

### FE-013 – Komponent obsługi kontaktu social media

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-009, BE-018
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-20
**Czeka na BE:** BE-018 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-06-01, US-06-02, US-06-03, US-06-04, EPIC-06

**Opis:**
Widok obsługi wiadomości z social media (Facebook Messenger, Instagram, WhatsApp): chat UI z historią konwersacji, pole odpowiedzi z emoji picker, znaczniki czasu, wskaźnik "platforma" (ikona). Tryb read-only dla historii zamkniętych konwersacji.

**Kryteria akceptacji:**
- [x] Ikona platformy widoczna obok każdej wiadomości
- [x] Nowe wiadomości w aktywnej zakładce wyświetlane natychmiast przez WebSocket push
- [x] Historia konwersacji scroll-paginowana (infinite scroll, load 20 wiadomości)
- [x] Pola odpowiedzi zablokowane gdy konwersacja jest w statusie "zamknięta"

---

## MODUL: IVR i Automatyzacja (EPIC-04)

### FE-014 – Graficzny edytor drzewa IVR (drag & drop)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależy od:** FE-005, BE-020, BE-013
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-25
**Czeka na BE:** BE-020 ✅, BE-013 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-04-01, US-04-03, US-04-04, EPIC-04

**Opis:**
Edytor wizualny IVR oparty na canvas SVG z obsługą drag & drop węzłów. Węzły: Start, Menu (DTMF), Play Audio, TTS Prompt, Transfer to Queue, Transfer to Agent, Hangup. Drag & drop węzłów, łączenie krawędziami SVG path, konfiguracja węzła w panelu bocznym. Zapis drzewa jako JSONB. Zrealizowane: IvrListComponent (lista drzew IVR z oznaczeniem aktywnego), IvrEditorComponent (canvas SVG, typy węzłów z IVR_NODE_LABELS, DragState, SvgConnection, panel konfiguracji inline), IvrService (frontend), ivr.model.ts (IvrDefinitionUI, IvrNodeUI, IvrNodeType, IvrOption, IvrResponse).

**Kryteria akceptacji:**
- [x] Dodawanie i usuwanie węzłów bez przeładowania
- [x] Połączenia między węzłami rysowane interaktywnie przez drag
- [x] Upload pliku audio (max 10MB, formaty MP3/WAV) z progress bar
- [x] Zapis IVR generuje podgląd JSONB w panelu debug
- [x] Walidacja: brak "wiszących" węzłów bez wyjścia, ostrzeżenie przed zapisem
- [x] Wersjonowanie: lista wersji IVR z możliwością podglądu poprzedniej wersji

---

## MODUL: Kampanie Outbound (EPIC-08)

### FE-015 – Zarządzanie kampaniami: lista i formularz tworzenia

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** FE-005, BE-022
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-22
**Czeka na BE:** BE-022 ✅
**Blokuje:** FE-016
**Odniesienie PRD:** US-08-01, US-08-02, US-08-06, EPIC-08

**Opis:**
Widok listy kampanii dla SUPERVISOR: tabela z kolumnami (nazwa, status, progres, start, koniec). Formularz tworzenia kampanii: nazwa, typ (inbound/outbound), dialer type (progressive/predictive), harmonogram (date picker, time range picker, dni tygodnia checkboxes). Akcje inline: uruchom, wstrzymaj, zatrzymaj (z potwierdzeniem).

**Kryteria akceptacji:**
- [x] Status kampanii aktualizowany RT przez polling co 10s lub WebSocket
- [x] Przyciski akcji (uruchom/wstrzymaj/zatrzymaj) zmieniają stan wg możliwych przejść
- [x] Formularz harmonogramu waliduje: data końca > data startu, przynajmniej 1 dzień tygodnia wybrany
- [x] Tabela sortowalna po kolumnach: nazwa, status, data startu

---

### FE-016 – Import listy kontaktów CSV do kampanii

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-015, BE-023
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-24
**Czeka na BE:** BE-023 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-08-01, EPIC-08

**Opis:**
Komponent upload pliku CSV z mapowaniem kolumn: użytkownik wskazuje która kolumna CSV odpowiada polu systemowemu (telefon, imię, nazwisko, custom_fields). Walidacja formatu pliku przed wysłaniem (max 50MB, tylko .csv). Pasek postępu importu (polling statusu zadania async). Raport po zakończeniu: ile rekordów zaimportowano, ile odrzucono z powodu błędów.

Zrealizowane: CampaignImportComponent – 4-krokowy wizard (upload drag&drop → mapowanie kolumn → progress bar polling 3s → raport), walidacja client-side 50MB, auto-mapowanie kolumn, integracja z campaign-list (przycisk dla DRAFT/SCHEDULED).

**Kryteria akceptacji:**
- [x] Plik > 50MB wyświetla błąd przed wysłaniem (walidacja client-side)
- [x] Mapowanie kolumn CSV działa dla plików z nagłówkiem i bez nagłówka
- [x] Pasek postępu odpytuje endpoint statusu co 3s i zamyka się po zakończeniu
- [x] Raport końcowy pokazuje liczbę sukcesów i błędów z przykładowymi wadliwymi rekordami

---

### FE-017 – Panel disposition codes po zakończeniu kontaktu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-009, BE-027
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-20
**Czeka na BE:** BE-027 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-08-04, US-08-05, EPIC-08

**Opis:**
Modal/panel po-kontaktowy wyświetlany automatycznie po zakończeniu połączenia. Dropdown z disposition codes (6 hardcoded kodów: Sprzedaż, Brak zainteresowania, Oddzwonienie, Błędny numer, Zgłoszenie techniczne, Inne; model w disposition.model.ts). Pole notatki (textarea, opcjonalne). Timer ACW w formacie MM:SS w panelu (czas pracy po-kontaktowej). Przycisk "Zapisz i zamknij" → PATCH /api/contacts/{id}/disposition. Integracja z contact-tab.store.ts (stan WRAPPING + markAsWrapping()). Effect() na session.state=ENDED w agent-desktop.component.ts automatycznie otwiera panel.

**Kryteria akceptacji:**
- [x] Panel otwiera się automatycznie po rozłączeniu połączenia
- [x] Disposition codes ładowane z disposition.model.ts (6 kodów hardcoded na MVP)
- [x] Zapis disposition wysyła PATCH /api/contacts/{id}/disposition
- [x] Timer wskazuje czas spędzony w stanie "po-kontaktowym" (wliczany do raportów)

---

## MODUL: Baza Klientów (EPIC-09)

### FE-018 – Wyszukiwanie i lista klientów (fuzzy search)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-005, BE-025
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-19
**Czeka na BE:** BE-025 ✅
**Blokuje:** FE-019, FE-020
**Odniesienie PRD:** US-09-01, US-09-03, EPIC-09

**Opis:**
Strona bazy klientów z globalnym polem wyszukiwania (debounce 300ms, min 2 znaki, fuzzy search przez backend). Wyniki w tabeli z paginacją (20 rekordów). Kolumny: imię, nazwisko, telefon, email, data ostatniego kontaktu. Akcje: edytuj, wyświetl profil, usuń (RODO – soft delete z potwierdzeniem i informacją o anonimizacji).

**Kryteria akceptacji:**
- [x] Wyszukiwanie inicjowane po 300ms debounce i min 2 znakach
- [x] Wyniki paginowane, link "Załaduj więcej" lub numeracja stron
- [x] Usunięcie klienta wymaga potwierdzenia z komunikatem RODO
- [x] Tabela ma nagłówki z sortowaniem (imię, nazwisko, data kontaktu)

---

### FE-019 – Profil klienta: widok szczegółowy i historia kontaktów

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** FE-018, BE-025, BE-027
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-21
**Czeka na BE:** BE-025 ✅, BE-027 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-09-02, US-09-03, US-09-04, EPIC-09

**Opis:**
Strona profilu klienta: sekcja danych podstawowych (imię, nazwisko, wielowartościowe pola telefon[] i email[]), sekcja custom_fields (dynamiczny formularz z pól JSONB), oś czasu historii kontaktów (kanał, agent, data, disposition, link do nagrania jeśli dostępny), status zgody RODO.

**Kryteria akceptacji:**
- [x] Formularz edycji obsługuje wielokrotne wartości dla telefon i email (add/remove chip)
- [x] Custom fields renderowane dynamicznie na podstawie schematu z API
- [x] Historia kontaktów paginowana (20 per strona), sortowalna po dacie
- [x] Link do nagrania otwiera odtwarzacz audio inline (jeśli nagranie dostępne i uprawnienia OK)
- [x] Badge "RODO: zgoda" lub "RODO: brak zgody" z datą ostatniej zgody

---

### FE-020 – Import klientów z CSV

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-018, BE-026
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-24
**Czeka na BE:** BE-026 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-09-05, EPIC-09

**Opis:**
Komponent importu bazy klientów z pliku CSV (analogiczny do FE-016 ale dedykowany dla modułu klientów). Mapowanie kolumn, walidacja po stronie klienta (max 50MB), async job z paskiem postępu. Deduplikacja po telefonie/emailu (opcja: pomij duplikat / nadpisz).

Zrealizowane: `customer-import.model.ts` (typy DeduplicationMode, ImportJobStatus, CustomerImportStatus), `customer-import.component.ts|html|scss` (4-krokowy wizard: upload drag&drop + deduplikacja radio, mapowanie kolumn z auto-mapowaniem, progress bar polling 3s, raport z pobieraniem błędów CSV). Zmodyfikowane: `customer.service.ts` – dodano importCsv(), getImportStatus(), downloadImportErrors(); `customer-list.component.ts|html` – przycisk "Importuj CSV"; `supervisor.routes.ts` – trasa `customers/import` PRZED `customers/:id`. ng build: SUKCES, 0 błędów.

**Kryteria akceptacji:**
- [x] Opcja deduplikacji widoczna w kroku mapowania kolumn
- [x] Raport importu zawiera liczbę: dodanych, zaktualizowanych, pominiętych, błędnych
- [x] Użytkownik może pobrać plik błędnych rekordów jako CSV

---

## MODUL: Raportowanie i Analityka (EPIC-10)

### FE-021 – Dashboard RT supervisora

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** FE-005, BE-029
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-22
**Czeka na BE:** BE-029 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-10-01, EPIC-10

**Opis:**
Dashboard real-time dla SUPERVISOR: karty KPI (aktywne połączenia, agenci online/przerwa/dostępni, śr. czas oczekiwania, śr. czas obsługi), tabela agentów z aktualnym statusem i aktualnym kontaktem, wykres kolejek (liczba oczekujących per kolejka). Dane aktualizowane przez WebSocket lub polling co 5s.

**Kryteria akceptacji:**
- [x] Dane odświeżane co max 5 sekund (wymaganie PRD ≤ 5s)
- [x] Tabela agentów podświetla agentów na przerwie > 10 min (konfigurowalny próg)
- [x] Wykresy kolejek aktualizowane animowane (bez migotania całego komponentu)
- [x] Dashboard ma tryb pełnoekranowy (F11 lub przycisk "fullscreen")

---

### FE-022 – Raporty historyczne: filtry, tabele, eksport

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** FE-005, BE-028
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-22
**Czeka na BE:** BE-028 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-10-02, US-10-03, US-10-05, EPIC-10

**Opis:**
Moduł raportów historycznych: filtry (zakres dat, agent, kolejka, kanał, kampania), tabela wynikowa z sortowaniem i paginacją. Raporty: wydajność agentów, statystyki kampanii outbound, ruch per kolejka. Eksport do CSV i Excel (XLSX) przez pobieranie pliku z API.

Zrealizowane: `report.model.ts` – interfejsy `AgentReportRow`, `AgentReportFilters`; `reports.service.ts` – `getAgentReport()`, `exportCsv()`, `exportXlsx()` (responseType: blob); `ReportsComponent` – filtry z URL sync, tabela z badge'ami kanałów (CALL/EMAIL/CHAT/SOCIAL), paginacja, eksport Blob, skeleton, empty state; `supervisor.routes.ts` – trasa `/reports` z `roleGuard`; build 0 błędów.

**Kryteria akceptacji:**
- [x] Filtry zachowywane w URL query params (sharable link)
- [x] Eksport CSV/XLSX inicjuje pobieranie pliku bez przeładowania strony (Blob URL)
- [x] Tabela z kolumną "Nagranie" ma link otwierający odtwarzacz (jeśli uprawnienia OK)
- [x] Brak danych dla wybranych filtrów pokazuje stan "brak wyników" z sugestią zmiany filtrów

---

## MODUL: Konfiguracja i Integracje (EPIC-01, EPIC-06)

### FE-023 – Panel konfiguracji integracji social media (OAuth flow)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-005, BE-017
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-17
**Czeka na BE:** BE-017 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-06-02, EPIC-06

**Opis:**
Widok konfiguracji integracji per tenant: lista platform (Facebook, Instagram, WhatsApp) ze statusem połączenia. Przycisk "Połącz" inicjuje OAuth 2.0 flow (redirect → callback → token zapis). Przycisk "Rozłącz" (z potwierdzeniem). Status webhook (aktywny/błąd).

**Zrealizowane:** SocialIntegrationsComponent (lista platform FACEBOOK/INSTAGRAM/WHATSAPP ze statusem, przycisk "Połącz" inicjujący OAuth redirect, `OAuthCallbackComponent` przechwytujący callback i zapisujący token, przycisk "Rozłącz" z modalem potwierdzenia, wyświetlanie statusu webhooka), SocialIntegrationService (initiateOAuth, disconnect, list), social-integration.model.ts, integrations.routes.ts.

**Kryteria akceptacji:**
- [x] OAuth flow działa przez redirect z powrotem do aplikacji (callback URL skonfigurowany)
- [x] Po udanym połączeniu status zmienia się na "Połączony" z datą autoryzacji
- [x] Błąd OAuth wyświetla czytelny komunikat (np. "Odmowa dostępu przez użytkownika")
- [x] Rozłączenie usuwa token z backendu i zmienia status na "Niepołączony"

---

### FE-024 – Panel konfiguracji kolejek i routingu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-005, BE-020
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-21
**Czeka na BE:** BE-020 ✅
**Blokuje:** brak
**Odniesienie PRD:** US-07-01, US-07-02, US-07-03, EPIC-07

**Opis:**
Formularz tworzenia/edycji kolejki: nazwa, strategia routingu (round-robin/first-available/skill-based), wymagane skills (multi-select), sticky agent timeout (input numeryczny, domyślnie 60s), adres email kolejki (opcjonalne pole `emailAddress` – walidacja formatu email, VARCHAR 255). Lista kolejek z liczbą agentów i aktualnym obciążeniem. Pole `emailAddress` w `queue.model.ts` jako `string | null`.

**Kryteria akceptacji:**
- [x] Zmiana strategii routingu na "skill-based" ujawnia sekcję wymaganych skills
- [x] Sticky agent timeout tylko liczba całkowita > 0 (walidacja)
- [x] Lista kolejek pokazuje aktualną liczbę oczekujących (polling co 10s)
- [x] Pole adresu email kolejki walidowane jako format email (Validators.email), opcjonalne

---

### FE-025 – Panel konfiguracji Twilio per tenant

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-005, BE-032
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-01
**Czeka na BE:** BE-032 ✅
**Blokuje:** brak
**Odniesienie PRD:** EPIC-03

**Opis:**
Sekcja „Telefonia VoIP" w panelu ustawień tenanta (dostępna dla ADMIN i SUPERVISOR). Formularz z dwoma polami: numer telefonu Twilio (walidacja E.164) i URL webhooka statusów (opcjonalny, generowany automatycznie gdy pusty). Przycisk „Zapisz" wywołuje `PATCH /api/tenants/{id}/config`. Status aktualnej konfiguracji wyświetlany inline (numer aktywny / brak konfiguracji – fallback globalny).

**Kryteria akceptacji:**
- [x] Pole numeru telefonu walidowane po stronie klienta (regex E.164: `^\+[1-9]\d{6,14}$`) przed wysłaniem
- [x] Przy pustym URL webhooka wyświetlany jest podgląd automatycznie generowanego URL (`baseUrl + ?tenantId=UUID`)
- [x] Po zapisie toast „Konfiguracja Twilio zaktualizowana"
- [x] Przy braku konfiguracji per-tenant widoczna informacja „Używany globalny numer fallback"
- [x] Formularz dostępny tylko dla roli ADMIN i SUPERVISOR (guard RoleGuard)

---

---

## MODUL: Routing numerów telefonicznych (EPIC-11)

### FE-026 – Panel zarządzania numerami telefonów i regułami routingu IVR (Supervisor)

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** L
**Zależy od:** FE-005, FE-014 (IVR editor), BE-033, BE-034
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-14
**Czeka na BE:** ~~BE-033~~ ✅, ~~BE-034~~ ✅
**Blokuje:** brak
**Odniesienie PRD:** EPIC-11

**Opis:**
Zastępuje usuniętą zakładkę „Twilio VoIP" w ustawieniach supervisora. Nowa strona `/supervisor/settings/phone-numbers` umożliwia zarządzanie numerami telefonów przypisanymi do tenanta oraz konfigurację reguł routingu: który IVR lub kolejka obsługuje połączenie przychodzące na dany numer w określonych dniach tygodnia i godzinach. System wizualnie sygnalizuje kolizje reguł.

**Zmiany do istniejących plików:**
- `sidenav.component.ts` – w sekcji „Konfiguracja": usunąć wpis „Twilio VoIP" (`/supervisor/settings/twilio`), dodać „Numery telefonów" (`/supervisor/settings/phone-numbers`)
- `supervisor.routes.ts` – usunąć route `settings/twilio`, dodać `settings/phone-numbers` (lazy)

**Nowe pliki:**
```
supervisor/services/phone-number.service.ts        – CRUD /api/phone-numbers
supervisor/pages/settings/
  phone-numbers/
    phone-numbers.component.ts / .html / .scss     – lista numerów + akcje
    routing-rules/
      routing-rules.component.ts / .html / .scss   – reguły dla wybranego numeru
      routing-rule-form/
        routing-rule-form.component.ts / .html / .scss  – modal dodawania/edycji reguły
```

**Szczegóły komponentów:**

`PhoneNumbersComponent` (strona główna `/supervisor/settings/phone-numbers`):
- Lista numerów tenanta (number, displayName, isActive, liczba reguł)
- Przycisk „Dodaj numer" → inline formularz lub modal (E.164, displayName)
- Akcje per wiersz: edycja displayName/is_active, usunięcie (z potwierdzeniem; 409 → toast „Usuń najpierw reguły")
- Kliknięcie w numer → rozwija/nawiguje do `RoutingRulesComponent` dla tego numeru
- Stan pusty: „Brak numerów – dodaj pierwszy numer Twilio"

`RoutingRulesComponent` (osadzony lub sub-route):
- Wizualizacja reguł jako lista kart: dni tygodnia (checkboxy tylko do odczytu), zakres godzin, target (IVR/kolejka z nazwą)
- Badge: brak reguł w jakimś przedziale → żółty „Połączenia poza harmonogramem będą odrzucane"
- Przycisk „Dodaj regułę" → otwiera `RoutingRuleFormComponent` w trybie tworzenia
- Akcje per regułę: edycja, usunięcie

`RoutingRuleFormComponent` (modal):
- Checkboxy dni tygodnia: Pon Wt Śr Czw Pt Sob Nie (min 1 wymagany)
- Time pickery: „Od" i „Do" (walidacja: Do > Od)
- Radio/select: „Target" → IVR (dropdown z listą drzew IVR tenanta) lub Kolejka (dropdown z listą kolejek)
- Walidacja kolizji: przy submit → HTTP 409 → wyróżnij kolidujące reguły w tle + toast z opisem
- Tryb edycji: pre-fill z istniejącej reguły

`PhoneNumberService`:
```ts
listPhoneNumbers(): Observable<PhoneNumber[]>
createPhoneNumber(req): Observable<PhoneNumber>
updatePhoneNumber(id, req): Observable<PhoneNumber>
deletePhoneNumber(id): Observable<void>
listRoutingRules(phoneNumberId): Observable<PhoneRoutingRule[]>
createRoutingRule(phoneNumberId, req): Observable<PhoneRoutingRule>
updateRoutingRule(phoneNumberId, ruleId, req): Observable<PhoneRoutingRule>
deleteRoutingRule(phoneNumberId, ruleId): Observable<void>
```

**Modele:**
```ts
interface PhoneNumber {
  phoneNumberId: string;
  number: string;           // E.164
  displayName?: string;
  isActive: boolean;
}

interface PhoneRoutingRule {
  ruleId: string;
  phoneNumberId: string;
  ivrTreeId?: string;
  queueId?: string;
  daysOfWeek: number[];     // 1=Pon, 7=Nie
  timeStart: string;        // "HH:mm"
  timeEnd: string;
  isActive: boolean;
}
```

**Kryteria akceptacji:**
- [x] Lista numerów tenanta: dodawanie (E.164 walidacja), edycja displayName, soft delete (409 blokuje gdy są reguły)
- [x] Reguły routingu: dodawanie, edycja, usunięcie per numer
- [x] Kolizja → HTTP 409 → wizualne wyróżnienie kolidujących reguł + toast
- [x] Brak reguł w pewnych godzinach → badge ostrzegawczy
- [x] IVR dropdown ładuje drzewa IVR z `/api/ivr-trees`; kolejka dropdown z `/api/queues`
- [x] Dostępne tylko dla roli SUPERVISOR i ADMIN (roleGuard)
- [x] Usunięto route `settings/twilio` i sidenav entry „Twilio VoIP" z supervisora; zastąpiono „Numery telefonów"

---

---

### FE-027 – Przycisk „Zadzwoń" dla dialera manualnego na liście rekordów kampanii

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-015, FE-016
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-08
**Czeka na BE:** brak (endpoint `POST /api/dialer/manual/call` zrealizowany)
**Blokuje:** brak
**Odniesienie PRD:** EPIC-08 (Kampanie wychodzące)

**Opis:**
Na liście rekordów kampanii manualnej (`dialer_type = MANUAL`) każdy rekord ze statusem `PENDING` powinien mieć przycisk „Zadzwoń". Kliknięcie inicjuje połączenie wychodzące przez endpoint `POST /api/dialer/manual/call` z `campaignId` i `recordId`. Przycisk niewidoczny / disabled dla kampanii progresywnych oraz rekordów w statusie innym niż `PENDING`.

Zrealizowane: `ManualCampaignPanelComponent` (polling co 30s, lista kampanii manualnych z rekordami PENDING, przycisk „Zadzwoń" z spinner i disabled guard), `DialerService` (getManualCampaignRecords → GET /api/dialer/manual/records, callRecord → POST /api/dialer/manual/call), integracja z `AgentDesktopComponent`. Obsługa błędów 409/404 przez toast. Przycisk disabled gdy agent nie jest AVAILABLE lub trwa inne połączenie.

**Kryteria akceptacji:**
- [x] Przycisk „Zadzwoń" widoczny tylko dla rekordów PENDING w kampanii z `dialerType = MANUAL`
- [x] Kliknięcie wywołuje `POST /api/dialer/manual/call { campaignId, recordId }`
- [x] Po sukcesie (200 OK) rekord zmienia status na DIALING w UI (optymistyczna aktualizacja lub odświeżenie listy)
- [x] Podczas trwania żądania przycisk pokazuje spinner i jest disabled (zapobiega podwójnemu kliknięciu)
- [x] Błąd 409 (np. rekord nie PENDING, kampania nie RUNNING) → toast z komunikatem z backendu
- [x] Błąd 404 (rekord/kampania nie istnieje) → toast „Rekord nie został znaleziony"
- [x] Przycisk niewidoczny dla kampanii `dialerType = PROGRESSIVE` (dialer automatyczny)
- [x] Dostępne tylko dla roli AGENT (agent inicjuje połączenie)

---

---

## MODUL: Prezentacja Kontaktów (EPIC-12)

### FE-028 – Komponent szczegółów kontaktu z odtwarzaczem nagrania (modal)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-005, BE-037 ✅
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-08
**Czeka na BE:** ~~BE-037~~ ✅ zrealizowane (`GET /api/contacts/{id}/recording` → `ContactRecordingUrlResponse`)
**Blokuje:** FE-029, FE-030
**Odniesienie PRD:** EPIC-12

**Opis:**
Reużywalny modal wyświetlający pełne szczegóły jednego kontaktu oraz — jeśli nagranie istnieje — wbudowany odtwarzacz audio. Modal jest wywoływany z dwóch miejsc: panelu klienta (`CustomerDetailComponent`, FE-030) oraz raportu kontaktów (`ContactsReportComponent`, FE-029). Dane kontaktu przekazywane przez input signal `contactId: string | null`.

**Nowe pliki:**
```
shared/components/contact-detail-modal/
  contact-detail-modal.component.ts / .html / .scss
shared/components/audio-player/
  audio-player.component.ts / .html / .scss
core/models/contact.model.ts  (rozszerzenie istniejącego)
```

**Szczegóły komponentu `ContactDetailModalComponent`:**
- Otwierany metodą `open(contactId: string)` lub `openWithData(contact: ContactResponse)`
- Nagłówek: kanał (ikona + label), kierunek (INBOUND / OUTBOUND), status (badge kolorowy)
- Sekcja „Szczegóły":
  - Data i czas rozpoczęcia (`startedAt`) — sformatowane jako lokalny czas
  - Czas trwania — `durationSeconds` skonwertowany na format `MM:SS` (funkcja `secondsToTime`)
  - Agent — nazwa agenta (lookup po `agentId` jeśli brak nazwy w danych) lub „—" jeśli brak
  - Kolejka — nazwa kolejki (lookup po `queueId`) lub „—"
  - Kampania — nazwa kampanii (lookup po `campaignId`) lub „—" dla kontaktów inbound
  - Dyspozycja (`dispositionCode`) lub „Brak dyspozycji"
  - Numer klienta (`remoteAddress`)
- Sekcja „Nagranie" (widoczna tylko gdy `recordingUrl != null`):
  - Wywołuje `GET /api/contacts/{id}/recording` → otrzymuje `presignedUrl`
  - Komponent `AudioPlayerComponent` z wbudowanym `<audio>` elementem
  - Przycisk „Pobierz" (`<a [href]="presignedUrl" download>`) otwierający pobieranie

**Szczegóły komponentu `AudioPlayerComponent`:**
- `@Input() src: string` — presigned URL do pliku audio
- `@Input() filename?: string` — nazwa pliku do pobrania
- Kontrolki: play/pause (ikona toggle), progress bar (`<input type="range">`), wyświetlony czas `aktualny / całkowity` w formacie `MM:SS`
- Obsługa zdarzeń HTML5 Audio API: `timeupdate`, `loadedmetadata`, `ended`, `error`
- Stan błędu: gdy audio nie może się załadować → komunikat „Nie można załadować nagrania"
- Standalone component, nie wymaga żadnych zewnętrznych bibliotek (czysty HTML5 Audio)

**Rozszerzenie `contact.model.ts`:**
Aktualny interfejs `ContactResponse` w `core/models/contact.model.ts` jest niekompletny (brakuje `queueId`, `campaignId`, `durationSeconds`, `recordingUrl`, `remoteAddress`, `assignedAt`, `queuedAt`, `channelMetadata`). Zaktualizować do pełnego mapowania z backendu.

**Kryteria akceptacji:**
- [x] Modal otwiera się z pełnymi danymi kontaktu (wszystkie pola wymienione w sekcji Szczegóły)
- [x] Sekcja nagrania widoczna tylko gdy `recordingUrl` nie jest null w danych kontaktu
- [x] Kliknięcie „Play" → `AudioPlayerComponent` pobiera presigned URL (`GET /api/contacts/{id}/recording`) i zaczyna odtwarzanie
- [x] Progress bar aktualizuje się w czasie rzeczywistym podczas odtwarzania; kliknięcie na progress bar przewija audio
- [x] Czas wyświetlany w formacie `MM:SS / MM:SS` (aktualny / całkowity)
- [x] Przycisk „Pobierz" otwiera pobieranie pliku przez link z `download` attribute
- [x] Błąd ładowania audio (np. presigned URL wygasł) → komunikat inline „Nie można załadować nagrania. Odśwież stronę."
- [x] `contact.model.ts` zaktualizowany o pola `queueId`, `campaignId`, `durationSeconds`, `recordingUrl`, `remoteAddress`, `assignedAt`, `queuedAt`, `channelMetadata`
- [x] Dostępność: przyciski play/pause mają `aria-label`, progress bar ma `aria-valuenow`/`aria-valuemax`

---

### FE-029 – Strona „Raporty > Kontakty" z tabelą, filtrami i eksportem CSV

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** FE-005, FE-022, FE-028 ✅, BE-036 ✅
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-14
**Czeka na BE:** ~~BE-036~~ ✅ zrealizowane (GET /api/contacts z filtrami queueId, campaignId, remoteAddress, durationMin/Max)
**Czeka na FE:** ~~FE-028~~ ✅ zrealizowane (ContactDetailModalComponent gotowy)
**Blokuje:** brak
**Odniesienie PRD:** EPIC-12

**Opis:**
Nowa zakładka „Kontakty" w sekcji Raporty supervisora. Dostępna pod ścieżką `/supervisor/reports/contacts`. Wyświetla paginowaną tabelę wszystkich kontaktów tenanta z rozbudowanym formularzem filtrowania. Kliknięcie w wiersz otwiera `ContactDetailModalComponent` (FE-028). Eksport widocznych danych do CSV.

**Zmiany do istniejących plików:**
- `supervisor.routes.ts` — zmienić route `reports` z `loadComponent: ReportsComponent` na children z podzakładkami: `reports/agents` (istniejący `ReportsComponent`) i `reports/contacts` (nowy `ContactsReportComponent`); `reports` → redirect do `reports/agents`
- `reports-placeholder.component.ts` / `.html` — dodać nawigację między zakładkami (linki lub tabs: „Agenci" / „Kontakty")
- `sidenav.component.ts` — zaktualizować link „Raporty" żeby wskazywał na `/supervisor/reports` (bez zmian w anchor, redirect zadziała)

**Nowe pliki:**
```
supervisor/pages/reports/contacts/
  contacts-report.component.ts / .html / .scss
supervisor/services/contact-report.service.ts
```

**Szczegóły `ContactsReportComponent`:**

Formularz filtrów (wszystkie opcjonalne):
- `dateFrom` / `dateTo` — date picker, domyślnie ostatnie 7 dni; walidacja: dateTo >= dateFrom, max 90 dni
- `agentId` — dropdown z listą agentów tenanta (pobierana z `UserService.getUsers({role:'AGENT'})`)
- `queueId` — dropdown z listą kolejek tenanta (pobierana z `QueueService.listQueues()`)
- `campaignId` — dropdown z listą kampanii tenanta (pobierana z `CampaignService.listCampaigns()`)
- `status` — select: wszystkie / QUEUED / ACTIVE / COMPLETED / ABANDONED
- `remoteAddress` — input tekstowy (numer telefonu, prefix search)
- `durationMin` / `durationMax` — pola numeryczne (sekundy), opcjonalne
- Przyciski: „Szukaj", „Wyczyść filtry"

Tabela kontaktów (kolumny):
- Data (`startedAt` sformatowana `YYYY-MM-DD HH:mm`)
- Kanał (ikona + skrót: tel/email/social)
- Kierunek (IN / OUT)
- Klient (`remoteAddress`)
- Agent (imię i nazwisko — z pomocniczej mapy `agentId → name` pobranej przy inicjalizacji)
- Kolejka (nazwa — z pomocniczej mapy `queueId → name`)
- Status (badge)
- Czas trwania (`durationSeconds` jako `MM:SS`)
- Dyspozycja
- Kliknięcie w wiersz → `ContactDetailModalComponent.open(contactId)`

Paginacja: serwer-side, rozmiar strony 20, przyciski Poprzednia/Następna + info „X-Y z N".

Eksport CSV:
- Przycisk „Eksport CSV" — wywołuje `GET /api/contacts` z bieżącymi filtrami i `size=10000` (max eksport)
- Mapuje wynik na wiersze CSV (separator `;`) i pobiera plik przez `URL.createObjectURL`
- Kolumny CSV: data, kanał, kierunek, telefon_klienta, agent_id, kolejka_id, status, czas_trwania_s, dyspozycja

**`ContactReportService`:**
```ts
interface ContactFilters {
  dateFrom?: string;         // ISO 8601
  dateTo?: string;
  agentId?: string;
  queueId?: string;
  campaignId?: string;
  status?: string;
  remoteAddress?: string;
  durationMin?: number;
  durationMax?: number;
}

listContacts(filters: ContactFilters, page: number, size: number): Observable<PagedResponse<ContactResponse>>
exportCsv(filters: ContactFilters): Blob  // synchroniczny — dane pobrane przez listContacts z size=10000
```

**Kryteria akceptacji:**
- [x] Strona dostępna pod `/supervisor/reports/contacts` (roleGuard: SUPERVISOR, ADMIN)
- [x] Nawigacja między zakładkami „Agenci" i „Kontakty" w widoku Raportów (zakładki lub linki)
- [x] Tabela wyświetla paginowaną listę kontaktów tenanta posortowaną od najnowszych
- [x] Każdy z 7 filtrów (data od-do, agent, kolejka, kampania, status, telefon, czas trwania) działa samodzielnie i w kombinacji
- [x] Walidacja zakresu dat: `dateTo >= dateFrom` i max 90 dni → komunikat inline (nie toast)
- [x] Paginacja server-side: zmiana strony nie resetuje filtrów; parametry filtrów zachowane w URL (queryParams)
- [x] Kliknięcie w wiersz otwiera `ContactDetailModalComponent` z danymi kontaktu
- [x] Przycisk „Eksport CSV" pobiera plik z aktualnie zastosowanymi filtrami
- [x] Stan pusty (brak wyników) → komunikat „Brak kontaktów spełniających kryteria"
- [x] Skeleton loader podczas ładowania danych
- [x] Dostępne tylko dla SUPERVISOR i ADMIN (roleGuard)

---

### FE-030 – Integracja szczegółów kontaktu w panelu klienta (CustomerDetailComponent)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-019, FE-028 ✅
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-08 (zrealizowane przy okazji FE-028)
**Czeka na BE:** ~~BE-037~~ ✅ zrealizowane, ~~BE-027~~ ✅ (contact API gotowe)
**Blokuje:** brak
**Odniesienie PRD:** EPIC-12

**Opis:**
`CustomerDetailComponent` już wyświetla listę kontaktów klienta (paginacja działa), ale kliknięcie w wiersz kontaktu nie robi nic. Zadanie polega na dodaniu interakcji: kliknięcie w wiersz historii kontaktów otwiera `ContactDetailModalComponent` (FE-028) z pełnymi szczegółami i odtwarzaczem nagrania.

**Zmiany do istniejących plików:**
- `customer-detail.component.ts` — dodać `ContactDetailModalComponent` do `imports[]`; dodać metodę `onContactRowClick(contact: ContactResponse)` wywołującą modal
- `customer-detail.component.html` — na wierszach tabeli kontaktów dodać `(click)="onContactRowClick(contact)"` i styl `cursor: pointer`; dodać `<app-contact-detail-modal>` w szablonie
- `core/models/contact.model.ts` — zaktualizowany w FE-028 (zależność)

**Kryteria akceptacji:**
- [x] Kliknięcie w wiersz historii kontaktów klienta otwiera modal ze szczegółami kontaktu
- [x] Modal wyświetla wszystkie pola: czas trwania, status, agent, kolejka, kampania, dyspozycja, daty
- [x] Jeśli kontakt ma nagranie (`recordingUrl != null`) — widoczna sekcja nagrania z odtwarzaczem
- [x] Jeśli kontakt nie ma nagrania — sekcja nagrania ukryta (nie wyświetla się)
- [x] Wiersze kontaktów mają wizualny sygnał klikalności (`cursor: pointer`, hover state)
- [x] Zamknięcie modala nie resetuje paginacji listy kontaktów klienta

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
| Twilio config | FE-025 (po BE-032) |
| Routing telefoniczny | FE-026 (po BE-033, BE-034) |
| Prezentacja kontaktów | FE-028 (modal + audio player), FE-029 (raport), FE-030 (panel klienta) |

### Blokery od Backendu (FE czeka na BE)

| Zadanie FE | Czeka na zadanie BE |
|------------|---------------------|
| FE-004 | BE-004 (auth endpoints) |
| FE-006 | BE-006 (tenant CRUD API) |
| FE-007 | BE-007 (admin metrics API) |
| FE-008 | BE-008 (users/agents API) |
| FE-010 | BE-009 ✅, BE-012 ✅ (WebRTC/SIP signaling – zrealizowane) |
| FE-012 | BE-015 (email API) |
| FE-013 | BE-018 (social media API) |
| FE-014 | BE-020 ✅ (Queue API – gotowe), BE-013 ✅ (IVR Engine – zrealizowane) |
| FE-015, FE-016 | BE-022 ✅ (campaign API – zrealizowane) |
| FE-018, FE-019 | BE-025 ✅, BE-027 ✅ (customer API + contact API – gotowe) |
| FE-021 | BE-029 ✅ (RT metrics WebSocket – zrealizowane) |
| FE-022 | BE-028 ✅ (reports API – zrealizowane) |
| FE-025 | BE-032 ✅ (Twilio per-tenant config – zrealizowane) |
| FE-026 | BE-033 (PhoneNumber API), BE-034 (RoutingRule API) |
| FE-027 | brak (`POST /api/dialer/manual/call` gotowe) |
| FE-028 | BE-037 ✅ (recording presigned URL – zrealizowane) |
| FE-029 | ~~BE-036~~ ✅ zrealizowane (Contact API filtry zaawansowane); czeka jeszcze na FE-028 (modal) |
| FE-030 | BE-037 ✅ (nagranie – zrealizowane), FE-028 (modal komponent – do zrobienia) |
| FE-031 | BE-039 ✅ (reschedule callback API) |
| FE-032 | BE-040 ✅ (inbound callback API) |

> Do czasu gotowości backendu zadania FE mogą używać MSW (Mock Service Worker) do mockowania odpowiedzi API zgodnie z kontraktem OpenAPI.

---

## MODUL: Zaplanowane oddzwonienia (EPIC-13)

### FE-031 – Modal przełożenia rozmowy wychodzącej (Agent Desktop)

**Typ:** Feature – UI Component
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-009 (Agent Desktop), BE-039 (PUT /api/dialer/callbacks/{id})
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Blokuje:** FE-034 (RescheduleCallbackModalComponent – reużycie)
**Epic:** EPIC-13 Zaplanowane oddzwonienia

**Opis:**
Agent podczas rozmowy wychodzącej (kampania lub manualny dialer) lub po jej zakończeniu może przełożyć planowane oddzwonienie na inną godzinę. UI wyświetla przycisk "Przesuń oddzwonienie" dostępny gdy agent ma aktywny callback PENDING.

**Komponenty do stworzenia:**

1. **`RescheduleCallbackModalComponent`** (`features/agent-desktop/components/reschedule-callback-modal/`):
   - Standalone component, selector: `app-reschedule-callback-modal`
   - Input: `callbackId: string`, `currentScheduledAt: Date`
   - Output: `rescheduled: EventEmitter<ScheduledCallbackDto>`, `cancelled: EventEmitter<void>`
   - Formularz z polami:
     - `scheduledAt` – date-time picker (Angular Material `<mat-datetime-picker>` lub PrimeNG `<p-calendar>`) z walidacją: wartość w przyszłości, wymagana
     - `notes` – textarea opcjonalna (max 500 znaków)
   - Submit wywołuje `DialerService.rescheduleCallback(callbackId, { scheduledAt, notes })`
   - Po sukcesie: emituje `rescheduled`, pokazuje toast "Oddzwonienie przełożone na [data]"
   - Loading state podczas wysyłki (disabled submit + spinner)
   - Error handling: wyświetla komunikat błędu API przy 409/403/404

2. **`DialerService.rescheduleCallback(callbackId: string, req: RescheduleCallbackRequest): Observable<ScheduledCallbackDto>`**:
   - `PUT /api/dialer/callbacks/{callbackId}`
   - Metoda do dodania do istniejącego `DialerService`

3. **Integracja w Agent Desktop:**
   - Przycisk "Przesuń oddzwonienie" widoczny gdy aktywna karta (tab) agenta pokazuje callback PENDING
   - Kliknięcie otwiera `RescheduleCallbackModalComponent` w overlay/dialog
   - Po zamknięciu modalu: odśwież listę callbacków

**Typy** (`features/agent-desktop/models/callback.model.ts`):
```typescript
export interface RescheduleCallbackRequest {
  scheduledAt: string; // ISO 8601
  notes?: string;
}

export interface ScheduledCallbackDto {
  callbackId: string;
  phone: string;
  firstName?: string;
  lastName?: string;
  scheduledAt: string;
  notes?: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'CANCELLED';
  sourceType: 'CAMPAIGN_CALLBACK' | 'INBOUND_CALLBACK';
  agentId?: string;
}
```

**Kryteria akceptacji:**
- [x] Modal otwiera się po kliknięciu przycisku "Przesuń oddzwonienie"
- [x] Pole daty nie pozwala wybrać czasu w przeszłości (walidacja min date)
- [x] Submit jest zablokowany gdy formularz niepoprawny
- [x] Po sukcesie: modal zamknięty, toast z nową datą, lista callbacków odświeżona
- [x] Błąd API 409 → komunikat "Oddzwonienie nie jest już oczekujące"
- [x] Błąd API 403 → komunikat "Brak uprawnień do zmiany tego oddzwonienia"
- [x] Loading spinner podczas wysyłki (brak podwójnego submitu)

---

### FE-032 – Modal dodania oddzwonienia podczas rozmowy przychodzącej

**Typ:** Feature – UI Component
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-009 (Agent Desktop), BE-040 (POST /api/contacts/{contactId}/callback)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Blokuje:** brak
**Epic:** EPIC-13 Zaplanowane oddzwonienia

**Opis:**
Podczas aktywnej rozmowy przychodzącej agent może zaplanować oddzwonienie do klienta. Opcja dostępna w panelu aktywnego połączenia – przycisk "Zaplanuj oddzwonienie" otwiera modal z formularzem.

**Komponenty do stworzenia:**

1. **`ScheduleInboundCallbackModalComponent`** (`features/agent-desktop/components/schedule-inbound-callback-modal/`):
   - Standalone component, selector: `app-schedule-inbound-callback-modal`
   - Input: `contactId: string`, `prefillPhone?: string`, `prefillFirstName?: string`, `prefillLastName?: string`
   - Output: `scheduled: EventEmitter<ScheduledCallbackDto>`, `cancelled: EventEmitter<void>`
   - Formularz z polami:
     - `phone` – wstępnie uzupełniony z danych kontaktu, edytowalny, walidacja E.164 pattern
     - `firstName` – opcjonalne, wstępnie uzupełnione z danych klienta
     - `lastName` – opcjonalne
     - `scheduledAt` – date-time picker z walidacją: wartość w przyszłości, wymagana
     - `notes` – textarea opcjonalna (max 500 znaków)
   - Submit wywołuje `DialerService.createInboundCallback(contactId, request)`
   - Po sukcesie: emituje `scheduled`, toast "Oddzwonienie zaplanowane na [data]"
   - Loading state + disable submit podczas wysyłki

2. **`DialerService.createInboundCallback(contactId: string, req: CreateInboundCallbackRequest): Observable<ScheduledCallbackDto>`**:
   - `POST /api/contacts/{contactId}/callback`
   - Metoda do dodania do istniejącego `DialerService`

3. **Integracja w Agent Desktop:**
   - Przycisk "Zaplanuj oddzwonienie" widoczny w panelu aktywnego połączenia przychodzącej rozmowy
   - Dane kontaktu (telefon, imię, nazwisko) pre-fill z aktywnej sesji rozmowy
   - Po zaplanowaniu: badge/wskaźnik w UI informujący że oddzwonienie zostało zaplanowane

**Typy** (dodać do `callback.model.ts`):
```typescript
export interface CreateInboundCallbackRequest {
  phone: string;
  firstName?: string;
  lastName?: string;
  scheduledAt: string; // ISO 8601
  notes?: string;
}
```

**Kryteria akceptacji:**
- [x] Przycisk "Zaplanuj oddzwonienie" widoczny tylko podczas aktywnej rozmowy przychodzącej
- [x] Pola phone/imię/nazwisko pre-uzupełnione danymi z aktywnego kontaktu
- [x] Pole phone jest edytowalne (agent może zmienić numer)
- [x] Pole daty nie pozwala wybrać czasu w przeszłości
- [x] Po sukcesie: toast z potwierdzenem, modal zamknięty
- [x] Błąd 404 (kontakt nie istnieje) → komunikat błędu
- [x] Błąd 403 → "Brak uprawnień"
- [x] Formularz zablokowany podczas wysyłki (brak podwójnego submitu)
- [x] Walidacja phone: format E.164 lub akceptowalny lokalny format

---

## MODUL: RODO / GDPR (EPIC-09, przekrojowe)

### FE-033 – Panel RODO w profilu klienta: eksport danych i anonimizacja

**Typ:** Feature – UI Component
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** FE-019 (CustomerDetailComponent), BE-031
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Czeka na BE:** BE-031
**Blokuje:** brak
**Odniesienie PRD:** US-09-06, wymagania RODO Art. 15, Art. 17

**Opis:**
Rozszerzenie istniejącego `CustomerDetailComponent` (FE-019) o sekcję RODO z dwoma akcjami:
- **Eksport danych (Art. 15):** pobiera plik ZIP z danymi klienta
- **Anonimizacja (Art. 17):** anonimizuje wszystkie PII klienta z potwierdzeniem modal

Akcje widoczne tylko dla ról SUPERVISOR i ADMIN.

**Komponenty do stworzenia/modyfikacji:**

1. **Rozszerzenie `CustomerDetailComponent`** (`features/customers/customer-detail/`):
   - Sekcja „Prawa RODO" na dole strony profilu (po historii kontaktów)
   - Przycisk „Eksportuj dane (Art. 15)" – wywołuje `GdprService.exportData(customerId)`, pobiera ZIP przez `URL.createObjectURL`
   - Przycisk „Anonimizuj klienta (Art. 17)" – widoczny tylko dla ADMIN/SUPERVISOR, otwiera `GdprAnonymizeModalComponent`
   - Oba przyciski ukryte gdy klient ma już `is_deleted=true` (wyświetlić zamiast tego badge "Dane zanonimizowane")

2. **`GdprAnonymizeModalComponent`** (`features/customers/gdpr-anonymize-modal/`):
   - Standalone component, selector: `app-gdpr-anonymize-modal`
   - Input: `customerId: string`, `customerName: string`
   - Output: `confirmed: EventEmitter<void>`, `cancelled: EventEmitter<void>`
   - Treść: ostrzeżenie "Ta operacja jest nieodwracalna. Wszystkie dane osobowe klienta [name] zostaną trwale zanonimizowane."
   - Wymaga wpisania słowa "ANONIMIZUJ" w pole tekstowe (potwierdzenie)
   - Submit wywołuje `GdprService.anonymize(customerId)` → po sukcesie: toast + przekierowanie na listę klientów

3. **`GdprService`** (`features/customers/services/gdpr.service.ts`):
   - `exportData(customerId: string): Observable<Blob>` → `POST /api/customers/{id}/gdpr/export`, responseType: 'blob'
   - `anonymize(customerId: string): Observable<void>` → `POST /api/customers/{id}/gdpr/anonymize`

**Kryteria akceptacji:**
- [x] Przycisk eksportu pobiera plik ZIP o nazwie `gdpr_export_{id}.zip`
- [x] Przycisk anonimizacji widoczny tylko dla ról SUPERVISOR i ADMIN (RoleGuard / `*ngIf`)
- [x] Modal anonimizacji wymaga wpisania "ANONIMIZUJ" przed zatwierdzeniem
- [x] Po anonimizacji profil klienta pokazuje badge "Dane zanonimizowane" zamiast danych PII
- [x] Loading state na obu przyciskach podczas operacji
- [x] Błąd 403 → toast "Brak uprawnień"
- [x] Błąd 404 → toast "Klient nie istnieje"
- [x] Sekcja RODO ukryta gdy klient już zanonimizowany (`is_deleted=true`)

---

### FE-034 – Panel Agenta: lista własnych callbacków z edycją i usunięciem

**Typ:** Feature – UI Component
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-041, BE-042, FE-031 (RescheduleCallbackModalComponent – reużycie)
**Status:** ✅ Ukończone
**Ukończono:** 2026-04-15
**Czeka na BE:** ~~BE-041~~ ✅, ~~BE-042~~ ✅ (backend gotowy)
**Blokuje:** FE-035
**Odniesienie PRD:** EPIC-13 Zaplanowane oddzwonienia

**Opis:**
Nowa strona w panelu agenta wyświetlająca listę jego własnych zaplanowanych oddzwonień. Agent widzi wyłącznie swoje callbacki (izolacja po `agentId` realizowana po stronie backendu – BE-041). Strona dostępna z nawigacji Agent Desktop jako osobna trasa.

**Nowy komponent strony:**

`features/agent/pages/callbacks/agent-callbacks-page.component.ts`
- Selektor: `app-agent-callbacks-page`
- Standalone component, `ChangeDetectionStrategy.OnPush`
- Ładuje dane przez `CallbackService` przy inicjalizacji (`ngOnInit` → `loadCallbacks()`)

**Nowy serwis `CallbackService`:**

`features/agent/services/callback.service.ts`

```typescript
@Injectable({ providedIn: 'root' })
export class CallbackService {
  listCallbacks(params: CallbackListParams): Observable<PagedResponse<CallbackListItem>>;
  updateCallback(callbackId: string, req: UpdateCallbackRequest): Observable<CallbackListItem>;
  cancelCallback(callbackId: string): Observable<void>;
}
```

gdzie `CallbackListParams`:
```typescript
interface CallbackListParams {
  status?: 'PENDING' | 'COMPLETED' | 'CANCELLED' | 'PROCESSING' | null;
  sortDir?: 'ASC' | 'DESC';
  page?: number;
  size?: number;
}
```

i `CallbackListItem` odpowiada `CallbackListItemResponse` z backendu (dodaj do `features/agent/models/callback.model.ts`).

**Widok strony — elementy UI:**

1. **Pasek filtrów** (u góry):
   - Select "Status": opcje All / Pending / Completed / Cancelled (domyślnie: All)
   - Select "Sortowanie": Najwcześniejsze / Najpóźniejsze (domyślnie: Najwcześniejsze)
   - Zmiana filtra → odświeżenie listy (page=0, zachowaj size)

2. **Tabela callbacków** (kolumny):
   - Numer telefonu
   - Imię i nazwisko klienta (firstName + lastName, fallback: "—")
   - Data i godzina oddzwonienia (`scheduledAt` formatowane `dd.MM.yyyy HH:mm`)
   - Notatka (skrócona do 60 znaków, tooltip z pełną treścią)
   - Status (chip/badge kolorowany: PENDING=niebieski, COMPLETED=zielony, CANCELLED=szary, PROCESSING=pomarańczowy)
   - Kolumna Akcje: ikony edytuj / usuń (disabled gdy status != PENDING)

3. **Paginacja** pod tabelą: page size selector (10/20/50), numery stron

4. **Stan pusty**: komunikat "Nie masz żadnych zaplanowanych oddzwonień" gdy lista pusta

5. **Akcja Edytuj**: otwiera istniejący `RescheduleCallbackModalComponent` (FE-031) z prefillowanymi danymi; po zapisaniu odświeża listę. Uwaga: istniejący modal obsługuje tylko reschedule (data + notatka) — wystarczy dla roli AGENT

6. **Akcja Usuń**: otwiera `ConfirmDeleteCallbackModalComponent` (nowy, inline lub dedykowany) z pytaniem "Czy na pewno chcesz anulować to oddzwonienie?" → po potwierdzeniu wywołuje `CallbackService.cancelCallback(callbackId)` → toast sukcesu + odświeżenie listy

**Routing:**

Dodaj trasę `/agent/callbacks` w `agent.routes.ts`:
```typescript
{
  path: 'callbacks',
  component: AgentCallbacksPageComponent,
  canActivate: [AuthGuard, RoleGuard],
  data: { roles: ['AGENT'] }
}
```

**Zarządzanie stanem (signals):**

```typescript
callbacks = signal<CallbackListItem[]>([]);
total = signal<number>(0);
loading = signal<boolean>(false);
selectedStatus = signal<string | null>(null);
sortDir = signal<'ASC' | 'DESC'>('ASC');
page = signal<number>(0);
pageSize = signal<number>(20);
```

**Kryteria akceptacji:**
- [x] Strona ładuje się pod `/agent/callbacks` i wyświetla wyłącznie callbacki zalogowanego agenta
- [x] Filtr statusu "Pending" → lista zawiera wyłącznie rekordy PENDING
- [x] Filtr statusu "All" → lista zawiera rekordy wszystkich statusów
- [x] Sortowanie "Najpóźniejsze" → lista posortowana `scheduledAt DESC`
- [x] Akcja "Edytuj" (przycisk aktywny tylko dla PENDING) otwiera modal z prefillowaną datą i notatką
- [x] Po zapisaniu w modalu edycji lista odświeża się bez przeładowania strony
- [x] Akcja "Usuń" dla callbacku PENDING otwiera dialog potwierdzenia
- [x] Po potwierdzeniu usunięcia: callback znika z listy (lub zmienia status na CANCELLED przy sortDir=All), toast "Oddzwonienie anulowane"
- [x] Przyciski edytuj/usuń są zablokowane (disabled) dla callbacków w statusie COMPLETED, CANCELLED, PROCESSING
- [x] Paginacja działa poprawnie: zmiana strony odświeża listę
- [x] Stan pusty: widoczny komunikat gdy brak callbacków
- [x] Loading spinner widoczny podczas ładowania danych
- [x] Błąd sieciowy → toast z komunikatem błędu

---

### FE-035 – Panel Supervisora: lista wszystkich callbacków z reassign agenta

**Typ:** Feature – UI Component
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-041, BE-042, FE-034 (CallbackService – reużycie)
**Status:** ✅ Ukończone
**Ukończono:** 2026-04-15
**Czeka na BE:** ~~BE-041~~ ✅, ~~BE-042~~ ✅ (backend gotowy)
**Blokuje:** brak
**Odniesienie PRD:** EPIC-13 Zaplanowane oddzwonienia

**Opis:**
Nowa strona w panelu supervisora wyświetlająca wszystkie callbacki tenanta. Rozszerza możliwości FE-034 o widok wieloagentowy, filtr po agencie i opcję reassign. Supervisor może edytować każdy callback i przepisać go do innego agenta.

**Nowy komponent strony:**

`features/supervisor/pages/callbacks/supervisor-callbacks-page.component.ts`
- Selektor: `app-supervisor-callbacks-page`
- Standalone component, `ChangeDetectionStrategy.OnPush`
- Reużywa `CallbackService` z FE-034 (ten sam serwis, inne parametry HTTP)

**Rozszerzenie `CallbackService`:**

```typescript
// Dodaj do istniejącego serwisu:
updateCallbackFull(callbackId: string, req: UpdateCallbackRequest): Observable<CallbackListItem>;
// PATCH /api/dialer/callbacks/{callbackId}
// UpdateCallbackRequest: { phone?, firstName?, lastName?, scheduledAt?, notes?, agentId? }
```

gdzie `UpdateCallbackRequest` dodaj do `features/agent/models/callback.model.ts`.

**Widok strony — elementy UI:**

1. **Pasek filtrów** (u góry):
   - Select "Status": All / Pending / Completed / Cancelled
   - Select "Agent": lista wszystkich agentów tenanta załadowana z `GET /api/users?role=AGENT` (reużyj istniejącego serwisu użytkowników); opcja "Wszyscy agenci" (domyślna)
   - Select "Sortowanie": Najwcześniejsze / Najpóźniejsze
   - Zmiana filtra → odświeżenie listy (page=0)

2. **Tabela callbacków** (kolumny — identyczne jak FE-034 plus dodatkowa):
   - Numer telefonu
   - Imię i nazwisko klienta
   - Data i godzina oddzwonienia
   - Notatka (skrócona, tooltip)
   - **Agent** (imię i nazwisko z pola `agentName`; "—" gdy brak agenta)
   - Status (chip/badge)
   - Kolumna Akcje: ikona edytuj / usuń

3. **Paginacja** identyczna jak FE-034

4. **Modal edycji — `EditCallbackModalComponent`:**

   Nowy komponent: `features/supervisor/components/edit-callback-modal/edit-callback-modal.component.ts`
   - Selektor: `app-edit-callback-modal`
   - Standalone, `ChangeDetectionStrategy.OnPush`
   - Input signals: `callback: CallbackListItem` (dane do prefill), `agents: AgentOption[]` (lista agentów do reassign)
   - Output: `saved: EventEmitter<CallbackListItem>`, `cancelled: EventEmitter<void>`
   - Formularz reaktywny z polami:
     - Numer telefonu (wymagane, walidacja E.164: `+[cyfry]`, min 7 max 15 cyfr po `+`)
     - Imię (opcjonalne)
     - Nazwisko (opcjonalne)
     - Data i godzina oddzwonienia (datetime-local input, wymagane, min=teraz)
     - Notatka (textarea, opcjonalna, max 500 znaków)
     - Agent (select z listy agentów; opcja "Brak przypisania" ustawia `agentId=null`)
   - Submit wywołuje `CallbackService.updateCallbackFull(callbackId, req)`
   - Po sukcesie: emituje `saved` z zaktualizowanym callbackiem

5. **Akcja Usuń**: identyczna logika jak FE-034 (dialog potwierdzenia + `CallbackService.cancelCallback`)

**Routing:**

Dodaj trasę `/supervisor/callbacks` w `supervisor.routes.ts`:
```typescript
{
  path: 'callbacks',
  component: SupervisorCallbacksPageComponent,
  canActivate: [AuthGuard, RoleGuard],
  data: { roles: ['SUPERVISOR', 'ADMIN'] }
}
```

**Zarządzanie stanem (signals):**

```typescript
callbacks = signal<CallbackListItem[]>([]);
total = signal<number>(0);
loading = signal<boolean>(false);
agents = signal<AgentOption[]>([]);
selectedStatus = signal<string | null>(null);
selectedAgentId = signal<string | null>(null);
sortDir = signal<'ASC' | 'DESC'>('ASC');
page = signal<number>(0);
pageSize = signal<number>(20);
editingCallback = signal<CallbackListItem | null>(null);
```

**Kryteria akceptacji:**
- [x] Strona ładuje się pod `/supervisor/callbacks` i wyświetla callbacki wszystkich agentów tenanta
- [x] Kolumna "Agent" wyświetla imię i nazwisko agenta (z pola `agentName` zwróconego przez BE-041)
- [x] Filtr "Agent" zawęża listę do callbacków wybranego agenta
- [x] Filtr "Status" działa analogicznie jak w FE-034
- [x] Akcja "Edytuj" otwiera `EditCallbackModalComponent` z prefillowanymi danymi
- [x] W modalu edycji: select "Agent" zawiera listę wszystkich agentów tenanta
- [x] Po zmianie agenta w modalu i zapisaniu: kolumna "Agent" w wierszu aktualizuje się na nową wartość (bez przeładowania całej listy)
- [x] Akcja "Usuń" działa identycznie jak w FE-034 (dialog potwierdzenia, toast, odświeżenie)
- [x] Supervisor może usunąć callback dowolnego agenta
- [x] Strona niedostępna dla roli AGENT (RoleGuard przekierowuje)
- [x] Loading spinner podczas ładowania danych i podczas operacji save/delete
- [x] Błąd sieciowy → toast z komunikatem błędu

---

## MODUL: Zarządzanie przypisaniem agentów do kolejek (EPIC-14)

### FE-036 – Serwis `AgentGroupService` i typy DTO dla grup agentów

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Zależy od:** BE-044
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** FE-037, FE-038

**Opis:**
Warstwa serwisowa Angular do komunikacji z API grup agentów. Serwis wstrzykiwany jako `providedIn: 'root'`.

Plik: `frontend/src/app/core/services/agent-group.service.ts`

**Typy** (plik: `frontend/src/app/core/models/agent-group.model.ts`):

```typescript
export interface AgentGroup {
  groupId: string;
  name: string;
  memberCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AgentGroupMember {
  agentId: string;
  firstName: string;
  lastName: string;
  email: string;
}

export interface AgentGroupMembers {
  groupId: string;
  groupName: string;
  members: AgentGroupMember[];
}

export interface AgentGroupSummary {
  groupId: string;
  name: string;
  memberCount: number;
}

export interface QueueAssignment {
  queueId: string;
  allAgents: boolean;
  directAgents: AgentGroupMember[];
  groups: AgentGroupSummary[];
}
```

**Metody serwisu** (wszystkie zwracają `Observable`):
- `listGroups(params: { name?: string; page?: number; size?: number }): Observable<PagedResponse<AgentGroup>>`
- `createGroup(name: string): Observable<AgentGroup>`
- `updateGroup(groupId: string, name: string): Observable<AgentGroup>`
- `deleteGroup(groupId: string): Observable<void>`
- `getGroupMembers(groupId: string): Observable<AgentGroupMembers>`
- `replaceGroupMembers(groupId: string, agentIds: string[]): Observable<AgentGroupMembers>`
- `getQueueAssignment(queueId: string): Observable<QueueAssignment>`
- `updateQueueAssignment(queueId: string, req: UpdateQueueAssignmentRequest): Observable<QueueAssignment>`

**Kryteria akceptacji:**
- [x] Wszystkie metody wywołują poprawne endpointy HTTP (metoda + ścieżka zgodna z BE-044 i BE-046)
- [x] Błędy HTTP propagowane jako Observable error (nie swallowane)
- [x] Serwis dostępny przez DI we wszystkich komponentach feature

---

### FE-037 – Panel zarządzania grupami agentów (`AgentGroupsPageComponent`)

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** L
**Zależy od:** FE-036
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** FE-038

**Opis:**
Nowa strona w panelu supervisora: lista grup agentów z możliwością tworzenia, edycji, usuwania i zarządzania składem grupy.

**Komponenty:**

1. `AgentGroupsPageComponent` (`features/supervisor/agent-groups/agent-groups-page/`)
   - Selektor: `app-agent-groups-page`
   - Standalone, `ChangeDetectionStrategy.OnPush`
   - Signals: `groups = signal<AgentGroup[]>([])`, `total = signal<number>(0)`, `loading = signal<boolean>(false)`, `editingGroup = signal<AgentGroup | null>(null)`, `managingMembersGroup = signal<AgentGroup | null>(null)`
   - Tabela z kolumnami: Nazwa, Liczba agentów, Akcje (Edytuj nazwę / Zarządzaj agentami / Usuń)
   - Przycisk "Utwórz grupę" otwiera `CreateEditGroupModalComponent`

2. `CreateEditGroupModalComponent` (`features/supervisor/agent-groups/create-edit-group-modal/`)
   - Selektor: `app-create-edit-group-modal`
   - Input signal: `group: AgentGroup | null` (null = tryb tworzenia)
   - Output: `saved: EventEmitter<AgentGroup>`, `cancelled: EventEmitter<void>`
   - Formularz z jednym polem: Nazwa (wymagane, max 255, unikalne — błąd 409 z BE wyświetl jako błąd pola)

3. `GroupMembersModalComponent` (`features/supervisor/agent-groups/group-members-modal/`)
   - Selektor: `app-group-members-modal`
   - Input signal: `group: AgentGroup`
   - Wyświetla aktualnych członków grupy
   - Multi-select: lista dostępnych agentów tenanta (pobierana przy otwarciu z `AppUserService` — filtr rola=AGENT)
   - Submit wywołuje `AgentGroupService.replaceGroupMembers(groupId, selectedAgentIds)`
   - Agenci niezaznaczeni a będący w grupie → usunięci; zaznaczeni a nieobecni → dodani
   - Output: `saved: EventEmitter<void>`, `cancelled: EventEmitter<void>`

**Routing** — dodaj w `supervisor.routes.ts`:
```typescript
{
  path: 'agent-groups',
  component: AgentGroupsPageComponent,
  canActivate: [AuthGuard, RoleGuard],
  data: { roles: ['SUPERVISOR', 'ADMIN'] }
}
```

**Usunięcie grupy:**
- Dialog potwierdzenia (wzorzec jak w FE-034)
- Błąd 409 (grupa przypisana do kolejki) → toast z komunikatem: "Nie można usunąć grupy przypisanej do kolejki. Usuń najpierw powiązanie z kolejką."

**Kryteria akceptacji:**
- [x] Strona ładuje się pod `/supervisor/agent-groups` z paginowaną listą grup
- [x] "Utwórz grupę" otwiera modal; po sukcesie nowa grupa pojawia się na liście
- [x] Edycja nazwy: modal prefillowany, po sukcesie tabela odświeżona
- [x] "Zarządzaj agentami" otwiera `GroupMembersModal`; multi-select zawiera agentów tenanta z rolą AGENT; bieżący skład grupy jest preselektowany
- [x] Po zapisie składu: `memberCount` w tabeli aktualizuje się
- [x] Usunięcie grupy nieprzypisanej → znika z listy
- [x] Usunięcie grupy przypisanej do kolejki → toast z błędem 409, brak usunięcia
- [x] Strona niedostępna dla roli AGENT (RoleGuard)
- [x] Loading spinner przy każdej operacji

---

### FE-038 – Komponent przypisania agentów do kolejki (`QueueAssignmentPanelComponent`)

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** L
**Zależy od:** FE-036, FE-037
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** FE-039

**Opis:**
Komponent wbudowany w istniejący formularz konfiguracji kolejki (strona edycji kolejki). Supervisor konfiguruje tryb przypisania agentów bez opuszczania formularza kolejki.

**Komponent:** `QueueAssignmentPanelComponent` (`features/supervisor/queues/queue-assignment-panel/`)
- Selektor: `app-queue-assignment-panel`
- Standalone, `ChangeDetectionStrategy.OnPush`
- Input signal: `queueId: string`

**Struktura UI:**

Sekcja "Przypisanie agentów" wyświetlona pod podstawowymi danymi kolejki:

1. **Radio group** wyboru trybu:
   - "Wszyscy agenci tenanta" — gdy zaznaczony, sekcje 2 i 3 są zwinięte/wyłączone
   - "Wybrane grupy lub agenci" — gdy zaznaczony, sekcje 2 i 3 są aktywne

2. **Sekcja Grupy** (widoczna gdy tryb = "Wybrane"):
   - Multi-select lub lista z checkboxami — dostępne grupy (z `AgentGroupService.listGroups`)
   - Zaznaczone grupy = przypisane do kolejki

3. **Sekcja Indywidualni agenci** (widoczna gdy tryb = "Wybrane"):
   - Multi-select lub lista z checkboxami — dostępni agenci tenanta (rola AGENT)
   - Podsumowanie: "X grup + Y agentów indywidualnych"
   - Informacja, gdy agenci z grup pokrywają się z indywidualnymi — wyświetl badge "Pokrycie X agentów łącznie"

4. **Przycisk "Zapisz przypisanie"** — osobny od głównego formularza kolejki (nie wymaga zapisania całej kolejki)

**Zachowanie:**
- Przy wejściu na stronę: `ngOnInit` pobiera `getQueueAssignment(queueId)` i ustawia stan
- Zapis wywołuje `AgentGroupService.updateQueueAssignment(queueId, { allAgents, directAgentIds, groupIds })`
- Po zapisie: toast "Przypisanie zaktualizowane"

**Signals:**
```typescript
assignment = signal<QueueAssignment | null>(null);
allAgents = signal<boolean>(true);
availableGroups = signal<AgentGroupSummary[]>([]);
availableAgents = signal<AgentGroupMember[]>([]);
selectedGroupIds = signal<string[]>([]);
selectedAgentIds = signal<string[]>([]);
saving = signal<boolean>(false);
```

**Kryteria akceptacji:**
- [x] Przy wejściu na stronę edycji kolejki: komponent pobiera aktualną konfigurację i ustawia radio + checkboxy
- [x] Przełączenie na "Wszyscy agenci" → checkboxy grup i agentów nieaktywne
- [x] Przełączenie na "Wybrane" → checkboxy aktywne; przy braku zaznaczenia wyświetl ostrzeżenie "Brak przypisanych agentów — kolejka nie obsłuży żadnego kontaktu"
- [x] Zapisanie z `allAgents=true` → API PUT z `allAgents: true`
- [x] Zapisanie z grupami i agentami → API PUT z poprawnymi listami ID
- [x] Po zapisie toast "Przypisanie zaktualizowane"
- [x] Błąd walidacji (agentId spoza tenanta) → toast z komunikatem błędu z BE
- [x] Komponent wyświetla łączną liczbę agentów objętych konfiguracją (computed z grup + indywidualnych)

---

### FE-039 – Integracja panelu przypisania z formularzem edycji kolejki

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Zależy od:** FE-038
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** brak

**Opis:**
Osadzenie `QueueAssignmentPanelComponent` w istniejącej stronie edycji kolejki. Zadanie dotyczy wyłącznie integracji — nie modyfikuje logiki głównego formularza kolejki.

**Lokalizacja istniejącego komponentu edycji kolejki:**
Znajdź komponent w `features/supervisor/queues/` (prawdopodobnie `edit-queue-page` lub `queue-form`). Dodaj sekcję "Przypisanie agentów" po sekcji z ustawieniami routingu.

**Zmiany:**
1. Zaimportuj `QueueAssignmentPanelComponent` w standalone imports edytora kolejki
2. Dodaj do szablonu:
   ```html
   <app-queue-assignment-panel [queueId]="queueId()" />
   ```
   — komponent jest samowystarczalny (pobiera i zapisuje dane niezależnie od głównego formularza)
3. Dodaj link "Grupy agentów" w nawigacji panelu supervisora (sidebar lub header) prowadzący do `/supervisor/agent-groups`

**Uwaga:** Sekcja przypisania jest aktywna tylko dla istniejących kolejek (po zapisaniu). Przy tworzeniu nowej kolejki — sekcja niewidoczna lub wyświetla informację "Zapisz kolejkę, aby skonfigurować przypisanie agentów".

**Kryteria akceptacji:**
- [x] Sekcja "Przypisanie agentów" widoczna na stronie edycji istniejącej kolejki
- [x] Sekcja niewidoczna (lub z komunikatem) przy tworzeniu nowej kolejki
- [x] Zapis głównego formularza kolejki NIE resetuje konfiguracji przypisania
- [x] Link "Grupy agentów" widoczny w nawigacji supervisora
- [x] Brak regresji: istniejące formularze kolejki działają jak przed zmianą

---

---

## MODUL: Zakładka Klienci w Agent Desktop (EPIC-15)

### FE-040 – Zakładka „Klienci" w Agent Desktop (`AgentCustomersTabComponent`)

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** M
**Zależy od:** FE-009 (Agent Desktop layout), FE-018 (CustomerService / Customer API)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-24
**Czeka na BE:** BE-025 ✅ (Customer CRUD API)
**Blokuje:** FE-041
**Epic:** EPIC-15 Zakładka Klienci w Agent Desktop

**Opis:**
Dodanie zakładki „Klienci" do głównego layoutu Agent Desktop (`AgentDesktopComponent`). Zakładka umożliwia agentowi wyszukiwanie i przeglądanie bazy klientów bez opuszczania panelu pracy. Widok jest osobny od strony `/clients` (CRM) — jest lżejszy i zoptymalizowany pod szybkie wyszukanie klienta.

**Lokalizacja:**
`features/agent-desktop/tabs/customers/`

**Komponenty:**
- `AgentCustomersTabComponent` — standalone, selector `app-agent-customers-tab`
- `AgentCustomerCardComponent` — pojedynczy wiersz/kafelek wyniku wyszukiwania

**Funkcjonalność:**

1. **Pole wyszukiwania** (debounced 300ms):
   - Fuzzy search po imieniu, nazwisku, numerze telefonu, emailu
   - Wywołuje `GET /api/customers?search=...&limit=20` (reużywa `CustomerService`)
   - Minimum 2 znaki przed wyszukaniem
   - Stan „Wpisz minimum 2 znaki..." gdy poniżej progu

2. **Lista wyników** (virtual scroll przy > 10 elementach):
   - Avatar z inicjałami
   - Imię i nazwisko (bold)
   - Pierwszy numer telefonu z tablicy `phone[]`
   - Pierwszy email z tablicy `email[]`
   - Data ostatniego kontaktu (`lastContactAt` z API lub brak)
   - Przycisk „Szczegóły" → otwiera `CustomerDetailDrawer` (reużywa FE-019/FE-030 logikę)
   - Przycisk „Zamów oddzwonienie" → otwiera `ManualCallbackModalComponent` (FE-041)

3. **Stan pustego wyniki:** komunikat „Nie znaleziono klientów"

4. **Stan ładowania:** skeleton loader (3 wiersze)

5. **Integracja z zakładkami Agent Desktop:**
   - Zakładka wyświetlana zawsze (niezależnie od stanu kontaktu)
   - Ikona: `person_search` (Material Icons)
   - Badge z liczbą wyników (opcjonalnie)

**Sygnały/state:**
```typescript
searchQuery = signal('');
customers = signal<CustomerSummary[]>([]);
isLoading = signal(false);
selectedCustomer = signal<CustomerDetail | null>(null);
```

**Kryteria akceptacji:**
- [ ] Zakładka „Klienci" widoczna w Agent Desktop obok istniejących zakładek
- [ ] Wyszukiwanie działa z debounce 300ms, min. 2 znaki
- [ ] Wyniki zawierają imię/nazwisko, telefon, email, datę ostatniego kontaktu
- [ ] Skeleton loader podczas ładowania
- [ ] Pusty stan gdy brak wyników
- [ ] Przycisk „Zamów oddzwonienie" widoczny dla każdego wyniku
- [ ] Przycisk „Szczegóły" otwiera panel szczegółów klienta
- [ ] Komponent standalone, brak NgModules
- [ ] Sygnały zamiast `BehaviorSubject` dla lokalnego stanu
- [ ] Lint i testy przechodzą bez błędów

---

### FE-041 – Modal zamówienia manualnego oddzwonienia do klienta (`ManualCallbackModalComponent`)

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Zależy od:** FE-040 (AgentCustomersTabComponent)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-24
**Czeka na BE:** BE-048 (Manual Callback API)
**Blokuje:** brak
**Epic:** EPIC-15 Zakładka Klienci w Agent Desktop

**Opis:**
Modal otwierany z przycisku „Zamów oddzwonienie" na liście klientów w zakładce FE-040. Agent wybiera numer telefonu (spośród numerów przypisanych do klienta), datę i godzinę oddzwonienia oraz opcjonalne notatki. Po zatwierdzeniu wysyła żądanie do `POST /api/callbacks/manual` (BE-048).

**Lokalizacja:**
`features/agent-desktop/tabs/customers/manual-callback-modal/`

**Komponent:** `ManualCallbackModalComponent` — standalone dialog (Angular CDK Dialog lub Material MatDialog)

**Formularz (ReactiveFormsModule):**

| Pole | Typ | Walidacja |
|------|-----|-----------|
| Numer telefonu | `<select>` / dropdown | wymagane; opcje z `customer.phone[]`; jeśli pusta lista — pole tekstowe z walidacją E.164 |
| Data i godzina | datetime-local input | wymagane; min. teraz + 5 minut |
| Notatki | textarea | opcjonalne; max 500 znaków |

**Zachowanie:**
1. Otwarcie: preload numerów telefonu z przekazanego obiektu klienta (input `customer: CustomerSummary`)
2. Submit → `CallbackService.createManualCallback(request)` → `POST /api/callbacks/manual`
3. Sukces: zamknij modal + snackbar „Oddzwonienie zaplanowane na [data]"
4. Błąd 400: wyświetl komunikat przy konkretnym polu formularza
5. Błąd serwera: snackbar z błędem, modal pozostaje otwarty

**Typy:**
```typescript
interface ManualCallbackRequest {
  customerId: string;
  phoneNumber: string;
  scheduledAt: string; // ISO 8601
  notes?: string;
}

interface ManualCallbackResponse {
  callbackId: string;
  customerName: string;
  phoneNumber: string;
  scheduledAt: string;
  status: 'PENDING';
}
```

**Kryteria akceptacji:**
- [ ] Modal otwiera się z preloaded numerami telefonu klienta
- [ ] Walidacja: numer wymagany, data minimalna = teraz + 5 min, notatki max 500 znaków
- [ ] Submit wywołuje `POST /api/callbacks/manual` z poprawnymi danymi
- [ ] Sukces → snackbar + zamknięcie modala
- [ ] Błąd serwera → snackbar, modal otwarty (dane zachowane)
- [ ] Spinner na przycisku Submit podczas wysyłania
- [ ] Przycisk „Anuluj" zamyka bez zapisu
- [ ] Komponent standalone, ReactiveFormsModule
- [ ] Lint i testy jednostkowe: walidacja formularza, submit success, submit error

---

---

## MODUŁ: Kalendarz Agenta (EPIC-16)

### FE-042 – `AgentCalendarService` i typy DTO dla kalendarza

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** BE-051, BE-050
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-26
**Blokuje:** FE-043, FE-044, FE-045
**Odniesienie PRD:** EPIC-16 – Agent Calendar

**Opis:**
Serwis Angular i TypeScript interfaces dla modułu kalendarza agenta. Obsługuje pobieranie danych z API (BE-051) oraz CRUD przerw (BE-050).

**Interfejsy:**
```typescript
interface CalendarCallback { id: string; customerName: string; scheduledAt: string; sourceType: 'CAMPAIGN_CALLBACK' | 'INBOUND_CALLBACK' | 'AGENT_MANUAL'; status: string }
interface CalendarCampaign { id, name, startDate, endDate, status }
interface CalendarBreak   { id, startTime, endTime, breakType, notes, status }
interface AgentCalendarResponse { callbacks, campaigns, breaks }
interface AgentBreakRequest { startTime, endTime, breakType, notes? }
```

**Metody serwisu:**
```typescript
getCalendar(from: Date, to: Date): Observable<AgentCalendarResponse>
addBreak(req: AgentBreakRequest): Observable<CalendarBreak>
updateBreak(id: string, req: AgentBreakRequest): Observable<CalendarBreak>
cancelBreak(id: string): Observable<void>
```

**Kryteria akceptacji:**
- [ ] Serwis `@Injectable({ providedIn: 'root' })`, standalone
- [ ] Typy zgodne z OpenAPI BE-051 / BE-050
- [ ] Testy jednostkowe z `HttpClientTestingModule`

---

### FE-043 – `AgentCalendarComponent`: widok kalendarza agenta

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** L
**Zależy od:** FE-042
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-27
**Blokuje:** brak
**Odniesienie PRD:** EPIC-16 – Agent Calendar

**Opis:**
Główny widok kalendarza jako nowa zakładka w Agent Desktop. Wyświetla zdarzenia trzech typów w siatce tygodniowej/dziennej. Każdy typ zdarzenia ma odrębny kolor i ikonę. Kliknięcie callbacku otwiera `RescheduleCallbackModalComponent`, kliknięcie „Dodaj przerwę" otwiera `AddBreakModalComponent`. Widok przełączany między tygodniem a dniem.

**Wymagania wizualne:**
- Kampanie wychodzące: kolor niebieski, ikona `campaign`
- Callbacki: kolor pomarańczowy, ikona `phone_callback`
- Przerwy: kolor zielony, ikona `free_breakfast`
- Przycisk FAB „+ Dodaj przerwę" w prawym dolnym rogu
- Przełącznik Tydzień / Dzień w nagłówku
- Strzałki nawigacji (poprzedni/następny tydzień lub dzień)
- Spinner podczas ładowania, komunikat gdy brak zdarzeń

**Kryteria akceptacji:**
- [ ] Zakładka „Kalendarz" widoczna w `AgentDesktopComponent`
- [ ] Zdarzenia z trzech źródeł wyświetlane z poprawnymi kolorami i ikonami
- [ ] Klik callbacku → otwiera `RescheduleCallbackModalComponent` z wypełnioną datą
- [ ] Klik kampanii → tooltip/panel boczny z detalami kampanii (read-only)
- [ ] Klik własnej przerwy → otwiera `AddBreakModalComponent` (tryb edycji) lub opcję anulowania
- [ ] Przełącznik tydzień/dzień działa poprawnie
- [ ] Komponent standalone, `OnPush`, `signal()` dla stanu dat i zdarzeń
- [ ] Responsywny: na wąskim ekranie fallback do widoku dziennego

---

### FE-044 – `RescheduleCallbackModalComponent`: zmiana daty callbacku z kalendarza

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-042, BE-039
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-26
**Blokuje:** brak
**Odniesienie PRD:** EPIC-16 – Agent Calendar

**Opis:**
Modal uruchamiany po kliknięciu zdarzenia typu callback w kalendarzu. Wyświetla bieżącą datę callbacku i pozwala wybrać nową. Po zatwierdzeniu wywołuje istniejący endpoint BE-039 (`PUT /api/callbacks/{id}/reschedule`). Po zapisie odświeża kalendarz.

**Kryteria akceptacji:**
- [ ] Modal otwiera się z aktualną datą callbacku wypełnioną w polu date-time picker
- [ ] Data nie może być w przeszłości → walidacja inline
- [ ] Submit → `PUT /api/callbacks/{id}/reschedule` z nową datą
- [ ] Sukces → snackbar + zamknięcie modala + odświeżenie kalendarza
- [ ] Błąd 4xx/5xx → snackbar z komunikatem, modal otwarty
- [ ] Komponent standalone, `ReactiveFormsModule`

---

### FE-045 – `AddBreakModalComponent`: dodanie i edycja zaplanowanej przerwy

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-042
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-27
**Blokuje:** brak
**Odniesienie PRD:** EPIC-16 – Agent Calendar

**Opis:**
Modal do dodania nowej zaplanowanej przerwy lub edycji istniejącej (tryb przekazywany przez `data` injection). Formularz reaktywny z polami: typ przerwy (select), data i godzina rozpoczęcia, data i godzina zakończenia, notatki (opcjonalne). Przy edycji istniejącej przerwy wyświetla przycisk „Anuluj przerwę" (soft-delete).

**Pola formularza:**
- Typ przerwy: select (Lunch, Krótka przerwa, Szkolenie, Inne)
- Czas rozpoczęcia: date-time picker
- Czas zakończenia: date-time picker
- Notatki: textarea, opcjonalne

**Kryteria akceptacji:**
- [ ] Walidacja: czas zakończenia > czas rozpoczęcia
- [ ] Walidacja: czas w przyszłości (przy tworzeniu)
- [ ] Tryb dodawania: Submit → `POST /api/agent/breaks`
- [ ] Tryb edycji (dane wstrzyknięte): Submit → `PUT /api/agent/breaks/{id}`
- [ ] Przycisk „Anuluj przerwę" w trybie edycji → `DELETE /api/agent/breaks/{id}` (dialog potwierdzenia)
- [ ] Sukces → snackbar + zamknięcie modala + odświeżenie kalendarza
- [ ] Komponent standalone, `ReactiveFormsModule`, cross-field validator `endAfterStart`

---

## Powiadomienia o połączeniu (EPIC-17 – Incoming Call Alert)

> Agent przebywający na zakładce innej niż `/agent/desktop` (Klienci, Oddzwonienia) nie widzi
> softphone'a i może przeoczyć przychodzące połączenie. Poniższe zadania rozwiązują ten problem
> przez globalny serwis alertów + pływający banner + opcjonalne powiadomienie przeglądarki.

---

### FE-046 – `IncomingCallAlertService`: globalny serwis alertów o przychodzącym połączeniu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-009 (Agent Desktop, WebSocket, SoftphoneService, ContactTabStore)
**Status:** ✅ Zrobione
**Blokuje:** FE-047, FE-048
**Odniesienie PRD:** EPIC-17 – Incoming Call Alert

**Opis:**
Serwis singleton (`providedIn: 'root'`) nasłuchujący zdarzeń WebSocket `CALL_INCOMING` i `CONTACT_ASSIGNED` (type: PHONE) przez cały czas sesji agenta, niezależnie od aktywnej strony. Przejmuje odpowiedzialność za wywołanie `ContactTabStore.openFromCallIncoming()` i `SoftphoneService.incomingCall()` — czyli logikę dotychczas obsługiwaną wyłącznie w `AgentDesktopComponent.ngOnInit()`, która nie działa gdy komponent jest odmontowany.

**Interfejsy:**
```typescript
interface IncomingCallAlert {
  contactId: string;
  customerName: string;
  customerPhone: string;
  queueName: string;
  receivedAt: Date;
}
```

**Sygnały i metody:**
- `pendingAlert: signal<IncomingCallAlert | null>` — aktywne oczekujące połączenie
- `dismissAlert(): void` — czyści alert (nie rozłącza połączenia)
- Automatyczny auto-dismiss gdy `softphoneService.session()?.state` zmienia się z `RINGING` na `ACTIVE` lub `ENDED`

**Obsługa CALL_INCOMING (przeniesiona z AgentDesktopComponent):**
1. Wywołuje `ContactTabStore.openFromCallIncoming(payload)` — otwiera tab (sprawdza limity)
2. Jeśli limit OK → wywołuje `SoftphoneService.incomingCall(payload)` — stan → `RINGING`
3. Ustawia `pendingAlert` z danymi połączenia
4. Jeśli limit przekroczony → tylko `NotificationService.warning(...)`, nie ustawia alertu

**Web Notifications API:**
- Przy inicjalizacji serwisu: `Notification.requestPermission()` (jednorazowo)
- Przy ustawieniu `pendingAlert`: `new Notification('Przychodzące połączenie', { body: ..., icon: ... })`
- Klik w powiadomienie systemowe → `window.focus()` + `Router.navigate(['/agent/desktop'])`
- Auto-zamknięcie powiadomienia systemowego po 15s lub przy dismissAlert

**Dźwięk dzwonka:**
- Plik `assets/sounds/ringtone.mp3` (krótka pętla, ~3s)
- Start odtwarzania przy ustawieniu `pendingAlert` (loop: true)
- Stop przy `dismissAlert()` lub auto-dismiss
- Obsługa błędów: AudioContext wymaga gestu użytkownika — inicjuj przy pierwszej interakcji

**Kryteria akceptacji:**
- [ ] Serwis jest singleton `providedIn: 'root'`, inicjalizuje się przy starcie aplikacji (np. przez APP_INITIALIZER lub wstrzyknięcie w AppComponent)
- [ ] Nasłuchuje `CALL_INCOMING` niezależnie od aktywnej strony (nawet gdy `/agent/customers` jest aktywny)
- [ ] Wywołuje `ContactTabStore.openFromCallIncoming()` i `SoftphoneService.incomingCall()` — przenosząc tę logikę z `AgentDesktopComponent`
- [ ] `pendingAlert` ustawiany tylko gdy limit nie jest przekroczony
- [ ] Auto-dismiss działa przez effect na `softphoneService.session`
- [ ] Web Notification wyświetla się, gdy uprawnienia są przyznane
- [ ] Klik w Web Notification nawiguje do `/agent/desktop`
- [ ] Dźwięk dzwonka gra w pętli gdy `pendingAlert !== null`
- [ ] Dźwięk zatrzymuje się przy `dismissAlert()`
- [ ] Brak błędów w konsoli przy braku uprawnień do powiadomień lub audio

---

### FE-047 – `IncomingCallBannerComponent`: pływający banner powiadomienia

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-046
**Status:** ⬜ Do zrobienia
**Blokuje:** FE-048
**Odniesienie PRD:** EPIC-17 – Incoming Call Alert

**Opis:**
Standalone komponent wyświetlający pulsujący banner u góry ekranu gdy agent jest poza `/agent/desktop` i istnieje oczekujące połączenie (`IncomingCallAlertService.pendingAlert() !== null`). Banner jest `position: fixed` z wysokim `z-index`, widoczny nad dowolną stroną aplikacji.

**Selector:** `cc-incoming-call-banner`

**Warunki widoczności:**
- `pendingAlert() !== null` — jest oczekujące połączenie
- `!router.url.startsWith('/agent/desktop')` — agent nie jest na stronie desktop

**Zawartość:**
- Ikona telefonu z animacją pulsowania (CSS `@keyframes`)
- Tekst: „Przychodzące połączenie"
- Imię i nazwisko klienta (bold)
- Numer telefonu
- Nazwa kolejki
- Przycisk CTA: „Przejdź do pulpitu i odbierz" → `Router.navigate(['/agent/desktop'])` + `IncomingCallAlertService.dismissAlert()`
- Przycisk X (ikonka): `dismissAlert()` — zamknięcie bannera bez odrzucania połączenia

**Styl:**
- Tło: czerwień ostrzegawcza (`#dc2626` lub odpowiednik z design tokenu)
- Tekst biały
- Cień (box-shadow) dla wyróżnienia nad treścią
- Animacja pulse na ikonie telefonu
- Animacja slideDown przy pojawieniu się bannera

**Kryteria akceptacji:**
- [ ] Banner widoczny tylko gdy `pendingAlert !== null` i agent nie jest na `/agent/desktop`
- [ ] Po kliknięciu CTA: nawigacja do `/agent/desktop` + dismiss alertu
- [ ] Po kliknięciu X: dismiss alertu (banner znika, połączenie nadal dzwoni w Twilio)
- [ ] Komponent standalone, `ChangeDetectionStrategy.OnPush`
- [ ] Animacja pulse na ikonie działa (CSS-only, nie JS interval)
- [ ] Banner znika automatycznie gdy agent odbierze lub odrzuci połączenie (auto-dismiss)
- [ ] Responsywny — działa na wąskich ekranach (laptop 1280px)

---

### FE-048 – Integracja bannera w AgentShellComponent i refaktoryzacja AgentDesktopComponent

**Typ:** Refactor + Integration
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-046, FE-047
**Status:** ⬜ Do zrobienia
**Blokuje:** brak
**Odniesienie PRD:** EPIC-17 – Incoming Call Alert

**Opis:**
Dwa powiązane kroki: (1) dodanie `<cc-incoming-call-banner />` do szablonu `AgentShellComponent`, który jest persistentny dla wszystkich stron agenta; (2) usunięcie z `AgentDesktopComponent` logiki obsługi `CALL_INCOMING`, która została przeniesiona do `IncomingCallAlertService` w FE-046 — tak by uniknąć podwójnego wywołania.

**Zmiany w AgentShellComponent:**
```typescript
// agent-shell.component.ts
import { IncomingCallBannerComponent } from './components/incoming-call-banner/incoming-call-banner.component';

@Component({
  template: `
    <cc-app-shell />
    <cc-incoming-call-banner />
  `,
  imports: [AppShellComponent, IncomingCallBannerComponent],
})
export class AgentShellComponent {}
```

**Zmiany w AgentDesktopComponent:**
- Usunąć subskrypcję na `CALL_INCOMING` z `ngOnInit` (przeniesioną do FE-046)
- Usunąć wywołania `tabStore.openFromCallIncoming()` i `softphoneService.incomingCall()` z tego komponentu
- Zachować subskrypcję na `CALL_OUTBOUND`, `CONTACT_ASSIGNED` (inne kanały niż PHONE), `QUEUE_UPDATE`, `CALL_HANGUP` — te pozostają w AgentDesktopComponent
- Wywołać `IncomingCallAlertService.dismissAlert()` gdy agent wróci na desktop i połączenie zostanie odebrane (w effect na `softphoneService.session()?.state === 'ACTIVE'`)
- Upewnić się, że gdy agent wraca na desktop po kliknięciu bannera, widzi softphone w stanie `RINGING` (stan jest już ustawiony przez serwis — komponent tylko renderuje sygnały)

**Kryteria akceptacji:**
- [ ] `AgentShellComponent` renderuje `<cc-incoming-call-banner />` obok `<cc-app-shell />`
- [ ] `AgentDesktopComponent` nie wywołuje już `openFromCallIncoming()` ani `softphoneService.incomingCall()` dla `CALL_INCOMING`
- [ ] Brak podwójnego wywołania `incomingCall()` gdy agent jest na `/agent/desktop`
- [ ] Gdy agent kliknie banner i wróci na desktop, softphone wyświetla stan `RINGING` z poprawnymi danymi klienta
- [ ] Wszystkie istniejące przepływy (CALL_OUTBOUND, CONTACT_ASSIGNED EMAIL/CHAT/SOCIAL, QUEUE_UPDATE, CALL_HANGUP) działają bez zmian
- [ ] Testy jednostkowe dla `IncomingCallAlertService`: przeniesiona logika, auto-dismiss, Web Notification
- [ ] Brak regresji w istniejących testach `AgentDesktopComponent`

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
| RODO / GDPR | 1 | 1 | 0 |
| Raporty (EPIC-10) | 2 | 2 | 0 |
| Konfiguracja | 3 | 2 | 1 |
| Routing telefoniczny (EPIC-11) | 1 | 1 | 0 |
| Dialer manualny | 1 | 1 | 0 |
| Prezentacja Kontaktów (EPIC-12) | 3 | 3 | 0 |
| Zaplanowane oddzwonienia (EPIC-13) | 4 | 4 | 0 |
| Zarządzanie przypisaniem agentów (EPIC-14) | 4 | 4 | 0 |
| Zakładka Klienci w Agent Desktop (EPIC-15) | 2 | 2 | 0 |
| Kalendarz Agenta (EPIC-16) | 4 | 0 | 4 |
| Powiadomienia o połączeniu (EPIC-17) | 3 | 3 | 0 |
| Testy jednostkowe (EPIC-18) | 4 | 0 | 4 |

---

## MODUL: Testy jednostkowe (EPIC-18)

### FE-T001 – Testy jednostkowe AgentStatusService

**Typ:** Testing
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-agent-desktop (ukończone)
**Status:** 🔲 Do zrobienia
**Blokuje:** -
**Odniesienie PRD:** EPIC-03

**Opis:**
Napisać testy Vitest dla `AgentStatusService`.

**Kryteria akceptacji:**
- [ ] `setStatus()` → HTTP PUT, optymistyczna aktualizacja sygnału, rollback przy błędzie
- [ ] WebSocket update: odbiór eventu zmiany statusu innego agenta, aktualizacja listy
- [ ] `getAvailableStatuses()` — filtrowanie według roli (agent vs supervisor)
- [ ] Wszystkie testy przechodzą (`npm test`)

---

### FE-T002 – Testy jednostkowe ContactService

**Typ:** Testing
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-agent-desktop (ukończone)
**Status:** 🔲 Do zrobienia
**Blokuje:** -
**Odniesienie PRD:** EPIC-12

**Opis:**
Napisać testy Vitest dla `ContactService`.

**Kryteria akceptacji:**
- [ ] `getContacts()` — stronicowanie, filtrowanie po statusie/kanale, tenant header
- [ ] `assignContact()` — happy path, kontakt już przypisany (błąd 409), optymistyczna aktualizacja
- [ ] `closeContact()` — zmiana statusu, wyzwolenie eventu do supervisora
- [ ] Wszystkie testy przechodzą (`npm test`)

---

### FE-T003 – Testy jednostkowe CallbackService

**Typ:** Testing
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-oddzwonienia (ukończone)
**Status:** 🔲 Do zrobienia
**Blokuje:** -
**Odniesienie PRD:** EPIC-13

**Opis:**
Napisać testy Vitest dla `CallbackService`.

**Kryteria akceptacji:**
- [ ] `scheduleCallback()` — walidacja daty (nie w przeszłości), HTTP POST, zapis w lokalnym stanie
- [ ] `cancelCallback()` — HTTP DELETE, usunięcie z listy
- [ ] `getUpcoming()` — filtrowanie przeszłych callbacków, sortowanie po dacie
- [ ] Wszystkie testy przechodzą (`npm test`)

---

### FE-T004 – Testy jednostkowe AgentGroupService

**Typ:** Testing
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-przypisanie-agentów (ukończone)
**Status:** 🔲 Do zrobienia
**Blokuje:** -
**Odniesienie PRD:** EPIC-14

**Opis:**
Napisać testy Vitest dla `AgentGroupService`.

**Kryteria akceptacji:**
- [ ] `getGroups()` — lista grup z agentami, cache (nie wywołuje HTTP dwa razy)
- [ ] `addAgentToGroup()` — HTTP POST, aktualizacja lokalnej listy
- [ ] `removeAgentFromGroup()` — HTTP DELETE, rollback przy błędzie 404
- [ ] Wszystkie testy przechodzą (`npm test`)
| **RAZEM** | **51** | **42** | **9** |
