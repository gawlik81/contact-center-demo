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
