---
name: feedback_rls_insert_vs_update_semantics
description: PostgreSQL RLS bez stosowalnej polityki — SELECT/UPDATE/DELETE filtrują cicho do 0 wierszy, ale INSERT rzuca twardy błąd (nie cichy no-op)
metadata:
  type: feedback
---

Gdy tabela ma `ENABLE ROW LEVEL SECURITY` i dla danej komendy (SELECT/UPDATE/DELETE/INSERT) NIE ISTNIEJE żadna stosowalna polityka (ani `FOR <command>`, ani `FOR ALL`):
- **SELECT/UPDATE/DELETE:** rola bez `BYPASSRLS` widzi/dopasowuje ZERO wierszy — cicho, bez błędu (efekt jak `WHERE false`).
- **INSERT:** PostgreSQL odrzuca CAŁY wiersz błędem `ERROR: new row violates row-level security policy for table "<t>"` (SQLSTATE `42501`) — TWARDY błąd, nie cichy no-op.

**Why:** odkryte przy DB-062 (`anonymize_customer` V096) — `audit_log` ma od V012 WYŁĄCZNIE politykę `FOR SELECT` (brak INSERT). Zlecenie zakładało, że pod `SET ROLE app_user` (bez `BYPASSRLS`) `audit_log` zachowa się jak `email_message`/`social_message` (cicho 0 wierszy), ale ponieważ `anonymize_customer` kończy się `INSERT INTO audit_log`, CAŁE wywołanie w trybie rzeczywistym rzuca i cofa się w całości pod `app_user`. Zweryfikowane empirycznie na jednorazowej bazie `scratch_rls_check` (utworzonej i usuniętej w tej samej sesji, żywa baza `contact_center` nietknięta) — patrz notatka wykonania DB-062 w TASKS-DATABASE.md.

**How to apply:** przy projektowaniu/testowaniu funkcji SQL, które MUTUJĄ dane pod rolą ograniczoną RLS (`app_user`), zawsze rozróżniaj INSERT od UPDATE/DELETE przy przewidywaniu skutków braku polityki zapisu — nie zakładaj, że wszystkie "brakujące polityki zapisu" objawią się tak samo. Jeśli funkcja robi zarówno UPDATE (na tabelach bez polityki UPDATE) JAK I na końcu INSERT do tabeli bez polityki INSERT (typowy wzorzec: `INSERT INTO audit_log` jako ostatni krok), to test pod `app_user` w trybie "wywołaj całą funkcję i odczytaj liczniki z JSONB" NIE ZADZIAŁA (funkcja nigdy nie zwróci wyniku) — trzeba albo testować UPDATE/DELETE per tabela BEZPOŚREDNIM SQL (nie przez funkcję), albo tymczasowo załatać brakującą politykę INSERT WYŁĄCZNIE w fixturze testowej (nigdy w migracji), żeby zaobserwować resztę zachowania w izolacji od tej jednej, osobnej luki.
