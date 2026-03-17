---
name: JSONB mapowanie w Hibernate 6 – zawsze @JdbcTypeCode, nigdy @Convert
description: Hibernate 6 + PostgreSQL JDBC zwraca JSONB jako PGobject, nie String – @Convert(AttributeConverter) rzuca ClassCastException powodując HTTP 403
type: feedback
---

Nigdy nie używaj `@Convert(converter = ...)` z `AttributeConverter<T, String>` dla kolumn JSONB w PostgreSQL.

Hibernate 6 przez JDBC dostaje `PGobject` dla kolumny JSONB, nie `String`. `AttributeConverter<T, String>` oczekuje `String` jako typ DB → `ClassCastException` przy SELECT → wyjątek połykany w `JwtAuthFilter` catch(Exception) → SecurityContext nie jest ustawiany → HTTP 403 dla wszystkich użytkowników.

**Fix:** Zawsze używaj `@JdbcTypeCode(SqlTypes.JSON)` – Hibernate 6 obsługuje `PGobject ↔ Java type` natywnie:

```java
// Dla Map<String, Object>:
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "config", columnDefinition = "jsonb")
private Map<String, Object> config;

// Dla List<String>:
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "skills", columnDefinition = "jsonb")
private List<String> skills;
```

**Why:** Naprawiane dwukrotnie: raz dla `Tenant.config` (BE-006, opisane w PROGRESS.md "JsonMapConverter → @JdbcTypeCode"), drugi raz dla `AppUser.skills` (BE-008, objawiało się jako HTTP 403 dla SUPERVISOR – ClassCastException połykany przez JwtAuthFilter catch block).

**How to apply:** Każda nowa kolumna JSONB w encji → `@JdbcTypeCode(SqlTypes.JSON)`. Klasy `JsonStringListConverter` i `JsonMapConverter` nie są używane przez encje JPA.
