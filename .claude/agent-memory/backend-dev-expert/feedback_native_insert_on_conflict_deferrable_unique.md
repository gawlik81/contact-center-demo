---
name: ON CONFLICT nie działa na constraint DEFERRABLE — natywny INSERT z deduplikacją
description: PostgreSQL odrzuca ON CONFLICT ON CONSTRAINT na unikalności DEFERRABLE; dedup w natywnym INSERT = WHERE NOT EXISTS + pg_advisory_xact_lock (BE-134, email_message)
metadata:
  type: feedback
---

Dla natywnego INSERT-u z deduplikacją po unikalności `DEFERRABLE INITIALLY DEFERRED` NIE używaj `ON CONFLICT ON CONSTRAINT <nazwa> DO NOTHING` — PostgreSQL zwraca błąd „ON CONFLICT does not support deferrable unique constraints/exclusion constraints as arbiters". Wzorzec, który działa:

- `INSERT … SELECT <parametry z jawnym CAST> WHERE NOT EXISTS (SELECT 1 FROM tabela WHERE <klucz dedup>) RETURNING pk` — pusty wynik = duplikat.
- Przed INSERT-em `SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))` (klucz = tenant + nagłówek) w tej samej transakcji — serializuje wyścig; drugi INSERT widzi zatwierdzony wiersz. Bez locka dwa INSERT-y przechodzą `NOT EXISTS`, a błąd wychodzi dopiero w COMMIT (odroczony constraint).
- W `INSERT … SELECT` każdy parametr musi mieć `CAST(:x AS typ)` — `unknown` resolvuje się do `text` i nie przypisuje się do `timestamptz`/`uuid`/`jsonb`.

**Why:** BE-134 (2026-10-04). Sprawdzone na PG 16 w Testcontainers; test wyścigu dwóch wątków (`EmailMessageIngestionIntegrationTest$Redelivery`) przechodzi.

**How to apply:** każdy natywny INSERT na tabeli z constraintem DEFERRABLE (np. `email_message`, `uq_email_message_id_header`). Nie łap `DataIntegrityViolationException` wewnątrz transakcji (rollback-only, patrz BE-132). Powiązane: [[project_be134_email_message_composite_key]], [[feedback_partitioned_table_jpa]].
