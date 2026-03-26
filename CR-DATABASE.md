## Review: V029__add_email_address_to_queue.sql — 2026-03-26

### Bugs / Critical Issues

_None identified._

### Security Concerns

_None identified._

### Architecture / Pattern Violations

_None identified._

### Improvements & Suggestions

**[V029:29–31] UNIQUE constraint `uq_queue_tenant_email_address` jest nadmiarowy wobec indeksu `idx_queue_email_address`**

```sql
ALTER TABLE queue
    ADD CONSTRAINT uq_queue_tenant_email_address
        UNIQUE (tenant_id, email_address);

CREATE INDEX idx_queue_email_address
    ON queue (tenant_id, email_address)
    WHERE email_address IS NOT NULL;
```

PostgreSQL przy tworzeniu UNIQUE constraint automatycznie tworzy pełny B-tree index na `(tenant_id, email_address)` bez filtra. Następnie `CREATE INDEX ... WHERE email_address IS NOT NULL` tworzy drugi, partial index na tych samych kolumnach. Wynik: dwa indeksy na `(tenant_id, email_address)` — jeden pełny (z constraintu) i jeden partial. Lookup w `findByEmailAddressAndTenantId` skorzysta tylko z partial indexu (gdy `email_address IS NOT NULL`), ale constraint utrzymuje pełny index dla wszystkich wierszy, włącznie z tymi, gdzie `email_address IS NULL`.

Partial index zapewnia unikalność tylko dla wierszy `IS NOT NULL`. Constraint pełny jest więc wymagany dla unikalności semantycznej (PostgreSQL nie pozwala zdefiniować UNIQUE constraint jako partial). Jest to jednak sytuacja, gdzie utrzymywane są dwa overlappingowe indeksy dla każdego wiersza z `email_address IS NOT NULL`.

Jeśli unikalność semantyczna jest ważniejsza niż rozmiar indeksu — obecne podejście jest prawidłowe. Jeśli priorytetem jest rozmiar, można rozważyć usunięcie ręcznie tworzonego partial indexu i pozostanie tylko z indeksem z UNIQUE constraint (który PostgreSQL utrzymuje automatycznie, choć jest pełny, nie partial).

**[V029:40–42] CHECK constraint `chk_queue_email_address_format` — zbyt liberalna walidacja formatu**

```sql
CHECK (email_address IS NULL OR email_address LIKE '%@%')
```

Komentarz w migracji poprawnie stwierdza, że "szczegółowa walidacja RFC 5322 pozostaje po stronie aplikacji". Warunek `LIKE '%@%'` przepuści jednak oczywiste błędy jak `@`, `@@`, `a@`, `@b`, `a@ b`, spacje wewnątrz, cudzysłowy itp. — wszystko co ma co najmniej jeden `@`. Format `"Name <email@domain.com>"` (RFC 5322 encoded display name) przejdzie ten check, ale `findByEmailAddressAndTenantId` szuka case-insensitive match — jeśli kolumna zawiera format z `"Name <...>"`, dopasowanie nie nastąpi.

Minimalne wzmocnienie (bez regex-ów): `email_address LIKE '%@%.%'` — wymaga przynajmniej jednej kropki po `@`, co wykluczy `user@domain` bez TLD. Nie jest to RFC 5322, ale spójniejsze z oczekiwanym formatem prostego adresu email.

### Positive Observations

- **NULL semantics dla UNIQUE constraint** — komentarz w migracji explicite dokumentuje, że PostgreSQL traktuje NULL jako wartości różne w indeksach UNIQUE, co uzasadnia brak problemu z wieloma kolejkami bez adresu email. Dokumentacja tej nieoczywistej cechy PostgreSQL jest wartościowa dla przyszłych developerów.
- **Partial index `WHERE email_address IS NOT NULL`** — pomija NULL-owe wiersze w indeksie wyszukiwania, co jest poprawną optymalizacją dla kolumny opcjonalnej. Predykat używa wyłącznie `IS NOT NULL` (funkcja IMMUTABLE) — zgodne z wymogiem architektury.
- **Backwards-compatible `ADD COLUMN ... NULL`** — domyślna wartość NULL nie wymaga aktualizacji istniejących wierszy i nie blokuje tabeli przy dużym wolumenie danych.
- **Migracja idempotentna** — `ALTER TABLE ADD COLUMN` i `ADD CONSTRAINT` są bezpieczne dla Flyway; brak `IF NOT EXISTS` jest akceptowalny gdy migracje nie są ponawiane ręcznie.
- **Zależność udokumentowana w komentarzu** — `-- Zaleznosci: V008 (tabela queue)` to dobra praktyka czytelności.

### Summary

Migracja jest poprawna i bezpieczna. Jeden wzorzec architektoniczny — jednoczesne utrzymywanie UNIQUE constraint (pełny index) i ręcznie tworzonego partial indexu na tych samych kolumnach — może być zbędny. Komentarze są wzorcowe.

**Ocena: 4.5/5** — solidna migracja z dobrą dokumentacją, drobna optymalizacja indeksów możliwa, ale nie krytyczna.
