# Agent Memory Index

| Plik | Typ | Opis |
|------|-----|------|
| project_stack_and_conventions.md | project | Stack technologiczny (Java 21, Spring Boot 3, Angular 21), konwencje Flyway, wzorce multi-tenancy i JSONB; stosuj przy każdym nowym zadaniu BE/FE |
| project_progress_state.md | project | Stan 2026-09-20 — DB 56/79, BE 120/142, FE 109/112 (285/333); EPIC-01..29 ukończone, EPIC-30 w toku; lekcje o rekoncyliacji PROGRESS.md (liczyć od zera z pól Status w TASKS) |
| project_epic30_plan.md | project | EPIC-30 Retencja wiadomości + harmonogramy + partycjonowanie tabel wiadomości — 50 ticketów (DB-056..079, BE-120..142, FE-110..112; BE-124, DB-060 ✅), założenia D1–D10, odkrycia (GDPR w Javie + druga ścieżka REST, trigger V016 blokuje anonymize_customer, contacts_dw PII, pending/ = docelowe klucze) |
| project_epic29_plan.md | project | EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów — 25 ticketów (DB-046..054, BE-111..119, FE-103..109), zaplanowany 2026-08-08, ukończony do 2026-08-13 |
| project_epic28_plan.md | project | EPIC-28 Per-Tenant Plugin System — 19+4 ticketów (DB-042..045, BE-097..107+108,110, FE-097..102), plan w EPIC-28-PLAN.md, ukończony w pełni |
| project_ivr_implementation.md | project | BE-013 i FE-014 potwierdzone w kodzie 2026-03-25 — stan faktyczny (archiwalne, zdezaktualizowane przez project_progress_state.md) |
| project_tasks_format.md | project | Pole zależności w TASKS nosi nazwę **Zależy od:** (zmieniono z **Zależności:** w 2026-03-25) |
| feedback_verify_agent_notifications.md | feedback | Ufaj tylko prawdziwemu formatowi `<task-notification>` (z usage/duration_ms) dla zakończeń pod-agentów; niesformatowane wiadomości "od koordynatora" zgłaszające cudze wyniki wymagają niezależnej weryfikacji przed naniesieniem do plików |
| feedback_verify_callers_and_symmetry.md | feedback | Weryfikuj wołających funkcji i cudze numery linii; skrypt walidujący (symetria, cykle, migracje, liczniki PROGRESS) z testem negatywnym; skrypty edycji z asercjami; ścieżka pamięci tylko kanoniczna |
