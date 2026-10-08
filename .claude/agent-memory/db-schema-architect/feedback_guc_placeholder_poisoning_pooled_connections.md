---
name: feedback_guc_placeholder_poisoning_pooled_connections
description: Custom GUC (app.current_tenant_id) na PostgreSQL zwraca NULL via current_setting(name,TRUE) wyłącznie na połączeniu, które NIGDY go nie ustawiło; po pierwszym SET (nawet transakcyjnym) i COMMIT/ROLLBACK, kolejne odczyty bez ponownego SET dają '' (pusty string), nie NULL -- rzutowanie ::uuid wtedy rzuca hard error, nie ciche 0 wierszy
metadata:
  type: feedback
---

**Odkrycie (DB-074, 2026-10-08):** `current_setting('app.current_tenant_id', TRUE)` zwraca `NULL`
TYLKO gdy ten custom GUC nigdy nie był referencjonowany (żadnym `SET`/`set_config`) na danej
sesji/fizycznym połączeniu PostgreSQL. Po PIERWSZYM ustawieniu (nawet `is_local=true`,
transakcyjnym) i zakończeniu transakcji (**COMMIT lub ROLLBACK — oba identyczne**), kolejne
odwołanie BEZ ponownego `SET` zwraca **`''` (pusty string)**, nie `NULL`. Dotyczy JEDNAKOWO formy
1-argumentowej i 2-argumentowej (`missing_ok` nie ma znaczenia, bo GUC nie jest już "missing" — ma
wartość, tylko pustą). Zweryfikowane empirycznie (`set_config(...,true)` → `COMMIT`/`ROLLBACK` →
`current_setting(...,true)` = `''`).

**Why:** cała seria migracji EPIC-30 (V090, V099, V111, V112, V124...) zakłada jako aksjomat: "GUC
nieustawiony → `current_setting(...,TRUE)::uuid` = `NULL` → porównanie `tenant_id = NULL` = nieprawda
→ ciche 0 wierszy/odmowa, nigdy hard error". To jest PRAWDZIWE tylko dla połączenia, które NIGDY
nie ustawiło tego GUC-a. Na POOLOWANYM połączeniu (HikariCP w produkcji), gdzie
`TenantAwareRepository.setTenantContextInDb()` ustawia ten GUC przy KAŻDYM zapytaniu (norma), każde
pooled connection jest "zużyte" (poisoned) po pierwszym requeście — każdy KOLEJNY request/job na
TYM SAMYM fizycznym połączeniu, który NIE ustawia GUC (np. `EtlSyncServiceImpl`, `AuditLogConsumer`
dla zdarzeń globalnych, `AppUserRepository`/`AuditLogRepository` — obie `extends JpaRepository`, nie
`TenantAwareRepository`), dostałby **twardy błąd `invalid input syntax for type uuid: ""`**
(SQLSTATE 22P02), NIE ciche puste wyniki, jeśli rola połączenia kiedykolwiek przestanie mieć
BYPASSRLS.

**Dlaczego to NIE jest widoczne w testach Testcontainers tego repo:** każdy `connect()` w
istniejących testach RLS (`EmailSocialMessageRlsWritePoliciesTest`, `CampaignContactRlsMigrationsTest`,
`Db074RlsCompletionMigrationsTest`) otwiera NOWE fizyczne połączenie JDBC przez
`DriverManager.getConnection(...)`, nie przez pulę (HikariCP) — więc GUC jest ZAWSZE faktycznie
"świeży" (nigdy referencjonowany) w scenariuszu "bez GUC". To jest poprawne i wystarczające DLA
TYCH testów (dowodzą dokładnie to, co testują), ale NIE jest dowodem, że produkcyjne pooled
connections zachowają się tak samo.

**How to apply:**
- Przy KAŻDYM przyszłym tickiecie dotyczącym RLS + roli połączenia (BE-139, DB-071 "Otwarte") —
  przeczytać to PRZED jakąkolwiek decyzją o przełączeniu roli z `ccapp` (BYPASSRLS) na coś bez
  BYPASSRLS. "Dormant risk, ciche 0 wierszy" nie jest bezpiecznym założeniem bez dodatkowego
  mechanizmu (np. `NULLIF(current_setting(...,TRUE), '')::uuid` w KAŻDEJ polityce RLS w schemacie,
  albo `DISCARD ALL`/`connectionInitSql` przy zwrocie połączenia do puli HikariCP).
- Przy pisaniu testu "zachowanie bez ustawionego GUC" — używać ZAWSZE świeżego
  `DriverManager.getConnection(...)`, NIGDY współdzielonej/poolowanej `DataSource`, inaczej test
  będzie flaky w zależności od kolejności wykonania innych testów na tym samym połączeniu.
- Nie polegać na `RESET <guc>` jako sposobie na przywrócenie `NULL` — `RESET` po wcześniejszym `SET`
  również daje `''`, nie `NULL` (zweryfikowane).

Powiązane: [[project_db074_rls_completion]], [[feedback_rls_insert_vs_update_semantics]],
[[feedback_explain_rls_deny_proof]], [[project_db071_rls_classification_report]]
