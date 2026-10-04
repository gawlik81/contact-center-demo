---
name: feedback-measurement-script-rls-tz
description: Skrypty pomiarowe (read-only) na cc-postgres — rola app_user bez GUC zwraca 0 wierszy BEZ BŁĘDU (cichy fałsz), pinować TimeZone UTC przy date_trunc, ANALYZE/ALTER w skrypcie zabronione nawet przy "tylko statystykach"
metadata:
  type: feedback
---

Przy pomiarach wolumenu/rozkładu danych tenantowych (DB-066) obowiązuje:

1. **Uruchamiaj skrypt rolą z BYPASSRLS (ccapp w demo) albo superuserem.** `app_user` bez `app.current_tenant_id` dostaje `count(*) = 0` bez błędu (zweryfikowane: `SET ROLE app_user; SELECT count(*) FROM email_message` → 0 przy 55 wierszach). Wynik wygląda jak „pusta tabela", więc jest niebezpieczny. Dodaj do nagłówka skryptu wymóg roli i sprawdzenie `rolbypassrls`.
2. **Ustaw `SET TIME ZONE 'UTC'` zaraz po `SET default_transaction_read_only = on`.** `date_trunc('month', timestamptz)` zależy od strefy sesji; hosty bywają w Europe/Warsaw, baza w UTC (zgodnie z [[feedback-flyway-manual-timezone]]).
3. **Nie używaj w skrypcie słów `ANALYZE`/`VACUUM` ani nie licz na „nieinwazyjne" statystyki.** `ANALYZE` zapisuje `pg_statistic`, więc łamie zasadę tylko-do-odczytu. Wynik `pg_stat_user_tables` (n_live_tup) bywa 0 mimo danych — oznacz go jako nieaktualny, zamiast go „naprawiać".
4. **Weryfikacja stanu:** przed i po uruchomieniu policz `count(*)` oraz `md5(string_agg(t::text, '|' ORDER BY pk))` dla każdej czytanej tabeli. Fingerprint zapisz w scratchpadzie, nie w repo (przez pomyłkę zostawiłem plik w katalogu głównym repo — przenieś od razu).
5. **Grep na DDL/DML** po skrypcie: słowa `insert|update|delete|create|alter|drop|truncate|grant|revoke|vacuum|analyze|copy|merge` także w komentarzach — komentarze z tymi słowami dają fałszywe alarmy w przeglądzie.

**Why:** DB-066 (2026-10-04): cichy 0 wierszy przy `app_user` byłby niewykrywalny; dwa z pięciu punktów wyszły przy weryfikacji po napisaniu skryptu.
**How to apply:** każdy skrypt pomiarowy na danych tenantowych w tym repo. Połączenie: `docker exec -i -e PGOPTIONS='-c default_transaction_read_only=on' cc-postgres psql -U ccapp -d contact_center -X` (patrz [[feedback-readonly-audit-technique]]). Guard zweryfikowany: `CREATE TEMP TABLE` w tej sesji kończy się `read-only transaction`.
