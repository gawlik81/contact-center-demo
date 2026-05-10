## Review: EPIC-21 — V053 + V054 — 2026-05-08

**Branch:** EPIC-21  
**Reviewer:** senior-code-reviewer agent  
**Migracje:** `V053__add_not_reached_callback_status.sql`, `V054__add_campaign_contact_record_id_to_scheduled_callback.sql`

---

### MEDIUM

#### [V053:26-29] Indeks częściowy idx_campaign_contact_dialer — uwaga na typ kolumny status

Predykat `WHERE status IN ('PENDING', 'NO_ANSWER')` jest poprawny dla TEXT/VARCHAR. Jeśli kolumna zostanie zmieniona na PostgreSQL ENUM — indeks przestanie być używany bez ostrzeżenia. Warto dodać komentarz ostrzegający.

Dodatkowo: `DROP INDEX IF EXISTS` bez `CONCURRENTLY` — na dużej tabeli może blokować DML. Rozważ `CONCURRENTLY`.

---

#### [V053:32-58] DROP MATERIALIZED VIEW bez maintenance window

`DROP MATERIALIZED VIEW` wymaga `AccessExclusiveLock`. Przy regularnym odświeżaniu lub zapytaniach dashboardowych może trwać długo. Brak komentarza ostrzegawczego.

Brak RLS na `mv_campaign_stats` — widok zawiera `tenant_id` ale nie ma row-level security. Bezpieczeństwo w pełni po stronie aplikacji.

---

### LOW

#### [V054:1-14] Indeks idx_scheduled_callback_cc_record bez tenant_id

Indeks `(campaign_contact_record_id)` bez `tenant_id`. UUID może nie być globalnie unikalne między tenantami. Powinno być `(tenant_id, campaign_contact_record_id)`.

---

#### [V053] Brak statusu SKIPPED w materialized view mv_campaign_stats

`SKIPPED` jest prawidłowym statusem (CHECK constraint), ale nie ma kolumny `skipped_records` w MV. Dashboard nie wyświetli pominiętych rekordów.

---

### Pozytywne obserwacje

- Migracja V053 aktualizuje zarówno `campaign_contact` jak i `campaign_contact_archive` — zachowanie spójności archive często pomijane w analogicznych PR.
- Poprawna konwencja: nowa kolumna `campaign_contact_record_id` zamiast przeciążenia istniejącej kolumny (zgodnie z anti-pattern z CLAUDE.md).

---

## Review: V031__add_dialer_indexes.sql — 2026-04-08

### Bugs / Critical Issues

_None identified._

### Security Concerns

_None identified._

### Architecture / Pattern Violations

**[V031:17] Partial index `idx_campaign_contact_dialer_tenant` — predykat `WHERE status = 'PENDING'` na kolumnie VARCHAR**

```sql
CREATE INDEX IF NOT EXISTS idx_campaign_contact_dialer_tenant
    ON campaign_contact (tenant_id, campaign_id, status, next_attempt_at)
    WHERE status = 'PENDING';
```

Predykat `WHERE status = 'PENDING'` jest poprawny składniowo — stała tekstowa jest IMMUTABLE. Jednak kolumna `status` jest już włączona w klucz indeksu `(tenant_id, campaign_id, status, next_attempt_at)`. Oznacza to, że kolumna `status` jest zarówno w predykacie filtra jak i w kolumnach indeksu. To jest redundancja: skoro indeks dotyczy wyłącznie wierszy z `status = 'PENDING'`, kolumna `status` w kluczu zawsze będzie miała tę samą wartość i nie wnosi informacji porządkującej. Należy usunąć `status` z listy kolumn klucza, zostawiając go tylko w predykacie:

```sql
CREATE INDEX IF NOT EXISTS idx_campaign_contact_dialer_tenant
    ON campaign_contact (tenant_id, campaign_id, next_attempt_at)
    WHERE status = 'PENDING';
```

Taki indeks jest mniejszy (3 kolumny zamiast 4) i nadal obsługuje zapytania filtrujące po `tenant_id`, `campaign_id` i sortujące/filtrujące po `next_attempt_at` wśród wierszy PENDING.

---

**[V031:22] Partial index `idx_campaign_running_tenant` — ta sama redundancja kolumny**

```sql
CREATE INDEX IF NOT EXISTS idx_campaign_running_tenant
    ON campaign (tenant_id, status)
    WHERE status = 'RUNNING';
```

Identyczny problem: `status` w predykacie i w kluczu. Poprawna forma:

```sql
CREATE INDEX IF NOT EXISTS idx_campaign_running_tenant
    ON campaign (tenant_id)
    WHERE status = 'RUNNING';
```

---

**[V031:27] Partial index `idx_callback_ready` — brak kolumny `scheduled_at` w kluczu mimo jej filtrowania w zapytaniach**

```sql
CREATE INDEX IF NOT EXISTS idx_callback_ready
    ON scheduled_callback (tenant_id, scheduled_at)
    WHERE status = 'PENDING';
```

Ten indeks jest poprawny — `status` nie jest w kluczu, tylko w predykacie. Jednak w `ScheduledCallbackRepository.findDueCallbacks` (linia 133) zapytanie filtruje `scheduled_at <= NOW()` i sortuje `ORDER BY scheduled_at ASC`. Indeks na `(tenant_id, scheduled_at)` przy predykacie `status = 'PENDING'` dobrze obsługuje to zapytanie. Brak uwag.

---

**[V031] Brak migracji tworzącej tabelę `scheduled_callback`**

Plik `V031__add_dialer_indexes.sql` zakłada istnienie tabeli `scheduled_callback` (z zależności na V009), ale `V009__create_campaign.sql` nie tworzy tabeli `scheduled_callback` — nowa encja `ScheduledCallback.java` mapuje na tę tabelę. W codebase brak migracji tworzące tę tabelę. Flyway uruchomi V031 i padnie z błędem `relation "scheduled_callback" does not exist` jeśli tabela nie istnieje. Należy sprawdzić, czy tabela `scheduled_callback` faktycznie istnieje w V009 lub innej migracji, i ewentualnie dodać brakującą migrację `V030__create_scheduled_callback.sql` (lub dodać CREATE TABLE do V031 z odpowiednim komentarzem).

**Krytyczne: brak tej tabeli spowoduje błąd startu aplikacji.**

---

### Improvements & Suggestions

**[V031] Brak `COMMENT ON INDEX` dla `idx_callback_ready`**

Dwa pierwsze indeksy mają `COMMENT ON INDEX`, trzeci (`idx_callback_ready`) nie ma. Drobny brak spójności.

**[V031] Brak `COMMENT ON INDEX` dla nowo dodanego indeksu `idx_callback_ready`**

Indeksy `idx_campaign_contact_dialer_tenant` i `idx_campaign_running_tenant` mają `COMMENT ON INDEX`. `idx_callback_ready` nie ma — drobna niespójność, warto dodać dla kompletności.

### Positive Observations

- **`CREATE INDEX IF NOT EXISTS`** — migracja jest idempotentna; bezpieczna do ponownego uruchomienia.
- **Komentarze `COMMENT ON INDEX`** dla dwóch z trzech indeksów — dobra praktyka dokumentowania celu indeksu bezpośrednio w bazie.
- **Uzasadnienie wyboru indeksów** w komentarzu SQL (linie 9–16) wyjaśnia wzorzec zapytania, który indeks obsługuje — cenne dla przyszłych deweloperów.
- **Dedykowane indeksy dla dialera** zamiast polegania na istniejących — świadczy o analizie wzorców dostępu.

### Summary

Migracja jest bezpieczna formalnie, ale zawiera redundancję kolumny `status` w dwóch partial indexach (kolumna w predykacie i w kluczu jednocześnie), co zwiększa rozmiar indeksów bez korzyści. Krytycznym potencjalnym problemem jest brak migracji tworzącej tabelę `scheduled_callback` — bez niej V031 i aplikacja nie uruchomią się. Wymaga weryfikacji, czy tabela jest tworzona przez inną migrację.

**Ocena: 3/5** — poprawna intencja, wymagana weryfikacja istnienia tworzonej tabeli i korekta redundancji w predykatach.

---

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
