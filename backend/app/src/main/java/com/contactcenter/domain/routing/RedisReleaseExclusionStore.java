package com.contactcenter.domain.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed {@link ReleaseExclusionStore}: a SET per tenant+contact
 * ({@code routing:release-excl:{tenantId}:{contactId}}) holding agent ids, with a TTL
 * ({@code routing.release-exclusion-ttl}, default 5 minutes) refreshed on every release.
 * A second release by another agent therefore adds to the set. Redis errors are logged and swallowed.
 */
@Slf4j
@Component
class RedisReleaseExclusionStore implements ReleaseExclusionStore {

    static final String KEY_PREFIX = "routing:release-excl:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    RedisReleaseExclusionStore(StringRedisTemplate redisTemplate,
                               @Value("${routing.release-exclusion-ttl:5m}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    @Override
    public void exclude(UUID tenantId, UUID contactId, UUID agentId) {
        String key = key(tenantId, contactId);
        try {
            redisTemplate.opsForSet().add(key, agentId.toString());
            redisTemplate.expire(key, ttl);
        } catch (Exception e) {
            log.warn("[ReleaseExclusion] Nie udało się zapisać wykluczenia (routing bez wykluczenia): " +
                    "contactId={}, agentId={}, error={}", contactId, agentId, e.getMessage());
        }
    }

    @Override
    public Set<UUID> excludedAgents(UUID tenantId, UUID contactId) {
        try {
            Set<String> members = redisTemplate.opsForSet().members(key(tenantId, contactId));
            if (members == null || members.isEmpty()) {
                return Set.of();
            }
            Set<UUID> result = new HashSet<>();
            for (String member : members) {
                try {
                    result.add(UUID.fromString(member));
                } catch (IllegalArgumentException ignored) {
                    log.warn("[ReleaseExclusion] Pomijam niepoprawny wpis wykluczenia: contactId={}", contactId);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[ReleaseExclusion] Nie udało się odczytać wykluczeń (traktuję jako brak): " +
                    "contactId={}, error={}", contactId, e.getMessage());
            return Set.of();
        }
    }

    private static String key(UUID tenantId, UUID contactId) {
        return KEY_PREFIX + tenantId + ":" + contactId;
    }
}
