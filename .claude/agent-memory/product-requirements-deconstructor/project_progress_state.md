---
name: project_progress_state
description: Aktualny stan ukończenia zadań DB/BE/FE (2026-08-08) — DB 39/52, BE 92/112, FE 75/86; EPIC-28 ukończony w pełni, EPIC-29 (25 ticketów) zaplanowany, nierozpoczęty
metadata:
  type: project
---

Stan zweryfikowany bezpośrednio w `TASKS-DATABASE.md`/`TASKS-BACKEND.md`/`TASKS-FRONTEND.md` na
2026-08-08 (NIE z sekcji „Podsumowanie” `PROGRESS.md`, która okazała się nieaktualna — patrz
niżej): **EPIC-28 jest w pełni ukończony** (DB-042..045, BE-097..107 + BE-108/BE-110 dodane po
zamknięciu epiku, FE-097..102) — wszystkie ✅. Brakujące (⬜, nierozpoczęte) to w całości
EPIC-29 (Partycjonowanie i retencja danych z obsługi kontaktów, DB-046..054, BE-111..119,
FE-103..109 — 25 ticketów). Zob. `[[project_epic29_plan]]` dla szczegółów tego epika.

**Ważne odkrycie 2026-08-08:** sekcja „Podsumowanie”/„Nie rozpoczęte wg EPIC” w `PROGRESS.md`
wciąż pokazywała EPIC-28 jako 19 nierozpoczętych ticketów, mimo że nagłówek dokumentu i treść
TASKS-*.md jednoznacznie potwierdzają pełne ukończenie (ta sama klasa rozbieżności co wcześniej
przy EPIC-27). Dodano notatkę ostrzegawczą w `PROGRESS.md` zamiast cichej korekty (poza zakresem
zlecenia) — **zalecana sesja `/update-progress` przed kolejną dekompozycją**.

**Why:** Ten plik jest migawką stanu — sprawdzaj `PROGRESS.md` I bezpośrednio pliki TASKS-*.md
przed poleganiem na liczbach tutaj, bo stan postępuje przy każdej sesji egzekucji, a
`PROGRESS.md` bywa niezsynchronizowany (patrz odkrycie wyżej). Wartość tej pamięci to wiedza o
tym, **który epik jest najnowszy i jaki jest jego zakres**, nie precyzyjne liczby na żywo.

**How to apply:** Przed dekonstrukcją nowego epika zawsze zweryfikuj aktualny najwyższy numer
ticketu BEZPOŚREDNIO w `TASKS-DATABASE.md`/`TASKS-BACKEND.md`/`TASKS-FRONTEND.md` i najwyższą
migrację Flyway (`grep -oE '(DB|BE|FE)-[0-9]+' ... | sort -t- -k2 -n -u | tail` + `ls
db/migration | sort -V | tail`) — NIE ufaj ani liczbom w tej pamięci, ani sekcji „Podsumowanie”
`PROGRESS.md`, obie mogą być nieaktualne. Uwaga: niektóre tickety (np. BE-108) mają przydzielone
ID i są zaimplementowane, ale NIE mają własnego nagłówka `### BE-108` — treść jest wklejona jako
addendum w sekcji poprzedniego ticketu. `grep -oE 'BE-[0-9]+'` (bez wymogu nagłówka `###`) łapie
też takie przypadki, `grep '^### BE-'` — nie.

## Historia epików (najnowsze na końcu)
- EPIC-17 (Incoming Call Alert): FE-046, FE-047, FE-048 — 2026-04-28
- EPIC-19 (Wielojęzyczność): DB-029, BE-054, FE-049–FE-065 — 2026-04-28 do 2026-05-03
- EPIC-20 (Per-tenant Twilio config): DB-030, DB-031, BE-055–BE-061, FE-066–FE-068 — 2026-05-05 do 2026-05-07
- EPIC-25 (Kampanie — refaktor i transfer): DB-032–DB-037, BE-062–BE-085, FE-069–FE-085 — ✅ zakończony 2026-05-23
- EPIC-26 (AI-Powered Conversation Summary): DB-038–DB-039, BE-086–BE-091, FE-086–FE-089 — ✅ zakończony 2026-05-24
- EPIC-27 (Własne dyspozycje per kampania i kolejka): DB-040/041, BE-092..096, FE-090..096 —
  status mieszany w TASKS (część ✅ część ⬜); `PROGRESS.md` global summary NIE był
  zsynchronizowany z tym epikiem jeszcze na 2026-06-20
- EPIC-28 (Per-Tenant Plugin/Extension System): DB-042..045, BE-097..107 (+ addendum BE-108,
  bez własnego nagłówka), BE-110, FE-097..102 — ✅ **ukończony w pełni** (potwierdzone
  bezpośrednio w TASKS-*.md 2026-08-08; `PROGRESS.md` „Podsumowanie” błędnie wciąż pokazywał 19
  nierozpoczętych — patrz notatka o odkryciu wyżej)
- EPIC-29 (Partycjonowanie i retencja danych z obsługi kontaktów): DB-046..054, BE-111..119,
  FE-103..109 — ⬜ zaplanowany 2026-08-08, plan w `[[project_epic29_plan]]`

## Najwyższe numery na 2026-08-08 (zweryfikować przed następną dekonstrukcją)
- DB-054 (ostatnia migracja: V090__fix_rls_guc_naming_inconsistency.sql, opcjonalny bonus-fix)
- BE-119
- FE-109
- Następny epik powinien zaczynać numerację od DB-055 / BE-120 / FE-110 / V091
