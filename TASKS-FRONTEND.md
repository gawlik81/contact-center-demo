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
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-28
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
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-28
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
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-28
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

## MODUL: Wielojęzyczność UI (EPIC-19)

### FE-049 – Konfiguracja Transloco i pliki tłumaczeń (PL / EN / DE)

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-001
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-28
**Czeka na BE:** brak
**Blokuje:** FE-050, FE-051, FE-052
**Epic:** EPIC-19 Wielojęzyczność
**Odniesienie PRD:** przekrojowe

**Opis:**
Instalacja i konfiguracja biblioteki **Transloco** (`@jsverse/transloco`). Ustawienie providerów w `app.config.ts` z loaderem HTTP lazy-loading plików `assets/i18n/{lang}.json`. Stworzenie szkieletowych plików tłumaczeń dla języków: `pl`, `en`, `de`. Konfiguracja `availableLangs`, `defaultLang = 'pl'`, `fallbackLang = 'en'`. Dodanie `assets/i18n/` do `angular.json` assets.

**Kryteria akceptacji:**
- [ ] `@jsverse/transloco` zainstalowany i skonfigurowany w `app.config.ts`
- [ ] Pliki `assets/i18n/pl.json`, `en.json`, `de.json` obecne i ładowane przez HTTP
- [ ] `TranslocoService.setActiveLang()` zmienia język bez przeładowania strony
- [ ] Fallback na `en` gdy klucz brakuje w aktywnym języku
- [ ] `ng build` i `npm test` kończą się bez błędów
- [ ] Loader skonfigurowany do lazy-load (nie inline translations)

---

### FE-050 – `LanguageService`: zarządzanie językiem i persystencja

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-049, BE-054
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-28
**Czeka na BE:** BE-054 (endpoint preferencji użytkownika)
**Blokuje:** FE-051
**Epic:** EPIC-19 Wielojęzyczność
**Odniesienie PRD:** przekrojowe

**Opis:**
Serwis `LanguageService` (`core/services/language.service.ts`) odpowiedzialny za:
1. **Init przy starcie** (`APP_INITIALIZER`): odczyt `preferred_language` z profilu zalogowanego użytkownika (`GET /api/users/me/preferences`) → localStorage → język przeglądarki (`navigator.language`) → fallback `pl`.
2. **Zmiana języka**: `setLanguage(lang: SupportedLanguage)` → wywołuje `TranslocoService.setActiveLang()` → persystuje w localStorage → jeśli zalogowany, synchronizuje z backendem `PUT /api/users/me/preferences`.
3. Eksponuje `currentLang = signal<SupportedLanguage>()`.
4. Typ `SupportedLanguage = 'pl' | 'en' | 'de'` w `core/models/language.model.ts`.

**Kryteria akceptacji:**
- [ ] `LanguageService` zarejestrowany jako `providedIn: 'root'`
- [ ] `APP_INITIALIZER` ładuje preferencję z backendu (gdy zalogowany) lub localStorage
- [ ] `setLanguage()` aktualizuje Transloco, localStorage i backend (w tle, bez blokowania UI)
- [ ] `currentLang` signal reaguje na zmiany
- [ ] Gdy backend zwróci błąd przy zapisie, zmiana języka w UI nie jest cofana
- [ ] Testy jednostkowe: init flow, fallback chain, sync z backendem

---

### FE-051 – `LanguageSwitcherComponent`: wybór języka w nagłówku

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-050
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-28
**Czeka na BE:** brak
**Blokuje:** brak
**Epic:** EPIC-19 Wielojęzyczność
**Odniesienie PRD:** przekrojowe

**Opis:**
Standalone component `LanguageSwitcherComponent` (`shared/components/language-switcher/`) wyświetlający dropdown z listą dostępnych języków. Każdy język reprezentowany flagą + skrótem (PL, EN, DE). Aktywny język wyróżniony. Komponent dodany do `AppShellComponent` (nagłówek lub menu użytkownika). Używa `LanguageService` do odczytu i zmiany języka.

**Selektor:** `app-language-switcher`

**Kryteria akceptacji:**
- [ ] Dropdown z opcjami: PL / EN / DE z etykietami języka
- [ ] Aktywny język oznaczony (checkmark lub pogrubienie)
- [ ] Kliknięcie zmienia natychmiast widoczne teksty w UI (bez przeładowania)
- [ ] Komponent widoczny w `AppShellComponent` dla wszystkich zalogowanych ról
- [ ] Dostępność: `aria-label`, `role="listbox"` lub natywny `<select>` na mobile
- [ ] Responsywny: na małych ekranach pokazuje tylko skrót (PL/EN/DE)

---

### FE-052 – Internacjonalizacja: moduł Auth i AppShell

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-049, FE-050
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-28
**Czeka na BE:** brak
**Blokuje:** FE-053
**Epic:** EPIC-19 Wielojęzyczność
**Odniesienie PRD:** przekrojowe

**Opis:**
Zastąpienie hardkodowanych ciągów tekstowych kluczami Transloco w priorytetowych modułach:
- **Auth**: formularz logowania, komunikaty błędów, etykiety pól
- **AppShell / nawigacja**: etykiety menu, nagłówki sekcji, tooltips
- **Komponenty shared**: komunikaty `NotificationService`, etykiety przycisków, okna dialogowe

Uzupełnienie plików `pl.json`, `en.json`, `de.json` o przetłumaczone klucze dla ww. tekstów. Użycie pipe `{{ 'key' | transloco }}` w szablonach i `TranslocoService.translate()` w logice serwisowej.

**Kryteria akceptacji:**
- [ ] Formularz logowania w pełni przetłumaczony (PL/EN/DE)
- [ ] Nawigacja AppShell przetłumaczona (PL/EN/DE)
- [ ] Komunikaty błędów HTTP i walidacji przetłumaczone
- [ ] Brak hardkodowanych polskich ciągów w ww. komponentach
- [ ] Zmiana języka przez `LanguageSwitcherComponent` widoczna natychmiast

---

### FE-053 – Internacjonalizacja: Agent Desktop, Supervisor, Admin

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** L
**Zależy od:** FE-052
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-29
**Czeka na BE:** brak
**Blokuje:** brak
**Epic:** EPIC-19 Wielojęzyczność
**Odniesienie PRD:** przekrojowe

**Opis:**
Pełna internacjonalizacja pozostałych modułów aplikacji:
- **Agent Desktop**: zakładki (Softphone, Email, Chat, Social, Klienci, Kalendarz), etykiety statusów agenta, przyciski akcji
- **Supervisor Dashboard**: nagłówki tabel, statusy, filtry, etykiety wykresów
- **Admin Panel**: formularze tenantów, agentów, kolejek, kampanii; komunikaty sukcesu/błędu
- **Modale / dialogi**: wszystkie okna dialogowe w aplikacji

Klucze organizowane hierarchicznie w JSON: `{ "agent": { "desktop": { ... } }, "supervisor": { ... }, "admin": { ... } }`.

**Kryteria akceptacji:**
- [ ] Wszystkie moduły aplikacji nie zawierają hardkodowanych polskich ciągów
- [ ] Pliki `pl.json`, `en.json`, `de.json` kompletne (brak brakujących kluczy)
- [ ] Testy snapshot/unit przechodzą z domyślnym językiem `pl`
- [ ] Dynamiczne wartości (imię użytkownika, liczby) obsługiwane przez Transloco params

---

### FE-054 – i18n fix: contacts-report — hardcoded nagłówki tabeli

**Typ:** Bug / i18n
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Zrobione
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Plik: `features/supervisor/pages/contacts-report/contacts-report.component.html`
Nagłówki `<th>` ("Data i czas", "Kanal", "Kierunek", "Kolejka", "Czas trwania", "Status", "Dyspozycja", "Akcje") oraz `<option>Wszystkie</option>` są hardcoded po polsku.

**Kryteria akceptacji:**
- [x] Wszystkie nagłówki tabeli używają `| transloco` pipe
- [x] Opcja "Wszystkie" w filtrze używa klucza `common.all` lub `supervisor.contactsReport.*`
- [x] Tekst poprawnie wyświetla się w PL / EN / DE / UK

---

### FE-055 – i18n fix: customer-detail — hardcoded etykiety i nagłówki

**Typ:** Bug / i18n
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Zrobione
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Plik: `features/supervisor/pages/customers/customer-detail/customer-detail.component.html`
Hardcoded: "Dane kontaktowe", "Ostatnia aktualizacja", "Zgoda na przetwarzanie", "Tak"/"Nie" (badge), "Data zgody", "Zgoda marketingowa", "Dodatkowe pola", nagłówki tabeli historii kontaktów oraz aria-labels nawigacji.

**Kryteria akceptacji:**
- [x] Wszystkie etykiety `<dt>`, `<th>`, tytuły sekcji używają `| transloco`
- [x] Badge "Tak"/"Nie" używają `common.yes` / `common.no`
- [x] Aria-labels nawigacji używają kluczy `supervisor.customerDetail.*`

---

### FE-056 – i18n fix: social-integrations — hardcoded tytuł i etykiety

**Typ:** Bug / i18n
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Zrobione
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Pliki: `features/integrations/pages/social-integrations/social-integrations.component.html` + `.ts`
HTML: "Integracje Social Media", "Nazwa strony", "Token wygasa" hardcoded po polsku.
TS: metoda zwraca hardcoded `'Błąd'` zamiast przetłumaczonego stringa.

**Kryteria akceptacji:**
- [x] Tytuł i etykiety `<dt>` używają `| transloco` z kluczami `integrations.social.*`
- [x] Metoda w TS używa `transloco.translate()` lub klucz jest rozwiązywany w template

---

### FE-057 – i18n fix: ivr-editor — hardcoded etykiety formularza i aria-labels

**Typ:** Bug / i18n
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-053
**Status:** ✅ Zrobione
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Plik: `features/supervisor/pages/ivr/ivr-editor/ivr-editor.component.html` + `.ts`
HTML: `<label>` "Plik audio", "Routing", "Przypadki"; przycisk "Kopiuj"; aria-labels węzłów ("Węzeł startowy", "Port wejściowy/wyjściowy", "Kliknij, by połączyć", "Paleta węzłów", "Właściwości węzła", "Podgląd JSON"); title attrs "Usuń węzeł/opcję/przypadek".
TS: `warnings.push('Brak zdefiniowanego wezla startowego...')` hardcoded po polsku.

**Kryteria akceptacji:**
- [x] Wszystkie `<label>`, aria-label, title w edytorze używają `| transloco`
- [x] Warning o braku węzła startowego używa `transloco.translate()` z kluczem `supervisor.ivr.*`
- [x] Przycisk "Kopiuj" używa `common.copy` lub `supervisor.ivrEditor.copyJson`

---

### FE-058 – i18n fix: dni tygodnia w campaign-form, campaign-info, phone-number.model

**Typ:** Bug / i18n
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Zrobione
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Pliki:
- `features/supervisor/pages/campaigns/campaign-form/campaign-form.component.ts` — tablica `WEEK_DAYS` z `{ value: 'MON', label: 'Pon' }` itp.
- `features/supervisor/pages/campaigns/campaign-info/campaign-info.component.ts` — mapa `{ MON: 'Pon', TUE: 'Wt', ... }`
- `features/supervisor/models/phone-number.model.ts` — mapa `{ 1: 'Pon', 2: 'Wt', ... }`
Zastąpić `transloco.translate()` z kluczami `agent.calendar.days.MON` itp. + reaktywność na zmianę języka.

**Kryteria akceptacji:**
- [x] Nazwy dni renderują się w aktywnym języku po przełączeniu
- [x] Brak hardcodowanych polskich skrótów w plikach TS

---

### FE-059 – i18n fix: error-handler.interceptor — hardcoded komunikaty błędów

**Typ:** Bug / i18n
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Zrobione
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Plik: `core/interceptors/error-handler.interceptor.ts`
Hardcoded: `notifications.error('Brak połączenia z serwerem')`, `'Brak uprawnień'`, `'Błąd serwera, spróbuj ponownie'`.
Interceptor musi wstrzykiwać `TranslocoService` i używać `transloco.translate()` z kluczami np. `common.errorNetwork`, `common.errorForbidden`, `common.errorServer`.

**Kryteria akceptacji:**
- [x] Wszystkie 3 komunikaty błędów pobierane z pliku i18n
- [x] Powiadomienia wyświetlają się w aktywnym języku

---

### FE-060 – i18n fix: tenant modals — hardcoded tytuły i etykiety

**Typ:** Bug / i18n
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Zrobione
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Pliki:
- `features/tenants/tenant-add-modal/tenant-add-modal.component.html` — "Nowy tenant", "Limity zasobow"
- `features/tenants/tenant-deactivate-modal/tenant-deactivate-modal.component.html` — "Dezaktywacja tenanta"
- `features/tenants/tenant-edit-modal/tenant-edit-modal.component.html` — "Edytuj tenanta", "Anuluj"

**Kryteria akceptacji:**
- [x] Tytuły modali i etykiety sekcji używają `| transloco`
- [x] Klucze dodane do wszystkich 4 plików i18n (pl/en/de/uk)

---

### FE-061 – i18n fix: schedule/reschedule-callback-modal — hardcoded stringi

**Typ:** Bug / i18n
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Zrobione
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Pliki:
- `features/agent/components/schedule-inbound-callback-modal/` — HTML: "Zaplanuj oddzwonienie", "Oddzwonienie zostanie dodane do kolejki agentow", "Nazwisko", "Zaplanuj"; TS: `errorMessage.set('Brak uprawnien...')`
- `features/agent/components/reschedule-callback-modal/` — HTML: `<span>Zapisz</span>`; TS: `errorMessage.set('Brak uprawnien...')`

**Kryteria akceptacji:**
- [x] Widoczne teksty w obu modalach używają `| transloco`
- [x] Komunikaty błędów w TS pobierane z i18n

---

### FE-062 – i18n fix: email-contact i social-contact — hardcoded etykiety

**Typ:** Bug / i18n
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-03
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Pliki:
- `features/agent/pages/agent-desktop/email-contact/email-contact.component.html` — "Temat odpowiedzi", "Wyslij odpowiedz"
- `features/agent/pages/agent-desktop/social-contact/social-contact.component.html` — "Zaladuj wczesniejsze"

**Kryteria akceptacji:**
- [ ] Etykiety używają kluczy `agent.emailContact.*` i `agent.socialContact.*`

---

### FE-063 – i18n fix: customer-panel — hardcoded etykiety

**Typ:** Bug / i18n
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-03
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Plik: `features/agent/components/customer-panel/customer-panel.component.html`
Hardcoded: "Brak aktywnego kontaktu", "Nieznany klient", "Ostatnie kontakty".

**Kryteria akceptacji:**
- [ ] Etykiety używają kluczy `agent.customerPanel.*`

---

### FE-064 – i18n fix: agent-groups, admin-user-list — hardcoded aria-labels i title

**Typ:** Bug / i18n
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-03
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Pliki:
- `features/supervisor/pages/agent-groups/agent-groups-page/agent-groups-page.component.html` — title "Edytuj grupę", "Zarządzaj agentami", "Usuń grupę"
- `features/supervisor/pages/agent-groups/group-members-modal/group-members-modal.component.html` — aria-label "Filtruj agentów", "Lista agentów"
- `features/admin/pages/users/admin-user-list/admin-user-list.component.html` — aria-label "Skills użytkownika", "Potwierdź usuniecie", `<span>Brak</span>`

**Kryteria akceptacji:**
- [ ] Wszystkie title i aria-label używają `[attr.aria-label]` / `[title]` z `| transloco`
- [ ] `<span>Brak</span>` zastąpiony kluczem `common.no_data` lub `common.none`

---

### FE-065 – i18n fix: manual-callback-modal i agent-callbacks-page — pozostałe stringi

**Typ:** Bug / i18n
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** FE-053
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-03
**Blokuje:** -
**Epic:** EPIC-19 Wielojęzyczność

**Opis:**
Pliki:
- `features/agent/pages/customers/manual-callback-modal/manual-callback-modal.component.ts` — `errorMessage.set('Brak uprawnien do zamowienia oddzwonienia.')`
- `features/agent/pages/callbacks/agent-callbacks-page.component.html` — `title="Przełóż oddzwonienie"`, `<span class="sr-only">Akcje</span>`

**Kryteria akceptacji:**
- [ ] Komunikat błędu w TS pobierany z i18n
- [ ] title i sr-only "Akcje" używają `| transloco`

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
| i18n fixes (EPIC-19) | 12 | 6 | 6 |
| Per-tenant konfiguracja Twilio (EPIC-20) | 3 | 0 | 3 |

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
| **RAZEM** | **54** | **42** | **12** |

---

## MODUL: Per-tenant konfiguracja Twilio (EPIC-20)

### FE-066 – `TwilioConfigComponent`: formularz konfiguracji Twilio w panelu supervisora

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** BE-057 (REST API konfiguracji Twilio)
**Status:** ✅ Ukończone
**Blokuje:** brak
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

**Opis:**
Standalone komponent `TwilioConfigComponent` (`features/supervisor/pages/twilio-config/twilio-config.component.ts`) wyświetlający formularz zarządzania integracją Twilio dla supervisora bieżącego tenanta. Komponent dostępny pod ścieżką `/supervisor/settings/twilio` (lub `/supervisor/twilio-config` w zależności od struktury routingu).

**Serwis:**
`TwilioConfigService` (`features/supervisor/services/twilio-config.service.ts`) – wrapper HTTP:
- `getConfig(): Observable<TwilioConfigResponse>` → `GET /api/supervisor/twilio-config`
- `saveConfig(data: TwilioConfigRequest): Observable<TwilioConfigResponse>` → `PUT /api/supervisor/twilio-config`
- `deleteConfig(): Observable<void>` → `DELETE /api/supervisor/twilio-config`
- `testConnection(): Observable<TwilioConnectionTestResult>` → `POST /api/supervisor/twilio-config/test`

**Formularz (ReactiveFormsModule):**
Pola formularza:
- `accountSid` – input text, required, walidacja: `pattern(/^AC[0-9a-fA-F]{32}$/)`, label "Account SID"
- `authToken` – input password (type="password"), required przy tworzeniu, przy edycji placeholder "••••••••...{ostatnie 4 znaki}" (nie wstrzykiwać zamaskowanej wartości jako value), label "Auth Token"
- `apiKeySid` – input text, opcjonalne, label "API Key SID"
- `apiKeySecret` – input password, opcjonalne przy edycji (analogicznie jak authToken), label "API Key Secret"
- `twimlAppSid` – input text, opcjonalne, label "TwiML App SID"
- `phoneNumber` – input text, opcjonalne, walidacja: `pattern(/^\+[1-9]\d{7,14}$/)`, label "Numer telefonu (E.164)", placeholder "+48XXXXXXXXX"
- `statusCallbackUrl` – input text, opcjonalne, label "Status Callback URL"

**UX masked inputs (authToken, apiKeySecret):**
- Przy ładowaniu istniejącego configu: pola hasła wyświetlają placeholder z maską (np. "••••••••...a3f2") ale value formularza jest puste (wymagane ponowne wpisanie przy aktualizacji)
- Checkbox "Zmień token" lub ikona edycji odblokowuje pole do wpisania nowej wartości
- Przy PUT: jeśli pole hasła pozostaje puste, backend nie aktualizuje sekretu (logika po stronie BE – lub wysyłamy flagę `changeAuthToken: false`)

**Przyciski i akcje:**
- "Zapisz" – submit formularza → `PUT /api/supervisor/twilio-config` → snackbar sukces
- "Anuluj" – reset formularza do stanu załadowanego
- "Usuń konfigurację" – dialog potwierdzenia → `DELETE /api/supervisor/twilio-config` → powrót do stanu "brak konfiguracji"
- "Testuj połączenie" – `POST /api/supervisor/twilio-config/test` → wskaźnik statusu: zielona ikona checkmark + "Połączenie OK" lub czerwona X + komunikat błędu z Twilio

**Wskaźnik statusu połączenia:**
- Signal `connectionTestResult = signal<TwilioConnectionTestResult | null>(null)`
- Wyświetlany pod przyciskiem "Testuj połączenie"
- Auto-ukrycie po 30s lub po edycji formularza

**Nawigacja:**
Dodać pozycję "Integracja Twilio" (lub "Ustawienia telefonii") do menu supervisora – sekcja "Ustawienia" lub "Integracje". Ikona: telefon lub chmura.

**Kryteria akceptacji:**
- [ ] Komponent standalone (`ChangeDetectionStrategy.OnPush`, `ReactiveFormsModule`)
- [ ] Formularz ładuje istniejącą konfigurację przy wejściu na stronę (`GET /api/supervisor/twilio-config`)
- [ ] Gdy brak konfiguracji (204): formularz wyświetla stan "Brak konfiguracji" z przyciskiem "Skonfiguruj"
- [ ] Walidacja `accountSid` (pattern AC + 32 hex) – błąd inline przy niepoprawnym formacie
- [ ] Walidacja `phoneNumber` (E.164) – błąd inline przy niepoprawnym formacie
- [ ] Pola hasła (authToken, apiKeySecret) nie zwracają zamaskowanej wartości jako value formularza
- [ ] Przycisk "Zapisz" nieaktywny gdy formularz invalid lub pristine (brak zmian)
- [ ] "Testuj połączenie" wyświetla wynik testu z kolorowym wskaźnikiem (zielony/czerwony)
- [ ] "Usuń konfigurację" wymaga potwierdzenia w dialogu przed DELETE
- [ ] Snackbar po zapisaniu: "Konfiguracja Twilio zapisana pomyślnie"
- [ ] Snackbar po usunięciu: "Konfiguracja Twilio usunięta"
- [ ] Pozycja menu supervisora "Integracja Twilio" nawiguje do komponentu
- [ ] Klucze i18n dla wszystkich tekstów (przygotuj klucze w `pl.json`, `en.json`, `de.json`)
- [ ] Komponent widoczny tylko dla roli SUPERVISOR (guard lub `*ngIf` na roli)

---

### FE-067 – Pole "Numer prezentacji" w formularzu kampanii

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** BE-060 (API kampanii z polem `caller_id`), FE-015 (formularz kampanii)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-07
**Blokuje:** brak
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

**Opis:**
Rozszerzenie formularza tworzenia i edycji kampanii (`CampaignFormComponent`) o opcjonalne pole "Numer prezentacji" (caller ID) dla kampanii wychodzących głosowych (`type = OUTBOUND_VOICE`).

**Zmiany w `CampaignFormComponent`:**
- Dodanie kontrolki `callerId` do `FormGroup`:
  ```typescript
  callerId: new FormControl<string | null>(null, [
    Validators.pattern(/^\+[1-9]\d{7,14}$/)
  ])
  ```
- Pole widoczne tylko gdy `type === 'OUTBOUND_VOICE'` (ukryte dla email kampanii)
- Label: "Numer prezentacji (opcjonalny)"
- Placeholder: numer domyślny tenanta (odczytany z `TwilioConfigService.getConfig()` → `phoneNumber`, lub tekst "Domyślny numer tenanta" gdy brak konfiguracji)
- Hint pod polem: "Pozostaw puste aby użyć domyślnego numeru tenanta. Format E.164, np. +48123456789"
- Walidacja: `pattern(/^\+[1-9]\d{7,14}$/)` tylko gdy wartość jest podana (nie jest required)

**Zmiany w modelu/serwisie:**
- `CampaignRequest` DTO (TypeScript): dodanie opcjonalnego `callerId?: string | null`
- `CampaignResponse` DTO: dodanie `callerId: string | null`
- `CampaignService.createCampaign()` i `updateCampaign()`: przekazywanie `callerId` w body

**Kryteria akceptacji:**
- [ ] Pole "Numer prezentacji" widoczne w formularzu kampanii dla `type = OUTBOUND_VOICE`
- [ ] Pole ukryte dla `type = OUTBOUND_EMAIL`
- [ ] Walidacja formatu E.164 inline (błąd przy niepoprawnym formacie, brak błędu gdy puste)
- [ ] Hint informuje o domyślnym numerze tenanta
- [ ] `POST /api/supervisor/campaigns` z `callerId: "+48123456789"` – pole wysyłane w body
- [ ] `POST /api/supervisor/campaigns` z pustym polem – `callerId: null` wysyłany lub pole pominięte
- [ ] Formularz edycji kampanii ładuje istniejący `callerId` z API i wyświetla w polu
- [ ] Klucze i18n dla etykiety, placeholder i hint (`supervisor.campaignForm.callerId.*`)

---

### FE-068 – Dropdown aktywnych numerów Twilio: reużywalny komponent i integracja

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** BE-061 (endpoint listowania numerów), FE-066 (formularz konfiguracji Twilio), FE-067 (formularz kampanii)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-07
**Blokuje:** brak
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

**Opis:**
Reużywalny komponent `TwilioPhoneNumberSelectComponent` zastępujący ręczne pole tekstowe dla numeru telefonu Twilio. Pobiera aktywne numery z `GET /api/supervisor/twilio-config/phone-numbers` i wyświetla je jako `<mat-select>` (lub natywny `<select>`). Używany w dwóch miejscach: formularz konfiguracji Twilio (FE-066) i formularz kampanii (FE-067).

**Komponent `TwilioPhoneNumberSelectComponent`:**
- Selector: `app-twilio-phone-number-select`
- Standalone, implementuje `ControlValueAccessor` (działa jako pole formularza reaktywnego)
- Inputs:
  - `placeholder: string` – tekst gdy brak wyboru (np. "Wybierz numer" / "Domyślny numer tenanta")
  - `required: boolean` – czy pole jest wymagane (false = opcjonalne, jak w kampanii)
- Stan ładowania: skeleton/spinner dopóki lista nie wróci z API
- Stan błędu: komunikat "Nie można pobrać numerów z Twilio" z przyciskiem "Spróbuj ponownie" gdy API zwróci 502
- Stan pusty: komunikat "Brak skonfigurowanych numerów w koncie Twilio" gdy lista jest pusta
- Stan brak konfiguracji: komunikat "Najpierw skonfiguruj konto Twilio" gdy API zwróci 404
- Każda opcja wyświetla: `{friendlyName} — {phoneNumber}` (np. "Contact Center PL — +48123456789")
- Wartość formularza: string `phoneNumber` w formacie E.164 (nie `sid`)

**Integracja w `TwilioConfigComponent` (FE-066):**
- Zastąp pole tekstowe "Numer telefonu" (typ `<input>`) komponentem `app-twilio-phone-number-select` z `required: true`
- Po załadowaniu formularza, jeśli `config.phoneNumber` jest ustawiony, komponent pre-selekcjonuje odpowiednią opcję

**Integracja w `CampaignFormComponent` (FE-067):**
- Zastąp pole tekstowe "Numer prezentacji" komponentem `app-twilio-phone-number-select` z `required: false`
- Opcja "— Domyślny numer tenanta —" jako pierwsza pozycja listy (wartość `null`)
- Komponenty ładuje listę numerów tylko gdy `type === 'OUTBOUND_VOICE'` (lazy load)

**Serwis `TwilioConfigService` (rozszerzenie istniejącego):**
```typescript
getPhoneNumbers(): Observable<TwilioPhoneNumberDto[]> {
  return this.http.get<{ phoneNumbers: TwilioPhoneNumberDto[] }>(
    '/api/supervisor/twilio-config/phone-numbers'
  ).pipe(map(r => r.phoneNumbers));
}
```

**Kryteria akceptacji:**
- [ ] Komponent `app-twilio-phone-number-select` implementuje `ControlValueAccessor` i działa z `FormControl`
- [ ] Wyświetla spinner podczas ładowania listy z API
- [ ] Wyświetla opcje w formacie `{friendlyName} — {phoneNumber}`
- [ ] Wyświetla komunikat błędu z przyciskiem "Spróbuj ponownie" gdy API zwróci 502
- [ ] Wyświetla komunikat o braku konfiguracji gdy API zwróci 404
- [ ] W `TwilioConfigComponent` pole "Numer telefonu" jest selectem z aktywnych numerów (required)
- [ ] W `CampaignFormComponent` pole "Numer prezentacji" jest selectem z opcją null jako pierwszą (not required)
- [ ] W formularzu kampanii lista ładowana tylko dla `type = OUTBOUND_VOICE`
- [ ] Klucze i18n dla wszystkich komunikatów (`supervisor.twilioPhoneSelect.*`)

---

## MODUL: Retry i callback w kampaniach wychodzących (EPIC-21)

### FE-069 – Aktualizacja widoku listy kontaktów kampanii — nowe statusy `NOT_REACHED` i `CALLBACK`

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Status:** ⏳ Do zrobienia
**Zależy od:** DB-032, BE-063, BE-064
**Blokuje:** –
**Epic:** EPIC-21 Retry i callback w kampaniach wychodzących

**Opis:**

Lista kontaktów kampanii (`CampaignContactsComponent` lub analogiczny) wyświetla status każdego rekordu. Po dodaniu statusów `NOT_REACHED` i `CALLBACK` widok musi je poprawnie etykietować i kolorować. Ponadto dla rekordów `NO_ANSWER` i `CALLBACK` należy pokazać czas kolejnej próby.

**Zmiany:**

### 1. Mapowanie statusów na etykiety i kolory

| Status        | Etykieta PL          | Kolor (chip/badge)     |
|---------------|----------------------|------------------------|
| `PENDING`     | Oczekuje             | szary (neutral)        |
| `DIALING`     | Wybieranie           | niebieski (info)       |
| `CONNECTED`   | Połączony            | zielony (success)      |
| `COMPLETED`   | Zakończono           | zielony (success)      |
| `NO_ANSWER`   | Brak odpowiedzi      | pomarańczowy (warning) |
| `NOT_REACHED` | Niedodzwoniony       | czerwony (error)       |
| `CALLBACK`    | Oddzwonienie         | fioletowy (accent)     |
| `FAILED`      | Błąd połączenia      | czerwony (error)       |
| `SKIPPED`     | Pominięto            | szary (neutral)        |
| `ERROR`       | Błąd techniczny      | czerwony (error)       |

### 2. Kolumna „Próby" i „Następna próba"

W tabeli kontaktów dodać dwie nowe kolumny (lub rozszerzyć istniejącą kolumnę statusu):

- **Próby**: `attempt_count / max_attempts` — wyświetlaj tylko dla statusów innych niż PENDING
  - Np. `2/3`
  - Wymagana zmiana w API response DTO: `CampaignContactResponse` musi zawierać `attemptCount` i `maxAttempts`
    - `maxAttempts` pochodzi z Campaign, może być dodane do endpointu `GET /api/campaigns/{id}/contacts` jako pole w metadata response lub dołączone do każdego rekordu
- **Następna próba** (`next_attempt_at`): wyświetlaj dla statusów `NO_ANSWER` i `CALLBACK`
  - Format: data i godzina względna (`za 45 min`, `jutro 09:15`)
  - Wymagana zmiana: `CampaignContactResponse` musi zawierać `nextAttemptAt` (Instant / ISO-8601)

### 3. Filtr statusów w UI

Rozszerzyć filtr `status` (select/chips) o nowe wartości:
- Dodać: `NOT_REACHED`, `CALLBACK`
- Zachować istniejące: `PENDING`, `DIALING`, `NO_ANSWER`, `COMPLETED`, `FAILED`, `SKIPPED`, `ERROR`

**Zmiany backendowe wymagane przez FE-069 (mogą być zrealizowane w ramach tego zadania lub osobnego bugfix):**

`CampaignContactResponse` (BE) musi zostać rozszerzony o:
```java
public record CampaignContactResponse(
    UUID recordId,
    String phone,
    String firstName,
    String lastName,
    Map<String, String> customFields,
    String status,
    String dispositionCode,
    Instant createdAt,
    int attemptCount,         // nowe
    Instant nextAttemptAt     // nowe (nullable)
) {}
```

Odpowiednie kolumny (`attempt_count`, `next_attempt_at`) muszą być dołączone do SELECT w `CampaignContactRepository.findByCampaign()`.

**Kryteria akceptacji:**
- [ ] Status `NOT_REACHED` wyświetla etykietę "Niedodzwoniony" w kolorze czerwonym
- [ ] Status `CALLBACK` wyświetla etykietę "Oddzwonienie" w kolorze fioletowym
- [ ] Dla rekordów `NO_ANSWER` i `CALLBACK` wyświetlana jest kolumna "Następna próba" z formatowanym czasem
- [ ] Kolumna "Próby" pokazuje `attempt_count / max_attempts`
- [ ] Filtr statusów zawiera opcje `NOT_REACHED` i `CALLBACK`
- [ ] `CampaignContactResponse` zawiera `attemptCount` i `nextAttemptAt`

---

### FE-070 – Konfiguracja parametru retry w formularzu kampanii — `retryDelayMinutes`

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Status:** ⏳ Do zrobienia
**Zależy od:** –
**Blokuje:** –
**Epic:** EPIC-21 Retry i callback w kampaniach wychodzących

**Opis:**

Formularz tworzenia/edycji kampanii (`CampaignFormComponent`) nie eksponuje parametru `retryDelayMinutes` — jest on zapisywany w DB z wartością domyślną 60 min i nigdy nie można go zmienić z UI. Pole `maxAttempts` najprawdopodobniej jest już w formularzu, ale należy to zweryfikować.

**Zmiany w `CampaignFormComponent`:**

### 1. Pole `retryDelayMinutes` — czas między próbami (gdy brak odpowiedzi)

- Typ: liczba całkowita, zakres: 1–1440 minut (1 min – 24h)
- Label: "Czas między próbami (minuty)" lub z select presetów:
  - 15 min, 30 min, 1h (domyślna), 2h, 4h, 8h, 24h + pole własne
- Widoczność: tylko dla kampanii `OUTBOUND_VOICE`
- Sekcja: „Ustawienia dialera" obok `maxAttempts`

### 2. Pole `maxAttempts` — weryfikacja i ewentualne dodanie

Sprawdzić czy `maxAttempts` jest już w formularzu. Jeśli nie — dodać:
- Typ: liczba całkowita, zakres: 1–10
- Label: "Maksymalna liczba prób"
- Widoczność: tylko dla `OUTBOUND_VOICE`

### 3. Walidacja pól

```typescript
retryDelayMinutes: new FormControl(60, [
    Validators.required,
    Validators.min(1),
    Validators.max(1440)
]),
maxAttempts: new FormControl(3, [
    Validators.required,
    Validators.min(1),
    Validators.max(10)
])
```

### 4. Tooltips / help text

- `retryDelayMinutes`: "Ile minut odczekać przed kolejną próbą połączenia gdy klient nie odbierze"
- `maxAttempts`: "Po ilu nieudanych próbach oznaczyć rekord jako Niedodzwoniony"

**Kryteria akceptacji:**
- [ ] Pole `retryDelayMinutes` dostępne w formularzu tworzenia i edycji kampanii
- [ ] Pole `maxAttempts` dostępne w formularzu (dodać jeśli brak)
- [ ] Oba pola widoczne tylko dla `type = 'OUTBOUND_VOICE'`
- [ ] Walidacja: `retryDelayMinutes` w zakresie 1–1440, `maxAttempts` w zakresie 1–10
- [ ] Wartości zapisywane na `POST /api/campaigns` i `PUT /api/campaigns/{id}`
- [ ] Wartości ładowane poprawnie przy edycji istniejącej kampanii
- [ ] Helptexty/tooltips przy obu polach

---

## MODUŁ: Ad hoc połączenia i email z panelu agenta

### FE-071 – Przycisk „Zadzwoń" na karcie klienta i w szufladzie szczegółów

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** FE-040 (AgentCustomersTabComponent), BE-067
**Status:** [ ] Do zrobienia
**Odniesienie PRD:** Agent desktop – kontakt z klientem

**Opis:**
Dodanie przycisku „Zadzwoń" do `AgentCustomerCardComponent` oraz do szuflady szczegółów klienta w `AgentCustomersTabComponent`. Po kliknięciu:
- Jeśli klient ma jeden numer telefonu — od razu inicjuje połączenie
- Jeśli klient ma wiele numerów — wyświetla mini-dropdown z wyborem numeru
- Po wyborze numeru: wywołuje `POST /api/telephony/calls/outbound` i pokazuje powiadomienie sukcesu/błędu

**Implementacja:**
- `AgentCustomerCardComponent`: nowy `@Output() initiateCall = new EventEmitter<{customer, phoneNumber}>()`
- Nowy przycisk z ikoną telefonu (`customer-card__btn--call`) — zielony akcent, widoczny gdy `customer.phone.length > 0`
- `AgentCustomersTabComponent.onInitiateCall()`: wywołuje nowy `OutboundCallService.call(phoneNumber, customerId)`
- `OutboundCallService` (nowy, `providedIn: 'root'`): HTTP `POST /api/telephony/calls/outbound`, zwraca `Observable<{contactId, callId}>`
- Szuflada szczegółów: analogiczny przycisk obok istniejącego „Zamów oddzwonienie"
- Po sukcesie: `NotificationService.success('Połączenie zainicjowane')` + opcjonalne przejście do zakładki desktop
- i18n: `agent.customers.initiateCall`, `agent.customers.selectPhone`, `agent.customers.callInitiated`, `agent.customers.callError`

**Kryteria akceptacji:**
- [ ] Przycisk „Zadzwoń" widoczny na karcie gdy klient ma ≥1 numer telefonu
- [ ] Klient z 1 numerem: kliknięcie od razu inicjuje połączenie (bez dropdown)
- [ ] Klient z wieloma numerami: dropdown z listą numerów przed wywołaniem
- [ ] Wywołanie `POST /api/telephony/calls/outbound` z poprawnym `phoneNumber` i `customerId`
- [ ] Sukces: powiadomienie toast
- [ ] Błąd HTTP: powiadomienie z komunikatem błędu
- [ ] Brak numeru: przycisk niewidoczny lub disabled
- [ ] Tłumaczenia w pl.json i en.json

---

### FE-072 – Modal „Wyślij email" do klienta ad hoc

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** FE-040 (AgentCustomersTabComponent), BE-068
**Status:** [ ] Do zrobienia
**Odniesienie PRD:** Agent desktop – kontakt z klientem

**Opis:**
Nowy komponent `AdHocEmailModalComponent` — formularz wysyłki nowego emaila do klienta, otwierany z karty klienta lub szuflady szczegółów. Formularz zawiera: pole `Do` (pre-wypełnione adresem klienta, edytowalne), `Temat`, edytor treści HTML (lub textarea), przycisk „Wyślij".

**Plik:** `frontend/src/app/features/agent/pages/customers/adhoc-email-modal/adhoc-email-modal.component.ts`

**Implementacja:**
- Standalone component, `ChangeDetectionStrategy.OnPush`
- `@Input() customer: CustomerSummary` — do pre-wypełnienia pola `Do`
- `@Output() sent = new EventEmitter<void>()`
- `@Output() cancelled = new EventEmitter<void>()`
- Formularz reaktywny (`ReactiveFormsModule`): `toAddress` (@Email, pre-fill z `customer.email[0]` jeśli istnieje), `subject` (required, max 500), `bodyHtml` (textarea, required)
- Jeśli klient ma wiele emaili: dropdown wyboru adresu (zamiast ręcznego wpisywania)
- Serwis: `EmailService.sendOutbound(toAddress, subject, bodyHtml, customerId)` — nowa metoda wywołująca `POST /api/email/messages/outbound`
- Po sukcesie: `sent.emit()`, zamknięcie modala, toast sukcesu
- Po błędzie: komunikat w modalu (nie toast) — np. „Brak konfiguracji SMTP" lub „Błąd wysyłki"
- Integracja w `AgentCustomersTabComponent`:
  - Nowy `@Output() sendEmail` na `AgentCustomerCardComponent` → `onSendEmail(customer)` w tabie
  - Signal `emailCustomer = signal<CustomerSummary | null>(null)`
  - `@if (emailCustomer()) { <app-adhoc-email-modal ... /> }`
- Szuflada szczegółów: przycisk „Wyślij email" obok „Zamów oddzwonienie"
- i18n: `agent.customers.sendEmail`, `agent.adhocEmail.title`, `agent.adhocEmail.toLabel`, `agent.adhocEmail.subjectLabel`, `agent.adhocEmail.bodyLabel`, `agent.adhocEmail.send`, `agent.adhocEmail.cancel`, `agent.adhocEmail.sent`, `agent.adhocEmail.errorNoSmtp`, `agent.adhocEmail.errorSend`

**Kryteria akceptacji:**
- [ ] Przycisk „Wyślij email" widoczny na karcie gdy klient ma ≥1 adres email
- [ ] Kliknięcie otwiera modal z pre-wypełnionym polem `Do`
- [ ] Klient z wieloma emailami: dropdown wyboru adresu
- [ ] Walidacja formularza: `toAddress` poprawny email, `subject` i `bodyHtml` niepuste
- [ ] Kliknięcie „Wyślij" wywołuje `POST /api/email/messages/outbound`
- [ ] Sukces: modal zamknięty, toast z potwierdzeniem
- [ ] Błąd SMTP: komunikat wewnątrz modala (nie toast), modal pozostaje otwarty
- [ ] Anulowanie: modal zamknięty, brak wywołania API
- [ ] Tłumaczenia w pl.json i en.json
- [ ] Brak emaila klienta: przycisk niewidoczny lub disabled

---

## MODUŁ: Notatki do kontaktów (EPIC-22)

### FE-073 – Wyświetlanie notatki w widoku szczegółów kontaktu (`contact-detail-modal`)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-069 (pole `notes` w `ContactResponse`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Epic:** EPIC-22 Notatki do kontaktów

**Opis:**
Modal szczegółów kontaktu (`contact-detail-modal.component.html`) zawiera już martwy kod wyświetlający `c.notes` (sekcja "Status", linie ~142-147):
```html
@if (c.notes) {
  <div class="contact-dl__row contact-dl__row--tall">
    <dt class="contact-dl__term">{{ 'contactDetailModal.fieldNotes' | transloco }}</dt>
    <dd class="contact-dl__desc contact-dl__desc--notes">{{ c.notes }}</dd>
  </div>
}
```
Po wdrożeniu BE-069 ten kod "ożyje" automatycznie. Zadanie obejmuje weryfikację i dopracowanie UX dla długich notatek — `dd` musi obsługiwać wieloliniowy tekst bez obcinania.

**Zakres pracy:**

1. **`contact-detail-modal.component.scss`** — sprawdź czy klasa `.contact-dl__desc--notes` istnieje; jeśli nie, dodaj:
   ```scss
   .contact-dl__desc--notes {
     white-space: pre-wrap;   // zachowaj znaki nowej linii z notatki
     word-break: break-word;  // łam długie słowa
     max-height: 200px;
     overflow-y: auto;
     line-height: 1.5;
   }
   ```

2. **`contact-detail-modal.component.ts`** — sprawdź czy model `ContactResponse` w `src/app/core/models/contact.model.ts` ma pole `notes?: string | null`. Jeśli tak — brak zmian w TypeScript. Jeśli nie — dodaj.

3. **Tłumaczenia** — klucz `contactDetailModal.fieldNotes` powinien już istnieć w pl.json (sprawdź). Upewnij się że jest też w `en.json`, `de.json`, `uk.json`.

**Kryteria akceptacji:**
- [ ] Kontakt z notatką: pole „Notatka" widoczne w sekcji Status modala
- [ ] Kontakt bez notatki: sekcja notatki nie renderuje się (warunek `@if (c.notes)`)
- [ ] Długa notatka (>500 znaków): wyświetla się w scrollowalnym obszarze, nie rozrywa layoutu
- [ ] Notatka z wieloma liniami (`\n`): znaki nowej linii są respektowane (nie zwinięte w jedną linię)
- [ ] Klucz `contactDetailModal.fieldNotes` przetłumaczony w pl, en, de, uk

---

### FE-074 – Notatki z ostatnich kontaktów w panelu klienta (`customer-panel`) — truncation + expand

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-070 (pole `notes` w `ContactSummaryDto` → `CustomerLookupResponse`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Epic:** EPIC-22 Notatki do kontaktów

**Opis:**
Panel klienta (`cc-customer-panel`) wyświetla ostatnie 5 kontaktów klienta w sekcji "Historia kontaktów". Aktualnie pokazuje: ikonę kanału, dyspozycję i datę. Po wdrożeniu BE-070 API zwróci `notes` przy każdym kontakcie. Notatki mogą być długie — nie mogą być wyświetlane w całości w zwartej liście.

**Rozwiązanie UX:** każdy element historii pokazuje maksymalnie 2 linie notatki z przyciskiem „Rozwiń / Zwiń" gdy notatka jest dłuższa.

**Pliki do modyfikacji:**

1. **`customer-profile.model.ts`** (`src/app/core/models/`) — dodaj `notes?: string | null` do `ContactHistoryItem`:
   ```typescript
   export interface ContactHistoryItem {
     id: string;
     channel: 'PHONE' | 'EMAIL' | 'CHAT' | 'SOCIAL';
     date: string;
     disposition: string;
     agentName?: string;
     notes?: string | null;  // nowe pole
   }
   ```

2. **`customer-panel.component.ts`** — dodaj mechanizm expand/collapse:
   ```typescript
   protected readonly expandedNotes = signal<Set<string>>(new Set());

   protected toggleNote(contactId: string): void {
     this.expandedNotes.update(set => {
       const next = new Set(set);
       next.has(contactId) ? next.delete(contactId) : next.add(contactId);
       return next;
     });
   }

   protected isNoteExpanded(contactId: string): boolean {
     return this.expandedNotes().has(contactId);
   }

   protected hasLongNote(note: string | null | undefined): boolean {
     return !!note && note.length > 120;
   }
   ```

3. **`customer-panel.component.html`** — w bloku `@for (item of profile()!.recentContacts; ...)`, wewnątrz `<div class="cp__history-details">`, po `cp__history-agent` dodaj:
   ```html
   @if (item.notes) {
     <div class="cp__history-note-wrap">
       <p class="cp__history-note"
          [class.cp__history-note--collapsed]="!isNoteExpanded(item.id)">
         {{ item.notes }}
       </p>
       @if (hasLongNote(item.notes)) {
         <button
           type="button"
           class="cp__history-note-toggle"
           (click)="toggleNote(item.id)">
           {{ isNoteExpanded(item.id)
               ? ('agent.customerPanel.noteCollapse' | transloco)
               : ('agent.customerPanel.noteExpand' | transloco) }}
         </button>
       }
     </div>
   }
   ```

4. **`customer-panel.component.scss`** — dodaj style:
   ```scss
   .cp__history-note-wrap {
     margin-top: 4px;
   }

   .cp__history-note {
     font-size: 0.75rem;
     color: var(--color-text-secondary);
     line-height: 1.4;
     white-space: pre-wrap;
     word-break: break-word;
     margin: 0;

     &--collapsed {
       display: -webkit-box;
       -webkit-line-clamp: 2;
       -webkit-box-orient: vertical;
       overflow: hidden;
     }
   }

   .cp__history-note-toggle {
     background: none;
     border: none;
     padding: 0;
     font-size: 0.7rem;
     color: var(--color-primary);
     cursor: pointer;
     margin-top: 2px;

     &:hover {
       text-decoration: underline;
     }
   }
   ```

5. **Tłumaczenia** (`public/i18n/pl.json`, `en.json`, `de.json`, `uk.json`) — w sekcji `agent.customerPanel` dodaj:
   - `noteExpand`: `"Pokaż więcej"` / `"Show more"` / `"Mehr anzeigen"` / `"Показати більше"`
   - `noteCollapse`: `"Pokaż mniej"` / `"Show less"` / `"Weniger anzeigen"` / `"Показати менше"`

**Kryteria akceptacji:**
- [ ] Kontakt bez notatki: brak elementu notatki w historii
- [ ] Kontakt z krótką notatką (≤120 znaków): wyświetlana w całości, bez przycisku "Pokaż więcej"
- [ ] Kontakt z długą notatką (>120 znaków): widoczne 2 linie + przycisk "Pokaż więcej"
- [ ] Kliknięcie "Pokaż więcej": pełna notatka widoczna, przycisk zmienia się na "Pokaż mniej"
- [ ] Kliknięcie "Pokaż mniej": notatka zwinięta z powrotem do 2 linii
- [ ] Notatka z `\n`: znaki nowej linii zachowane (`white-space: pre-wrap`)
- [ ] Stan expand/collapse niezależny per element historii (rozwinięcie jednego nie wpływa na inne)
- [ ] Tłumaczenia `noteExpand` i `noteCollapse` w pl, en, de, uk

---

## MODUŁ: Historia etapów kontaktu (EPIC-23)

### FE-075 – Sekcja „Historia kontaktu" w modalu szczegółów kontaktu (`contact-detail-modal`)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-073 (endpoint `GET /api/contacts/{id}/events`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Epic:** EPIC-23 Historia etapów kontaktu

**Opis:**
Modal szczegółów kontaktu (`contact-detail-modal`) pokazuje aktualnie: kanał, kierunek, status, czas trwania, dyspozycję, notatkę, nagranie i powiązane kontakty. Nowa sekcja „Historia kontaktu" prezentuje pełny przepływ kontaktu przez etapy: IVR → Kolejka → Agent → (Hold → Agent), z datą rozpoczęcia i czasem trwania każdego etapu.

**Projekt UI (mockup sekcji):**
```
── Historia kontaktu ──────────────────────────────────────
  [IVR]      10:00:05    2m 30s    "Powitanie"
  [KOLEJKA]  10:02:35    5m 10s    "Sprzedaż"
  [AGENT]    10:07:45    6m 00s    "Jan Kowalski"
  [WSTRZYM.] 10:09:45    1m 30s
  [AGENT]    10:11:15    3m 30s    "Jan Kowalski"
───────────────────────────────────────────────────────────
```
Każdy wiersz: ikona/badge etapu | czas startu | czas trwania (lub „w toku" gdy `ended_at = null`) | kontekst z metadata.

**Pliki do stworzenia/modyfikacji:**

**1. `src/app/core/models/contact.model.ts`** — dodaj interfejsy:
```typescript
export interface ContactEventResponse {
  eventId: string;
  stage: 'IVR' | 'VOICEBOT' | 'QUEUE' | 'AGENT' | 'ON_HOLD' | 'CONSULTING' | 'TRANSFER';
  startedAt: string;           // ISO 8601
  endedAt: string | null;      // null = etap aktywny; dla TRANSFER = startedAt
  durationSeconds: number | null;
  metadata: Record<string, string>;
  // IVR/VOICEBOT: ivr_tree_name, outcome (ESCALATED|COMPLETED|ERROR)
  // QUEUE: queue_name
  // AGENT: agent_name
  // CONSULTING: target, transfer_type
  // TRANSFER: target, transfer_type, target_agent_name (nullable)
}
```

**2. `src/app/features/agent/services/contact.service.ts`** (lub shared) — dodaj metodę:
```typescript
getContactEvents(contactId: string): Observable<ContactEventResponse[]> {
  return this.http.get<ContactEventResponse[]>(
    `${environment.apiUrl}/contacts/${contactId}/events`
  );
}
```

**3. `contact-detail-modal.component.ts`** — dodaj logikę pobierania historii:
```typescript
protected readonly events = signal<ContactEventResponse[]>([]);
protected readonly eventsState = signal<'idle' | 'loading' | 'loaded' | 'error'>('idle');

// W ngOnInit lub po załadowaniu kontaktu:
private loadEvents(contactId: string): void {
  this.eventsState.set('loading');
  this.contactService.getContactEvents(contactId)
    .pipe(takeUntilDestroyed(this.destroyRef))
    .subscribe({
      next: (list) => { this.events.set(list); this.eventsState.set('loaded'); },
      error: () => this.eventsState.set('error'),
    });
}

// Helper do formatowania czasu trwania:
protected formatDuration(seconds: number | null): string {
  if (seconds === null) return '—';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

// Helper do etykiety etapu (i18n przez klucze transloco):
protected getStageLabel(stage: ContactEventResponse['stage']): string {
  const labels: Record<string, string> = {
    IVR: 'IVR',
    VOICEBOT: 'Bot',
    QUEUE: 'Kolejka',
    AGENT: 'Agent',
    ON_HOLD: 'Wstrzym.',
    CONSULTING: 'Konsult.',
    TRANSFER: 'Transfer',
  };
  return labels[stage] ?? stage;
}

// Helper do metadanych (nazwa IVR / bota / kolejki / agenta / celu transferu):
protected getStageContext(event: ContactEventResponse): string {
  const m = event.metadata;
  if (event.stage === 'TRANSFER' || event.stage === 'CONSULTING') {
    const who = m['target_agent_name'] ?? m['target'] ?? '';
    const type = m['transfer_type'] ? ` (${m['transfer_type']})` : '';
    return who + type;
  }
  return m['ivr_tree_name'] ?? m['queue_name'] ?? m['agent_name'] ?? '';
}
```

**4. `contact-detail-modal.component.html`** — nowa sekcja po sekcji „Status", przed nagraniem:
```html
<!-- Section: Historia kontaktu -->
@if (eventsState() === 'loading') {
  <section class="contact-section" aria-labelledby="contact-section-events">
    <h3 id="contact-section-events" class="contact-section__title">
      {{ 'contactDetailModal.sectionEvents' | transloco }}
    </h3>
    <div class="events-skeleton">
      @for (i of [1, 2, 3]; track i) {
        <div class="skeleton-block skeleton-block--event"></div>
      }
    </div>
  </section>
}

@if (eventsState() === 'loaded' && events().length > 0) {
  <section class="contact-section" aria-labelledby="contact-section-events">
    <h3 id="contact-section-events" class="contact-section__title">
      {{ 'contactDetailModal.sectionEvents' | transloco }}
    </h3>
    <ol class="contact-events" aria-label="Historia etapów kontaktu">
      @for (event of events(); track event.eventId) {
        <li class="contact-event contact-event--{{ event.stage.toLowerCase() }}">
          <span class="contact-event__badge">
            {{ getStageLabel(event.stage) }}
          </span>
          <span class="contact-event__time">
            {{ event.startedAt | date: 'HH:mm:ss' }}
          </span>
          <span class="contact-event__duration">
            {{ event.endedAt ? formatDuration(event.durationSeconds) : ('contactDetailModal.eventInProgress' | transloco) }}
          </span>
          @if (getStageContext(event)) {
            <span class="contact-event__context">{{ getStageContext(event) }}</span>
          }
        </li>
      }
    </ol>
  </section>
}
```

**5. `contact-detail-modal.component.scss`** — style dla listy etapów:
```scss
.contact-events {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.contact-event {
  display: grid;
  grid-template-columns: 90px 70px 80px 1fr;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--color-surface-secondary, #f5f5f5);
  font-size: 0.8rem;

  &__badge {
    font-weight: 600;
    font-size: 0.7rem;
    padding: 2px 6px;
    border-radius: 4px;
    text-align: center;
    text-transform: uppercase;
    letter-spacing: 0.03em;
  }

  &__time   { font-variant-numeric: tabular-nums; }
  &__duration { color: var(--color-text-secondary); font-variant-numeric: tabular-nums; }
  &__context  { color: var(--color-text-secondary); font-size: 0.75rem; truncate: ellipsis; }

  &--ivr        .contact-event__badge { background: #e3f2fd; color: #1565c0; }  // niebieski
  &--voicebot   .contact-event__badge { background: #ede7f6; color: #4527a0; }  // fioletowy
  &--queue      .contact-event__badge { background: #fff8e1; color: #f57f17; }  // żółty
  &--agent      .contact-event__badge { background: #e8f5e9; color: #2e7d32; }  // zielony
  &--on_hold    .contact-event__badge { background: #fff3e0; color: #e65100; }  // pomarańczowy
  &--consulting .contact-event__badge { background: #e0f7fa; color: #006064; }  // cyjanowy
  &--transfer   .contact-event__badge { background: #f5f5f5; color: #424242; }  // szary
}

.skeleton-block--event {
  height: 36px;
  border-radius: 6px;
}
```

**6. Tłumaczenia** (`public/i18n/pl.json`, `en.json`, `de.json`, `uk.json`) — w sekcji `contactDetailModal`:
- `sectionEvents`: `"Historia kontaktu"` / `"Contact timeline"` / `"Kontaktverlauf"` / `"Історія контакту"`
- `eventInProgress`: `"w toku"` / `"in progress"` / `"laufend"` / `"у процесі"`

**Uwagi implementacyjne:**
- `loadEvents()` wywołaj po załadowaniu kontaktu (`loadState() === 'loaded'`), nie przy inicjalizacji komponentu — unikaj zbędnego requestu gdy kontakt nie załaduje się poprawnie
- Sekcja historii nie wyświetla się gdy `eventsState === 'idle'` lub `eventsState === 'error'` (cicha degradacja — brak historii nie powinien blokować wyświetlania pozostałych danych kontaktu)
- Lista `events()` posortowana po `startedAt ASC` przez backend — frontend nie sortuje

**Kryteria akceptacji:**
- [ ] Po otwarciu modalu kontaktu z historią → sekcja „Historia kontaktu" widoczna z listą etapów
- [ ] Każdy etap: badge z nazwą etapu, godzina startu, czas trwania, kontekst (IVR/kolejka/agent)
- [ ] Etap bez `ended_at` (aktywny) → wyświetla „w toku" zamiast czasu trwania
- [ ] Kontakt bez historii etapów → sekcja się nie renderuje
- [ ] Stany ładowania: skeleton podczas ładowania historii
- [ ] Badge etapów mają różne kolory: IVR=niebieski, VOICEBOT=fioletowy, QUEUE=żółty, AGENT=zielony, ON_HOLD=pomarańczowy, CONSULTING=cyjanowy, TRANSFER=szary
- [ ] Tłumaczenia `sectionEvents` i `eventInProgress` w pl, en, de, uk
- [ ] Brak regresji: pozostałe sekcje modalu działają bez zmian

---

## EPIC-24 Transfer połączenia: agent i kolejka

Rozszerzenie panelu transferu w softphonie agenta o dwa nowe cele: **Agent** (transfer BLIND + konsultacja ATTENDED) i **Kolejka** (transfer BLIND). Obecny UI obsługuje tylko transfer na numer telefonu.

---

### FE-076 – Rozszerzenie modelu i serwisu softphone o typ celu transferu

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-077, BE-078
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-077, FE-078, FE-079, FE-080
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Warstwa modelu i serwisu przed pracą na UI. Definiuje nowe typy, modele i sygnatury metod w `SoftphoneService`.

**1. Rozszerzenie `call-session.model.ts`:**

```typescript
export type TransferMode       = 'BLIND' | 'ATTENDED';
export type TransferTargetType = 'PHONE' | 'AGENT' | 'QUEUE';

export interface TransferAgentItem {
  agentId:    string;
  firstName:  string;
  lastName:   string;
  status:     'AVAILABLE' | 'BUSY' | 'BREAK' | 'ON_CALL';
  queueNames: string[];
}

export interface TransferQueueItem {
  queueId:         string;
  name:            string;
  waitingContacts: number;
  availableAgents: number;
}
```

**2. Nowe sygnatury w `SoftphoneService`:**

```typescript
// Pobieranie list do panelu transferu
fetchTransferAgents(): Observable<TransferAgentItem[]>
fetchTransferQueues(): Observable<TransferQueueItem[]>

// Transfer do agenta
initiateBlindTransferToAgent(callId: string, agentId: string): void
initiateAttendedTransferToAgent(callId: string, agentId: string): void

// Transfer do kolejki (tylko BLIND)
initiateBlindTransferToQueue(callId: string, queueId: string): void
```

**3. Implementacja HTTP — zastąpienie `/api/dev/telephony/simulate`:**

Wszystkie metody transfer wywołują:
```
POST /api/telephony/calls/{callId}/transfer
Body: { transferType, targetType, phoneNumber?, agentId?, queueId? }
```

Attended bridge wywołuje:
```
POST /api/telephony/calls/{callId}/bridge/{secondCallId}
```

**Kryteria akceptacji:**
- [ ] `TransferTargetType`, `TransferAgentItem`, `TransferQueueItem` wyeksportowane z modelu
- [ ] `fetchTransferAgents()` → `GET /api/telephony/transfer/agents`
- [ ] `fetchTransferQueues()` → `GET /api/telephony/transfer/queues`
- [ ] `initiateBlindTransferToAgent()`, `initiateAttendedTransferToAgent()` → `POST .../transfer` z `targetType=AGENT`
- [ ] `initiateBlindTransferToQueue()` → `POST .../transfer` z `targetType=QUEUE`
- [ ] Bridge (attended complete) → `POST .../bridge/{secondCallId}`
- [ ] `npm run lint` i `npm test` przechodzą

---

### FE-077 – Panel transferu: zakładki „Telefon / Agent / Kolejka"

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** FE-076
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-078, FE-079
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Przebudowa layoutu panelu transferu w `softphone.component.html` — dodanie selektora typu celu jako zakładek. Zawartość pod zakładkami zostanie zaimplementowana w FE-078 i FE-079.

**Selektor trybu celu (`TransferTargetType`):**

```html
<div class="transfer-panel__target-tabs">
  <button
    *ngFor="let tab of transferTargetTabs"
    [class.active]="transferTargetType() === tab.value"
    (click)="setTransferTargetType(tab.value)"
    type="button">
    {{ tab.label }}
  </button>
</div>
```

Zakładki (label / value):
- `"Telefon"` / `PHONE` — istniejący formularz z inputem numer + tryby BLIND/ATTENDED
- `"Agent"` / `AGENT` — lista agentów (FE-078)
- `"Kolejka"` / `QUEUE` — lista kolejek, tylko BLIND (FE-079)

**Nowy sygnał w komponencie:**

```typescript
protected readonly transferTargetType = signal<TransferTargetType>('PHONE');

protected setTransferTargetType(type: TransferTargetType): void {
  this.transferTargetType.set(type);
  this.transferTarget.set('');
  this.attendedConnected.set(false);
}
```

**Warunkowe renderowanie:**

```html
@if (transferTargetType() === 'PHONE') {
  <!-- istniejący formularz tel -->
}
@if (transferTargetType() === 'AGENT') {
  <app-transfer-agent-list ... />
}
@if (transferTargetType() === 'QUEUE') {
  <app-transfer-queue-list ... />
}
```

**Styl zakładek** — spójny z istniejącymi przyciskami trybu (BLIND/ATTENDED); aktywna zakładka podkreślona kolorem `--color-primary`.

**Selektor trybu BLIND/ATTENDED** — wyświetlany tylko gdy `transferTargetType() !== 'QUEUE'`.

**Kryteria akceptacji:**
- [ ] Panel transferu zawiera trzy zakładki: Telefon / Agent / Kolejka
- [ ] Kliknięcie zakładki resetuje stan wyboru (target, attendedConnected)
- [ ] Zakładka QUEUE ukrywa selektor BLIND/ATTENDED (queue = zawsze BLIND)
- [ ] Zakładka Telefon renderuje istniejący formularz bez zmian funkcjonalnych
- [ ] Styl zakładek spójny z resztą panelu
- [ ] `npm run lint` przechodzi

---

### FE-078 – Lista agentów w panelu transferu z wyszukiwaniem i statusem

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** FE-076, FE-077, BE-075
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-080
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Nowy standalone komponent `TransferAgentListComponent` wyświetlający listę agentów dostępnych do transferu z wyszukiwaniem i wskaźnikiem statusu.

**Selekto komponentu:** `app-transfer-agent-list`

**Inputs / Outputs:**

```typescript
// Input
transferMode = input.required<TransferMode>(); // BLIND | ATTENDED

// Output
agentSelected = output<{ agentId: string; mode: TransferMode }>();
```

**Template (szkielet):**

```html
<div class="transfer-agent-list">
  <input
    type="search"
    placeholder="Szukaj agenta..."
    (input)="searchQuery.set($event.target.value)" />

  @if (loadState() === 'loading') {
    <div class="transfer-agent-list__skeleton">
      <!-- 4 skeleton rows -->
    </div>
  }

  @for (agent of filteredAgents(); track agent.agentId) {
    <button
      class="transfer-agent-list__item"
      [class]="'status--' + agent.status.toLowerCase()"
      (click)="selectAgent(agent)">
      <span class="transfer-agent-list__status-dot"></span>
      <span class="transfer-agent-list__name">
        {{ agent.firstName }} {{ agent.lastName }}
      </span>
      <span class="transfer-agent-list__queues">
        {{ agent.queueNames.join(', ') }}
      </span>
    </button>
  }

  @empty {
    <p class="transfer-agent-list__empty">Brak dostępnych agentów</p>
  }
</div>
```

**Logika:**

```typescript
private readonly agents = signal<TransferAgentItem[]>([]);
protected readonly searchQuery  = signal('');
protected readonly loadState    = signal<'idle' | 'loading' | 'loaded' | 'error'>('idle');

protected readonly filteredAgents = computed(() =>
  this.agents().filter(a =>
    `${a.firstName} ${a.lastName}`.toLowerCase()
      .includes(this.searchQuery().toLowerCase())
  )
);

ngOnInit(): void {
  this.loadState.set('loading');
  this.softphoneService.fetchTransferAgents()
    .pipe(takeUntilDestroyed(this.destroyRef))
    .subscribe({ next: data => { this.agents.set(data); this.loadState.set('loaded'); },
                 error: ()   => this.loadState.set('error') });
}

protected selectAgent(agent: TransferAgentItem): void {
  this.agentSelected.emit({ agentId: agent.agentId, mode: this.transferMode() });
}
```

**Kolory statusu (dot):**
- `AVAILABLE` → zielony (`--color-success`)
- `BUSY` / `ON_CALL` → pomarańczowy (`--color-warning`)
- `BREAK` → żółty

**Kryteria akceptacji:**
- [ ] Lista agentów ładuje się po przełączeniu zakładki „Agent"
- [ ] Pole wyszukiwania filtruje po imieniu i nazwisku (case-insensitive)
- [ ] Wskaźnik statusu (dot) z odpowiednim kolorem
- [ ] Skeleton podczas ładowania (4 wiersze)
- [ ] Pusta lista → komunikat „Brak dostępnych agentów"
- [ ] Kliknięcie agenta emituje `agentSelected` z `agentId` i aktualnym `transferMode`
- [ ] Komponent nie przechowuje stanu po odmontowaniu (destroyRef)
- [ ] `npm run lint` przechodzi

---

### FE-079 – Lista kolejek w panelu transferu

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** FE-076, FE-077, BE-076
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-080
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Nowy standalone komponent `TransferQueueListComponent` — lista kolejek dostępnych jako cel transferu. Transfer do kolejki jest zawsze BLIND (bez konsultacji).

**Selektor komponentu:** `app-transfer-queue-list`

**Output:**

```typescript
queueSelected = output<{ queueId: string }>();
```

**Template (szkielet):**

```html
<div class="transfer-queue-list">
  @for (queue of queues(); track queue.queueId) {
    <button
      class="transfer-queue-list__item"
      (click)="selectQueue(queue)">
      <span class="transfer-queue-list__name">{{ queue.name }}</span>
      <span class="transfer-queue-list__stats">
        <span class="badge badge--waiting">
          {{ queue.waitingContacts }} czeka
        </span>
        <span class="badge badge--agents">
          {{ queue.availableAgents }} agentów
        </span>
      </span>
    </button>
  }

  @empty {
    <p class="transfer-queue-list__empty">Brak dostępnych kolejek</p>
  }
</div>
```

**Logika:**

```typescript
protected readonly queues    = signal<TransferQueueItem[]>([]);
protected readonly loadState = signal<'idle' | 'loading' | 'loaded' | 'error'>('idle');

ngOnInit(): void {
  this.loadState.set('loading');
  this.softphoneService.fetchTransferQueues()
    .pipe(takeUntilDestroyed(this.destroyRef))
    .subscribe({ next: data => { this.queues.set(data); this.loadState.set('loaded'); },
                 error: ()   => this.loadState.set('error') });
}

protected selectQueue(queue: TransferQueueItem): void {
  this.queueSelected.emit({ queueId: queue.queueId });
}
```

**Kryteria akceptacji:**
- [ ] Lista kolejek ładuje się po przełączeniu zakładki „Kolejka"
- [ ] Każda pozycja: nazwa kolejki, liczba oczekujących, liczba dostępnych agentów
- [ ] Skeleton podczas ładowania
- [ ] Pusta lista → komunikat „Brak dostępnych kolejek"
- [ ] Kliknięcie emituje `queueSelected` z `queueId`
- [ ] `npm run lint` przechodzi

---

### FE-080 – Integracja panelu transferu z nowym API

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** FE-076, FE-077, FE-078, FE-079, BE-077, BE-078
**Status:** ⬜ Nie rozpoczęte
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Spinanie wszystkich elementów: obsługa outputów z list agentów/kolejek w `SoftphoneComponent`, wywołanie właściwych metod serwisu, aktualizacja stanu sesji po transferze. Zastąpienie wywołań `/api/dev/telephony/simulate` właściwymi endpointami.

**Zmiany w `softphone.component.ts`:**

```typescript
// Obsługa outputu z listy agentów
protected onAgentSelected(event: { agentId: string; mode: TransferMode }): void {
  if (event.mode === 'BLIND') {
    this.softphoneService.initiateBlindTransferToAgent(
      this.session()!.contactId, event.agentId);
  } else {
    this.softphoneService.initiateAttendedTransferToAgent(
      this.session()!.contactId, event.agentId);
    this.attendedConnected.set(true);
  }
}

// Obsługa outputu z listy kolejek (zawsze BLIND)
protected onQueueSelected(event: { queueId: string }): void {
  this.softphoneService.initiateBlindTransferToQueue(
    this.session()!.contactId, event.queueId);
}
```

**Attended transfer do agenta — faza 2 (Complete / Cancel):**

Istniejące przyciski „Ukończ" i „Anuluj" działają tak samo niezależnie od targetType — warunek wyświetlania: `attendedConnected() === true` (bez zmian).

**Usunięcie zależności od `/api/dev/telephony/simulate`:**

- `SoftphoneService` — usuń wywołania `POST /api/dev/telephony/simulate` z metod transfer
- Zastąp wywołaniami `POST /api/telephony/calls/{callId}/transfer`
- Bridge zastąp `POST /api/telephony/calls/{callId}/bridge/{secondCallId}`

**Kryteria akceptacji:**
- [ ] Wybór agenta BLIND → kontakt przechodzi w `TRANSFERRING` → `ENDED`
- [ ] Wybór agenta ATTENDED → stan `TRANSFERRING`, pojawia się przycisk „Ukończ" / „Anuluj"
- [ ] „Ukończ" → bridge API → `ENDED`
- [ ] „Anuluj" → cancel → `ACTIVE`
- [ ] Wybór kolejki → kontakt `TRANSFERRING` → `ENDED`
- [ ] Żadne wywołanie do `/api/dev/telephony/simulate` w ścieżce transferu
- [ ] `npm run lint`, `npm test` przechodzą

---

## MODUŁ: Przypisywanie agentów do kampanii (EPIC-25)

### FE-081 – Usunięcie pola `queueId` z formularza kampanii

**Typ:** Refactor
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-079
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-082
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst:**
Formularz tworzenia kampanii (`campaign-form.component`) zawiera obowiązkowy dropdown wyboru kolejki (`queueId: ['', Validators.required]`). Po EPIC-25 kampanie nie są powiązane z kolejką — to pole należy usunąć.

**Zakres zmian:**

1. **`campaign.model.ts`**:
   - `CreateCampaignRequest`: usuń `queueId: string` (pole obowiązkowe) — całkowite usunięcie
   - `UpdateCampaignRequest`: usuń `queueId?: string`
   - `Campaign`: `queueId?: string` — zostaje jako opcjonalne (dane historyczne)

2. **`campaign-form.component.ts`**:
   - Usuń `queueId: ['', Validators.required]` z `form`
   - Usuń import i wstrzyknięcie `QueueService`
   - Usuń sygnały `queuesLoading`, `queues`
   - Usuń metodę `loadQueues()`
   - Usuń wywołanie `loadQueues()` z `ngOnInit()`
   - Usuń `get queueIdError()` getter
   - W `onSubmit()` przy create: usuń `queueId: raw.queueId!`

3. **`campaign-form.component.html`**:
   - Usuń sekcję HTML z dropdownem kolejki (`<select formControlName="queueId">`) i jej etykietę
   - Usuń blok błędu `queueIdError`

4. **i18n** (pliki transloco `pl.json`, `en.json`):
   - Usuń klucze `supervisor.campaignForm.errors.queueRequired` i `supervisor.campaigns.queue` (jeśli istnieją)

**Kryteria akceptacji:**
- [ ] Formularz tworzenia kampanii nie zawiera pola kolejki
- [ ] `POST /api/campaigns` wysyłany bez `queueId`
- [ ] Formularz edycji kampanii (DRAFT) nie zawiera pola kolejki
- [ ] `npm run lint`, `npm test` przechodzą

---

### FE-082 – Modal zarządzania przypisaniem agentów do kampanii (trójpoziomowy)

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-080, BE-084, FE-081
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-083
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Opis:**
Modal zarządzania przypisaniem agentów do kampanii — identyczny w strukturze UI z `queue-agents-modal.component`. Obsługuje trzy poziomy przypisania: `allAgents`, grupy agentów i agenci bezpośredni. Używa jednego endpointu `PUT /api/campaigns/{id}/assignment` do atomowej podmiany całego przypisania.

**Nowe pliki:**
```
features/supervisor/pages/campaigns/campaign-assignment-modal/
  campaign-assignment-modal.component.ts
  campaign-assignment-modal.component.html
  campaign-assignment-modal.component.scss
```

**Model danych — rozszerzenie `campaign.model.ts`:**
```typescript
export interface CampaignAssignment {
  campaignId: string;
  allAgents: boolean;
  directAgents: AgentSummary[];
  groups: AgentGroupSummary[];
}

export interface AgentSummary {
  agentId: string;
  firstName: string;
  lastName: string;
  email: string;
}

export interface AgentGroupSummary {
  groupId: string;
  name: string;
  memberCount: number;
}

export interface UpdateCampaignAssignmentRequest {
  allAgents: boolean;
  directAgentIds: string[];
  groupIds: string[];
}
```

**Rozszerzenie `CampaignService` (frontend):**
```typescript
getCampaignAssignment(campaignId: string): Observable<CampaignAssignment>
updateCampaignAssignment(campaignId: string, req: UpdateCampaignAssignmentRequest): Observable<CampaignAssignment>
```

**Komponent `CampaignAssignmentModalComponent`:**
```typescript
@Component({ selector: 'app-campaign-assignment-modal', ... })
export class CampaignAssignmentModalComponent implements OnInit {
  readonly campaign = input.required<Campaign>();
  readonly closed = output<void>();

  readonly assignment = signal<CampaignAssignment | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);

  // Lokalne kopie do edycji (nie mutują assignment sygnału bezpośrednio)
  readonly allAgents = signal(false);
  readonly selectedAgentIds = signal<string[]>([]);
  readonly selectedGroupIds = signal<string[]>([]);

  // Listy dostępne do wyboru (GET /api/users + GET /api/agent-groups)
  readonly availableAgents = signal<AgentSummary[]>([]);
  readonly availableGroups = signal<AgentGroupSummary[]>([]);
}
```

**Layout modalu — analogiczny do `queue-agents-modal`:**

1. **Przełącznik "Wszyscy agenci"** (toggle) — gdy ON: sekcje grup i agentów ukryte (wystarczy flaga)
2. **Sekcja "Grupy agentów"** — lista przypisanych grup z `memberCount`, przycisk usuwania; poniżej dropdown/search do dodawania grup
3. **Sekcja "Agenci bezpośredni"** — lista przypisanych agentów, przycisk usuwania; poniżej dropdown/search do dodawania agentów
4. **Ostrzeżenie** gdy `allAgents=false` i obie listy puste: "Brak przypisania — dialer nie będzie dzwonił, panel manualny nie wyświetli rekordów tej kampanii."
5. **Przycisk "Zapisz"** — wywołuje `PUT /api/campaigns/{id}/assignment` z aktualnym stanem (atomowa podmiana)

**Integracja w `campaign-info.component`:**
- Przycisk "Zarządzaj agentami" otwiera `CampaignAssignmentModalComponent`
- Po zamknięciu modalu z sukcesem: odświeżenie `assignedAgentsCount` w widoku kampanii

**Kryteria akceptacji:**
- [ ] Modal ładuje aktualny stan przypisania z `GET /api/campaigns/{id}/assignment`
- [ ] Toggle "Wszyscy agenci" — włączenie ukrywa sekcje grup/agentów, nie usuwa istniejących przypisań (tylko flaga)
- [ ] Dodanie grupy: pojawia się w sekcji grup z `memberCount`
- [ ] Usunięcie grupy: usuwana z lokalnej listy (bez natychmiastowego zapisu — zapis przez "Zapisz")
- [ ] Dodanie agenta: pojawia się w sekcji agentów bezpośrednich
- [ ] Usunięcie agenta: usuwany z lokalnej listy
- [ ] "Zapisz" → `PUT /api/campaigns/{id}/assignment` → success toast + zamknięcie modalu
- [ ] Ostrzeżenie widoczne gdy `allAgents=false` i obie listy puste
- [ ] Komponent standalone, bez NgModules
- [ ] `npm run lint`, `npm test` przechodzą

---

### FE-083 – Wyświetlenie stanu przypisania agentów na liście kampanii i w szczegółach

**Typ:** Feature
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** FE-082, BE-080
**Status:** ⬜ Nie rozpoczęte
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Opis:**
Stan przypisania agentów powinien być widoczny bez otwierania modalu. `CampaignResponse` rozszerzony o pole sumaryczne; frontend pokazuje badge z informacją o trybie przypisania.

**Zmiany:**

1. **`CampaignResponse` (backend)** — rozszerzyć o:
   ```java
   boolean allAgents,
   int assignedAgentsCount   // 0 gdy allAgents=true (nie liczymy) lub suma direct+groups
   ```
   Backend zlicza w `CampaignService.getCampaign()` przez `CampaignAssignmentRepository`.
   Gdy `allAgents=true` → `assignedAgentsCount` = -1 (sygnał "wszyscy") lub specjalna wartość.

2. **`Campaign` model (frontend)**:
   ```typescript
   allAgents?: boolean;
   assignedAgentsCount?: number; // -1 = all agents mode
   ```

3. **`campaign-list.component.html`** — badge przy nazwie kampanii:
   ```html
   @if (campaign.allAgents) {
     <span class="badge badge--info">Wszyscy agenci</span>
   } @else if ((campaign.assignedAgentsCount ?? 0) === 0) {
     <span class="badge badge--warning" title="Brak agentów — dialer nieaktywny">
       Brak agentów
     </span>
   } @else {
     <span class="badge badge--agents">{{ campaign.assignedAgentsCount }} agentów</span>
   }
   ```

4. **`campaign-info.component.html`** — wiersz w sekcji konfiguracji:
   ```
   Agenci: [Wszyscy] / [X agentów] / [⚠ Brak przypisania]  [Zarządzaj]
   ```

**Kryteria akceptacji:**
- [ ] Badge z liczbą agentów widoczny na liście kampanii
- [ ] Badge w kolorze ostrzegawczym gdy `assignedAgentsCount === 0`
- [ ] Liczba agentów aktualizuje się po zamknięciu modalu przypisania (FE-082)
- [ ] `npm run lint` przechodzi

---

### FE-084 – Ukrycie zakładki „Kolejka" w panelu transferu dla połączeń wychodzących

**Typ:** Bug fix / UX
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** FE-076 (TransferTargetType), FE-077 (panel transferu)
**Status:** ⬜ Nie rozpoczęte
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst — zweryfikowany stan:**
`SoftphoneComponent.transferTargetTabs` jest stałą tablicą `['PHONE', 'AGENT', 'QUEUE']` — niezależnie od kierunku połączenia. Pole `tab.direction` (`'INBOUND' | 'OUTBOUND'`) jest już dostępne w komponencie i ustawiane przez `contact-tab.store.ts` w momencie tworzenia zakładki. Dla połączeń wychodzących (`OUTBOUND`) transfer do kolejki jest niemożliwy — kolejka przyjmuje tylko ruch przychodzący.

**Zmiana w `softphone.component.ts`:**

```typescript
// Przed (stała tablica):
protected readonly transferTargetTabs: TransferTargetType[] = ['PHONE', 'AGENT', 'QUEUE'];

// Po (computed signal filtrujący QUEUE dla OUTBOUND):
protected readonly transferTargetTabs = computed<TransferTargetType[]>(() =>
  this.tab.direction === 'OUTBOUND'
    ? ['PHONE', 'AGENT']
    : ['PHONE', 'AGENT', 'QUEUE']
);
```

Przy okazji: jeśli `transferTargetType()` jest aktualnie `'QUEUE'` i zmieni się kierunek na `OUTBOUND` (edge case), zresetować do `'PHONE'`. W praktyce zakładka powstaje raz z ustalonym kierunkiem i nie zmienia się — reset nie jest konieczny, wystarczy computed.

**Brak zmian w szablonie** — `@for (tab of transferTargetTabs; track tab)` już iteruje po aktualnej wartości; computed signal obsługuje zmianę automatycznie.

**Kryteria akceptacji:**
- [ ] Dla połączeń `OUTBOUND`: panel transferu zawiera tylko zakładki „Telefon" i „Agent" (brak „Kolejka")
- [ ] Dla połączeń `INBOUND`: panel transferu zawiera wszystkie trzy zakładki bez zmian
- [ ] Zmiana nie wpływa na działanie transferu BLIND/ATTENDED na zakładkach PHONE i AGENT
- [ ] `npm run lint`, `npm test` przechodzą

---

## MODUŁ: Historia prób wydzwonienia rekordu kampanii (EPIC-25)

### FE-085 – Nawigacja z rekordu kampanii do historii kontaktów (prób wydzwonienia)

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-085, FE-082
**Status:** ⬜ Nie rozpoczęte
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst:**
Widok `campaign-contacts.component` (modal listy rekordów kampanii) wyświetla rekordy z pola `CampaignContact`, w tym `attemptCount` i `status`. Po BE-085 `CampaignContactResponse` zawiera `lastContactId`. Potrzebny jest sposób przejścia do widoku szczegółów konkretnej próby oraz do listy wszystkich prób dla rekordu.

**Model danych — rozszerzenie `campaign.model.ts`:**
```typescript
export interface CampaignContact {
  // ... istniejące pola bez zmian ...
  lastContactId: string | null;   // null gdy brak prób — nowe pole
}

export interface ContactAttempt {
  contactId: string;
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
  status: string;
  dispositionCode: string | null;
  agentId: string | null;
  // standardowe pola ContactResponse
}
```

**Rozszerzenie `CampaignService` (frontend):**
```typescript
getContactAttempts(
  campaignId: string,
  recordId: string
): Observable<ContactAttempt[]>
// GET /api/campaigns/{campaignId}/contacts/{recordId}/attempts
```

**Zmiany w `campaign-contacts.component`:**

### UI per rekord — przycisk historii prób

W szablonie `campaign-contacts.component.html` dodaj przy każdym rekordzie:

```html
@if (contact.attemptCount > 0) {
  <button
    class="btn-attempts"
    type="button"
    (click)="showAttempts(contact)"
    [attr.aria-label]="'supervisor.campaigns.showAttempts' | transloco"
  >
    {{ contact.attemptCount }}
    {{ 'supervisor.campaigns.attempts' | transloco }}
  </button>
}
```

### Stan rozwinięcia historii

```typescript
// Sygnał: który rekord ma rozwiniętą historię
readonly expandedRecordId = signal<string | null>(null);
readonly attempts = signal<ContactAttempt[]>([]);
readonly attemptsLoading = signal(false);

showAttempts(contact: CampaignContact): void {
  if (this.expandedRecordId() === contact.recordId) {
    this.expandedRecordId.set(null); // zwiń
    return;
  }
  this.expandedRecordId.set(contact.recordId);
  this.attemptsLoading.set(true);
  this.campaignService.getContactAttempts(this.campaign().campaignId, contact.recordId)
    .pipe(
      catchError(() => of([])),
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.attemptsLoading.set(false))
    )
    .subscribe(a => this.attempts.set(a));
}
```

### Lista prób (rozwijana pod rekordem)

Inline pod wierszem rekordu, gdy `expandedRecordId() === contact.recordId`:

```html
@if (expandedRecordId() === contact.recordId) {
  <div class="attempts-panel">
    @if (attemptsLoading()) {
      <div class="skeleton skeleton--short"></div>
    } @else if (attempts().length === 0) {
      <p class="attempts-empty">{{ 'supervisor.campaigns.noAttempts' | transloco }}</p>
    } @else {
      @for (attempt of attempts(); track attempt.contactId) {
        <div class="attempt-row" (click)="openContactDetail(attempt.contactId)">
          <span class="attempt-date">{{ attempt.startedAt | date:'dd.MM.yyyy HH:mm' }}</span>
          <span class="attempt-status attempt-status--{{ attempt.status | lowercase }}">
            {{ attempt.status }}
          </span>
          <span class="attempt-duration">
            @if (attempt.durationSeconds) {
              {{ attempt.durationSeconds | duration }}
            } @else {
              —
            }
          </span>
          <span class="attempt-disposition">{{ attempt.dispositionCode ?? '—' }}</span>
          <svg class="attempt-chevron" aria-hidden="true" viewBox="0 0 24 24" fill="currentColor">
            <path d="M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z"/>
          </svg>
        </div>
      }
    }
  </div>
}
```

### Nawigacja do szczegółów kontaktu

Po kliknięciu próby — otwieramy istniejący `contact-detail-modal` (jeśli istnieje w kontekście supervisora) lub przekierowujemy do widoku kontaktów z filtrem `contactId`:

```typescript
openContactDetail(contactId: string): void {
  // Emit event do rodzica (campaign-info lub campaign-list)
  // rodzic otwiera contact-detail-modal z danym contactId
  this.contactSelected.emit(contactId);
}

// Output:
readonly contactSelected = output<string>();
```

**Kryteria akceptacji:**
- [ ] Rekord z `attemptCount > 0`: widoczny przycisk „X prób" (liczba prób z `attemptCount`)
- [ ] Kliknięcie przycisku: rozwinięcie listy prób ładowanej z `GET /.../attempts`
- [ ] Ponowne kliknięcie: zwinięcie listy
- [ ] Lista prób: data, czas trwania, status, kod dyspozycji w każdym wierszu, posortowane od najnowszych
- [ ] Kliknięcie próby: emituje `contactSelected` → rodzic otwiera contact-detail-modal
- [ ] Rekord z `attemptCount === 0`: brak przycisku historii
- [ ] Loading skeleton podczas ładowania prób
- [ ] Pusta lista prób (edge case API): komunikat „Brak prób"
- [ ] `npm run lint`, `npm test` przechodzą

---

## EPIC-26: AI-Powered Conversation Summary

### FE-086 – `AiSummaryService`: serwis Angular do generowania podsumowania AI

**Typ:** Frontend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-090 (endpoint `POST /api/contacts/{contactId}/ai-summary`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** FE-087, FE-088, FE-089
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Opis:**
Standalone Angular service `AiSummaryService` (`shared/services/ai-summary.service.ts`) obsługujący komunikację z backendem.

```typescript
export interface AiSummaryResponse {
  summary: string;
  modelUsed: string;
  tokensUsed: number;
}

@Injectable({ providedIn: 'root' })
export class AiSummaryService {
  private readonly http = inject(HttpClient);

  generateSummary(contactId: string): Observable<AiSummaryResponse> {
    return this.http.post<AiSummaryResponse>(
      `/api/contacts/${contactId}/ai-summary`,
      null
    );
  }
}
```

**Obsługa błędów HTTP:**
- 422: rzuć `AiConfigNotSetError` — frontend wyświetli komunikat „Skonfiguruj dostawcę AI w ustawieniach"
- 502: rzuć `AiServiceUnavailableError` — „Serwis AI tymczasowo niedostępny. Spróbuj ponownie."
- Inne: propaguj do komponentu

**Kryteria akceptacji:**
- [x] Serwis wstrzykiwalny jako standalone (`providedIn: 'root'`)
- [x] `generateSummary()` zwraca `Observable<AiSummaryResponse>`
- [x] 422 → rzuca `AiConfigNotSetError` z komunikatem dla użytkownika
- [x] 502 → rzuca `AiServiceUnavailableError`
- [x] Testy jednostkowe Vitest: happy path + 422 + 502
- [x] `npm run lint`, `npm test` przechodzą

---

### FE-087 – Przycisk „Generuj podsumowanie AI" na formularzu dyspozycji

**Typ:** Frontend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** FE-086 (AiSummaryService)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** FE-089
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Opis:**
Rozszerzenie istniejącego formularza dyspozycji (disposition form) po zakończeniu kontaktu telefonicznego. Agent ma możliwość wygenerowania podsumowania AI przed zapisaniem dyspozycji.

**Lokalizacja:** komponent formularza dyspozycji widoczny po zakończeniu rozmowy (agent desktop — zakładka/modal po rozłączeniu).

**UI — nowa sekcja „Podsumowanie AI":**

```html
<!-- Sekcja AI Summary — widoczna gdy contactId jest dostępny -->
<section class="ai-summary-section">
  <div class="ai-summary-header">
    <span class="ai-summary-label">Podsumowanie AI</span>
    <button
      type="button"
      class="btn-ai-generate"
      [disabled]="aiLoading()"
      (click)="generateAiSummary()"
      aria-label="Generuj podsumowanie AI"
    >
      @if (aiLoading()) {
        <span class="spinner spinner--xs" aria-hidden="true"></span>
        Generowanie…
      } @else {
        <svg class="icon-sparkle" aria-hidden="true">…</svg>
        Generuj podsumowanie AI
      }
    </button>
  </div>

  @if (aiError()) {
    <p class="ai-error" role="alert">{{ aiError() }}</p>
  }

  @if (aiSummary()) {
    <textarea
      class="ai-summary-textarea"
      [(ngModel)]="aiSummary"
      rows="4"
      placeholder="Podsumowanie zostanie wygenerowane…"
      aria-label="Podsumowanie AI — możesz edytować przed zapisaniem"
    ></textarea>
    <p class="ai-summary-meta">
      Model: {{ aiModelUsed() }} · {{ aiTokensUsed() }} tokenów
    </p>
  }
</section>
```

**Logika komponentu (sygnały):**

```typescript
readonly aiLoading = signal(false);
readonly aiSummary = signal<string | null>(null);
readonly aiModelUsed = signal<string | null>(null);
readonly aiTokensUsed = signal<number | null>(null);
readonly aiError = signal<string | null>(null);

generateAiSummary(): void {
  if (!this.contactId()) return;
  this.aiLoading.set(true);
  this.aiError.set(null);
  this.aiSummaryService.generateSummary(this.contactId()!)
    .pipe(
      catchError((err: AiConfigNotSetError | AiServiceUnavailableError | unknown) => {
        this.aiError.set(err instanceof Error ? err.message : 'Nieznany błąd.');
        return EMPTY;
      }),
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.aiLoading.set(false))
    )
    .subscribe(res => {
      this.aiSummary.set(res.summary);
      this.aiModelUsed.set(res.modelUsed);
      this.aiTokensUsed.set(res.tokensUsed);
    });
}
```

**Zapis dyspozycji:** `aiSummary()` jest tylko informacyjne dla agenta — agent może edytować. Wartość tekstowa z textarea powinna być uwzględniona w payload zapisu dyspozycji (lub zapisana osobno przez serwis). Backend już przechowuje `contact.ai_summary` zapisany przez `BE-090` — w tym tasku frontend jedynie wyświetla wynik.

**Kryteria akceptacji:**
- [x] Przycisk „Generuj podsumowanie AI" widoczny na formularzu dyspozycji gdy `contactId` jest dostępny
- [x] Kliknięcie przycisku → spinner + tekst „Generowanie…" + przycisk disabled
- [x] Po sukcesie: textarea z podsumowaniem + metadane (model, tokeny)
- [x] Agent może edytować treść w textarea przed zapisaniem dyspozycji
- [x] Błąd 422 → komunikat „Skonfiguruj dostawcę AI w ustawieniach"
- [x] Błąd 502 → komunikat „Serwis AI tymczasowo niedostępny"
- [x] Ponowne kliknięcie „Generuj" nadpisuje poprzednie podsumowanie
- [x] `npm run lint`, `npm test` przechodzą

---

### FE-088 – Panel konfiguracji dostawcy AI w ustawieniach supervisora

**Typ:** Frontend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-088 (TenantAiConfigController), FE-086
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** —
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Opis:**
Nowa zakładka/sekcja „Konfiguracja AI" w panelu ustawień supervisora. Umożliwia ustawienie dostawcy AI, klucza API, modelu i opcjonalnego promptu systemowego.

**Routing:** `/supervisor/settings/ai-config` (standalone komponent z lazy loading)

**UI — formularz konfiguracji AI:**

```html
<form [formGroup]="form" (ngSubmit)="save()">
  <div class="form-group">
    <label for="provider">Dostawca AI</label>
    <select id="provider" formControlName="provider">
      <option value="ANTHROPIC">Anthropic (Claude)</option>
      <option value="OPENAI">OpenAI (GPT)</option>
      <option value="AZURE_OPENAI">Azure OpenAI</option>
    </select>
  </div>

  <div class="form-group">
    <label for="apiKey">Klucz API</label>
    <input type="password" id="apiKey" formControlName="apiKey"
           autocomplete="new-password"
           placeholder="Wprowadź klucz API (zostanie zaszyfrowany)"/>
    @if (maskedKey()) {
      <p class="api-key-hint">Aktualny klucz: {{ maskedKey() }}</p>
    }
  </div>

  <div class="form-group">
    <label for="modelName">Nazwa modelu</label>
    <input type="text" id="modelName" formControlName="modelName"
           placeholder="np. claude-opus-4-7 / gpt-4o"/>
  </div>

  <!-- Widoczne tylko dla AZURE_OPENAI -->
  @if (form.get('provider')?.value === 'AZURE_OPENAI') {
    <div class="form-group">
      <label for="azureEndpoint">Azure Endpoint URL</label>
      <input type="url" id="azureEndpoint" formControlName="azureEndpoint"/>
    </div>
    <div class="form-group">
      <label for="deploymentName">Nazwa deployment</label>
      <input type="text" id="deploymentName" formControlName="deploymentName"/>
    </div>
  }

  <div class="form-group">
    <label for="promptTemplate">Prompt systemowy (opcjonalny)</label>
    <textarea id="promptTemplate" formControlName="summaryPromptTemplate" rows="4"
              placeholder="Zostaw puste, aby użyć domyślnego promptu aplikacji."></textarea>
  </div>

  <div class="form-actions">
    <button type="submit" [disabled]="form.invalid || saving()">
      @if (saving()) { Zapisywanie… } @else { Zapisz konfigurację }
    </button>
    @if (hasConfig()) {
      <button type="button" class="btn-danger" (click)="deleteConfig()">
        Usuń konfigurację AI
      </button>
    }
  </div>
</form>
```

**Serwis `AiConfigService`** (`supervisor/services/ai-config.service.ts`):
- `getConfig(): Observable<AiConfigResponse | null>`
- `saveConfig(request: AiConfigRequest): Observable<AiConfigResponse>`
- `deleteConfig(): Observable<void>`

**Kryteria akceptacji:**
- [x] Formularz ładuje istniejącą konfigurację przy wejściu na stronę (404 → pusty formularz)
- [x] Pole „Klucz API" typu `password`; przy istniejącej konfiguracji wyświetla zamaskowany klucz `****xxxx`
- [x] Pola Azure widoczne tylko gdy wybrany dostawca `AZURE_OPENAI`
- [x] Zapis → toast sukcesu „Konfiguracja AI zapisana"
- [x] Usunięcie → potwierdzenie dialog → toast „Konfiguracja AI usunięta"
- [x] Walidacja: `provider` + `apiKey` + `modelName` wymagane; `azureEndpoint` wymagane dla Azure
- [x] `npm run lint`, `npm test` przechodzą

---

### FE-089 – Podsumowanie AI dla kanału email (widok obsługi emaila)

**Typ:** Frontend implementation
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** FE-086 (AiSummaryService), FE-087 (AiSummaryPanelComponent)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** —
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Opis:**
Rozszerzenie widoku obsługi kontaktu email przez agenta — analogiczny przycisk „Generuj podsumowanie AI" jak w FE-087. Umożliwia agentowi szybkie podsumowanie wątku emailowego przed wysłaniem odpowiedzi lub zapisaniem dyspozycji.

**Lokalizacja:** komponent widoku emaila (panel agenta — zakładka email, widok wątku z klientem).

**Różnica względem FE-087:** treść do podsumowania to wątek emailowy zamiast transkrypcji rozmowy — logika backendowa (BE-089) już obsługuje tę różnicę na podstawie `channel` kontaktu. Komponent frontendowy jest identyczny — wywołuje ten sam endpoint z `contactId`.

**Współdzielenie kodu:** Wyodrębnij sekcję AI summary do **osobnego standalone komponentu** `AiSummaryPanelComponent` (`shared/components/ai-summary-panel/`), który przyjmuje `@Input() contactId: string` i enkapsuluje całą logikę sygnałów oraz UI. Użyj go zarówno w FE-087 (dyspozycja telefon) jak i w FE-089 (email).

**Kryteria akceptacji:**
- [x] `AiSummaryPanelComponent` wyodrębniony jako standalone z `@Input() contactId`
- [x] FE-087 refaktoryzowany do użycia `AiSummaryPanelComponent`
- [x] Przycisk „Generuj podsumowanie AI" widoczny w widoku emaila gdy `contactId` jest dostępny
- [x] Zachowanie identyczne jak FE-087: spinner, textarea z wynikiem, obsługa błędów
- [x] `npm run lint`, `npm test` przechodzą
