---
name: feedback_jdbc_batchupdate_param_count_mismatch
description: JdbcTemplate.batchUpdate(sql, List<Object[]>) wymaga że długość każdej tablicy Object[] dokładnie odpowiada liczbie "?" w SQL — łatwo to złamać gdy tablica "row" pełni podwójną rolę (marker + dane)
metadata:
  type: feedback
---

W `CustomerImportServiceImpl.batchInsertCustomers()` (BE-026, import CSV klientów) `rows`
(wynik `buildInsertRow()`) był przekazywany BEZPOŚREDNIO do
`jdbcTemplate.batchUpdate(sql, rows)`. Tablica `row[]` miała 8 elementów
(`[null, tenantId, firstName, lastName, phone, email, customFields, createdAt]`), gdzie
`row[0]=null` to tylko znacznik routingu dla `flushBatch()` (insert vs update) —
NIE parametr SQL (bo `customer_id` jest generowany przez `gen_random_uuid()`, nie
przekazywany jako `?`). SQL INSERT miał tylko 7 placeholderów `?`. Efekt: parametry
byłyby przesunięte o jeden (tenant_id dostałby `null`, first_name dostałby tenantId itd.),
a 8-ty element wywołałby `SQLException: parameter index out of range`.

Dla porównania `batchUpdateCustomers()` robił to poprawnie — budował NOWĄ tablicę
`updateParams` mapując tylko potrzebne indeksy `row[N]` w kolejności placeholderów SQL,
zamiast przekazywać surowy `row[]` z markerem.

**Why:** Bug pozostawał niewykryty, bo `CustomerImportServiceTest` mockuje `JdbcTemplate`
całkowicie (`@Mock private JdbcTemplate jdbcTemplate`) — testy weryfikują tylko że SQL
zawiera "INSERT INTO customer" i że `batchUpdate` zostało wywołane, nigdy nie sprawdzają
zgodności liczby parametrów z placeholderami. Brak testu integracyjnego (Testcontainers)
dla tej ścieżki importu.

**How to apply:** Naprawiono przy okazji BE-XXX (dodanie `external_id` do importu CSV,
2026-07-05) — `batchInsertCustomers` teraz też buduje osobną tablicę `insertParams`
(bez `row[0]` markera), analogicznie do `batchUpdateCustomers`. Przy każdej zmianie
`jdbcTemplate.batchUpdate(sql, rows)` w tym repo: policz ręcznie liczbę `?` w SQL i
porównaj z długością przekazywanej tablicy Object[] — nie ufaj że "marker" na pozycji 0
zostanie pominięty automatycznie. Jeśli tablica `row[]` pełni podwójną rolę (routing +
dane), zawsze buduj osobną tablicę parametrów tuż przed wywołaniem `batchUpdate`.
