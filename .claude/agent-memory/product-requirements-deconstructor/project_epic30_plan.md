---
name: project-epic30-plan
description: EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości — 50 ticketów (DB-056..079, BE-120..142, FE-110..112; BE-124 i DB-060 ✅), plan 2026-09-20 + korekta po BE-124/DB-060, projekt w DESIGN-message-retention-and-partitioning.md, założenia D1–D10
metadata:
  type: project
---

EPIC-30 zdekonstruowany 2026-09-20 z analizy db-schema-architect (tylko odczyt) + własnej weryfikacji w kodzie i na żywej bazie; ten sam dzień: korekta planu po zamknięciu **BE-124** (ADR D1) i **DB-060** (audyt PII) — 4 nowe tickety i decyzje D9/D10.
Tylko dokumentacja: `DESIGN-message-retention-and-partitioning.md` (root, ~205 linii: ustalenia U1–U19, decyzje D1–D10, fale, wymagania przekrojowe WP-1..WP-8, ryzyka R1–R9), moduły „EPIC-30" na końcu `TASKS-DATABASE.md`/`TASKS-BACKEND.md`/`TASKS-FRONTEND.md`, wiersze w `PROGRESS.md`.
Stan: 50 ticketów (DB 24, BE 23, FE 3), **✅ tylko BE-124 i DB-060**, 48 ⬜. Następny epik zaczyna od **DB-080 / BE-143 / FE-113**. Numery migracji Flyway świadomie NIE wpisane (nadawane przy implementacji: develop + otwarte gałęzie + `flyway_schema_history`).

**Why:** luka RODO (treść wiadomości nigdy nie usuwana) + martwe harmonogramy + partycjonowanie `email_message`/`social_message` zablokowane kluczami. Właściciel produktu nie odpowiedział na D1–D10, więc tickety działają przy ZAŁOŻENIACH DOMYŚLNYCH (D1 = A przyjęte roboczo 2026-09-20 BEZ wyraźnego potwierdzenia PO — to nie zatwierdzenie).

**How to apply:** przy pracy nad ticketem EPIC-30 najpierw sprawdź, czy PO potwierdził/zmienił decyzje (DESIGN §3 i sekcja „Zakłada Dn = …" ticketu).
Założenia: D1=A (DELETE wierszy + S3 w `CONTACT_INTERACTIONS`), D2 = progi G1–G4 (≥10 GB / ≥2 mln wierszy / prognoza ≤12 mies. / objaw operacyjny; kalibruje DB-066), D3=A (rozszerzyć funkcje SQL i podłączyć do `GdprServiceImpl`), D4=A (`message_at NOT NULL` + unikalność złożona; **źródło wieku = INTERNALDATE (`getReceivedDate()`), NIE nagłówek `Date` nadawcy**), D5=24 mies. konfigurowalny, D6=`archived_at`, D7=RLS TAK, D8=job archiwizacji za flagą `retention.campaign-archive.enabled` domyślnie false,
**D9=A** (zbiór podmiotu po powiązaniu I po identyfikatorze telefon/e-mail, z licznikami `matched_by_*` i podglądem dry-run; ryzyko fałszywych trafień), **D10** = maskowanie kluczy PII w `audit_log` (wiersze zostają; wymaga potwierdzenia prawnego).
Tickety warunkowe (tylko przy alternatywie, 8): DB-063/BE-130/FE-111 (D1=C), DB-068/BE-136 (D4=B), DB-075/BE-140 (D6=koniec kampanii), BE-142 (D10). Bramkowane wolumenem (5): DB-067/BE-134/BE-135 (email, bramka DB-066), DB-069/BE-137 (archiwum).
Nowe po BE-124/DB-060: **DB-078** (sweep + drop `contacts_dw.remote_address`, zależy od **BE-141** = ETL bez `remote_address`), **DB-079** (zawężenie `fn_contact_ref_integrity` — blokuje DB-062 i BE-129), **BE-142** (maskowanie audytu). Zmiany zakresu: BE-129 = L (dwie ścieżki REST, podgląd D9, jedno źródło audytu, stany operacyjne), DB-062 = L i zależy od DB-061 (wspólna funkcja pomocnicza D9) oraz DB-079, FE-112 = Should/M (podgląd D9 + lista klientów na modal GDPR).

**Odkrycia niewidoczne w treści zlecenia (warto pamiętać):**
- Przepływ RODO to **Java** (`GdprServiceImpl`), a **DRUGA ścieżka REST** `DELETE /api/customers/{id}` (`CustomerServiceImpl#anonymizeCustomer`, `@Audited`) aktualizuje tylko `customer`; funkcje SQL `anonymize_customer`/`export_customer_data` mają 0 wywołań. `export_customer_data` jest `STABLE` z `INSERT INTO audit_log`. **`anonymize_customer` nie zadziała dla klienta z kontaktami** (V013 ustawia `is_deleted = TRUE` l. 48–58 przed `UPDATE contact` l. 80; trigger V016 wymaga `is_deleted = FALSE`; 12 kopii triggera) — dowód statyczny, potwierdzenie działaniem w DB-062/DB-079. Trzy nazwy akcji audytu (`GDPR_ANONYMIZE`, `CUSTOMER_ANONYMIZED` ×2).
- `campaign_contact.customer_id` NULL w 37/37 (import go nie ustawia) → wiązanie tylko po kluczu jest za wąskie (D9). PG `contacts_dw.remote_address` niesie PII (130/130, wszystkie bez kontaktu źródłowego); ClickHouse czysty.
- `pending/` w S3 to DOCELOWE klucze załączników OUTBOUND (8 z 9 wskazywanych) — TTL/lifecycle na prefiksie skasowałby je (BE-131 skorygowany); EML = `contact.recording_url` (RECORDINGS); `RecordingService#deleteFromS3` połyka `S3Exception`; FB/IG `sent_at = Instant.now()` (unikalność złożona nie zastąpi globalnego UNIQUE w social).
- `PartitionReclaimJob` DROP-uje także niepuste partycje (WARN + DROP) — dla tabel z S3 tryb `ONLY_IF_EMPTY` (BE-123 wprowadza `ReclaimTarget`/`DropMode`, BE-133 implementuje). `PartitionScannerImpl#countRowsByTenant` daje NPE dla `tenant_id IS NULL` (`audit_log`).
- Włączenie `archive_completed_campaign_contacts()` ukrywa kontakty zakończonych kampanii >30 dni w UI — Faza 0 NIE jest wolna od decyzji PO (D8).
- RLS: polityki tylko SELECT na `email_message`/`social_message`/`audit_log`/`ivr_tree`/`queue`/`app_user`; `contact` SELECT+INSERT; `ccapp` = superuser BYPASSRLS, `app_user` NOLOGIN — łańcuch purge/RODO nigdy nie był sprawdzony pod rolą ograniczoną.
- `retention_purge_log.data_category` MA CHECK (V084); D1=C wymaga przebudowy trzech CHECK-ów. Klucz załącznika w JSONB to `s3_key` (nie `s3_url` z V010).
- FE dryf po BE-119: `UNSUPPORTED_PURGE_CATEGORIES` w `data-retention.component.ts` nadal blokuje `CAMPAIGN_DATA` (FE-110).
- `PROGRESS.md` nie ma wierszy dla EPIC-27 (DB-040/041, BE-092..096, FE-090..096) i addendów EPIC-28 (BE-110, FE-101/102) — liczby w Podsumowaniu liczone z TASKS-*.md; NIE naprawione (poza zakresem).
