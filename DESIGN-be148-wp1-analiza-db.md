# BE-148 WP-1 — analiza bazodanowa (role, RLS, funkcje krzyżujące tenanty, warianty)

Data: 2026-10-11. Autor: db-schema-architect. Zakres: wyłącznie analiza read-only; inwentarz Javy robi backend-dev-expert (tu tylko odniesienia tam, gdzie Java wywołuje SQL).
Metodyka: stan żywej bazy `cc-postgres` (PG 16.13, db `contact_center`) odczytany zapytaniami SELECT; eksperyment na jednorazowym kontenerze `postgres:16-alpine` (`be148-scratch`, schemat odtworzony z `pg_dump --schema-only` żywej bazy, kontener usunięty po teście). Żadnych zmian w repo poza tym plikiem, żadnych zmian na żywej bazie.

Konwencja oznaczeń: **[F]** fakt (zapytanie/numer migracji/wynik eksperymentu), **[W]** wniosek, **[Z]** założenie niezweryfikowane.

---

## 1. Role i uprawnienia

### 1.1 Stan żywej bazy (local-demo)

[F] `pg_roles` (bez ról `pg_*`):

| rola | rolsuper | rolbypassrls | rolcanlogin |
|---|---|---|---|
| ccapp | t | t | t |
| app_user | f | f | f |
| admin_user | f | t | f |
| rls_probe | f | f | f |

- [F] `pg_auth_members` (poza `pg_*`): brak jakichkolwiek członkostw. Żaden login nie jest członkiem `app_user`/`admin_user`.
- [F] Właścicielem wszystkich obiektów schematu `public` jest `ccapp`: 120 tabel zwykłych, 9 partycjonowanych, 52 indeksy partycjonowane, 637 indeksów, 7 widoków, 1 sekwencja, 118 funkcji. Baza `contact_center` należy do `ccapp`. Rozszerzenia (`uuid-ossp`, `pg_trgm`, `pgcrypto`) w schemacie `public`, właściciel `ccapp`.
- [F] `rls_probe` nie występuje w żadnej migracji ani w kodzie (grep po `*.sql/*.java/*.md`) — pozostałość po jakimś teście. NOLOGIN, bezpieczna, ale nieudokumentowana. Nie ruszałem.
- [F] `pg_default_acl`: jeden wpis — `ccapp`, schemat `public`, tabele: `app_user=arwd`, `admin_user=arwdDxt`.
- [F] Schemat `public`: właściciel `pg_database_owner`, ACL `UC` dla `pg_database_owner` i `U` dla PUBLIC — czyli zwykła rola NIE ma `CREATE` w `public` (PG 15+).

### 1.2 Gdzie w migracjach powstają role i GRANT-y

- [F] Role tworzy WYŁĄCZNIE `V012__row_level_security.sql` (linie ok. 26–45): `CREATE ROLE app_user ... NOBYPASSRLS` i `CREATE ROLE admin_user ... BYPASSRLS`, obie bez LOGIN i bez hasła (idempotentnie, w bloku DO). To jest jedyne `CREATE ROLE` w repo (grep po `V*.sql`). Wniosek: Flyway już dziś tworzy role NOLOGIN bez sekretów — to nie narusza zasady „brak ról z hasłami w Flyway”.
- [F] V012: `GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES` (+ sekwencje USAGE/SELECT, `EXECUTE ON ALL FUNCTIONS`) dla `app_user`; `ALL PRIVILEGES` dla `admin_user`; `ALTER DEFAULT PRIVILEGES IN SCHEMA public` dla obu — bez `FOR ROLE`, więc działa tylko dla obiektów tworzonych przez rolę wykonującą migrację (dziś `ccapp`).
- [F] V105–V110 (DB-080) + V103 (DB-064/065 social_message) + V107/V108/V109/V110: `REVOKE ALL ... FROM app_user` na partycjach `audit_log`, `contact`, `contact_transcription`, `contact_ai_summary`, `contact_event`, `plugin_invocation_log`, `social_message`; funkcje `create_*_partition` wykonują `REVOKE ALL ON TABLE <partycja> FROM app_user` zaraz po `CREATE TABLE ... PARTITION OF` (ten REVOKE działa tylko jako właściciel). Żywa baza: 0 partycji z ACL zawierającym `app_user`; `campaign_contact_default` ma ACL tylko `ccapp` i `admin_user`.
- [F] `flyway_schema_history`, `cron_log`, `scheduled_job`, `refresh_token`, `tenant` mają `app_user=arwd` (z default ACL/V012). Czyli rola aplikacji (przez `app_user`) mogłaby modyfikować `flyway_schema_history` — [W] do REVOKE przy przejściu na rolę aplikacji.
- [F] Wszystkie 118 funkcji w `public`: `prosecdef = false` (INVOKER), `proacl IS NULL` (domyślne `EXECUTE` dla PUBLIC), `proconfig` puste (brak `SET search_path`). Zero funkcji SECURITY DEFINER w żywej bazie. (Grep wykazał słowa `SECURITY DEFINER` w V094/V095/V096/V098/V111 — to komentarze/uzasadnienia, nie definicje; `pg_proc` jest rozstrzygający.) Ticket BE-148 WP-4 pisze o „`log_cron_failure` (SECURITY DEFINER zachowany)” — [F] `log_cron_failure` (V135) jest INVOKER; sformułowanie z ticketu jest nieścisłe.

### 1.3 Jak dziś dobierana jest rola

- [F] Backend (prod): `application-prod.yml` — `spring.datasource.username: ${DB_USERNAME}`, `password: ${DB_PASSWORD}`; sekcja `spring.flyway` w `application-prod.yml` NIE ma własnych `user/password/url` → Flyway używa tego samego DataSource co aplikacja ([F] potwierdza to też CR-DATABASE.md l. 657). Dev: `${DB_USERNAME:postgres}`.
- [F] local-demo: `.env.local-demo` `DB_USERNAME=ccapp`; w `docker-compose.local-demo.yml` `POSTGRES_USER: ${DB_USERNAME}` → `ccapp` jest bootstrap-superuserem klastra (nie da się go zdegradować do NOSUPERUSER).
- [F] DEPLOYMENT.md l. 685 i 1663: `DB_USERNAME=ccapp` (jako przykład; nazwa roli prod nieznana — zob. BE-139).
- [F] Testcontainers: `PostgresTestDatabase` — domyślny użytkownik `cc_test` = superuser; helper `createRestrictedLoginRole` (l. ok. 87–101) tworzy rolę `LOGIN NOSUPERUSER NOBYPASSRLS` i `GRANT app_user TO <rola>` — wzorzec do reużycia w harnessie BE-139; część testów używa `SET ROLE app_user`.
- [F] CI (`.github/workflows/ci.yml`): brak jawnego serwisu Postgres (grep po `postgres`/`services`/`DB_` pusty) → CI polega na Testcontainers (superuser `cc_test`). [Z] nie czytałem całego ci.yml.

---

## 2. RLS

### 2.1 Pokrycie (żywa baza, tabele nie-partycyjne, 39 z `tenant_id` lub z RLS)

- [F] 35 tabel: `relrowsecurity = t` ORAZ `relforcerowsecurity = t`.
- [F] 4 tabele z kolumną `tenant_id`, ale BEZ RLS: `plugin_version`, `refresh_token`, `tenant`, `gdpr_processing_register`.
- [F] Tabele bez RLS i bez `tenant_id`: `cron_log`, `scheduled_job`, `etl_sync_state`, `flyway_schema_history`, `plugin`, `agent_group_member`, `campaign_agent`, `campaign_agent_group`, `queue_agent`, `queue_agent_group` (razem z 4 powyższymi = 14 tabel bez RLS).
- [F] Polityki: 26 tabel ma jedną politykę `ALL` (USING = WITH CHECK = `tenant_id = current_setting('app.current_tenant_id', true)::uuid`); 8 tabel ma 4 polityki (SELECT/INSERT/UPDATE/DELETE): `app_user`, `audit_log` (z gałęzią `tenant_id IS NULL OR ...`), `campaign`, `contact`, `customer`, `ivr_tree`, `queue`, `social_integration`. Wszystkie polityki mają `roles = {public}`.
- [F] Wzorzec odwołania do GUC: `(current_setting('app.current_tenant_id'::text, true))::uuid` — `missing_ok = true`.
- [F] `refresh_token` NIE ma RLS (relrowsecurity = f) — ticket (R3 „refresh_token tenant_id NULL — wymaga osobnej polityki”) opiera się na nieaktualnym założeniu: dziś pod rolą bez BYPASSRLS login/refresh/cleanup tokenów działają bez GUC (potwierdzone eksperymentem E6). Polityki z gałęzią `tenant_id IS NULL` istnieją tylko na `app_user` (tabela) i `audit_log`.

### 2.2 Zachowanie na braku GUC (eksperyment, rola `app_rt` NOSUPERUSER NOBYPASSRLS, członek `app_user`)

- [F] Świeże połączenie, GUC nigdy nie ustawiony: `current_setting(..., true)` = NULL → `SELECT count(*) FROM campaign` = 0, `campaign_contact` = 0 (cichy zero). `INSERT` do `campaign_contact` bez GUC: `ERROR: new row violates row-level security policy` (twardy błąd).
- [F] Po `set_config('app.current_tenant_id', <T1>, true)` w transakcji: `campaign` = 1, `campaign_contact` = 3 (tylko T1).
- [F] Po COMMIT na tym samym połączeniu GUC = `''` (nie NULL); kolejne `SELECT count(*) FROM campaign` → `ERROR: invalid input syntax for type uuid: ""` (22P02). Potwierdza znalezisko DB-074/BE-139: na poolu po pierwszym użyciu brak kontekstu = błąd, nie 0 wierszy.
- [W] Konsekwencja: ten sam job bez kontekstu zachowuje się różnie zależnie od historii połączenia (0 wierszy na świeżym, błąd na używanym) — nie wolno polegać na żadnym z tych trybów jako sygnale.

### 2.3 FORCE RLS a właściciel

- [F] Eksperyment D4: rola `own_nb` (NOBYPASSRLS) jako właściciel tabeli `campaign` z FORCE RLS widzi 0 wierszy bez GUC. Czyli właściciel bez BYPASSRLS/superuser jest objęty RLS.
- [W] Konsekwencje dla roli migracyjnej/ownera: (1) migracje z DML na tabelach FORCE (np. V098, V127 — V127 ma `SET LOCAL row_security = off`, co dla roli bez BYPASSRLS zamienia cichy UPDATE 0 na błąd) wymagają, aby rola migracyjna miała BYPASSRLS; (2) funkcje SECURITY DEFINER, które mają omijać RLS, muszą należeć do roli z BYPASSRLS albo superusera — samo „owner tabeli” nie wystarcza.

---

## 3. Funkcje SQL krzyżujące tenanty

Wszystkie poniższe są INVOKER, bez `SET search_path`, z `EXECUTE` dla PUBLIC (fakt z 1.2).

| funkcja (migracja) | co robi | tabele z RLS | pod rolą bez BYPASSRLS | wynik doświadczalny |
|---|---|---|---|---|
| `archive_completed_campaign_contacts()` (V015, zm. później) | pętla po `campaign` (wszystkie tenanty) → INSERT do `campaign_contact_archive`, DELETE z `campaign_contact`, `DROP TABLE campaign_contact_<uuid>` jeśli istnieje, wpis do `cron_log`+`scheduled_job` | `campaign`, `campaign_contact`, `campaign_contact_archive` | bez GUC: `SUCCESS`, 0 kampanii (fałszywy sukces); GUC `''`: twardy błąd 22P02; z GUC jednego tenanta: archiwizuje tylko ten tenant | E9, E10, E11 |
| `purge_campaign_contact_archive(p_tenant_id, cutoff, batch)` (V126) | DELETE batch z archiwum tenanta; wpis `cron_log` tylko gdy >0 | `campaign_contact_archive` | świeża sesja bez GUC: zwraca 0 (cicho, bez wpisu); GUC `''`: błąd 22P02 | E8 |
| `log_cron_failure(...)` (V135) | INSERT `cron_log` + UPDATE `scheduled_job` | brak RLS | działa (`app_user` ma INSERT/UPDATE) | E5 |
| `cleanup_expired_refresh_tokens()` | DELETE `refresh_token` + `cron_log`/`scheduled_job` | brak RLS | działa; usunął 1 z 2 tokenów | E6 |
| `create_next_month_partitions()`, `create_*_partition(y,m)` (8 szt.: audit_log, contact, plugin_invocation_log, contact_event, contact_transcription, contact_ai_summary, social_message, email_message) | `CREATE TABLE ... PARTITION OF` + `REVOKE ... FROM app_user` | n/d (DDL) | gdy partycja już istnieje: NOTICE „pomijam” i `SUCCESS` (idempotentnie, bez uprawnień do DDL); gdy trzeba ją utworzyć: `ERROR: permission denied for schema public` | E7 (pierwsza próba na istniejących partycjach nie udowadnia niczego; powtórka na `audit_log_2030_01`) |
| `rotate_{audit_log,contact,contact_event,contact_transcription,contact_ai_summary,plugin_invocation_log}_partitions()` (6), `drop_old_*_partitions(n)` (5) | tworzenie + `DROP TABLE` partycji | n/d | wymaga właściciela (DROP) — jak wyżej | [Z] nie testowane osobno; ta sama zasada właściciela |
| `set_tenant_context(uuid)` | `set_config(..., true)` | n/d | działa | używane przez `TenantAwareRepository` |

- [F] Java: `CampaignArchiveJobRepository` → `SELECT archive_completed_campaign_contacts()`; `CampaignArchiveRetentionRepository` → `purge_campaign_contact_archive(...)`; `PartitionMaintenanceRepository` → `create_next_month_partitions()` i `create_<t>_partition`; `CronFailureLogRepository` → `log_cron_failure`; **`PartitionScannerImpl` wykonuje `DROP TABLE IF EXISTS` bezpośrednio z Javy** (l. 179) — DDL poza funkcją SQL (do uwzględnienia w inwentarzu i w ocenie B).
- [F] Handler `EXCEPTION WHEN OTHERS` w `archive_completed_campaign_contacts` kończy się `RAISE`, więc wpis `ERROR` do `cron_log` jest cofany razem z transakcją (potwierdzone: po błędzie E10 `cron_log` puste) — to martwy handler opisany w DB-082; stąd potrzeba `log_cron_failure` wołanego z Javy w osobnej transakcji.
- [W] Pod rolą bez BYPASSRLS dwa typy awarii: (a) cichy `SUCCESS` z 0 (brak GUC na świeżym połączeniu), (b) błąd 22P02 (GUC `''`). Pierwszy jest groźny; oba znikają, jeśli job jawnie ustawia GUC per tenant albo działa jako rola omijająca RLS.

### 3.1 Konwersja na SECURITY DEFINER — co potwierdził eksperyment

Wrapper `sd_archive()` (SECURITY DEFINER, właściciel `admin_user` — istniejąca rola NOLOGIN z BYPASSRLS i `ALL` na tabelach), `REVOKE ALL FROM PUBLIC`, `GRANT EXECUTE` dla `app_rt`:

- [F] D1: wywołany przez `app_rt` BEZ GUC zarchiwizował kampanie obu tenantów (`cron_log`: `rows_affected = 6`, „Kampanie: 2, Rekordy: 6”), `current_user` po wywołaniu nadal `app_rt`. Czyli SECURITY DEFINER + właściciel z BYPASSRLS naprawia fałszywy `SUCCESS` bez żadnego GUC.
- [F] D5: rola bez GRANT dostaje `permission denied for function sd_archive` (działa REVOKE FROM PUBLIC).
- [F] D2 (pułapka): wrapper z `SET search_path = pg_catalog, public` (dokładnie wersja z BE-148 WP-3) wywołujący `create_audit_log_partition(2030,1)` → `ERROR: permission denied to create "pg_catalog.audit_log_2030_01"` — niekwalifikowany `CREATE TABLE` trafia do PIERWSZEGO schematu ścieżki, czyli `pg_catalog`. Po poprawce `SET search_path = public, pg_temp` partycja powstała w `public` i `app_user` nie ma na niej uprawnień (REVOKE zadziałał) — D2'.
- [F] D3 (wstrzyknięcie search_path): funkcja SECURITY DEFINER BEZ `SET search_path` (`sd_bad2`, właściciel superuser) — rola aplikacji utworzyła `CREATE TEMP TABLE cron_log`, wywołała funkcję; INSERT trafił do tabeli atakującego (`rows_in_attacker_temp = 1`). Dla właściciela bez uprawnień do cudzej tabeli temp (`admin_user`) ta sama ścieżka daje `permission denied` — dowód, że rozwiązanie nazwy poszło do `pg_temp`.
- [F] D3b: wrapper z `SET search_path = pg_catalog, public` (bez `pg_temp`) też jest podatny — `pg_temp` jest przeszukiwany JAKO PIERWSZY dla relacji, gdy nie ma go na liście (INSERT do `cron_log` poszedł do tabeli temp → `permission denied`). Z `SET search_path = public, pg_temp` atak nie działa (`temp_rows = 0`, `real_rows = 1`).
- [W] Poprawna deklaracja: `SET search_path = public, pg_temp` (pg_temp jawnie OSTATNI; `pg_catalog` i tak jest implicite pierwszy dla funkcji i operatorów). Wersja z ticketu (`pg_catalog, public`) jest błędna dwukrotnie: pozwala na shadowing przez `pg_temp` i psuje niekwalifikowany DDL. Dodatkowo wszędzie, gdzie definer woła `format('%I')`/`EXECUTE`, nazwy należy kwalifikować schematem.
- [W] Ryzyka SECURITY DEFINER: (1) właściciel = superuser to maksymalna eskalacja — właściciel musi być NOSUPERUSER z BYPASSRLS i minimalnymi GRANT-ami; (2) parametry (np. `p_tenant_id`, `cutoff`, `batch`) stają się powierzchnią ataku — skompromitowana aplikacja może wywołać purge dla dowolnego tenanta i dowolnej daty (dziś to samo może zrobić przez BYPASSRLS, ale nowy model miał to ograniczyć); (3) `GRANT EXECUTE ON ALL FUNCTIONS` z V012 i domyślne PUBLIC EXECUTE trzeba odwołać per funkcja; (4) funkcje DDL (`create_*_partition`) mogą być definerem tylko jeśli ich właściciel jest właścicielem tabel nadrzędnych.

---

## 4. Eksperyment — dokładne polecenia i wyniki

Środowisko: `docker run -d --name be148-scratch -e POSTGRES_PASSWORD=x postgres:16-alpine`; `docker exec cc-postgres pg_dump -U ccapp -d contact_center --schema-only --no-comments > schema.sql` (read-only); role w scratch: `ccapp` (superuser), `app_user`, `admin_user (bypassrls)`; baza `cc` odtworzona bez błędów (129 relacji public). Rola testowa:

```
create role app_rt login password 'rt' nosuperuser nobypassrls nocreatedb nocreaterole in role app_user;
```

Dane: 2 tenanty, po 1 kampanii `COMPLETED` sprzed 60 dni, po 3 wiersze `campaign_contact`, 2 `refresh_token` (1 wygasły), wpis `scheduled_job`. Skrypty uruchamiane jako `docker exec -i be148-scratch psql -U app_rt -d cc -X -q < plik.sql`. Wyniki w sekcjach 2.2 i 3; podsumowanie:

| # | test | wynik |
|---|---|---|
| whoami | `rolsuper, rolbypassrls` dla app_rt | f, f |
| E1 | świeża sesja, SELECT campaign / campaign_contact | 0 / 0 (GUC NULL) |
| E2 | INSERT campaign_contact bez GUC | błąd RLS (WITH CHECK) |
| E3 | `set_config(...,true)` T1 w tx | campaign 1, campaign_contact 3 |
| E4 | po COMMIT, ta sama sesja | GUC = `''`, SELECT → 22P02 |
| E5 | INSERT cron_log, `log_cron_failure` | OK (tabele globalne) |
| E6 | `cleanup_expired_refresh_tokens()` | OK, usunięto 1 token |
| E7 | `create_audit_log_partition(2030,1)`; `create table` | `permission denied for schema public` |
| E8 | purge bez GUC (świeża sesja) | zwraca 0, brak wpisu |
| E9 | `archive_completed_campaign_contacts()` bez GUC | `SUCCESS`, `Kampanie: 0, Rekordy: 0` (fałszywy sukces) |
| E10 | to samo na sesji z GUC `''` | błąd 22P02; `cron_log` puste |
| E11 | to samo z GUC T1 | `Kampanie: 1, Rekordy: 3`, tylko T1 |
| D1 | `sd_archive()` (definer, owner `admin_user`) bez GUC | 6 wierszy, oba tenanty |
| D2 / D2' | definer DDL z `pg_catalog, public` / z `public, pg_temp` | błąd w pg_catalog / partycja w public, REVOKE zadziałał |
| D3 / D3b / D3b' | shadowing temp `cron_log` | podatne bez SET i z `pg_catalog, public`; odporne z `public, pg_temp` |
| D4 | owner bez BYPASSRLS przy FORCE RLS | widzi 0 wierszy |
| D5 | wykonanie bez GRANT EXECUTE | `permission denied for function` |

Uwaga o rzetelności: przypadkowe wywołanie `sd_archive()` jako superuser (pomyłka w poleceniu D1b) zarchiwizowało dane w scratchu — dane przesiałem i powtórzyłem D2–D5; żywa baza nie była dotknięta. Eksperyment nie obejmuje: roli migracyjnej NOSUPERUSER będącej właścicielem tabel (w scratchu właścicielem jest superuser `ccapp`), `REASSIGN OWNED`, zachowania HikariCP/puli, `DROP TABLE` partycji pod definerem, Flyway pod nową rolą. Te punkty: **niezweryfikowane**.

---

## 5. Ocena wariantów od strony DB

Założenie wspólne [W]: nowa rola aplikacji `cc_app` (LOGIN, NOSUPERUSER, NOBYPASSRLS, NOCREATEDB, NOCREATEROLE, członek `app_user`) — wzorzec zweryfikowany w `app_rt` (grupa `app_user` daje grant-y tabelowe; `rolinherit = t`).

### Ownership i Flyway (dotyczy wszystkich wariantów)
- [F] Dziś Flyway = ta sama rola co aplikacja = `ccapp` = właściciel wszystkiego.
- [W] Najtańsze rozwiązanie: zostawić `ccapp` jako rolę migracyjną/owner (BEZ `REASSIGN OWNED`), a aplikację przenieść na nowy `cc_app`. Dla Flyway ustawić `spring.flyway.user/password` (osobne zmienne środowiskowe). Zalety: zero zmian własności ~800 obiektów, prosty rollback (powrót `DB_USERNAME` na `ccapp`), `ALTER DEFAULT PRIVILEGES` z V012 nadal działa (bo obiekty tworzy `ccapp`).
- [W] Wada: `ccapp` w local-demo jest bootstrap-superuserem — wszystko, co ma być „definerem z minimalnymi uprawnieniami”, byłoby superuserem. Jeśli właściciel chce prawdziwej separacji (definer NOSUPERUSER), konieczny `REASSIGN OWNED BY ccapp TO cc_owner` (jednorazowo, krótkie lock-i, wymaga okna) i powtórzenie `ALTER DEFAULT PRIVILEGES FOR ROLE cc_owner ...`. [Z] nie sprawdzałem, czy produkcja ma `ccapp` jako bootstrap-superusera (zależy od hostingu).
- [W] Rola migracyjna/owner musi mieć BYPASSRLS (sekcja 2.3), inaczej migracje DML na tabelach FORCE padną/zero-wierszowo.
- [W] `CREATE ROLE` z hasłem poza Flyway (krok operacyjny w DEPLOYMENT.md); w Flyway można tworzyć wyłącznie role NOLOGIN (precedens V012) i GRANT-y warunkowe (`IF EXISTS` na rolę), tak by migracja przechodziła także tam, gdzie `cc_app` jeszcze nie istnieje (CI, Testcontainers).
- [F] Partycjonowanie: `CREATE/DROP` partycji wymaga właściciela tabeli nadrzędnej (E7: `permission denied for schema public`; poza tym REVOKE w funkcjach `create_*_partition` też wymaga właściciela). Każdy wariant musi rozstrzygnąć DDL partycji.

### Wariant A — osobna rola „batch” z BYPASSRLS + osobny DataSource
- Migracje: 0–1 (REVOKE `flyway_schema_history` z `app_user`; ewentualne GRANT-y). Role z hasłem poza Flyway: `cc_app`, `cc_batch`.
- Aby `cc_batch` robiła DDL partycji, musi być właścicielem lub członkiem roli-właściciela (NOINHERIT + `SET ROLE` albo `INHERIT`); [W] to daje jej pełny zakres właściciela (DDL na wszystkich tabelach) — szerszy niż potrzeba dla jobów.
- Powierzchnia ataku: dwie role z BYPASSRLS/uprawnieniami; błąd routingu `DataSource` (bean `@Primary`/`JdbcTemplate`) = ciche użycie omijającej roli. Rollback: zmiana zmiennych + wyłączenie osobnego DS.
- Nie wymaga zmian funkcji SQL (najmniejsza zmiana kodu SQL), fałszywy `SUCCESS` znika, bo job działa z BYPASSRLS.

### Wariant B — SECURITY DEFINER
- Migracje: min. 2–3 (jedna: `ALTER FUNCTION ... SECURITY DEFINER SET search_path = public, pg_temp; OWNER TO <rola>; REVOKE ... FROM PUBLIC; GRANT EXECUTE ... TO cc_app` dla ok. 17 funkcji wejściowych jobów — archive, purge, cleanup_refresh, create_next_month_partitions, 6x rotate, 8x create_*_partition ewent. drop_old_*; druga: nowe funkcje dla DDL wykonywanego dziś z Javy (`DROP TABLE` w `PartitionScannerImpl`, DETACH/ATTACH jeśli są); trzecia: GRANT-y/REVOKE (`flyway_schema_history`)).
- Właściciel definera: NOSUPERUSER + BYPASSRLS + własność tabel (dla DDL). W praktyce wymaga `REASSIGN OWNED` (patrz wyżej) albo akceptacji superusera jako właściciela.
- Najmniejsze uprawnienia na poziomie aplikacji (rola aplikacji nie ma BYPASSRLS ani DDL), ale parametry funkcji to nowa powierzchnia (3.1 pkt 2). Każda funkcja wymaga testu na kolizję `search_path`. Rollback: migracja kompensacyjna (ALTER FUNCTION ... SECURITY INVOKER) lub powrót `DB_USERNAME` na `ccapp`.
- [W] `purge_campaign_contact_archive` nie potrzebuje definera, jeśli wołany jest per tenant z `set_config` (wariant C).

### Wariant C — iteracja per tenant z `set_config`
- Wykonalne bez BYPASSRLS: [F] lista tenantów z `tenant` (bez RLS, `app_user` ma SELECT; E-test `select count(*) from tenant` jako `app_rt` = 2).
- Migracje: min. 1 (nowa wersja `archive_completed_campaign_contacts(p_tenant_id uuid)` — obecna iteruje po wszystkich tenantach i przy GUC dla jednego tenanta zadziała tylko dla niego, E11; plus usunięcie gałęzi `DROP TABLE campaign_contact_<uuid>` z funkcji lub zostawienie jej jako no-op: [F] `campaign_contact` ma tylko partycję DEFAULT, więc gałąź jest dziś martwa).
- Nie rozwiązuje DDL partycji ani tabel bez `tenant_id` — dla nich wystarcza, że nie mają RLS (E5/E6), ale `create_*_partition` i `DROP TABLE` nadal wymagają właściciela.
- Koszt: N tenantów × transakcja; wymaga twardej reguły „GUC tylko tx-local (`set_config(...,true)`)” i obsługi poolowego `''` (każdy job musi sam ustawić kontekst przed pierwszym zapytaniem). Test fałszywego sukcesu: job porównuje liczbę widocznych kampanii/tenantów z oczekiwaną.

### Wariant mieszany (rekomendowany) — C dla jobów danych, osobny mechanizm dla DDL partycji
- C: archiwizacja kampanii i purge archiwum per tenant z `set_config` (rola `cc_app` bez żadnego wyjątku od RLS); `cleanup_expired_refresh_tokens`, `log_cron_failure` bez zmian (tabele bez RLS, tylko GRANT).
- DDL partycji (`create_next_month_partitions`, `create_*_partition`, `rotate_*`, `drop_old_*`, `DROP TABLE` z Javy): jedno z dwóch — (i) B wąsko: tylko funkcje DDL jako SECURITY DEFINER z właścicielem = właściciel tabel i `SET search_path = public, pg_temp`, parametry ograniczone do nazw z białej listy (Java dziś buduje `create_<t>_partition` z nazwy — zob. `PartitionMaintenanceRepository` l. 79, wymaga walidacji nazw); albo (ii) A wąsko: osobny `DataSource` ownera tylko dla `PartitionMaintenanceJob`/`PartitionReclaimJob`/`PartitionScanner`.
- Migracje: 2–3 (funkcja `archive...(p_tenant_id)`, definer-owanie funkcji DDL, REVOKE/GRANT porządkowy).
- Powód rekomendacji [W]: tylko operacje DDL naprawdę wymagają właściciela; operacje na danych dają się wykonać bez żadnego omijania RLS, więc kompromitacja puli aplikacji nie daje cross-tenant DELETE; wymaganie najmniejszych uprawnień spełnione bez duplikowania roli BYPASSRLS dla batch.

### Czy potrzebny osobny ticket DB?
[W] TAK. Próg z BE-148 (≥ 3 migracje/funkcje SQL) jest przekroczony w każdym wariancie poza czystym A: wariant mieszany to ≥ 2–3 migracje i ≥ 3 funkcje SQL (nowa `archive...(p_tenant_id)`, definer-owanie ok. 15 funkcji DDL, ewentualne nowe funkcje dla DDL z Javy). Proponuję nowy ticket DB (następny wolny numer, DB-083; migracje od V136 — numer sprawdzić w `flyway_schema_history` i na wszystkich gałęziach), który zależy tylko od decyzji z WP-1 (wg wymogu BE-148: bez cyklu zależności).

### Rollback i sekrety (wspólne)
- [W] Rollback = powrót `DB_USERNAME`/`DB_PASSWORD` na `ccapp`; migracje GRANT/ALTER FUNCTION są wstecznie zgodne z `ccapp` (superuser ignoruje wszystko), więc nie wymagają cofania; role `cc_*` nie są usuwane do końca obserwacji (zgodnie z R6 w BE-148).
- [W] Sekrety: hasła ról tworzone poza Flyway (DEPLOYMENT.md, krok operacyjny); `.env.local-demo` — nie ruszać bez zgody właściciela (incydent 2026-09-30).

---

## 6. Rekomendacja (do decyzji właściciela)

1. Zachować `ccapp` jako rolę migracyjną/owner (z BYPASSRLS), wprowadzić `cc_app` (NOBYPASSRLS, w `app_user`) dla aplikacji; osobne `spring.flyway.user/password`. Na tym etapie bez `REASSIGN OWNED`.
2. Joby danych (`archive_completed_campaign_contacts`, `purge_campaign_contact_archive`) przez C (per tenant, `set_config` tx-local), z wykrywaniem fałszywego `SUCCESS` w Javie (wynik 0 przy niezerowej liczbie kandydatów).
3. DDL partycji — osobna decyzja A-wąsko vs B-wąsko (patrz pytania); jeśli B, to koniecznie `SET search_path = public, pg_temp`, `REVOKE ... FROM PUBLIC`, `GRANT EXECUTE` tylko dla `cc_app`, właściciel NOSUPERUSER.
4. Poprawić zapis BE-148: (a) `search_path = pg_catalog, public` → `public, pg_temp` (obie wady udowodnione, D2/D3b); (b) `refresh_token` nie ma RLS (R3 nieaktualne); (c) `log_cron_failure` nie jest SECURITY DEFINER; (d) dodać `REVOKE` na `flyway_schema_history` dla `app_user`.
5. Założyć osobny ticket DB (kryterium progu spełnione).

## 7. Niezweryfikowane / ograniczenia

- [Z] Rola produkcyjna: nazwa i atrybuty (czy bootstrap-superuser) — znane tylko z potwierdzenia właściciela (BYPASSRLS/superuser).
- [Z] Zachowanie `REASSIGN OWNED` / nie-superuserowego ownera / Flyway pod nową rolą — nie testowane.
- [Z] Zachowanie `DROP TABLE` partycji i `DETACH` pod definerem — nie testowane.
- [Z] Zachowanie pod HikariCP (`''` na poolu) — odtworzone tylko na jednym połączeniu psql, nie na realnej puli.
- [Z] Pełna treść `ci.yml` nie czytana; inwentarz Javy poza wskazanymi wywołaniami SQL — zadanie backend-dev-expert.
- Scratch `be148-scratch` usunięty; żywa baza i repo (poza tym plikiem) bez zmian.

## 8. Pytania do właściciela

1. Czy produkcyjny `ccapp` (lub inna nazwa) jest bootstrap-superuserem klastra (np. `POSTGRES_USER` w kontenerze), czy rolą utworzoną ręcznie, którą można zdegradować/zastąpić? Czy produkcja to kontener, czy usługa zarządzana (RDS/Cloud SQL — tam role z BYPASSRLS bywają niedostępne)?
2. Czy zgadzasz się na wariant mieszany (C dla danych + wąski mechanizm dla DDL partycji), czy preferujesz A (osobny DataSource batch)?
3. DDL partycji: A-wąsko (osobny DataSource ownera) czy B-wąsko (SECURITY DEFINER)? Czy akceptujesz `REASSIGN OWNED` do roli NOSUPERUSER (okno serwisowe), czy właściciel zostaje superuserem?
4. Czy rola migracyjna może mieć BYPASSRLS (niezbędne dla migracji DML na tabelach FORCE)?
5. Czy mamy zgodę na osobny ticket DB-083 (migracje od V136) i na odwołanie uprawnień `app_user` do `flyway_schema_history`?
6. Czym jest rola `rls_probe` w żywej bazie i czy można ją usunąć (osobna decyzja, nie dotykałem)?
7. Czy w local-demo i CI ma być docelowo ta sama nowa rola `cc_app` (wymaga zmiany `.env.local-demo` po Twojej stronie), czy najpierw tylko Testcontainers (harness BE-139)?
