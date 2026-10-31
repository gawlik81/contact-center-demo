package com.contactcenter.domain.social;

import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Chooses the default queue for new social contacts of a tenant (BE-155).
 *
 * <p>Same convention as e-mail ({@code email_default_queue_id}), no schema change:
 * <ol>
 *   <li>{@code tenant.config["social_default_queue_id"]} - explicit choice; must point to an
 *       ACTIVE queue of the SAME tenant, otherwise it is ignored with a WARN;</li>
 *   <li>fallback: the first active queue of the tenant (deterministic: queue name ascending),
 *       WARN so the admin knows the choice was implicit;</li>
 *   <li>no active queue at all: {@link Optional#empty()} + WARN. The caller keeps the contact
 *       QUEUED (it is re-resolved on the next routing pass) instead of ending it with ERROR.</li>
 * </ol>
 * Must be called with {@code TenantContext} set for {@code tenantId} (RLS).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialDefaultQueueResolver {

    /** Key in {@code tenant.config} holding the UUID of the default social queue. */
    public static final String CONFIG_KEY = "social_default_queue_id";

    private final TenantService tenantService;
    private final QueueService queueService;

    /**
     * @param tenantId tenant of the social contact
     * @return queue id to assign, or empty when the tenant has no usable queue
     */
    public Optional<UUID> resolve(UUID tenantId) {
        Optional<UUID> configured = resolveConfigured(tenantId);
        if (configured.isPresent()) {
            return configured;
        }

        Optional<Queue> fallback = queueService.getAllQueues(tenantId).stream()
                .filter(Queue::isActive)
                .findFirst();
        if (fallback.isPresent()) {
            log.warn("[SocialDefaultQueue] Tenant {} has no valid {} in tenant.config - using the first " +
                            "active queue {} ({}). Set {} to choose explicitly.",
                    tenantId, CONFIG_KEY, fallback.get().getQueueId(), fallback.get().getName(), CONFIG_KEY);
            return Optional.of(fallback.get().getQueueId());
        }

        log.warn("[SocialDefaultQueue] Tenant {} has no active queue - social contact stays QUEUED " +
                "without a queue until a queue exists", tenantId);
        return Optional.empty();
    }

    private Optional<UUID> resolveConfigured(UUID tenantId) {
        Map<String, Object> config = tenantService.findTenantEntity(tenantId)
                .map(Tenant::getConfig)
                .orElse(null);
        Object raw = config != null ? config.get(CONFIG_KEY) : null;
        if (raw == null) {
            return Optional.empty();
        }

        UUID queueId;
        try {
            queueId = UUID.fromString(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            log.warn("[SocialDefaultQueue] Invalid {} in tenant.config of tenant {}: {}",
                    CONFIG_KEY, tenantId, raw);
            return Optional.empty();
        }

        // findQueueEntity filters by tenant_id, so a foreign tenant's queue is never accepted
        Optional<Queue> queue = queueService.findQueueEntity(queueId, tenantId).filter(Queue::isActive);
        if (queue.isEmpty()) {
            log.warn("[SocialDefaultQueue] {}={} of tenant {} does not point to an active queue of this tenant",
                    CONFIG_KEY, queueId, tenantId);
        }
        return queue.map(Queue::getQueueId);
    }
}
