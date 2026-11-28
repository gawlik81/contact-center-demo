---
name: be245-degraded-reason-v153
description: Przepięcie przyczyny DEGRADED z licznika na kolumnę degraded_reason (V153) - enum DegradedReason, CAS-y, fallback legacy, 409 dla kwarantanny
metadata:
  type: project
---

- `DegradedReason {CIRCUIT_OPEN, CONFIG_LOAD_FAILED}` (domain.plugin), encja `degradedReason` = wartość SUROWA; interpretuj WYŁĄCZNIE przez `effectiveDegradedReason()` (null gdy status != DEGRADED; legacy: DEGRADED bez reason -> licznik >= CIRCUIT_FAILURE_THRESHOLD = CIRCUIT_OPEN). SQL-owy bliźniak: stałe `CIRCUIT_DEGRADED`/`CONFIG_DEGRADED` w repo (oba oznaczone "remove after strict CHECK migration").
- Repo: `updateHealthStatus(id,tenant,status,reason,count)` rzuca IAE przy niespójnej parze; `markDegradedByConfigLoad` NIE rusza licznika; service: `markDegradedByCircuit` zastąpił `updateHealthStatus` (CircuitBreakerState).
- Pierwszeństwo: obwód NADPISUJE CONFIG_LOAD_FAILED; config-fail nigdy nie nadpisuje CIRCUIT_OPEN (CAS z HEALTHY). `updateEnabled` nie dotyka health/reason. Nikt w Javie nie zapisuje DISABLED_BY_ADMIN (tylko stała).
- DTO `TenantPluginInstallationDto.degradedReason` (ostatni parametr rekordu, wartość efektywna) - 7 testów konstruują DTO pozycyjnie.
- `PluginQuarantinedException` -> 409 ProblemDetail w `GlobalExceptionHandler` (z `message` = detail, konwencja FE; `internalErrorResponse` 500 NIE ma `message`, więc `assertProblem` nie pasuje do 500).
- Pułapka: mapRow czyta degraded_reason z indeksu 11 (na końcu SELECT-ów); fixtures `buildRow` w 2 testach muszą mieć 12 kolumn.
- Doc: documentation/tech/04-backend.md §4.9.

**Why:** CLAUDE.md zakazuje przeciążonych kolumn; review V153 (tura 83). **How to apply:** nowy zapis health_status zawsze z parą (status, reason) w jednym UPDATE.
