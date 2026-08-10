---
name: contact_center_project
description: Opis projektu Contact Center SaaS – kluczowe informacje architektoniczne i podjete decyzje
type: project
---

Projekt: Wielokanałowa platforma Contact Center w modelu SaaS (multi-tenant).
Stack: PostgreSQL 16 (operacyjna), ClickHouse (DW), Redis (cache/sesje), RabbitMQ (broker), Flyway (migracje), Debezium (CDC).
Backend: Java Spring Boot (modularny monolit Faza 1). Frontend: Angular SPA.

**Why:** PRD v1.0 z 2026-03-12. Faza 1 = MVP z kanałami PHONE/EMAIL/SOCIAL_MEDIA.

**How to apply:** Przy kolejnych zadaniach DB zakładaj że V001-V090 już istnieją (ostatnia: V090__fix_rls_guc_naming_inconsistency.sql, 2026-08-10 — DB-054, ostatni ticket EPIC-29 warstwy DB, 9/9 ukończone). Numery migracji kontynuuj od najwyższego istniejącego +1. Zawsze zweryfikuj `ls backend/src/main/resources/db/migration/` przed napisaniem nowej migracji — nie ufaj tej liczbie bezkrytycznie.

**DB-054/V090 (2026-08-10) — naprawa niespójności GUC RLS `app.tenant_id` → `app.current_tenant_id` na 4 tabelach, ostatni ticket EPIC-29 warstwy DB (DB-046..054, 9/9 ukończone).** Ticket typu S, oznaczony jako opcjonalny/poboczny — celowo wydzielony do własnego pliku migracji dla łatwej odwracalności. Naprawia błąd z V059 (`contact_event`)/V064 (`tenant_ai_config`)/V067 (`contact_transcription`)/V068 (`contact_ai_summary`), które ustawiały RLS z nieistniejącym GUC `app.tenant_id` zamiast faktycznie ustawianego przez `set_tenant_context()`/`TenantAwareRepository` `app.current_tenant_id` — RLS na tych 4 tabelach był de facto martwy (current_setting(...,TRUE) zwracał NULL, porównanie zawsze NULL/false), warstwa aplikacji (`assertSameTenant`) nadal chroniła, więc nie była to aktywnie eksploatowalna luka, tylko osłabione defense-in-depth. Zamyka też notatkę "Znana niekonsekwencja RLS" niżej w tym pliku — od V090 wszystkie 4 tabele używają poprawnego GUC, notatka jest już tylko historyczna.
- `DROP POLICY`/`CREATE POLICY` z tą samą nazwą na wszystkich 4 (`contact_event_tenant_isolation`, `tenant_ai_config_isolation`, `contact_transcription_isolation`, `contact_ai_summary_isolation`) — tylko zmiana GUC w `USING`, zero innych zmian struktury polityki.
- `ALTER TABLE tenant_ai_config FORCE ROW LEVEL SECURITY` — jedyna z czterech bez `FORCE` przed tą migracją (pozostałe trzy dostały `FORCE` już wcześniej przy swoich online-swap migracjach: `contact_event`/V085, `contact_transcription`/V086, `contact_ai_summary`/V087; `tenant_ai_config` nigdy nie przechodziła przez podobny zabieg, więc zaległość ciągnęła się od V064).
- **Decyzja: świadomie BEZ `WITH CHECK`** (minimalny, łatwo odwracalny diff — priorytet tego opcjonalnego ticketu to przewidywalność, nie ujednolicenie stylu z nowszymi tabelami typu `tenant_retention_policy`/DB-046, które mają jawny `WITH CHECK`). Zweryfikowano empirycznie że ochrona przy zapisie i tak działa: dla polityki domyślnej `ALL` bez jawnego `WITH CHECK`, Postgres używa `USING` również jako check (ten sam wzorzec potwierdzony już w DB-049/050/051) — cross-tenant INSERT poprawnie odrzucony testem na wszystkich 4 tabelach. Ujednolicenie stylu (dodanie `WITH CHECK` wszędzie) pozostaje otwarte jako osobna, przyszła migracja porządkowa.
- Weryfikacja `DO $$...RAISE EXCEPTION...$$` na końcu migracji (licznik polityk z GUC ≠ `app.current_tenant_id` i licznik tabel bez `FORCE` — oba muszą być 0, inaczej cała migracja robi ROLLBACK) — wzorzec z V085/086/087 zastosowany też tutaj, mimo że to najprostsza z migracji tego epiku.
- Test manualny pod `SET ROLE app_user`, `SAVEPOINT`/`ROLLBACK TO SAVEPOINT` per przypadek, na wszystkich 4 tabelach: izolacja (tenant B 0 wierszy tenanta A), cross-tenant INSERT odrzucony. `tenant_ai_config` ma `UNIQUE(tenant_id)` i w dev-seedzie tylko tenant A ("KMN Software") ma wiersz — test insertu własnego tenanta wykonany dla tenanta B (który dotąd nie miał wiersza), nie dla A (kolidowałoby z unique). Zero wyciekłych wierszy testowych po `ROLLBACK`, potwierdzone `COUNT(*)` po migracji (`tenant_ai_config`=1, czyli tylko oryginalny dev-seed).
- Sposób aplikacji: metoda z V082-089 (`RunFlyway.java` lokalnie skompilowany, jary z `~/.m2`: flyway-core/flyway-database-postgresql 10.20.1, postgresql 42.7.4, jackson 2.17.2, `-Duser.timezone=UTC`), IP bridge `cc-postgres` **znów `172.18.0.11:5432`** (ta sama wartość co w sesjach V082-089 — sieć Dockera nie była restartowana). Dry-run w transakcji z jawnym `ROLLBACK` (`docker cp` + `psql -f` wrapowany `BEGIN;...ROLLBACK;`) wykonany przed prawdziwym uruchomieniem — zero błędów za pierwszym razem, jak w poprzednich migracjach tego epiku.

**DB-053/V089 (2026-08-10) — 4 nowe indeksy `(tenant_id, kolumna_czasowa)` pod przyszły purge per-tenant.** Ticket typu S, czysta migracja DDL (4× `CREATE INDEX IF NOT EXISTS` + `COMMENT ON INDEX`), audyt istniejących indeksów z treści ticketu potwierdzony 1:1 przez `\d` przed implementacją:
- `idx_contact_tenant_started_at (tenant_id, started_at)` na `contact` — DODANY.
- `contact_event` — POMINIĘTY świadomie (już ma `idx_contact_event_tenant (tenant_id, started_at DESC)` od V059), udokumentowane blokiem komentarza w migracji zamiast DDL.
- `idx_contact_transcription_tenant_created (tenant_id, created_at)` na `contact_transcription` — DODANY.
- `idx_contact_ai_summary_tenant_generated (tenant_id, generated_at)` na `contact_ai_summary` — DODANY, kolumna `generated_at` (nie `created_at`) zgodnie z decyzją DB-051/V087.
- `idx_cca_tenant_archived_at (tenant_id, archived_at)` na `campaign_contact_archive` (tabela NIE partycjonowana, zwykła) — DODANY.

**Decyzja CONCURRENTLY vs zwykły CREATE INDEX:** świadomie NIE użyto `CONCURRENTLY` — Flyway w tym repo domyślnie wykonuje każdą migrację w jednej transakcji, a `CREATE INDEX CONCURRENTLY` nie może działać wewnątrz transakcji; żeby go użyć trzeba by wyłączyć transakcyjność TEJ migracji (Flyway ≥7: plik konfiguracyjny per-migracja `executeInTransaction=false`), wzorzec dziś nigdzie w tym repo nieużywany (zweryfikowane grepem — zero trafień na `CREATE INDEX CONCURRENTLY` w całym katalogu migracji, tylko wzmianki w komentarzach V085/V088 jako świadomie odłożona kwestia). Przy obecnym wolumenie (dev) SHARE lock na czas budowy indeksu jest niezauważalny. Rekomendacja dla produkcji przy dużym wolumenie `contact`/`contact_transcription` zapisana w nagłówku V089: wykonać `CREATE INDEX CONCURRENTLY` na tabeli nadrzędnej (PG11+ wspiera to bezpośrednio na partycjonowanej tabeli, buduje na każdej partycji) jako osobny, ręczny krok DBA POZA Flyway, nie jako kolejną migrację w katalogu.

**Ważne odkrycie metodologiczne o weryfikacji EXPLAIN na małych partycjach dev (do reużycia w przyszłych tickietach indeksowych):** przy ≤~100-360 wierszach w partycji planner PRAWIDŁOWO wybiera Seq Scan zamiast nowego indeksu — to nie błąd migracji, tylko poprawna kalkulacja kosztu dla danych mieszczących się w 1 stronie. Żeby uczciwie zweryfikować że indeks JEST wybierany przy realistycznym wolumenie, trzeba zasymulować dane w transakcji z `ROLLBACK` na końcu (ten sam wzorzec, który ticket DB-053 explicite autoryzował dla pustej `campaign_contact_archive`, tu rozszerzony na pozostałe 3 tabele). KLUCZOWA pułapka odkryta w tej sesji: symulacja z tylko 2 tenantami (odzwierciedlająca dev-seed) daje ~50% selektywność `tenant_id`, przy której Seq Scan pozostaje POPRAWNIE tańszy niż indeks (nie zmienia tego nawet `ANALYZE` po insertach ani 100k wierszy) — to NIE dowodzi że indeks jest bezużyteczny, tylko że test nie odzwierciedla rzeczywistości. Dopiero symulacja z ~100 syntetycznymi tenant_id (`gen_random_uuid()`, selektywność ~1% na tenanta — realistyczna skala wielotenantowego SaaS, gdzie `RetentionPurgeService` faktycznie będzie odpytywać) pokazała `Bitmap Index Scan` na nowym indeksie `contact`. `contact_transcription`/`contact_ai_summary` pokazały `Bitmap Heap Scan` już przy 2 tenantach/100k wierszy (mniej kolumn/węższy wiersz niż `contact` — próg selektywności niższy). Zasada na przyszłość: przy weryfikacji EXPLAIN dla tabel wielotenantowych z małym dev-seedem (dziś tylko 2 tenanty: `680dc6bb-2bbd-4174-9bfe-2679d058327c`/"KMN Software" i `dd4b5c16-c39d-4cdb-b1ca-2bbc71a4a7f8`/"Kampania Handlowa"), zawsze symuluj DZIESIĄTKI syntetycznych tenant_id, nie tylko 2 realne — inaczej wynik testu jest mylący.

**Odkrycie o `contact.tenant_id`:** BRAK FK do `tenant(tenant_id)` (zweryfikowane `pg_constraint`) — w odróżnieniu od `contact_event`/`campaign_contact_archive`, które MAJĄ FK do `tenant`. Przydatne przy przyszłych testach wymagających insertu wielu syntetycznych tenant_id bez tworzenia realnych wierszy w `tenant`.

**Metoda aplikacji Flyway zaktualizowana (KOREKTA wcześniejszej notatki w [[feedback_flyway_manual_timezone]]):** polecenie `mvn org.flywaydb:flyway-maven-plugin:10.20.1:migrate` wywołane ad-hoc z CLI (bez `<plugin>` zadeklarowanego w żadnym `pom.xml` tego repo — zweryfikowane grepem, zero trafień) KOŃCZY SIĘ BŁĘDEM `FlywayException: No database found to handle jdbc:postgresql://...` — plugin nie ma na swoim classpath ani sterownika `postgresql`, ani `flyway-database-postgresql` (to są zależności modułu `app`, nie pluginu). Jedyna faktycznie działająca metoda w tym repo to `RunFlyway.java` (mały plafram Javy, `Flyway.configure().dataSource(...).locations(...).load()`, kompilowany lokalnie `javac -cp <jary z ~/.m2>`, uruchamiany `java -Duser.timezone=UTC -cp ".:<jary>" RunFlyway`) — jary: `flyway-core`, `flyway-database-postgresql`, `postgresql` (driver), `jackson-databind`/`jackson-core`/`jackson-annotations` (wszystkie w wersji z `backend/app/pom.xml`, już obecne w `~/.m2/repository` na tym hoście). Zasada `-Duser.timezone=UTC` z [[feedback_flyway_manual_timezone]] nadal obowiązuje, tylko przekazywana jako `-D` flaga bezpośrednio do `java`, nie przez `MAVEN_OPTS` (bo `mvn` w tej metodzie w ogóle nie jest już używany do uruchomienia migracji).

**DB-052/V088 (2026-08-09) — naprawa rotacji partycji `contact`/`audit_log` + rozszerzenie na `contact_event`/`contact_transcription`/`contact_ai_summary`.** Dwie części w jednym pliku:
- **Część A (naprawa realnych danych, nie test):** backfill partycji `contact_2026_06..09` i `audit_log_2026_06..09` (funkcje V007/V004) + przeniesienie z `*_default` (122 wierszy contact, 287 audit_log — oba `*_default` puste po migracji, 0 wierszy poza obsłużonym zakresem). Zero utraty danych (556/1215 niezmienione).
- **Część B:** nowe `create_<table>_partition`/`drop_old_<table>_partitions(p_retention_months INT DEFAULT 120)`/`rotate_<table>_partitions()` dla 3 tabel wzorcem 1:1 z `plugin_invocation_log` (V077); `create_next_month_partitions()` rozszerzona o 3 nowe `PERFORM`; 3 nowe wpisy `scheduled_job` (cron `15/30/45 3 1 * *`, tuż po `rotate_contact_partitions` 03:00). Retention 120 mies. (max CHECK w `tenant_retention_policy`) świadomie wybrany jako bezpieczny backstop — realna retencja per-tenant to `RetentionPurgeService`/BE-113 na poziomie wiersza.

**PUŁAPKA #1 (PostgreSQL, nie specyficzna dla tego repo) — nie da się utworzyć nowej partycji RANGE, jeśli partycja DEFAULT ma już wiersze pasujące do nowego zakresu** (`ERROR: updated partition constraint for default partition would be violated by some row`). Rozwiązanie: `ALTER TABLE <t> DETACH PARTITION <t>_default;` → utworzyć nowe partycje zakresowe → przenieść pasujące wiersze z odłączonej (teraz zwykłej) tabeli do rodzica (routing trafia poprawnie) → `ALTER TABLE <t> ATTACH PARTITION <t>_default DEFAULT;` na koniec. Wzorzec zweryfikowany empirycznie w V088, zastosuj przy każdym przyszłym "backfill partycji do tabeli z niepustym default".

**PUŁAPKA #2 (KRYTYCZNA, kosztowała ~1h debugowania w DB-052) — ręczne stosowanie migracji przez `flyway-maven-plugin` z hosta (bridge IP) używa strefy czasowej JVM hosta, NIE UTC.** PGJDBC domyślnie synchronizuje sesję Postgresa (`SET TimeZone`) ze strefą `java.util.TimeZone.getDefault()` klienta — a maszyna hosta tego repo ma strefę `Europe/Warsaw` (+01/+02 w zależności od DST), podczas gdy WSZYSTKIE granice partycji w tej bazie są tworzone w UTC (tak łączy się `cc-backend`, kontener ma `TZ=UTC` — zweryfikowane `docker exec cc-backend date`). Skutek: literały dat typu `'2026-06-01'` rzutowane na `TIMESTAMPTZ` wewnątrz `EXECUTE format(...)` w funkcjach `create_*_partition` dostają PRZESUNIĘTĄ o 1-2h granicę względem istniejących (UTC-owych) partycji → `ERROR: partition "..." would overlap partition "..."`, mimo że IDENTYCZNA migracja przechodzi bezbłędnie przez `docker exec cc-postgres psql` (bo psql w kontenerze dziedziczy strefę serwera/kontenera = UTC). Ani `?options=-c%20TimeZone=UTC` w JDBC URL, ani `SET TimeZone` w `-c` startup option NIE pomaga — PGJDBC nadpisuje to własnym `SET TimeZone` po connect. **Jedyny działający fix:** `MAVEN_OPTS="-Duser.timezone=UTC" mvn ...` (wymusza `user.timezone` na poziomie JVM PRZED uruchomieniem PGJDBC). Diagnostyka: tymczasowy `RAISE EXCEPTION` z `current_setting('TimeZone')` wewnątrz migracji ujawnił `Europe/Warsaw` zamiast `UTC`. **Zastosuj `MAVEN_OPTS="-Duser.timezone=UTC"` przy KAŻDYM przyszłym ręcznym `flyway-maven-plugin:migrate` w tym repo, nie tylko gdy migracja dotyka dat/partycji** — inaczej ryzyko cichych, trudnych do zdiagnozowania niezgodności stref w danych TIMESTAMPTZ zapisywanych podczas samej migracji (np. `DEFAULT NOW()`).

**Konsolidacja DETACH/CREATE/INSERT/ATTACH w jeden blok `DO $$ ... $$` per tabela** (zamiast osobnych top-level statementów) okazała się ślepym tropem przy diagnozowaniu Pułapki #2 (nie była przyczyną błędu — przyczyną była strefa czasowa), ale została zachowana w finalnym V088 jako bardziej atomowy/czytelny wzorzec (DETACH/ATTACH przez dynamiczny `EXECUTE` wewnątrz bloku, żadnych pośrednich round-tripów klienta). Nie jest to wymagane do poprawności — samodzielne top-level statementy też działają poprawnie pod UTC — ale warto utrzymać ten wzorzec przy podobnych "online backfill" migracjach w przyszłości dla czytelności i pojedynczego miejsca weryfikacji (`RAISE EXCEPTION`/`RAISE WARNING` na końcu bloku).

**Brak infrastruktury testów Testcontainers/H2 dla migracji SQL w tym repo** (potwierdzone przy DB-046/V082): `application-test.yml` ma `spring.flyway.enabled=false` i `ddl-auto=none`, `ContactCenterApplicationIT` jawnie wyłącza DataSource/Flyway/JPA autoconfigurację ("brak prawdziwej bazy w unit testach", TODO na Testcontainers nieodhaczone). Repozytoria typu `TenantTwilioConfigRepositoryTest` testują logikę Java przez Mockito (mockowany `EntityManager`), NIE prawdziwe RLS/CHECK/UNIQUE na żywej bazie. Ustalony w tym repo wzorzec weryfikacji migracji SQL-only (bez towarzyszącej encji JPA w tym samym tickecie, np. DB-043..046) to WYŁĄCZNIE manualny test przez psql pod `SET ROLE app_user` z `SAVEPOINT`/`ROLLBACK TO SAVEPOINT` per przypadek (zob. [[feedback_rls_testing]]) — żaden plik testu Java nie jest commitowany dla takich tabel (potwierdzone w historii git dla V074-081, brak plików `*Test.java` towarzyszących). Jeśli przyszły ticket poprosi o "test weryfikujący RLS/constrainty" dla tabeli bez encji JPA w zakresie, ten manualny skrypt psql jest właściwym, zgodnym z konwencją repo rozwiązaniem — nie wymuszać sztucznego testu Java/Mockito bez obiektu do mockowania.

KRYTYCZNA PUŁAPKA — nazwy PK w tym projekcie NIE są `id`. Konwencja: `{tabela}_id`:
- tenant.tenant_id (nie id)
- queue.queue_id (nie id)
- campaign.campaign_id (nie id)
- app_user.user_id (nie id)
Zawsze sprawdzaj \d <tabela> przez psql zanim napiszesz FK. Logowanie do bazy: user=ccapp, db=contact_center (nie postgres/contact_center_dev).

Kluczowe decyzje architektoniczne:
- Izolacja logiczna przez tenant_id (nie osobne schematy/bazy) + RLS jako dodatkowa warstwa
- Partycjonowanie RANGE miesięczne: tabele CONTACT i AUDIT_LOG
- Partycjonowanie LIST po campaign_id: tabela CAMPAIGN_CONTACT
- Soft delete: is_deleted BOOLEAN na CUSTOMER i APP_USER
- UUID v4 (uuid-ossp) jako klucze główne we wszystkich tabelach
- Fuzzy search: pg_trgm trigram indexes na CUSTOMER (first_name || last_name)
- JSONB dla: phone[], email[], skills[], custom_fields, config, gdpr_consent, IVR definition
- Tabela APP_USER (nie "user" – słowo zarezerwowane w PostgreSQL)

Znane pułapki i poprawki:
- V003 linia 172: indeks idx_refresh_token_cleanup na refresh_token(expires_at, is_revoked) — usunięto predykat WHERE z NOW() (STABLE, nie IMMUTABLE). Indeks jest kompozytowy bez predykatu; pg_cron filtruje warunki po stronie zapytania.
- V007 linia 176 (naprawiono 2026-03-13): started_at::DATE na kolumnie TIMESTAMPTZ w wyrażeniu indeksowym — STABLE, nie IMMUTABLE (wynik zależy od TimeZone GUC). Zamieniono na zwykłą kolumnę started_at w indeksie. Reguła ogólna: nigdy nie używaj ::DATE, AT TIME ZONE, DATE_TRUNC w wyrażeniach indeksowych na kolumnach timestamptz.
- V011 linia 116 i 126 (naprawiono 2026-03-13): te same błędy co V007 — dwa indeksy z (started_at::DATE) DESC. Zamieniono na started_at DESC bez rzutowania.

Znana niekonsekwencja RLS (HISTORYCZNA, NAPRAWIONA w DB-054/V090, 2026-08-10):
- Konwencja ustalona w V012 i utrwalona w V042/V048/V051/V070: current_setting('app.current_tenant_id', TRUE)::UUID
- V059 (contact_event), V064 (tenant_ai_config), V067 (contact_transcription), V068 (contact_ai_summary) używały app.tenant_id (bez current_) — NAPRAWIONE w V090, wszystkie 4 tabele mają dziś poprawny GUC.
- Zawsze pisz nowe polityki RLS z app.current_tenant_id — to jest to, co faktycznie ustawia aplikacja.

Dokumentacja DB napisana 2026-06-12: /home/pawelm/contact-center/documentation/tech/06-database.md
- Pokrywa wszystkie 73 migracje (V001-V073), konwencje Flyway, RLS, mapę schematu per domena, ERD mermaid, wzorce (soft-delete/audit/wersjonowanie/JSONB/enum->varchar/partycjonowanie), anti-pattern overloaded columns, krok-po-kroku jak dodać tabelę.

Lokalizacja migracji:
- PostgreSQL: D:\CloudeAI\contact-center-demo\backend\src\main\resources\db\migration\
- Seed DEV: D:\CloudeAI\contact-center-demo\backend\src\main\resources\db\seed\V999__dev_seed.sql
- ClickHouse DW: D:\CloudeAI\contact-center-demo\dw\migrations\

Stan migracji po V035 (2026-04-08):
- V034__add_error_status_to_campaign_contact.sql: status ERROR dla campaign_contact
- V035__contact_search_indexes.sql (DB-022): indeksy wyszukiwania kontaktów dla EPIC-12 Raporty > Kontakty
  - idx_contact_queue_date: (tenant_id, queue_id, started_at) – filtrowanie po kolejce i zakresie dat (BE-036)
  - idx_contact_duration: (tenant_id, duration_seconds) WHERE duration_seconds IS NOT NULL – filtrowanie po czasie trwania (BE-036)
  - Oba z CREATE INDEX IF NOT EXISTS; propagują do partycji automatycznie (PostgreSQL 11+)
  - Odblokowano: BE-036 GET /api/contacts z filtrami queueId/dateFrom/dateTo/durationMin/Max

Stan migracji po V085 (2026-08-09):
- V085__partition_contact_event.sql (DB-049, EPIC-29 — CZWARTY ticket implementacyjny epiku, PIERWSZA online migracja partycjonująca JUŻ ISTNIEJĄCĄ tabelę z danymi w tym repo, w odróżnieniu od DB-046/047/048 i V077, gdzie tabele były tworzone od zera lub partycjonowane od CREATE TABLE). `contact_event` (zwykła tabela od V059/DB-035, 857 wierszy w dev) → RANGE-partycjonowana po `started_at`, PK złożony `(event_id, started_at)`.
  - **Wzorzec online swap:** `contact_event_new` (PARTITION BY RANGE) → `INSERT...SELECT` (jawna lista kolumn, nie `SELECT *`) → `RENAME` (`contact_event`→`contact_event_old`, `contact_event_new`→`contact_event`) → odtworzenie indeksów (budowane PO insercie, szybciej niż w trakcie ładowania)/trigera/RLS/COMMENT na przemianowanej tabeli → weryfikacja COUNT(*) blokiem `DO $$...RAISE EXCEPTION...$$` (przy niezgodności cała migracja robi ROLLBACK — Flyway na PostgreSQL domyślnie jedna transakcja) → `DROP TABLE contact_event_old` dopiero po pozytywnej weryfikacji.
  - **Ważne odkrycie o nazewnictwie obiektów podczas online-swap (do reużycia w DB-050/DB-051, analogiczne migracje `contact_transcription`/`contact_ai_summary`):** PK/UNIQUE/indeksy tworzą własną relację w `pg_class` (backing index) — ich nazwa musi być unikalna w CAŁYM schemacie, więc dopóki stara tabela istnieje pod nazwami `pk_contact_event`/`idx_contact_event_contact`/`idx_contact_event_tenant`, nowa tabela musi tymczasowo użyć nazw z sufiksem `_new`, a dopiero PO `DROP TABLE contact_event_old` (gdy nazwy się zwalniają) zrobić `ALTER TABLE ... RENAME CONSTRAINT` / `ALTER INDEX ... RENAME TO` do finalnych, konwencyjnych nazw. Natomiast CHECK constraints, triggery i RLS policies NIE mają tego problemu — ich nazwa jest unikalna tylko per-tabela (potwierdzone empirycznie: dwie różne tabele mogą mieć identycznie nazwany CHECK/trigger/policy jednocześnie bez błędu), więc mogą dostać finalną nazwę od razu przy tworzeniu nowej tabeli, bez żadnego late-rename. Zasada ogólna: przy każdym przyszłym online-swap tabeli z PK/indeksami — zawsze temp-suffix dla PK/index, finalna nazwa od razu dla CHECK/trigger/policy.
  - Nazwy partycji nadane FINALNIE od razu (`contact_event_2026_05`..`contact_event_2026_10` + `contact_event_default`, bez `_new` w nazwie) — bo partycje to niezależne relacje, nie kolidują z niczym istniejącym, i przyszła funkcja `create_contact_event_partition()` z DB-052/V088 rozpozna je jako już istniejące po dokładnie takiej nazwie (wzorzec idempotentnego `SELECT FROM pg_tables WHERE tablename=...` z V077) — gdyby partycje zostały tymczasowo nazwane z `_new`, DB-052 próbowałby tworzyć duplikaty z nakładającym się zakresem i migracja by się wysypała.
  - Zakres partycji: istniejące dane (2026-05-14..2026-08-04, zweryfikowane `MIN/MAX(started_at)` przed pisaniem migracji) + bieżący miesiąc (2026-08, już pokryty przez zakres danych) + 2 kolejne (2026-09, 2026-10) + DEFAULT.
  - **Funkcje rotacji partycji (`create_contact_event_partition`/`drop_old_...`/`rotate_...`, wpis `scheduled_job`, rozszerzenie `create_next_month_partitions()`) ŚWIADOMIE NIE utworzone w tej migracji** — to zakres DB-052 (V088), który dodaje ten mechanizm naraz dla 3 nowych tabel partycjonowanych (`contact_event`, `contact_transcription`, `contact_ai_summary`). Potwierdzone czytaniem treści DB-052 wprost przed pisaniem V085 — kryteria akceptacji DB-049 nie wymagają tych funkcji, tylko strukturę partycjonowania + DEFAULT.
  - RLS: GUC pozostał **`app.tenant_id`** (świadomie NIE naprawiony — identycznie jak oryginalna V059), ale dodano `FORCE ROW LEVEL SECURITY` (dzisiejsza, nie partycjonowana tabela go nie miała — `relforcerowsecurity=f` przed migracją, `t` po). To jedyne świadome zaostrzenie bezpieczeństwa w tym tickecie. Naprawa GUC (razem z `tenant_ai_config`/V064, `contact_transcription`/V067, `contact_ai_summary`/V068 — te same 4 tabele z tym samym błędem) wydzielona do DB-054/V090.
  - Test manualny pod `SET ROLE app_user`, `SAVEPOINT`/`ROLLBACK TO SAVEPOINT` per przypadek: trigger `fn_contact_event_on_update` (funkcja tabelo-agnostyczna z V059, nieodtwarzana — tylko `CREATE TRIGGER` wskazujący na nią; `UPDATE ended_at` → `duration_seconds` poprawnie przeliczone), oba CHECK (`chk_contact_event_stage`/`chk_contact_event_times` — odrzucone poprawnie), izolacja RLS (tenant B 0 wierszy tenanta A), cross-tenant INSERT odrzucony (mimo że `CREATE POLICY` nie ma jawnego `WITH CHECK` — dla polityki domyślnej `ALL` bez jawnego `WITH CHECK`, Postgres używa `USING` również jako check przy zapisie, potwierdzone empirycznie błędem "new row violates row-level security policy").
  - **Przed napisaniem migracji zweryfikowano dry-run w transakcji z jawnym `ROLLBACK`** (`BEGIN; <cała migracja>; ROLLBACK;` przez `psql -f`) — dopiero po czystym przejściu uruchomiono naprawdę przez Flyway. Dobra praktyka do powtórzenia przy DB-050/DB-051 (też online-swap z realnymi danymi) — pozwala złapać błędy składni/nazewnictwa bez ryzyka zostawienia bazy w połowie migracji.
  - Sposób aplikacji: metoda z V082/083/084 (lokalny `RunFlyway.java`, JDK 21 + jary z `~/.m2` już obecne na hoście z poprzednich sesji, IP bridge `cc-postgres` **to samo `172.18.0.11:5432`** co w sesji V083/V084 — sieć Dockera nie była restartowana między sesjami). `MigrateResult.migrations[].state` nie istnieje w Flyway 10.20.1 API (kompilacja się wysypała) — użyto tylko `.version`/`.description`.
  - Blokuje DB-052 (V088, rotacja partycji dla tej i 2 kolejnych tabel), DB-053 (V089, indeksy purge), BE-117 (`ContactEvent` → `@IdClass` w Javie, poza zakresem tego ticketu — czysta migracja SQL).

Stan migracji po V086 (2026-08-09):
- V086__partition_contact_transcription.sql (DB-050, EPIC-29 — PIĄTY ticket implementacyjny epiku,
  DRUGA online migracja partycjonująca już istniejącą tabelę z danymi, po V085/contact_event/DB-049,
  ten sam wzorzec zastosowany 1:1). `contact_transcription` (zwykła tabela od V067, 50 wierszy w dev,
  zakres created_at 2026-05-24..2026-07-29) → RANGE-partycjonowana po `created_at`, PK złożony
  `(transcription_id, created_at)`.
  - **Prostsza niż V085/contact_event:** brak FK (tenant_id/contact_id bez REFERENCES, potwierdzone
    `\d` przed migracją), brak triggera, brak CHECK constraints — tylko PK, jeden indeks wtorny
    (`idx_contact_transcription_contact (contact_id, tenant_id)`, kolejność kolumn zachowana
    DOKŁADNIE 1:1, świadomie nie "ulepszana" — nowy `(tenant_id, created_at)` dochodzi osobno w
    DB-053) i RLS. Zero rename FK w kroku porządkowania nazw (sekcja 8), bo go nie było.
  - **Nazwa PK zachowana jako auto-generowana `contact_transcription_pkey`** (nie `pk_contact_...`)
    — bo oryginalna V067 nie nadała PK jawnej nazwy CONSTRAINT (inline `PRIMARY KEY DEFAULT
    gen_random_uuid()`), więc backing index nowej tabeli auto-nazwał się
    `contact_transcription_new_pkey`, przemianowany na końcu na `contact_transcription_pkey` (bez
    prefiksu `pk_`) dla zgodności z pierwotną konwencją tej konkretnej tabeli — w odróżnieniu od
    `contact_event`, gdzie oryginalny PK MIAŁ jawną nazwę `pk_contact_event` i tak też został
    odtworzony. Zasada na przyszłość (do DB-051/contact_ai_summary): zawsze sprawdź `\d <tabela>`
    PRZED migracją, żeby wiedzieć czy PK ma jawną nazwę `pk_...` czy auto-nazwę `..._pkey`, i
    odtworzyć dokładnie ten sam styl nazewnictwa, nie ujednolicać na siłę.
  - `transcription_id` default zachowany jako `gen_random_uuid()` (nie `uuid_generate_v4()` jak w
    `contact_event`/`event_id`) — 1:1 z oryginałem V067, obie funkcje współistnieją w tej bazie
    (pgcrypto + uuid-ossp), nie ujednolicano.
  - Partycje: `contact_transcription_2026_05`..`_2026_07` (zakres danych) + `_2026_08` (bieżący) +
    `_2026_09`, `_2026_10` (2 kolejne) + `contact_transcription_default` — te same miesiące co
    V085, bo aplikowane tego samego dnia (2026-08-09).
  - RLS: GUC pozostał **`app.tenant_id`** (świadomie NIE naprawiony, jak V085), dodano
    `FORCE ROW LEVEL SECURITY` (`relforcerowsecurity=f`→`t`, jedyne świadome zaostrzenie).
  - Dry-run w transakcji z jawnym `ROLLBACK` (`psql -f` z wrapperem `BEGIN;...ROLLBACK;`) wykonany
    przed prawdziwym uruchomieniem przez Flyway — powtórzenie dobrej praktyki z V085, zero błędów
    za pierwszym razem.
  - Test manualny pod `SET ROLE app_user`, `SAVEPOINT`/`ROLLBACK TO SAVEPOINT`: izolacja RLS
    (tenant A 50/50 własnych, tenant B 0), cross-tenant INSERT odrzucony (bez jawnego `WITH CHECK`,
    `USING` użyty też jako check dla polityki `ALL`), insert własnego tenanta zaakceptowany.
    **Pułapka w skrypcie testowym:** `\gset` na `SELECT contact_id FROM contact WHERE tenant_id=<B>`
    zwrócił "no rows returned" — tenant B (Kampania Handlowa) nie ma żadnych wierszy w `contact` w
    tym dev seedzie. Że `contact_transcription` NIE ma FK do `contact`, test cross-tenant insert
    naprawiono użyciem dowolnego losowego UUID jako `contact_id` zamiast prawdziwego — zadziałało
    bez błędu FK (potwierdza brak FK). Zanotować: przy pisaniu testów dla tabel bez FK do `contact`,
    nie zakładać że `contact` ma dane dla obu tenantów dev-seed — zawsze sprawdzić przed `\gset`.
  - Sposób aplikacji: metoda z V085 (`RunFlyway.java` lokalnie na hoście, jary z `~/.m2`, IP bridge
    `cc-postgres` **to samo `172.18.0.11:5432`** co w sesji V085 — sieć Dockera nie była
    restartowana między sesjami tego samego dnia).
  - Blokuje DB-052 (V088, rotacja partycji dla tej i 2 pozostałych tabel), DB-053 (V089, indeksy
    purge), BE-117 (`ContactTranscriptionRepository` → dodanie `created_at` do adresowania wiersza
    po PK w Javie, poza zakresem tego ticketu — czysta migracja SQL, tabela nie ma encji JPA).
  - Równolegle inny agent implementował DB-051 (`V087__partition_contact_ai_summary.sql`) tego
    samego dnia — osobny plik, zero konfliktu, nie koordynowano.

Stan migracji po V087 (2026-08-09):
- V087__partition_contact_ai_summary.sql (DB-051, EPIC-29 — SZÓSTY i OSTATNI ticket
  partycjonujący z tej trójki (po V085/contact_event/DB-049 i V086/contact_transcription/DB-050),
  ten sam wzorzec online-swap zastosowany 1:1). `contact_ai_summary` (zwykła tabela od V068, 57
  wierszy w dev) → RANGE-partycjonowana po **`generated_at`** (NIE `created_at` — jedyna z tej
  trójki tabel, gdzie ticket explicite delegował wybór kolumny partycjonującej do
  db-schema-architect), PK złożony `(ai_summary_id, generated_at)`.
  - **Decyzja `generated_at` vs `created_at` — uzasadnienie zapisane w nagłówku V087 i w
    TASKS-DATABASE.md:** `generated_at` to moment faktycznego wygenerowania treści podsumowania
    przez model AI — biznesowo istotny "wiek" danych, analogicznie do `started_at` w
    `contact_event`/`contact`. `created_at` jest wyłącznie technicznym znacznikiem zapisu wiersza
    do bazy. Przyszłe polityki retencji/purge (DB-052/DB-053) mają sens liczone od momentu
    wygenerowania treści, nie od przypadkowego opóźnienia zapisu. W dev obie kolumny są sobie
    bliskie co do dnia (`MIN/MAX(generated_at)`=2026-05-24..2026-07-29,
    `MIN/MAX(created_at)`=2026-05-25..2026-07-29) — wybór nie zmienił liczby/zakresu partycji w tej
    migracji, tylko poprawność semantyczną przyszłych zapytań/purge. Zasada na przyszłość: gdy
    tabela ma DWIE kolumny czasowe (jedna = moment zdarzenia biznesowego, druga = moment zapisu do
    bazy), partycjonuj po tej pierwszej — `contact_transcription`/V086 partycjonowało po
    `created_at` właśnie dlatego, że nie miało odpowiednika `generated_at`.
  - **Brak FK w ogóle** (potwierdzone zapytaniem do `pg_constraint` przed migracją — jedyny
    constraint na tabeli to PK) — w odróżnieniu od `contact_event` (miała FK do `tenant`) i
    identycznie jak `contact_transcription`. Nic do odtworzenia w tym zakresie. Brak też triggerów.
  - **Nazwa PK ZMIENIONA na konwencyjną `pk_contact_ai_summary`** (świadoma decyzja, w
    odróżnieniu od V086/`contact_transcription`, gdzie zachowano oryginalną auto-nazwę
    `contact_transcription_pkey`) — oryginalny PK `contact_ai_summary_pkey` był również
    auto-nazwany (V068 użyło inline `PRIMARY KEY` bez `CONSTRAINT`), więc oba podejścia (zachować
    auto-nazwę / nadać jawną konwencyjną nazwę) były technicznie równoważne; wybrano jawną nazwę
    dla spójności z `pk_contact_event` (V085). **Wniosek na przyszłość:** nazwa PK po online-swapie
    dla tabel z oryginalnie auto-nazwanym PK to decyzja stylistyczna, nie techniczny wymóg — obie
    opcje są poprawne, udokumentuj wybór w migracji żeby nie było niespójności bez wyjaśnienia
    między V086 (`_pkey`) i V087 (`pk_...`) w tym samym repo.
  - Partycje: `contact_ai_summary_2026_05`..`_2026_07` (zakres danych wg `generated_at`) + `_2026_08`
    (bieżący) + `_2026_09`, `_2026_10` (2 kolejne) + `contact_ai_summary_default` — te same miesiące
    co V085/V086, bo aplikowane tego samego dnia.
  - RLS: GUC pozostał **`app.tenant_id`** (świadomie NIE naprawiony, jak V085/V086), dodano
    `FORCE ROW LEVEL SECURITY` (`relforcerowsecurity=f`→`t`, jedyne świadome zaostrzenie).
  - Dry-run w transakcji z jawnym `ROLLBACK` (`docker cp` + `psql -f` z wrapperem
    `BEGIN;...ROLLBACK;`) wykonany przed prawdziwym uruchomieniem przez Flyway — zero błędów za
    pierwszym razem.
  - Test manualny pod `SET ROLE app_user`, `SAVEPOINT`/`ROLLBACK TO SAVEPOINT`: izolacja RLS
    (tenant A widzi własny testowy wiersz, tenant B widzi 0), cross-tenant INSERT odrzucony (bez
    jawnego `WITH CHECK`, `USING` użyty też jako check dla polityki `ALL`), insert własnego tenanta
    B zaakceptowany i widoczny. Cała weryfikacja (setup + testy) w jednej zewnętrznej transakcji z
    `ROLLBACK` na końcu — potwierdzono `COUNT(*)=57` i zero wyciekłych testowych wierszy po
    zakończeniu.
  - Sposób aplikacji: metoda z V085/V086 (`RunFlyway.java` skompilowany lokalnie na hoście, jary z
    `~/.m2`, IP bridge `cc-postgres` **to samo `172.18.0.11:5432`** co w sesjach V085/V086 — sieć
    Dockera nie była restartowana między sesjami tego samego dnia). Flyway `DbValidate` przy
    starcie potwierdził 87 zwalidowanych migracji i "Current version: 086" — czyli V086
    (równoległy agent, DB-050) był już zaaplikowany w momencie uruchomienia V087, bez żadnej
    kolizji numeracji ani checksumów.
  - Blokuje DB-052 (V088, rotacja partycji dla tej i 2 pozostałych tabel), DB-053 (V089, indeksy
    purge), BE-117 (`ContactAiSummary` → `@IdClass` w Javie, poza zakresem tego ticketu — encja
    JPA istnieje dziś jako proste `@Id`, zmiana na złożony klucz świadomie odłożona do BE-117).

Stan migracji po V084 (2026-08-09):
- V084__create_retention_purge_log.sql (DB-048, EPIC-29 — TRZECI ticket implementacyjny epiku, po DB-046/V082 i DB-047/V083): `retention_purge_log`, tabela audytu operacji purge (manualnych/automatycznych) — RUNNING→COMPLETED/FAILED lifecycle, `rows_deleted`/`status` ustrukturyzowane dla UI historii (FE-107). Odrębna od genericznego `audit_log` (podwójny zapis do obu to świadoma decyzja BE-113, poza zakresem tego ticketu).
  - PK surogat `purge_id UUID DEFAULT uuid_generate_v4()` (w odróżnieniu od V083, które ma PK złożony 1:1 cache) — bo ta tabela ma WIELE wierszy per (tenant, kategoria) w czasie, każda operacja purge = nowy wiersz, nie upsert.
  - DDL z TASKS-DATABASE.md był poprawny 1:1 dla FK (`tenant(tenant_id)`, `app_user(user_id)`) — bez błędów do poprawienia tym razem.
  - **Decyzja architektoniczna odnotowana explicite w tickecie i w nagłówku migracji:** DDL z ticketu NIE miał CHECK na `data_category` (w odróżnieniu od V082/V083, które mają identyczny CHECK na te same 4 kategorie). Dodałem ten CHECK mimo braku w treści ticketu, dla spójności z resztą modelu retencji — ta tabela jest ściśle sprzężona z tym samym zamkniętym zbiorem kategorii z `tenant_retention_policy`, a koszt krańcowy dodania nowej kategorii w przyszłości jest identyczny niezależnie czy CHECK jest tu, czy nie (i tak trzeba ALTER w V082/V083). Uzasadnienie zapisane w komentarzu nagłówkowym migracji — do przeczytania jeśli przyszły ticket zakwestionuje tę decyzję.
  - `triggered_by UUID REFERENCES app_user(user_id)` bez `NOT NULL` (zgodnie z ticketem) — auto-purge = system, brak sprawcy. Zweryfikowane insertem z `triggered_by = NULL` i `trigger_type='AUTO'`.
  - Test manualny pod `SET ROLE app_user`, `SAVEPOINT`/`ROLLBACK TO SAVEPOINT` per przypadek: RLS izolacja, WITH CHECK (cross-tenant insert odrzucony), CHECK `trigger_type`/`status`/`data_category` (BOGUS odrzucone), `triggered_by IS NULL` dla AUTO, `triggered_by` z realnym `user_id` dla MANUAL, lifecycle RUNNING→COMPLETED (UPDATE `rows_deleted`/`completed_at`), tabela pusta po migracji (brak backfillu, zgodnie z zakresem ticketu).
  - **Pułapka odkryta w teście lifecycle:** `WITH ins AS (INSERT ... RETURNING purge_id) UPDATE ... WHERE purge_id = (SELECT purge_id FROM ins)` daje `UPDATE 0` — PostgreSQL wykonuje wszystkie modyfikujące CTE w jednym query na tym samym snapshocie, więc główny UPDATE NIE widzi wiersza wstawionego przez CTE INSERT w tym samym stole (znane ograniczenie data-modifying CTE, nie błąd migracji/RLS). Poprawny test: dwa osobne statementy (INSERT z `RETURNING ... \gset`, potem osobny UPDATE) — zadziałało (`UPDATE 1`, `has_completed_at=t`). Zanotować na przyszłość: nigdy nie testować insert+update na tej samej tabeli w jednym query przez WITH-CTE-chaining, zawsze rozdzielić na dwa statementy w skrypcie weryfikacyjnym.
  - Indeks `idx_retention_purge_log_tenant (tenant_id, started_at DESC)` potwierdzony przez `EXPLAIN (ANALYZE, BUFFERS)` na 10000 testowych wierszach (5000/tenant): `Index Scan using idx_retention_purge_log_tenant`, `Index Cond: (tenant_id = ...)`, brak osobnego kroku Sort (DESC w indeksie pokrywa ORDER BY wprost) — dokładnie wzorzec zapytania historii FE-107.
  - Sposób aplikacji: metoda z V082/V083 (RunFlyway.java lokalnie skompilowany na hoście, jary z `~/.m2`, IP bridge `cc-postgres` tym razem `172.18.0.11:5432` — TO SAMO IP co w sesji V083, nie zawsze zmienia się między sesjami jeśli sieć Dockera nie była restartowana). Brak psql na hoście — weryfikacja przez `docker exec cc-postgres psql -U ccapp -d contact_center -f ...` (plik wgrywany `docker cp`).
  - Blokuje BE-113 (encja/repozytorium JPA + logika RUNNING→COMPLETED/FAILED, poza zakresem DB-048).

Stan migracji po V083 (2026-08-09):
- V083__create_tenant_retention_pending_summary.sql (DB-047, EPIC-29 — DRUGI ticket implementacyjny epiku, zależny od DB-046/V082): tabela-cache `tenant_retention_pending_summary`, wypełniana PÓŹNIEJ przez przyszły `RetentionEvaluationJob` (BE-112, poza zakresem). PK złożony `(tenant_id, data_category)` BEZ surogatu (w odróżnieniu od V082, które ma `policy_id UUID` jako PK) — DDL z TASKS-DATABASE.md był poprawny 1:1, bez błędów FK do poprawienia tym razem.
  - **Brak backfillu, w odróżnieniu od V082** — tabela świadomie zaczyna pusta, potwierdzone testem `COUNT(*) = 0` po migracji pod `ccapp` (bypassrls, widzi wszystkie tenanty).
  - Semantyka „brak wiersza = jeszcze nie policzone” vs „eligible_row_count=0 = policzone, zero do usunięcia” udokumentowana w `COMMENT ON TABLE` — ważne rozróżnienie dla BE-112/FE-105, które będą czytać tę tabelę przez LEFT JOIN.
  - **PK złożony jako cel `ON CONFLICT` zweryfikowany explicite:** `INSERT ... ON CONFLICT (tenant_id, data_category) DO UPDATE` zadziałał bez błędu "no unique or exclusion constraint" — PK (btree na obu kolumnach) w PostgreSQL jest wystarczającym celem konfliktu, nie trzeba osobnego `UNIQUE`. Test: dwa kolejne upserty na ten sam klucz, finalny wiersz miał wartość z DRUGIEGO insertu (42, nie 10) — potwierdza że to był prawdziwy UPDATE, nie duplicate-key error połknięty gdzieś po drodze.
  - Test manualny pod `SET ROLE app_user`, `SAVEPOINT`/`ROLLBACK TO SAVEPOINT` per przypadek (ten sam wzorzec co DB-046): RLS izolacja (tenant B 0 wierszy po insercie tenanta A), WITH CHECK (cross-tenant insert odrzucony — `ERROR: new row violates row-level security policy`), CHECK data_category (BOGUS_CATEGORY odrzucony), upsert ON CONFLICT (opisany wyżej), brak backfillu. Cała weryfikacja w jednej transakcji z `ROLLBACK` na końcu.
  - **Nowa obserwacja o środowisku (ta sesja, host Linux `/home/pawelm/contact-center`):** katalog `~/.m2/repository` na hoście już miał wszystkie potrzebne jary (flyway-core/flyway-database-postgresql 10.20.1, postgresql 42.7.4, jackson 2.17.2) i JDK 21 zainstalowany — metoda host-side z V080 (bez `docker cp` do `cc-backend`) zadziałała od razu, bez potrzeby ponownego reużycia artefaktów z poprzednich sesji (żadne `/tmp/flyway-run*` nie przetrwało — inny host/środowisko niż wcześniejsze sesje, więc zawsze zakładaj start od zera, nie polegaj na starych ścieżkach `/tmp`). IP bridge kontenera `cc-postgres` w tej sesji: `172.18.0.11:5432` (potwierdzone `docker inspect`, jak zawsze zmienne między sesjami).
  - Blokuje BE-112 (`RetentionEvaluationJob` — wypełnianie tej tabeli, poza zakresem DB-047).

Stan migracji po V082 (2026-08-09):
- V082__create_tenant_retention_policy.sql (DB-046, EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów — PIERWSZY ticket implementacyjny epiku): tabela konfiguracyjna `tenant_retention_policy`, jeden wiersz per (tenant, kategoria: CONTACT_INTERACTIONS/RECORDINGS/TRANSCRIPTS/CAMPAIGN_DATA). `audit_log` świadomie POZA zakresem (log platformowy, DB-052 osobno).
  - DDL z TASKS-DATABASE.md tym razem był poprawny od razu — FK `tenant(tenant_id)` i `app_user(user_id)` już OK, RLS GUC już `app.current_tenant_id` (nie powtórzono błędu z V059/064/067/068). PK `policy_id UUID DEFAULT uuid_generate_v4()` (nie `id`) — tak podane wprost w tickecie, zachowane 1:1, nie ujednolicano do konwencji V069+.
  - Backfill dla tenantów istniejących (ta sama migracja): CONTACT_INTERACTIONS=60mies., CAMPAIGN_DATA=60mies., TRANSCRIPTS=CEIL(90/30.0)=3mies. (płaskie defaulty, bo te kategorie nie mają dziś personalizacji per-tenant).
  - **RECORDINGS backfill — jedyna kategoria z realną pułapką:** czyta `tenant.config->>'recording_retention_days'` PER TENANT (COALESCE fallback 90 gdy klucz brak w JSONB), `CEIL(dni/30.0)` + `GREATEST(1, LEAST(120, ...))` jako zabezpieczenie przed CHECK(1..120) — bo `TenantResourceLimitsDto.recordingRetentionDays` nie ma dziś żadnej walidacji @Min/@Max na poziomie API, więc surowa wartość w JSONB teoretycznie mogłaby wypaść poza sensowny zakres miesięcy. Oba dev-seed tenanty miały `recording_retention_days=90` (brak dziś w danych dev tenanta z realnie niestandardową wartością) — personalizacja formuły zweryfikowana testem symulującym (nowy tymczasowy tenant z 45 dni w tej samej transakcji testowej, ROLLBACK na końcu, wynik CEIL(45/30.0)=2 potwierdzony).
  - Weryfikacja manualna pod `SET ROLE app_user`, `SAVEPOINT`/`ROLLBACK TO SAVEPOINT` per test: RLS izolacja (tenant A i B każdy widzi tylko swoje 4 wiersze), WITH CHECK (cross-tenant insert odrzucony), UNIQUE (tenant_id, data_category) (duplikat odrzucony), CHECK data_category (BOGUS odrzucony), CHECK retention_months (0 i 121 odrzucone przez `ON CONFLICT DO UPDATE` na istniejącym wierszu — CHECK egzekwowany też w tej ścieżce; 1 i 120 zaakceptowane na tymczasowym tenancie), backfill dokładnie 4 wiersze/tenant potwierdzony przez `COUNT(*) GROUP BY tenant_id`. Cała weryfikacja w jednej transakcji zakończonej `ROLLBACK` — baza nietknięta poza samym backfillem z migracji.
  - Sposób aplikacji: lokalny `RunFlyway.java` skompilowany na hoście (JDK 21) z jarami z `~/.m2` (flyway-core/flyway-database-postgresql 10.20.1, postgresql 42.7.4, jackson 2.17.2), połączenie po IP bridge-network kontenera `cc-postgres` (`docker inspect` → tym razem `172.18.0.11:5432`, zmienia się między restartami sieci Dockera — zawsze re-sprawdzić, nie zakładać poprzedniego IP z pamięci). `DB_USERNAME`/`DB_PASSWORD` z `.env.local-demo`.
  - Blokuje DB-047 (tabela cache `tenant_retention_pending_summary`, spójna z kategoriami z tej tabeli) i BE-111 (seedowanie polityk przy tworzeniu NOWEGO tenanta — poza zakresem DB-046, świadomie pominięte, tylko backfill dla istniejących).

Stan migracji po V080 (2026-07-12):
- V080__add_super_admin_role.sql: refaktor rol uzytkownikow — nowa rola SUPER_ADMIN (globalny administrator platformy, bez tenanta). Czesc DB planu z /home/pawelm/.claude/plans/linked-questing-sedgewick.md (backend/frontend delegowane osobno do backend-dev-expert/angular-frontend-expert).
  - `ALTER TABLE app_user ALTER COLUMN tenant_id DROP NOT NULL` + podmiana `chk_app_user_role` (dodano SUPER_ADMIN do listy 4 wartosci) + nowy CHECK `chk_super_admin_tenant_invariant`: `(role='SUPER_ADMIN' AND tenant_id IS NULL) OR (role<>'SUPER_ADMIN' AND tenant_id IS NOT NULL)`.
  - Nowy partial unique index `uq_super_admin_email ON app_user (LOWER(email)) WHERE role='SUPER_ADMIN' AND is_deleted=FALSE` — bez niego dwa konta SUPER_ADMIN z tenant_id=NULL moglyby miec ten sam email (Postgres traktuje NULL w kolumnach indeksu unikalnego jako zawsze rozny od innego NULL, wiec istniejacy `uq_user_tenant_email (tenant_id, email)` nie chroni SUPER_ADMIN).
  - Zero migracji danych — nazwa roli 'ADMIN' sie nie zmienia (tylko znaczenie uprawnien w logice aplikacji), dev-seed ADMIN (`admin@kmnsoftware.com` @ tenant "KMN Software" — NIE "Acme" jak sugerowal brief, dev-seed w tym repo ma tenantow "KMN Software" i "Kampania Handlowa") mial juz tenant_id NOT NULL wiec spelnia nowy invariant bez zmian.
  - Zweryfikowano (grep przez caly katalog migracji), ze `chk_app_user_role` jest JEDYNYM miejscem odwolujacym sie do zamknietej listy wartosci roli. Widoki `v_tenant_stats`/`v_queue_available_agents`/`v_queue_realtime_stats`/`v_active_contacts` i funkcje `check_tenant_limit`/`fn_contact_ref_integrity` NIE wymagaly zmian — wszystkie dolaczaja app_user przez rownosc `tenant_id`/`agent_id`, a SUPER_ADMIN (tenant_id NULL, nigdy nie jest agentem/queue_agent) naturalnie nie pojawia sie w zadnym z nich (NULL = X jest NULL w SQL).
  - Test manualny (BEGIN/DO $$.../ROLLBACK, ccapp — bo to byly testy CHECK/unique, nie RLS): SUPER_ADMIN+tenant_id NOT NULL odrzucony, ADMIN+tenant_id NULL odrzucony, SUPER_ADMIN+tenant_id NULL przyjety, duplikat emaila SUPER_ADMIN (rozna wielkosc liter) odrzucony przez uq_super_admin_email, rola spoza listy odrzucona przez chk_app_user_role, dev-seed ADMIN nadal spelnia invariant. Widoki (`v_tenant_stats` itd.) smoke-testowane po migracji bez bledow.
  - **Nowa, uproszczona metoda weryfikacji Flyway (zastepuje docker-cp-do-cc-backend z poprzednich sesji):** host MA bezposredni dostep TCP do `cc-postgres` po IP bridge-network Dockera (`docker inspect cc-postgres` -> `NetworkSettings.Networks.<siec>.IPAddress`, w tej sesji `172.18.0.2:5432`, potwierdzone `bash -c "echo > /dev/tcp/<ip>/5432"`) — mimo ze port nie jest opublikowany na `localhost`. Nie trzeba juz `docker cp` do `cc-backend`. Wystarczy: `javac`/`java` z hosta (JDK 21 zainstalowany), classpath z lokalnego `~/.m2` (`flyway-core` + `flyway-database-postgresql` w wersji z `backend/pom.xml`, `postgresql` driver, `jackson-databind`/`core`/`annotations`), maly `RunFlyway.java` z `Flyway.configure().dataSource(url,user,pass).locations("filesystem:<pelna-sciezka-do-...db/migration>").load()`, `DB_USERNAME`/`DB_PASSWORD` z `.env.local-demo` (NIE `ccapp:ccapp`). To faktycznie uruchamia Flyway (poprawny checksum liczony przez sam Flyway, zapisany do `flyway_schema_history` — bezpieczniejsze niz reczny INSERT z wyliczonym CRC32 uzywany w V070).
  - Uwaga: `cc-backend` (kontener) ma juz uruchomiony `app.jar` (prod profile, PID 1) — restart kontenera NIE podciagnie nowej migracji automatycznie, bo jar jest zbudowany PRZED dodaniem V080 (migracje sa bundlowane w jarze). Migracja zaaplikowana bezposrednio na baze przez zewnetrzny Flyway runner (jak opisano wyzej) jest widoczna dla wszystkich polaczen (w tym dla juz dzialajacego backendu) natychmiast, bo to zmiana schematu na poziomie DB, niezalezna od stanu JVM.
  - **Potwierdzony fakt architektoniczny (nie nowa wiedza, ale teraz zweryfikowany w kodzie zrodlowym, nie tylko wnioskowany):** `TenantAwareRepository.setTenantContextInDb()` (`backend/app/src/main/java/com/contactcenter/domain/repository/TenantAwareRepository.java`) woła WYLACZNIE `SELECT set_tenant_context(?)` (ustawia `app.current_tenant_id` w sesji) — NIGDY nie robi `SET ROLE app_user`. Polaczenie aplikacji zawsze idzie jako `ccapp` (`rolbypassrls=true`, potwierdzone w `pg_roles`), wiec RLS na `app_user` (i wszedzie indziej) w praktyce NIE ogranicza zapytan produkcyjnego backendu — dziala tylko jako deklaratywna, ale nieaktywna warstwa obronna, chyba ze ktos rowniez doda `SET ROLE app_user` (czego dzis kod nie robi). Test izolacji RLS w tym projekcie ZAWSZE wymaga recznego `SET ROLE app_user` (zob. [[feedback_rls_testing]]) wlasnie dlatego, ze sama aplikacja tego nie robi.

Stan migracji po V079 (2026-07-05):
- V079__add_external_id_to_customer.sql: kolumna techniczna external_id VARCHAR(255) NULL na customer (identyfikator z zewnętrznego CRM). Partial unique index uq_customer_tenant_external_id ON (tenant_id, external_id) WHERE external_id IS NOT NULL AND is_deleted = FALSE — wzorzec 1:1 z uq_user_tenant_email (V003). Bez błędów FK (brak FK w tej migracji); customer.tenant_id i is_deleted istnieją od V006 (tabela starsza, PK customer_id — zgodnie z [[feedback_pk_naming]]).

Stan migracji po V077 (2026-06-20):
- V077__create_plugin_invocation_log.sql (DB-045, EPIC-28, OSTATNI ticket DB tego epicu — zamyka warstwę DB 43/43): audit log wywołań pluginów (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED), RANGE-partycjonowana miesięcznie po invoked_at.
  - Sam błąd FK co DB-043/044: `REFERENCES tenant(id)` → poprawione na `tenant(tenant_id)`. FK do `tenant_plugin_installation(id)` poprawne.
  - **Mechanizm partycjonowania: PODŁĄCZONO do istniejącego (nie zbudowano nowego).** Wzorzec z V004 (audit_log) / V007 (contact): funkcja `create_<table>_partition(year, month)` idempotentna + `drop_old_<table>_partitions(retention_months)` + `rotate_<table>_partitions()` (loguje do `cron_log`, update `scheduled_job`). Dla plugin_invocation_log: `create_plugin_invocation_log_partition`, `drop_old_plugin_invocation_log_partitions`, `rotate_plugin_invocation_log_partitions` — 1:1 ten sam wzorzec. Nowy wpis w `scheduled_job` (cron `45 2 1 * *`). Funkcja zbiorcza `create_next_month_partitions()` (V014) ROZSZERZONA przez `CREATE OR REPLACE` o `PERFORM create_plugin_invocation_log_partition(...)` — teraz obsługuje 3 tabele. pg_cron nieaktywne w tym środowisku (sekcja rejestracji w V014 zakomentowana) — rotacja w praktyce przez zewnętrzny scheduler aplikacyjny czytający `scheduled_job`, identycznie jak audit_log/contact.
  - **Odkrycie o RLS+partycjonowanie w PostgreSQL (właściwość silnika, NIE błąd migracji):** `pg_class.relrowsecurity`/`relforcerowsecurity` na partycjach potomnych są ZAWSZE `f`, niezależnie od ENABLE/FORCE RLS na rodzicu i niezależnie od kolejności tworzenia. Zapytanie PRZEZ tabelę nadrzędną poprawnie egzekwuje RLS na każdej partycji (zweryfikowane na partycji DEFAULT/bieżącej i na partycji utworzonej ręcznie w teście, 2027_01). Zapytanie bezpośrednio po nazwie partycji potomnej (`SELECT FROM plugin_invocation_log_2027_01`) OMIJA RLS rodzica całkowicie — potwierdzone identycznym zachowaniem na już produkcyjnej `contact`/`contact_2026_03`, i na izolowanym minimalnym przykładzie. Aplikacja (grep przez backend/src/main/java) nigdy nie odpytuje partycji po nazwie — JPA mapuje tylko tabelę nadrzędną — więc brak ryzyka w obecnym kodzie. Zasada na przyszłość: nigdy nie pisać kodu/raportów odpytujących `<table>_YYYY_MM` bezpośrednio po nazwie.
  - Test manualny pod `SET ROLE app_user` w transakcji z SAVEPOINT/ROLLBACK TO SAVEPOINT per test-case: CHECK status (BOGUS odrzucony), RLS izolacja (tenant B 0 wierszy) na partycji 2026_06 i na ręcznie utworzonej 2027_01, WITH CHECK (cross-tenant insert odrzucony), FK installation ON DELETE SET NULL (log przetrwał), FK tenant ON DELETE CASCADE (log usunięty).
  - Setup danych testowych: tabela `plugin` ma kolumny `plugin_key`/`display_name`/`vendor` (nie `name`/`description` jak można by się intuicyjnie spodziewać) — zawsze `\d plugin` przed pisaniem INSERT testowego, nie zgadywać nazw kolumn nawet dla tabel z wcześniejszego ticketu w tej samej sesji.
  - Sposób aplikacji: artefakty V074-076 w cc-backend (`/tmp/flyway-run2/RunFlyway.class` + jary) przetrwały — tylko `docker cp` nowego V077 do `/tmp/migrations/` + ponowne `java -cp ... RunFlyway`, bez rekompilacji.

Stan migracji po V076 (2026-06-20):
- V076__create_tenant_plugin_extension_binding.sql (DB-044, EPIC-28): bindingi 5 punktów rozszerzeń (PRE_CONTACT_CONNECT/POST_CONTACT_END/CUSTOMER_SYNC/DISPOSITION_SET/MANUAL_ACTION) per `tenant_plugin_installation`, z invocation_mode (BLOCKING/ASYNC) i timeout_ms. Lookup table dla `PluginRegistry` (BE-102), evita parsowania manifestu JSON przy każdym wywołaniu.
  - Sam błąd FK co DB-043: DDL miał `REFERENCES tenant(id)` → poprawione na `tenant(tenant_id)`. FK do `tenant_plugin_installation(id)` było poprawne (ta tabela ma PK `id`, konwencja V069+).
  - uq_tenant_plugin_extension_binding UNIQUE(tenant_plugin_installation_id, extension_point); 3 CHECK (point/mode/timeout 0<x<=60000); RLS+FORCE identyczne wzorcowo jak V075 (current_setting('app.current_tenant_id', TRUE)::UUID).
  - Indeks idx_tenant_plugin_extension_binding_lookup ON (tenant_id, extension_point) — confirmed via EXPLAIN: Index Scan (nie Seq Scan), One-Time Filter z RLS wrapuje na zewnątrz.
  - Test manualny: nie istniały dane testowe z DB-043 (tenant_plugin_installation puste w dev) — trzeba było w tej samej transakcji wstawić plugin/plugin_version/tenant_plugin_installation jako setup, potem przełączyć SET ROLE app_user. Użyto SAVEPOINT/ROLLBACK TO SAVEPOINT per test-case (nie pełny ROLLBACK), żeby błąd jednego testu (oczekiwany, np. CHECK violation) nie psuł reszty testów w tej samej transakcji — wzorzec do reużycia w DB-045 i dalej.
  - DDL DB-045 (V077, kolejny w kolejce, NIE wykonane) ma TEN SAM błędny FK `tenant(id)` → do poprawy przy realizacji.
  - Sposób aplikacji: artefakty z V075 (jary we /tmp/flyway-run-local na hoście, RunFlyway.class skompilowany w /tmp/flyway-run2 w kontenerze cc-backend) przetrwały między sesjami — wystarczyło `docker cp` nowego pliku V076 do cc-backend:/tmp/migrations/ i ponownie odpalić `java -cp ... RunFlyway`. Nie trzeba było rekompilować nic.

Stan migracji po V075 (2026-06-20):
- V075__create_tenant_plugin_installation.sql (DB-043, EPIC-28): pierwsza tabela tenant-scoped z RLS w epiku (ADR-13). Instalacja konkretnej plugin_version per tenant; upgrade = nowy wiersz, stary enabled=false = rollback.
  - DDL z TASKS-DATABASE.md miał DWA błędne FK (handoff zgłaszał tylko jeden — app_user): `tenant_id REFERENCES tenant(id)` błędne (PK to tenant.tenant_id) i `installed_by_user_id REFERENCES app_user(id)` błędne (PK to app_user.user_id). Oba poprawione. Wniosek: handoff/brief nie jest wystarczający — zawsze weryfikować WSZYSTKIE FK w DDL przez \d, nie tylko te wymienione w notatce z poprzedniego ticketu.
  - **Pułapka testowa RLS odkryta tutaj:** rola `ccapp` (używana domyślnie w psql w tym środowisku) ma `rolbypassrls=true` — RLS/FORCE RLS nie są wobec niej egzekwowane, test izolacji pod `ccapp` daje fałszywy negatywny wynik (tenant B widzi wiersze tenanta A mimo poprawnej polityki). Potwierdzone że to nie błąd migracji — identyczny efekt na już zaakceptowanej custom_disposition (V070). Test trzeba robić pod `SET ROLE app_user;` (rolbypassrls=false, ma uprawnienia SELECT/INSERT na tabelach domenowych, ale `Cannot login` — tylko przez SET ROLE z ccapp). Zob. [[feedback_pk_naming]] i zanotować analogiczną zasadę dla testów RLS.
  - Constrainty zweryfikowane manualnie pod app_user w transakcji z ROLLBACK: uq_tenant_plugin_installation_version (duplikat odrzucony), chk_tenant_plugin_installation_health (BOGUS_STATUS odrzucony), FK plugin_version_id ON DELETE RESTRICT (delete z aktywną instalacją odrzucony), WITH CHECK RLS (cross-tenant insert odrzucony).
  - DDL ticketów DB-044 (V076) i DB-045 (V077), przeczytane podczas analizy ale NIE wykonane: zawierają TEN SAM błąd `REFERENCES tenant(id)` → musi być `tenant(tenant_id)` przy realizacji. Zanotowane w TASKS-DATABASE.md przy DB-043 jako uwaga na przyszłość.
  - Sposób aplikacji: identyczny jak V074 (cc-backend, port 5432 niepublikowany), ale `/tmp/flyway-run` z poprzedniej sesji okazał się read-only dla nowych plików — trzeba było `mkdir /tmp/flyway-run2` (zapisywalny) i skopiować jary tam. `javac` niedostępny w cc-backend (tylko JRE) — RunFlyway.java skompilowany lokalnie na hoście (ma JDK 21) z jarami pobranymi z kontenera przez `docker cp`, potem `.class` wgrany z powrotem.

Stan migracji po V074 (2026-06-20):
- V074__create_plugin_catalog.sql (DB-042, EPIC-28): katalog globalny pluginów, tabele `plugin`/`plugin_version`.
  - Świadomie BEZ tenant_id i BEZ RLS (ADR-13) — definicja pluginu jest metadaną infrastrukturalną współdzieloną; instalacja per tenant zaczyna się w V075 (DB-043, tenant_plugin_installation, pierwsza tabela z RLS w epiku).
  - PK obu tabel: `id` (nie `{tabela}_id`) — potwierdza że tabele tworzone od V069+ (custom_disposition, disposition_set, plugin) używają już `id` jako PK dla NOWYCH tabel; konwencja `{tabela}_id` dotyczy tylko starszych tabel (tenant, app_user, queue, campaign, itd.) — zob. [[feedback_pk_naming]] zaktualizowane.
  - DDL z TASKS-DATABASE.md miał błąd: `REFERENCES app_user(id)` — PK tej tabeli to `app_user.user_id`. Poprawiono na `REFERENCES app_user(user_id) ON DELETE SET NULL`. Reszta DDL przepisana 1:1 bez zmian.
  - plugin_version niemutowalna po statusie VALIDATED (logika aplikacyjna, nie DB constraint) — nowa wersja JAR-a = nowy wiersz.
  - Constrainty zweryfikowane manualnie w dev: uq_plugin_version_plugin_version (duplikat plugin_id+version odrzucony), chk_plugin_version_status (status spoza listy odrzucony), FK uploaded_by_user_id ON DELETE SET NULL (potwierdzone że NULL-uje się po usunięciu user).
  - Sposób aplikacji migracji na dev: port 5432 postgresa NIE jest opublikowany na hosta w tym docker-compose (sieć wewnętrzna `contact-center-network`/`contact-center_default`). Backend (`cc-backend`) ma JRE 21 i jest podłączony do tej sieci — zadziałało: skopiować jary (flyway-core, flyway-database-postgresql, postgresql driver, jackson-databind/core/annotations z `~/.m2`) + mały `RunFlyway.java` (Flyway.configure().dataSource(...).locations("filesystem:/tmp/migrations").load().migrate()) do kontenera `cc-backend` przez `docker cp`, skompilować lokalnie (javac), i odpalić `java -cp ...` wewnątrz kontenera. Dane logowania (`DB_USERNAME`/`DB_PASSWORD`) są w `.env.local-demo`, NIE `ccapp:ccapp` jak błędnie założono na starcie.
  - Backend Spring Boot fat-jar (`/app/app.jar`) NIE da się dołożyć do classpath płasko (struktura BOOT-INF/classes + BOOT-INF/lib zagnieżdżona) — stąd potrzeba osobnych jarów jackson skopiowanych z `.m2`.

Stan migracji po V070 (2026-05-27):
- V069__create_custom_disposition.sql (DB-040, EPIC-27): własne dyspozycje po kontakcie per kampania lub kolejka.
  - Zakres (scope): dokładnie jeden z campaign_id/queue_id musi być NOT NULL — egzekwowany przez chk_custom_disposition_scope CHECK.
  - Tone: positive/negative/neutral/warning — egzekwowany przez chk_custom_disposition_tone CHECK.
  - Unikalność kodu per zakres: dwa partial unique indexy (WHERE campaign_id IS NOT NULL / WHERE queue_id IS NOT NULL).
  - Indeksy wyszukiwania: (tenant_id, campaign_id, ordinal) + (tenant_id, queue_id, ordinal) — oba partial WHERE is_active=TRUE.
  - RLS: custom_disposition_isolation USING current_setting('app.tenant_id', TRUE)::UUID.
  - UWAGA: DDL w TASKS-DATABASE.md miał błąd — tenant(id) i queue(id) nie istnieją. Poprawiono na tenant(tenant_id) i queue(queue_id).
  - Flyway checksum: -878697635 (CRC32 per-line UTF-8, signed int32).
- V070__fix_custom_disposition_rls_and_indexes.sql (code-review fix, EPIC-27): poprawki RLS i indeksów dla custom_disposition.
  - [CRITICAL fix] RLS: zmieniono app.tenant_id → app.current_tenant_id (izolacja multi-tenant była wyłączona).
  - [MAJOR fix] Dodano WITH CHECK do polityki RLS (ochrona INSERT/UPDATE).
  - [MAJOR fix] ALTER TABLE custom_disposition FORCE ROW LEVEL SECURITY (blokuje właściciela tabeli).
  - [MINOR fix] Nowy indeks idx_custom_disposition_tenant_id ON (tenant_id, id) dla wzorca findByIdAndTenantId.
  - [MINOR fix] Nowe indeksy _all bez filtra is_active dla widoku supervisora (zwraca wszystkie wiersze):
      idx_custom_disposition_campaign_all ON (tenant_id, campaign_id, ordinal) WHERE campaign_id IS NOT NULL
      idx_custom_disposition_queue_all    ON (tenant_id, queue_id, ordinal)    WHERE queue_id IS NOT NULL
  - Flyway checksum: -1578165228. Rejestracja ręczna przez INSERT do flyway_schema_history (migracja przez psql).

Stan migracji po V068 (2026-05-25):
- V064__create_tenant_ai_config.sql (DB-038): konfiguracja dostawcy AI per tenant.
  - Enum ai_provider: ANTHROPIC | OPENAI | AZURE_OPENAI
  - UNIQUE (tenant_id) — jeden rekord per tenant, FK ON DELETE CASCADE
  - api_key_encrypted TEXT: klucz API szyfrowany AES-256-GCM przez JPA EncryptedStringConverter
  - Pola Azure-only: azure_endpoint, azure_deployment_name (nullable)
  - summary_prompt_template TEXT NULL: nadpisuje domyślny prompt aplikacji; NULL = użyj domyślnego
  - Partial index WHERE is_active; RLS USING current_setting('app.tenant_id', TRUE)::UUID
- V065__add_ai_summary_to_contact.sql (DB-039): pola podsumowania AI w tabeli contact (tymczasowe, wycofane przez V068).
- V067__create_contact_transcription.sql: transkrypcje rozmów od Whisper; contact_id+tenant_id bez FK (contact partycjonowana); RLS; wzorzec dla V068.
- V068__extract_ai_summary_to_own_table.sql (2026-05-25): wyodrębnienie AI summary z contact do contact_ai_summary.
  - Kolejność: CREATE TABLE → indeks → RLS → INSERT (migracja danych) → DROP COLUMN
  - Brak FK do contact — tabela partycjonowana RANGE (PostgreSQL nie obsługuje FK do partycji ze strony child)
  - Kolumny: ai_summary_id UUID PK, contact_id UUID, tenant_id UUID, summary TEXT, model VARCHAR(100), generated_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT NOW()
  - Indeks: idx_contact_ai_summary_contact ON (contact_id, tenant_id)
  - RLS policy: contact_ai_summary_isolation USING (tenant_id = current_setting('app.tenant_id', TRUE)::UUID)
  - DROP COLUMN z IF EXISTS na: ai_summary, ai_summary_model, ai_summary_generated_at (propaguje do partycji automatycznie)

Stan migracji po V062 (2026-05-21):
- V062__campaign_agent_assignment.sql (DB-036): trójpoziomowe przypisanie agentów do kampanii wychodzącej.
  - campaign.all_agents BOOLEAN DEFAULT FALSE: TRUE = wszyscy agenci tenanta, FALSE = jawne przypisanie
  - Istniejące kampanie: UPDATE SET all_agents = TRUE (backward compat)
  - campaign_agent: many-to-many kampania ↔ agent (bezpośrednie), PK (campaign_id, agent_id), CASCADE DELETE
  - campaign_agent_group: many-to-many kampania ↔ agent_group, PK (campaign_id, group_id), CASCADE DELETE
  - Indeksy pokrywające: idx_campaign_agent_group_lookup INCLUDE(group_id), idx_campaign_agent_member_lookup na agent_group_member(agent_id) INCLUDE(group_id)
  - BUILD SUCCESS: mvn verify -pl app -DskipTests (01:15 min)

Stan migracji po V053 (2026-05-08):
- V053__add_not_reached_callback_status.sql (DB-032): rozszerzenie CHECK constraint na campaign_contact i campaign_contact_archive o statusy NOT_REACHED i CALLBACK; przebudowa idx_campaign_contact_dialer (teraz WHERE status IN ('PENDING', 'NO_ANSWER') – retry); przebudowa mv_campaign_stats z nowymi kolumnami not_reached_records i callback_records; COMMENT ON COLUMN campaign_contact.status z opisem wszystkich 10 statusów.

Stan migracji po V052 (2026-05-05):
- V049__add_version_columns.sql: kolumny wersjonowania
- V050__add_preferred_language_to_app_user.sql: preferred_language na app_user
- V051__create_tenant_twilio_config.sql (DB-030): tabela tenant_twilio_config – konfiguracja Twilio per tenant, UNIQUE (tenant_id), FK ON DELETE CASCADE, wrażliwe pola (account_sid, auth_token, api_key_sid, api_key_secret) szyfrowane AES-256-GCM przez JPA AttributeConverter (baza przechowuje Base64(IV||ciphertext)), partial index WHERE is_active, RLS USING current_setting('app.current_tenant_id', TRUE)::uuid, komentarze kolumn dokumentujące szyfrowanie. Seed V999 uzupełniony o placeholder konfiguracje dla obu tenantów testowych.
- V052__add_caller_id_to_campaign.sql (DB-031): kolumna caller_id VARCHAR(30) NULL na tabeli campaign (format E.164, addytywna/idempotentna), partial index idx_campaign_caller_id ON (tenant_id, caller_id) WHERE caller_id IS NOT NULL AND is_deleted = FALSE. NULL = fallback do tenant_twilio_config.phone_number.

Stan migracji po V048 (2026-04-25):
- V048__agent_break.sql: tabela przerw agentów (agent_break), klucz UUID (uuid_generate_v4()), FK do tenant i app_user ON DELETE RESTRICT, CHECK constraints na break_type (LUNCH/SHORT_BREAK/TRAINING/OTHER), status (PLANNED/ACTIVE/COMPLETED/CANCELLED) i end_time > start_time, indeks kompozytowy (tenant_id, agent_id, start_time), RLS USING (current_setting('app.current_tenant_id', TRUE)::uuid)

Stan migracji po V033 (2026-04-08):
- V030__add_error_contact_status.sql: dodanie statusu ERROR do tabeli contact
- V031__add_dialer_indexes.sql: indeksy dla Progressive Dialer (BE-024) – zawierała błędy redundancji naprawione w V033
- V032__create_scheduled_callback.sql: tabela scheduled_callback (klucz: callback_id, statusy: PENDING/PROCESSING/COMPLETED/CANCELLED, agent_id i campaign_id opcjonalne, is_deleted soft-delete, RLS policy)
- V033__fix_dialer_indexes.sql: naprawa redundantnych indeksów z V031 (status usunięty z klucza, zachowany tylko w WHERE), + odbudowa idx_callback_ready na scheduled_callback

Stan migracji po V029 (2026-03-26):
- V029__add_email_address_to_queue.sql: kolumna email_address VARCHAR(255) NULL w tabeli queue, UNIQUE (tenant_id, email_address), CHECK (IS NULL OR LIKE '%@%'), partial index idx_queue_email_address WHERE email_address IS NOT NULL

Stan migracji po DB-002 (2026-03-13):
- V001-V014: wykonane w ramach DB-001
- V015__campaign_contact_archive.sql: tabela archiwum campaign_contact, pelna funkcja archive_completed_campaign_contacts() (zastapienie stubu z V014), funkcja purge_campaign_contact_archive()
- V016__contact_referential_integrity.sql: trigger trg_contact_ref_integrity (FK zastepczy dla partycjonowanej tabeli CONTACT), widoki: v_active_contacts, v_queue_realtime_stats, v_rls_status, v_index_health
- V017__gdpr_archive_export.sql: rozszerzona export_customer_data() obejmujaca archiwum, widok v_customer_timeline (historia klienta – CONTACT + EMAIL + SOCIAL UNION ALL)
