---
name: EXPLAIN na partycjach: Seq Scan na pustych partycjach nie jest regresją
description: Asercja „brak Seq Scan" w planie na tabeli partycjonowanej jest niedeterministyczna (puste partycje, koszt 0,00) — asercjuj zapełnioną partycję; sondę planu rób tymczasowym testem Testcontainers
metadata:
  type: feedback
---

Na tabeli partycjonowanej planner (gdy nie ma pruningu, np. klucz łączenia w `USING jsonb_to_recordset` albo `contact_id IN (...)` bez `message_at`) wymienia też PUSTE partycje (`_11`, `_12`, `_default`) jako `Seq Scan … cost=0.00..0.01` — statystyki 0-stronicowe. Zapełniona partycja jest w tym samym planie czytana indeksem.

**Why:** BE-134 (2026-10-04). Globalne `doesNotContain("Seq Scan")` padało raz na jednym, raz na drugim przebiegu zależnie od stanu partycji w kontenerze (flaky). Probe pokazał: `Index Scan using email_message_2026_10_pkey` na zapełnionej partycji + `Seq Scan` tylko na pustych.

**How to apply:**
- Asercja planu: `populated = SELECT tableoid::regclass::text FROM tabela WHERE tenant_id = ? LIMIT 1`; potem `containsPattern("Index (Only )?Scan using \\S+ on " + populated)` i `doesNotContain("Seq Scan on " + populated)`.
- Nazwy indeksów na partycjach są generowane (`*_2026_10_pkey`) — szukaj rodzica przez `PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren`, nie literału.
- Gdy trzeba zobaczyć plan, którego nie znasz: TYMCZASOWY test Testcontainers, który `System.out.println`-uje `EXPLAIN`, uruchom `-Dtest=…` i usuń przed commitem. NIE baza demo (`cc-postgres`) — zakaz z CLAUDE.md dla zapisów.

Powiązane: [[feedback_shared_db_integration_test_partition_leak]], [[project_be134_email_message_composite_key]].
