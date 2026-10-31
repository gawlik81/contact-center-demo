---
name: project_db074_rls_completion
description: DB-074 (2026-10-08, V113-V124) -- domknięcie RLS wg klasyfikacji DB-071 (klasa D email_routing_rule/email_template/ivr_audio/contacts_dw, klasa B contact/campaign/customer/queue/ivr_tree, klasa C audit_log/app_user bugfix, kosmetyka scheduled_callback); dwa odkrycia o GUC poolowanych połączeń i AppUserRepository/AuditLogRepository poza TenantAwareRepository
metadata:
  type: project
---

**Fakt/decyzja:** DB-074 (EPIC-30) wykonane 2026-10-08, 12 migracji **V113-V124**, każda osobna
(jedna zmiana = jedna migracja). Zamyka klasyfikację z DB-071 dla wszystkich tabel poza
`refresh_token`/`gdpr_processing_register` (świadomie wyłączone). Status ✅.

**Lista migracji:**
- V113 `email_routing_rule_rls.sql`, V114 `email_template_rls.sql`, V115 `ivr_audio_rls.sql`,
  V116 `contacts_dw_rls.sql` — klasa D (`relrowsecurity=false` → ENABLE+FORCE+jedna polityka
  `<tabela>_tenant_isolation` ALL+WITH CHECK, wzorzec 1:1 z V099/V111/V112). Żadna partycjonowana.
- V117 `contact_rls_update_delete.sql` (dopisane `pol_contact_update`/`pol_contact_delete`),
  V118 `campaign_rls_delete.sql` (`pol_campaign_delete`), V119 `customer_rls_delete.sql`
  (`pol_customer_delete`), V120 `queue_rls_write_policies.sql` (`pol_queue_insert/update/delete`),
  V121 `ivr_tree_rls_write_policies.sql` (`pol_ivr_tree_insert/update/delete` + **FORCE** — jedyna
  z klasy B bez FORCE od V012) — klasa B, konwencja nazw `pol_<tabela>_<cmd>` (NIE `_tenant_isolation`,
  bo te tabele JUŻ miały RLS, tylko niepełne pokrycie komend).
- V122 `audit_log_rls_write_policies.sql` (`pol_audit_log_insert/update/delete`, galąź
  `tenant_id IS NULL OR tenant_id = GUC` + FORCE), V123
  `app_user_rls_fix_select_and_write_policies.sql` (**naprawa buga**: `pol_app_user_select` miał
  `tenant_id = GUC` bez `IS NULL` → SUPER_ADMIN niewidoczny pod ŻADNYM GUC; DROP+CREATE z galęzią OR,
  + INSERT/UPDATE/DELETE z tą samą galęzią + FORCE) — klasa C MIXED.
- V124 `scheduled_callback_fix_guc_arity.sql` (punkt D opcjonalny, wykonany): DROP+CREATE
  `tenant_isolation_scheduled_callback`, `current_setting('app.current_tenant_id')` 1-arg →
  `current_setting('app.current_tenant_id', TRUE)` 2-arg. FORCE **nie dotknięty** (było `f`, zostaje `f`,
  poza AC).

**Test Testcontainers:** `Db074RlsCompletionMigrationsTest` (25 testów, wzorzec „jedna świeża baza do
najnowszej"), plus manualny dry-run+behawioralny dowód na żywej bazie demo PRZED napisaniem testu
Java (SAVEPOINT/ROLLBACK pod `SET ROLE app_user`, dwa realne tenanty).

**ZNALEZISKO 1 (najważniejsze, dla BE-139/DB-071 "Otwarte", NIE blokuje DB-074):** custom GUC
placeholder (`app.current_tenant_id`) na **tym samym fizycznym połączeniu** zwraca `NULL` z
`current_setting(name, TRUE)` TYLKO gdy nigdy wcześniej nie był referencjonowany na tej sesji. Po
PIERWSZYM `set_config`/`SET` (nawet transakcyjnym, `is_local=true`) i zakończeniu transakcji
(COMMIT **lub** ROLLBACK — oba dają ten sam efekt), kolejne odwołanie bez ponownego SET zwraca
**`''` (pusty string)**, nie `NULL` — zweryfikowane empirycznie (1-arg i 2-arg forma identyczne).
`''::uuid` rzuca TWARDY błąd `invalid input syntax for type uuid`, nie ciche 0 wierszy. Dotyczy
CAŁEGO schematu RLS (każda polityka z `current_setting(...)::uuid`), nie tylko tabel DB-074 —
fundamentalna właściwość silnika PG dla custom GUC, nieznana w całej serii EPIC-30 do tej sesji.
DZIŚ bez skutku (`ccapp` BYPASSRLS; testy Testcontainers w tym repo niepodatne, bo `connect()` =
zawsze NOWE fizyczne połączenie JDBC via `DriverManager`, nie pool). **Ryzyko realne na produkcji
z HikariCP**, jeśli kiedyś rola połączenia przestanie mieć BYPASSRLS: pooled connection, na którym
WCZEŚNIEJSZY request ustawił GUC (norma — `TenantAwareRepository` robi to przy każdym zapytaniu),
obsłuży PÓŹNIEJSZY request/job który GUC nie ustawia (ETL, `AuditLogConsumer` zdarzenia globalne,
`AppUserRepository`/`AuditLogRepository`) i dostanie HARD 500 (uuid cast), nie ciche puste wyniki —
zaprzecza założeniu "dormant, ciche 0 wierszy" powtarzanemu w CAŁEJ serii V099-V124. Rozwiązania do
rozważenia przy BE-139 (żadne nie w zakresie DB-074): `NULLIF(current_setting(...,TRUE),'')::uuid`
w politykach (odporne na `''`); HikariCP `DISCARD ALL` na zwrocie do puli; pozostać przy BYPASSRLS.

**ZNALEZISKO 2 (potwierdzenie, rozszerza DB-071 §2):** `PostgresDwWriter#upsert` (ETL fallback PG
dev) pisze do `contacts_dw` surowym `JdbcTemplate`, wołany z `EtlSyncServiceImpl` (scheduler,
wielu tenantów w jednym `batchUpdate`), NIGDY nie ustawia GUC. Od V116 (dodanie RLS do
`contacts_dw`) to kolejny dormant-risk tej samej klasy jak `archive_completed_campaign_contacts()`
(V111) — dziś bez skutku, udokumentowane w nagłówku V116.

**ZNALEZISKO 3 (potwierdzenie, nie nowe, ale konsekwencja dla V122/V123):**
`AppUserRepository`/`AuditLogRepository` extends `JpaRepository` (NIE `TenantAwareRepository`) —
WŁASNY javadoc obu klas explicite mówi "nie polegamy na RLS dla tej tabeli" / "filtrowanie przez
parametr zapytania, nie RLS". Oba NIGDY nie wołają `set_tenant_context()`. Pod przyszłą rolą
ograniczoną WSZYSTKIE zapisy do tych dwóch tabel zależałyby WYŁĄCZNIE od przypadkowego stanu GUC
zostawionego przez inny request na tym samym pooled connection (patrz Znalezisko 1) — silniejszy
argument za przeczytaniem Znaleziska 1 przy BE-139.

**Regresje testów naprawione (ten sam wzorzec co DB-064/DB-072/DB-073 — migracja zmieniająca
EFEKTYWNE zachowanie RLS psuje testy W INNYCH plikach, nie tylko we własnym):**
- `AnonymizeCustomerExtensionTest` testy (A)/(B)/(C) sekcji "10) RLS pod SET ROLE app_user" —
  zakładały starą semantykę (`contact` UPDATE cicho 0, `audit_log` INSERT zawsze twardy błąd).
  V117/V122 to zmieniają. Test (B) przepisany z "dowodu błędu" na "dowód naprawy" (cały
  `anonymize_customer` pod `app_user` teraz SUKCES, bo `p_tenant_id` insertowany do `audit_log`
  == GUC w tym teście). Test (C): usunięty tymczasowy patch polityki INSERT (niepotrzebny po V122),
  licznik `contact` 0→1. 14/14 zielone po poprawce.
- `PartitionGrantsRevokeMigrationsTest` (DB-080) — `@BeforeAll` migrował POST-snapshot do
  "najnowszej" (`latest.migrate()`), więc V117/V122 fałszywie "psuły" test
  `throughParent_andRlsCatalog_identicalToPreMigrationSnapshot` (porównanie całego zrzutu
  polityk/RLS, nie tylko ACL partycji, który jest JEDYNYM przedmiotem DB-080). **Naprawa**: POST
  pinowany dynamicznie (przez `Flyway#info()`, bez numerów na sztywno) do wersji TUŻ PO V110
  (ostatniej migracji DB-080), nie do "latest" — przywraca wąski, pierwotny zakres testu, odporny
  na KAŻDY kolejny ticket RLS na tych samych 6 tabelach (nie tylko DB-074). Zero zmian w logice
  asercji. 61/61 zielone po poprawce.
- Bez regresji (zweryfikowane, brak zmian): `ContactRefIntegrityNarrowingTest`,
  `ExportCustomerDataSubjectHelperTest`, `CampaignContactRlsMigrationsTest`,
  `EmailSocialMessageRlsWritePoliciesTest`, `RlsValidationServiceIntegrationTest` (ten ostatni
  NIE dotknięty — 4 nowe tabele klasy D nie są na jego hardkodowanej liście 11 tabel; plik sam
  poza zakresem, własność BE-138 równoległej tury).

**How to apply:** przy kolejnych tickietach RLS na JUŻ dotkniętych tabelach (contact, campaign,
customer, queue, ivr_tree, audit_log, app_user) — grep `SET ROLE app_user` w
`backend/app/src/test/java` PRZED pisaniem migracji (ten wzorzec zadziałał poprawnie trzeci raz z
rzędu: DB-064, DB-072/073, teraz DB-074). Przy jakimkolwiek teście "pre/post snapshot" na tabelach
współdzielonych między tickietami — SPRAWDŹ czy POST jest pinowany do konkretnej wersji (bezpieczne)
czy do "latest"/`Flyway.migrate()` bez targetu (fragile, złapie Cię przy następnym tickiecie na tej
samej tabeli, jak `PartitionGrantsRevokeMigrationsTest` tutaj).

Powiązane: [[project_db071_rls_classification_report]], [[project_db072_073_campaign_contact_rls]],
[[project_db064_email_social_message_rls_write_policies]], [[feedback_rls_insert_vs_update_semantics]],
[[feedback_explain_rls_deny_proof]], [[feedback_partition_grants_revoke]],
[[project_db080_revoke_six_tenant_tables]], [[feedback_measurement_script_rls_tz]]
