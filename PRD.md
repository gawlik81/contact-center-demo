# System Contact Center – Product Requirements Document

**Wersja:** 1.0
**Data:** 2026-03-12
**Status:** Draft
**Autor:** Wygenerowano na podstawie sesji odkrywania wymagań

---

## Spis treści

1. [Streszczenie wykonawcze](#1-streszczenie-wykonawcze)
2. [Kontekst biznesowy i cele](#2-kontekst-biznesowy-i-cele)
3. [Persony użytkowników](#3-persony-użytkowników)
4. [Zakres projektu](#4-zakres-projektu)
5. [Wymagania funkcjonalne](#5-wymagania-funkcjonalne)
6. [Wymagania niefunkcjonalne](#6-wymagania-niefunkcjonalne)
7. [Architektura wysokiego poziomu](#7-architektura-wysokiego-poziomu)
8. [Integracje](#8-integracje)
9. [Model danych – wysokopoziomowo](#9-model-danych--wysokopoziomowo)
10. [Roadmapa i fazy wdrożenia](#10-roadmapa-i-fazy-wdrożenia)
11. [Kryteria sukcesu i KPI](#11-kryteria-sukcesu-i-kpi)
12. [Ryzyka i ograniczenia](#12-ryzyka-i-ograniczenia)
13. [Otwarte pytania i decyzje do podjęcia](#13-otwarte-pytania-i-decyzje-do-podjęcia)
14. [Appendix](#14-appendix)

---

## 1. Streszczenie wykonawcze

Projekt zakłada budowę wielokanałowej platformy Contact Center w modelu SaaS (Software as a Service), dostępnej dla wielu klientów jednocześnie (architektura multi-tenant). System umożliwia organizacjom profesjonalne zarządzanie zarówno kontaktami przychodzącymi (inbound) inicjowanymi przez klientów końcowych, jak i wychodzącymi (outbound) inicjowanymi przez organizację w celach takich jak telemarketing, windykacja czy obsługa posprzedażowa.

Platforma integruje w jednym miejscu kanały: telefonię (VoIP), pocztę elektroniczną (email) oraz media społecznościowe (social media), z planowanym rozszerzeniem o kanał RCS (SMS/MMS/VMS). Uzupełnieniem jest wbudowana baza klientów (profil klienta + historia kontaktów), moduł automatyzacji (chatbot, voicebot, IVR) oraz rozbudowana analityka z magazynem danych.

Docelowymi odbiorcami systemu są firmy potrzebujące profesjonalnego narzędzia do zarządzania relacjami z klientami przez wiele kanałów komunikacji jednocześnie.

---

## 2. Kontekst biznesowy i cele

### 2.1 Problem biznesowy

Organizacje zarządzające dużą liczbą kontaktów z klientami napotykają na szereg problemów:

- **Fragmentaryczność kanałów** – telefon, email i media społecznościowe obsługiwane są przez oddzielne, niepowiązane narzędzia, co utrudnia uzyskanie spójnego obrazu klienta.
- **Brak automatyzacji** – powtarzalne czynności (kwalifikacja rozmów, odpowiedzi na typowe pytania, inicjowanie kampanii) angażują zasoby ludzkie, które mogłyby być wykorzystane efektywniej.
- **Ograniczona analityka** – brak centralnego źródła danych uniemożliwia monitorowanie wydajności agentów, skuteczności kampanii i jakości obsługi.
- **Trudność skalowania** – brak elastycznego modelu SaaS zmusza klientów do kosztownych wdrożeń on-premise.

### 2.2 Stan obecny (As-Is)

Potencjalni klienci platformy korzystają dziś z rozproszonych narzędzi: osobne systemy do obsługi telefonicznej (np. tradycyjne centrale), osobne do email (np. skrzynki grupowe), bez centralnej bazy klientów lub z uproszczonymi arkuszami kalkulacyjnymi. Kampanie wychodzące prowadzone są ręcznie lub przy pomocy niepowiązanych systemów dialerowych.

### 2.3 Stan docelowy (To-Be)

Jedna platforma SaaS, w której agent widzi wszystkie kanały komunikacji z klientem w jednym interfejsie. Supervisor monitoruje pracę zespołu w czasie rzeczywistym i analizuje wyniki. Administrator zarządza całą infrastrukturą tenantów. Procesy wychodzące są zautomatyzowane przez kampanie z harmonogramem i dilerem progresywnym.

### 2.4 Cele biznesowe

| Cel | Opis |
|-----|------|
| C1 | Dostarczenie platformy SaaS gotowej do obsługi wielu niezależnych klientów (multi-tenant) |
| C2 | Unifikacja kanałów komunikacji (telefon, email, social media) w jednym interfejsie agenta |
| C3 | Automatyzacja powtarzalnych procesów kontaktu (IVR, chatbot, voicebot, kampanie outbound) |
| C4 | Zapewnienie pełnej zgodności z RODO/GDPR dla danych osobowych klientów z UE |
| C5 | Umożliwienie klientom SaaS integracji platformy z ich własnymi systemami przez REST API |
| C6 | Dostarczenie analityki i raportowania wspierającego decyzje biznesowe supervisorów |

---

## 3. Persony użytkowników

### 3.1 Persona 1: Administrator Systemu

| Atrybut | Opis |
|---------|------|
| **Rola** | Rola techniczna, zarządza całą platformą SaaS |
| **Zakres** | Wszystkie tenanty (klienci SaaS) |
| **Cele** | Utrzymanie stabilności platformy, konfiguracja nowych tenantów, zarządzanie integracjami, monitorowanie zasobów technicznych |
| **Potrzeby** | Dashboard techniczny z metrykami systemu, narzędzia do zarządzania tenantami, logi systemowe, konfiguracja integracji zewnętrznych |
| **Frustracje** | Brak widoczności problemów tenantów, trudność w izolacji błędów między tenantami |
| **Dostęp** | Pełny dostęp do wszystkich modułów i konfiguracji platformy |

**Kluczowe User Stories:**
- Jako Administrator, chcę tworzyć i konfigurować nowych tenantów, aby onboardować nowych klientów SaaS.
- Jako Administrator, chcę monitorować zasoby systemowe (CPU, pamięć, kolejki) dla każdego tenanta, aby wykrywać problemy wydajnościowe.
- Jako Administrator, chcę przeglądać logi systemowe i audytowe, aby diagnozować incydenty.
- Jako Administrator, chcę zarządzać globalnymi konfiguracjami integracji (dostawca telefonii, dostawcy social media), aby udostępniać je tenantom.

### 3.2 Persona 2: Supervisor

| Atrybut | Opis |
|---------|------|
| **Rola** | Rola biznesowa w kontekście jednego tenanta |
| **Zakres** | Agenci i kampanie przypisane do jego tenanta |
| **Cele** | Monitorowanie wydajności zespołu, analiza wyników kampanii, zapewnienie jakości obsługi klienta |
| **Potrzeby** | Dashboard czasu rzeczywistego, raporty historyczne, zarządzanie kampaniami i kolejkami, konfiguracja routingu |
| **Frustracje** | Brak bieżącego wglądu w pracę agentów, trudność w ocenie skuteczności kampanii |
| **Dostęp** | Zarządzanie agentami, kampaniami, raportami i konfiguracją w ramach własnego tenanta |

**Kluczowe User Stories:**
- Jako Supervisor, chcę widzieć w czasie rzeczywistym liczbę oczekujących kontaktów w każdej kolejce, aby reagować na przeciążenia.
- Jako Supervisor, chcę konfigurować kampanie wychodzące (listy kontaktów, harmonogram, dialer), aby planować działania telemarketingowe.
- Jako Supervisor, chcę przeglądać nagrania rozmów agentów, aby oceniać jakość obsługi.
- Jako Supervisor, chcę definiować umiejętności (skills) agentów i reguły routingu, aby optymalizować przydzielanie kontaktów.
- Jako Supervisor, chcę eksportować raporty do narzędzi BI przez data warehouse, aby prowadzić własne analizy.

### 3.3 Persona 3: Agent

| Atrybut | Opis |
|---------|------|
| **Rola** | Rola wykonawcza – obsługa klienta |
| **Zakres** | Kontakty przypisane do jego kolejek i kanałów |
| **Cele** | Efektywna obsługa klientów, rejestracja wyników, realizacja celów kampanii |
| **Potrzeby** | Unified desktop (jeden interfejs do wszystkich kanałów), profil klienta, historia kontaktów, skrypty, statusy dostępności |
| **Frustracje** | Konieczność przełączania między systemami, brak informacji o kliencie przed odebraniem połączenia |
| **Dostęp** | Dostęp tylko do przypisanych kolejek, kanałów i danych klientów w ramach tenanta |

**Kluczowe User Stories:**
- Jako Agent, chcę widzieć profil klienta i historię kontaktów przed/podczas rozmowy, aby personalizować obsługę.
- Jako Agent, chcę obsługiwać wiele kontaktów jednocześnie (np. kilka czatów), aby pracować efektywniej.
- Jako Agent, chcę zmieniać swój status (dostępny, przerwa, szkolenie), aby system prawidłowo kierował do mnie kontakty.
- Jako Agent, chcę rejestrować wynik kontaktu (disposition code) po jego zakończeniu, aby raportować rezultat obsługi.
- Jako Agent, chcę przekazywać kontakt innemu agentowi lub do kolejki, aby eskalować sprawy wymagające specjalistycznej wiedzy.

### 3.4 Klient końcowy (aktor zewnętrzny)

Klient końcowy to osoba kontaktująca się z organizacją przez jeden z obsługiwanych kanałów. Nie jest użytkownikiem systemu, ale jego dane są przechowywane w bazie klientów. System powinien zapewniać mu krótki czas oczekiwania, spójną obsługę niezależnie od kanału i brak konieczności powtarzania tych samych informacji.

---

## 4. Zakres projektu

### 4.1 W zakresie MVP (Faza 1)

| Obszar | Funkcjonalność |
|--------|---------------|
| Kanały inbound | Telefon (VoIP przez zewnętrznego dostawcę), Email, Social Media |
| Kanały outbound | Telefon (progressive dialer), Email |
| Automatyzacja | IVR z drzewem głosowym, Voicebot (obsługa automatyczna w IVR), Chatbot (social media / web) |
| Baza klientów | Profil klienta, historia kontaktów, wyszukiwanie |
| Routing | Skill-based routing, sticky agent (priorytet powracającego agenta), kolejkowanie proste |
| Persony | Administrator, Supervisor, Agent |
| Kampanie outbound | Listy kontaktów, harmonogram, progressive dialer |
| Nagrywanie | Nagrywanie rozmów telefonicznych |
| Raportowanie | Dashboard czasu rzeczywistego (Supervisor), dashboard techniczny (Admin), podstawowe raporty historyczne |
| Data Warehouse | Eksport danych do magazynu danych dla narzędzi BI |
| API | REST API z autoryzacją tokenem czasowym |
| Multi-tenancy | Logiczna izolacja danych między tenantami |
| Compliance | Zgodność z RODO/GDPR |

### 4.2 W zakresie Fazy 2 i późniejszych

| Obszar | Funkcjonalność |
|--------|---------------|
| Kanały | RCS (SMS, MMS, VMS) |
| Dialer | Predictive dialer, preview dialer |
| CRM | Rozbudowany moduł CRM (pipeline sprzedażowy, segmentacja, dokumenty) |
| Analityka AI | Transkrypcja rozmów, analiza sentymentu, podpowiedzi dla agentów (agent assist) |
| Integracje | Gotowe konektory do popularnych systemów (Salesforce, HubSpot, ERP) |
| Raportowanie | Rozbudowane raporty niestandardowe w interfejsie platformy |
| Supervisor tools | Whisper (podpowiedź do agenta), barge-in (wtrącenie do rozmowy) |

### 4.3 Poza zakresem (Out-of-scope)

- Własna centrala telefoniczna (softswitch/PBX) – system integruje się z zewnętrznym dostawcą
- Aplikacja mobilna dla agentów (wyłącznie interfejs webowy)
- Obsługa płatności (PCI DSS) w wersji 1.0
- Moduł HR / zarządzania zasobami ludzkimi
- Własny system ticketingowy (możliwa integracja przez API)

### 4.4 Założenia

- Każdy tenant posiada co najmniej jednego Supervisora i jednego Administratora tenanta (rola lokalna, odróżniana od globalnego Administratora systemu).
- Dostawca telefonii VoIP zostanie wybrany na etapie implementacji; architektura musi umożliwiać wymianę dostawcy bez przepisywania kodu (wzorzec adaptera).
- Klienci końcowi nie mają dostępu do interfejsu platformy.
- System działa wyłącznie przez przeglądarkę internetową (Angular SPA).

---

## 5. Wymagania funkcjonalne

### 5.1 Moduł: Zarządzanie Tenantami (Administrator)

**EPIC-01: Onboarding i konfiguracja tenanta**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-01-01 | Jako Administrator, chcę tworzyć nowego tenanta z unikalnym identyfikatorem i konfiguracją bazową | Must Have | Tenant jest tworzony z izolowaną przestrzenią danych; można się zalogować kontem supervisora tenanta |
| US-01-02 | Jako Administrator, chcę dezaktywować tenanta bez usuwania jego danych | Must Have | Po dezaktywacji logowanie użytkowników tenanta jest zablokowane; dane pozostają w bazie |
| US-01-03 | Jako Administrator, chcę konfigurować limity zasobów per tenant (max agentów, max kampanii) | Should Have | System egzekwuje limity; przy próbie przekroczenia zwraca czytelny błąd |
| US-01-04 | Jako Administrator, chcę przeglądać dashboard techniczny z metrykami wszystkich tenantów | Must Have | Dashboard pokazuje: liczba aktywnych agentów, liczba kontaktów w kolejce, status integracji, błędy systemowe |

**EPIC-02: Zarządzanie użytkownikami i rolami**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-02-01 | Jako Administrator, chcę tworzyć konta Supervisorów dla tenantów | Must Have | Konto Supervisora ma dostęp wyłącznie do danych swojego tenanta |
| US-02-02 | Jako Supervisor, chcę tworzyć i zarządzać kontami Agentów w moim tenancie | Must Have | Agent widzi wyłącznie kolejki i dane przypisane do jego profilu |
| US-02-03 | Jako Supervisor, chcę definiować umiejętności (skills) agentów | Must Have | Skill jest powiązany z agentem i używany przez silnik routingu |
| US-02-04 | Jako Administrator/Supervisor, chcę wymusić reset hasła użytkownika | Must Have | Link resetujący wygasa po 24 godzinach |

### 5.2 Moduł: Kanały komunikacji

**EPIC-03: Kanał telefoniczny (inbound)**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-03-01 | Jako Agent, chcę odbierać połączenia przychodzące przez interfejs webowy (softphone) | Must Have | Połączenie jest zestawiane w < 3 sekundy od przypisania do agenta; jakość audio spełnia standard G.711 |
| US-03-02 | Jako Agent, chcę widzieć numer/dane dzwoniącego przed odebraniem połączenia | Must Have | System wyświetla numer CLI i dopasowany profil klienta z bazy (jeśli istnieje) |
| US-03-03 | Jako Agent, chcę wyciszać mikrofon, stawiać połączenie na hold i transferować je | Must Have | Operacje działają bez przerywania połączenia; klient słyszy muzykę oczekiwania podczas hold |
| US-03-04 | Jako Agent, chcę transferować połączenie ślepo (blind transfer) i z konsultacją (attended transfer) | Should Have | Blind transfer: natychmiastowe przekazanie; attended transfer: agent może porozmawiać z odbiorcą przed przekazaniem |
| US-03-05 | Jako System, powinienem nagrywać wszystkie rozmowy telefoniczne i przechowywać nagrania | Must Have | Nagrania są dostępne dla Supervisora; format MP3/WAV; przechowywanie minimum 90 dni (konfigurowalnie) |

**EPIC-04: IVR i Automatyzacja głosowa**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-04-01 | Jako Supervisor, chcę konfigurować drzewo IVR (menu głosowe) bez ingerencji programistycznej | Must Have | Interfejs graficzny do budowania drzewa IVR; zmiany wchodzą w życie w czasie rzeczywistym |
| US-04-02 | Jako System, powinienem obsługiwać klientów automatycznie przez voicebot przed przekazaniem do agenta | Must Have | Voicebot obsługuje: zbieranie numeru klienta, weryfikację, proste FAQ; próg pewności < 70% = eskalacja do agenta |
| US-04-03 | Jako System, powinienem przekierowywać połączenie do odpowiedniej kolejki na podstawie wyboru w IVR | Must Have | Przekierowanie następuje w < 1 sekundzie od wyboru przez klienta |
| US-04-04 | Jako Supervisor, chcę konfigurować komunikaty dźwiękowe (TTS lub nagrane pliki) w IVR | Must Have | Obsługa TTS (text-to-speech) i upload plików audio (WAV, MP3) |

**EPIC-05: Kanał email**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-05-01 | Jako Agent, chcę odbierać i odpowiadać na emaile w interfejsie platformy | Must Have | Wiadomości wczytują się w < 2 sekundy; obsługiwany HTML i załączniki do 25 MB |
| US-05-02 | Jako System, powinienem przypisywać przychodzące emaile do kolejek na podstawie reguł (nadawca, temat, słowa kluczowe) | Must Have | Reguły konfigurowane przez Supervisora; obsługa wyrażeń regularnych w warunkach |
| US-05-03 | Jako Agent, chcę korzystać z szablonów odpowiedzi email | Should Have | Supervisor zarządza biblioteką szablonów; agent może personalizować szablon przed wysłaniem |
| US-05-04 | Jako System, powinienem powiązać wątek emailowy z profilem klienta i historią kontaktów | Must Have | Dopasowanie po adresie email; nowy kontakt tworzony automatycznie jeśli klient nieznany |

**EPIC-06: Kanał Social Media**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-06-01 | Jako Agent, chcę odbierać i odpowiadać na wiadomości z platform social media w jednym interfejsie | Must Have | Obsługiwane platformy definiowane przez konfigurację (architektura pluginowa); czas dostarczenia wiadomości < 30 sekund od wysłania przez klienta |
| US-06-02 | Jako Supervisor, chcę konfigurować integrację z kontem social media tenanta | Must Have | Konfiguracja przez OAuth lub API token; osobna konfiguracja per platforma |
| US-06-03 | Jako System, powinienem automatycznie routować wiadomości social media do kolejek agentów | Must Have | Routing jak inne kanały: skill-based, sticky agent |
| US-06-04 | Jako Agent, chcę widzieć historię konwersacji z klientem na danej platformie | Should Have | Pełna historia wątku widoczna po prawej stronie interfejsu |

### 5.3 Moduł: Routing i Kolejkowanie

**EPIC-07: Silnik routingu**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-07-01 | Jako System, powinienem kierować kontakty do agentów na podstawie ich umiejętności (skill-based routing) | Must Have | Kontakt trafia do agenta z najlepiej dopasowanym zestawem skills; czas decyzji routingu < 500 ms |
| US-07-02 | Jako System, powinienem priorytetyzować routing do agenta, który poprzednio obsługiwał danego klienta (sticky agent) | Must Have | Jeśli preferowany agent jest dostępny w ciągu konfigurowalnego czasu oczekiwania (domyślnie 60 s), kontakt jest do niego kierowany |
| US-07-03 | Jako Supervisor, chcę konfigurować proste kolejkowanie (round-robin, pierwszy wolny) dla wybranych kampanii | Must Have | Możliwość wyboru strategii routingu per kampania/kolejka |
| US-07-04 | Jako System, powinienem informować klienta o szacowanym czasie oczekiwania w kolejce | Should Have | Komunikat głosowy/tekstowy aktualizowany co 30 sekund |
| US-07-05 | Jako Agent, chcę obsługiwać wiele kontaktów jednocześnie (wielozadaniowość) | Must Have | Konfigurowalny limit jednoczesnych kontaktów per agent (domyślnie: 1 dla telefonu, 3 dla czat/email); UI wyraźnie rozróżnia aktywne kontakty |

### 5.4 Moduł: Kampanie Outbound

**EPIC-08: Zarządzanie kampaniami**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-08-01 | Jako Supervisor, chcę tworzyć kampanię wychodzącą i importować listę kontaktów (CSV) | Must Have | Import do 100 000 rekordów w < 2 minuty; walidacja formatu numeru i deduplikacja |
| US-08-02 | Jako Supervisor, chcę definiować harmonogram kampanii (data start/stop, godziny aktywności, dni tygodnia) | Must Have | System nie inicjuje połączeń poza zdefiniowanymi godzinami |
| US-08-03 | Jako System, powinienem realizować kampanię przez progressive dialer (dial po potwierdzeniu gotowości agenta) | Must Have | System dzwoni do klienta dopiero gdy agent jest gotowy; czas między gotowością agenta a inicjacją połączenia < 2 sekundy |
| US-08-04 | Jako Agent, chcę rejestrować wynik kontaktu outbound (disposition: sprzedaż, odmowa, brak odpowiedzi, callback) | Must Have | Disposition codes konfigurowane przez Supervisora per kampania; rejestracja wymagana przed zamknięciem kontaktu |
| US-08-05 | Jako System, powinienem automatycznie planować oddzwonienie (callback) na podstawie dyspozycji agenta | Should Have | Callback tworzony z datą/godziną; pojawia się w kolejce agenta we wskazanym czasie |
| US-08-06 | Jako Supervisor, chcę wstrzymywać, wznawiać i zatrzymywać kampanię w czasie rzeczywistym | Must Have | Zmiana stanu kampanii wchodzi w życie w < 5 sekund; aktywne połączenia są dokańczane |

### 5.5 Moduł: Baza Klientów

**EPIC-09: Profil i historia klienta**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-09-01 | Jako Agent/Supervisor, chcę wyszukiwać klienta po nazwie, numerze telefonu lub emailu | Must Have | Wyniki wyszukiwania pojawiają się w < 1 sekundzie; obsługiwane wyszukiwanie częściowe (fuzzy) |
| US-09-02 | Jako Agent, chcę przeglądać profil klienta zawierający dane kontaktowe i historię kontaktów | Must Have | Historia pokazuje: datę, kanał, agenta, czas trwania, dyspozycję; lista posortowana od najnowszych |
| US-09-03 | Jako Agent/Supervisor, chcę tworzyć, edytować i dezaktywować profile klientów | Must Have | Dezaktywacja nie usuwa historii kontaktów; klient nieaktywny nie pojawia się w listach kampanii |
| US-09-04 | Jako System, powinienem automatycznie tworzyć profil klienta przy pierwszym kontakcie z nieznanego numeru/emailu | Should Have | Tworzony z dostępnymi danymi (numer/email); agent proszony o uzupełnienie danych podczas kontaktu |
| US-09-05 | Jako Supervisor, chcę importować bazę klientów z pliku CSV | Must Have | Import: obsługiwane pola (imię, nazwisko, telefon, email, dodatkowe pola custom); walidacja duplikatów |
| US-09-06 | Jako System, powinienem obsługiwać prawo do usunięcia danych klienta (RODO Art. 17) | Must Have | Usunięcie danych osobowych przy zachowaniu anonimizowanych statystyk kontaktów |

### 5.6 Moduł: Raportowanie i Analityka

**EPIC-10: Dashboardy i raporty**

| ID | User Story | Priorytet MoSCoW | Kryteria akceptacji |
|----|-----------|-----------------|---------------------|
| US-10-01 | Jako Supervisor, chcę widzieć dashboard czasu rzeczywistego z kluczowymi metrykami | Must Have | Odświeżanie co 5 sekund lub mniej; metryki: kontakty w kolejce, AHT, agenci dostępni/zajęci/w przerwie, porzucone kontakty |
| US-10-02 | Jako Supervisor, chcę przeglądać raporty historyczne pracy agentów (dzień, tydzień, miesiąc) | Must Have | Raport zawiera: liczba obsłużonych kontaktów, AHT, czas logowania, czas przerw, wyniki dyspozycji |
| US-10-03 | Jako Supervisor, chcę przeglądać raporty skuteczności kampanii outbound | Must Have | Raport zawiera: liczba prób, połączenia nawiązane, conversion rate per dyspozycja |
| US-10-04 | Jako Administrator, chcę mieć dashboard techniczny z metrykami infrastruktury | Must Have | Dashboard pokazuje: uptime, liczba aktywnych sesji, kolejki wiadomości, błędy integracji, zużycie zasobów per tenant |
| US-10-05 | Jako Supervisor, chcę eksportować raporty do CSV/Excel | Should Have | Eksport w < 30 sekund dla raportów obejmujących do 12 miesięcy danych |
| US-10-06 | Jako System, powinienem replikować dane do magazynu danych (data warehouse) | Must Have | Dane replikowane z opóźnieniem < 1 godziny; schemat danych udokumentowany; zgodność z popularnymi narzędziami BI (Power BI, Tableau, Metabase) |

---

## 6. Wymagania niefunkcjonalne

### 6.1 Wydajność

| ID | Wymaganie | Wartość |
|----|-----------|---------|
| NFR-P01 | Czas ładowania interfejsu agenta (initial load) | < 3 sekundy przy łączu 10 Mbps |
| NFR-P02 | Czas odpowiedzi API dla operacji CRUD | < 200 ms (p95) |
| NFR-P03 | Czas odpowiedzi API dla wyszukiwania klientów | < 1 sekunda (p95) |
| NFR-P04 | Czas decyzji silnika routingu | < 500 ms |
| NFR-P05 | Przepustowość – liczba jednoczesnych aktywnych agentów | 100 agentów per tenant |
| NFR-P06 | Przepustowość – liczba jednoczesnych połączeń telefonicznych | 150 (150% liczby agentów, z zapasem na IVR) |
| NFR-P07 | Odświeżanie dashboardu czasu rzeczywistego | <= 5 sekund |
| NFR-P08 | Import CSV kampanii (100 000 rekordów) | < 2 minuty |

### 6.2 Dostępność i niezawodność

| ID | Wymaganie | Wartość |
|----|-----------|---------|
| NFR-A01 | Dostępność platformy (SLA) | >= 99,9% (tj. < 8,7 godziny przestoju rocznie) |
| NFR-A02 | Planowane okna serwisowe | Maksymalnie 4 godziny miesięcznie, poza godzinami szczytu (00:00-06:00) |
| NFR-A03 | Czas przywracania usługi po awarii (RTO) | < 1 godzina |
| NFR-A04 | Maksymalna utrata danych (RPO) | < 15 minut |
| NFR-A05 | Odporność na awarię pojedynczego węzła | System pozostaje dostępny (architektura HA) |

### 6.3 Skalowalność

| ID | Wymaganie |
|----|-----------|
| NFR-S01 | Architektura musi umożliwiać skalowanie horyzontalne warstwy aplikacyjnej bez downtime |
| NFR-S02 | Dodanie nowego tenanta nie może wymagać restartu systemu |
| NFR-S03 | Baza danych musi obsługiwać co najmniej 50 tenantów z 100 agentami każdy jednocześnie |
| NFR-S04 | Data warehouse musi obsługiwać zapytania na danych z ostatnich 5 lat bez degradacji wydajności |

### 6.4 Bezpieczeństwo

| ID | Wymaganie |
|----|-----------|
| NFR-SEC01 | Cała komunikacja sieciowa szyfrowana protokołem TLS 1.2 lub nowszym |
| NFR-SEC02 | Hasła przechowywane z użyciem algorytmu bcrypt (min. 12 rund) |
| NFR-SEC03 | Tokeny API: JWT z czasem wygaśnięcia konfigurowalnym (domyślnie 1 godzina); obsługa refresh token |
| NFR-SEC04 | Uwierzytelnianie wieloskładnikowe (MFA) dla kont Administratora i Supervisora |
| NFR-SEC05 | Pełna izolacja logiczna danych między tenantami; każde zapytanie do bazy filtrowane po tenant_id |
| NFR-SEC06 | Dziennik audytowy (audit log) wszystkich operacji administracyjnych: kto, co, kiedy |
| NFR-SEC07 | Nagrania rozmów szyfrowane w spoczynku (AES-256) |
| NFR-SEC08 | Testy bezpieczeństwa (OWASP Top 10) przed każdym major release |
| NFR-SEC09 | Wdrożony mechanizm rate limiting dla API (max 1000 req/min per token) |

### 6.5 Zgodność z RODO/GDPR

| ID | Wymaganie |
|----|-----------|
| NFR-RODO01 | System musi umożliwiać realizację prawa do usunięcia danych (Art. 17 RODO) – anonimizacja danych osobowych klienta z zachowaniem statystyk |
| NFR-RODO02 | System musi umożliwiać eksport danych klienta w formacie maszynowo czytelnym (Art. 20 – prawo do przenoszenia danych) |
| NFR-RODO03 | Nagrania rozmów muszą być automatycznie usuwane po upływie zdefiniowanego okresu retencji (konfigurowalny per tenant) |
| NFR-RODO04 | Dane osobowe klientów z UE muszą być przechowywane na serwerach zlokalizowanych w UE |
| NFR-RODO05 | System musi prowadzić rejestr przetwarzania danych (Art. 30 RODO) dostępny dla Administratora |
| NFR-RODO06 | Zgody marketingowe przechowywane i weryfikowane przed inicjacją kontaktu outbound |

### 6.6 Użyteczność i dostępność (UX)

| ID | Wymaganie |
|----|-----------|
| NFR-UX01 | Interfejs w języku polskim (domyślnie) z architekturą i18n umożliwiającą dodanie kolejnych języków |
| NFR-UX02 | Responsywność interfejsu: optymalizacja dla rozdzielczości od 1366x768 do 4K |
| NFR-UX03 | Zgodność z WCAG 2.1 poziom AA |
| NFR-UX04 | Obsługa przeglądarek: Chrome (ostatnie 2 wersje), Firefox (ostatnie 2 wersje), Edge (ostatnie 2 wersje) |
| NFR-UX05 | Interfejs agenta musi umożliwiać obsługę przez klawiaturę (skróty klawiszowe dla odbierania/rozłączania) |

---

## 7. Architektura wysokiego poziomu

### 7.1 Styl architektoniczny

System zbudowany jako **platforma mikroserwisów** (lub modularny monolit z jasno wydzielonymi modułami w pierwszej fazie) z następującymi warstwami:

```
+----------------------------------------------------------+
|                    KLIENCI (Browser - Angular SPA)       |
+----------------------------------------------------------+
                              |
+----------------------------------------------------------+
|              API Gateway / Load Balancer                 |
|         (autentykacja, rate limiting, routing)           |
+----------------------------------------------------------+
          |              |              |              |
  +---------------+ +-----------+ +-----------+ +----------+
  | Auth Service  | | Contact   | | Campaign  | | Customer |
  | (JWT, MFA)    | | Service   | | Service   | | Service  |
  +---------------+ +-----------+ +-----------+ +----------+
          |              |              |              |
  +---------------+ +-----------+ +-----------+ +----------+
  | Channel       | | Routing   | | Recording | | Reporting|
  | Adapters      | | Engine    | | Service   | | Service  |
  | (Tel/Email/SM)| |           | |           | |          |
  +---------------+ +-----------+ +-----------+ +----------+
                              |
+----------------------------------------------------------+
|                  Message Broker (np. Kafka/RabbitMQ)    |
+----------------------------------------------------------+
                              |
        +---------------------+---------------------+
        |                                           |
+---------------+                         +------------------+
| PostgreSQL    |                         | Data Warehouse   |
| (per-tenant   |                         | (np. ClickHouse/ |
|  schema)      |                         |  BigQuery)       |
+---------------+                         +------------------+
```

### 7.2 Kluczowe komponenty

| Komponent | Technologia | Opis |
|-----------|------------|------|
| Frontend | Angular (TypeScript) | SPA – unified desktop agenta, panele supervisora i administratora |
| Backend API | Java + Spring Boot | Główna logika biznesowa: zarządzanie tenantami, routing, kampanie, CRM |
| Automatyzacja / AI | Python | Voicebot, chatbot, integracje ML, przetwarzanie NLP |
| Baza danych | PostgreSQL | Dane operacyjne; izolacja logiczna przez tenant_id; rozważyć row-level security |
| Broker wiadomości | Apache Kafka lub RabbitMQ | Asynchroniczna komunikacja między serwisami; kolejki zdarzeń kontaktów |
| Data Warehouse | ClickHouse / Apache Druid / BigQuery | Magazyn danych analitycznych; zasilany przez CDC lub event streaming |
| Przechowywanie nagrań | Object Storage (S3-compatible) | Nagrania szyfrowane AES-256; policy lifecycle do automatycznego usuwania |
| WebRTC / Softphone | WebRTC (przeglądarka) | Komunikacja głosowa bezpośrednio w przeglądarce |

### 7.3 Multi-tenancy

- Izolacja **logiczna** – wszystkie tabele zawierają kolumnę `tenant_id`
- Zapytania filtrowane automatycznie przez warstwę repozytorium (nie można ominąć bez modyfikacji kodu)
- Rozważenie PostgreSQL Row-Level Security (RLS) jako dodatkowej warstwy ochrony
- Konfiguracja per-tenant przechowywana w dedykowanej tabeli `tenant_config`

---

## 8. Integracje

### 8.1 Integracje w zakresie MVP

| Integracja | Typ | Opis |
|-----------|-----|------|
| Dostawca telefonii VoIP | Zewnętrzny (adapter) | SIP trunk lub CPaaS API (np. Twilio, Vonage, Telnyx); wzorzec adaptera umożliwia wymianę dostawcy |
| Platformy Social Media | Zewnętrzny (webhook/API) | Facebook Messenger API, Instagram API, WhatsApp Business API; inne przez pluginy |
| Serwer SMTP / Email | Zewnętrzny | Odbiór przez IMAP/POP3 lub webhook (np. SendGrid Inbound Parse); wysyłka przez SMTP/API |
| TTS (Text-to-Speech) | Zewnętrzny | Google Cloud TTS, Microsoft Azure TTS lub open-source (Coqui TTS) – dla IVR i voicebota |
| Data Warehouse | Wewnętrzny | ETL/CDC pipeline z PostgreSQL do magazynu analitycznego |

### 8.2 REST API dla klientów SaaS

| Obszar | Opis |
|--------|------|
| Autoryzacja | OAuth 2.0 / JWT; token czasowy z konfigurowalnymi uprawnieniami (scopes) |
| Dokumentacja | OpenAPI 3.0 (Swagger); interaktywna dokumentacja dostępna dla każdego tenanta |
| Wersjonowanie | Wersjonowanie przez URL (/api/v1/, /api/v2/) |
| Webhooks | Powiadomienia o zdarzeniach (nowy kontakt, zakończenie kontaktu, zmiana statusu) |
| Kluczowe endpointy | Klienci (CRUD), Kontakty (historia), Kampanie (zarządzanie), Agenci (statusy), Raporty (dane) |

### 8.3 Planowane integracje (Faza 2+)

- Salesforce CRM
- HubSpot CRM
- Systemy ERP (przez REST API lub dedykowane konektory)
- Systemy ticketingowe (Jira Service Management, Zendesk)

---

## 9. Model danych – wysokopoziomowo

### 9.1 Główne encje

```
TENANT
  - tenant_id (PK)
  - name
  - status (active/inactive)
  - config (JSONB)
  - created_at

USER
  - user_id (PK)
  - tenant_id (FK)
  - role (ADMINISTRATOR | SUPERVISOR | AGENT)
  - email
  - password_hash
  - skills (JSONB / relacja many-to-many)
  - status (available | busy | break | offline)

CUSTOMER
  - customer_id (PK)
  - tenant_id (FK)
  - first_name, last_name
  - phone[], email[]
  - custom_fields (JSONB)
  - gdpr_consent (JSONB)
  - is_deleted (soft delete)
  - created_at, updated_at

CONTACT (interakcja z klientem)
  - contact_id (PK)
  - tenant_id (FK)
  - customer_id (FK)
  - agent_id (FK -> USER)
  - channel (PHONE | EMAIL | SOCIAL_MEDIA | RCS)
  - direction (INBOUND | OUTBOUND)
  - status (queued | active | completed | abandoned)
  - campaign_id (FK, nullable)
  - started_at, ended_at
  - disposition_code
  - recording_url (nullable)

CAMPAIGN
  - campaign_id (PK)
  - tenant_id (FK)
  - name, type (TELEMARKETING | DEBT | SURVEY | ...)
  - dialer_type (PROGRESSIVE | PREDICTIVE | PREVIEW)
  - schedule (JSONB: start_date, end_date, hours, days)
  - status (draft | active | paused | completed)
  - contact_list_id (FK)

QUEUE
  - queue_id (PK)
  - tenant_id (FK)
  - name
  - routing_strategy (SKILL_BASED | ROUND_ROBIN | FIRST_AVAILABLE)
  - required_skills (JSONB)
  - sticky_agent_timeout_seconds

IVR_TREE
  - ivr_id (PK)
  - tenant_id (FK)
  - name
  - definition (JSONB – drzewo węzłów IVR)
  - version, is_active

AUDIT_LOG
  - log_id (PK)
  - tenant_id (FK, nullable)
  - user_id (FK)
  - action
  - entity_type, entity_id
  - old_value (JSONB), new_value (JSONB)
  - created_at
```

---

## 10. Roadmapa i fazy wdrożenia

### 10.1 Faza 1 – MVP (Contact Center Core)

**Cel:** Działająca platforma z kanałami inbound/outbound (telefon, email, social media), podstawowym CRM i raportowaniem.

| Obszar | Funkcjonalności |
|--------|----------------|
| Infrastruktura | Multi-tenant SaaS, autentykacja, zarządzanie tenantami |
| Kanał telefoniczny | Inbound + outbound (progressive dialer), IVR, voicebot, nagrywanie |
| Kanał email | Inbound + outbound, routing, szablony |
| Social media | Odbiór i odpowiedź na wiadomości, routing |
| Routing | Skill-based, sticky agent, simple queue |
| Baza klientów | Profil + historia kontaktów, import CSV |
| Kampanie | Zarządzanie kampaniami, harmonogram, progressive dialer |
| Chatbot | Podstawowy chatbot dla social media / web |
| Raportowanie | Dashboard RT (Supervisor), dashboard techniczny (Admin), raporty podstawowe |
| Data Warehouse | Pipeline replikacji danych |
| API | REST API v1 z dokumentacją OpenAPI |
| Compliance | RODO: usuwanie danych, retencja nagrań, audit log |

### 10.2 Faza 2 – Rozszerzony dialer i analityka

| Obszar | Funkcjonalności |
|--------|----------------|
| Dialer | Predictive dialer, preview dialer |
| Supervisor tools | Whisper (podpowiedź do agenta podczas rozmowy), barge-in |
| Raportowanie | Rozbudowane raporty niestandardowe w UI |
| AI/ML | Transkrypcja rozmów, analiza sentymentu |

### 10.3 Faza 3 – Nowe kanały i CRM

| Obszar | Funkcjonalności |
|--------|----------------|
| Kanały | RCS: SMS, MMS, VMS |
| CRM | Rozbudowany moduł: pipeline sprzedażowy, segmentacja, dokumenty, tagi |
| AI | Agent Assist (podpowiedzi dla agenta w czasie rzeczywistym) |
| Integracje | Gotowe konektory: Salesforce, HubSpot, Zendesk |

### 10.4 Faza 4 – Dojrzałość platformy

| Obszar | Funkcjonalności |
|--------|----------------|
| Marketplace | Ekosystem pluginów / integracji dla klientów SaaS |
| API v2 | Rozszerzone API z webhooks i event streaming |
| Compliance | Dodatkowe certyfikaty (ISO 27001, SOC 2) |
| Analytics | Self-service BI wbudowany w platformę |

---

## 11. Kryteria sukcesu i KPI

### 11.1 KPI platformy (metryki produktowe)

| KPI | Definicja | Cel (12 miesięcy od launchu) |
|-----|-----------|------------------------------|
| Liczba aktywnych tenantów | Tenanty z min. 1 aktywnym agentem w ostatnim miesiącu | 10 tenantów |
| Retencja tenantów | % tenantów kontynuujących subskrypcję po 6 miesiącach | > 85% |
| Uptime platformy | Mierzony monthly | >= 99,9% |
| Czas onboardingu tenanta | Od rejestracji do pierwszego aktywnego agenta | < 2 godziny |
| NPS tenantów | Net Promoter Score mierzony kwartalnie | > 40 |

### 11.2 KPI operacyjne (metryki dla klientów SaaS)

| KPI | Definicja |
|-----|-----------|
| AHT (Average Handle Time) | Średni czas obsługi kontaktu (dostępny w raportach) |
| FCR (First Contact Resolution) | % kontaktów rozwiązanych przy pierwszym kontakcie |
| ASA (Average Speed of Answer) | Średni czas oczekiwania klienta na połączenie z agentem |
| Abandon Rate | % klientów rozłączających się przed obsługą |
| Conversion Rate (outbound) | % kontaktów outbound kończących się sukcesem (per kampania) |
| Agent Utilization | % czasu agenta poświęconego na aktywne kontakty |

### 11.3 Kryteria akceptacji MVP

- System obsługuje co najmniej 100 jednoczesnych agentów per tenant bez degradacji wydajności
- Czas odpowiedzi API < 200 ms dla 95. percentyla
- Wszystkie nagrania rozmów dostępne w przeciągu 5 minut od zakończenia rozmowy
- Izolacja danych między tenantami: testy penetracyjne nie wykrywają możliwości dostępu do danych innego tenanta
- System przechodzi audyt zgodności z RODO

---

## 12. Ryzyka i ograniczenia

### 12.1 Ryzyka techniczne

| ID | Ryzyko | Prawdopodobieństwo | Wpływ | Mitygacja |
|----|--------|-------------------|-------|-----------|
| RT-01 | Wybór dostawcy telefonii VoIP – różnice w API mogą wymagać znaczącej pracy integracyjnej | Wysokie | Wysoki | Wzorzec adaptera; POC z 2-3 dostawcami przed wyborem |
| RT-02 | Wydajność routingu przy dużej liczbie jednoczesnych kontaktów | Średnie | Wysoki | Testy obciążeniowe (load testing) jako parte of definition of done |
| RT-03 | Spójność danych między bazą operacyjną (PostgreSQL) a data warehouse | Średnie | Średni | CDC (Change Data Capture) z gwarancją at-least-once delivery |
| RT-04 | WebRTC – problemy z jakością audio przy różnych konfiguracjach sieci klientów | Wysokie | Średni | TURN/STUN server, monitoring jakości połączeń (MOS score) |
| RT-05 | Dostępność API platform social media (zmiany polityk, rate limiting) | Średnie | Wysoki | Architektura pluginowa; monitoring statusu API zewnętrznych |

### 12.2 Ryzyka biznesowe

| ID | Ryzyko | Prawdopodobieństwo | Wpływ | Mitygacja |
|----|--------|-------------------|-------|-----------|
| RB-01 | Zmiana regulacji RODO lub pojawienie się nowych wymogów compliance | Średnie | Wysoki | Modularny moduł compliance; regularne przeglądy prawne |
| RB-02 | Konkurencja ze strony dojrzałych platform (Genesys, NICE, Twilio Flex) | Wysokie | Średni | Pozycjonowanie na konkretne nisze rynkowe; czas TTM jako przewaga |
| RB-03 | Trudność w pozyskaniu pierwszych tenantów (zimny start) | Średnie | Wysoki | Pilotaż z 1-2 firmami partnerskimi na wczesnym etapie |
| RB-04 | Koszty operacyjne (dostawcy telefonii, storage nagrań) wyższe niż zakładano | Średnie | Średni | Model kosztowy per-tenant; analiza unit economics przed launch |

### 12.3 Ograniczenia

- **Technologiczne:** Stack technologiczny (Angular, Java/Spring Boot, Python, PostgreSQL) jest ustalony.
- **Izolacja danych:** Izolacja logiczna (nie fizyczna per tenant) – wymaga szczególnej uwagi w testach bezpieczeństwa.
- **Brak własnej centrali:** Zależność od zewnętrznego dostawcy telefonii wprowadza ryzyko dostępności i kosztów.
- **Zasoby:** Projekt wewnętrzny – możliwości rozwoju ograniczone dostępnością zespołu.

---

## 13. Otwarte pytania i decyzje do podjęcia

| ID | Pytanie / Decyzja | Właściciel | Termin |
|----|------------------|-----------|--------|
| OQ-01 | Który dostawca telefonii VoIP zostanie wybrany dla MVP? (Rekomendacja: POC z Twilio i Telnyx) | Architekt / PM | Przed Fazą 1 Sprint 1 |
| OQ-02 | Które konkretne platformy social media wchodzą do MVP? (Facebook Messenger, WhatsApp, Instagram?) | PM / Biznes | Przed Fazą 1 Sprint 1 |
| OQ-03 | Jaka jest strategia cenowa (pricing model) dla tenantów SaaS? (per agent/month, per contact, flat fee?) | Biznes | Przed launchem |
| OQ-04 | Czy voicebot/chatbot budujemy in-house (Python/NLP) czy integrujemy gotowe rozwiązanie (Dialogflow, Rasa)? | Architekt | Przed Fazą 1 Sprint 2 |
| OQ-05 | Jakie narzędzie BI jest preferowane przez docelowych klientów? (wpływ na schemat data warehouse) | PM / Klienci pilotażowi | Przed Fazą 1 Sprint 3 |
| OQ-06 | Jaki jest model SLA gwarantowany klientom SaaS i jakie kary umowne za jego naruszenie? | Biznes / Legal | Przed launchem |
| OQ-07 | Czy platforma wymaga certyfikacji ISO 27001 lub SOC 2 na etapie MVP, czy w późniejszej fazie? | Biznes / Legal | Faza 2 |
| OQ-08 | Jak ma działać billing/rozliczenie tenantów? Czy platforma zawiera moduł billing, czy integracja zewnętrzna? | PM / Biznes | Przed launchem |

---

## 14. Appendix

### A. Słownik pojęć

| Termin | Definicja |
|--------|-----------|
| **AHT** | Average Handle Time – średni czas obsługi kontaktu, od odebrania do zamknięcia |
| **ASA** | Average Speed of Answer – średni czas oczekiwania klienta na połączenie z agentem |
| **Barge-in** | Funkcja supervisora pozwalająca wtrącić się do aktywnej rozmowy agenta z klientem |
| **Chatbot** | Zautomatyzowany asystent tekstowy obsługujący klientów przez kanały tekstowe |
| **CPaaS** | Communications Platform as a Service – dostawca API do komunikacji (telefonia, SMS) |
| **Dialer progresywny** | System automatycznego wybierania numeru dopiero gdy agent jest gotowy do rozmowy |
| **Dialer predyktywny** | System przewidujący gotowość agenta i inicjujący połączenie z wyprzedzeniem |
| **Disposition code** | Kod wyniku kontaktu rejestrowany przez agenta (np. sprzedaż, odmowa, callback) |
| **FCR** | First Contact Resolution – wskaźnik rozwiązania sprawy klienta przy pierwszym kontakcie |
| **IVR** | Interactive Voice Response – interaktywne menu głosowe obsługiwane przez klawiaturę lub głos |
| **MoSCoW** | Metoda priorytetyzacji: Must Have, Should Have, Could Have, Won't Have |
| **Multi-tenant** | Architektura SaaS, w której wielu klientów (tenantów) korzysta z tej samej instancji aplikacji |
| **RCS** | Rich Communication Services – standard wiadomości mobilnych (SMS/MMS następnej generacji) |
| **ROI** | Return on Investment – zwrot z inwestycji |
| **RPO** | Recovery Point Objective – maksymalna akceptowalna utrata danych |
| **RTO** | Recovery Time Objective – maksymalny akceptowalny czas przywracania usługi |
| **SLA** | Service Level Agreement – umowa o poziomie usług |
| **Skill-based routing** | Routing kierujący kontakt do agenta o umiejętnościach najlepiej dopasowanych do potrzeby klienta |
| **Sticky agent** | Mechanizm priorytetowego kierowania klienta do agenta, który go poprzednio obsługiwał |
| **Tenant** | Klient SaaS – organizacja korzystająca z platformy jako wydzielonej, izolowanej instancji |
| **TTS** | Text-to-Speech – synteza mowy z tekstu, używana w IVR i voicebocie |
| **VMS** | Voice Message Service – nagranie głosowe jako wiadomość (kanał RCS) |
| **Voicebot** | Zautomatyzowany asystent głosowy obsługujący klientów przez kanał telefoniczny |
| **WebRTC** | Web Real-Time Communication – standard komunikacji audio/video bezpośrednio w przeglądarce |
| **Whisper** | Funkcja supervisora pozwalająca podpowiadać agentowi podczas rozmowy bez wiedzy klienta |

### B. Referencje i materiały źródłowe

- Sesja odkrywania wymagań z użytkownikiem (2026-03-12)
- RODO / GDPR: Rozporządzenie UE 2016/679
- WCAG 2.1: Web Content Accessibility Guidelines
- OWASP Top 10 (2021): https://owasp.org/Top10/
- WebRTC Standard: https://webrtc.org/
- OpenAPI Specification 3.0: https://swagger.io/specification/
