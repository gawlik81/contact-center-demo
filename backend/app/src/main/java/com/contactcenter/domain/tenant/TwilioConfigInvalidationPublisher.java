package com.contactcenter.domain.tenant;

import com.contactcenter.infrastructure.cache.CacheInvalidationBus;
import com.contactcenter.infrastructure.cache.CacheInvalidationChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes the cross-instance invalidation of the Twilio client cache (BE-179) AFTER the configuration change
 * is committed - a replica that rebuilt its client before the commit would cache the old credentials again.
 *
 * <p>Payload = tenantId only (see {@link CacheInvalidationBus}); never credentials. A Redis failure is absorbed by
 * the bus (log + metric) so it cannot fail the already committed configuration change.
 */
@Component
@RequiredArgsConstructor
public class TwilioConfigInvalidationPublisher {

    private final CacheInvalidationBus bus;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onTwilioConfigChanged(TwilioConfigChangedEvent event) {
        bus.publish(CacheInvalidationChannel.TWILIO_CLIENT, event.tenantId());
    }
}
