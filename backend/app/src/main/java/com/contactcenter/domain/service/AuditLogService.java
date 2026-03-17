package com.contactcenter.domain.service;

import com.contactcenter.domain.model.AuditLogEvent;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Serwis odpowiedzialny za publikowanie zdarzeń audytowych do RabbitMQ.
 *
 * <p>Publikacja jest asynchroniczna ({@code @Async}) – nie blokuje głównego wątku HTTP.
 * Wiadomości trafiają do exchange {@code cc.audit} z routing key {@code audit.entity.changed},
 * skąd konsument ({@code AuditLogConsumer}) pobiera je i zapisuje do bazy danych.
 *
 * <p>Błędy publikacji są logowane (nie rzucane) – audit log nie powinien przerywać
 * operacji biznesowych (fire-and-forget).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publikuje zdarzenie audytowe do RabbitMQ asynchronicznie.
     *
     * <p>Metoda jest nieblokująca – wywołanie wraca natychmiast,
     * a publikacja odbywa się w puli wątków {@code cc-async-*}.
     *
     * <p>Błędy (brak połączenia z RabbitMQ, timeout) są logowane na poziomie ERROR
     * i nie propagowane do wywołującego.
     *
     * @param event zdarzenie audytowe do opublikowania – nie może być null
     */
    @Async("applicationTaskExecutor")
    public void publishAuditEvent(AuditLogEvent event) {
        if (event == null) {
            log.warn("[AuditLog] Próba publikacji null zdarzenia audytowego – ignoruję");
            return;
        }

        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_AUDIT,
                    AuditLogEvent.ROUTING_KEY,
                    event
            );
            log.debug("[AuditLog] Opublikowano zdarzenie: action={}, entityType={}, entityId={}",
                    event.action(), event.entityType(), event.entityId());
        } catch (AmqpException e) {
            // Nie przerywamy operacji biznesowej – audit log jest drugorzędny
            log.error("[AuditLog] Błąd publikacji zdarzenia audytowego: action={}, entityType={}, error={}",
                    event.action(), event.entityType(), e.getMessage(), e);
        }
    }
}
