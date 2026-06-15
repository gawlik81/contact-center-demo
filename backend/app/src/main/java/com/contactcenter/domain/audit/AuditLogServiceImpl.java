package com.contactcenter.domain.audit;

import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class AuditLogServiceImpl implements AuditLogService {

    private final RabbitTemplate rabbitTemplate;
    private final AuditLogRepository auditLogRepository;

    @Override
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

    @Override
    public Page<AuditLog> findAuditLogs(UUID tenantId, String entityType, UUID userId,
                                         Instant dateFrom, Instant dateTo, Pageable pageable) {
        return auditLogRepository.findByFilters(tenantId, entityType, userId, dateFrom, dateTo, pageable);
    }
}
