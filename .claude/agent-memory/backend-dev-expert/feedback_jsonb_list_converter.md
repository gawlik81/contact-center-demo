---
name: JSONB List<String> bez hypersistence-utils
description: Jak mapować JSONB tablicę stringów w JPA bez dodatkowej zależności hypersistence-utils
type: feedback
---

Projekt nie używa hypersistence-utils. Do mapowania JSONB `List<String>` w encji JPA używaj własnego `AttributeConverter`:

```java
@Convert(converter = JsonStringListConverter.class)
@Column(name = "skills", columnDefinition = "jsonb", nullable = false)
private List<String> skills = new ArrayList<>();
```

Konwerter w `infrastructure/persistence/JsonStringListConverter.java` – analogiczny do istniejącego `JsonMapConverter.java`.

**Why:** hypersistence-utils nie jest w pom.xml projektu. Dodanie `@Type(JsonType.class)` spowoduje błąd kompilacji.

**How to apply:** Zawsze dla JSONB string[] używaj `JsonStringListConverter`. Dla JSONB obiektu (Map) używaj istniejącego `JsonMapConverter`. Nie dodawaj hypersistence-utils jako zależności.
