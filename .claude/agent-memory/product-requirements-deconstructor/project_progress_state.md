---
name: project_progress_state
description: Aktualny stan ukończenia zadań DB/BE/FE (2026-08-09, po pełnej rekoncyliacji /update-progress) — DB 45/54, BE 110/119, FE 102/109; jedyny nierozpoczęty epik to EPIC-29
metadata:
  type: project
---

Stan zweryfikowany bezpośrednio w `TASKS-DATABASE.md`/`TASKS-BACKEND.md`/`TASKS-FRONTEND.md`
oraz w `PROGRESS.md` (sekcja „Podsumowanie") na **2026-08-09**, po pełnym przebiegu
`/update-progress`: **DB 45/54, BE 110/119, FE 102/109 — RAZEM 257/282.** Jedyny epik z
zadaniami nierozpoczętymi to **EPIC-29** (Partycjonowanie i retencja danych z obsługi
kontaktów, 25/25 ⬜) — wszystkie pozostałe epiki EPIC-01..EPIC-28 są w pełni ukończone.
`PROGRESS.md` jest teraz zsynchronizowany z TASKS-*.md (poprzednia rozbieżność EPIC-28 —
patrz niżej — została naprawiona).

**Why:** Ten plik jest migawką stanu — sprawdzaj bezpośrednio pliki TASKS-*.md przed poleganiem
na liczbach tutaj, bo stan postępuje przy każdej sesji egzekucji.

**How to apply:** Przed dekonstrukcją nowego epika zawsze zweryfikuj aktualny najwyższy numer
ticketu BEZPOŚREDNIO w plikach TASKS-*.md i najwyższą migrację Flyway. Następny epik powinien
zaczynać numerację od DB-055 / BE-120 / FE-110 / V091.

## Naprawiona historyczna rozbieżność EPIC-28 (2026-08-09)
Sekcja „Podsumowanie" `PROGRESS.md` przez kilka sesji błędnie pokazywała EPIC-28 jako 19
ticketów nierozpoczętych, mimo że TASKS-*.md od dawna pokazywały go jako w pełni ukończony.
Root cause: przy dodawaniu nowych epików (EPIC-29) ktoś dopisywał deltę do STAREJ, już
nieaktualnej liczby zamiast przeliczać sumę od zera z plików TASKS-*.md. **Naprawione
2026-08-09** — sekcja „Podsumowanie" przeliczona mechanicznie ze statusów w plikach źródłowych,
niezależnie zweryfikowano kod EPIC-28 (klasy domenowe `domain/plugin/`, moduł Maven
`plugin-sdk`, migracje V074-V077, iframe sandbox bez `allow-same-origin`) — faktycznie w pełni
ukończony. **Lekcja na przyszłość:** przy każdym dodaniu epiku do PROGRESS.md, przeliczaj
sekcję „Podsumowanie" od zera ze stanu plików TASKS-*.md, nigdy nie dodawaj delty do istniejącej
liczby w PROGRESS.md — ta klasa błędu powtórzyła się już 3 razy (EPIC-27, EPIC-28, i niemal
znowu przy EPIC-29 w poprzedniej sesji).

## Znaleziska sesji 2026-08-09 (`/update-progress`, weryfikacja epik-po-epiku)
- **EPIC-25** (DB-036/037, BE-078..085, FE-081..085 — 15 ticketów): wszystkie były błędnie ⬜
  mimo w pełni zaimplementowanego, zacommitowanego kodu (commity `e2bc907`/`039f48c`/`e7b973b`/
  `a482ed8`, 2026-05-15/21). Przełączone na ✅.
- **EPIC-21..24** (BE-068/074-077, FE-069-072/076-080 — 13 ticketów): ta sama klasa błędu,
  kod istniał i był zacommitowany, status w TASKS-*.md pozostał ⬜. Przełączone na ✅.
- Kilkanaście brakujących/asymetrycznych pól `**Zależy od:**`/`**Blokuje:**` naprawionych w
  starszych epikach (EPIC-01, 03, 04, 08, 09, 13, 20, 25) — pełna lista w historii commitów
  `TASKS-DATABASE.md`/`TASKS-BACKEND.md`/`TASKS-FRONTEND.md` z tej daty.
- Kilka realnych, udokumentowanych (jako notatka w treści ticketu, status ✅ pozostał) gapów
  funkcjonalnych: BE-060 (`ScheduledCallbackExecutor.resolveCallbackFromNumber` nie stosuje
  `campaign.getCallerId()` dla callbacków kampanijnych), brakujące testy dla BE-018/BE-030b/
  BE-048/BE-053/FE-048, FE-026 (stara trasa `settings/twilio` nadal współistnieje ze `settings/
  phone-numbers`, nieusunięta), FE-038 (`QueueAssignmentPanelComponent` to martwy kod — funkcja
  realizowana przez nieudokumentowany `QueueAgentsModalComponent`).
- **Pokrycie weryfikacji nie jest równe we wszystkich epikach.** EPIC-05..11, 21..24, 25..27,
  28, 29 dostały pełny, systematyczny przegląd kod-po-tickecie. EPIC-01..04+fundament i
  EPIC-17..20 dostały pokrycie częściowe/przypadkowe (przez dociekanie zależności między
  ticketami, nie pełny systematyczny audyt). **EPIC-12..16 (DB-022..028, BE-036..053 poza
  BE-038/039/040/048/053, FE-028..045 poza FE-038) NIE dostał pełnej weryfikacji kod-po-tickecie
  w tej sesji** — subagent do tego klastra padał z powodu limitu API sesji i mimo wznowienia nie
  zdążył zwrócić kompletnego raportu. Zalecana kolejna sesja `/update-progress` skupiona
  konkretnie na EPIC-12..16, jeśli dokładność w tym zakresie jest krytyczna.

## Historia epików (najnowsze na końcu)
- EPIC-25 (Kampanie — refaktor i transfer): DB-032–037, BE-062–085, FE-069–085 — ✅ w pełni
  ukończony, status naprawiony 2026-08-09
- EPIC-26 (AI-Powered Conversation Summary): DB-038–039, BE-086–091, FE-086–089 — ✅ zakończony.
  Uwaga: DB-039/BE-089 opisują kolumny `contact.ai_summary*`, które od migracji
  `V068__extract_ai_summary_to_own_table.sql` zostały przeniesione do tabeli
  `contact_ai_summary` — opis ticketów jest nieaktualny wobec kodu (patrz notatka w treści).
- EPIC-27 (Własne dyspozycje per kampania i kolejka): DB-040/041, BE-092..096, FE-090..096 —
  ✅ w pełni ukończony (BE-095 miał sprzeczność nagłówek/status, naprawione)
- EPIC-28 (Per-Tenant Plugin/Extension System): DB-042..045, BE-097..107 (+ addendum BE-108,
  bez własnego nagłówka), BE-110, FE-097..102 — ✅ **ukończony w pełni**, niezależnie
  zweryfikowane 2026-08-09 (drugi raz, po 2026-08-08) — bardzo wysokie zaufanie do tego wyniku
- EPIC-29 (Partycjonowanie i retencja danych z obsługi kontaktów): DB-046..054, BE-111..119,
  FE-103..109 — ⬜ nadal w pełni nierozpoczęty, niezależnie zweryfikowane 2026-08-09 (brak
  migracji V082-090, brak klas retention, brak komponentów FE)

## Najwyższe numery na 2026-08-09
- DB-054 / BE-119 / FE-109 / V090 (ostatnia zarezerwowana, jeszcze niezaaplikowana migracja)
- Następny epik powinien zaczynać numerację od DB-055 / BE-120 / FE-110 / V091
