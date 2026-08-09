---
name: feedback_flyway_manual_timezone
description: Ręczne uruchamianie flyway-maven-plugin z hosta wymaga MAVEN_OPTS="-Duser.timezone=UTC", inaczej ciche przesunięcie granic partycji TIMESTAMPTZ
metadata:
  type: feedback
---

Przy ręcznym stosowaniu migracji Flyway z hosta przez `org.flywaydb:flyway-maven-plugin:migrate`
(wzorzec z [[contact_center_project]] — bridge IP `172.18.0.11:5432`, bo `cc-postgres` w tym
środowisku nie publikuje portu 5432 na hosta), ZAWSZE uruchamiaj z:

```bash
MAVEN_OPTS="-Duser.timezone=UTC" mvn -o org.flywaydb:flyway-maven-plugin:10.20.1:migrate \
  -Dflyway.url="jdbc:postgresql://172.18.0.11:5432/contact_center" \
  -Dflyway.user=ccapp -Dflyway.password='...' \
  -Dflyway.locations=filesystem:/home/pawelm/contact-center/backend/src/main/resources/db/migration \
  -Dflyway.table=flyway_schema_history
```

**Why:** Odkryte przy DB-052/V088 (~1h debugowania). PGJDBC domyślnie synchronizuje sesję
PostgreSQL (`SET TimeZone`) ze strefą `java.util.TimeZone.getDefault()` klienta JVM. Maszyna
hosta tego repo ma strefę `Europe/Warsaw` (+01/+02 z DST), ale WSZYSTKIE dane/granice partycji
w tej bazie są tworzone w UTC — tak łączy się prawdziwa aplikacja (`cc-backend`, kontener ma
`TZ=UTC`, zweryfikowane `docker exec cc-backend date`). Bez wymuszenia UTC, migracja tworząca
cokolwiek z literałem daty rzutowanym na `TIMESTAMPTZ` (typowo `EXECUTE format('... FOR VALUES
FROM (%L) TO (%L)', ...)` w funkcjach `create_*_partition`) dostaje granicę przesuniętą o 1-2h
względem istniejących, UTC-owych partycji — objawia się jako `ERROR: partition "..." would
overlap partition "..."` (PostgreSQL), mimo że IDENTYCZNA sekwencja SQL przechodzi bezbłędnie
przez `docker exec cc-postgres psql` (bo psql w kontenerze dziedziczy strefę serwera = UTC).
To NIE dotyczy tylko partycji — każdy `DEFAULT NOW()`/literał daty zapisany podczas migracji
ręcznie odpalonej z hosta byłby cicho przesunięty, bez żadnego błędu (partycje akurat rzucają
błąd bo mają CHECK na zakres, ale zwykłe dane by tego nie zrobiły — dużo bardziej podstępne).

**Co NIE zadziałało (ślepe tropy, nie próbuj ponownie):**
- `?options=-c%20TimeZone=UTC` w JDBC URL — PGJDBC i tak nadpisuje to własnym `SET TimeZone`
  po connect, oparty na strefie klienta JVM.
- Rozbicie `ALTER TABLE ... DETACH/ATTACH PARTITION` + `CREATE TABLE ... PARTITION OF` na
  osobne top-level statementy vs. połączenie w jeden atomowy blok `DO $$ ... $$` — obie wersje
  dają IDENTYCZNY błąd pod złą strefą i obie działają pod poprawną. To nie jest kwestia
  grupowania/kolejności statementów przez Flyway, wyłącznie strefy czasowej sesji.

**Jak zdiagnozować podobny przypadek w przyszłości:** tymczasowy
`RAISE EXCEPTION 'DIAG: tz=%', current_setting('TimeZone');` na początku podejrzanego bloku
migracji, uruchomiony przez `flyway:migrate` (nie przez psql — psql nie odtwarza tego bugu).
Jeśli zwróci coś innego niż `UTC`, to jest to.

**How to apply:** Przy KAŻDYM przyszłym ręcznym stosowaniu migracji przez `flyway-maven-plugin`
z hosta w tym repo (nie tylko migracje partycjonujące) — zawsze `MAVEN_OPTS="-Duser.timezone=UTC"`.
Rozważ dodanie tego jako stały nawyk/alias, nie tylko pamiętać ad-hoc.
