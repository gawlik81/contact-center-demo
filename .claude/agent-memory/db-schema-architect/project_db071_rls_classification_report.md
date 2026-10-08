---
name: project_db071_rls_classification_report
description: DB-071 (2026-10-08) raport klasyfikacji 39 tabel tenant_id bez pełnego RLS, macierz komend dowiedziona przez EXPLAIN (bez ANALYZE/zapisu), lista ścieżek pre-tenant w kodzie, pytanie D7 dla campaign_contact/archive
metadata:
  type: project
---

**Fakt/decyzja:** DB-071 (raport, bez zmian schematu) sklasyfikował wszystkie 39 tabel bazowych
z kolumną `tenant_id` (+ 5 widoków) wg `information_schema.columns` ⋈ `pg_class` ⋈ `pg_policies`.
Notatka pełna w `TASKS-DATABASE.md` sekcja `### DB-071`. Status ✅ (ticket analityczny, raport
kompletny). Blokuje DB-072/073/074/BE-138/BE-139.

**Klasyfikacja (podsumowanie):**
- **A — TENANT pełne RLS (21 tabel)**: `agent_break`, `agent_group`, `contact_ai_summary`,
  `contact_event`, `contact_transcription`, `custom_disposition`, `disposition_set`,
  `disposition_set_item`, `email_message`, `phone_number`, `phone_routing_rule`,
  `plugin_invocation_log`, `retention_purge_log`, `social_integration`, `social_message`,
  `tenant_ai_config`, `tenant_plugin_extension_binding`, `tenant_plugin_installation`,
  `tenant_retention_pending_summary`, `tenant_retention_policy`, `tenant_twilio_config`.
- **B — TENANT niepełne pokrycie komend → DB-074**: `contact` (SELECT+INSERT, brak UPDATE/DELETE —
  DELETE nowo dowiedzione przez EXPLAIN, UPDATE już z DB-079), `campaign`/`customer` (brak DELETE),
  `queue`/`ivr_tree` (tylko SELECT).
- **C — MIXED (`tenant_id` nullable)**: `audit_log` (SELECT ma gałąź IS NULL, ale INSERT/UPDATE/
  DELETE bez polityki), `app_user` (SELECT **nie ma** gałęzi IS NULL — SUPER_ADMIN niewidoczny pod
  RLS niezależnie od GUC, dowód EXPLAIN), `refresh_token` (bez RLS wcale), `gdpr_processing_register`
  (bez RLS wcale, 2/2 wiersze demo = NULL, metadane zgodności nie PII).
- **D — TENANT bez RLS wcale → DB-072/073/074**: `campaign_contact` (PII, DB-073, D7),
  `campaign_contact_archive` (PII, DB-072, D7), `contacts_dw` (PII `remote_address`, tylko PG-dev
  fallback, priorytet spada po DB-078), `email_routing_rule`/`email_template`/`ivr_audio`
  (konfiguracja, bez PII klienta).
- **E — GLOBAL świadomie bez RLS**: `tenant` (rejestr najemców, PK nie kolumna izolacji, musi być
  czytelny pre-auth), `plugin_version` (decyzja DB-042; `tenant_id` = kto wgrał wersję, nie izolacja
  widoczności — `findById` bez filtra tenanta jest intencjonalne, model marketplace).

**Metoda dowodowa nowa (do reużycia):** `EXPLAIN (COSTS OFF)` (bez `ANALYZE`) wewnątrz
`BEGIN; SET ROLE app_user; SELECT set_config(...); ...; ROLLBACK;` dowodzi odmowy RLS na poziomie
PLANU bez wykonania instrukcji — bezpieczne nawet dla `UPDATE`/`DELETE`/`INSERT` w sesji
tylko-do-odczytu. Brak polityki dla komendy → planner dokleja stały `false` do filtra
(`One-Time Filter: false` dla czystego DELETE/UPDATE po PK, albo `Filter: (false AND <predykat
biznesowy>)` gdy jest dodatkowy WHERE) — dowód "deny" bez pisania do bazy. Działa dla
SELECT/UPDATE/DELETE (USING qual, plan-time provable constant). **Nie działa dla INSERT** — `WITH
CHECK` jest sprawdzany per-wiersz w executorze, niewidoczny w planie bez `ANALYZE`; na to trzeba
użyć ustalenia z wcześniejszego tiketu (`feedback_rls_insert_vs_update_semantics`: brak polityki
INSERT = `42501` w runtime), nie powtarzać zapisem.

**Znalezisko nowe — `scheduled_callback` GUC 1-arg vs 2-arg:** polityka `tenant_isolation_scheduled_callback`
używa `current_setting('app.current_tenant_id')` (1 argument, bez `missing_ok`), podczas gdy
WSZYSTKIE inne tabele używają 2-argumentowej formy (`..., true`). Dowód: `SELECT count(*) FROM
scheduled_callback` pod `app_user` bez ustawionego GUC → `ERROR: unrecognized configuration
parameter` (hard error), nie ciche 0 wierszy jak reszta schematu. Nieszkodliwe dziś (GUC zawsze
ustawiany przez `TenantAwareRepository` przed zapytaniem), ale inny failure mode — do ujednolicenia
przy DB-074.

**Ścieżki pre-tenant w kodzie (najważniejsze, plik:linia):**
- `security/PublicPathsConfig.java:18-47` + `SecurityConfig.java:118-153` (zsynchronizowane listy
  permitAll/public prefixes).
- `infrastructure/aspect/CrossTenantAspect.java:125-179,193-197,203-214` — **gotowa,
  samodokumentująca mapa** ścieżek pre-tenant/async już istniejąca w repo (whitelist bootstrap
  methods + public paths + async-expected). Punkt wyjścia dla każdego kolejnego audytu tego typu.
- `domain/user/UserServiceImpl.java:475-483` — `findAuthenticatableUser(tenantId, email)` (login,
  WHERE z parametru, ale GUC nieustawiony w momencie logowania → RLS zwróciłby 0 wierszy pod rolą
  ograniczoną, **logowanie by się zepsuło**) i `findAuthenticatableGlobalUser(email)` (SUPER_ADMIN,
  `tenant_id IS NULL` — blokowane PERMANENTNIE przez dzisiejszą politykę `app_user`, patrz klasa C).
- `domain/social/SocialIntegrationRepository.java:119-125,144-145` — **już w kodzie udokumentowany
  komentarzem** cross-tenant lookup (`findByPlatformAndPageId`) dla webhooków FB/IG/WhatsApp — tenant
  nieznany do czasu tego zapytania. Działa dziś tylko dzięki BYPASSRLS połączenia.
- `domain/etl/EtlSyncServiceImpl.java:72-134,159/172/185/198` — **największy pre-tenant surface**:
  4 surowe zapytania `JdbcTemplate` (nie `TenantAwareRepository`) cross-tenant po `contact`+`customer`,
  `campaign_contact`+`campaign`, `app_user`, `queue`. Komentarz w kodzie (l.72,156) dokumentuje to jako
  zamierzone. Pod rolą ograniczoną bez GUC wszystkie 4 zwróciłyby 0 wierszy po cichu (ETL przestałby
  synchronizować, bez błędu).
- `domain/retention/PartitionMaintenanceJob.java` / `PartitionReclaimJob.java` — DDL cross-tenant z
  natury (partycja = wszystkie tenanty), działa na uprawnieniach ownera, nie RLS; patrz
  [[project_db080_revoke_six_tenant_tables]].
- `domain/retention/RetentionEvaluationJob.java` → `RetentionEvaluationService.runForAllActiveTenants()`
  — **wzorcowy przykład**: skan partycji cross-tenant (owner-level) + per-tenant `TenantContext`
  PRZED zapisem do `tenant_retention_pending_summary`.
- `domain/email/EmailPollingServiceImpl.java:60-90` — **wzorcowy przykład** pętli po
  `tenantService.getActiveTenants()` z `TenantContext.Snapshot`/`restore` per iteracja.
- `domain/audit/AuditLogConsumer.java:64,69` → `TenantAwareConsumer.processWithTenant` — dla
  `tenantId==null` (zdarzenia globalne) `TenantContext` NIE jest ustawiany wcale; pod rolą ograniczoną
  `audit_log` (klasa C, brak polityki INSERT) → `42501` na KAŻDYM zapisie (nie tylko globalnym).
- 10 innych `@RabbitListener` NIE zweryfikowanych linia-po-linii (poza budżetem S) — do potwierdzenia
  przy BE-139.

**D7 — ROZSTRZYGNIĘTE 2026-10-08: Opcja 1 (pełne RLS), zob. [[project_db072_073_campaign_contact_rls]] (V111/V112, DB-072/DB-073 ✅).** Treść poniżej zostaje jako zapis historyczny stanu w chwili tego raportu.

**D7 (było NIE rozstrzygnięte w chwili raportu):** dla `campaign_contact`/`campaign_contact_archive`
— pełne RLS (ALL+WITH CHECK+FORCE) symetrycznie z `contact`/`email_message`, czy węższe (bez DELETE)
dla `campaign_contact`? Dowód asymetrii: `campaign_contact` ma **0 wywołań DELETE w Javie** (jedyny
`DELETE FROM campaign_contact` jest w martwej funkcji SQL `archive_completed_campaign_contacts()`,
V015:140, zależnej od BE-120); `campaign_contact_archive` ma DELETE **aktywnie używany**
(`purge_campaign_contact_archive`, DB-056, wywołany z `RetentionPurgeServiceImpl` — żywy przepływ
retencji). 3 opcje przedstawione w notatce, żadna nie wybrana.

**Czego NIE zweryfikowano:** runtime WITH CHECK dla INSERT na tabelach klasy B (oparte na
wcześniejszym ustaleniu, nie powtórzone); 10/14 klas `@RabbitListener` nie przeczytane linia-po-linii;
`plugin_version` REST-layer filtering (tylko warstwa repo sprawdzona); czy `gdpr_processing_register`
kiedykolwiek zapisuje `tenant_id` niepusty (brak znalezionego repozytorium zapisu w szybkim grepie).

Powiązane: [[feedback_rls_testing]], [[feedback_rls_insert_vs_update_semantics]],
[[project_db070_campaign_contact_partition_adr]], [[project_db080_revoke_six_tenant_tables]],
[[feedback_readonly_audit_technique]], [[feedback_partition_grants_revoke]]
