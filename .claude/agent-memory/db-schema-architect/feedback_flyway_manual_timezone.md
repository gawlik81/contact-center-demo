---
name: feedback_flyway_manual_timezone
description: Ręczne uruchamianie migracji Flyway z hosta (RunFlyway.java) wymaga -Duser.timezone=UTC, inaczej ciche przesunięcie granic partycji TIMESTAMPTZ
metadata:
  type: feedback
---

**KOREKTA (2026-08-10, sesja DB-053/V089):** komenda `mvn org.flywaydb:flyway-maven-plugin:migrate`
pokazana niżej NIE DZIAŁA w tym repo wywołana ad-hoc z CLI — kończy się
`FlywayException: No database found to handle jdbc:postgresql://...` (plugin nie ma na
classpath sterownika `postgresql` ani `flyway-database-postgresql`, żaden `pom.xml` w repo nie
deklaruje `<plugin>` z tymi zależnościami — zweryfikowane grepem). Metoda faktycznie działająca to
`RunFlyway.java` opisana w [[contact_center_project]] (sekcja DB-053/V089) — mały program Javy
kompilowany lokalnie z jarami z `~/.m2`, `-Duser.timezone=UTC` przekazywane bezpośrednio do
`java`, nie przez `MAVEN_OPTS`. Poniższa treść (historyczna) zachowana dla kontekstu diagnozy
strefy czasowej, ale NIE kopiuj polecenia `mvn flyway-maven-plugin:migrate` 1:1 — użyj
`RunFlyway.java`.

Przy ręcznym stosowaniu migracji Flyway z hosta (wzorzec z [[contact_center_project]] — bridge IP
`172.18.0.11:5432`, bo `cc-postgres` w tym środowisku nie publikuje portu 5432 na hosta), ZAWSZE
uruchamiaj z wymuszoną strefą UTC na poziomie JVM, np.:

```bash
java -Duser.timezone=UTC -cp ".:<jary flyway-core/flyway-database-postgresql/postgresql/jackson>" RunFlyway
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

**How to apply:** Przy KAŻDYM przyszłym ręcznym stosowaniu migracji z hosta w tym repo (nie tylko
migracje partycjonujące), metodą `RunFlyway.java` — zawsze `-Duser.timezone=UTC` na `java`
uruchamiającym `RunFlyway`. Rozważ dodanie tego jako stały nawyk, nie tylko pamiętać ad-hoc.
