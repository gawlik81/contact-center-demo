---
name: feedback_jdbc_query_overload_ambiguous_lambda
description: Spring JdbcTemplate.query(sql, PreparedStatementSetter, X) ma 2 przeciążenia (RowCallbackHandler / ResultSetExtractor<T>) — lambda bez jawnego rzutu jest AMBIGUOUS (błąd kompilacji)
metadata:
  type: feedback
---

Wywołanie `jdbcTemplate.query(sql, ps -> ..., rs -> ...)` z DWIEMA lambdami (PreparedStatementSetter
+ coś) rzuca błąd kompilacji „reference to query is ambiguous", bo `JdbcTemplate` ma jednocześnie:
`query(String, PreparedStatementSetter, RowCallbackHandler)` i
`<T> query(String, PreparedStatementSetter, ResultSetExtractor<T>)`. Javac nie jest w stanie
wywnioskować, którego interfejsu funkcyjnego ma użyć druga lambda, nawet jeśli oba ciała lambd są
składniowo poprawne dla obu typów (np. `rs -> result.put(...)` wygląda jak poprawny zarówno
`RowCallbackHandler.processRow` jak i fragment `ResultSetExtractor.extractData`).

**Why:** odkryte przy BE-138 (`RlsValidationService#fetchPolicyCommandsByTable`/
`fetchRlsFlagsByTable`) — kod wygląda poprawnie, ale `mvn compile` pada z dwuznacznością na obu
lambdach.

**How to apply:** przy `jdbcTemplate.query(sql, PreparedStatementSetter, <lambda>)` zawsze rzutuj
JAWNIE drugi argument na docelowy interfejs funkcyjny, np.
`(RowCallbackHandler) rs -> result.put(rs.getString("x"), ...)` albo
`(PreparedStatementSetter) ps -> ps.setArray(1, ...)`. Dotyczy też bindowania tablic Postgresa do
`= ANY(?)`: `ps.setArray(1, ps.getConnection().createArrayOf("text", list.toArray()))` jako
`PreparedStatementSetter` — żadnego specjalnego `Types.ARRAY` nie trzeba, `createArrayOf` wystarcza.
