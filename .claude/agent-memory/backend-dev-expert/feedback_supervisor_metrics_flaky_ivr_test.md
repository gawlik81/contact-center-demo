---
name: supervisor-metrics-flaky-ivr-test
description: SupervisorMetricsServiceTest$KpiCallsInIvrTests jest order-dependent/flaky niezależnie od zmian w innych nested klasach
type: feedback
---

`SupervisorMetricsServiceTest$KpiCallsInIvrTests` (testy `shouldCountOnlyIvrSessionsBelongingToTenant` i `shouldSkipKeyWithInvalidJson`,
mockujące `RedisConnection`/`Cursor`/`RedisKeyCommands` przez `mockIvrSessionScan()`) bywają flaky/order-dependent –
losowo failują (`expected: N but was: 0`) gdy w tym samym forku JVM uruchamiane są inne klasy testowe
(np. `WaitTimeEstimationServiceTest`, `ContactRepositoryUpdateIfNotTerminalTest`) lub dodawane są nowe `@Nested` klasy
do `SupervisorMetricsServiceTest`, zmieniające kolejność wykonania.

**Why:** Zweryfikowane empirycznie (2026-06-12) – failuje to też na czystym `main`/`telco-review` (przed BE zmianą avg_wait_time),
gdy uruchamia się 3 klasy testowe razem (50 testów, 2 failures). W izolacji (`-Dtest=...KpiCallsInIvrTests`) zawsze przechodzi.
Przyczyna prawdopodobnie w sposobie mockowania `stringRedisTemplate.execute(RedisCallback, boolean)` z ręcznie tworzonym
mockiem `RedisConnection`/`Cursor` – jakiś global Mockito/bytebuddy state bleed między testami w jednym forku.

**How to apply:** Jeśli `mvn test -pl app` (cały moduł lub batch klas) zgłasza failure w `KpiCallsInIvrTests`,
NIE traktować jako regresji wprowadzonej przez bieżącą zmianę – zweryfikować uruchamiając tę klasę w izolacji
(`mvn test -pl app -Dtest='com.contactcenter.domain.SupervisorMetricsServiceTest$KpiCallsInIvrTests'`).
Jeśli przechodzi w izolacji, problem jest pre-existing i niezależny od zmiany. Docelowo wymaga refaktoryzacji
`mockIvrSessionScan()` (np. użycie `@Mock` pól zamiast ręcznych `mock()` w metodzie pomocniczej, lub `@TestInstance(PER_METHOD)`
weryfikacja, czy mocki są poprawnie resetowane).
