---
name: feedback_micrometer_gauge_weak_reference
description: Pułapka przy testowaniu kodu czytającego MeterRegistry (Micrometer) — gauge() z ephemeral literałem daje losowo NaN
type: feedback
---

`MeterRegistry.gauge(name, tags, number)` / `Gauge.builder(name, stateObject, fn)` trzyma tylko
**WeakReference** do przekazanego obiektu stanu (celowo — Micrometer nie chce trzymać silnych referencji
do dowolnych obiektów aplikacji, żeby nie wyciekać pamięci). Jeśli w teście zarejestrujesz gauge z
efemerycznym boxed literałem (np. `meterRegistry.gauge("jvm.memory.used", Tags.of("area","heap"), 512.0)`
gdzie `512.0` nie jest trzymane przez żadne inne pole), GC może zebrać ten obiekt między rejestracją a
odczytem w teście — `gauge.value()` zwraca wtedy `NaN` zamiast oczekiwanej wartości. Objaw: flaky test,
losowo (zależnie od GC), z `NumberFormatException: Character N is neither a decimal digit...` gdy `NaN`
trafia do `BigDecimal.valueOf()`.

**Why:** Odkryte przy pisaniu testów dla `AdminMetricsServiceImpl.getSystemResourceMetrics()` — jeden
test na kilka nagle failował z tym stack trace'em mimo identycznego setupu co inne przechodzące testy.

**How to apply:**
1. W testach: trzymaj wartości przekazywane do `meterRegistry.gauge(...)` jako **pola instancji** klasy
   testowej (nie lokalne literały w metodzie) — obiekt testowy żyje przez cały czas trwania testu, więc
   pole jest silną referencją i nie zostanie zebrane przez GC.
2. W kodzie produkcyjnym czytającym gauge'e: zawsze sprawdzaj `Double.isNaN(value)` i traktuj jak brak
   metryki (graceful degradation, zwróć 0.0/domyślną wartość) — nie zakładaj, że `gauge.value()` nigdy
   nie zwróci `NaN` nawet w produkcji (ten sam mechanizm WeakReference obowiązuje zawsze, nie tylko
   w testach, choć w prod jest znacznie mniej prawdopodobny bo metryki JVM/Actuator trzymają swoje
   referencje).
