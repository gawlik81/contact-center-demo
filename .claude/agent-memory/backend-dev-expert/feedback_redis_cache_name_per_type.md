---
name: feedback_redis_cache_name_per_type
description: W RedisConfig każdy @Cacheable cache name musi mieć swój własny DTO typ i dedykowany Jackson2JsonRedisSerializer — nigdy nie współdziel jednego cache name między dwoma różnymi typami zwracanymi, nawet jeśli różnicujesz je tylko przez key.
metadata:
  type: feedback
---

W `backend/app/src/main/java/com/contactcenter/infrastructure/config/RedisConfig.java` każdy cache name
zarejestrowany w `cacheConfigurations` (metoda `redisCacheManager`) ma dedykowany
`Jackson2JsonRedisSerializer<T>` TYPOWANY na jedną konkretną klasę DTO (wzorzec: `ADMIN_METRICS`→
`AdminMetricsResponse`, `ADMIN_METRICS_USAGE`→`UsageMetrics`, `ADMIN_METRICS_GROWTH`→`GrowthMetrics`,
`ADMIN_METRICS_CHANNEL_BREAKDOWN`→`ContactChannelMatrix`).

**Why:** Wpadka z 2026-07: `AdminMetricsServiceImpl.getContactChannelMatrix()` dostał
`@Cacheable(cacheNames = ADMIN_METRICS_USAGE, key = "'channel-breakdown'")` — różny klucz w tym samym
cache name co `getUsageMetrics()` (`UsageMetrics`). Serializer jest przypięty do cache name, NIE do
klucza, więc Spring próbował deserializować JSON `ContactChannelMatrix` (ma pole `channels`) przez
deserializator `UsageMetrics` → `UnrecognizedPropertyException` przy KAŻDYM odczycie w działającym
kontenerze. Naprawiono dodając osobny cache name `admin-metrics-channel-breakdown` +
`Jackson2JsonRedisSerializer<ContactChannelMatrix>` (patrz commit poprawki).

**How to apply:** Zanim dodasz `@Cacheable` do nowej metody zwracającej NOWY typ DTO w kontekście admin
metrics (albo dowolnym innym miejscu z typowanym serializerem w `RedisConfig`) — zawsze dodaj nowy cache
name + serializer + wpis w `cacheConfigurations`, nawet jeśli logicznie "pasuje" do istniejącej kategorii
(np. TTL). Różne klucze w jednym cache name są OK tylko gdy wszystkie klucze tego cache name zwracają ten
sam typ. Po naprawie takiego buga w działającym kontenerze — zatruty wpis w Redis pod starym
cache-name::key zniknie sam po restarcie kontenera/aplikacji (bo poprawiony kod czyta/zapisuje już pod
nowym kluczem cache name), ręczny `DEL`/`FLUSHDB` nie jest konieczny, chyba że chcemy odzyskać miejsce
natychmiast.
