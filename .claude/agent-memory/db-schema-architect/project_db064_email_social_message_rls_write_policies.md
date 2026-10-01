---
name: project_db064_email_social_message_rls_write_policies
description: DB-064/V099 (2026-10-01) — email_message/social_message RLS FOR SELECT -> ALL+WITH CHECK+FORCE; pierwszy ticket łańcucha EPIC-30 DB-064→DB-065→BE-132→BE-133; test Testcontainers pojedyncza baza; znalezisko o przestarzałych testach kodujących starą semantykę RLS
type: project
---

**DB-064/V099 — defense-in-depth RLS dla `email_message`/`social_message`.** V012 dało obu tabelom
WYŁĄCZNIE politykę `FOR SELECT` (`pol_email_message_select`/`pol_social_message_select`), bez
`FORCE`. Pod rolą bez `BYPASSRLS` (`app_user`) oznaczało to: INSERT zawsze twardy błąd 42501 (brak
jakiejkolwiek polityki INSERT = domyślna odmowa), UPDATE/DELETE cicho 0 wierszy. `ccapp` (rola
demo/dev używana przez backend) ma `BYPASSRLS`, więc luka była niewidoczna w demo. Migracja:
`DROP POLICY IF EXISTS pol_*_select` → `CREATE POLICY <tabela>_tenant_isolation ON <tabela> FOR ALL
USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid) WITH CHECK (...)` → `ALTER
TABLE ... FORCE ROW LEVEL SECURITY` — wzorzec 1:1 z `plugin_invocation_log_isolation` (V077) +
blok `DO $$...RAISE EXCEPTION` weryfikujący na końcu (wzorzec V090). GUC potwierdzony grepem jako
`app.current_tenant_id` w CAŁYM repo od V090 — żadnej nowej niekonsekwencji.

**Przegląd 6 ścieżek zapisu z ticketu — ZERO defektów znalezionych.** `EmailPollingServiceImpl`
(scheduler, `TenantContext.restore(snapshot)` per tenant przed `pollTenantInbox`),
`EmailContactCreator` (RabbitMQ consumer, `TenantContext.restore` na początku `onEmailEvent`/
`handleEmailSent`), `SocialMessageServiceImpl.processIncomingMessage` (webhook consumer, restore
przed przetwarzaniem) — wszystkie trzy jawnie ustawiają `TenantContext` przed zapisem (wątek bez
HTTP request). `EmailSendServiceImpl.sendReply/sendNew` i `SocialMessageServiceImpl.sendMessage`
NIE ustawiają `TenantContext` same — ale są wywoływane SYNCHRONICZNIE z kontrolerów REST
(`EmailController`, `SocialContactController`), bez `@Async`, więc dziedziczą kontekst już
ustawiony przez `TenantFilter` na tym samym wątku — potwierdzone grepem wywołań w `api/`.
`RetentionPurgeServiceImpl.purgeAsync` (`@Async`) używa snapshot/restore/clear w `finally` — wzorzec
z CLAUDE.md. `GdprServiceImpl` czyta `TenantContext.getTenantId()` wprost (REST endpoint). Każde
repozytorium (`EmailMessageRepository`/`SocialMessageRepository extends TenantAwareRepository`)
woła `setTenantContextInDb()` (→ `SELECT set_tenant_context(...)`) PRZED każdym zapytaniem zapisu —
to jest faktyczny mechanizm ustawiający GUC w sesji DB, nie samo `TenantContext` (ThreadLocal Javy).

**ZNALEZISKO — istniejące testy kodowały STARĄ (przed-DB-064) semantykę RLS i musiały zostać
zaktualizowane, inaczej `mvn verify` czerwony.** `AnonymizeCustomerExtensionTest` (DB-062/V096) miał
sekcję "10) RLS pod SET ROLE app_user" z testami (A)/(B)/(C) dokumentującymi DOKŁADNIE lukę, którą
DB-064 naprawia: test (A) (`rlsUnderAppUser_contactEmailSocial_silentlyZeroRows`, przemianowany na
`rlsUnderAppUser_contactStillZero_emailSocialNowWriteable`) oczekiwał `UPDATE email_message/
social_message` = 0 wierszy pod `app_user` ("tylko polityka SELECT"); test (C)
(`rlsUnderAppUser_withAuditLogPolicyPatchedForIsolation_revealsPerTableCounters`) oczekiwał
`counts.email_message`/`counts.social_message` = 0 z `anonymize_customer`. Po V099 oba UPDATE
faktycznie trafiają (fixture `EMAIL_RLS`/`SOCIAL_RLS` należą do `TENANT_A` = ten sam GUC) — liczniki
zmienione z `isZero()` na `isEqualTo(1)`, `DisplayName`/komentarze zaktualizowane z wyjaśnieniem
"DB-064/V099 dodało politykę ALL+WITH CHECK+FORCE". Test (B) (`audit_log` INSERT bez polityki, pełny
ROLLBACK) NIE wymagał zmian — `audit_log` poza zakresem DB-064. **Zasada dla DB-065/DB-067 (kolejne
tickety łańcucha, partycjonujące te same tabele):** PRZED zmianą sprawdź grepem
`grep -rn "tylko.*SELECT\|tylko polityka SELECT\|cicho 0" backend/app/src/test` — każda migracja
zmieniająca efektywne zachowanie RLS może cicho zepsuć testy w INNYCH plikach (nie tylko we
własnym), bo zachowanie RLS jest obserwowane przez testy spoza modułu, którego tabela dotyczy
(tu: test GDPR obserwujący RLS tabel wiadomości).

**Test Testcontainers — wzorzec „pojedyncza świeża baza", NIE pre/post TEMPLATE.**
`EmailSocialMessageRlsWritePoliciesTest` (`backend/app/src/test/java/com/contactcenter/
infrastructure/config/`) użył prostszego wzorca z `CampaignContactArchivePurgeTenantIsolationTest`
(jeden kontener, pełny `Flyway.migrate()` do najnowszej wersji, każdy test tworzy własne
`tenant`/wiersze ze świeżymi UUID) — NIE wzorca pre/post z `[[feedback_migration_test_pre_post_db]]`.
Powód: ten ostatni jest dla migracji zmieniających zachowanie na ISTNIEJĄCYCH danych (trigger/funkcja
+ dowód "przed vs po" na tym samym fixture); DB-064 to migracja DODAJĄCA nową zdolność (polityka
zapisu) na tabelach z 0 wierszy live — nie ma "starych danych" do zachowania między dwiema bazami,
więc dual-DB jest zbędnym narzutem. 10 testów: `pg_class` (relrowsecurity+relforcerowsecurity=true
dla obu), `pg_policies` (stare `pol_*_select` usunięte, nowe `*_tenant_isolation` FOR ALL z
WITH CHECK zawierającym `app.current_tenant_id`), własny tenant INSERT/UPDATE/DELETE OK (×2 tabele),
cross-tenant INSERT odrzucony 42501 (×2), cross-tenant SELECT/UPDATE/DELETE = 0 wierszy nie błąd
(×2, potwierdzone że wiersz nadal istnieje pod inną sesją), bez GUC = 0 wierszy SELECT + INSERT
odrzucony 42501 (×2). `set_config(..., false)` (session-level, nie `true`/is_local) użyty w helperze
`asAppUser`, bo test NIE zarządza jawnymi transakcjami (autocommit, wzorzec
`CampaignContactArchivePurgeTenantIsolationTest`) — w odróżnieniu od `ContactRefIntegrityNarrowingTest`,
który owija każdą próbę w `SAVEPOINT`/transakcję i może użyć `is_local=true`.

**Blokada numeracji V098 (żywa baza dev) — patrz [[feedback_migration_numbering_check]] (druga
instancja, 2026-10-01).** Decyzja: NIE wymuszać live-apply na współdzielonym `cc-postgres`, polegać
wyłącznie na Testcontainers + dry-run SQL w transakcji z `ROLLBACK`. Weryfikacja na żywo w
local-demo (polling IMAP/odpowiedź email/webhook social, WP-4 z ticketu) NIE wykonana w tej sesji —
wymaga przebudowy obrazów Dockera, poza zakresem jednej sesji subagenta; ticket sam zaznacza że
"dowodem jest test pod app_user, nie demo".

`mvn verify -pl app`: 2196 testów, 0 failures po poprawce `AnonymizeCustomerExtensionTest`.

Powiązane: [[contact_center_project]], [[feedback_rls_testing]], [[feedback_migration_numbering_check]],
[[feedback_migration_test_pre_post_db]], [[feedback_rls_insert_vs_update_semantics]]
