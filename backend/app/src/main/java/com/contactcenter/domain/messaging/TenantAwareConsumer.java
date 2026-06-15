package com.contactcenter.domain.messaging;

import com.contactcenter.security.TenantContext;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Klasa bazowa dla konsumentów RabbitMQ wymagających kontekstu tenanta.
 *
 * <p>Wątki RabbitMQ nie mają ustawionego {@link TenantContext} (brak HTTP filter).
 * Ta klasa zapewnia wzorzec setup/teardown TenantContext wokół logiki konsumenta,
 * eliminując duplikację kodu w każdym @RabbitListener.
 *
 * <p><strong>WAŻNE:</strong> Nie deklaruje @Transactional – transakcje są zarządzane
 * przez repozytoria (metody z @Transactional w warstwie repo). Dzięki temu AMQP ack
 * następuje po commicie transakcji repozytorium (Spring AMQP acknowledge-mode: auto).
 *
 * <p>Użycie:
 * <pre>
 * public class MyConsumer extends TenantAwareConsumer {
 *     &#64;RabbitListener(queues = "my.queue")
 *     public void handle(MyEvent event) {
 *         processWithTenant(event.tenantId(), () -> doHandle(event));
 *     }
 * }
 * </pre>
 */
@Slf4j
public abstract class TenantAwareConsumer {

    /**
     * Wykonuje zadanie w kontekście tenanta.
     *
     * <p>Ustawia {@link TenantContext} przed wykonaniem zadania i czyści go
     * w bloku {@code finally} – niezależnie od wyjątku.
     *
     * @param tenantId UUID tenanta; jeśli null – zadanie jest wykonywane bez kontekstu (z WARN)
     * @param task     logika do wykonania w kontekście tenanta
     */
    protected void processWithTenant(UUID tenantId, Runnable task) {
        if (tenantId == null) {
            log.warn("[TenantAwareConsumer] tenantId is null – skipping tenant context setup");
            task.run();
            return;
        }
        try {
            TenantContext.setTenantId(tenantId);
            task.run();
        } finally {
            TenantContext.clear();
        }
    }
}
