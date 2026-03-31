---
name: JdbcTemplate + java.time.Instant — pgjdbc nie rozumie Instant
description: Przekazanie java.time.Instant bezpośrednio do JdbcTemplate.update() rzuca PSQLException i jest opakowane w BadSqlGrammarException; wymaga jawnej konwersji na java.sql.Timestamp
type: feedback
---

Przekazywanie `java.time.Instant` jako parametru pozycyjnego do `JdbcTemplate.update()` rzuca:

```
Caused by: org.postgresql.util.PSQLException:
  Can't infer the SQL type to use for an instance of java.time.Instant.
  Use setObject() with an explicit Types value to specify the type to use.
```

Spring opakuje to w `BadSqlGrammarException` — nazwa myląca, bo problem nie dotyczy składni SQL lecz nieobsługiwanego typu Java przez sterownik `pgjdbc`.

**Why:** Sterownik pgjdbc nie ma wbudowanego mapowania dla `java.time.Instant`. Hibernate/JPA obsługuje Instant wewnętrznie, ale `JdbcTemplate` z parametrami pozycyjnymi przekazuje obiekt bezpośrednio do `setObject()` JDBC, gdzie pgjdbc nie potrafi wywnioskować odpowiedniego typu SQL.

**How to apply:** Zawsze gdy w natywnym `JdbcTemplate.update()` lub `query()` przekazywana jest wartość `Instant`, konwertuj ją przez `java.sql.Timestamp.from(instant)` lub `instant != null ? java.sql.Timestamp.from(instant) : null`. Wzorzec stosowany konsekwentnie w `ContactRepository.updateContactStatusOnTelephonyEvent`. Hibernatowe `@Query` i `EntityManager` nie mają tego problemu.
