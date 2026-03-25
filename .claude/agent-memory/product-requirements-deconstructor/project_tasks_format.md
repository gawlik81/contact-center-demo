---
name: Format pola zależności w plikach TASKS
description: Pole zależności w plikach TASKS nosi nazwę Zależy od (od 2026-03-25); wcześniej było Zależności
type: project
---

W plikach TASKS-BACKEND.md, TASKS-FRONTEND.md, TASKS-DATABASE.md każdy task ma pole `**Zależy od:**` z listą ID tasków zależnych (lub "brak").

**Why:** Zmieniono nazwę z `**Zależności:**` na `**Zależy od:**` 2026-03-25 dla ujednolicenia z formatem wymaganym przez PRD deconstructor.

**How to apply:** Przy tworzeniu nowych tasków używaj zawsze `**Zależy od:**` (nie `**Zależności:**`).
