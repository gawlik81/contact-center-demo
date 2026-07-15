---
name: feedback_jvm_heap_uptime_metrics_source
description: heap used/max i uptime w AdminMetricsServiceImpl muszą być czytane przez java.lang.management, nie przez Micrometer MeterRegistry
type: feedback
---

W `AdminMetricsServiceImpl.getSystemResourceMetrics()` (`GET /api/admin/metrics/resources`)
`heapUsedBytes`, `heapMaxBytes` i `uptimeSeconds` są czytane przez
`java.lang.management.ManagementFactory` (`getMemoryMXBean().getHeapMemoryUsage()` i
`getRuntimeMXBean().getUptime()`), **nie** przez `findGaugeValue()` (Micrometer `MeterRegistry`).

**Why:** Micrometer `JvmMemoryMetrics` rejestruje `jvm.memory.max`/`jvm.memory.used` OSOBNO per
pula pamięci JVM (tag `id` = "G1 Eden Space", "G1 Survivor Space", "G1 Old Gen" — wszystkie
z `area=heap` przy G1GC, domyślnym collectorze w tym projekcie). Filtrowanie tylko po
`tag("area","heap")` bez `id` trafia na wiele gauge'y jednocześnie i `.gauge()` zwraca dowolną
z nich (kolejność niegwarantowana) — część pul G1 (Eden/Survivor) legalnie zwraca `-1` jako
"brak zdefiniowanego maksimum" (poprawne zachowanie G1, nie bug). Skutek: front pokazywał
"0 MB" / "0 dni 0 godz." bo `formatBytes(-1)` po zaokrągleniu wygląda jak 0. Analogicznie nazwa/
tagowanie `process.uptime` w Micrometer/Spring Boot 3.3.5 nie zgadzała się z tym czego szukał kod,
więc `findGaugeValue` cicho degradowało do 0.0 (log tylko na `debug`, nie `warn`).

**How to apply:**
1. Dla heap: użyj `ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()`/`.getMax()`
   — to CAŁY heap, jedna spójna liczba, bez per-pula ambiguity. Użyj tego dla OBU wartości
   (used i max), żeby pochodziły z tego samego źródła i się nie rozjeżdżały. Jeśli `getMax()`
   zwróci `-1` (możliwe bez ustawionego `-Xmx`), zwróć `0` zamiast `-1`.
2. Dla uptime: `ManagementFactory.getRuntimeMXBean().getUptime()` (millisekundy, podziel przez 1000).
3. CPU (`system.cpu.usage`), wątki (`jvm.threads.live`), pula DB Hikari
   (`hikaricp.connections.active`/`.max`) NADAL czytane przez `findGaugeValue()` (Micrometer) —
   te metryki nie mają per-pula ambiguity, działają poprawnie, nie zmieniaj ich.
4. W testach (`SimpleMeterRegistry`): heap/uptime NIE są już kontrolowalne przez
   `meterRegistry.gauge(...)` — asercjonuj sensowne wartości realnego procesu testowego JVM
   (`> 0`, `max >= used`) zamiast konkretnych liczb. Powiązane:
   [[feedback_micrometer_gauge_weak_reference]] (osobna pułapka — WeakReference przy testowaniu
   gauge'y, dotyczy pozostałych metryk czytanych przez `findGaugeValue()`).
