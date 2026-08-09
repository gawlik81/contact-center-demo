---
name: contact_center_project
description: Opis projektu Contact Center SaaS – kluczowe informacje architektoniczne i podjete decyzje
type: project
---

Projekt: Wielokanałowa platforma Contact Center w modelu SaaS (multi-tenant).
Stack: PostgreSQL 16 (operacyjna), ClickHouse (DW), Redis (cache/sesje), RabbitMQ (broker), Flyway (migracje), Debezium (CDC).
Backend: Java Spring Boot (modularny monolit Faza 1). Frontend: Angular SPA.

**Why:** PRD v1.0 z 2026-03-12. Faza 1 = MVP z kanałami PHONE/EMAIL/SOCIAL_MEDIA.

**How to apply:** Przy kolejnych zadaniach DB zakładaj że V001-V083 już istnieją (ostatnia: V083__create_tenant_retention_pending_summary.sql, 2026-08-09). Numery migracji kontynuuj od V084+. Zawsze zweryfikuj `ls backend/src/main/resources/db/migration/` przed napisaniem nowej migracji — nie ufaj tej liczbie bezkrytycznie.

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

Znana niekonsekwencja RLS (do uwagi przy nowych tabelach i przy ewentualnym fix):
- Konwencja ustalona w V012 i utrwalona w V042/V048/V051/V070: current_setting('app.current_tenant_id', TRUE)::UUID
- Ale V059 (contact_event), V064 (tenant_ai_config), V067 (contact_transcription), V068 (contact_ai_summary) używają app.tenant_id (bez current_)
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
