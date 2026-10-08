---
name: feedback_explain_rls_deny_proof
description: Dowodzenie "brak polityki RLS = odmowa" przez EXPLAIN (COSTS OFF) bez ANALYZE, bezpieczne w sesji tylko-do-odczytu, dla SELECT/UPDATE/DELETE (nie działa dla WITH CHECK na INSERT)
metadata:
  type: feedback
---

**Technika:** żeby udowodnić, że tabela z RLS włączonym ale BEZ polityki dla danej komendy
rzeczywiście odmawia dostępu (a nie tylko "wygląda na to" z `pg_policies`), bez jakiegokolwiek
zapisu do bazy: `EXPLAIN (COSTS OFF) <SELECT|UPDATE|DELETE> ...` wewnątrz
`BEGIN; SET ROLE app_user; SELECT set_config('app.current_tenant_id', <uuid>, true); ...; ROLLBACK;`.
`EXPLAIN` bez `ANALYZE` TYLKO planuje, nie wykonuje — bezpieczne nawet dla `UPDATE`/`DELETE` w
sesji z `default_transaction_read_only=on`.

**Why:** DB-071 (2026-10-08) potrzebował dowodu per-komenda (SELECT/INSERT/UPDATE/DELETE × tabela)
bez pisania do bazy demo (WP-4). `pg_policies` mówi tylko co ZADEKLAROWANO, nie co silnik
rzeczywiście robi przy braku polityki.

**Jak wygląda dowód:**
- Brak polityki dla komendy na tabeli RLS-enabled → planner dokleja stały `false` do qualifiera:
  - Czysty `DELETE ... WHERE pk = <const>` → `Delete on t -> Result -> One-Time Filter: false`
    (silnik dowodzi zbiór pusty, bez skanu tabeli).
  - `UPDATE/DELETE ... WHERE <predykat biznesowy>` → `Filter: (false AND <predykat>)` doklejone do
    normalnego planu skanu.
- Polityka OBECNA dla komendy → qual realny, np.
  `Filter: (tenant_id = (current_setting('app.current_tenant_id', true))::uuid)` albo
  `Index Cond:` tym samym wyrażeniem jeśli indeks na `tenant_id` istnieje.
- Tabela BEZ RLS wcale (`relrowsecurity=f`) → plan to zwykły `Seq Scan`/`Index Scan` BEZ żadnego
  `Filter` na `tenant_id` — odwrotność powyższego (brak ograniczenia, nie odmowa).
- MIXED z gałęzią `IS NULL` w polityce (np. `audit_log`) → `Filter: (tenant_id IS NULL) OR
  (tenant_id = GUC)` widoczne wprost w planie (dla partycjonowanej tabeli: `Append` + per-partycja
  `Index Cond: (tenant_id IS NULL)` gdy indeks częściowy to wspiera).

**Ograniczenie — NIE działa dla `WITH CHECK` na INSERT:** `WITH CHECK` jest sprawdzany per-wiersz w
executorze (runtime `ERROR 42501`), nie jest statycznie dowodliwy w planie `EXPLAIN` bez `ANALYZE`
(wartości wstawiane nie są stałymi, GUC nieznany na etapie planowania w sposób umożliwiający
constant-fold identyczny jak USING). Na odmowę INSERT trzeba albo realnego testu (Testcontainers,
poza sesją read-only), albo odwołać się do wcześniej ustalonego faktu
([[feedback_rls_insert_vs_update_semantics]]: brak polityki INSERT = `42501` w runtime, zweryfikowane
empirycznie w DB-062) bez powtarzania zapisem.

**How to apply:** każdy kolejny raport klasyfikacji RLS (DB-072/073/074 i dalsze) — użyj tej techniki
dla macierzy SELECT/UPDATE/DELETE; dla INSERT cytuj poprzednie ustalenie albo deleguj realny test do
Testcontainers przy właściwej migracji (nie w raporcie read-only).

Powiązane: [[feedback_readonly_audit_technique]], [[feedback_rls_testing]],
[[feedback_rls_insert_vs_update_semantics]], [[project_db071_rls_classification_report]]
