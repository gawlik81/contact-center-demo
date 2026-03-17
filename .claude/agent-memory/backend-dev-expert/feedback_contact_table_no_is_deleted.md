---
name: Tabela contact nie ma kolumny is_deleted
description: Tabela contact (partycjonowana) nie posiada kolumny is_deleted – native queries muszą to uwzględniać
type: feedback
---

Tabela `contact` (V007) nie ma kolumny `is_deleted`. Nie stosuj filtru `is_deleted = FALSE` w zapytaniach na tej tabeli.

Kolumna `is_deleted` istnieje w: `app_user`, `tenant`, `ivr_queue`, `campaign`, `customer`.

**Why:** Tabela contact jest partycjonowana RANGE po started_at; soft delete realizowany przez status (COMPLETED/ABANDONED).

**How to apply:** Przy pisaniu native queries na tabeli contact – pomijaj filtr `is_deleted`. Sprawdzaj statusy aktywne: `IN ('QUEUED', 'ACTIVE', 'ON_HOLD')`.
