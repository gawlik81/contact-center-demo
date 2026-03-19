---
name: JSONB phone array query pattern
description: Jak poprawnie odpytywać klientów po numerze telefonu w tablicy JSONB phone[] w PostgreSQL
type: feedback
---

Kolumna `phone` w tabeli `customer` to JSONB array (nie `TEXT[]`), więc operatora `ANY()` nie można użyć.

Poprawne zapytanie native SQL z operatorem JSONB `@>` (contains):

```sql
SELECT * FROM customer
WHERE tenant_id = CAST(:tenantId AS uuid)
  AND phone @> to_jsonb(CAST(:phone AS text))
  AND is_deleted = false
LIMIT 1
```

`to_jsonb(CAST(:phone AS text))` konwertuje string na JSON string, który może być porównany z elementem tablicy JSONB.

Korzysta z indeksu GIN `idx_customer_phone_gin` (V006, `jsonb_path_ops`) – czas < 10ms przy milionach rekordów.

**Why:** Kolumna phone to JSONB nie TEXT[] – ANY() działa tylko na SQL arrays. operator @> z GIN index jest optymalny dla JSONB.

**How to apply:** Zawsze przy wyszukiwaniu po polu JSONB array w tabeli customer (i innych tabelach z JSONB arrays).
