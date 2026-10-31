---
target: frontend/src/app/features/agent
total_score: 17
max_score: 40
na_heuristics: 
p0_count: 2
p1_count: 3
target_identity: "file:/home/pawelm/contact-center/frontend/src/app/features/agent"
timestamp: 2026-10-21T18-41-51Z
slug: frontend-src-app-features-agent
---
# Krytyka: powierzchnia Agenta (frontend/src/app/features/agent)

Method: dual-agent (A: przegląd projektowy · B: detektor + przeglądarka)

## Design Health Score
| # | Heurystyka | Ocena | Kluczowy problem |
|---|---|---|---|
| 1 | Widoczność stanu systemu | 2 | Wskaźnik połączenia WS dobry; status agenta tylko na Desktopie, karty bez hold/ACW/nieprzeczytanych, przerwa bez licznika |
| 2 | Zgodność z rzeczywistością | 2 | Ręczny wybór stanów systemowych; brak „Szkolenia”; mieszanka języków |
| 3 | Kontrola i swoboda | 1 | Esc psuje modale; × na karcie PHONE rozłącza; zmiana karty kasuje szkic |
| 4 | Spójność i standardy | 2 | Statusy w hex; PL w UI EN; dwa „Dodaj przerwę” |
| 5 | Zapobieganie błędom | 1 | „Zamknij” na banerze wycisza dzwonek przy trwającym dzwonieniu; rozłączenie bez potwierdzenia |
| 6 | Rozpoznawanie zamiast pamiętania | 3 | Etykiety na przyciskach, historia w karcie klienta |
| 7 | Elastyczność i efektywność | 0 | Brak skrótów, choć PRODUCT.md ich wymaga |
| 8 | Estetyka i minimalizm | 3 | Spokojnie; ok. 160 z 591 px na chrome przy 768 |
| 9 | Pomoc w naprawie błędów | 2 | Baner WS; błąd karty klienta bez ponowienia |
| 10 | Pomoc i dokumentacja | 1 | Brak ściągi skrótów |
| **Suma** | | **17/40** | **Poor** |

## Werdykt: specyficzność projektu
A: wizualnie „Well-Lit Workbench” jest zrealizowany, ale zachowanie nie wynika ze sceny pracy agenta (brak skrótów i fokusu, karty bez stanu, Kalendarz zasłania softphone); bez warstwy wizualnej to typowy helpdesk.
Detektor: 16 znalezisk CLI w features/agent i 1 w shell; 18–24 na widok w przeglądarce. bounce-easing ×10 to fałszywe alarmy (--ease-spring nie ma odbicia); overused-font ×5 to świadoma decyzja z DESIGN.md; side-tab ×2 (email-thread-message.component.scss:34,41) to celowe znaczniki kierunku. Złapane dodatkowo przez detektor: --text-muted (#606468) w ciemnym motywie ma 2,8–3,2:1; etykiety KPI w top-navbar mają 9,5 px; kafle KPI oznaczone jako karty w kartach.

## Najważniejsze problemy
- [P0] Przychodzące połączenie może zniknąć: baner ukryty na /agent/desktop (incoming-call-banner.component.ts:19-21), Kalendarz nie zamyka się przy dzwonieniu, brak fokusu i aria-live, „Zamknij” wycisza dzwonek, CTA nie odbiera. Fix: dzwonienie zamyka Kalendarz, aktywuje PHONE, fokus na „Odbierz”; baner role=alert; „Odrzuć” zamiast „Zamknij”. → /impeccable harden
- [P0] Brak skrótów klawiszowych w agent/shared/core; menu statusu bez klawiatury. Fix: serwis skrótów w AgentShell, aria-keyshortcuts, ściąga. → /impeccable harden
- [P1] Esc rozsynchronizowuje natywne dialogi (dyspozycja, przerwa, oddzwonienia); ACW ogłaszany co sekundę, błąd walidacji od razu, modal blokuje czaty. Fix: obsługa (cancel), przywrócenie fokusu, licznik poza aria-live, ACW jako niemodalny stan karty. → /impeccable harden, /impeccable shape
- [P1] Praca na kilku kontaktach: szkice niszczone przez @switch, × rozłącza bez potwierdzenia (zagnieżdżony button w tab), karty bez stanu, przydział e-mail/social tylko na Desktopie. Fix: magazyn szkiców per kontakt, znaczniki na kartach, przydział na poziomie powłoki, blokada zamknięcia aktywnej rozmowy. → /impeccable shape
- [P1] Podłoga WCAG/i18n: kontrast --text-muted w ciemnym motywie, KPI 9,5 px, teksty na sztywno po polsku (część bez diakrytyków), fałszywe KPI „cel 90%”/„z 8:00”, marka na sztywno, dwa <main>. → /impeccable audit, /impeccable clarify

## Czerwone flagi według person
Alex: zero skrótów; menu statusu bez klawiatury; dwa kliknięcia, żeby odebrać; przerwa = 6 pól bez wartości domyślnych; dwa toasty po dyspozycji.
Sam: brak przeniesienia fokusu; fokus na body po Esc; ACW co sekundę; dwa <main>; polskie nazwy dostępne bez diakrytyków; tekst wyciszony 2,8:1.
Agentka na zmianie: CHAT to placeholder; telefon kasuje szkic; ACW blokuje 3 czaty; karty nie mówią, gdzie ktoś czeka; status i e-maile znikają poza Desktopem; × rozłącza; teksty w rodzaju męskim.

## Drobne obserwacje
Karta klienta tylko dla PHONE/EMAIL, CTA prowadzą na /supervisor/...; jasna pigułka statusu (#e8f5e9) w ciemnym motywie; „Po kontakcie” w kolorze akcentu (Lamp Rule); zielony nagłówek Add break; martwy agent-dashboard.component.ts; breadcrumb na stronie jednopoziomowej; stałe szerokości kolumn 264/280 px; 123 fallbacki hex w agent-desktop.component.scss.

## Pytania do rozważenia
1. Czy pulpit agenta potrzebuje nawigacji bocznej i breadcrumbów?
2. Czy dyspozycja nie powinna być stanem karty zamiast blokującej nakładki?
3. Bez myszy: które trzy czynności agent musi wykonać w pierwszej minucie i czy da się choć jedną?
