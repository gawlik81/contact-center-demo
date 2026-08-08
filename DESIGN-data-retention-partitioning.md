# Projekt: Partycjonowanie i retencja danych z obsługi kontaktów

Status: **projekt do akceptacji** (nie wdrożone)
Powiązane: `ARCHITECTURE.md` §4.1–4.3, §6.6, §8.6, §10.3 (RC-02); `PRD.md` §6.5 (NFR-RODO03); `documentation/tech/06-database.md` §5.6

## 0. Kontekst — co już istnieje i co jest realnie zepsute

Audyt repo (migracje V001–V081, `TenantAwareRepository`, joby `@Scheduled`, panel `/supervisor/settings`) pokazał, że temat retencji/partycjonowania **nie jest budowany od zera**:

| Element | Stan |
|---|---|
| `contact` — RANGE partycja po `started_at`, miesięcznie | ✅ istnieje (`V007`, `V011`) |
| `audit_log` — RANGE partycja po `created_at`, miesięcznie, + `drop_old_audit_log_partitions(24m)` | ✅ istnieje (`V004`) |
| `campaign_contact` — LIST partycja po `campaign_id` + archiwizacja → `campaign_contact_archive` → `purge_campaign_contact_archive(5 lat)` | ✅ istnieje, wzorcowy (`V009`, `V015`) |
| `RecordingRetentionJob` — usuwa nagrania z S3 po retencji | ✅ istnieje, ale **retencja globalna** (`S3Properties.retentionDays`), mimo że `tenant.config.recording_retention_days` i `TenantResourceLimitsDto` sugerują per-tenant |
| UI edycji tenanta — pole retencji nagrań | ❌ **brak** w `tenant-edit-modal` mimo że model TS je zna |
| Automatyczne tworzenie kolejnych partycji miesięcznych (`create_next_month_partitions`, `pg_cron`) | ❌ **nie działa** — `cron.schedule(...)` w `V014` jest zakomentowany, `pg_cron` nie jest nawet włączony w obrazie `postgres:16-alpine`, i żaden Java `@Scheduled` job tego nie wywołuje jako fallback |
| Panel `/supervisor/settings/data-retention` dla tenant admina | ❌ brak (jest `email`, `phone-numbers`, `twilio`, `ai-config`, `plugins` itd., ale nie retencja) |
| Powiadomienie „są dane do usunięcia” | ❌ brak — `ARCHITECTURE.md` §10.3 (RC-02) explicite wymienia to jako wymaganą mitygację, która nie została zrealizowana |
| `contact_event`, `contact_transcription`, `contact_ai_summary` | zwykłe (niepartycjonowane) tabele powiązane logicznie z `contact` |

**Krytyczna, potwierdzona konsekwencja**: dzisiejsza data systemowa to 2026-08-08, a partycje `contact`/`audit_log` utworzone w migracjach kończą się na `2026_05`. Od czerwca 2026 wszystkie nowe rekordy trafiają do partycji `*_default` (fallback) — bez indeksów partycjonowania, bez możliwości szybkiego `DROP PARTITION`. **To musi zostać naprawione jako fundament tej funkcji** — partycjonowanie retencji nie zadziała, jeśli dane nie trafiają do właściwych partycji.

Ten projekt więc: (a) naprawia rotację partycji, (b) rozszerza partycjonowanie na tabele powiązane z kontaktem, (c) dodaje warstwę konfiguracji retencji per tenant, (d) dodaje silnik liczenia/usuwania danych, (e) dodaje UI w panelu tenant admina.

## 1. Zakres danych (kategorie retencji)

| Kategoria | Tabele | Domyślna retencja | Mechanizm usuwania |
|---|---|---|---|
| `CONTACT_INTERACTIONS` | `contact`, `contact_event` | 60 miesięcy (5 lat — zgodnie z tekstem już wpisanym w `gdpr_processing_register`) | usunięcie wiersza |
| `RECORDINGS` | `contact.recording_url` (kolumna, nie osobna tabela) + obiekt S3 | 90 dni | **wyzerowanie kolumny** + usunięcie obiektu z S3 (`contact` nie jest usuwany) |
| `TRANSCRIPTS` | `contact_transcription`, `contact_ai_summary` | 90 dni (spójne z nagraniami — to pochodne treści rozmowy) | usunięcie wiersza |
| `CAMPAIGN_DATA` | `campaign_contact_archive` (po dotychczasowym 30-dniowym auto-archiwum z `campaign_contact`) | 60 miesięcy | usunięcie wiersza (rozszerzenie istniejącego `purge_campaign_contact_archive`) |

**Poza zakresem tego projektu** (świadoma decyzja, do potwierdzenia):
- `audit_log` — pozostaje retencją **platformową** (24 mies., ustawianą przez SUPER_ADMIN, nie tenant admina) — to log bezpieczeństwa/zgodności, nie „dane z obsługi kontaktów”. Naprawiamy tylko jego rotację/DROP (patrz §5), nie dodajemy per-tenant configu.
- `customer` (dane mistrzowskie klienta) — ma już osobny mechanizm RODO (`anonymize_customer`, `export_customer_data`). Ten projekt dotyczy historii *interakcji*, nie usuwania danych klienta.
- Zgłoszenia/tickety — w repo **nie znaleziono** modułu ticketingu jako osobnej encji; jeśli powstanie, dołoży się jako kolejna kategoria wg tego samego wzorca.
- Legal hold / blokada usuwania dla danych objętych postępowaniem — nie zaimplementowane, do rozważenia jako rozszerzenie.

## 2. Model danych — konfiguracja polityk retencji

```sql
-- V082__create_tenant_retention_policy.sql
CREATE TABLE tenant_retention_policy (
    policy_id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    data_category        VARCHAR(30) NOT NULL CHECK (data_category IN
                          ('CONTACT_INTERACTIONS','RECORDINGS','TRANSCRIPTS','CAMPAIGN_DATA')),
    retention_months      INT NOT NULL CHECK (retention_months BETWEEN 1 AND 120),
    auto_purge_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by             UUID REFERENCES app_user(user_id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_retention_policy UNIQUE (tenant_id, data_category)
);

ALTER TABLE tenant_retention_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_retention_policy ON tenant_retention_policy
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);
-- UWAGA: użyj `app.current_tenant_id` (poprawna nazwa GUC ustawiana przez
-- set_tenant_context()) — NIE `app.tenant_id`, na który omyłkowo trafiły
-- V059/V064/V067/V068 (patrz §7).
```

Cache zapotrzebowania na usuwanie (żeby dashboard admina był szybki — bez liczenia COUNT(*) na żądanie):

```sql
-- V083__create_tenant_retention_pending_summary.sql
CREATE TABLE tenant_retention_pending_summary (
    tenant_id            UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    data_category         VARCHAR(30) NOT NULL,
    eligible_row_count      BIGINT NOT NULL DEFAULT 0,
    oldest_eligible_period   DATE,           -- najstarszy miesiąc z danymi do usunięcia
    newest_eligible_period    DATE,          -- najmłodszy miesiąc kwalifikujący się (= cutoff - 1 dzień)
    computed_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, data_category)
);
-- RLS analogicznie jak wyżej
```

Historia operacji usuwania (audyt szczegółowy, oddzielny od generycznego `audit_log`, bo potrzebujemy ustrukturyzowanych liczb do UI):

```sql
-- V084__create_retention_purge_log.sql
CREATE TABLE retention_purge_log (
    purge_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    data_category    VARCHAR(30) NOT NULL,
    triggered_by      UUID REFERENCES app_user(user_id),  -- NULL = auto-purge (system)
    trigger_type       VARCHAR(10) NOT NULL CHECK (trigger_type IN ('MANUAL','AUTO')),
    cutoff_date         DATE NOT NULL,
    rows_deleted          BIGINT,
    status               VARCHAR(15) NOT NULL DEFAULT 'RUNNING'
                         CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    started_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    error_message            TEXT
);
CREATE INDEX idx_retention_purge_log_tenant ON retention_purge_log (tenant_id, started_at DESC);
-- RLS analogicznie
```

Domyślne polityki są zasiewane dla każdego tenanta przy jego utworzeniu (rozszerzenie `TenantServiceImpl` — analogicznie do dzisiejszego zasiewania `tenant.config`), a migracja `V082` dodatkowo backfilluje wiersze dla tenantów już istniejących wartościami z tabeli powyżej.

## 3. Partycjonowanie — uzupełnienie brakujących tabel

`contact_event`, `contact_transcription`, `contact_ai_summary` stają się partycjonowane RANGE po własnej kolumnie czasowej, wg wzorca już użytego w `V077__create_plugin_invocation_log.sql` (najświeższy, wzorcowy przykład w repo):

```sql
-- V085__partition_contact_event.sql (analogicznie V086 dla contact_transcription, V087 dla contact_ai_summary)
-- Wzorzec bezpiecznej migracji online (tabela ma dane produkcyjne):
-- 1. CREATE TABLE contact_event_new (... identyczne kolumny ...,
--      CONSTRAINT pk_contact_event_new PRIMARY KEY (event_id, started_at)
--    ) PARTITION BY RANGE (started_at);
-- 2. utworzenie partycji dla istniejącego zakresu danych + bieżący/przyszłe miesiące
-- 3. INSERT INTO contact_event_new SELECT * FROM contact_event;  (w transakcji / batchami dla dużych wolumenów)
-- 4. ALTER TABLE contact_event RENAME TO contact_event_old;
--    ALTER TABLE contact_event_new RENAME TO contact_event;
--    (przeniesienie indeksów, RLS, grantów)
-- 5. DROP TABLE contact_event_old; -- po weryfikacji
```

Konsekwencje dla warstwy Java (zgodnie z istniejącym wzorcem `Contact`/`AuditLog`):
- `ContactEvent` i `ContactAiSummary` (mają dziś proste `@Id`) przechodzą na `@IdClass` z kompozytowym kluczem `(id, kolumna_czasowa)`, zapis przez natywny SQL zamiast `EntityManager.persist()` — identycznie jak `ContactRepository`.
- `contact_transcription` nie ma dziś encji JPA (czysty `JdbcTemplate`) — zmiana ogranicza się do SQL-a repozytorium (dodanie kolumny partycjonowania do PK w insertach).
- Wszystkie trzy tabele dołączają do wspólnej funkcji rotacji partycji (patrz §5) — rozszerzenie `create_next_month_partitions()` o te tabele, analogicznie jak zrobiono to dla `plugin_invocation_log` w V077.

Dla `campaign_contact` / `campaign_contact_archive` **nie zmieniamy** istniejącej strategii (LIST po `campaign_id` + archiwum) — to już działa i dobrze pasuje do charakterystyki danych kampanijnych. Nowa polityka retencji (`CAMPAIGN_DATA`) podłącza się do istniejącego `purge_campaign_contact_archive()`, zamieniając jego dziś zahardkodowany parametr `p_retention_years DEFAULT 5` na wartość czytaną per-tenant z `tenant_retention_policy`.

Nowy indeks potrzebny do wydajnego usuwania per-tenant (dziś dostępne indeksy `tenant_id`-owe na `contact` są zawężone do konkretnych zapytań raportowych, brak ogólnego `(tenant_id, started_at)`):

```sql
-- V089__add_tenant_scoped_retention_indexes.sql
CREATE INDEX idx_contact_tenant_started_at ON contact (tenant_id, started_at);
-- + analogiczne (tenant_id, <kolumna_czasowa>) na contact_event, contact_transcription,
--   contact_ai_summary, campaign_contact_archive (jeśli brak)
```

## 4. Naprawa rotacji partycji (fundament — musi wejść pierwsze)

Zamiast aktywować `pg_cron` (rozszerzenie niedostępne w obrazie `postgres:16-alpine`, wymagałoby customowego obrazu Docker — niepotrzebna nowa zależność infrastrukturalna), rotację realizujemy przez **Spring `@Scheduled`** — to jest już dominujący, sprawdzony wzorzec w projekcie (12 istniejących jobów, m.in. `RecordingRetentionJob`).

```java
// domain/retention/PartitionMaintenanceJob.java
@Scheduled(cron = "${retention.partition-maintenance-cron:0 30 0 * * *}", zone = "UTC")
public void ensureFuturePartitions() {
    // dla każdej partycjonowanej tabeli (contact, audit_log, contact_event,
    // contact_transcription, contact_ai_summary) woła SQL create_*_partition()
    // tak, aby zawsze istniały partycje na bieżący miesiąc + 3 miesiące w przód
}
```

Backfill przy wdrożeniu (`V088__fix_partition_rotation_functions.sql`): jednorazowe dotworzenie brakujących partycji `2026_06`–`2026_09` dla `contact`/`audit_log` (dziś te dane leżą w `*_default`) + przeniesienie wierszy z `*_default` do właściwych partycji miesięcznych (`INSERT ... SELECT ... FROM contact_default WHERE started_at >= ... AND started_at < ...` + `DELETE FROM contact_default WHERE ...`, batchami).

## 5. Silnik liczenia „danych do usunięcia” — wykorzystanie partycjonowania

`RetentionEvaluationJob` (`@Scheduled`, codziennie np. 01:00 UTC):

1. Dla każdej partycjonowanej tabeli w zakresie, **iteruje partycje od najstarszej**, a nie skanuje całej tabeli — to jest bezpośrednia korzyść z partycjonowania miesięcznego: partycja, której górna granica jest młodsza niż najkrótsza skonfigurowana retencja wśród wszystkich tenantów, oznacza koniec skanowania (nowsze partycje na pewno nie mają jeszcze przeterminowanych danych).
2. Na każdej takiej „starej” partycji robi `SELECT tenant_id, count(*) FROM ONLY <partycja> GROUP BY tenant_id` (szybkie — skan pojedynczej, ograniczonej partycji z indeksem, nie całej hierarchii).
3. Zestawia wynik z `tenant_retention_policy.retention_months` per tenant/kategoria i zapisuje do `tenant_retention_pending_summary` (upsert).
4. Dla polityk z `auto_purge_enabled = TRUE` — od razu po policzeniu wywołuje `RetentionPurgeService` dla danego tenanta/kategorii (patrz §6).

To sprawia, że panel admina zawsze czyta gotowy, tani w odczycie wiersz z `tenant_retention_pending_summary` (+ znacznik `computed_at`), zamiast liczyć na żywo.

## 6. Silnik usuwania — model dwupoziomowy

Zgodnie z ustaloną decyzją (partycja tylko po miesiącu, bez sub-partycji per tenant), usuwanie realizujemy w dwóch niezależnych warstwach:

**Poziom 1 — usuwanie na poziomie wiersza, per tenant (widoczne dla admina)**
Wywoływane ręcznie (`POST /purge`) lub automatycznie (auto-purge toggle). Działa niezależnie od innych tenantów współdzielących tę samą partycję miesięczną:
```sql
DELETE FROM contact WHERE tenant_id = :tenantId AND started_at < :cutoff
```
wykonywane **batchami** (wzorzec identyczny jak w istniejącym `RecordingRetentionJob`, `BATCH_SIZE=100`/`LIMIT` + pętla), żeby nie trzymać długich locków na partycji współdzielonej przez innych tenantów. Dla `CONTACT_INTERACTIONS` dodatkowo czyści logicznie powiązane rekordy bez fizycznego FK: `email_message`/`social_message` wskazujące na usuwany `contact_id` (już nullable — `V028`).

**Poziom 2 — fizyczne odzyskanie miejsca, globalne (niewidoczne dla admina, czysto operacyjne)**
`PartitionReclaimJob` (`@Scheduled`, tygodniowo) — dla każdej partycji miesięcznej sprawdza, czy jej górna granica jest starsza niż **maksimum** `retention_months` spośród *wszystkich* tenantów mających kiedykolwiek dane w tej kategorii. Jeśli tak (czyli formalnie żaden tenant nie mógłby już mieć tam nic do zachowania) — `DROP TABLE <partycja>` (rozszerzenie istniejącego `drop_old_audit_log_partitions`, uogólnione na pozostałe tabele). To jest bezpieczny, zachowawczy próg: nigdy nie usuwa danych szybciej niż zezwala na to najdłuższa skonfigurowana retencja — a że Poziom 1 już wcześniej usunął wiersze każdego tenanta zgodnie z jego własną, krótszą retencją, DROP na końcu w praktyce trafia na już (prawie) puste partycje.

**Wyjątek: kategoria `RECORDINGS`** — to nie jest usuwanie wiersza, tylko wyzerowanie pola + usunięcie obiektu blob:
```java
// rozszerzenie istniejącego RecordingRetentionJob — dziś czyta globalny
// S3Properties.retentionDays, ma zacząć czytać tenant_retention_policy
// (data_category = 'RECORDINGS') per tenant
for (UUID tenantId : tenantsWithRecordings) {
    Instant cutoff = now().minus(retentionPolicyService.getRetentionDays(tenantId, RECORDINGS));
    // ... reszta logiki bez zmian (S3 delete + recording_url = NULL)
}
```
To domyka istniejącą, udokumentowaną lukę (NFR-RODO03 w `PRD.md` wprost wymaga „konfigurowalny per tenant”, a dziś tego nie ma).

Każda operacja usuwania — manualna czy automatyczna — zapisuje wpis w `retention_purge_log` (status RUNNING → COMPLETED/FAILED, liczba usuniętych wierszy) oraz w `audit_log` (`entity_type='RETENTION_PURGE'`) dla spójności z istniejącym mechanizmem audytu.

Wykonanie asynchroniczne (endpoint zwraca `purgeId` natychmiast, klient odpytuje status) — zgodnie z regułą z `CLAUDE.md` o `TenantContext.snapshot()`/`restore()`/`clear()` przy przechodzeniu przez granice wątków (`@Async`), bo purge nie może polegać na `ThreadLocal` z wątku żądania HTTP.

## 7. API (do implementacji przez `backend-dev-expert`)

Wszystkie endpointy wymagają roli `ADMIN` (tenant-scoped), autoryzacja przez istniejący `JwtAuthFilter`/`TenantFilter` — **brak nowych endpointów publicznych**, więc nie dotyczy to `SecurityConfig`/`TenantFilter.PUBLIC_PATH_PREFIXES`.

| Endpoint | Opis |
|---|---|
| `GET /api/tenants/{tenantId}/retention/policies` | lista polityk per kategoria |
| `PUT /api/tenants/{tenantId}/retention/policies/{category}` | zmiana `retentionMonths` / `autoPurgeEnabled` |
| `GET /api/tenants/{tenantId}/retention/summary` | dane do dashboardu „ile do usunięcia” (z cache) |
| `POST /api/tenants/{tenantId}/retention/purge` | body `{dataCategory}` → async purge, zwraca `purgeId` |
| `GET /api/tenants/{tenantId}/retention/purge/{purgeId}` | status trwającego/zakończonego purge |
| `GET /api/tenants/{tenantId}/retention/history` | log operacji (`retention_purge_log`) |

## 8. UI panelu tenant admina (do implementacji przez `angular-frontend-expert`)

Nowa strona: `features/supervisor/settings/pages/data-retention/data-retention.component.ts`, dopięta jako dziecko `SUPERVISOR_ROUTES` → `settings` (rola `ADMIN`, wzorem `twilio`/`ai-config`).

1. **Tabela konfiguracji polityk** — jeden wiersz na kategorię (Interakcje i kontakty / Nagrania / Transkrypcje / Dane kampanii): pole „okres retencji (miesiące)”, przełącznik „usuwaj automatycznie po upływie retencji”, przycisk zapisu.
2. **Dashboard „dane do usunięcia”** — karty per kategoria: liczba kwalifikujących się rekordów, najstarszy miesiąc, znacznik czasu ostatniego przeliczenia (`computed_at` z `tenant_retention_pending_summary`).
3. **Globalny wskaźnik powiadomienia** — badge przy pozycji „Ustawienia” w nawigacji panelu supervisora, widoczny gdy `sum(eligible_row_count) > 0` w dowolnej kategorii — to realizuje wymóg „administrator powinien mieć informację, że są dane do usunięcia” w sposób trudny do przeoczenia, nie tylko schowany w podstronie ustawień. (Domyka wprost `RC-02` z `ARCHITECTURE.md` §10.3.)
4. **Akcja „Usuń teraz”** — per kategoria, modal potwierdzenia pokazujący dokładną liczbę i zakres dat (wzorem istniejącego `tenant-deactivate-modal`), akcja nieodwracalna → wyraźne ostrzeżenie.
5. **Historia operacji** — tabela z `retention_purge_log` (kto/kiedy/ile/status).

Przy okazji tego samego epicu warto domknąć znany gap: dodać brakujące pole `recording_retention_days` do `tenant-edit-modal` (dziś model TS je zna, formularz nie) — SUPER_ADMIN i tak zarządza nim na poziomie `tenant.config`, ale skoro wprowadzamy per-tenant retencję nagrań jako pełnoprawną politykę w `tenant_retention_policy` (kategoria `RECORDINGS`), **rekomenduję migrację tego pola** z `tenant.config.recording_retention_days` do nowej tabeli polityk i usunięcie duplikatu, żeby nie było dwóch źródeł prawdy dla tej samej wartości.

## 9. Bezpieczeństwo i spójność multi-tenancy

- Wszystkie nowe tabele: RLS z poprawną nazwą GUC `app.current_tenant_id` (patrz `TenantAwareRepository.setTenantContextInDb()`), **nie** powtarzać błędu z `V059`/`V064`/`V067`/`V068`.
- Przy okazji prac na `contact_event`/`contact_transcription`/`contact_ai_summary` (bo i tak przechodzą migrację przez partycjonowanie w §3) — **rekomenduję dołączyć poprawkę** tej niespójności GUC jako osobną migrację (`V090__fix_rls_guc_naming_inconsistency.sql`), skoro dotykamy dokładnie tych tabel i tak. To nie jest ściśle częścią wymagania użytkownika, ale jest tanie do zrobienia teraz i ryzykowne do zostawienia (RLS z niewłaściwym GUC oznacza politykę, która nigdy się nie dopasowuje, czyli w praktyce brak izolacji przez RLS — warstwa aplikacji z `assertSameTenant` wciąż chroni, ale to defense-in-depth jest osłabione).
- Każde repozytorium retencji rozszerza `TenantAwareRepository`, wywołuje `assertSameTenant(...)` przed zapisem, zgodnie z regułą z `CLAUDE.md`.
- Purge jobs działające w tle (`@Scheduled`) jawnie ustawiają kontekst tenanta per iteracja (`setTenantContextInDb(tenantId)`), analogicznie do `RecordingRetentionJob` — nie polegają na `TenantContext` z wątku żądania.

## 10. Plan migracji Flyway (numeracja od V082)

```
V082__create_tenant_retention_policy.sql
V083__create_tenant_retention_pending_summary.sql
V084__create_retention_purge_log.sql
V085__partition_contact_event.sql
V086__partition_contact_transcription.sql
V087__partition_contact_ai_summary.sql
V088__fix_partition_rotation_functions.sql   -- backfill V082026_06..09 + create_next_month_partitions() dla nowych tabel
V089__add_tenant_scoped_retention_indexes.sql
V090__fix_rls_guc_naming_inconsistency.sql   -- opcjonalny bonus-fix, do potwierdzenia z użytkownikiem
```

## 11. Rekomendowana kolejność wdrożenia i podział pracy

Zgodnie z tabelą delegacji z `CLAUDE.md`:

1. **`db-schema-architect`** — migracje V082–V090, w tym najbardziej ryzykowna część: bezpieczna online-migracja `contact_event`/`contact_transcription`/`contact_ai_summary` na tabele partycjonowane bez utraty danych/przestoju.
2. **`backend-dev-expert`** — encje/repozytoria (`@IdClass` dla nowych partycjonowanych tabel), `RetentionPolicyService`, `RetentionEvaluationJob`, `RetentionPurgeService`, `PartitionMaintenanceJob`, `PartitionReclaimJob`, rozszerzenie `RecordingRetentionJob`, `RetentionController` + DTO. Może ruszyć równolegle z (1) po ustaleniu finalnego schematu, właściwa implementacja poczeka na gotowe migracje.
3. **`angular-frontend-expert`** — strona `data-retention`, serwis, badge powiadomień, poprawka `tenant-edit-modal`. Może ruszyć równolegle z (2) po ustaleniu kontraktu API z §7.
4. **`senior-code-reviewer`** — przegląd całości po zakończeniu 1–3, ze szczególnym naciskiem na: poprawność RLS, brak długich locków przy DELETE na współdzielonych partycjach, batching.
5. **`test-suite-expert`** — testy dla: liczenia pending summary na granicach miesięcy, purge per-tenant nie kasującego danych innego tenanta we wspólnej partycji, auto-purge toggle, `PartitionReclaimJob` nie dropującego partycji z żywymi danymi.

Prace 1–3 powinny trafić do `TASKS-DATABASE.md` / `TASKS-BACKEND.md` / `TASKS-FRONTEND.md` i `PROGRESS.md` zgodnie z konwencją projektu — mogę to rozpisać przez `product-requirements-deconstructor`, jeśli zatwierdzisz ten projekt.

## 12. Założenia i decyzje wymagające Twojego potwierdzenia

1. `audit_log` pozostaje poza konfigurowalną per-tenant retencją (tylko naprawa rotacji/DROP) — traktowany jako log platformowy, nie „dane z obsługi kontaktów”.
2. Domyślne wartości retencji: interakcje/kontakty 60 mies., nagrania 90 dni, transkrypcje 90 dni, dane kampanii 60 mies. — zaczerpnięte z istniejących wzmianek w kodzie/migracjach (`gdpr_processing_register`, `V022`), nie z nowego ustalenia.
3. Próg bezpieczeństwa `retention_months` ograniczony do 1–120 (10 lat) — do potwierdzenia czy to sensowna górna granica biznesowa.
4. Migracja pola `recording_retention_days` z `tenant.config` JSONB do `tenant_retention_policy` (§8) — zmiana źródła prawdy, wymaga też dostosowania istniejącego `PATCH /api/tenants/{id}/config`. Jeśli wolisz zostawić to pole tam gdzie jest i nie ruszać istniejącego kontraktu API tenantów, mogę to wyłączyć z zakresu.
5. Poprawka nazwy GUC RLS (`V090`) — techniczny bonus-fix przy okazji, nie blokujący dla funkcji retencji; mogę to wydzielić jako osobny, niezależny task, jeśli wolisz nie mieszać go z tym epikiem.
