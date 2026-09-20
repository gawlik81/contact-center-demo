---
name: project-epic30-plan
description: EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości — 46 ticketów (DB-056..077, BE-120..140, FE-110..112), zaplanowany 2026-09-20, projekt w DESIGN-message-retention-and-partitioning.md; założenia D1–D8 i kluczowe odkrycia
metadata:
  type: project
---

EPIC-30 zdekonstruowany 2026-09-20 z analizy db-schema-architect (tylko odczyt) + własnej weryfikacji w kodzie i na żywej bazie. Tylko dokumentacja: `DESIGN-message-retention-and-partitioning.md`
(root, ~160 linii: ustalenia U1–U15, decyzje D1–D8, fale, wymagania przekrojowe WP-1..WP-8, ryzyka R1–R5), moduły „EPIC-30" na końcu `TASKS-DATABASE.md`/`TASKS-BACKEND.md`/`TASKS-FRONTEND.md`, wiersze w `PROGRESS.md`.
Wszystkie tickety ⬜ (nierozpoczęte). Następny epik zaczyna od **DB-078 / BE-141 / FE-113**. Numery migracji Flyway świadomie NIE wpisane (nadawane przy implementacji: develop + otwarte gałęzie + `flyway_schema_history`).

**Why:** luka RODO (treść wiadomości nigdy nie usuwana) + martwe harmonogramy + partycjonowanie `email_message`/`social_message` zablokowane kluczami. Właściciel produktu nie odpowiedział na D1–D7, więc tickety działają przy ZAŁOŻENIACH DOMYŚLNYCH.

**How to apply:** przy pracy nad którymkolwiek ticketem EPIC-30 najpierw sprawdź, czy PO potwierdził/zmienił decyzje (zapisane w DESIGN §3 i w sekcji „Zakłada Dn = …" ticketu).
Założenia: D1=A (DELETE wierszy + S3 w `CONTACT_INTERACTIONS`), D2 = progi G1–G4 (≥10 GB / ≥2 mln wierszy / prognoza ≤12 mies. / objaw operacyjny; kalibruje DB-066), D3=A (rozszerzyć funkcje SQL i podłączyć do `GdprServiceImpl`), D4=A (`message_at NOT NULL` + unikalność złożona),
D5=24 mies. konfigurowalny, D6=`archived_at`, D7=RLS TAK, **D8 (nowa)**=job archiwizacji za flagą `retention.campaign-archive.enabled` domyślnie false.
Tickety warunkowe (tylko przy alternatywie): DB-063/BE-130/FE-111 (D1=C), DB-068/BE-136 (D4=B), DB-075/BE-140 (D6=koniec kampanii). Bramkowane wolumenem: DB-067/BE-134/BE-135 (email, bramka DB-066), DB-069/BE-137 (archiwum).

**Odkrycia niewidoczne w treści zlecenia (warto pamiętać):**
- Przepływ RODO w aplikacji to **Java** (`GdprController`→`GdprServiceImpl`; `CustomerRepository#anonymize` UPDATE tylko `customer`; eksport = customer+contacts ≤1000). Funkcje SQL `anonymize_customer`/`export_customer_data` mają 0 wywołań; `export_customer_data` jest `STABLE` z `INSERT INTO audit_log` (PG: „INSERT is not allowed in a non-volatile function", potwierdzone na funkcji sondującej w `pg_temp`).
- `PartitionReclaimJob` dziś DROP-uje także niepuste partycje (WARN + DROP) — dla tabel z S3 potrzebny tryb `ONLY_IF_EMPTY` (BE-123 wprowadza `ReclaimTarget`/`DropMode`, BE-133 implementuje). `PartitionScannerImpl#countRowsByTenant` daje NPE dla `tenant_id IS NULL` (`audit_log`).
- Włączenie `archive_completed_campaign_contacts()` ukrywa kontakty zakończonych kampanii >30 dni w UI (brak czytelników archiwum) — Faza 0 NIE jest wolna od decyzji PO.
- RLS: `email_message`/`social_message`/`audit_log`/`ivr_tree`/`queue`/`app_user` mają polityki tylko SELECT; `contact` SELECT+INSERT; pod rolą bez BYPASSRLS zapisy/DELETE byłyby odrzucane (`ccapp` = superuser BYPASSRLS, `app_user` NOLOGIN).
- `retention_purge_log.data_category` MA CHECK (V084 — inaczej niż DDL w opisie DB-048); D1=C wymaga przebudowy trzech CHECK-ów. Klucz załącznika w JSONB to `s3_key` (nie `s3_url` z V010).
- FE dryf po BE-119: `UNSUPPORTED_PURGE_CATEGORIES` w `data-retention.component.ts` nadal blokuje `CAMPAIGN_DATA` (uwzględnione w FE-110).
- `PROGRESS.md` nie ma wierszy dla EPIC-27 (DB-040/041, BE-092..096, FE-090..096) i addendów EPIC-28 (BE-110, FE-101/102) — liczby w Podsumowaniu są jednak liczone z TASKS-*.md (nie z wierszy); NIE naprawione (poza zakresem).
