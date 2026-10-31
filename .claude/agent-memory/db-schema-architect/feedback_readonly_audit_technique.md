---
name: feedback-readonly-audit-technique
description: Technika audytu PII tylko-do-odczytu w tym repo — psql z PGOPTIONS read-only, strukturalne sondowanie JSONB/tekstu bez wypisywania wartości, ClickHouse i MinIO bez narzędzi
metadata:
  type: feedback
---

Przy audytach PII/RODO (DB-060) obowiązywał twardy zakaz zmian i wypisywania wartości osobowych. Sprawdzone, działające sposoby:

- **psql tylko-do-odczytu:** `docker exec -i -e PGOPTIONS='-c default_transaction_read_only=on' cc-postgres psql -U ccapp -d contact_center -X` (SQL przez stdin/heredoc). Read-only blokuje nawet `CREATE TEMP TABLE` — sondy typu „funkcja w pg_temp" niemożliwe, a wywołanie funkcji z DML też się nie uda; dowody funkcji SQL zostają statyczne (`pg_proc.provolatile/prosrc`, `pg_trigger`, md5 `prosrc` vs plik migracji).
- **JSONB bez wartości:** `jsonb_object_keys` + `jsonb_typeof` z `GROUP BY` (klucze × typ × liczność); teksty: `length`, `count(*) FILTER (WHERE col ~ '<regexp>')` (e-mail, ≥9 cyfr, 11 cyfr, 13–19 cyfr, słowa „ul./aleja", kod pocztowy); klucze S3/adresy: `regexp_replace(..., uuid → '<uuid>', cyfry → 'N')` żeby pokazać kształt.
- **Pokrycie powiązań** mierz przez `count(*) FILTER (WHERE EXISTS (...))` dla każdej ścieżki (customer_id / last_contact_id / origin_contact_id / dopasowanie telefonu-adresu w tym samym tenancie) — dosłowne porównanie po `lower()` wystarcza w demo.
- **ClickHouse:** `docker exec cc-clickhouse clickhouse-client --query "SELECT … FROM system.columns/system.tables …"` (bez hasła, user default).
- **MinIO (`cc-minio`):** w kontenerze NIE ma `find`, `grep`, `sed`, `which`; liczenie obiektów: pętla `ls -R "$dir" | while read l; do case "$l" in xl.meta) echo x;; esac; done | wc -l` (każdy obiekt = katalog z `xl.meta`). Bucket: `contact-center-recordings` (prefiksy: `{tenantId}/…`, `email-attachments/`, `plugins/`); `minio/mc:latest` jest w lokalnych obrazach, ale nie był potrzebny.
- **`track_functions = none`** w tej bazie → `pg_stat_user_functions` nie dowodzi braku wywołań; dowodem jest grep repo + `pg_proc.prosrc`/`pg_views.definition`.
- Helper w scratchpadzie sesji (`ro.sh`) — nie jest częścią repo.

**Why:** wymóg WP-4/DB-060 (zero zmian, zero PII w notatce). **How to apply:** kolejne audyty danych osobowych/retencji — użyj tych samych technik; liczby z demo (2 klientów) opisuj jako ilustrację mechanizmu, nie skali.

Powiązane: [[project-db060-gdpr-pii-audit]], [[feedback_scratch_and_catalog_gotchas]]
