/**
 * Wspólne jądro dla konsumentów RabbitMQ – {@link com.contactcenter.domain.messaging.TenantAwareConsumer}
 * zapewnia setup/teardown {@link com.contactcenter.security.TenantContext} na wątkach konsumenckich,
 * a {@link com.contactcenter.domain.messaging.DeadLetterConsumer} obsługuje Dead Letter Queue.
 *
 * <p>Analogicznie do {@code com.contactcenter.domain.repository} (wspólne jądro repozytoriów) –
 * klasy z tego pakietu mogą być rozszerzane/używane przez konsumentów w innych domenach.
 */
package com.contactcenter.domain.messaging;
