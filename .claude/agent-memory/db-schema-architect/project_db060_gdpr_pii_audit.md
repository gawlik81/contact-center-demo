---
name: project-db060-gdpr-pii-audit
description: DB-060 (2026-09-20) audyt PII klienta pod Art. 17/15 — nieoczywiste ustalenia (trigger V016 psuje anonymize_customer, customer_id w campaign_contact martwe, PII w contacts_dw/audit_log, pending S3 referencjonowane, dwie ścieżki REST anonimizacji)
metadata:
  type: project
---

Audyt DB-060 (tylko odczyt, baza demo V093, 2 klientów) — macierz i dowody są w `TASKS-DATABASE.md` sekcja DB-060. Ustalenia, których NIE widać przy czytaniu V013/V017:

- **`anonymize_customer` (V013) nie działa dla klienta z kontaktami.** `trg_contact_ref_integrity` (V016, BEFORE INSERT OR UPDATE, bez listy kolumn, klon na każdej partycji `contact`) odrzuca `UPDATE contact` gdy `customer.is_deleted = TRUE`; V013 ustawia `is_deleted` PRZED `UPDATE contact`. Dowód tylko statyczny (funkcji nie wolno było uruchomić) — potwierdzenie testem w DB-062. To samo blokuje `RecordingRetentionJob#clearRecordingUrl` dla klientów zanonimizowanych Javą. Wniosek ogólny: trigger walidujący FK-podobne referencje na tabeli PII koliduje z anonimizacją — kolejność instrukcji: UPDATE-y zależne PRZED `is_deleted=TRUE`.
- **`campaign_contact.customer_id` jest martwe** (37/37 NULL): jedyny INSERT (`CampaignContactRepository#buildInsertSql`) go nie ustawia, żaden UPDATE też. Predykaty `customer_id = …` w V017 nic nie znajdą. Powiązanie tylko przez `last_contact_id`, `contact.campaign_contact_record_id`, `phone`. Ogólnie: przed zaufaniem predykatowi po FK zmierz pokrycie (ile wierszy ma niepusty klucz) — 64/426 kontaktów, 20/55 callbacków i 23/55 e-maili też nie ma powiązania, choć niosą telefon/adres klienta (proponowana decyzja D9: dopasowanie po identyfikatorze).
- **PII poza tabelami źródłowymi:** PG `contacts_dw.remote_address` (fallback writer `PostgresDwWriter`; 130/130 wierszy bez kontaktu źródłowego — przeżyły retencję EPIC-29; ClickHouse NIE ma kolumn PII, prod/local-demo = `ETL_DW_TYPE=clickhouse`); `audit_log.new_value/old_value` (pełne snapshoty klienta i kontaktu, `notes`, `remoteAddress`).
- **S3 `email-attachments/{tenant}/pending/…` nie znaczy „porzucone":** `EmailSendServiceImpl#buildAttachmentsJson` zapisuje klucz `pending/` jako docelowy załącznika wiadomości OUTBOUND (8 z 9 obiektów w demo). BE-131 wariant A (TTL na `pending/`) niszczyłby załączniki wysłanych maili — wymaga anty-joinu z `attachments[*].s3_key`.
- **Dwie ścieżki REST anonimizacji:** `POST /api/customers/{id}/gdpr/anonymize` (`GdprServiceImpl`) oraz `DELETE /api/customers/{id}` (`CustomerController` → `CustomerRepository#anonymize`, używana przez listę klientów w UI, bez kasowania S3). BE-129 znał tylko pierwszą.
- `RecordingServiceImpl#deleteFromS3` połyka `S3Exception` — licznik `failed` w `GdprServiceImpl` jest martwy.
- Dialer (`fetchNextPendingContact`) nie filtruje `phone IS NOT NULL`, a `ScheduledCallbackExecutor` dzwoni na `getPhone()` dla `PENDING` → anonimizacja musi zmieniać status (SKIPPED / CANCELLED), nie tylko zerować telefon.

**Why:** wyniki zasilają DB-061/DB-062/BE-129 (EPIC-30, D3 = A); bez nich ktoś „naprawiłby" funkcje SQL, które i tak by nie zadziałały, i podłączył je do jednej z dwóch ścieżek.
**How to apply:** przy DB-061/DB-062/BE-129/BE-131/DB-077 najpierw przeczytaj notatkę DB-060 w `TASKS-DATABASE.md` i sekcje „Uzupełnienie z DB-060"; nowe decyzje D9 (identity resolution), D10 (audit_log) i tickety N1–N3 były tylko PROPONOWANE — sprawdź, czy właściciel je przyjął, zanim je zakładasz.

Powiązane: [[project-partitioning-candidates-2026-09]], [[feedback-readonly-audit-technique]], [[feedback_rls_testing]]
