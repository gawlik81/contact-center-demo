package com.contactcenter.domain.tenant;

import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared gate on the STATUS OF THE TENANT (BE-249, owner decision 4): {@code login}, {@code refresh}, STOMP CONNECT
 * and the HTTP {@code JwtAuthFilter} ask it whether the tenant of the user is still allowed to use the platform.
 *
 * <p>{@code updateTenant(INACTIVE)} deliberately does not deactivate the accounts of the tenant (only
 * {@code deactivateTenant} does), so without this gate a user of a deactivated tenant could log in with a password,
 * refresh a token or reconnect a WebSocket with a still-valid access token.
 *
 * <p>Cost control: a LOCAL Caffeine cache per instance, {@code tenant.status-gate.ttl} (default 30 s) after write.
 * The hot path (HTTP filter) is therefore a map lookup; the database is hit once per tenant per TTL per instance and
 * only the {@code status} column is read. Staleness: this instance invalidates the entry when it changes the status
 * ({@link #invalidate(UUID)}, after commit); OTHER instances learn it after at most the TTL (the existing WebSocket
 * sessions of a deactivated tenant are closed at once by BE-233 / the control channel). Reactivation therefore
 * restores access after at most the TTL on other instances and immediately on the instance that handled it.
 *
 * <p>SUPER_ADMIN has no tenant ({@code tenantId == null}) and is never blocked. An unknown tenant is not blocked
 * (the same as the pre-BE-249 refresh check: only an explicit {@code INACTIVE} blocks); SUSPENDED is not blocked
 * either (its semantics are not defined by this ticket).
 *
 * <p>A lookup failure (database down) propagates: callers fail closed (login/refresh = 500, CONNECT = WS_INTERNAL,
 * HTTP filter = no authentication = 401).
 */
@Slf4j
@Component
public class TenantStatusGate {

    /** Source of the status; a seam so that callers outside the package can be tested. */
    @FunctionalInterface
    public interface StatusLookup {
        Optional<TenantStatus> findStatus(UUID tenantId);
    }

    private final LoadingCache<UUID, Boolean> blockedByTenant;

    @Autowired
    public TenantStatusGate(TenantRepository tenantRepository,
                            @Value("${tenant.status-gate.ttl:PT30S}") Duration ttl) {
        this(tenantRepository::findStatusById, ttl, Ticker.systemTicker());
    }

    public TenantStatusGate(StatusLookup lookup, Duration ttl, Ticker ticker) {
        this.blockedByTenant = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(10_000)
                .ticker(ticker)
                .build(id -> lookup.findStatus(id).map(s -> s == TenantStatus.INACTIVE).orElse(false));
    }

    /** {@code true} when the tenant is INACTIVE. {@code null} (SUPER_ADMIN) is never blocked. */
    public boolean isBlocked(UUID tenantId) {
        if (tenantId == null) {
            return false;
        }
        return Boolean.TRUE.equals(blockedByTenant.get(tenantId));
    }

    /** Drops the cached verdict of this tenant on THIS instance. */
    public void invalidate(UUID tenantId) {
        if (tenantId != null) {
            blockedByTenant.invalidate(tenantId);
        }
    }

    /**
     * Invalidates now and, inside a transaction, again after the commit: a reader that cached the OLD status between
     * the first invalidation and the commit would otherwise keep it for the whole TTL.
     */
    void invalidateAroundCommit(UUID tenantId) {
        invalidate(tenantId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate(tenantId);
                }
            });
        }
    }
}
