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

> **Zamknięte przez V132 (DB-058, 2026-10-09):** widoki materializowane `mv_campaign_stats`/`mv_agent_daily_stats` i funkcja `refresh_materialized_views()` zostały usunięte.

---

### LOW

#### [V054:1-14] Indeks idx_scheduled_callback_cc_record bez tenant_id

Indeks `(campaign_contact_record_id)` bez `tenant_id`. UUID może nie być globalnie unikalne między tenantami. Powinno być `(tenant_id, campaign_contact_record_id)`.

---

#### [V053] Brak statusu SKIPPED w materialized view mv_campaign_stats

`SKIPPED` jest prawidłowym statusem (CHECK constraint), ale nie ma kolumny `skipped_records` w MV. Dashboard nie wyświetli pominiętych rekordów.

> **Zamknięte przez V132 (DB-058, 2026-10-09):** widok usunięty — uwaga nieaktualna.

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

---

## Review: V069__create_custom_disposition.sql — 2026-05-27

**Branch:** custom-dispozition
**Reviewer:** senior-code-reviewer agent
**Epic:** EPIC-27 — Własne dyspozycje per kampania i kolejka

---

### [CRITICAL] Błędna nazwa zmiennej w polityce RLS

**Plik:** `backend/src/main/resources/db/migration/V069__create_custom_disposition.sql:55`

**Problem:** Polityka RLS używa `current_setting('app.tenant_id', TRUE)`, podczas gdy cały projekt (V012, V023, TenantAwareRepository) ustawia zmienną `app.current_tenant_id` przez funkcję `set_tenant_context()`. Nazwy się nie zgadzają — polityka nigdy nie zadziała poprawnie.

```sql
-- V069 (BŁĄD):
USING (tenant_id = current_setting('app.tenant_id', TRUE)::UUID);

-- Powinno być (zgodnie z V012 i wszystkimi innymi politykami):
USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);
```

**Sugestia:** Poprawić w nowej migracji `V070__fix_custom_disposition_rls_setting_name.sql`:
```sql
DROP POLICY custom_disposition_isolation ON custom_disposition;
CREATE POLICY custom_disposition_isolation ON custom_disposition
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);
```

---

### [MAJOR] Brak `WITH CHECK` w polityce RLS

**Plik:** `backend/src/main/resources/db/migration/V069__create_custom_disposition.sql:54-55`

**Problem:** Polityka RLS ma tylko `USING` (dla odczytu i DELETE), ale brak `WITH CHECK` (dla INSERT i UPDATE). Oznacza to, że polityka nie chroni przed zapisem wiersza z innym `tenant_id`, jeśli sesja ma ustawiony inny `app.current_tenant_id`. Wszystkie inne tabele w projekcie (V012) używają zarówno `USING` jak i `WITH CHECK`.

**Sugestia:** W nowej migracji dodać:
```sql
WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);
```

---

### [MAJOR] Brak `FORCE ROW LEVEL SECURITY`

**Plik:** `backend/src/main/resources/db/migration/V069__create_custom_disposition.sql:53`

**Problem:** Kluczowe tabele projektu (customer, contact, campaign, queue w V012:83-86) mają `FORCE ROW LEVEL SECURITY`, które gwarantuje stosowanie RLS nawet dla właściciela tabeli. `custom_disposition` tego nie ma — właściciel tabeli (rola PostgreSQL) omija RLS przy INSERT/UPDATE/DELETE.

**Sugestia:**
```sql
ALTER TABLE custom_disposition FORCE ROW LEVEL SECURITY;
```

---

### [MAJOR] Brak `is_deleted` — projekt używa soft-delete jako standardu

**Plik:** `backend/src/main/resources/db/migration/V069__create_custom_disposition.sql`

**Problem:** Wszystkie kluczowe encje projektu (app_user, customer, scheduled_callback) mają kolumnę `is_deleted BOOLEAN NOT NULL DEFAULT FALSE`. Tabela `custom_disposition` używa fizycznego DELETE. Oznacza to brak historii usuniętych dyspozycji i potencjalne problemy z audytem oraz zgodnością z GDPR (np. brak możliwości powiązania historycznych kontaktów z dyspozycją, która była aktywna w chwili połączenia).

**Sugestia:** Rozważyć dodanie `is_deleted BOOLEAN NOT NULL DEFAULT FALSE` i zmianę DELETE na soft-delete. Jeśli jest to celowe odstępstwo, udokumentować decyzję w komentarzu SQL.

---

### [MINOR] Brak kompozytowego indeksu `(tenant_id, id)`

**Plik:** `backend/src/main/resources/db/migration/V069__create_custom_disposition.sql`

**Problem:** Wzorzec `findByIdAndTenantId` wykonuje `WHERE id = ? AND tenant_id = ?`. Istniejące indeksy (kampania, kolejka) nie pokrywają tego przypadku. Zapytania findByIdAndTenantId (używane w update i delete) będą skanowały cały PK index bez filtrowania po tenantId.

**Sugestia:**
```sql
CREATE INDEX idx_custom_disposition_tenant_id
    ON custom_disposition (tenant_id, id);
```

---

### [MINOR] Brak `is_active = FALSE` w indeksach dla widoku supervisora

**Plik:** `backend/src/main/resources/db/migration/V069__create_custom_disposition.sql:45-51`

**Problem:** Indeksy `idx_custom_disposition_campaign` i `idx_custom_disposition_queue` mają predykat `is_active = TRUE`, więc nie są używane przez `findAllByCampaignId` / `findAllByQueueId` (które zwracają ALL wiersze, aktywne i nieaktywne). Supervisor widok wykona sequential scan.

**Sugestia:** Albo usunąć predykat `is_active = TRUE` z indeksów (indeks bez filtra obsługuje oba przypadki), albo dodać osobne indeksy bez predykatu dla widoku supervisora. Obecna konfiguracja jest niespójna z faktycznymi wzorcami zapytań.

---

### Pozytywne obserwacje

- `CHECK CONSTRAINT chk_custom_disposition_scope` (kampania XOR kolejka) jest elegancki i poprawny — gwarantuje integralność danych na poziomie DB bez możliwości obejścia z aplikacji.
- `chk_custom_disposition_tone` jako CHECK constraint jest wzorową obroną przed błędnymi wartościami tonu.
- Partial UNIQUE indexes dla obsługi NULL w PostgreSQL (`WHERE campaign_id IS NOT NULL`) — poprawne i idiomatyczne podejście.
- Używa `gen_random_uuid()` i `DEFAULT NOW()` — zgodne z resztą projektu.
- Komentarze kolumn są kompletne i pomocne.

### Summary

Migracja ma solidny szkielet (CHECK constraints, partial unique indexes, TIMESTAMPTZ), ale ma trzy poważne błędy bezpieczeństwa: błędna nazwa zmiennej w RLS (polityka jest martwa), brak WITH CHECK i brak FORCE ROW LEVEL SECURITY. Bez poprawki RLS, multi-tenancy tej tabeli jest iluzoryczna.

**Ocena: 2/5** — blokuje merge z powodu niedziałającej polityki RLS.

---

## Review: V071__create_disposition_set.sql — 2026-05-28

**Branch:** custom-dispozition
**EPIC:** EPIC-27 (zestawy dyspozycji wielokrotnego użytku)

### Bugs / Critical Issues

_None identified._

### Security Concerns

- **V071:55 (brakuje FORCE ROW LEVEL SECURITY na custom_disposition)** Powiązana tabela `custom_disposition` z V069 ma politykę RLS z `USING` opartą na `'app.tenant_id'` (nie `'app.current_tenant_id'`), a V071 dodaje FORCE RLS tylko na nowych tabelach. To ujawnia, że V069 ma martwą RLS politykę (błędna nazwa zmiennej). Nowa migracja nie naprawia tego błędu mimo że go powiela. Sprawdź czy V070 poprawia tę kwestię — jeśli nie, potrzebna jest osobna migracja poprawkowa.

### Architecture / Pattern Violations

- **V071:18-31 (brak `updated_at` na `disposition_set_item`)** Tabela `disposition_set_item` nie ma kolumn `created_at TIMESTAMPTZ` ani `updated_at TIMESTAMPTZ`. Projekt wymaga tych kolumn na każdej tabeli DB. Co prawda encja `DispositionSetItem.java` też ich nie ma, co świadczy o zamierzonym pomyśle, ale łamie konwencję projektu. W przyszłości będzie trudno implementować auditing/changelog dla tej tabeli bez migracji addytywnej.
  - Fix: Dodaj `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()` do `disposition_set_item` w nowej migracji `V072__add_timestamps_to_disposition_set_item.sql`.

- **V071:33-38 (brak kompozytowego indeksu `(tenant_id, id)`)** Nie ma indeksu `(tenant_id, id)` na tabeli `disposition_set`, który jest standardem projektu do wyszukiwania po PK w kontekście tenanta. Istniejący `idx_disposition_set_tenant (tenant_id, name)` obsługuje sortowanie, ale nie lookup po ID.
  - Fix: `CREATE INDEX idx_disposition_set_tenant_id ON disposition_set (tenant_id, id);`

- **V071:37-38 (indeks na `disposition_set_item` nie zawiera `tenant_id`)** `idx_disposition_set_item_set (set_id, ordinal)` nie zawiera `tenant_id`. RLS zapewnia izolację na poziomie filtrowania wierszy, ale brak `tenant_id` w indeksie oznacza, że planner nie może pominąć skanowania po RLS. Ponieważ `set_id` jest już powiązany z `tenant_id` przez FK, to ryzyko niskie, ale warto zachować spójność ze wzorcem `(tenant_id, ...)` w całym projekcie.

### Improvements & Suggestions

- **V071:15 (UNIQUE constraint zamiast częściowego indeksu)** `uq_disposition_set_tenant_name UNIQUE (tenant_id, name)` to zwykły UNIQUE constraint — poprawny dla tego przypadku, bo pole `name` jest NOT NULL. Dobra decyzja projektowa.

- **V071:27 (UNIQUE na `(set_id, disposition_code)`)** Poprawne ograniczenie unikalności kodu w zakresie zestawu. Spójne z analogicznym wzorcem w `custom_disposition`.

- **V071:28-31 (CHECK constraint na `tone`)** Poprawnie zdefiniowany CHECK constraint z tymi samymi wartościami co w DTO (`positive|negative|neutral|warning`). Walidacja na poziomie DB jest uzupełnieniem walidacji Javy.

### Positive Observations

- Obie tabele mają `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY` i `WITH CHECK` — to pełna i poprawna konfiguracja RLS, lepsza niż w V069 (które brakowało wszystkich trzech elementów).
- RLS używa `current_setting('app.current_tenant_id', TRUE)` z flagą `TRUE` (safe fallback do NULL gdy ustawienie nie istnieje) — zgodne ze standardem projektu.
- FK do `tenant(tenant_id)` i `disposition_set(id)` z `ON DELETE CASCADE` — poprawna semantyka czyszczenia przy usunięciu tenanta lub zestawu.

### Summary

Migracja jest solidna: RLS skonfigurowana poprawnie z FORCE i WITH CHECK, CHECK constraints na tone, UNIQUE constraints na właściwych kolumnach. Główne usterki to brak kolumn `created_at/updated_at` na `disposition_set_item` (naruszenie konwencji projektu) oraz brak indeksu `(tenant_id, id)` na `disposition_set`. Żadna usterka nie blokuje production pod względem bezpieczeństwa.

**Ocena: 4/5** — dobra podstawa, wymaga dodania timestamps na item i composite index (tenant_id, id) przed merge.

---

## Review: V080__add_super_admin_role.sql, V081__refresh_token_nullable_tenant_id.sql — 2026-07-12

**Branch:** rule-refactor
**Kontekst:** refaktor ról SUPER_ADMIN/ADMIN/SUPERVISOR/AGENT — nowa rola globalna `SUPER_ADMIN` z `tenant_id IS NULL` (jedyny wyjątek od reguły „każda tabela ma `tenant_id NOT NULL`” z CLAUDE.md, celowo i wąsko wyegzekwowany przez CHECK constraint). Pełny plan: `linked-questing-sedgewick.md`.

### 🐛 Bugs / Critical Issues

_Brak zidentyfikowanych._

### ⚠️ Security Concerns

_Brak nowych zagrożeń._ Zweryfikowałem względem aktualnej treści migracji, do których odwołuje się komentarz w `V080` (nie tylko zaufałem opisowi w pliku):
- `pol_app_user_select` (V012) rzeczywiście używa `USING (tenant_id = current_setting('app.current_tenant_id')::UUID)` — dla wiersza SUPER_ADMIN (`tenant_id IS NULL`) warunek jest zawsze `NULL`/`false`, więc RLS poprawnie nigdy nie ujawni wiersza SUPER_ADMIN przez sesję z ustawionym kontekstem tenanta. Zgodne z opisem w migracji.
- `chk_super_admin_tenant_invariant` jest jedynym miejscem egzekwującym wyjątek od `tenant_id NOT NULL` — sam CHECK constraint (nie tylko walidacja aplikacyjna) gwarantuje, że żaden przyszły bug w Javie nie utworzy wiersza `ADMIN`/`SUPERVISOR`/`AGENT` z `tenant_id IS NULL`, ani wiersza `SUPER_ADMIN` z `tenant_id` ustawionym. To poprawny wzorzec „defense in depth” na poziomie schematu.

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń._ Zgodność ze standardami z CLAUDE.md zweryfikowana punkt po punkcie:
- Nazewnictwo `V080__add_super_admin_role.sql` / `V081__refresh_token_nullable_tenant_id.sql` — poprawny format `V{NNN}__{description}.sql`, numeracja sekwencyjna kontynuująca `V079`.
- **Wzorowe przestrzeganie reguły „nigdy nie edytuj zaaplikowanej migracji”**: `V081` powstał jako osobny plik po tym, jak podczas implementacji backendu wykryto, że `refresh_token.tenant_id` (NOT NULL od V003) też wymaga zmiany na nullable — zamiast dopisać to do `V080`, autor stworzył nowy plik z jawnym komentarzem tłumaczącym dlaczego (`"stad osobny plik zamiast edycji juz zaaplikowanego V080 (zasada z CLAUDE.md)"`). To dokładnie ten proces, jaki CLAUDE.md nakazuje.
- Partial unique index `uq_super_admin_email ON app_user (LOWER(email)) WHERE role = 'SUPER_ADMIN' AND is_deleted = FALSE` używa wyłącznie `LOWER()` (IMMUTABLE) w predykacie i w wyrażeniu indeksowanym — brak `::DATE`/`NOW()`/innych funkcji mutowalnych, zgodnie z regułą o partial index predicates.
- `tenant_id UUID` (nullable) na `app_user`/`refresh_token` jest jedynym świadomym wyjątkiem od reguły „`tenant_id UUID NOT NULL` na każdej tabeli” z CLAUDE.md — uzasadnionym i, co ważne, **wyegzekwowanym przez CHECK constraint**, a nie tylko udokumentowanym komentarzem. To właściwy sposób na wprowadzenie wyjątku od konwencji projektu: nie cichym pominięciem reguły, tylko jawnym, wąsko zakresowanym constraintem opisującym dokładnie kiedy wyjątek jest dozwolony.
- FK `fk_user_tenant`/`fk_refresh_token_tenant` nie wymagały zmian — poprawnie zauważone w komentarzu, że FK nie waliduje wartości NULL, więc nullable kolumna z FK działa od razu poprawnie bez modyfikacji ograniczenia.

### 🔧 Improvements & Suggestions

- **Brak dedykowanego testu na poziomie bazy (IT/Testcontainers) weryfikującego, że `chk_super_admin_tenant_invariant` faktycznie odrzuca niepoprawną kombinację** (np. `INSERT ... role='ADMIN', tenant_id=NULL` lub `role='SUPER_ADMIN', tenant_id='<uuid>'`). Obecne testy Java (`AppUser`/`AppUserRepository`) nie mogą tego łatwo zweryfikować, bo warstwa JPA/Hibernate nie ma już `nullable=false` na tym polu i nie stoi na przeszkodzie zbudowaniu takiej encji w pamięci — jedynym egzekwującym elementem jest CHECK w bazie. Warto dodać chociaż jeden lekki IT test (raw JDBC/`@SpringBootTest` z prawdziwym Postgresem) który próbuje wstawić niepoprawną kombinację i asercjonuje `DataIntegrityViolationException`/`PSQLException` z kodem `23514` (check_violation) — obecnie ten constraint jest chroniony wyłącznie ręczną analizą w komentarzu migracji, nie automatycznym testem.
- Brak indeksu na samej kolumnie `role` (używanej przez `existsByRole()` w bootstrapie) — dla docelowo małej tabeli `app_user` to nieistotne wydajnościowo (bootstrap wykonuje się raz na start aplikacji), więc nie blokujące, ale warto odnotować gdyby tabela kiedyś urosła do rozmiarów, przy których sequential scan zacząłby mieć znaczenie.

### ✅ Positive Observations

- **Wyjątkowa jakość dokumentacji migracji** — sekcja „Analiza wplywu na obiekty zalezne od app_user” w nagłówku `V080` systematycznie wymienia KAŻDY zależny widok/funkcję/politykę RLS (`v_tenant_stats`, `v_queue_available_agents`, `v_queue_realtime_stats`, `v_active_contacts`, `check_tenant_limit()`, `fn_contact_ref_integrity()`, `pol_app_user_select`) z konkretnym uzasadnieniem dlaczego SUPER_ADMIN (`tenant_id IS NULL`) go nie psuje. Zweryfikowałem samodzielnie treść `pol_app_user_select` (V012) i potwierdzam, że opis w komentarzu jest dokładny, nie tylko wiarygodnie brzmiący — rzadko spotykany poziom rygoru w migracji Flyway.
- **Constraint niezmienniczy (`chk_super_admin_tenant_invariant`) jako właściwa alternatywa dla „cichej” nullable kolumny** — zamiast po prostu zdjąć `NOT NULL` i polegać na walidacji aplikacyjnej, migracja od razu dodaje CHECK wiążący `tenant_id IS NULL` ściśle z `role = 'SUPER_ADMIN'` w obie strony. To domyka lukę, którą sam `DROP NOT NULL` by otworzył (możliwość ustawienia `tenant_id = NULL` dla DOWOLNEJ roli).
- **V081 jako podręcznikowy przykład właściwej reakcji na błąd znaleziony podczas implementacji** — zamiast edytować już zastosowaną migrację V080 (co CLAUDE.md wprost zabrania i co zablokowałoby start aplikacji przy walidacji Flyway), błąd (`refresh_token.tenant_id NOT NULL` przeoczone przy projektowaniu V080) został naprawiony nowym, w pełni udokumentowanym plikiem.
- Zgodność z dev-seedem potwierdzona w komentarzu i przez fakt, że `mvn verify` (1531 testów) przechodzi bez błędów związanych z migracją — istniejący wiersz `role='ADMIN'` w danych deweloperskich ma `tenant_id NOT NULL`, więc spełnia nowy constraint bez potrzeby migracji danych.

### Summary

**Ocena: 5/5 ⭐** — wzorcowa para migracji: precyzyjnie zakresowany wyjątek od konwencji `tenant_id NOT NULL` (wyegzekwowany CHECK constraintem, nie tylko udokumentowany), poprawny partial unique index z IMMUTABLE predykatem, i podręcznikowe zastosowanie zasady „nigdy nie edytuj zaaplikowanej migracji” przy V081. Jedyna sugestia to dodanie automatycznego testu DB-level dla samego CHECK constraintu, żeby nie polegać wyłącznie na ręcznej weryfikacji udokumentowanej w komentarzu.

---

## Review: V094__narrow_contact_ref_integrity_on_update.sql + ContactRefIntegrityNarrowingTest (DB-079, EPIC-30) — 2026-09-21

**Branch:** `feature/epic-30-message-retention` (pliki nieśledzone, względem HEAD `078134b`)
**Reviewer:** senior-code-reviewer agent
**Pliki:** `backend/src/main/resources/db/migration/V094__narrow_contact_ref_integrity_on_update.sql`, `backend/app/src/test/java/com/contactcenter/domain/contact/ContactRefIntegrityNarrowingTest.java`; oryginał funkcji: `V016__contact_referential_integrity.sql` (l. 36-97), kontekst: `V013__gdpr_functions.sql`, `TASKS-DATABASE.md` (DB-079, DB-060 F1).
**Metoda:** czytanie migracji i testu względem V016 oraz specyfikacji; weryfikacja twierdzeń na żywej bazie wyłącznie odczytem (`default_transaction_read_only=on`: `pg_trigger`, `pg_proc`, `pg_policies`, `flyway_schema_history`); `git ls-tree` po wszystkich refach. **Nie uruchamiałem Mavena ani testów** (trwa pełny build), nie stosowałem migracji, nie tworzyłem bazy scratch. Zachowanie funkcji oceniłem z lektury kodu, dokumentacji PostgreSQL i analizy „co by padło” (mutacje ciała funkcji vs asercje testu); liczby z notatki wykonawcy (19 testów, 6 padających bez V094) przyjmuję z notatki.

### Indeks ustaleń (wg wagi)

_Brak blockerów, majorów i minorów._ Migracja jest poprawna. Ustalenia to wyłącznie nity dotyczące testu i dokumentacji:

| ID | Waga | Status | Miejsce | Streszczenie |
|----|------|--------|---------|--------------|
| DB079-01 | nit | potwierdzone (kod) | `ContactRefIntegrityNarrowingTest.java:124-125,186-187,598` | odporność na renumerację jest tylko częściowa: stała `93` w trzech miejscach, słaba asercja `> 93`, `version::int` |
| DB079-02 | nit | potwierdzone (brak testu) | `V094…sql:82-85` (ograniczenie 3), test | przeniesienie wiersza między partycjami (`UPDATE … SET started_at`) opisane jako „sprawdzone na scratch”, ale bez testu w repo |
| DB079-03 | nit | potwierdzone (kod) | `ContactRefIntegrityNarrowingTest.java:59-66` | własny blok `static` duplikuje nowy `TestcontainersSupport.ensureDockerApiVersion()` |
| DB079-04 | nit | dokumentacja | `V094…sql:74-91` | nieujęta na liście konsekwencja: trigger nie wykryje już wcześniej istniejących, nieprawidłowych referencji przy edycji innych kolumn |
| DB079-05 | info | do wykonania przez właściciela | AC WP-4 (na żywo) | na żywej bazie `flyway_schema_history` kończy się na V093, a `fn_contact_ref_integrity` to nadal wersja V016 — weryfikacja po przebudowie obrazu |

### 🐛 Bugs / Critical Issues

_Brak zidentyfikowanych._ Sprawdzone, że migracja robi dokładnie to, co deklaruje:
- `CREATE OR REPLACE FUNCTION fn_contact_ref_integrity()` (`:97`) zachowuje sygnaturę (`RETURNS TRIGGER`, plpgsql, bez argumentów), więc podmienia ciało w miejscu; wszystkie kopie triggera używają nowej wersji natychmiast. Na żywej bazie potwierdziłem 12 kopii `trg_contact_ref_integrity` (rodzic `contact` + 11 partycji: `contact_2026_03` … `contact_2026_12`, `contact_default`), wszystkie `tgenabled = 'O'`, `tgisinternal = false`, wszystkie wskazują tę samą funkcję — zgodnie z nagłówkiem migracji. Zero DDL na tabelach: brak `ACCESS EXCLUSIVE`, brak `DROP/CREATE TRIGGER`, brak przebudowy indeksów.
- Blok wczesnego wyjścia (`:107-116`) jest poprawny: zagnieżdżone `IF` sprawia, że `OLD.*` nie jest czytane przy `INSERT` (dokumentacja PostgreSQL nie gwarantuje kolejności ewaluacji operandów `AND`, a semantyka `OLD` w wyzwalaczu `INSERT` różniła się między wersjami — „nieprzypisane” vs `NULL` — więc odstępstwo od ticketu, pojedynczego `TG_OP = 'UPDATE' AND …`, jest słuszne, nie tylko kosmetyczne). `IS NOT DISTINCT FROM` jest bezpieczne dla `NULL` (`NULL = NULL` dałoby `NULL`, a `IF` traktuje to jak fałsz, czyli brak wczesnego wyjścia — dla kontaktów z `agent_id IS NULL` funkcja nadal walidowałaby usuniętego klienta i błąd DB-079 by zostawał; użycie `IS NOT DISTINCT FROM` to dokładnie to, co go usuwa).
- `tenant_id` liczony jako referencja jest właściwy: walidacja kluczy obcych odbywa się względem `NEW.tenant_id`, więc zmiana `tenant_id` musi przejść pełną walidację (test `updateChangingTenantId_isValidatedAsReferenceChange`).
- Reszta ciała (`:118-171`) jest identyczna z V016 poza zamianą „–” na „-” w komentarzach (plik czysty ASCII — sprawdzone `grep -P '[^\x00-\x7F]'`); komunikaty wyjątków bez zmian, więc asercje `P0001` + treść działają jako dowód „odrzucone przez trigger, nie z innego powodu”.
- Przypadki brzegowe: `SET agent_id = <ta sama wartość>` (kształt `ContactRepository#update`) → wczesne wyjście (test `updateOfContactOfDeactivatedAgent_passes`); `NULL → NULL` → wczesne wyjście; `NULL → wartość` i `wartość → inna` → pełna walidacja; zdjęcie referencji (`→ NULL`) przechodziło i przechodzi; UPDATE zmieniający `started_at` — patrz DB079-02. Aplikacja nie zmienia `started_at` w żadnym `UPDATE contact` (sprawdzone `grep` po `ContactRepository`: `started_at` występuje tylko w `WHERE`).

### ⚠️ Security Concerns

_Brak nowych zagrożeń._ Funkcja nadal **nie jest `SECURITY DEFINER`** (na żywej bazie `prosecdef = false`), więc czyta `customer`/`app_user`/`queue`/`campaign` z uprawnieniami i RLS wywołującego; wczesne wyjście tylko pomija te odczyty, nie rozszerza dostępu. Osłabienie walidacji jest świadome i ograniczone: referencja jest sprawdzana w chwili zapisu; INSERT i każda zmiana referencji (łącznie z `tenant_id`) są walidowane jak dotąd, więc nie da się podpiąć kontaktu do klienta/agenta innego tenanta ani do usuniętego rekordu. Potwierdzona uboczna obserwacja wykonawcy: `pg_policies` na żywej bazie pokazuje dla `contact` wyłącznie `pol_contact_select` i `pol_contact_insert` (brak UPDATE/DELETE), a aplikacja łączy się jako `ccapp` (superuser, `BYPASSRLS`) — pod rolą bez `BYPASSRLS` `UPDATE contact` dotknie 0 wierszy i trigger nawet się nie odpali; to problem DB-071/DB-074, nie tej migracji.

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń._ Sprawdzone względem reguł projektu:
- Nazewnictwo `V094__narrow_contact_ref_integrity_on_update.sql` zgodne z `V{NNN}__{description}.sql`; **V094 jest wolne we wszystkich refach** — `git ls-tree` dla `develop`, `main` (V091), `partycjonowanie-2`, `chore/epic-30-zadania`, `appmod/…`, `origin/develop`, `origin/main`, `origin/feature-socialmedia` (V092), `origin/fix/drop-duplicate-contact-indexes` daje maksimum V093, a `flyway_schema_history` żywej bazy kończy się na `093 drop duplicate contact indexes`.
- Zasada „nie edytuj zastosowanej migracji” zachowana: V016 nietknięte (`git status` — jedyna zmiana w `db/migration/` to nowy plik V094), naprawa jako nowa migracja.
- Idempotencja: `CREATE OR REPLACE` + `COMMENT ON` — ponowne zastosowanie daje ten sam stan (wykonawca podaje identyczne `md5(prosrc)`). Odwracalność: brak dedykowanej migracji „down” (Flyway Community nie ma undo), ale nagłówek (`:93-95`) wskazuje właściwy sposób — nowa migracja z ciałem V016; brak zmian schematu do cofnięcia.
- Reguły tabelowe (`tenant_id NOT NULL`, indeks `(tenant_id, pk)`, RLS, predykaty indeksów częściowych) nie dotyczą tej migracji — nie tworzy ani nie zmienia tabel/indeksów/polityk.

### 🔧 Improvements & Suggestions

- **DB079-01 · nit · `ContactRefIntegrityNarrowingTest.java:124-125,186-187,598`** — test deklaruje, że „nie zna numeru migracji DB-079”, ale zna numer poprzedniej: `target("93")` (`:124`), `isEqualTo(93)` (`:125`, `:187`), `isGreaterThan(93)` (`:186`). Odporność na renumerację jest realna tylko w jedną stronę: jeśli inna migracja zajmie V094, a DB-079 zostanie V095, baza „pre” (do V093) nadal jest poprawnym „przed” dla V016, a „post” migruje wszystko, więc testy zachowania pozostają ważne. Słabe punkty: (1) `postDatabaseHasNarrowingMigrationApplied` (`:186`) przejdzie, gdy istnieje dowolna migracja > 93 — po błędnie rozwiązanym konflikcie numeracji (plik DB-079 zgubiony, cudzy V094 zostaje) ten test zostanie zielony; zachowanie i tak złapią testy (a)/(e), ale nazwa obiecuje więcej, niż sprawdza; (2) `max(version::int)` (`:598`) rzuci na wersji z kropką (np. `094.1`) — dziś w `db/migration` takich nie ma; (3) po ewentualnym baseline/squashu starych migracji `target("93")` przestanie istnieć.
  Rekomendacja: wyznaczyć wersję „przed” dynamicznie — `Flyway#info().all()`, znaleźć migrację o opisie zawierającym `narrow contact ref integrity`, `target` = wersja bezpośrednio poprzednia; `postDatabaseHasNarrowingMigrationApplied` zastąpić sprawdzeniem, że migracja o tym opisie ma `success = true` w `flyway_schema_history` (albo że `prosrc` funkcji zawiera `IS NOT DISTINCT FROM OLD`).

- **DB079-02 · nit · `V094…sql:82-85` (ograniczenie 3) i brak testu** — migracja twierdzi, że `UPDATE … SET started_at` przenosi wiersz między partycjami jako `DELETE` + `INSERT`, z `BEFORE UPDATE` na partycji źródłowej (wczesne wyjście) i `BEFORE INSERT` na docelowej (pełna walidacja jak dla INSERT). To zgodne z dokumentacją PostgreSQL (wyzwalacze wierszowe `BEFORE UPDATE`/`BEFORE DELETE` na źródle, `BEFORE INSERT` na celu), ale **nie zweryfikowałem tego w tym przeglądzie** (nie tworzyłem bazy), a w teście nie ma przypadku pokrywającego to zachowanie, choć nagłówek nazywa ograniczenia „sprawdzonymi na scratch”. Przypadek jest bezpieczny (fail-closed: pełna walidacja), więc to tylko dokumentacja: dodać jeden test „`UPDATE contact SET started_at = <inna partycja>` na kontakcie usuniętego klienta → `P0001`” jako test charakteryzujący, żeby zmiana zachowania w przyszłej wersji PostgreSQL nie przeszła niezauważona.

- **DB079-03 · nit · `ContactRefIntegrityNarrowingTest.java:59-66`** — blok `static` z `System.setProperty("api.version", "1.44")` duplikuje nowy `TestcontainersSupport.ensureDockerApiVersion()` dodany w tej samej zmianie (`support/TestcontainersSupport.java:18-22`). Wystarczy `static { TestcontainersSupport.ensureDockerApiVersion(); }` (metoda musi być wywołana przed startem kontenera; blok `static` klasy z `@Container` wykona się przed rozszerzeniem Testcontainers).

- **DB079-04 · nit · `V094…sql:74-91`** — lista „ZNANYCH OGRANICZEŃ” jest uczciwa (ograniczenie 2: przy zmianie choć jednej referencji walidowane są nadal wszystkie cztery — np. zmiana `queue_id` kontaktu usuniętego klienta nadal rzuca; ograniczenie 3: partycje). Brakuje jednej, trywialnej konsekwencji: trigger przestaje być okresową kontrolą integralności przy dotknięciu wiersza — kontakt, który już wskazuje nieprawidłowego klienta/agenta (np. historyczne skażenie danych albo klient innego tenanta), może być odtąd edytowany bez ostrzeżenia. Nie ma na to dowodu w danych; to kwestia jednego zdania w komentarzu przy ograniczeniu 1.

- **DB079-05 · info · AC WP-4 (na żywo)** — na żywej bazie (odczyt) `flyway_schema_history` kończy się na `093`, a funkcja jest w wersji V016; Flyway zastosuje V094 przy starcie backendu po przebudowie obrazu. Kryterium „`\sf fn_contact_ref_integrity` = nowa wersja + `RecordingRetentionJob` dla klienta zanonimizowanego Javą” pozostaje otwarte po stronie właściciela (job jest destrukcyjny — najpierw dry-run i zgoda). Po wdrożeniu warto też sprawdzić w logu backendu, że `clearRecordingUrl` przestał zwracać błąd per wpis.

### 🔍 Hipotezy do sprawdzenia (niepotwierdzone)

- **Zachowanie `UPDATE … SET started_at` (partycje) w PostgreSQL 16** — jak w DB079-02: oparte na dokumentacji, niezweryfikowane uruchomieniem w tym przeglądzie.
- **Wpływ na inne ścieżki, które ponownie wstawiają kontakty** (np. przyszła rotacja/przenoszenie wierszy z `contact_default` do nowej partycji przez `INSERT … SELECT`): każdy taki `INSERT` przechodzi pełną walidację i zawiedzie dla kontaktu zanonimizowanego klienta. `create_contact_partition` (V007:215-238) nie przenosi wierszy, więc dziś nie występuje; wart odnotowania przy DB-067/rotacji partycji, gdzie takie przenosiny są typowe.

### ✅ Positive Observations

- **Dowód działaniem, nie papierowo:** wzorzec „dwie bazy o tych samych danych” (`pre` = łańcuch Flyway do V093 + fixture z soft-delete PO utworzeniu kontaktów, `post` = `CREATE DATABASE … TEMPLATE` + migracja do najnowszej) dowodzi jednocześnie błędu na starej funkcji (`v093_*`, w tym pierwszy w historii dowód uruchomieniem, że `anonymize_customer` V013 nie działa dla klienta z kontaktami) i poprawności migracji stosowanej do bazy z istniejącymi danymi.
- **Czułość testu na mutacje** (analiza, nie przebieg): usunięcie któregokolwiek z pięciu porównań z wczesnego wyjścia jest łapane — `tenant_id` przez `updateChangingTenantId_…`, `agent_id` przez `updateChangingAgentIdToDeactivatedAgent_isRejected`, `queue_id`/`campaign_id` przez `missingReferences_areStillRejected` (UPDATE `NULL → MISSING`), `customer_id` przez (c); zamiana `IS NOT DISTINCT FROM` na `=` jest łapana przez (a) (kontakty usuniętego klienta mają `agent_id IS NULL`). Usunięcie samego guardu `TG_OP` może być na PostgreSQL 16 mutantem równoważnym (przy `INSERT` pola `OLD` są `NULL`, więc pełna walidacja i tak by zaszła dla każdej niepustej referencji) — nie liczę tego jako luki testu; zagnieżdżony `IF` jest tu zabezpieczeniem niezależnym od semantyki `OLD`. Wykonawca przedstawił też przebieg z ukrytym V094 (6 z 19 pada) — spójne z tą analizą (5 testów zachowania + `postDatabaseHasNarrowingMigrationApplied`).
- Negatywne asercje sprawdzają `SQLState = P0001` **i** treść komunikatu z konkretnym UUID (`contact: <kolumna> <UUID> nie istnieje`), więc odrzucenie nie może pochodzić z CHECK/NOT NULL/RLS; każda próba zapisu w transakcji zawsze wycofywanej (stan bazy stały między testami); fixture w `contact_2027_01`, `contact_2027_02` i `contact_default` z weryfikacją `tableoid`; osobny test partycji utworzonej po migracji (`create_contact_partition(2028, 5)`) oraz sprawdzenie przez `pg_trigger`, że każda partycja ma kopię triggera z tą samą funkcją; test pod rolą `app_user` z GUC innego tenanta (fail-closed `P0001`/`42501`).
- Uczciwe uzasadnienie odrzuconego wariantu `UPDATE OF …` (`:64-72`): trafna obserwacja, że `UPDATE OF kolumna` odpala się także przy `SET agent_id = <ta sama wartość>`, a `ContactRepository#update` ustawia `agent_id` w każdym UPDATE — więc wariant alternatywny nie rozwiązałby przypadku kontaktu dezaktywowanego agenta.
- Numeracja migracji sprawdzona zgodnie z WP-3 (wszystkie refy + żywa baza), nagłówek dokumentuje problem, dowód, rozwiązanie, odrzucony wariant, ograniczenia i sposób wycofania.

### Summary

**Ocena: 5/5 ⭐** — Minimalna, idempotentna migracja (samo `CREATE OR REPLACE FUNCTION`, zero DDL na tabelach) z poprawnym, NULL-bezpiecznym wczesnym wyjściem i test na prawdziwym PostgreSQL, który udowadnia zarówno błąd na V093, jak i naprawę na tych samych danych; nie znalazłem błędów, a jedyne uwagi to nity (dynamiczne wyznaczenie wersji „przed”, test przeniesienia między partycjami, użycie wspólnego helpera Testcontainers). **Werdykt: zatwierdzić** (DB079-01…04 opcjonalne; DB079-05 do wykonania przez właściciela po przebudowie obrazu).

---

## Review: V095__fix_export_customer_data_and_add_subject_helper.sql + ExportCustomerDataSubjectHelperTest (DB-061, EPIC-30) — 2026-09-24

**Zakres:** `backend/src/main/resources/db/migration/V095__fix_export_customer_data_and_add_subject_helper.sql` (532 linie: `fn_normalize_phone`, `fn_customer_subject_ids`, `export_customer_data`), `backend/app/src/test/java/com/contactcenter/domain/gdpr/ExportCustomerDataSubjectHelperTest.java` (616 linii, 10 testów). Metoda: czytanie linia-po-linii SQL i testu, weryfikacja schematu przez `git`/pliki migracji (nie tylko notatka wykonawcy), niezależny `grep` po wołających `export_customer_data`/`anonymize_customer`, odczyt kodu `GdprServiceImpl` i `EmailAttachmentKeys`, jedno zapytanie tylko-do-odczytu na żywej bazie (`flyway_schema_history`, bez wywołania którejkolwiek z trzech funkcji SQL, bez PII). Nie uruchamiałem Mavena (build zlecającego trwał równolegle) — polegam na deklaracji „mvn verify: 2056/0/0” w notatce wykonawcy, niepotwierdzonej niezależnie; `@Test` w pliku policzone ręcznie = 10, zgodnie z deklaracją.

**Werdykt: zatwierdzić z poprawkami.** Zero blockerów, zero majorów. SQL jest poprawny tam, gdzie to najważniejsze (izolacja tenantów, brak duplikacji wierszy, brak rekurencji, matematyka liczników `matched_by_*` zgadza się co do jednego z fixture'em). Ustalenia to same minory/nity — dokumentacyjne nieścisłości i luki w rygorze testów, żadna nie blokuje mergu, ale kilka warto zamknąć PRZED albo RAZEM z DB-062, bo DB-062 dziedziczy `fn_customer_subject_ids` bez dalszej konsultacji z autorem.

| ID | Waga | Status | Miejsce | Streszczenie |
|----|------|--------|---------|--------------|
| DB061-01 | minor | potwierdzone (grep migracji) | `V095…sql:89-96` + notatka TASKS-DATABASE.md „RLS (WP-4)” | `scheduled_callback` opisany jako mający `FORCE ROW LEVEL SECURITY` — żadna migracja (V032/V037/V038/V047/V054) tego nie ustawia, tylko `ENABLE` |
| DB061-02 | minor | potwierdzone (kod) | `ExportCustomerDataSubjectHelperTest.java:194-195,225-226,231-233` vs `:493-497` | asercje „brak duplikatów” dla `contacts`/`scheduled_callbacks`/`campaign_records` konwertują tablicę JSON na `Set` przed porównaniem — nie wykryją duplikatu wiersza |
| DB061-03 | minor | potwierdzone (kod) | `V095…sql:170-175,180-182,191`; test `:469-479` | reguła mostu przetestowana tylko dla kontaktu dopasowanego przez `link` (`customer_id`); wariant „kontakt tylko-identyfikator + most” nieujęty w fixture |
| DB061-04 | minor | potwierdzone (brak wywołania) | test, brak przypadku dla `CUSTOMER_OTHER_SAME_TENANT` | brak testu „pusty klient” (zero powiązań) — jawnie wymienionego w zleceniu recenzji |
| DB061-05 | nit | potwierdzone (kod) | `V095…sql:273-293` | precedencja `link` nad `identifier` przy podwójnym dopasowaniu wynika tylko z kolejności `CASE`, nieopisana wprost w `COMMENT ON FUNCTION` |
| DB061-06 | nit | potwierdzone (kod, kontrast z `EmailAttachmentKeys.java`) | `V095…sql:419-427,444-452,493,500` | `att->>'s3_key'` nie sprawdza `jsonb_typeof`, w przeciwieństwie do `EmailAttachmentKeys#extractS3Keys` (Java) |
| DB061-07 | nit | dokumentacja/test | brak bezpośredniego testu `fn_customer_subject_ids` | funkcja pomocnicza — jedyny kontrakt reużywany przez DB-062 — testowana wyłącznie pośrednio przez JSON `export_customer_data` |
| DB061-08 | nit | obserwacja projektowa | `V095…sql:259-269` vs `campaign_contact`/`campaign_contact_archive` PK `(record_id, campaign_id)` | `entity_id` niesie tylko `record_id`, zakładając globalną unikalność (prawdziwe dziś dzięki `uuid_generate_v4()`, ale niewymuszone przez schemat) |

### 🐛 Bugs / Critical Issues

_Brak zidentyfikowanych._ Sprawdzone systematycznie i potwierdzone jako poprawne:
- **Dwuetapowe rozwiązywanie bez rekurencji jest właściwym kształtem** (pkt 1 zlecenia): przejrzałem schemat wszystkich sześciu encji (`contact`, `scheduled_callback`, `campaign_contact`, `campaign_contact_archive`, `email_message`, `social_message`) — graf powiązań z klientem ma głębokość dokładnie 2 (klient → kontakt → {callback, rekord kampanii, e-mail}), żadna encja nie odsyła z powrotem do kontaktu poza `contact.campaign_contact_record_id` (który jest obsłużony jako „most” w osobnej CTE `subject_contact_campaign_records`, `:170-175`). CTE rekurencyjne nie są potrzebne i ich brak nie jest luką.
- **Brak duplikacji wierszy jest strukturalnie zagwarantowany, nie przypadkowy:** każda CTE (`subject_contacts`, `subject_callbacks`, `subject_campaign_contacts`, `subject_campaign_contact_archive`, `subject_emails`, `subject_social`) filtruje SWOJĄ tabelę źródłową samym `WHERE ... OR ... OR ...` — bez JOIN-ów, które mogłyby namnożyć wiersze. Kontakt dopasowany jednocześnie przez `customer_id` I przez `remote_address` (scenariusz z zapytania recenzji) daje jeden wiersz z `matched_by='link'` (CASE sprawdza `customer_id` jako pierwszy warunek) — potwierdzone kodem I fixture'em testu (`CONTACT_LINKED`, ma `customer_id=CUSTOMER` ORAZ `remote_address=PHONE` z listy telefonów klienta, `:386-391`, asercja `matched_by`=`link` na `:197`). Jedyna słabość jest w SPOSOBIE asercji tego w teście, patrz DB061-02.
- **Precedencja `link` vs `identifier` przy podwójnym dopasowaniu:** `link` wygrywa zawsze (CASE sprawdza warunek `link` jako pierwszy w każdej z pięciu sub-CTE) — spójne z resztą kontraktu, potwierdzone matematycznie (patrz niżej) i jednym jawnym testem (`CONTACT_LINKED`).
- **Matematyka liczników `matched_by_link`/`matched_by_identifier` zgadza się co do jednego** — przeliczyłem ręcznie oczekiwane dopasowania dla całego fixture'u (2 kontakty link + 1 kontakt-most-link = kontakty: 2 link/1 identifier; callbacki: 2 link/1 identifier; rekordy kampanii: 2 link/1 identifier; archiwum: 1 link; e-maile: 1 link/1 identifier; social: 1 link) = **9 link / 4 identifier** — dokładnie zgodne z asercją testu (`:251-252`, `:343-344`). To niezależne potwierdzenie poprawności całej logiki JOIN/CASE, nie tylko czytanie kodu.
- **Wszystkie kolumny referencjonowane w SQL istnieją i mają deklarowane typy/nullability zgodne z użyciem** — zweryfikowałem NIEZALEŻNIE od notatki wykonawcy, czytając migracje źródłowe: `contact.campaign_contact_record_id` (V063), `scheduled_callback.{source_type,origin_contact_id,campaign_contact_record_id}` (V037/V054, `source_type` `NOT NULL DEFAULT`), `campaign_contact.last_contact_id` (V009), `campaign_contact_archive` (V015, wszystkie kolumny użyte w `export_customer_data` istnieją, w tym `disposition_code`), `email_message.{cc_address,bcc_address}` (V010, `TEXT` nullable), `social_message.sender_external_id` (V010). `customer.phone`/`email`/`attachments` (email/social) mają `NOT NULL DEFAULT '[]'` + `CHECK jsonb_typeof(...) = 'array'` (V006/V010) — zapytania `jsonb_array_elements_text(phone)`/`jsonb_array_elements(em.attachments)` nigdy nie dostaną SQL NULL ani JSON `null` na wejściu, więc nie ma ryzyka `ERROR: cannot extract elements from a scalar`.
- **`email_message.contact_id`/`social_message.contact_id` są nullable (V028)** — zweryfikowane, bo fixture testu wstawia e-mail z `contact_id = NULL` (`:415-420`); gdyby kolumna była nadal `NOT NULL` (jak w pierwotnym V010), ten INSERT rzuciłby błąd i test w ogóle by nie ruszył — czyli sama obecność zielonego testu jest pośrednim dowodem, ale sprawdziłem to też wprost w migracji.
- **`GdprServiceImpl#exportCustomerData` faktycznie zapisuje `GDPR_EXPORT`** (pkt 2 zlecenia — nie zaufałem notatce): `GdprServiceImpl.java:61-72`, `auditLogService.publishAuditEvent(new AuditLogEvent(..., "GDPR_EXPORT", ...))`, fire-and-forget, PRZED zwróceniem ZIP-a. Usunięcie `INSERT INTO audit_log` z funkcji SQL nie tworzy więc luki audytowej. Przy okazji: `GdprServiceImpl` w ogóle nie woła `export_customer_data`/`fn_customer_subject_ids` — buduje ZIP własną, uboższą ścieżką Javy (limit 1000 kontaktów, bez e-maili/social/callbacków) — zgodne z U2, funkcja SQL pozostaje bez wołającego aż do BE-129.
- **Niezależny grep potwierdza brak innych wołających `export_customer_data`** poza nowym testem (0 wyników w `backend/app`, `frontend`, `voicebot` poza `/db/migration/` i nowym plikiem testu) — zmiana `CREATE OR REPLACE` bez zmiany sygnatury jest bezpieczna. `anonymize_customer` (nietknięta przez tę migrację) ma jednego wołającego (`ContactRefIntegrityNarrowingTest`), z niezmienioną sygnaturą — bez wpływu tej migracji.

### ⚠️ Security Concerns

_Brak nowych zagrożeń — izolacja tenantów zweryfikowana wyczerpująco (pkt 3 zlecenia)._ Przejrzałem KAŻDE `FROM`/JOIN w obu funkcjach (17 miejsc łącznie w `fn_customer_subject_ids` i `export_customer_data`) — wszystkie mają jawny `tenant_id = p_tenant_id` na tabeli źródłowej, łącznie z `campaign_contact`/`campaign_contact_archive`, które **nie mają żadnej polityki RLS** (potwierdzone grepem po `CREATE POLICY`/`ROW LEVEL SECURITY` w `V009__create_campaign.sql` i `V015__campaign_contact_archive.sql` — zero trafień) — to jest właśnie miejsce, gdzie brakujący filtr byłby realnym wyciekiem cross-tenant, i tu filtr jest obecny w obu miejscach, gdzie te tabele są czytane (`subject_campaign_contacts`/`subject_campaign_contact_archive` w helperze ORAZ w podzapytaniu `combined` w `export_customer_data`, `:392-406`). Test cross-tenant ze WSPÓLNYM telefonem/e-mailem (`CUSTOMER_FOREIGN_TENANT`, `:377-379`, `:270-276`) potwierdza to działaniem, nie tylko czytaniem kodu — skoro `fn_customer_subject_ids(CUSTOMER_FOREIGN_TENANT, TENANT_B)` zwraca zero kontaktów mimo identycznego telefonu z klientem TENANT_A, jawny filtr `tenant_id` na tabeli `contact` (nie tylko na `customer`) na pewno działa.

RLS pod `SET ROLE app_user`: `prosecdef=false` dla obu nowych funkcji (potwierdzone testem), więc działają z uprawnieniami wywołującego — brak eskalacji przez `SECURITY DEFINER`. Zachowanie bez ustawionego GUC (fail-closed: `RAISE EXCEPTION` zamiast cichego pustego wyniku) sprawdzone testem `underAppUserRole_withoutGuc_failsSafelyOnMissingCustomer` — poprawne, bo polityka `pol_customer_select` używa `current_setting('app.current_tenant_id', TRUE)` (missing_ok), więc brak GUC daje `NULL`, nie błąd, i `IF NOT EXISTS` w `export_customer_data` łapie to czytelnym komunikatem PRZED dotarciem do jakiegokolwiek zapytania na `scheduled_callback` (którego polityka z V032 używa `current_setting(...)` BEZ `TRUE` — bez GUC rzuciłaby własny błąd „unrecognized configuration parameter”, ale ta ścieżka nigdy nie jest osiągana w tym scenariuszu, więc niespójność nazw/stylu polityk sprzed tej migracji nie ma tu wpływu).

**DB061-01 (minor, dokumentacja RLS):** nagłówek migracji (`:89-96`) i notatka wykonania w TASKS-DATABASE.md („RLS (WP-4)”) twierdzą, że `scheduled_callback` (razem z `contact_transcription`/`contact_ai_summary`) ma `FORCE ROW LEVEL SECURITY`. Sprawdziłem grepem WSZYSTKIE migracje dotykające `scheduled_callback` (V032 tworząca tabelę + V037/V038/V047/V054 kolejne ALTER) — żadna nie zawiera `FORCE ROW LEVEL SECURITY`, tylko `ENABLE` (V032:37). Dla `contact_transcription`/`contact_ai_summary` `FORCE` faktycznie jest (V086/V087, potwierdzone), więc błąd dotyczy wyłącznie `scheduled_callback`. **Nie jest to luka bezpieczeństwa** — `app_user` nie jest właścicielem tabel (właściciel to rola uruchamiająca migracje; `app_user` ma tylko `GRANT SELECT/INSERT/UPDATE/DELETE`, potwierdzone V012:50-53), a `FORCE` wpływa wyłącznie na to, czy RLS obowiązuje WŁAŚCICIELA tabeli — dla nie-właściciela bez `BYPASSRLS` RLS obowiązuje zawsze, z `FORCE` czy bez. Czysto dokumentacyjna nieścisłość, ale warto ją poprawić PRZED DB-062, żeby nie utrwalić błędnego przekonania w kolejnym tickecie (DB-062 też będzie dotykać `scheduled_callback` i opisywać jego RLS w AC R1).

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń reguł projektu._ `V095__fix_export_customer_data_and_add_subject_helper.sql` — nazwa zgodna z `V{NNN}__{description}.sql`. Numeracja: niezależnie sprawdziłem `flyway_schema_history` na żywej bazie (tylko odczyt) — kończy się na `093` (`093 drop duplicate contact indexes`), więc V094 (DB-079) i V095 (ta migracja) są NIEZASTOSOWANE, zgodnie z wymogiem zlecenia. Spot-check `git ls-tree` na `main`/`develop`/`origin/main`/`origin/develop` — brak pliku `V095__*` na żadnej z nich (nie sprawdzałem WSZYSTKICH gałęzi wymienionych w notatce wykonawcy — to jedyna część WP-3, której nie zweryfikowałem w 100%, patrz „Czego nie zweryfikowałem”). Migracja nie edytuje V013/V017 (nie narusza zasady „nie edytuj zastosowanej migracji”) — `CREATE OR REPLACE FUNCTION` na tej samej sygnaturze jest właściwym mechanizmem. Idempotencja: `CREATE OR REPLACE` bez żadnego `DROP FUNCTION`/DDL na tabelach — bezpieczne przy ponownym uruchomieniu z definicji (nie testowałem tego sam, notatka deklaruje podwójne zastosowanie na scratch z identycznym `pg_proc`, wiarygodne, bo mechanizm jest z natury idempotentny).

**DB061-08 (nit, obserwacja):** `campaign_contact`/`campaign_contact_archive` mają PK złożony `(record_id, campaign_id)` (V009:164, V015:53-54) — `record_id` sam w sobie NIE jest wymuszony jako unikalny przez schemat. `fn_customer_subject_ids`/`export_customer_data` traktują `record_id` jako samodzielny `entity_id` (`:259-269` w V095). W praktyce bezpieczne, bo `record_id` jest zawsze `uuid_generate_v4()` (nigdy nie pochodzi od użytkownika/importu), więc kolizja między kampaniami jest kryptograficznie nieprawdopodobna — ale to założenie, nie gwarancja schematu. Do odnotowania dla DB-062: jeśli DB-062 użyje `entity_id` z tej funkcji do budowania `UPDATE`/`DELETE` na `campaign_contact`/`campaign_contact_archive`, powinien zawsze domykać warunek pełnym kluczem złożonym (`record_id = ... AND campaign_id = ...`), pobranym z właściwego wiersza, a nie zakładać, że sam `record_id` jednoznacznie identyfikuje wiersz.

### 🔧 Improvements & Suggestions

- **DB061-02 · minor · `ExportCustomerDataSubjectHelperTest.java:194-195,225-226,231-233` (helper `idsOf`, `:493-497`)** — asercje „brak duplikatów po podwójnym dopasowaniu” dla `contacts`, `scheduled_callbacks`, `campaign_records` robią `idsOf(result.get("contacts"), "contact_id")` → `Set<String>` → `containsExactlyInAnyOrder(...)`. Konwersja tablicy JSON na `Set` PRZED porównaniem oznacza, że gdyby przyszła zmiana SQL (np. refaktor `subject_contacts` na JOIN zamiast płaskiego `WHERE ... OR ...`) wprowadziła duplikat wiersza dla `CONTACT_LINKED` (który dziś pasuje jednocześnie przez `customer_id` I `remote_address`), ten test nadal by przeszedł — zbiór 2 identycznych ID nadal daje `Set` o rozmiarze 1. Kontrast: asercje dla `email_messages`/`social_messages`/`transcriptions`/`ai_summaries` używają `hasSize(n)` na surowej tablicy (`:210`, `:221`, `:204`, `:206`) — poprawnie łapałyby taki regres. Dziś NIE MA duplikacji (potwierdzone czytaniem SQL — brak JOIN-ów w tych CTE, patrz sekcja Bugs), więc to nie jest żywy błąd, tylko fałszywe poczucie bezpieczeństwa w teście. Rekomendacja: dodać `assertThat(result.get("contacts")).hasSize(3)` (analogicznie dla `scheduled_callbacks` = 3, `campaign_records` = 4) obok istniejących asercji `idsOf`.

- **DB061-03 · minor · `V095…sql:170-175` (`subject_contact_campaign_records`) vs test `:461-479`** — reguła mostu jest przetestowana wyłącznie na kontakcie dopasowanym przez `link` wprost: `CONTACT_FROM_BRIDGE_RECORD` ma w INSERT `customer_id = CUSTOMER` ustawione JAWNIE (`:469-472`, trzeci parametr to `CUSTOMER`, nie `NULL`), więc technicznie ten kontakt i tak pasowałby do zbioru podmiotu przez zwykły `customer_id = p_customer_id`, niezależnie od mechanizmu mostu. Notatka DB-061 opisuje bardziej wymagający przypadek jako reprezentatywny: „kontakt podmiotu (znaleziony CHOĆBY TYLKO identyfikatorem) niesie campaign_contact_record_id” — czyli kontakt dopasowany WYŁĄCZNIE przez telefon/e-mail, którego most i tak działa. Ten dokładny wariant (identifier-kontakt + most) nie ma osobnego fixture'u. Przejrzałem SQL: `subject_contact_campaign_records` JOIN-uje CAŁe `subject_contacts` bez rozróżniania `matched_by` (`:172-174`), więc mechanizm nie powinien się różnić — ale to obecnie NIEPOTWIERDZONE testem, tylko wnioskiem z czytania kodu. Rekomendacja: dodać wariant fixture'u (albo w uzupełnieniu tego testu, albo jako pierwszy krok DB-062) z kontaktem `customer_id = NULL` + telefon-identyfikator + `campaign_contact_record_id` wskazujący na rekord bez własnego powiązania.

- **DB061-04 · minor · brak testu** — zlecenie recenzji explicite prosiło o sprawdzenie pokrycia „pustego klienta (brak powiązań)”. Fixture ma gotowego kandydata: `CUSTOMER_OTHER_SAME_TENANT` (`:86`, `phone='[]', email='[]'`, `:374-376`) — używany WYŁĄCZNIE jako właściciel `CONTACT_OTHER` w kontroli negatywnej izolacji (`:266-267`), nigdy jako podmiot własnego eksportu. Brak asercji, że `export_customer_data(CUSTOMER_OTHER_SAME_TENANT, TENANT_A)` zwraca poprawny JSONB z WSZYSTKIMI tablicami puste i licznikami zerowymi, bez błędu. Tani do dodania (klient już istnieje w fixture), rekomenduję dopisać przed zamknięciem DB-061 albo jako pierwszy test DB-062 (skoro reużywa tej samej funkcji pomocniczej).

- **DB061-05 · nit · `V095…sql:273-293` (`COMMENT ON FUNCTION fn_customer_subject_ids`)** — komentarz opisuje osobno regułę `link` i osobno `identifier` per typ encji, ale nigdzie wprost nie mówi „gdy oba warunki są prawdziwe jednocześnie, `link` wygrywa”. To wynika tylko z kolejności gałęzi `CASE` w ciele funkcji (trzeba przeczytać SQL, nie wystarczy przeczytać komentarz). Skoro DB-062 ma pokazywać `matched_by` operatorowi w trybie podglądu (`p_dry_run`, FE-112), a ten komentarz jest jedynym miejscem opisującym kontrakt dla przyszłych wykonawców, warto dodać jedno zdanie: „gdy encja spełnia zarówno warunek link, jak i identifier, wynikiem jest zawsze link (link ma pierwszeństwo)”.

- **DB061-06 · nit · `V095…sql:419-427,444-452` (`attachments`) i `:493,500` (`s3_keys`)** — ekstrakcja klucza S3 używa `att->>'s3_key'` (operator tekstowy), który dla wartości JSON innej niż string (liczba/bool/obiekt/tablica) zwróci jej reprezentację tekstową zamiast `NULL` — trafiłoby to do `s3_keys` jako bezsensowny „klucz”. Kontrast: warstwa Java (`EmailAttachmentKeys.extractS3Keys`, `:206-207`) explicite wymaga `keyNode.isTextual()` i traktuje inne przypadki jako anomalię z ostrzeżeniem w logu. Dziś to nieosiągalne (writery zawsze zapisują `s3_key` jako string, `CHECK jsonb_typeof(attachments)='array'` na poziomie tablicy, ale NIE ma CHECK na strukturze pojedynczego elementu) — czysto defensywna sugestia, spójna z ostrożnością już przyjętą gdzie indziej w tym epiku (BE-124/125/143): `AND jsonb_typeof(att->'s3_key') = 'string'` w warunku `WHERE`.

- **DB061-07 · nit · brak testu bezpośredniego** — `fn_customer_subject_ids` jest jedynym kontraktem, który DB-062 ma reużyć „bez dalszej konsultacji z autorem” (wg zlecenia). Test sprawdza ją WYŁĄCZNIE pośrednio, przez kształt JSON zwracany przez `export_customer_data` — nie ma ani jednego `SELECT * FROM fn_customer_subject_ids(...)` z asercją na `entity_type`/`entity_id`/`matched_by` wprost. Działa to dziś (matematyka liczników się zgadza), ale przyszły błąd w SAMEJ funkcji pomocniczej (np. literówka w nazwie kolumny przy jej wywołaniu z DB-062) będzie trudniejszy do zlokalizowania niż gdyby istniał test bezpośredni. Rekomendacja dla DB-062: pierwszy krok to dodanie takiego testu wprost na `fn_customer_subject_ids`, niezależnie od `export_customer_data`.

- **Wydajność (pkt 6 zlecenia) — ocena bez uruchamiania:** wyjaśnienie wykonawcy (nieaktualne statystyki planisty tuż po masowym `INSERT` bez `ANALYZE` → gorszy plan, 6.7 s → <1 s po `ANALYZE`) jest zgodne ze znanym, udokumentowanym zachowaniem PostgreSQL (estymacja kardynalności bazuje na `pg_statistic`, które `autovacuum`/`ANALYZE` odświeża asynchronicznie) — mechanizm sam w sobie wiarygodny. Zastrzeżenie do oceny „nie jest to realne ryzyko poza syntetycznym testem”: `campaign_contact` ma udokumentowany, PRAWDZIWY tryb masowego zasilania (komentarz V009: „do 100 000 rekordów per kampania”, import CSV) — teoretycznie administrator mógłby wywołać eksport klienta zaraz po dużym imporcie kampanii, trafiając w tę samą lukę statystyk, choć dla INNEJ tabeli niż ta zmierzona (`email_message`, zasilana przychodząco, nie hurtowo). Nie jest to nowy problem tej migracji (dotyczy każdego zapytania po bulk-insercie, nie tylko `export_customer_data`) i nie blokuje mergu — ale warto rozważyć w BE-129 (który faktycznie wystawi to na endpoint) jawny `ANALYZE` (albo poleganie na `autovacuum_analyze_scale_factor`) w playbooku po dużych importach/migracjach danych, tak jak wykonawca już zasugerował w komentarzu migracji.

### 🔍 Hipotezy do sprawdzenia (niepotwierdzone)

- **Kompletność sprawdzenia numeracji V095 na WSZYSTKICH gałęziach z notatki wykonawcy** — sam sprawdziłem tylko `main`/`develop`/`origin/main`/`origin/develop` (spot-check, zero trafień `V095__*`) oraz `flyway_schema_history` żywej bazy (kończy się na 093, zgodnie z deklaracją). Nie sprawdziłem `partycjonowanie-2`, `chore/epic-30-zadania`, `appmod/java-upgrade-20260812100157`, `origin/feature-socialmedia`, `origin/fix/drop-duplicate-contact-indexes` — polegam tu na deklaracji notatki wykonawcy.
- **Wynik `mvn verify` (2056 testów, 0 błędów)** — niepotwierdzony niezależnie (zakaz uruchamiania Mavena w tym zleceniu). Sama treść testów (10 sztuk w tym pliku) przeczytana i logicznie spójna z deklarowanym zachowaniem — wysokie prawdopodobieństwo, że przechodzą, ale nie jest to dowód wykonaniem z mojej strony.
- **Realne prawdopodobieństwo kolizji `record_id` między kampaniami (DB061-08)** — teoretycznie możliwe wg schematu (PK złożony), praktycznie eliminowane przez `uuid_generate_v4()`; nie znalazłem żadnego miejsca w kodzie, które nadawałoby `record_id` inaczej niż domyślną funkcją bazy.

### ✅ Positive Observations

- **Dowód U3 przez wykonanie, nie tylko analizę statyczną** — `u3_beforeMigration_stableFunctionWithInsertFails` faktycznie wywołuje `export_customer_data` na bazie „pre” (łańcuch Flyway do wersji BEZPOŚREDNIO przed DB-061, wyznaczonej dynamicznie po OPISIE migracji, nie po numerze — odporne na renumerację przy scalaniu gałęzi, ten sam wzorzec co DB-079) i łapie dokładnie oczekiwany błąd PostgreSQL, po czym `afterMigration_exportSucceedsForSameCustomer` dowodzi naprawy na TYCH SAMYCH danych (baza „post” = `CREATE DATABASE ... TEMPLATE` z „pre”, nie nowa baza od zera).
- **Test izolacji cross-tenant ze WSPÓLNYM identyfikatorem jest dokładnie tym testem, który ma sens dla ryzyka fałszywych trafień D9** — klient innego tenanta z identycznym telefonem/e-mailem (`CUSTOMER_FOREIGN_TENANT`) to realistyczny scenariusz (współdzielona centrala/numer rodzinny między dwoma niezależnymi firmami-najemcami), nie sztuczny przypadek brzegowy.
- **Liczniki `matched_by_link`/`matched_by_identifier` przeliczone ręcznie zgadzają się z asercją co do jednego** (9/4) — silny, niezależny dowód poprawności całej logiki dopasowania, nie tylko „test przechodzi”.
- **Explicite udokumentowane, świadome ograniczenia** (nie ukryte): normalizacja telefonu bez wnioskowania kodu kraju (fałszywie ujemne dla numerów bez `+48`), `SOCIAL_MESSAGE` tylko `link` (brak identyfikatora nadającego się do porównania), brak strumieniowania dużych eksportów (przekazane do BE-129) — każde z uzasadnieniem opartym na rzeczywistych danych/kodzie, nie na domysłach.
- **Zero tolerancji na `SECURITY DEFINER`** mimo że ułatwiłby ominięcie fragmentarycznego RLS na `campaign_contact`/`campaign_contact_archive` — autor zamiast tego dodał jawne filtry `tenant_id` wszędzie, co jest bardziej pracochłonne, ale bezpieczniejsze i łatwiejsze do zweryfikowania przez przyszłego czytelnika kodu.
- **Notatka wykonania jest rzetelna tam, gdzie sprawdziłem ją niezależnie**: `GDPR_EXPORT` w `GdprServiceImpl` (potwierdzone kodem), zero innych wołających `export_customer_data` (potwierdzone niezależnym grepem), kolumny schematu (potwierdzone migracjami źródłowymi), stan `flyway_schema_history` żywej bazy (potwierdzone zapytaniem read-only) — jedyna znaleziona nieścisłość to DB061-01 (FORCE na `scheduled_callback`), i to bez wpływu na bezpieczeństwo.

### Summary

**Ocena: 4/5 ⭐** — Poprawna, dobrze przemyślana migracja z solidnym pokryciem testowym i wyczerpującą, uczciwą dokumentacją ograniczeń; żadnego blokera ani błędu w logice SQL nie znalazłem, a kluczowa własność (izolacja tenantów przy braku RLS na `campaign_contact*`) jest zweryfikowana wyczerpująco i potwierdzona testem działaniem. Obniżenie z 5/5 wynika z kilku nitów/minorów skupionych w jednym miejscu — teście: dwie asercje „brak duplikatów” nie mogą wykryć duplikatu (DB061-02), reguła mostu przetestowana w łatwiejszym wariancie niż ten nazwany w notatce jako reprezentatywny (DB061-03), i brak testu pustego klienta mimo że zlecenie o niego prosiło (DB061-04) — żadne z nich nie jest dowodem na żywy błąd (przeciwnie, czytanie SQL pokazuje, że kod jest poprawny w tych miejscach), ale obniżają wiarygodność samego test suite'u jako zabezpieczenia na przyszłość, a to bezpośrednio rzutuje na DB-062. **Werdykt: zatwierdzić z poprawkami** — migracja bezpieczna do zmergowania i zastosowania; rekomenduję domknąć DB061-02/03/04 (tanie, fixture częściowo już istnieje) przed albo na samym początku DB-062, oraz skorygować DB061-01 (FORCE) w komentarzu, żeby nie propagował się dalej.

---

## Review: V096__extend_anonymize_customer_gdpr_art17.sql + AnonymizeCustomerExtensionTest + ContactRefIntegrityNarrowingTest fix (DB-062, EPIC-30) — 2026-09-24

**Zakres:** `backend/src/main/resources/db/migration/V096__extend_anonymize_customer_gdpr_art17.sql` (604 linie: `DROP FUNCTION anonymize_customer(UUID,UUID,UUID)` + `CREATE FUNCTION anonymize_customer(p_customer_id, p_tenant_id, p_user_id, p_dry_run DEFAULT FALSE) RETURNS JSONB`), `backend/app/src/test/java/com/contactcenter/domain/gdpr/AnonymizeCustomerExtensionTest.java` (984 linie, 13 testów), zmiana w `backend/app/src/test/java/com/contactcenter/domain/contact/ContactRefIntegrityNarrowingTest.java` (jeden test, linie ~553-561), notatka wykonania DB-062 w `TASKS-DATABASE.md:3540-3594`. **Poza zakresem** (osobno zrecenzowane, nie ruszane): DB-061/V095 (`CR-DATABASE.md` wyżej).

**Metoda:** czytanie SQL linia-po-linii z ręczną weryfikacją każdego z 9 par predykatów dry-run/real (diff bajt-po-bajcie przez `sed`, nie „na oko”); niezależne sprawdzenie nullability KAŻDEJ kolumny użytej w warunkach idempotencji przez odczyt migracji źródłowych (`V007`/`V009`/`V010`/`V015`/`V032`/`V058`), nie zaufanie notatce; grep całego repo (`backend`, `frontend`, `voicebot`) po wołających starej 3-argumentowej sygnatury; **niezależna weryfikacja na żywej bazie `contact_center` (tylko odczyt)** `pg_policy`/`pg_class`/`pg_roles` dla `audit_log` i wszystkich 11 tabel dotykanych przez funkcję — potwierdza dosłownie notatkę wykonawcy (brak polityki INSERT na `audit_log` od V012, `relforcerowsecurity`/`relrowsecurity` per tabela, `ccapp`/`admin_user` mają `rolbypassrls=true`, `app_user` nie); **odtworzenie pełnego łańcucha Flyway V001..V096 na jednorazowej bazie scratch** (`scratch_db062_review`, utworzona i usunięta w trakcie tej recenzji, żywa baza nietknięta) — wszystkie 96 migracji zaaplikowane bez błędu, niezależne potwierdzenie „mvn verify: 2069/0/0” z notatki; **na tej samej bazie scratch, empiryczny test dwóch wywołań `anonymize_customer` z jawnym `p_dry_run = NULL`** (transakcja z `ROLLBACK`, zero wpływu na scratch po zakończeniu, scratch usunięty po pracy) — patrz DB062-01. Nie uruchamiałem Mavena (build zlecającego trwał równolegle) — nie potwierdzam niezależnie „2069/0/0”, tylko liczbę testów w pliku (13, zgodnie z deklaracją).

**Werdykt: WYMAGA ZMIAN przed merge.** Jeden **blocker**, znaleziony i potwierdzony DZIAŁANIEM (nie tylko czytaniem kodu) na bazie scratch: jawne `p_dry_run = NULL` (w przeciwieństwie do pominięcia argumentu) omija domyślną wartość `FALSE` i wykonuje PEŁNĄ, NIEODWRACALNĄ anonimizację zamiast bezpiecznego podglądu — bez błędu, bez ostrzeżenia, w trybie oznaczonym w wyniku jako `"dry_run": false`. To dokładnie scenariusz, przed którym ostrzega zlecenie tej recenzji („funkcja mogłaby pominąć coś po cichu” — tu jest odwrotnie: cicho POMIJA bezpieczeństwo i wykonuje destrukcję). Poza tym blockerem: SQL jest bardzo starannie napisany — guard DB061-08, kolejność instrukcji, atomowość, zbieranie kluczy S3 przed wyzerowaniem, reguła mostu (delegowana do `fn_customer_subject_ids` z DB-061, nie zduplikowana), oraz WSZYSTKIE 9 par predykatów idempotencji dry-run/real są bajt-w-bajt identyczne (zweryfikowane, nie założone) i NULL-bezpieczne względem rzeczywistej nullability kolumn (zweryfikowanej w migracjach źródłowych, nie w notatce). Zero nowych naruszeń izolacji tenantów. Reszta ustaleń to minory/nity — nie blokują merge same w sobie, ale warto domknąć razem z blockerem.

| ID | Waga | Status | Miejsce | Streszczenie |
|----|------|--------|---------|--------------|
| DB062-01 | **blocker** | **potwierdzone DZIAŁANIEM na scratch DB** | `V096…sql:274` (`IF p_dry_run THEN`) | `p_dry_run = NULL` (jawnie, nie pominięte) → PL/pgSQL traktuje `NULL` w `IF` jak `FALSE` → pełna, nieodwracalna anonimizacja zamiast podglądu, `"dry_run": false` w wyniku, bez błędu |
| DB062-02 | minor | potwierdzone (kod + test) | `AnonymizeCustomerExtensionTest.java:415-452` vs `:174-277` | idempotencja podwójnego RZECZYWISTEGO wywołania przetestowana tylko na ubogim fixture (`CUSTOMER_JAVA_ONLY`, bez `campaign_contact_archive`/`contacts_dw`/`notes`/`recording_url`/`channel_metadata`) — bogaty fixture (`CUSTOMER_MAIN`) nigdy nie jest wywołany dwa razy w trybie rzeczywistym |
| DB062-03 | nit | potwierdzone (diff bajt-po-bajcie) | `V096…sql:274-369` vs `:378-503` | 9 par predykatów WHERE ręcznie duplikowanych między blokiem `p_dry_run` a blokiem mutacji — dziś identyczne, ale nic nie wymusza synchronizacji przy przyszłej edycji |
| DB062-04 | nit | potwierdzone (kod) | `V096…sql:579-580` | wszystkie wyjątki (w tym guard DB061-08) spłaszczone do SQLSTATE `P0001` przez wspólny `EXCEPTION WHEN OTHERS` (wzorzec V013) — BE-129 rozróżni „guard/manualna interwencja” od zwykłego błędu tylko przez dopasowanie tekstu komunikatu |
| DB062-05 | nit | obserwacja (niepotwierdzone empirycznie) | `V096…sql:197-223` (`campaign_contact`), `:455-463` (`contact_transcription`/`contact_ai_summary`) | guard/UPDATE i DELETE filtrują po kolumnie INNEJ niż klucz partycjonowania (`record_id`/`contact_id`, nie `campaign_id`/`created_at`) — brak partition pruning, choć indeks per-partycja istnieje; ta sama klasa ryzyka już zaakceptowana w recenzji DB-061 dla dużych klientów |

### 🐛 Bugs / Critical Issues

- **DB062-01 (blocker) — `V096…sql:274`.** `IF p_dry_run THEN` — zgodnie ze standardową semantyką PL/pgSQL, warunek `NULL` w `IF` jest traktowany jak `FALSE` (blok się NIE wykonuje, sterowanie „przelatuje” do sekcji „TRYB RZECZYWISTY” w linii 371+). Domyślna wartość `p_dry_run BOOLEAN DEFAULT FALSE` (linia 153) chroni WYŁĄCZNIE przypadek POMINIĘCIA argumentu w wywołaniu (np. wywołanie 3-argumentowe) — nie chroni przypadku, gdy wywołujący jawnie przekaże SQL `NULL` jako 4. argument. **Potwierdzone DZIAŁANIEM, nie tylko czytaniem kodu**: odtworzyłem pełny łańcuch migracji V001..V096 na jednorazowej bazie scratch (`scratch_db062_review`, utworzonej i usuniętej w trakcie tej recenzji — żywa baza `contact_center` nietknięta przez cały czas, zero wywołań `anonymize_customer` na żywej bazie, zgodnie z ograniczeniami zlecenia), zasiałem minimalnego klienta z jednym kontaktem i wywołałem `SELECT anonymize_customer(<customer>, <tenant>, NULL::uuid, NULL::boolean)` (4. argument jawnie `NULL`, nie pominięty) wewnątrz transakcji zakończonej `ROLLBACK`. Wynik: `{"dry_run": false, "counts": {"contact": 1, "customer": 1, ...}, ...}`, a `contact.remote_address` zmienił się z `'+48123123123'` na `NULL` i `customer.is_deleted` z `f` na `t` — **RZECZYWISTA, NIEODWRACALNA MUTACJA zamiast bezpiecznego podglądu**, bez żadnego błędu ani ostrzeżenia. Dla kontrastu: to samo wywołanie z POMINIĘTYM 4. argumentem (`SELECT anonymize_customer(<customer>, <tenant>, NULL::uuid)`, 3 argumenty) poprawnie korzysta z `DEFAULT FALSE` i też mutuje — czyli zachowanie jest IDENTYCZNE dla „pominięty argument” i „jawny NULL”, mimo że semantycznie to dwa różne sygnały wywołującego ([]„nie wiem/nie mam zdania” dla NULL vs „chcę domyślne zachowanie” dla pominięcia). **Dlaczego to realne ryzyko, nie tylko teoretyczne**: BE-129 (kolejny ticket, jeszcze niezaimplementowany) będzie jedynym wołającym tej funkcji z Javy. Typowy wzorzec JDBC/Spring (`Boolean dryRun` boxed, `ps.setObject(4, dryRun)` albo `NamedParameterJdbcTemplate` z mapą parametrów, gdzie brakujący/`null` klucz DTO trafia jako SQL `NULL`, nie jako pominięty parametr) sprawiłby, że request z nieustawionym/`null` flagą podglądu (np. deserializacja `{"customerId": "...", "userId": "..."}` bez pola `dryRun` do rekordu Javy z polem `Boolean dryRun` bez wartości domyślnej) cicho wykonałby PEŁNĄ anonimizację zamiast podglądu — dokładnie odwrotność tego, czego endpoint podglądu miałby dowieść przed nieodwracalną operacją. Żaden test w `AnonymizeCustomerExtensionTest` nie sprawdza tego przypadku (wszystkie 13 testów używają jawnego `TRUE`/`FALSE`, nigdy `NULL`). **Rekomendacja (tania, jednoliniowa zmiana w kolejnej migracji `V097`, NIE edytować V096 — reguła projektu „nigdy nie edytuj zastosowanej migracji”, a V096 może już być zastosowana na czyimś środowisku deweloperskim/CI zanim ta recenzja dotrze do PR):** dodać na początku ciała funkcji (zaraz po `BEGIN`) jawne odrzucenie `NULL`: `IF p_dry_run IS NULL THEN RAISE EXCEPTION 'anonymize_customer: p_dry_run nie moze byc NULL — podaj jawnie TRUE albo FALSE.'; END IF;` — zamienia CICHĄ DESTRUKCJĘ w GŁOŚNY, BEZPIECZNY błąd (dokładnie ta sama filozofia, jaką autor już zastosował dla guardu DB061-08). Alternatywa (mniej bezpieczna, ale też akceptowalna): `IF COALESCE(p_dry_run, FALSE) THEN`, jeśli zespół uzna, że NULL powinien oznaczać „wykonaj” — ale to wymaga świadomej decyzji projektowej, nie milczącego domysłu jak dziś. Jeśli V096 NIE jest jeszcze scalone do `main`/wdrożone nigdzie — dopuszczalne jest też poprawienie samego pliku V096 (nie zastosowana na żywej bazie per notatka wykonania), zamiast nowej migracji; do decyzji zespołu w zależności od stanu innych gałęzi.

- **Sprawdzone i potwierdzone jako poprawne** (żeby nie pozostawić wrażenia, że reszta funkcji nie była badana równie rygorystycznie):
  - **Guard DB061-08 uruchamia się PRZED jakąkolwiek mutacją** (linie 192-223, zaraz po obliczeniu zbioru podmiotu, przed pierwszym `UPDATE`/`DELETE` w linii 380) i nie daje fałszywych trafień: `JOIN` w guardzie startuje od `subj` (zbiór podmiotu), więc dwa NIEPOWIĄZANE rekordy dzielące `record_id`, z których ŻADEN nie należy do zbioru podmiotu bieżącego wywołania, nie tworzą żadnego wiersza w wyniku joina i guard się nie uruchamia — potwierdzone czytaniem kodu ORAZ pośrednio empirycznie: fixture kolizji (`CAMPAIGN_COLLISION_A`/`B`, ten sam `record_id` co świadomy test) współistnieje w TEJ SAMEJ bazie testowej (`@BeforeAll`, jedna baza dla wszystkich 13 testów) co pozostałe scenariusze (`fullDataSet`, `bridgeRule`, `dosanityzacja`, `sharedPhone`, `rls`), a te wywołania `anonymize_customer` dla INNYCH klientów przechodzą bez wyjątku mimo obecności kolizji gdzieś indziej w tym samym tenancie — gdyby guard był zbyt szeroki (np. sprawdzał WSZYSTKIE kolizje w tenancie, nie tylko te dotyczące zbioru podmiotu), którykolwiek z tych testów by padł.
  - **`RAISE EXCEPTION` z guardu daje pełny ROLLBACK** — funkcja ma DOKŁADNIE JEDEN blok `BEGIN…EXCEPTION WHEN OTHERS…END` obejmujący całe ciało (linie 179-581), bez zagnieżdżonych bloków/`SAVEPOINT`/subtransakcji — PL/pgSQL tworzy niejawny savepoint na starcie takiego bloku; każdy błąd (w tym z guardu, w tym z poison-triggera na końcu) cofa WSZYSTKO od `BEGIN`, niezależnie od tego, na którym kroku wystąpił. Potwierdzone testem `atomicity_forcedFailureAtFinalStep_rollsBackEverythingBefore` (błąd na OSTATNIM kroku, wszystkie 6 wcześniejszych mutacji cofnięte) — najbardziej rygorystyczny możliwy test tej własności.
  - **Kolejność instrukcji**: przeczytane od góry do dołu — WSZYSTKIE `UPDATE`/`DELETE` na `contact` i tabelach pochodnych (kroki 1-8, linie 380-503) są PRZED `UPDATE customer SET is_deleted = TRUE` (krok 9, linia 523-533, ostatnia mutacja przed `INSERT INTO audit_log`). Zgodne z deklaracją.
  - **Idempotencja — wszystkie 9 par predykatów zweryfikowane bajt-po-bajcie** (`sed` na dokładnych zakresach linii, nie porównanie „na oko”) między blokiem `p_dry_run` (linie 274-369) a blokiem mutacji (378-503) — identyczne. Każdy predykat NULL-bezpieczny względem RZECZYWISTEJ nullability kolumny, zweryfikowanej niezależnie w migracjach źródłowych (nie w notatce wykonawcy): `scheduled_callback.phone` `NOT NULL` (V009) → poprawnie plain `<>`; `first_name`/`last_name` nullable → poprawnie `IS DISTINCT FROM`; `campaign_contact.phone`/`email` nullable (V009) → poprawnie `IS NOT NULL`; `email_message.from_address`/`to_address` `NOT NULL`, `cc_address`/`bcc_address`/`subject`/`body_html`/`body_text` nullable (V010) → wszystkie warunki dobrane poprawnie do nullability; `social_message.sender_external_id`/`content` nullable (V010) → `IS DISTINCT FROM`. Zero niespójności.
  - **Klucze S3 zebrane PRZED wyzerowaniem** (linie 238-268, przed sekcją mutacji 371+) w obu kształtach e-mail (`msg-inbound`/`pending`), z defensywnym `jsonb_typeof(att->'s3_key') = 'string'` (naprawia nit DB061-06 z recenzji V095, w nowym kodzie). Pole `s3_key` (nie `s3_url`, mimo mylącego komentarza w `V010`) potwierdzone jako RZECZYWISTE pole zapisywane przez `EmailPollingServiceImpl`/`EmailSendService` — zgodne ze znaną, już udokumentowaną rozbieżnością komentarz/dane (`EmailAttachmentKeys.java:22-23`, z ery BE-124). Dla `social_message` — `SocialMessageRepository.java:104-106` dokumentuje, że domena social NIE MA dziś żadnych obiektów S3 (same metadane `type`/`url`/`size_bytes`, bez `s3_key`), więc zapytanie zbierające `s3_key` z `social_message` poprawnie zwraca dziś zawsze pusty zbiór — to nie luka, to zgodność z rzeczywistym stanem domeny.
  - **`counts.customer` zawsze `1` przy sukcesie** — zweryfikowałem PRZYCZYNĘ tej gwarancji (nie tylko zaufałem deklaracji): `fn_customer_subject_ids` (V095, reużywana bez zmian) ma na samym początku bezwarunkowy `IF NOT EXISTS (SELECT 1 FROM customer WHERE customer_id = p_customer_id AND tenant_id = p_tenant_id) THEN RAISE EXCEPTION`, wywoływany jako DOSŁOWNIE pierwsza instrukcja `anonymize_customer` (linia 186-190) — więc funkcja przerywa się PRZED jakąkolwiek mutacją dla nieistniejącego/niewidocznego (RLS) klienta, i nie ma możliwości dotrzeć do końcowego `UPDATE customer` (który ustawia `v_n_customer`) z klientem, który w międzyczasie „zniknął” — architektura zakazuje twardego DELETE na `customer` (tylko soft-delete), więc nie ma też realnego wyścigu TOCTOU między sprawdzeniem na starcie a UPDATE-em na końcu tej samej transakcji.
  - **`DROP FUNCTION` starej sygnatury bezpieczny** — niezależny grep całego repo (`backend/app/src/main`, `frontend`, `voicebot`) potwierdza ZERO wołających starej 3-argumentowej sygnatury poza jednym testem (`ContactRefIntegrityNarrowingTest:198`), który CELOWO działa na bazie zmigrowanej TYLKO do wersji sprzed V094/V096 (`Flyway#info()`, dynamicznie, wzorzec z DB-079) — na tym stanie schematu stara sygnatura nadal istnieje, więc to nie jest błąd, tylko poprawnie odizolowany test „starego” zachowania.
  - **Pełny łańcuch Flyway V001..V096 aplikuje się bez błędu** — niezależnie odtworzony na bazie scratch (`psql -f` po kolei, `ON_ERROR_STOP=1`), zero błędów na żadnym z 96 plików, w tym V096 samo.

### ⚠️ Security Concerns

_Brak nowych zagrożeń izolacji tenantów._ Wszystkie 10 instrukcji mutujących (`UPDATE`/`DELETE`) mają jawny `tenant_id = p_tenant_id` w `WHERE`/`USING` (zweryfikowane dla każdej z osobna: `contact:387`, `scheduled_callback:403`, `campaign_contact:426`, `campaign_contact_archive:444`, `contact_transcription:457`, `contact_ai_summary:462`, `email_message:477`, `social_message:491`, `contacts_dw:500`, `customer:533`). Reguła mostu (bridge rule) NIE jest reimplementowana w V096 — deleguje w całości do `fn_customer_subject_ids` (V095, już zrecenzowana i zatwierdzona), co oznacza brak ryzyka rozjazdu logiki między eksportem (DB-061) a anonimizacją (DB-062); V096 tylko konsumuje płaską listę `(entity_type, entity_id)`.

**Niezależnie zweryfikowane na ŻYWEJ bazie `contact_center` (zapytania tylko-do-odczytu, zero wywołań funkcji, zero migracji zastosowanych)** — dokładnie potwierdza „odkrycie nieoczywiste” z notatki wykonania (RLS-DB062-punkt-9 w zleceniu):
  - `SELECT polname, polcmd FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid WHERE c.relname = 'audit_log'` → dokładnie JEDEN wiersz: `pol_audit_log_select`, `polcmd = 'r'` (SELECT). Żadnej polityki INSERT — potwierdzone zarówno na żywej bazie, jak i niezależnym grepem WSZYSTKICH migracji po `CREATE POLICY.*audit_log` (tylko V012, tylko SELECT).
  - `SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname IN (11 tabel dotykanych przez V096)` → wynik bajt-w-bajt zgodny z tabelą w nagłówku migracji (linie 111-134): `contact`/`customer`/`contact_transcription`/`contact_ai_summary` mają `FORCE`; `audit_log`/`email_message`/`social_message`/`scheduled_callback` mają RLS bez `FORCE`; `campaign_contact`/`campaign_contact_archive`/`contacts_dw` bez RLS w ogóle.
  - `SELECT rolname, rolbypassrls FROM pg_roles WHERE rolname IN ('ccapp','app_user','admin_user')` → `ccapp` (rola, którą backend faktycznie łączy się na tym środowisku — `DB_USERNAME` w `application-*.yml`) ma `rolbypassrls = true`, tak samo `admin_user`; `app_user` ma `false`. Potwierdza założenie notatki, że produkcyjne połączenie backendu ZAWSZE omija RLS — ale to jest zależność środowiskowa (wartość zmiennej `DB_USERNAME`), nie gwarancja schematu; gdyby ktoś kiedyś przełączył backend na `app_user` (np. w ramach przyszłego DB-071/DB-074, wspomnianych w notatce jako plan naprawy RLS), `anonymize_customer` w trybie rzeczywistym zacznie TWARDO rzucać na `INSERT INTO audit_log` dla KAŻDEGO klienta, nie tylko częściowo anonimizować — co jest bezpieczniejszym trybem awarii niż cichy częściowy sukces, ale warto, żeby DB-071/DB-074 miały to na liście rzeczy do zweryfikowania (uwzględnienia polityki INSERT na `audit_log` jest wymagane, zanim `app_user` mógłby kiedykolwiek być używany dla ścieżek zapisowych obejmujących audyt).

Ocena „odstępstwa od dosłownego AC R1” z notatki wykonania: uzasadniona. Twardy błąd na `audit_log` pod `app_user` (zamiast cichego częściowego sukcesu) jest silniejszą gwarancją bezpieczeństwa danych (żadnej połowicznej anonimizacji), zgodnie z twierdzeniem notatki — potwierdzone niezależnie, nie tylko zaufane.

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń reguł projektu poza blockerem DB062-01 (który jest błędem logiki/bezpieczeństwa, nie architektury)._ Nazwa migracji zgodna z `V{NNN}__{description}.sql`. Numeracja V096 zweryfikowana pośrednio (pełny łańcuch V001-V096 aplikuje się bez konfliktu na bazie scratch odtworzonej z plików repo; `flyway_schema_history` żywej bazy kończy się na 093, więc V094-096 rzeczywiście niezastosowane, zgodnie z deklaracją). Migracja nie edytuje V013 (reguła „nie edytuj zastosowanej migracji” zachowana) — `DROP FUNCTION` + `CREATE FUNCTION` to właściwy mechanizm przy zmianie typu zwracanego (`CREATE OR REPLACE` by tego nie obsłużył). Brak triggerów na tabelach dotykanych przez V096 poza już uwzględnionymi (`trg_contact_ref_integrity` na `contact`, zawężony przez V094; `trg_customer_updated_at`/`trg_campaign_contact_updated_at` — tylko ustawiają `updated_at`, nieszkodliwe wobec jawnego `updated_at = NOW()` w V096) — zweryfikowane grepem `CREATE TRIGGER` po wszystkich tabelach dotykanych przez funkcję.

### 🔧 Improvements & Suggestions

- **DB062-02 · minor · `AnonymizeCustomerExtensionTest.java:415-452`** — jedyny test z DWOMA rzeczywistymi (nie dry-run) wywołaniami tego samego klienta na tym samym stanie bazy (`dosanityzacja_customerAlreadyDeletedViaJavaPath_completesRemainingDataIdempotently`) używa fixture `CUSTOMER_JAVA_ONLY`, który NIE ma wierszy w `campaign_contact_archive` ani `contacts_dw`, i nie ustawia `contact.notes`/`recording_url`/`channel_metadata` na fixture'cie. Idempotencja tych konkretnych kolumn/tabel jest dziś potwierdzona WYŁĄCZNIE przez symetrię kodu (identyczny kształt predykatu jak w `campaign_contact`, które JEST przetestowane) — nie przez faktyczne drugie wywołanie z realnymi danymi w tych tabelach. Rekomendacja: dodać drugie (trzecie) rzeczywiste wywołanie `anonymize_customer(CUSTOMER_MAIN, TENANT_A, ..., FALSE)` na końcu testu `fullDataSet_...` (albo osobny test), asercja, że WSZYSTKIE liczniki poza `customer` wynoszą `0` przy drugim wywołaniu — tani do dodania, dane fixture już istnieją.

- **DB062-03 · nit · `V096…sql:274-369` vs `:378-503`** — 9 par predykatów WHERE (jeden per tabela) jest ręcznie zduplikowanych między blokiem `p_dry_run` a blokiem mutacji. Dziś identyczne (zweryfikowane), ale to manualne utrzymanie synchronizacji — przyszła migracja zmieniająca jeden z zestawów (np. dodanie nowej kolumny PII do `campaign_contact` do zerowania) bez zmiany drugiego cicho rozjedzie podgląd (`p_dry_run=TRUE`) od rzeczywistego zachowania, bez żadnego mechanizmu wymuszającego spójność poza czujnością recenzenta. Test `dryRun_doesNotMutate_twoCallsIdentical_matchesRealRunCounts` (linie 458-484) częściowo to łapie (porównuje liczby dla `contact`/`scheduled_callback`/`campaign_contact`/`campaign_contact_archive`/`email_message`/`social_message`), ale NIE dla `contacts_dw` ani `contact_transcription`/`contact_ai_summary` (te dwie ostatnie nie mają osobnego predykatu idempotencji, więc ryzyko tam mniejsze). Rekomendacja: rozważyć w przyszłej migracji materializację zbioru podmiotu do tabeli tymczasowej z pojedynczym zestawem predykatów reużywanym przez `SELECT count(*)`/`UPDATE ... RETURNING count`, albo — taniej — dodać `contacts_dw` do istniejącego testu spójności dry-run/real.

- **DB062-04 · nit · `V096…sql:579-580`** — wspólny `EXCEPTION WHEN OTHERS THEN RAISE EXCEPTION 'Blad anonimizacji klienta %: %', p_customer_id, SQLERRM` (wzorzec odziedziczony z V013, nie regresja tego ticketu) spłaszcza WSZYSTKIE błędy (guard DB061-08, RLS na `audit_log`, poison trigger, cokolwiek innego) do jednego SQLSTATE `P0001`, tracąc oryginalny SQLSTATE/DETAIL/HINT — zachowuje tylko tekst `SQLERRM`. Test (B) w `AnonymizeCustomerExtensionTest` już dokumentuje to ograniczenie (`error.getSQLState()).isEqualTo("P0001")` zamiast oczekiwanego `42501`). Dla BE-129: jedyny sposób odróżnienia „guard DB061-08, wymaga ręcznej interwencji” od „zwykły/przejściowy błąd” to dopasowanie podłańcucha `"DB061-08"` w tekście komunikatu (po polsku) — kruche, ale działa, bo komunikat guardu jest stabilny tekstowo. Rekomendacja dla BE-129 (nie blokuje DB-062): udokumentować ten kontrakt string-matchingu jawnie w kodzie Java (stała/komentarz wskazujący na V096), albo — w przyszłej migracji — nadać guardowi własny niestandardowy `ERRCODE` przez `RAISE EXCEPTION ... USING ERRCODE = 'XXNNN'`.

- **DB062-05 · nit · wydajność, bez wpływu na poprawność** — guard/`UPDATE` na `campaign_contact` (partycjonowana LIST po `campaign_id`) i `DELETE` na `contact_transcription`/`contact_ai_summary` (partycjonowane RANGE po `created_at`/`generated_at`) filtrują po kolumnie INNEJ niż klucz partycjonowania (`record_id`/`contact_id`) — Postgres nie może przyciąć partycji na podstawie tego predykatu, więc plan musi rozważyć każdą partycję osobno (choć z korzyścią z indeksu per-partycja: PK złożony `(record_id, campaign_id)` dla `campaign_contact`, `(contact_id, tenant_id)` dla transkrypcji/podsumowań). Przy niewielkiej liczbie partycji (miesięczne dla transkrypcji/podsumowań, per-kampania dla `campaign_contact`) to pomijalne; przy kliencie z bardzo długą historią / tenancie z tysiącami kampanii mogłoby być zauważalne. Ta sama klasa ryzyka (duży klient, brak strumieniowania) była już świadomie zaakceptowana i przekazana do BE-129 w recenzji DB-061 — nie traktuję jako nowego problemu tego ticketu, tylko odnotowuję kontynuację.

- **Nit, opcjonalny test:** dodać JEDEN bezpośredni test guardu DB061-08 dla scenariusza „fałszywie dodatni” z zapytania recenzji (dwa NIEPOWIĄZANE rekordy campaign_contact dzielące `record_id`, ŻADEN nie należy do żadnego zbioru podmiotu w teście) — dziś potwierdzone tylko pośrednio (współistnienie fixture kolizji z resztą scenariuszy w jednej bazie testowej, żaden inny test nie pada). Tani do dodania, zwiększa czytelność intencji dla przyszłych czytelników testu.

### ✅ Positive Observations

- **Zbiór podmiotu zamrożony RAZ, na starcie, w tablicach PL/pgSQL** (linie 42-53 nagłówka, 186-190 kodu) — świadomie unika klasycznego błędu „progressive narrowing” (kolejne wywołania `fn_customer_subject_ids` w miarę zerowania kolumn identyfikujących dawałyby kurczący się zbiór) — rozwiązanie eleganckie i poprawnie uzasadnione w komentarzu, zweryfikowane, że rzeczywiście funkcja pomocnicza jest wołana DOKŁADNIE RAZ w całej funkcji.
- **Reguła mostu w pełni zdelegowana do `fn_customer_subject_ids` (DB-061), nie zduplikowana** — V096 nie zawiera ŻADNEJ własnej logiki dopasowania encji do klienta, tylko konsumuje płaską listę `(entity_type, entity_id)` — eliminuje całą klasę błędów „dwie kopie tej samej reguły biznesowej rozjeżdżają się w czasie”, i to widać w praktyce: dedykowany test `bridgeRule_identifierOnlyContact_bridgedCampaignRecordAndCallback_areAnonymized` domyka rekomendację DB061-03 z poprzedniej recenzji (wariant „kontakt tylko-identyfikator + most”, którego brakowało w DB-061).
- **Guard DB061-08 to wzorcowy przykład „zamień ciche uszkodzenie danych w głośny, bezpieczny błąd”** — decyzja architektoniczna (odrzucenie „czystego” wariantu opierającego się wyłącznie na `tenant_id`) jest udokumentowana z uzasadnieniem OPARTYM NA TEŚCIE (nie na przypuszczeniu), i dokładnie ta sama filozofia powinna była zostać zastosowana do `p_dry_run = NULL` (DB062-01) — autor miał już właściwy odruch w tej migracji, tylko nie zastosował go konsekwentnie do WSZYSTKICH parametrów wejściowych.
- **Klucze S3 dla `social_message` poprawnie (dziś) puste, bo domena tego nie ma** — zamiast zakładać strukturę danych, kod używa defensywnego `jsonb_typeof(...) = 'string'`, który poprawnie zwraca pusty wynik dla dzisiejszego kształtu danych social (`type`/`url`/`size_bytes`, bez `s3_key`) i automatycznie zacznie działać, gdyby domena social kiedyś zaczęła używać S3 — bez potrzeby zmiany tej migracji.
- **Naprawa `ContactRefIntegrityNarrowingTest` jest minimalna, poprawna i WZMOCNIONA, nie osłabiona** — stara asercja sprawdzała tylko „nie rzuca” (`isNull()` na wyjątku); nowa sprawdza DODATKOWO wartość zwróconego licznika (`contact = 2`) odczytaną z JSONB, czyli test po zmianie sprawdza WIĘCEJ niż przed zmianą, nie mniej — dokładnie odwrotność „naprawy przez osłabienie asercji”, którą trzeba było wykluczyć per zlecenie recenzji.
- **Test suite (13 testów) systematycznie pokrywa niemal całą listę z zapytania recenzji** — pełny zestaw z asercjami PO WARTOŚCIACH (nie tylko liczności), izolacja cross-tenant/cross-customer, reguła mostu z normalizacją telefonu (separatory), guard kolizji w OBU trybach, atomowość przez wymuszony błąd na OSTATNIM kroku (najsurowszy wariant tego testu), dosanityzowanie klienta ze starej ścieżki Javy, spójność podglądu z `export_customer_data` (domyka otwarte kryterium akceptacji z DB-061), RLS per-tabela z trzema odrębnymi, dobrze wyizolowanymi testami, guard `contacts_dw` przez PRAWDZIWY `ALTER TABLE ... DROP COLUMN` w cofanej transakcji (nie atrapę/flagę). Jedyna systematyczna luka to brak testu `p_dry_run = NULL` (DB062-01) i słabsze pokrycie podwójnego rzeczywistego wywołania na bogatym fixture (DB062-02).

### Summary

**Ocena: 2/5 ⭐ — wymaga zmian przed merge.** SQL jest w ogromnej większości bardzo starannie zaprojektowany i zaimplementowany — guard DB061-08, kolejność instrukcji, atomowość, izolacja tenantów, delegacja reguły mostu do DB-061, idempotencja predykatów (zweryfikowana bajt-po-bajcie i względem rzeczywistej nullability schematu) są wszystkie poprawne, a test suite jest jednym z bardziej wyczerpujących w tym repo. Ocena jest mimo to niska, bo znaleziony **blocker (DB062-01) jest dokładnie tego rodzaju błędem, przed którym ostrzega charakter tego ticketu**: funkcja NIEODWRACALNIE kasuje/nadpisuje PII, jej JEDYNYM mechanizmem bezpieczeństwa przed przypadkowym użyciem w produkcji jest `p_dry_run`, a ten mechanizm ma udowodnioną DZIAŁANIEM (nie przypuszczeniem) dziurę: jawny SQL `NULL` w miejscu tego parametru — realistyczny do wystąpienia przez typowy wzorzec JDBC/DTO w przyszłym BE-129 — cicho wykonuje PEŁNĄ anonimizację zamiast podglądu, bez błędu, bez ostrzeżenia, z wynikiem fałszywie oznaczonym jako `"dry_run": false` (czyli funkcja NAWET NIE PRÓBUJE zasygnalizować niejednoznaczności). Fix jest tani (jedna linia, jeden `RAISE EXCEPTION` na starcie funkcji, dokładnie ta sama filozofia co już zastosowany guard DB061-08) i nie wymaga przeprojektowania. **Werdykt: WYMAGA ZMIAN** — nie zatwierdzać do merge w obecnej postaci; po naprawieniu DB062-01 (i, jeśli czas pozwoli, domknięciu DB062-02 jako taniego wzmocnienia test suite'u) migracja kwalifikuje się do „zatwierdzić z poprawkami” na poziomie zbliżonym do DB-061 (4/5) — reszta ustaleń to nity, żaden nie jest samodzielnym powodem blokady.


---

## Review: V126–V133 + testy migracji (DB-056, DB-078, DB-057, DB-058, DB-076, EPIC-30) — 2026-10-10

**Zakres:** `git diff 63090f1..HEAD` — `V126__purge_campaign_contact_archive_batched.sql`, `V127__sweep_contacts_dw_remote_address.sql`, `V128__drop_contacts_dw_remote_address.sql`, `V129`/`V130`/`V131` (DROP indeksów), `V132__drop_unused_materialized_views.sql`, `V133__reconcile_scheduled_job_with_java_executors.sql`; testy `CampaignContactArchivePurgeTenantIsolationTest`, `ContactsDwRemoteAddressDropMigrationTest`, `Db057IndexCleanupMigrationsTest`, `Db058DropMaterializedViewsMigrationTest`, `Db076ScheduledJobReconciliationMigrationTest`; zgodność dokumentacji (`06-database.md`, `ARCHITECTURE.md`, `DESIGN-message-retention-and-partitioning.md`, `CR-DATABASE.md`) z faktami. Kod Java/FE poza zakresem. Migracji nie uruchamiałem; na żywej bazie wykonałem wyłącznie zapytania SELECT/katalog (jedna transakcja `READ ONLY` + `ROLLBACK`).

### Tabela ustaleń

| ID | Severity | Pewność | Gdzie | Skrót |
|----|----------|---------|-------|-------|
| DB-R1 | ważne (proces/dokumentacja) | **pewne** (flyway_schema_history żywej bazy) | `TASKS-DATABASE.md` (DB-057, DB-078), `DESIGN-…md:171`, `V132` nagłówek | Żywa baza jest już na **V133** (V127–V133 zastosowane 2026-10-09 21:00:27); dokumenty twierdzą „ostatnia = V126 / niezastosowane”. V131 „do wycięcia z wydania do decyzji właściciela” wdrożyła się automatycznie. |
| DB-R2 | ważne | **pewne** (kod + własny test autora) | `V129:138-142` (`COMMENT ON INDEX`), `V129:116-119`, `Db057…Test:260-266` | Komentarz (trwały w katalogu) i komunikat guardu twierdzą, że `idx_scheduled_callback_due` obsługuje `findDueCallbacks`; test autora dowodzi, że `findDueCallbacks` używa `idx_callback_scheduled`, a `due` obsługuje tylko kształt z `is_deleted = false`, którego żaden kod Javy nie wysyła. |
| DB-R3 | drobne | **pewne** (kod), skutek dziś zerowy | `V128:236-244` | Guard (b) PL/pgSQL: tylko `prokind = 'f'` (pomija procedury; V132 ma `IN ('f','p')`), wyjątek przez podciąg `v_has_contacts_dw_remote_address` w DOWOLNYM miejscu `prosrc` (również w komentarzu / przy jednym guardowanym i jednym niechronionym odwołaniu), brak wykrycia SQL dynamicznego i innych schematów. Zweryfikowane na żywej bazie: dziś pasuje tylko `anonymize_customer` (poprawnie zagwardowana). |
| DB-R4 | drobne→ważne przy dużym DW | spekulacja co do skali (kod pewny) | `V127:174` | Jeden `UPDATE` całej tabeli, bez batchowania, bez `lock_timeout`/`statement_timeout`, w migracji startowej Flyway; stare wersje wierszy z PII zostają w martwych krotkach/WAL/backupach do VACUUM — ani nagłówek V127/V128, ani doc tego nie mówią. Na demo: 130 wierszy, bez problemu. |
| DB-R5 | drobne | **pewne** (kod testu) | `CampaignContactArchivePurgeTenantIsolationTest.java:215-218`, `V126:43-47` | Test `…underAppUserWithGuc` w `@DisplayName` obiecuje „bez GUC cichy 0”, ale sprawdza tylko GUC innego tenanta; przypadek braku/pustego GUC (`''`) niepokryty. Nagłówek V126 twierdzi „cichy 0” — dla `''` na niepustej tabeli prawdopodobnie błąd `22P02` (kod Javy `CampaignArchiveJob` dokumentuje taki pomiar dla `campaign`); niepotwierdzone (żywa tabela archiwum jest pusta, nie wymusza ewaluacji polityki). |
| DB-R6 | drobne | **pewne** | `06-database.md` (`scheduled_job`), `V133:174-176` | „`last_run_at` aktualizują tylko funkcje `create_next_month_partitions()` i `archive_completed_campaign_contacts()`” — fałsz: aktualizują go też `cleanup_expired_refresh_tokens()` i 6× `rotate_*` (pg_proc na żywej bazie); V133 nagłówek pomija `cleanup_expired_refresh_tokens`. |
| DB-R7 | drobne | **pewne** | `Db057…:63-` (`@Order`), `Db058`, `Db076`, `ContactsDw…` + `Db076…:224` | Wszystkie 4 testy migracji to łańcuchy `@Order` ze statycznym stanem (pojedynczy test uruchomiony samodzielnie padnie lub da fałszywy wynik); `Db076` test 8 używa ścieżki względnej `src/main/java/...` (działa tylko z cwd = `backend/app`). |
| DB-R8 | drobne | **pewne** | `Db057…:262-302` | Asercje EXPLAIN: `SET LOCAL enable_seqscan = off` + `doesNotContain("Seq Scan")` jest prawie tautologią; test 22 `containsAnyOf(lookup, agent)` nie dowodzi niczego o zwycięzcy (dobra jest tylko druga połowa — po DROP `_agent` wymusza Index Only Scan). Brak testu guardu V131 dla `idx_cca_tenant_customer`. |
| DB-R9 | drobne | **pewne** | `Db076…:194-201`, `V126` testy | Test komentarzy V133 sprawdza 5 z 11 funkcji i tylko wzorzec „Wywolywana przez pg_cron”; brak testu współbieżności `SKIP LOCKED` (2 połączenia), brak asercji na `p_cutoff_date IS NULL` / `p_tenant_id IS NULL` (zwraca 0 — bezpieczne, ale niepinowane). Javadoc klasy `CampaignContactArchivePurgeTenantIsolationTest` nadal opisuje wersję V091 / 2 argumenty. |
| DB-R10 | nit | **pewne** | `V133:197-198, 219-221` | `is_active = TRUE` dla `archive_completed_campaign_contacts` mimo flagi `retention.campaign-archive.enabled=false` — rejestr „aktywny”, wykonawca wyłączony; opis tekstowy w DB to zamrożona kopia flagi (zdryfuje po włączeniu). Decyzja właściciela — tylko odnotowuję. |

### 🐛 Bugs / Critical Issues

_Brak błędów krytycznych._ SQL V126–V133 jest poprawny i — co ważne — **już zastosowany na żywej bazie bez błędu** (`flyway_schema_history`: V126 14:47:25 / 28 ms; V127–V133 21:00:27–28, razem ok. 0,5 s, V132 276 ms; wszystkie `success = t`, 132 wiersze historii, brak V104 — zgodnie z rezerwacją).

- **DB-R1 (ważne, dokumentacja/proces).** Zlecenie i dokumenty zakładają, że na żywej bazie jest tylko V126 („V129–V131 niezastosowane (ostatnia = V126); zostaną zastosowane przy restarcie” — `TASKS-DATABASE.md` DB-057; „V094 … niezastosowana na żywej bazie” — `DESIGN-message-retention-and-partitioning.md:171`; V094–V098 też są zastosowane). Stan faktyczny: V133. Konsekwencje: (1) pytania o „blokadę startu aplikacji” są dziś bezprzedmiotowe — migracje przeszły; (2) V127 **nieodwracalnie wyzerowała** `contacts_dw.remote_address` i V128 usunęła kolumnę (żywa tabela: kolumna nie istnieje); (3) V131 miała adnotację „decyzja do potwierdzenia przez właściciela — można wyciąć z wydania”, ale wdrożyła się razem z V127–V133 bez tej możliwości — kolejny przykład wzorca „migracja destrukcyjna w paczce z innymi, auto-aplikowana przy starcie”. Poprawka: zaktualizować `TASKS-DATABASE.md`/`PROGRESS.md`/`DESIGN…md:171` do „zastosowane do V133 (2026-10-09)”; na przyszłość migracje z „decyzją właściciela” trzymać poza gałęzią wdrożeniową do czasu decyzji (nie ma sposobu na selektywne pominięcie przy `validate-on-migrate` + Flyway startowym).
- **DB-R2 (ważne, treść trwała w katalogu).** `V129:138-142` ustawia `COMMENT ON INDEX idx_scheduled_callback_due` na „callbacki gotowe do realizacji (findDueCallbacks: …)”, a komunikat guardu (`V129:116-119`) mówi „jedynego indeksu obsługującego findDueCallbacks”. Fakty: `ScheduledCallbackRepository#findDueCallbacks` (`WHERE tenant_id AND status='PENDING' AND scheduled_at <= NOW() ORDER BY scheduled_at LIMIT`) **nie zawiera `is_deleted = false`**, więc planista nie może użyć indeksów częściowych z predykatem `is_deleted = FALSE` — a test autora (`Db057…Test` `explainDueCallbacksJavaShape`) sam to dowodzi (`idx_callback_scheduled`, nie `due`/`ready`). Wniosek: usunięcie `idx_callback_ready` jest poprawne (identyczny duplikat, dobry zwycięzca wg nazewnictwa), ale komentarz wprowadza w błąd — `due` jest dziś indeksem nieużywanym przez Javę (zapis ×2 → ×1, odczyt: nikt). Poprawka (NIE edytować V129 — zastosowana, checksum): nowa migracja `COMMENT ON INDEX idx_scheduled_callback_due IS '…używany tylko przez zapytania z is_deleted = false; findDueCallbacks korzysta z idx_callback_scheduled…'`; dopisać `idx_scheduled_callback_due` do listy „kandydatów do osobnej decyzji” obok `idx_callback_scheduled` (redundantny `status` w kluczu). To samo zdanie zawiera nagłówek V129:30-45 i `TASKS-DATABASE.md`.

### ⚠️ Security Concerns

_Brak nowych zagrożeń izolacji tenantów._ Sprawdzone:
- **V126:** jawny `tenant_id = p_tenant_id` w wyborze partii + `DELETE … USING batch` po pełnym PK `(record_id, campaign_id)`; `SECURITY INVOKER` (brak eskalacji); pod `app_user` (V111 FORCE, polityka `tenant_id = current_setting('app.current_tenant_id', true)::uuid`) wiersze obcego tenanta niewidoczne — test `purge_batched_underAppUserWithGuc` to potwierdza (GUC=B, wołanie A → 0; GUC=A → 2,1,0; B nietknięty: 3). `cron_log` nie ma RLS (`relrowsecurity = f`) — INSERT pod `app_user` działa. `p_batch_size` walidowany `IS NULL OR <1 OR >100000` → `22023`, test pokrywa 0/-1/100001/NULL oraz granice 1 i 100000. Stara sygnatura `(UUID, TIMESTAMPTZ)` usunięta; guard końcowy w V126 liczy WSZYSTKIE przeciążenia `purge_campaign_contact_archive` w `public`. Grep Javy: jedyny wołający to `CampaignArchiveRetentionRepository:190` (3 argumenty); testy wołają 2-argumentowo przez `DEFAULT` (OK, jest test na to). Zero wołających starej sygnatury.
- **V127 `SET LOCAL row_security = off`:** nie jest nadużyciem — dla ról z `BYPASSRLS`/superuser nie ma efektu (migracja i tak omija RLS), dla roli bez BYPASSRLS zamienia cichy `UPDATE 0` na głośny błąd „would be affected by row-level security”. Flyway używa tego samego datasource co aplikacja (`application.yml` brak osobnego użytkownika Flyway) i `ccapp` ma `rolbypassrls = t`; jeśli backend kiedyś zostanie przełączony na `app_user` (DB-071/074), `V127`-podobne migracje DML na tabelach FORCE RLS zaczną twardo padać (korzystnie, nie cicho). Test V127 działa jako superuser (`cc_test`), więc „głośny błąd” jest nieprzetestowany — wniosek z dokumentacji PG, nie z testu.
- **V128:** guard (a) `pg_depend` (widoki/reguły/indeksy/constrainty/defaulty/statystyki) poprawnie łapie zależności katalogowe — test z widokiem to dowodzi; luki PL/pgSQL → DB-R3.

### 🏗️ Architecture / Pattern Violations

- **Zgodne z regułami CLAUDE.md:** żadna zastosowana migracja nie została edytowana (diff zawiera wyłącznie nowe pliki V126–V133); „jedna zmiana = jedna migracja” zachowana (V127 dane / V128 DDL; V129/V130/V131 po jednej tabeli; V132 widoki; V133 dane + COMMENT); numeracja ciągła od V126, brak kolizji (132 pliki, zero duplikatów prefiksu), V104 pominięte. Uwaga trwała: V104 „zarezerwowane” to w praktyce numer **niemożliwy do użycia na prod** — `application-prod.yml` ma `out-of-order: false`, a V105–V133 są zastosowane, więc późniejsze dodanie V104 skończy się błędem walidacji „resolved migration not applied”. Zarezerwować należy nowy numer > aktualny max, nie lukę.
- **Atomowość/lock przy auto-aplikacji:** każda migracja to osobna transakcja Flyway (PG); `SET LOCAL lock_timeout = '10s'` w V128–V132 działa (jest w transakcji migracji). Przy porażce V130 po zatwierdzeniu V129 aplikacja nie wstaje, a następny restart kontynuuje od V130 — bezpieczne, ale „awaria = brak startu” przy zajętej tabeli dłużej niż 10 s jest realnym trybem (ACCESS EXCLUSIVE na `scheduled_callback`/`agent_group_member` w czasie pracy dialera). Rekomendacja operacyjna już jest w nagłówkach („poza szczytem”) — przy zwykłym `docker restart` nikt jej nie egzekwuje.
- **V132 guard** jest rzetelny (zależności `pg_depend` typu `n` z wyłączeniem samozależności, regex po widokach/funkcjach `f`+`p`, zależności od funkcji, `cron.job`), brak `CASCADE` — dobra praktyka „głośnej awarii”.

### 🔧 Improvements & Suggestions

- **DB-R3 (`V128:236-244`)** — dla przyszłych szablonów „DROP COLUMN z guardem”: `p.prokind IN ('f','p')`, wyjątek węższy niż podciąg (np. wymagać, by każde wystąpienie `remote_address` było w gałęzi `IF v_has_…`) albo po prostu jawna lista dozwolonych funkcji (`anonymize_customer`). Dziś, na faktycznym katalogu, guard daje poprawny wynik.
- **DB-R4 (`V127:174`)** — dla większych instalacji PG-DW: batchowany `UPDATE … WHERE ctid IN (SELECT … LIMIT n)` w pętli (każda partia osobna transakcja niemożliwa w `DO`, więc job poza Flyway) albo `SET LOCAL lock_timeout/statement_timeout`; w nagłówku V128 dopisać „ODWRACALNOŚĆ: tylko `ADD COLUMN remote_address VARCHAR NULL` (dane utracone z założenia); fizyczne usunięcie PII z martwych krotek wymaga VACUUM, a z WAL/backupów — rotacji”.
- **DB-R5/R9** — dodać test: (1) `app_user` bez GUC i z `''` na NIEPUSTEJ tabeli archiwum (rozstrzyga „cichy 0” vs `22P02` i pozwala poprawić nagłówek V126 w kolejnej migracji komentarza); (2) dwa połączenia z otwartą transakcją `SELECT … FOR UPDATE` na części wierszy i weryfikacja rozłącznych partii (`SKIP LOCKED` jest dziś tylko deklarowany); (3) `p_cutoff_date NULL` → 0.
- **DB-R7/R8** — w testach: `@TestInstance(PER_CLASS)` + jawna zależność kroków w jednym `@Test` (lub `@Nested` z `@BeforeAll`), ścieżkę źródeł Java wyznaczać z `ClassLoader`/`user.dir` z fallbackiem; w EXPLAIN zamiast `doesNotContain("Seq Scan")` asercja na konkretny węzeł (`Index Scan|Index Only Scan|Bitmap Index Scan using <idx>`); dodać guard-test `idx_cca_tenant_customer`.
- **DB-R6** — poprawić zdanie w `06-database.md` (sekcja `scheduled_job`): „last_run_at aktualizują funkcje SQL: `create_next_month_partitions`, `archive_completed_campaign_contacts`, a w trybie backstopu także `cleanup_expired_refresh_tokens` i `rotate_*`”.
- **Dokumentacja vs fakty — sprawdzone i ZGODNE:** crony `@Scheduled` (grep): `PartitionMaintenanceJob 0 30 0 * * *`, `RetentionEvaluationJob 0 0 1 * * *`, `RecordingRetentionJob 0 0 2 * * *`, `PendingAttachmentSweepJob 0 30 2 * * *` (dry-run domyślnie: `…delete-enabled:false`), `RefreshTokenCleanupJob 0 30 3 * * *`, `PartitionReclaimJob 0 0 3 * * SUN`, `CampaignArchiveJob 0 0 4 * * *`, wszystkie `zone = "UTC"`; nazwy właściwości (`retention.evaluation-cron`, `retention.partition-maintenance-cron`, `auth.refresh-token-cleanup.cron`, `retention.campaign-archive-cron`, `retention.platform.audit-log-months:24`, `purge-batch-size`, `purge-max-batches`) istnieją w `application.yml`; „8 tabel” w `create_next_month_partitions` zgadza się z ciałem funkcji (live); bufor 3 miesięcy (`MONTHS_AHEAD = 3`); `campaign_contact` ma wyłącznie `campaign_contact_default` (live `pg_inherits`); partia purge w osobnej transakcji (`PROPAGATION_REQUIRES_NEW`); dialer `ORDER BY created_at ASC … FOR UPDATE SKIP LOCKED` (`ProgressiveDialerServiceImpl:428-430`); adnotacje w `CR-DATABASE.md` o zamknięciu przez V132 prawdziwe (widoków brak na żywej bazie). `scheduled_job` ma na żywej bazie 10 wierszy (4 aktywne z wykonawcą Java + 6 `rotate_*` nieaktywnych) — zgodnie z tabelą w V133.
- Dokumentacja poza zakresem edycji, ale nieaktualna: `TASKS-DATABASE.md` DB-057 („V129–V131 niezastosowane”), `DESIGN…md:171` („V094/V095/V096/V097 niezastosowane na żywej bazie”) — patrz DB-R1; `RetentionPurgeServiceImpl.java:478` (Javadoc) nadal wskazuje 2-argumentową sygnaturę V091 (kod Java — do drugiego recenzenta).

### ✅ Positive Observations

- **V126:** poprawny wzorzec „SELECT … ORDER BY … LIMIT … FOR UPDATE SKIP LOCKED” w CTE + `DELETE … USING` po pełnym PK (CTE z `FOR UPDATE` nie jest inline’owane — brak podwójnej ewaluacji); kolejność zgodna z `idx_cca_tenant_archived_at` (test EXPLAIN w Db057 to potwierdza dla dokładnie tego kształtu zapytania); wpis `cron_log` tylko dla wyniku > 0 w tej samej transakcji; końcowy guard `pg_proc` łapie pozostawione przeciążenie; nagłówek uczciwie opisuje zmianę semantyki wartości zwracanej i zależność od pętli BE-121.
- **V127+V128 jako para** (dane przed DDL, sweep idempotentny, drop z guardem, `DROP COLUMN IF EXISTS`), oraz test, który dowodzi także zachowania `PostgresDwWriter#upsert` przed i po DROP oraz `anonymize_customer` po DROP (guard istnienia kolumny, V096/V098 — zweryfikowane na żywej funkcji: `v_has_contacts_dw_remote_address`, UPDATE w gałęzi `IF`).
- **V129/V130:** audyt strukturalny po `pg_index` (indkey/indoption/indclass/indcollation/predykat/AM), nie po nazwie; guard sprawdza istnienie zwycięzcy i identyczność sygnatur; wybór zwycięzców poprawny — V129: duplikat identyczny, zostaje nazwa zgodna z konwencją tabeli (8/11); V130: zostaje `idx_agent_group_member_lookup` (nazwa zgodna z tabelą, V044 pierwszy), wprowadzająca w błąd nazwa `idx_campaign_agent_member_lookup` znika. Grep wszystkich nazw (cały repo, testy, dokumentacja): poza migracjami, `CR-DATABASE.md` (stare recenzje), `TASKS/PROGRESS` i dokumentacją — zero referencji; `06-database.md` poprawiony na zwycięzcę.
- **V131 warunki (a)(b)(c):** niezależnie potwierdzone — 5 funkcji w `pg_proc` dotyka archiwum (`anonymize_customer`, `archive_completed_campaign_contacts`, `export_customer_data`, `fn_customer_subject_ids`, `purge_campaign_contact_archive`); żadna nie filtruje po samym `campaign_id`/`archived_at` (archiwizacja: `INSERT … ON CONFLICT` po PK; eksport/anonimizacja: `tenant_id` + `customer_id`/`last_contact_id`/`record_id`/znormalizowany telefon, obsłużone przez `idx_cca_tenant_customer`/PK/`idx_cca_tenant_archived_at`); Java: tylko `CampaignArchiveRetentionRepository` (count/min/max po `tenant_id`+`archived_at`) i `CampaignArchiveJob`; DB-075/BE-140 potrzebują `(tenant_id, campaign_ended_at)`, nie usuwanych indeksów; indeksy nie wspierają FK. Na żywej bazie po V131 zostają dokładnie `pk_campaign_contact_archive`, `idx_cca_tenant_customer`, `idx_cca_tenant_archived_at`. `idx_scan = 0` na pustej tabeli słusznie NIE użyte jako dowód.
- **V132:** wielowarstwowy guard (zależności, tekstowe odwołania w widokach i funkcjach, zależności od funkcji, `pg_cron`), brak `CASCADE`, pełny skrypt odtworzenia w nagłówku; test Db058 sprawdza usunięcie dokładnie 2 widoków + 4 indeksów + funkcji i nietkniętą resztę katalogu (relacje/indeksy/funkcje), 4 scenariusze guardu na klonach bazy.
- **V133:** crony i klasy wykonawców zgodne z kodem (test 8 parsuje `@Scheduled` ze źródeł — dobry pomysł, strażnik dryfu); definicje funkcji niezmienione (test porównuje `pg_get_functiondef`), funkcje nadal aktualizują `last_run_at` swojego wiersza (test 5) — UPDATE nie psuje tego mechanizmu (brak triggerów na `scheduled_job`); wiersze `create_next_month_partitions`/`archive_completed_campaign_contacts` zachowane (konieczne, bo funkcje robią `UPDATE … WHERE job_name`); brak wiszących `pg_function` po usunięciu `refresh_materialized_views` (test 3); `COMMENT ON FUNCTION` dla wszystkich 11 sygnatur istnieje na żywej bazie (migracja przeszła).

### Summary

**Ocena: 4/5 ⭐ — zatwierdzić; brak blokerów i brak błędów SQL.** Migracje są poprawne, dobrze zabezpieczone (guardy, `lock_timeout`, idempotencja, zgodność z regułą „nie edytuj zastosowanych”) i zostały już bezawaryjnie zastosowane na żywej bazie (V133); do poprawy zostają trwały mylący komentarz indeksu w V129 (DB-R2, wymaga nowej migracji komentarza), nieaktualne twierdzenia o stanie żywej bazy w dokumentach (DB-R1) oraz drobne luki w testach (kruchość `@Order`, słabe asercje EXPLAIN, brak testu `SKIP LOCKED` i braku GUC).

---

## Review: V136–V139 + Db084CampaignArchivePerTenantMigrationsTest (DB-084, BE-151, EPIC-30) — 2026-10-17

**Zakres:** `V136__create_tenant_campaign_archive_setting.sql`, `V137__add_campaign_ended_at.sql`, `V138__archive_completed_campaign_contacts_per_tenant_threshold.sql`, `V139__scheduled_job_archive_description_per_tenant.sql`, `Db084CampaignArchivePerTenantMigrationsTest` (16 testów). Weryfikacja na żywej bazie `contact_center` (read-only): polityka RLS, ACL, triggery, `prosecdef/proconfig`, role. Żadna zastosowana migracja nie jest edytowana.

### Tabela ustaleń

| ID | Waga | Gdzie | Skrót |
|----|------|-------|-------|
| DB84-R1 | ważne (latentne, kierunek: utrata danych) | `V138` LEFT JOIN na tabeli z FORCE RLS; brak testu | Pod rolą bez BYPASSRLS (cc_app/DB-083 bez definera) brak GUC → LEFT JOIN widzi 0 wierszy → KAŻDY tenant dostaje 180 dni, także ten z ustawieniem 730 → archiwizacja WCZEŚNIEJ niż skonfigurowano; z GUC `''` → 22P02. Dziś niewidoczne: `ccapp` = SUPERUSER+BYPASSRLS |
| DB84-R2 | ważne | `V137` trigger | Luka INSERT: kampania wstawiona od razu jako COMPLETED/STOPPED ma `ended_at` NULL na zawsze; pierwszy `UPDATE` (np. `setAllAgents`) restartuje `updated_at` = dokładnie ten problem, który V137 miał usunąć |
| DB84-R3 | ważne (test) | `function_contract` | Test twardo wymusza `prosecdef=false`/`proconfig NULL` → padnie przy DB-083; brak odwrotnego strażnika; kryterium „testy krzyżowe z DB-083” otwarte |
| DB84-R4 | drobne→ważne (odziedziczone z V015) | `V138` DELETE | Warunek `v_batch_count > 0 OR NOT EXISTS` nie robi tego, co mówi komentarz; DELETE kasuje też wiersze dopisane po INSERT (READ COMMITTED) |
| DB84-R5 | drobne | `V136` | Odstępstwa od niezmienników: brak `is_deleted`, brak trigger'a `updated_at`, `updated_by` bez spójności tenanta, brak jawnego GRANT |
| DB84-R6 | drobne | `V137` | Backfill: `DISABLE/ENABLE TRIGGER` bezpieczne przy błędzie, ale `ENABLE` przywraca zawsze 'O' bez odczytu poprzedniego stanu; wiersze z `updated_at IS NULL`/oba NULL → nigdy nie archiwizowane (`updated_at` nullable — patrz niżej) |
| DB84-R7 | drobne | `V138` | Martwe kolumny w SELECT pętli (`status`, `updated_at`, `tenant_id`), brak ponownego sprawdzenia/blokady kampanii w pętli, jedna transakcja na wszystkie tenanty |
| DB84-R8 | drobne (testy) | test | Słabe asercje (`lastRowsAffected >= 2`, kontrakt przez `contains` na `prosrc`), brak testów: NOBYPASSRLS, GUC `''`, COMPLETED→STOPPED, INSERT-terminal + UPDATE, DROP partycji, granica dokładnie N/N+1 dla ustawionego progu |

### 🐛 Bugs / Critical Issues

_Brak błędów krytycznych._ SQL jest poprawny, migracje przeszły na żywej bazie (V136–V139 zastosowane 2026-10-11 17:44 UTC, `ended_at` 30/30, 0 terminalnych z NULL), ciało funkcji V138 różni się od V015 wyłącznie zamierzonym predykatem, `JOIN` i komunikatem `cron_log`.

- **DB84-R2 (ważne) — `V137:42-63`, luka INSERT w triggerze `fn_campaign_set_ended_at`.** Trigger to `BEFORE UPDATE OF status` z warunkiem na PRZEJŚCIE `OLD→NEW`. Kampania wstawiona od razu jako `COMPLETED/STOPPED` (seedy, importy, testy — komentarz V137 to uznaje) ma `ended_at = NULL`, a każde późniejsze `UPDATE` (nawet `status` terminal→terminal, np. COMPLETED→STOPPED, też nie ustawi) zostawia NULL. Konsekwencja: funkcja używa `updated_at`, który restartuje trigger `trg_campaign_updated_at` przy KAŻDYM UPDATE (PUT przypisania agentów) — czyli stary błąd „zegar restartuje edycja” wraca dla całej klasy wierszy, a „kierunek błędu” to dłuższe trzymanie danych (zgodne z R3, ale to nieograniczone w czasie). Dziś nie ma ścieżki Javy tworzącej kampanię jako terminalną (potwierdzone w WP-1), więc ryzyko jest niskie; ale nic w bazie tego nie pilnuje. Poprawka (nowa migracja V14x, bez edycji V137): `BEFORE INSERT OR UPDATE` z gałęzią `IF NEW.status IN (terminal) AND NEW.ended_at IS NULL THEN NEW.ended_at := NOW()` (ta sama gałąź łapie INSERT i UPDATE terminal→terminal z NULL) — NIE niszczy backfillu i seedów z jawnie podanym `ended_at` (jawna wartość jest respektowana). Alternatywa minimalna: `CHECK (status NOT IN (...) OR ended_at IS NOT NULL) NOT VALID` — gorsza (łamie seedy).
- **DB84-R4 (odziedziczone z V015, drobne→ważne) — `V138:81-88`.** Komentarz „usuwamy tylko gdy wszystkie rekordy skopiowano” nie odpowiada warunkowi: (a) gdy `ON CONFLICT DO NOTHING` zwróci 0 (wszystkie rekordy już w archiwum po wcześniejszym częściowym przebiegu/przywróceniu), a w `campaign_contact` nadal są wiersze, `NOT EXISTS` jest FALSE → brak DELETE, wiersze zostają na zawsze (kampania jest co noc „przetwarzana”, licznik `v_campaign_count` rośnie); (b) `DELETE … WHERE campaign_id` kasuje także wiersze, które pojawiły się między INSERT a DELETE (READ COMMITTED widzi nowo zatwierdzone) — nigdy nie zarchiwizowane. Poprawka: `DELETE FROM campaign_contact cc USING campaign_contact_archive a WHERE cc.campaign_id = … AND a.record_id = cc.record_id AND a.campaign_id = cc.campaign_id` (kasuje wyłącznie to, co jest w archiwum; obsługuje (a) i (b)). Poza zakresem DB-084 (V015) — do osobnego ticketu, ale to jedyne miejsce, gdzie funkcja może bezpowrotnie utracić dane.

### ⚠️ Security Concerns

- **DB84-R1 (ważne) — RLS + LEFT JOIN w funkcji bez definera.** Polityka `tenant_campaign_archive_setting_isolation` (żywa baza): `USING` i `WITH CHECK` = `tenant_id = current_setting('app.current_tenant_id', true)::uuid`, FORCE RLS = t, `ALL`, właściciel `ccapp` — poprawne i zgodne z V082/V125. Ale funkcja czyta tę tabelę przez `LEFT JOIN … ON s.tenant_id = c.tenant_id` bez GUC i polega na BYPASSRLS wywołującego/właściciela. Role na żywej bazie: `ccapp` SUPER+BYPASSRLS, `admin_user` BYPASSRLS, `app_user` NOBYPASSRLS. Scenariusze pod rolą bez BYPASSRLS i bez SECURITY DEFINER (np. `cc_app` z DB-083, gdyby `ALTER FUNCTION … SECURITY DEFINER` zostało pominięte albo ktoś zrobi kolejny `CREATE OR REPLACE`, który — jak V138 sam zaznacza — ZERUJE SECURITY DEFINER i `search_path`): (1) GUC nieustawiony → `current_setting(..., true)` = NULL → polityka daje 0 wierszy → wszystkie tenanty „bez wiersza” → próg 180 — **cicho**, a tenant z 730 dniami traci dane 550 dni wcześniej niż skonfigurował (to kierunek utraty, w przeciwieństwie do zapewnień z nagłówka V137 o „późniejszej archiwizacji”); (2) GUC ustawiony na jednego tenanta → tylko jego ustawienie widoczne, reszta = 180; (3) GUC `''` (po powrocie połączenia z puli po `set_config(…, true)`) → `''::uuid` → 22P02 i cały przebieg pada, a przez `RAISE` rollback unicestwia też wpis błędu (DB-082). Ten sam wzorzec `''::uuid` dotyczy wszystkich tabel RLS repo (nie ma `NULLIF`); nie zmieniać jednostronnie, ale funkcja nocna nie powinna od niego zależeć. Zalecenia: (a) przy DB-083 `ALTER FUNCTION … SECURITY DEFINER SET search_path = public, pg_temp` z właścicielem BYPASSRLS — i test, który to egzekwuje; (b) dodać do funkcji twardy guard na początku (`IF NOT (SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname = current_user) THEN RAISE EXCEPTION …` — w definerze `current_user` = właściciel) — wtedy cicha zmiana progu staje się głośną awarią; (c) test pod `SET ROLE app_user` wywołujący funkcję i pokazujący zachowanie (dziś nieudokumentowane).
- **GRANT/ACL** — żywa baza: `relacl = {ccapp=arwdDxt, app_user=arwd, admin_user=arwdDxt}` — zgodne z `pg_default_acl` (jawny GRANT zbędny, `has_table_privilege` testowany). Brak `TRUNCATE/REFERENCES/TRIGGER` dla `app_user` — dobrze. Uwaga operacyjna: grant zależy od tego, że migrację uruchamia ta sama rola, która ma `ALTER DEFAULT PRIVILEGES` (`ccapp`). Przy innym użytkowniku migracji (prod) `app_user` dostanie `permission denied` dopiero w runtime — idempotentny jawny `GRANT SELECT, INSERT, UPDATE, DELETE … TO app_user` kosztuje 1 linię i usuwa to założenie (DB84-R5).
- `SET LOCAL lock_timeout='10s'` jest w V136, V137, V139; **brak w V138** (`CREATE OR REPLACE FUNCTION` bierze krótki ACCESS... lock na `pg_proc` wiersz, praktycznie bez ryzyka — nit). Wszystkie migracje to jedna transakcja Flyway (PG, DDL transakcyjne) — zgodnie z oczekiwaniem.

### 🏗️ Architecture / Pattern Violations

- **DB84-R5 (drobne) — `V136`.** Niezmienniki projektu („każda tabela: `tenant_id NOT NULL`, composite `(tenant_id, pk)`, `is_deleted`, `created_at`, `updated_at`”): `tenant_id` jest PK (composite index zbędny — poprawnie), ale brak `is_deleted` (hard delete ustawienia 1:1 jest tu uzasadniony, brak wiersza = domyślne 180 — warto zapisać w komentarzu jako świadomy wyjątek), brak triggera `fn_set_updated_at` (aktualizuje je tylko natywny SQL repozytorium `updated_at = NOW()` — ręczny `UPDATE` w SQL/psql nie odnotuje zmiany; spójnie z resztą repo `trg_*_updated_at` powinien istnieć), `updated_by` → `app_user` bez egzekwowania zgodności tenanta (FK nie pilnuje, że użytkownik jest z tego samego tenanta; SUPER_ADMIN zmienia ustawienia innych tenantów, więc composite FK byłoby błędne — wystarczy zostawić, ale odnotować). `ON DELETE CASCADE` z `tenant` — tenant jest soft-delete, więc kaskada praktycznie nie zadziała; poprawne.
- **CHECK 30..730** — `BETWEEN 30 AND 730` + `NOT NULL` + nazwany constraint; test pokrywa 29/731/NULL/30/180/730. Zgodne z kontraktem.
- **Idempotencja/kolejność** — V136 `CREATE TABLE IF NOT EXISTS` (ale `IF NOT EXISTS` przy istniejącej tabeli o innym kształcie przejdzie cicho), `DROP POLICY IF EXISTS` + `CREATE POLICY`, V137 `ADD COLUMN IF NOT EXISTS`, `CREATE OR REPLACE FUNCTION`, `DROP TRIGGER IF EXISTS` + `CREATE TRIGGER`, backfill z `AND ended_at IS NULL`, V138 `CREATE OR REPLACE`. Kolejność poprawna: tabela → kolumna+trigger+backfill → funkcja (V138 zależy od obu). V139 idempotentny (`LIKE '%> 30 dni%'` + `REPLACE`; po zmianie fragmentu nie ma już dopasowania). Uwaga do V139: `WHERE description LIKE '%> 30 dni%'` jest szerszy niż `REPLACE` na `'(COMPLETED/STOPPED, > 30 dni)'` — jeśli opis zawiera „> 30 dni” w innym miejscu a nie w dokładnym frazie, UPDATE odświeży `updated_at` (trigger) bez zmiany treści; na żywej bazie dopasowanie jest dokładne, więc skutek zerowy.
- **V137 backfill — bezpieczeństwo.** `ALTER TABLE campaign DISABLE TRIGGER trg_campaign_updated_at` i `ENABLE` są w tej samej transakcji migracji → przy błędzie UPDATE całość (łącznie ze stanem triggera) jest wycofywana; nie ma okna „wyłączony na stałe”. Blokady: `ADD COLUMN` (ACCESS EXCLUSIVE, szybkie — brak DEFAULT) trzymane do COMMIT migracji, `DISABLE TRIGGER` SHARE ROW EXCLUSIVE; UPDATE tylko wierszy terminalnych na małej tabeli `campaign` (rzędu tysięcy) → pomijalne; przy bardzo dużej tabeli blokuje zapisy dialera na czas backfillu, ale nie czytelników. Trigger `trg_campaign_ended_at` (`BEFORE UPDATE OF status`) nie odpala w backfillu (kolumna `status` nie jest w SET) — poprawnie. Drobne: `ENABLE` bez zapamiętania poprzedniego `tgenabled` (dziś 'O', żywa baza po migracji: `trg_campaign_updated_at|O`, `trg_campaign_ended_at|O`); `campaign.updated_at` jest NULLABLE na żywej bazie (`attnotnull = f`; `status` NOT NULL), więc `AND updated_at IS NOT NULL` w backfillu ma sens, ale wiersz terminalny z `updated_at` NULL zostaje z `ended_at` NULL, a wtedy `COALESCE(ended_at, updated_at) < …` = NULL i kampania NIGDY nie zostanie zarchiwizowana (cicho, bez wpisu w logu). Dziś 0 takich wierszy (`ended_at IS NULL` wśród terminalnych = 0); w funkcji można dodać trzeci argument `COALESCE(ended_at, updated_at, created_at)`.
- **Wydajność V138.** `COALESCE(c.ended_at, c.updated_at) < NOW() - make_interval(days => COALESCE(s.archive_after_days,180))` zależy od kolumny `s` (po JOIN) — niesargowalne, planner zrobi Seq Scan `campaign` (+ hash join po 1-wierszowych ustawieniach). Indeks `campaign(status, ended_at)` NIE pomoże (COALESCE na dwóch kolumnach, próg zmienny per wiersz) — nie dodawać; istniejące `idx_campaign_tenant_status (tenant_id, status)` nie ma jak być użyte bez filtra tenanta. Tabela `campaign` jest mała (jeden wiersz = kampania, dziesiątki–tysiące), nocny job → OK. Ewentualna optymalizacja przy >100 tys. kampanii: filtr `c.status IN (...)` + indeks częściowy `ON campaign (tenant_id) WHERE status IN ('COMPLETED','STOPPED')` (predykat IMMUTABLE), ale dziś bez wartości. Pętla `FOR … IN SELECT` jest jednym zapytaniem (snapshot na starcie), więc kampania ponownie otwarta w trakcie przebiegu nadal zostanie zarchiwizowana — kampanie terminalne w kodzie nie są wznawiane (WP-1), ryzyko nikłe; ewentualnie `FOR UPDATE OF c SKIP LOCKED`.

### 🔧 Improvements & Suggestions

- **COALESCE(ended_at, updated_at) a ponowne otwarcie (pytanie z zlecenia).** Poprawne: wyjście z terminalnego zeruje `ended_at` (test `endedAt_notResetByLaterUpdates`), a kampania nieterminalna i tak odpada na `status IN`. Ponowne zakończenie ustawia nowe `NOW()`. Jedyny przypadek stałego `NULL` to DB84-R2 (INSERT terminal / terminal→terminal bez wartości).
- **V138 — odpowiedzi na punkty ze zlecenia:** `ON CONFLICT (record_id, campaign_id) DO NOTHING` bez zmian; DROP partycji (nazwa z UUID, `pg_tables` w `public`, `DROP TABLE IF EXISTS %I`) bez zmian względem V015 — nadal bez `lock_timeout` (DROP partycji bierze ACCESS EXCLUSIVE także na rodzicu `campaign_contact`; dziś brak dedykowanych partycji, więc martwe; przy ich pojawieniu się `SET LOCAL lock_timeout` w funkcji); `cron_log` + `UPDATE scheduled_job` bez zmian, komunikat `format(...)` bezpieczny (`min/max` z `'-'` przy 0 kampanii); blok `EXCEPTION WHEN OTHERS … RAISE` zachowany, zgodnie z DB-082 trwały ślad zapisuje wołający przez `log_cron_failure`. `v_campaign.status/updated_at/tenant_id` w SELECT pętli nieużywane (usunąć przy okazji DB-083). `make_interval(days => int)` — CHECK 30..730 wyklucza przepełnienie.
- **Komentarz V137** twierdzi „kierunek błędu: pózniejsza archiwizacja” — prawda dla backfillu, nieprawda dla DB84-R1 (zaniżony próg przy braku widoczności ustawienia). Poprawić nagłówek przy okazji nowej migracji komentarza.
- **Dokumentacja:** ticket DB-084 ma otwarte pole „testy krzyżowe z DB-083” i „code review migracji” — to review zamyka drugie; pierwsze pozostaje.

### Jakość testów (`Db084…Test`)

- **Mocne strony:** łańcuch Flyway krokowy (`target(before)` → dane sprzed → V136 → V137 → V138) dowodzi backfillu na danych sprzed migracji (`ended_at = updated_at`, `updated_at` nietknięte, RUNNING NULL); izolacja pod `SET ROLE app_user` z oczekiwanym SQLSTATE 42501 dla INSERT cudzego tenanta (dowodzi `WITH CHECK`), UPDATE/DELETE cudzego = 0 wierszy; test spójności `DEFAULT` z `pg_attrdef` ↔ próg funkcji (granica N−1/N+1); parytet progów w jednym przebiegu 3 tenantów; `COALESCE` z 3 kombinacjami; `tgenabled` triggera updated_at po backfillu; wyniki sprawdzane na realnym ruchu danych (live/archived), nie tylko na `prosrc`.
- **Luki (DB84-R3/R8):** (1) brak testu funkcji pod rolą NOBYPASSRLS — dokładnie tam, gdzie leży ryzyko DB84-R1; (2) `function_contract` asercjami `contains("RAISE;")`, `contains("COALESCE(c.ended_at, c.updated_at)")` na `prosrc` — kruche względem formatowania i bezwartościowe jako dowód zachowania; jednocześnie twarde `prosecdef=false`/`proconfig IS NULL` zepsuje się przy DB-083 (powinno być `isIn(false,true)` + gdy `true`, wymagane `search_path` w `proconfig`); (3) brak scenariuszy: COMPLETED→STOPPED zachowuje `ended_at`, INSERT terminal + późniejszy `UPDATE` (DB84-R2), `ended_at` nadpisany jawnie w tym samym UPDATE co przejście, `UPDATE campaign SET status=status` (bez zmiany) przy NULL; (4) `lastRowsAffected() >= 2` w teście idempotencji — przebieg globalny, więc dokładna wartość niedeterministyczna; lepiej sumować `count` po kampaniach testu; (5) brak testu `DROP` dedykowanej partycji (gałąź odziedziczona, nigdy niewykonywana) i ścieżki błędu funkcji (ticket oznacza jako [x] — pokrywa to Db082, nie ten plik; sprawdzić); (6) test `table_defaultAndKeys` w `@DisplayName` wspomina „brak ON DELETE CASCADE problemu” — nazwa myląca; (7) wszystkie testy dzielą jedną bazę i globalny `runArchive()`, co jest akceptowalne (kampanie testowe różne), ale kolejność-niezależność trzyma tylko dlatego, że każdy test tworzy własnych tenantów — dobre, nie psuć.

### ✅ Positive Observations

- Zgodność z regułą „nie edytuj zastosowanych migracji”; podział „jedna zmiana = jedna migracja” (tabela / kolumna+trigger / funkcja), V139 jako osobna korekta opisu, nie edycja V133/V135.
- Polityka RLS: `USING` + `WITH CHECK` + `FORCE`, właściwy GUC `app.current_tenant_id` (nie `app.tenant_id` z V069), `current_setting(..., TRUE)`; `COMMENT ON` z JEDNOSTKĄ (dni vs `retention_months`) — wprost realizuje zakaz „overloaded columns” z CLAUDE.md.
- Model „brak wiersza = 180” bez backfillu wierszy + test spójności DDL↔funkcja — eliminuje klasę błędów „tenant bez ustawienia” i przy okazji jest strażnikiem 3 miejsc, w których żyje 180.
- Funkcja V138 zachowuje sygnaturę, `RETURNS void`, idempotencję, kontrakt `RAISE`; komunikat `cron_log` rozszerzony bez zmiany `rows_affected`; nagłówek V138 uczciwie opisuje reset SECURITY DEFINER przez `CREATE OR REPLACE` i wskazuje `ALTER FUNCTION` dla DB-083.
- Backfill bez zmiany `updated_at` (DISABLE/ENABLE w jednej transakcji) i test, który to dowodzi na danych sprzed migracji.

### Rekomendacje (kolejność)

1. Nowa migracja (np. V14x): trigger `BEFORE INSERT OR UPDATE` z gałęzią `terminal AND ended_at IS NULL → NOW()` (DB84-R2) oraz test INSERT-terminal / COMPLETED→STOPPED.
2. Przy DB-083: `ALTER FUNCTION … SECURITY DEFINER SET search_path…` + guard `rolbypassrls` w funkcji (albo test pod NOBYPASSRLS), przepisać `function_contract` na tolerujący oba stany i egzekwujący definera po DB-083 (DB84-R1/R3).
3. Osobny ticket na `DELETE … USING campaign_contact_archive` (DB84-R4).
4. Przy okazji: trigger `fn_set_updated_at` i jawny GRANT na `tenant_campaign_archive_setting`, sprzątnięcie nieużywanych kolumn w pętli, komentarz o wyjątku od `is_deleted`.

### Summary

**Ocena: 4/5 ⭐ — zatwierdzić; brak blokerów, SQL poprawny i zastosowany na żywej bazie.** Migracje są czyste, idempotentne i dobrze przetestowane na realnym łańcuchu Flyway, ale (a) funkcja po cichu opiera się na BYPASSRLS (zaniżony próg zamiast awarii pod zwykłą rolą), (b) trigger `ended_at` nie obejmuje INSERT i przejścia terminal→terminal, (c) test kontraktu zablokuje DB-083, a odziedziczony warunek DELETE z V015 pozostaje jedyną ścieżką realnej utraty danych.

---

## Review: V140, V141, Db085Db086CampaignArchiveHardeningMigrationsTest + seedy w CampaignArchiveJob/CampaignArchiveSettingIntegrationTest, Db082LogCronFailureMigrationTest (DB-085, DB-086) — 2026-10-17

Zakres: niezacommitowane zmiany (V140, V141 — nieuzyte na żywej bazie, `flyway_schema_history` kończy się na V139; V136–V139 niezmienione w `git status`). Zweryfikowano wykonaniem: `mvn test -pl app` dla Db085Db086…, Db082…, Db084…, Db076…, CampaignArchiveJobIntegrationTest, CampaignArchiveSettingIntegrationTest, CampaignArchiveJobTest — wszystko zielone (EXIT 0). Baza live (read-only): 0 kampanii terminalnych z `ended_at IS NULL` (30 szt.), archiwum puste, więc brak potrzeby backfillu i brak ryzyka dla V140/V141 na danych demo.

### 🐛 Bugs / Critical Issues

- **DB85-R1 (Major) — V141 zamyka tylko połowę ścieżki DB84-R4: dopisanie wiersza, ale NIE jego modyfikację w oknie INSERT→DELETE.** `DELETE … USING campaign_contact_archive` po `(record_id, campaign_id)` usuwa żywy wiersz, jeśli w archiwum jest kopia o tym samym kluczu — także gdy kopia jest starsza niż stan żywego wiersza (UPDATE `attempt_count`/`status`/`disposition_code` między `INSERT … SELECT` a `DELETE` w READ COMMITTED; albo kopia z wcześniejszego, częściowego przebiegu). `ON CONFLICT DO NOTHING` nie nadpisuje, więc zmiana ginie bez śladu. Nagłówek V141 opisuje to jako „zastrzeżenie, jak w V015", ale ticket DB-086 i komentarz `COMMENT ON FUNCTION` przedstawiają zmianę jako domknięcie „jedynej realnej ścieżki utraty danych". Co gorsza, V141 *rozszerza* zasięg: V138/V140 w przypadku `v_batch_count = 0` w ogóle nie kasowały (żywy wiersz zostawał), V141 kasuje je na podstawie potencjalnie nieaktualnej kopii. Okno jest wąskie (kampania ≥30 dni po zakończeniu; ale dosłany disposition po `STOPPED`/ponowne otwarcie są realne). Poprawka (tania): w `INSERT … SELECT` dodać `FOR UPDATE` na `campaign_contact` (blokuje równoległe UPDATE-y do końca transakcji; spójne z `SKIP LOCKED` dialera), albo dodać do `DELETE` warunek równości stanu (`cc.updated_at IS NOT DISTINCT FROM a.updated_at AND cc.status = a.status AND cc.attempt_count = a.attempt_count`) + `ON CONFLICT DO UPDATE … WHERE` dla rozjechanych wierszy (uwaga na `archived_at` — zegar retencji CAMPAIGN_DATA). Minimum przed commitem: albo poprawka, albo jawna decyzja właściciela „akceptujemy" zapisana w tickecie + test dokumentujący zachowanie, i korekta sformułowania „zamyka ścieżkę utraty danych" w tickecie/COMMENT.
- **DB85-R2 (Minor, ścieżka dziś martwa) — `DROP TABLE` dedykowanej partycji po `DELETE` nadal kasuje niezarchiwizowane wiersze.** Jeśli istnieje partycja `campaign_contact_<uuid>`, to wiersz dopisany po `INSERT … SELECT` zostaje (dzięki V141) w partycji, ale `EXECUTE format('DROP TABLE …')` w tej samej iteracji usuwa go razem z partycją — czyli ta sama klasa utraty danych, której V141 miał zapobiec, pozostaje w gałęzi DROP. Dziś żadna partycja nie istnieje (ADR DB-070), stąd Minor. Poprawka: `DROP` warunkowo, tylko gdy `NOT EXISTS (SELECT 1 FROM campaign_contact WHERE campaign_id = v_campaign.campaign_id)` po `DELETE`.

### ⚠️ Security Concerns

- _None identified._ `GRANT` jawny, minimalny (SELECT/INSERT/UPDATE/DELETE, test potwierdza brak TRUNCATE/REFERENCES/TRIGGER); funkcje pozostają SECURITY INVOKER (bez eskalacji). Uwaga informacyjna (nie nowa): `DELETE … USING campaign_contact_archive` czyta archiwum pod FORCE RLS — pod `app_user` z GUC tenanta działa poprawnie (pętla i tak widzi tylko kampanie tego tenanta), bez GUC cały przebieg jest no-opem jak dotąd (DB84-R1/DB-083); testy działają na superuserze, więc tego nie dotykają (luka znana z DB84-R3).

### 🏗️ Architecture / Pattern Violations

- **DB85-R3 (Minor) — niedomknięcie specyfikacji triggera: `INSERT` nieterminalny z jawnym `ended_at` zostaje z niepustym `ended_at`.** Ticket: „INSERT nieterminalny → NULL"; trigger (gałąź INSERT) tylko uzupełnia NULL dla terminalnych, nie zeruje dla RUNNING/DRAFT/PAUSED. Spójność semantyczna (`ended_at` ≠ NULL ⇒ terminalny) jest egzekwowana w UPDATE (wyjście z terminalnego zeruje), ale nie w INSERT. Albo dodać `ELSIF … THEN NEW.ended_at := NULL` w INSERT, albo świadomie zostawić i zapisać w komentarzu/tickecie (seedy testowe). Test: brak.
- **DB85-R4 (Info) — semantyka „jawna wartość" w przejściu nieterminalny→terminalny to heurystyka.** `NEW.ended_at IS NOT DISTINCT FROM OLD.ended_at` → NOW(): jawne ustawienie w tym samym UPDATE wartości równej poprzedniej (praktycznie tylko NULL→NULL, bo wyjście zeruje) jest nieodróżnialne od braku. Praktycznie nieszkodliwe; uzasadnione w komentarzu — OK. Jawny `ended_at` przy przejściu terminal→nieterminalny jest cicho ignorowany (zawsze NULL) — zgodne z V137, warto jedno zdanie w COMMENT.
- Zgodność z regułami: V137–V139 niezmienione (nowe pliki V140/V141, brak edycji), `SET LOCAL lock_timeout = '10s'` w obu, `DROP TRIGGER IF EXISTS` + `CREATE TRIGGER` w jednej transakcji Flyway (brak luki bez triggera), `CREATE OR REPLACE FUNCTION` i `GRANT` idempotentne; `tenant_campaign_archive_setting` ma teraz trigger `updated_at` (`tgenabled='O'`, test), natywny SQL repozytorium z ręcznym `updated_at = NOW()` nie koliduje (test to dowodzi).

### 🔧 Improvements & Suggestions

- **DB85-R5 (Minor) — `cron_log.rows_affected`/komunikat nie pokazują liczby usuniętych.** Po V141 przebieg „sprzątający" (0 wstawionych, N usuniętych z `campaign_contact`) loguje `Rekordy: 0`, `rows_affected = 0` — a w tabeli operacyjnej zniknęło N wierszy; liczba trafia tylko do `RAISE NOTICE` (nieutrwalone). Decyzja „nie zmieniamy semantyki `rows_affected`" jest uzasadniona (Java czyta tylko to pole), ale można dopisać `Usunięte: %` do tekstu `message` (test sprawdza tylko `contains`). Obserwowalność operacji destrukcyjnej.
- **DB85-R6 (Minor) — duplikacja ~100 linii funkcji w V140 i V141.** Zamierzone (zmiana pojedynczych tickets, V140 już nie stanie się „aktualna"), ale każda kolejna zmiana (DB-083) będzie wymagała trzeciej kopii; zostawić, tylko pilnować `pg_get_functiondef` jako bazy (jest w nagłówku — dobrze).
- **DB85-R7 (Minor) — brak backfillu `ended_at` dla terminalnych kampanii z `ended_at IS NULL`** (wstawionych między V137 a V140). Na live zero takich wierszy, ale na innych środowiskach mogą istnieć (zegar spada na `updated_at`). Dla pewności: jednolinijkowy `UPDATE … SET ended_at = updated_at … WHERE terminal AND ended_at IS NULL` z `ALTER TABLE campaign DISABLE/ENABLE TRIGGER trg_campaign_updated_at` jak w V137, albo zapis w tickecie „nie dotyczy".
- Dokumentacja (DoD): `documentation/tech/06-database.md` i status w `TASKS-DATABASE.md` (DB-085/DB-086 nadal „⬜") do aktualizacji po zatwierdzeniu.

### Jakość testów

- **Test wyścigu (`delete_rowAppendedBetweenInsertAndDeleteSurvives`) — WIARYGODNY dla przypadku INSERT.** Trigger AFTER INSERT na archiwum dopisuje wiersz do `campaign_contact` *w trakcie* `INSERT … SELECT` (snapshot tego statementu go nie widzi), a następne polecenie `DELETE` (nowy snapshot READ COMMITTED) go widzi. Na V140 (`DELETE WHERE campaign_id`) wiersz „Late" zostałby skasowany (`live(c)` = 0, asercja `isEqualTo(1)` pada); na V141 przeżywa. Bez sleepów, deterministyczny, `finally` sprzątający trigger — dobre. **Luka (powiązana z DB85-R1):** brak testu dla *modyfikacji* wiersza w oknie (analogiczny trigger AFTER INSERT robiący UPDATE istniejącego wiersza) — test pokazałby, że zmiana ginie (albo, po poprawce, że nie).
- DB85-R8 (Minor) — `trigger_laterUpdatesDoNotMoveEndedAt` asertuje `updatedAt isAfterOrEqualTo(updatedBefore)` — zbyt słabo (kampania ma `updated_at` sprzed doby, test Db084 używa `isAfter`); zmienić na `isAfter`, inaczej regresja „trigger updated_at wyłączony" przejdzie. Display name `trigger_updateWithoutStatusDoesNotFire` („backfill V137 nietknięty") nie testuje backfillu — zmylić może przy czytaniu.
- DB85-R9 (Minor) — display name `delete_rowsAlreadyInArchiveAreRemoved` obiecuje „rows_affected = 0", a test nie czyta `cron_log`. Dodać asercję albo poprawić nazwę. `migrations_areIdempotent` sprawdza tylko brak błędu i jeden trigger; nie asertuje, że funkcja po dwukrotnym V140+V141 ma zachowanie V141 (kolejność V140→V141 zostawia właściwy stan, ale to przypadek kolejności) — dodać krótki sanity (np. powtórzyć scenariusz „już w archiwum").
- Pokrycie triggera dobre: INSERT terminalny/nieterminalny, jawny `ended_at`, terminal→terminal (NULL i ustawiony), jawny w przejściu, reopen→re-complete, UPDATE bez statusu, struktura `tgtype`/`tgenabled`, brak CHECK. Brakuje: terminal→nieterminalny z jawnym `ended_at`, INSERT RUNNING z jawnym `ended_at` (DB85-R3).
- Seedy w 3 zmienionych testach poprawne: parametry (`daysOld+1`, `daysOld`, `daysOld`) w odpowiedniej kolejności, `CASE … IN ('COMPLETED','STOPPED')` daje NULL dla nieterminalnych; komentarze opisują nową rolę triggera. Brak innych testów/seedów wstawiających kampanie terminalne zależne od NULL (grep).

### ✅ Positive Observations

- Trigger: czytelna maszyna stanów (INSERT / nieterminalny→terminalny / terminal→terminal / wyjście), zachowuje zachowanie V137, odporny na Hibernate (UPDATE z `status` bez zmiany nie rusza `ended_at`) — udowodnione testami.
- Funkcja archiwizacji: kontrakt (`RETURNS void`, brak parametrów, `EXCEPTION WHEN OTHERS … RAISE` zgodny z DB-082/`log_cron_failure`, `cron_log`, `scheduled_job`) zachowany bez zmian; `COALESCE(ended_at, updated_at, created_at)` naprawia R6; nagłówek uczciwie ostrzega o resecie `SECURITY DEFINER`.
- Test pinuje migrację po opisie (nie po numerze) i sprawdza zachowanie, nie `prosrc` — zgodnie z uwagą DB84-R8; `function_contract` tolerancyjny wobec DB-083.
- Dyscyplina migracji: nowe pliki, brak edycji V136–V139, lock_timeout, idempotencja dowiedziona testem.

### Do poprawy PRZED commitem

1. **DB85-R1** — decyzja + poprawka (`FOR UPDATE` w `INSERT … SELECT` lub warunek równości stanu) albo jawna akceptacja i korekta sformułowań w V141/tickecie/COMMENT; dodać test modyfikacji w oknie.
2. **DB85-R2** — warunkowy `DROP` partycji (tania poprawka, spójna z celem V141) — albo zapis w tickecie jako świadomie odłożone (ścieżka martwa).
3. **DB85-R3** — dopisać `NULL` dla INSERT nieterminalnego albo zapisać decyzję (spec ticketu mówi „INSERT nieterminalny → NULL").
Pozostałe (R4–R9) — nieblokujące, mogą iść razem lub osobno.

### Summary

**Ocena: 4/5 ⭐ — migracje i testy dobre, trigger i zegar poprawne, kontrakt RAISE/log_cron_failure nienaruszony, test wyścigu faktycznie by padł na V140; zastrzeżenie: V141 nie zamyka całej ścieżki utraty danych DB84-R4 (modyfikacja wiersza w oknie i `DROP` partycji), a deklaracja „zamknięte" w tickecie jest szersza niż fakty.**

---

## Review: V142 `purge_campaign_contact_archive` (DELETE po PK) + `Db056PurgeCampaignContactArchivePkNestedLoopMigrationTest` (DB-087, commit 9c800c81) — 2026-10-22

Zakres: `V142__purge_campaign_contact_archive_pk_nested_loop.sql`, test migracyjny (8 testów). Porównano z V126 (poprzednia wersja funkcji), V111 (RLS), DB-069 (partycjonowanie, odłożone). Odczyt READ-ONLY żywej bazy `contact_center` (rola `ccapp`, PG 16.13): migracji nie stosowano, kodu nie zmieniano. Nie uruchamiano EXPLAIN na żywej bazie (`campaign_contact_archive` ma 0 wierszy, plan byłby bez wartości), więc ustalenia o planie wynikają z analizy i z testu w repo, nie z własnego pomiaru.

### Weryfikacja semantyki względem V126 (wszystko zgodne)
- Sygnatura `(UUID, TIMESTAMPTZ, INT DEFAULT 10000) RETURNS INT`, `SECURITY INVOKER` (`prosecdef = f` na żywej bazie), walidacja `NULL/<1/>100000` → 22023, ten sam SELECT partii (`tenant_id`, `archived_at < cutoff`, `ORDER BY archived_at`, `LIMIT`, `FOR UPDATE SKIP LOCKED`), `cron_log` tylko przy `> 0`, ta sama treść komunikatu i `rows_affected`.
- Pusta partia: `SELECT array_agg(..) INTO a, b` bez `GROUP BY` zawsze zwraca jeden wiersz z `NULL`, a `IF v_record_ids IS NULL THEN RETURN 0` to obsługuje. Poprawne. Dodatkowo `NULL` tenant/cutoff → 0 jak w V126.
- `unnest(v_record_ids, v_campaign_ids)`: obie `array_agg` są liczone w jednym węźle Agg z tego samego strumienia wierszy, więc tablice mają tę samą długość i kolejność. `record_id`/`campaign_id` są częścią PK (NOT NULL), więc brak `NULL` w elementach (multi-arg `unnest` w FROM dopełniłby krótszą tablicę `NULL`-ami; tu niemożliwe). `FOR UPDATE` w podzapytaniu z `LIMIT` plus agregat na zewnątrz jest dozwolone (ograniczenie dotyczy agregatu na tym samym poziomie).
- Blokady z kroku 1 trzymane do końca transakcji, więc krok 2 trafia dokładnie w te wiersze; `ROW_COUNT` = liczba partii. `SKIP LOCKED` dla równoległych wywołań nadal daje rozłączne partie.
- Zakres `SET enable_hashjoin/mergejoin` jako atrybut funkcji (`proconfig`): obowiązuje wyłącznie w czasie wykonania funkcji i jest przywracany przy wyjściu (także po błędzie, dla wywołań z Javy `SELECT purge_...` w transakcji `batchTransaction` i dla wywołań z `psql`); nie zmienia planu instrukcji wywołującego. W samej funkcji pozostałe dwie instrukcje (SELECT partii, INSERT do `cron_log`) nie mają joinów, więc flagi ich nie dotyczą. Poprawnie.
- ACL/właściciel: `CREATE OR REPLACE` zachowuje właściciela i `proacl`; żywa baza: owner `ccapp`, `proacl = NULL` (domyślne EXECUTE dla PUBLIC), jak dla V126. Dla `SECURITY INVOKER` i RLS (V111) bez zmian.
- V126 nietknięta (V142 jest nowym plikiem, `git show --stat` = 2 nowe pliki). Zasada „nie edytuj zastosowanych migracji” zachowana.

### 🐛 Bugs / Critical Issues
- **DB87-R1 (Major — proces/dokumentacja, wymaga decyzji PRZED dokumentacją) V142 jest JUŻ zastosowana na żywej bazie, a ticket mówi, że nie.** `flyway_schema_history`: wersja 142, `success = t`, `installed_on = 2026-10-22 18:31:23` (commit `9c800c81` o 18:23 UTC), a `proconfig` funkcji na żywej bazie = `{enable_hashjoin=off,enable_mergejoin=off}`. `TASKS-DATABASE.md` DB-087 (status i kryterium „Zastosowanie V142 na żywej bazie — NIEWYKONANE (max Flyway = 141)”) oraz uwaga w DB-056/PROGRESS są nieaktualne. Konsekwencje: (a) checksum V142 jest już zamrożony na żywej bazie, więc KAŻDA poprawka funkcji z tego review musi trafić do nowej migracji V143 (nie edytować V142 — `validate` zablokuje start backendu); (b) dokumentacja `06-database.md` ma opisywać V142 jako zastosowaną (stan `max = 142`), a zastosowanie odhaczyć po weryfikacji (`proconfig` już potwierdzony tym review); (c) nie wolno pisać w docs „do zastosowania”. Kryterium „code review” zamknąć po wdrożeniu ustaleń lub ich jawnej akceptacji. *Skorygowane w dokumentacji (tura 44, 2026-10-22): ticket DB-087 i PROGRESS mówią, że V142 jest zastosowana; kryterium zastosowania V143 pozostaje otwarte.*

### ⚠️ Security Concerns
- **DB87-R2 (Minor — hardening, brak realnego wycieku) krok 2 usuwa po `(record_id, campaign_id)` bez `tenant_id`.** Bezpieczne dziś: PK jest unikalny, a pary pochodzą z kroku 1 (przefiltrowanego po `tenant_id`) i są zablokowane. Ale na żywej bazie `ccapp` ma `BYPASSRLS` (V111, nagłówek), więc RLS nie jest tu siatką bezpieczeństwa; jedyną gwarancją izolacji jest poprawność kroku 1. Tania obrona w głąb: dodać `AND a.tenant_id = p_tenant_id` w `DELETE` (nie zmienia planu — PK-lookup + filtr). W połączeniu z DB87-R3 daje spójny predykat „pełna tożsamość wiersza”. *Poprawione w commicie 15cf66df (V143) — INACZEJ niż rekomendacja: guard fail-closed na `RETURNING a.tenant_id` (SQLSTATE 23000, wycofanie partii) zamiast predykatu w `WHERE`, bo stały predykat `tenant_id` zaniżał estymatę na tabeli bez statystyk i dawał ~7 s/partię; świadome odstępstwo, właściciel poinformowany.*

### 🏗️ Architecture / Pattern Violations
- **DB87-R3 (Medium — dormant, ale teza „odporne na partycjonowanie” jest nieprawdziwa) `DELETE` po `(record_id, campaign_id)` nad-usuwa po zmianie PK z DB-069.** DB-069 (TASKS-DATABASE.md ok. l. 4018) mówi wprost: PK będzie `(record_id, campaign_id, archived_at)`. Wtedy unikalność pary `(record_id, campaign_id)` przestaje być gwarantowana przez bazę, a V142 usunie WSZYSTKIE wersje pary — także nowsze (niekwalifikujące się, `archived_at >= cutoff`) i niezablokowane — czyli utrata danych, a zwrócona liczba przestanie odpowiadać partii. Dodatkowo `DELETE` bez `archived_at` nie pozwala na partition pruning: każdy wiersz partii sonduje PK we WSZYSTKICH partycjach (koszt × liczba partycji), więc zysk z V142 znika. Wpis „Uwaga projektowa (DB-087)” w DB-069 i nagłówek V142 („nie po ctid (odporne na przyszle partycjonowanie)”) przeceniają odporność. Poprawka (tania, bezpieczna już dziś, `archived_at` jest NOT NULL i porównanie `timestamptz` na tych samych wartościach jest dokładne): w kroku 1 `array_agg(s.archived_at)` jako trzecia tablica, w kroku 2 `USING unnest(ids, cids, ats) AS b(record_id, campaign_id, archived_at)` i predykat `AND a.archived_at = b.archived_at`. Wtedy pełna tożsamość wiersza = PK po DB-069, pruning po kluczu partycji działa, a dla dzisiejszego PK planner nadal używa `pk_campaign_contact_archive` (dwie z trzech kolumn dopasowane indeksem + filtr). Zrobić w V143 teraz (zmiana jednolinijkowa) albo najpóźniej jako jawny punkt DoD DB-069 (obecnie jest tylko „sprawdzić, że nadal wykorzystuje indeks” — to za mało, bo problemem jest poprawność, nie tylko plan). *Poprawione w commicie 15cf66df (V143).*
- **DB87-R4 (Minor) `SET` w `proconfig` jest kruche względem kolejnych migracji.** `CREATE OR REPLACE FUNCTION` z nową definicją BEZ klauzul `SET` zeruje `proconfig` (każda następna migracja tej funkcji, np. DB-069/DB-083, która skopiuje definicję bez atrybutów, po cichu wraca do Hash Join + Seq Scan). Ochrona dziś: tylko test migracyjny, który migruje do `target(v142)` i dlatego NIE wykryje regresji z późniejszej migracji; końcowy blok `DO` działa tylko w V142. Poprawka: test „na końcu łańcucha” (pełny Flyway) sprawdzający `proconfig` i/lub plan, albo adnotacja w DoD DB-069/DB-083 („używać `ALTER FUNCTION` lub zachować `SET`”; ticket DB-087 R3 już zakłada `ALTER FUNCTION` dla DB-083 — dopisać to też do DB-069). Powiązane ograniczenie do odnotowania w `06-database.md`: funkcja z klauzulą `SET` nie może zostać przekształcona w `PROCEDURE` z `COMMIT` wewnątrz (PG zabrania transaction control w procedurach z `SET`), a dokładanie joinów do tej funkcji będzie wykonywane bez hash/merge join. *Poprawione w commicie 15cf66df (V143) — test na pełnym łańcuchu Flyway, ostrzeżenie w nagłówku i `COMMENT`; adnotacja w DoD DB-069 (tura 44).*

### 🔧 Improvements & Suggestions
- **DB87-R5 (Medium — jakość testu) asercja „najstarszy pierwszy” jest pusta i zależna od czasu (`batchSizeValidation_andOldestFirst`).** `min(archived_at) > now() - interval '2000 days'` porównuje wiersze wstawione o `now_ins - 2000d + g s` z `now_zapytania - 2000d`. Warunek jest prawdziwy tylko gdy upłynęło mniej niż ~1–2 s od wstawienia, więc: (a) przechodzi także gdy funkcja usunęła DOWOLNY wiersz inny niż najstarszy (min nadal `+1 s`, o ile test jest szybki) — nie dowodzi kolejności; (b) na wolnym CI padnie przypadkowo. Test parytetu też nie sprawdza kolejności, mimo komentarza „w tej samej kolejności”: porównuje tylko liczby partii i końcowy fingerprint po pełnym opróżnieniu (ten sam zbiór niezależnie od kolejności). Poprawka: wstawić wiersze z jawnymi stałymi `archived_at` (np. `timestamptz '2020-01-01' + g * interval '1 s'`), zapisać `min(archived_at)` PRZED wywołaniem i asercją `NOT EXISTS` wiersza o tym `archived_at` po wywołaniu z batch=1 oraz kolejne wywołania usuwające kolejne w porządku; w teście parytetu porównywać `fingerprint` po KAŻDEJ partii pre vs post (to dowodzi kolejności i dokładnie tych samych wierszy w partii). *Poprawione w commicie 15cf66df (V143) — test przepisany.*
- **DB87-R6 (Medium — jakość testu) plan sprawdzany tylko dla pierwszego wykonania, jako superuser, bez RLS.** `deletePlanOf` używa nowego połączenia jako `cc_test` (superuser, omija RLS) i loguje plan pierwszego wywołania (custom plan z realnym rozmiarem tablicy). Produkcyjny wzorzec to ta sama sesja z puli wywołująca funkcję w pętli (BE-121): po 5 wywołaniach plpgsql może przejść na generic plan (estymata `unnest` z parametru ≈ 10 wierszy), a pod docelowym `app_user` (DB-071) dochodzi filtr RLS `tenant_id = current_setting(...)`. Moja analiza mówi, że Nested Loop + Index Scan pozostaje wyborem (przy wyłączonym hash/merge alternatywą jest tylko NL z Seq Scan, wielokrotnie droższy), ale NIE jest to dowiedzione testem. Dodać: (1) ≥ 7 kolejnych wywołań w jednej sesji z `auto_explain` i asercją planu na ostatnim; (2) wariant z `SET plan_cache_mode = force_generic_plan`; (3) wariant pod rolą nie-superuser z `app.current_tenant_id` (są już testy izolacji dla V126 — `CampaignContactArchivePurgeTenantIsolationTest` — dobrze byłoby dopisać tam asercję planu). Wiązanie z `Seq Scan on campaign_contact_archive` / `Hash Join` to stringi planu tekstowego PG 16 (obraz przypięty do `postgres:16-alpine`, żywa baza 16.13); przy podbiciu obrazu na PG 17/18 (zmieniona semantyka `enable_*` = `disabled_nodes`, inne nazwy w EXPLAIN) test może wymagać aktualizacji — warto przejść na `auto_explain.log_format = json` i asercje na typach węzłów (`Node Type`, `Index Name`). *Poprawione w commicie 15cf66df (V143) — test przepisany.*
- **DB87-R7 (Minor) test nie dowodzi tego, co nagłówek deklaruje o „bez wpływu na sesję wywołującego”, i nie sprawdza SQLSTATE.** Dodać: po wywołaniu funkcji w tej samej sesji `SHOW enable_hashjoin` = `on` (także po wywołaniu, które rzuciło 22023); w `batchSizeValidation` zamiast `hasMessageContaining` sprawdzić `PSQLException.getSQLState()` = `22023` (ticket i nagłówek obiecują kod, test tylko fragment tekstu). Dodać też asercję, że wywołanie z dużą partią (100000, granica zakresu) działa na tablicach tej wielkości (obecnie 100000 przetestowane tylko na 4 wierszach). *Poprawione w commicie 15cf66df (V143) — test przepisany.*
- **DB87-R8 (Minor) nagłówek przecenia stabilność.** „plan nie zależy od estymat ani od random_page_cost” — `enable_*=off` tylko dodaje `disable_cost` (PG 16) / liczy węzły wyłączone (PG 18); wybór między Index Scan a Seq Scan wewnątrz Nested Loop nadal jest kosztowy (nagłówek sam to przyznaje dwa zdania wcześniej). Uczciwsze sformułowanie: „wyklucza Hash/Merge Join; Nested Loop z Index Scan po PK wygrywa kosztowo dla każdej realnej relacji wielkości partii do tabeli”. Wartość pomiaru (600 tys. wierszy, 78–105 ms) jest w tickecie, a nie w COMMENT — wystarczy. *Poprawione w commicie 15cf66df (V143).*
- **DB87-R9 (Minor) końcowy blok `DO` weryfikuje częściowo.** Sprawdza sygnaturę i `proconfig`, ale nie `prosecdef = false`, typu zwracanego, ani że komentarz został ustawiony; te rzeczy są pokryte wyłącznie testem Javy. Tanie dołożenie: `prosecdef` i `prorettype = 'int4'::regtype`. Bez wpływu na poprawność. *Poprawione w commicie 15cf66df (V143).*
- **DB87-R10 (Info) `SET LOCAL lock_timeout = '5s'`.** `CREATE OR REPLACE FUNCTION` nie blokuje tabel (tylko wiersz `pg_proc`), więc timeout jest praktycznie bez efektu, ale spójny z konwencją innych migracji i nieszkodliwy; zostawić. Pamięć: tablice do 100 000 UUID ≈ 1,6 MB każda — pomijalne. *Świadomie zostawione (bez zmian).*

### ✅ Positive Observations
- Ścisła zgodność kontraktu z V126 (sygnatura, 22023, `cron_log` tylko `> 0`, `SKIP LOCKED`, `SECURITY INVOKER`, ACL/owner) i jawna lista „ZACHOWANE BEZ ZMIAN” w nagłówku; V126 nietknięta, V142 jako nowy plik.
- Diagnoza opisana z liczbami (600 tys. wierszy, 490–720 ms → 78–105 ms) i z listą odrzuconych wariantów (`IN (SELECT…)`, `ctid`, pętla `FOR`) — decyzja odtwarzalna. Wybór `unnest` po pełnym PK zamiast `ctid` jest właściwy (`ctid` jest dwuznaczny między partycjami).
- Obsługa pustej partii (`array_agg` → `NULL`) jawna; krok 1 i krok 2 poprawnie sprzężone blokadą do końca transakcji.
- Test: dwie bazy (pre = migracja tuż przed V142 wyznaczona po opisie, post = V142) z identycznym, deterministycznym zbiorem (md5-UUID) i parytetem pre/post; przypadek ten sam `record_id` w dwóch kampaniach (pełny PK); `SKIP LOCKED` dowiedziony drugim połączeniem z `finally`-owym rollbackiem; test planu przez `auto_explain` + `SQLWarning` z cofniętą transakcją (dane nietknięte, `count` sprawdzony po); idempotencja dowiedziona przez ponowne wykonanie skryptu i porównanie `pg_get_functiondef`; RED na V126 potwierdzony.
- Opis w `COMMENT ON FUNCTION` zaktualizowany (wzmianka o Nested Loop i atrybutach).

### Do poprawy PRZED dokumentacją (i, jeśli zdecydujesz, V143)
1. **DB87-R1** — zaktualizować ticket DB-087/PROGRESS: V142 zastosowana 2026-10-22 18:31:23 (`success = t`, `proconfig` potwierdzony); wszelkie zmiany funkcji tylko przez V143. Bez tego dokumentacja `06-database.md` powieli nieprawdziwy stan.
2. **DB87-R3** (+ R2) — `archived_at` (i `tenant_id`) w predykacie `DELETE`: V143 teraz albo twardy punkt DoD DB-069; w każdym razie skorygować tezę „odporne na partycjonowanie” w V142-nagłówku/DB-069/dokumentacji (nie pisać w docs, że jest odporne).
3. **DB87-R5** — naprawić asercję kolejności i dodać porównanie po każdej partii (test jest dziś pusty co do ORDER BY i zależny od czasu).
4. **DB87-R6 / R4 / R7** — testy: generic plan / wiele wywołań w sesji / rola nie-superuser; guard na końcu łańcucha migracji dla `proconfig`; `SHOW enable_hashjoin` po wywołaniu i SQLSTATE 22023. Nieblokujące dla żywej bazy (V142 działa poprawnie na dziś: tabela ma 0 wierszy, flaga archiwum wyłączona), ale warto domknąć przed włączeniem retencji archiwum.
5. Reszta (R2 jako samodzielny hardening, R8–R10) — nieblokująca, do uwzględnienia w opisie w dokumentacji (R4: ograniczenia `SET` w funkcji; R8: poprawne sformułowanie stabilności).

### Summary

**Ocena: 4/5 ⭐ — migracja jest semantycznie wierna V126 i poprawnie obsługuje pustą partię oraz zakres `SET`, a test parytetu na dwóch bazach jest solidny; odejmuję za nieprawdziwą tezę „odporne na partycjonowanie” (DELETE bez `archived_at` nad-usunie po zmianie PK w DB-069), pustą asercję kolejności w teście i nieaktualny status „niezastosowana” (V142 jest już na żywej bazie, więc poprawki tylko przez V143).**
