# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

**Główny użytkownik: Agent.** Gdy potrzeby ról są w konflikcie, wygrywa agent. Agent pracuje w ramach jednego tenanta, przez całą zmianę, kontakt za kontaktem: odbiera i inicjuje połączenia, obsługuje e-maile i kilka czatów/social jednocześnie, zmienia swój status (dostępny, przerwa, szkolenie), rejestruje wynik kontaktu (disposition code) i przekazuje sprawy do innego agenta lub kolejki. Przed odebraniem kontaktu i w jego trakcie potrzebuje profilu klienta i historii kontaktów.

Pozostałe role (każda ma własną powierzchnię aplikacji):

- **Supervisor**: jeden tenant. Na żywo monitoruje kolejki i agentów, konfiguruje kampanie wychodzące, routing i umiejętności (skills), odsłuchuje nagrania, analizuje raporty.
- **Administrator**: cała platforma SaaS. Zakłada i konfiguruje tenanty, zarządza globalnymi integracjami (telefonia, social), monitoruje zdrowie systemu, przegląda logi audytowe.
- **Klient końcowy**: aktor zewnętrzny, nie loguje się do systemu. Liczy się dla niego krótki czas oczekiwania i to, że nie musi powtarzać informacji w kolejnych kanałach.

## Product Purpose

Wielokanałowa platforma Contact Center w modelu SaaS, multi-tenant. Łączy w jednym miejscu kontakty przychodzące (telefon VoIP, e-mail, social media, z RCS w planach) i wychodzące (kampanie z progressive dialerem, e-mail), wbudowaną bazę klientów z historią kontaktów, automatyzację (IVR, voicebot, chatbot) oraz analitykę z hurtownią danych.

Sukces na poziomie platformy (cele na 12 miesięcy od launchu, z PRD): 10 aktywnych tenantów, retencja > 85% po 6 miesiącach, onboarding tenanta do pierwszego aktywnego agenta < 2 h, NPS > 40, uptime ≥ 99,9%. Sukces u klientów SaaS mierzą AHT, FCR, ASA, Abandon Rate, konwersja kampanii outbound i utylizacja agentów.

## Positioning

Wyróżnikiem jest połączenie czterech cech, których konkurenci (Genesys, Five9, Talkdesk, Thulium, Focus Telecom) zwykle nie oferują razem:

1. **Omnichannel w jednym widoku**: agent obsługuje telefon, e-mail, social i przekazania z botów w jednym interfejsie, z jednym profilem klienta.
2. **Silny outbound**: kampanie z listami kontaktów, harmonogramem i progressive dialerem, z agentami przypisanymi bezpośrednio do kampanii, a zgody marketingowe są weryfikowane przed każdym kontaktem.
3. **Otwartość**: REST API z tokenami czasowymi, system pluginów i integracje z systemami klienta.
4. **RODO i dane w UE jako fundament**: przechowywanie w UE, konfigurowalna retencja i archiwizacja per tenant, anonimizacja (Art. 17), eksport danych (Art. 20), rejestr przetwarzania (Art. 30).

## Operating Context

- Agent spędza całą zmianę w przeglądarce, w słuchawkach, z wieloma równoległymi kontaktami. Odbieranie i rozłączanie musi działać ze skrótów klawiszowych.
- Supervisor trzyma otwarty dashboard czasu rzeczywistego, który odświeża się co najwyżej co 5 s, i reaguje na przeciążenia kolejek.
- Przeglądarki desktopowe: Chrome, Firefox i Edge (2 ostatnie wersje). Rozdzielczości od 1366×768 do 4K.
- Interfejs agenta ładuje się w < 3 s przy łączu 10 Mbps.
- Każdy tenant to osobna organizacja z logicznie izolowanymi danymi. Administrator widzi wszystkie tenanty.

## Capabilities and Constraints

- Aplikacja w Angularze (standalone components, signals) z Angular Material. Backend Spring Boot. Powierzchnie per rola: `admin`, `supervisor`, `agent` oraz `auth`, `tenants`, `integrations`, `reports`.
- Logowanie wieloetapowe (e-mail → dane uwierzytelniające → MFA). MFA jest wymagane dla Administratora i Supervisora.
- Słownictwo domenowe:
  - **Kolejka** służy tylko do routingu przychodzącego.
  - **Kampania** to outbound, z agentami przypisanymi bezpośrednio, nie przez kolejkę.
  - Pozostałe pojęcia: **disposition code**, **status agenta**, **sticky agent**, **skill-based routing**.
- Nagrania rozmów są szyfrowane i automatycznie usuwane po okresie retencji ustawionym per tenant.
- **Otwarte decyzje:** nazwa produktu, logo i marka nie są ustalone. `ContactCenterFrontend` w `index.html` to nazwa robocza.

## Brand Commitments

- Interfejs jest domyślnie po polsku. Architektura i18n (Transloco) obsługuje już `pl`, `en`, `de` i `uk`. Żaden tekst nie może być na sztywno w jednym języku.
- Produkt nie ma jeszcze nazwy, logo ani ustalonego głosu marki. Nie wolno ich wymyślać jako faktów.

## Evidence on Hand

- Dokumentacja produktowa i techniczna: `PRD.md`, `ARCHITECTURE.md`, `TECH-STACK.md`, `UI-REVIEW.md` (ocena UI z 2026-03-26).
- Dźwięki powiadomień: `frontend/public/sounds/`. Tłumaczenia: `frontend/public/i18n/`.
- **Brak:** klientów, tenantów produkcyjnych, referencji, case studies, logotypów klientów, danych o wynikach i cennika. Produkt jest na etapie demo. Przyszłe prace nie mogą fabrykować tych dowodów.

## Product Principles

1. **Agent ma pierwszeństwo.** Szybkość obsługi kolejnego kontaktu i praca z klawiatury są ważniejsze niż odkrywalność rzadko używanych funkcji.
2. **Jeden klient, jeden kontekst.** Kanał nie może rozbijać historii klienta. Agent nie powinien prosić o informacje, które system już ma.
3. **Dane na żywo mówią prawdę o sobie.** Widoki czasu rzeczywistego pokazują, jak świeże są dane, i wyraźnie sygnalizują utratę połączenia albo nieaktualny stan.
4. **Zgodność wbudowana, nie doklejona.** Zgody, retencja i izolacja tenantów są częścią przepływów, a nie osobnym modułem na końcu.
5. **Polski domyślnie, nigdy wyłącznie po polsku.** Każdy tekst przechodzi przez i18n, a układ musi znieść dłuższe tłumaczenia (np. `de`).

## Accessibility & Inclusion

- Zgodność z WCAG 2.1 na poziomie AA.
- Pełna obsługa interfejsu agenta z klawiatury, w tym skróty do odbierania i rozłączania połączeń.
