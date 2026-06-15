package com.contactcenter.domain.audit;

import com.contactcenter.domain.messaging.TenantAwareConsumer;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Konsument zdarzeń audytowych z kolejki RabbitMQ {@code cc.queue.audit-log}.
 *
 * <p>Nasłuchuje na zdarzenia opublikowane przez {@link AuditLogService}
 * i zapisuje je do tabeli {@code audit_log} w PostgreSQL przez natywne SQL
 * ({@link AuditLogRepository#insertAuditLog}).
 *
 * <p><strong>Odporność na błędy:</strong>
 * <ul>
 *   <li>Wiadomości, których nie udało się przetworzyć po wyczerpaniu retry,
 *       trafiają do Dead Letter Queue ({@code cc.queue.dead-letter}).</li>
 *   <li>Błędy serializacji (nieprawidłowy format JSON) są logowane i wiadomość
 *       jest odrzucana (nie requeued) – zapobiega nieskończonej pętli retry.</li>
 * </ul>
 *
 * <p><strong>Transakcyjność:</strong> Każdy zapis jest osobną transakcją zarządzaną
 * przez {@link AuditLogRepository#insertAuditLog} (metoda ma własny {@code @Transactional}).
 * Konsument NIE deklaruje własnego {@code @Transactional} – pozwala to uniknąć
 * race condition między commitem transakcji a AMQP acknowledge:
 * przy {@code acknowledge-mode: auto} Spring AMQP ackuje wiadomość po powrocie
 * z metody listenera, więc transakcja repozytorium jest już commitowana przed ack.
 * Gdyby konsument otwierał własną transakcję nadrzędną, ack mógłby nastąpić przed
 * commitem w skrajnych przypadkach (np. przy rollback po ack).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AuditLogConsumer extends TenantAwareConsumer {

    private final AuditLogRepository auditLogRepository;

    /**
     * Przetwarza zdarzenie audytowe i zapisuje je do bazy danych.
     *
     * <p>Metoda jest wywoływana przez Spring AMQP po odebraniu wiadomości
     * z kolejki {@code cc.queue.audit-log}. Konwersja JSON → {@link AuditLogEvent}
     * odbywa się przez {@code Jackson2JsonMessageConverter} skonfigurowany w
     * {@code RabbitMQConfig}.
     *
     * <p><strong>Acknowledge mode:</strong> Skonfigurowany jako {@code auto}
     * (patrz {@code application-prod.yml} i {@code application.yml}).
     * Spring AMQP ackuje wiadomość automatycznie po normalnym powrocie z metody,
     * lub nackuje przy wyjątku (co uruchamia retry/DLQ zgodnie z konfiguracją).
     * {@code @Transactional} jest celowo pominięte na poziomie konsumenta –
     * transakcja DB jest zarządzana przez {@link AuditLogRepository#insertAuditLog}.
     * Dzięki temu ack następuje dopiero po commicie transakcji repozytorium.
     *
     * <p>Audit log jest operacją idempotentną (log_id = UUID.randomUUID() per
     * przetworzenie) – duplikaty przy ponownym dostarczeniu są akceptowalne.
     *
     * @param event zdarzenie audytowe – zdesializowane przez Jackson z JSON
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_AUDIT_LOG)
    public void handleAuditEvent(AuditLogEvent event) {
        if (event == null) {
            log.warn("[AuditLogConsumer] Otrzymano null zdarzenie – ignoruję");
            return;
        }
        processWithTenant(event.tenantId(), () -> doHandle(event));
    }

    private void doHandle(AuditLogEvent event) {
        log.debug("[AuditLogConsumer] Przetwarzanie zdarzenia audytowego: action={}, entityType={}, entityId={}",
                event.action(), event.entityType(), event.entityId());

        try {
            auditLogRepository.insertAuditLog(
                    UUID.randomUUID().toString(),                     // log_id – generujemy tu (nie w aspekcie)
                    uuidToString(event.tenantId()),
                    uuidToString(event.userId()),
                    event.action(),
                    event.entityType(),
                    uuidToString(event.entityId()),
                    event.oldValue(),
                    event.newValue(),
                    event.ipAddress(),
                    event.userAgent(),
                    event.occurredAt()
            );

            log.info("[AuditLogConsumer] Zapisano wpis audytowy: action={}, entityType={}, entityId={}",
                    event.action(), event.entityType(), event.entityId());

        } catch (Exception e) {
            log.error("[AuditLogConsumer] Błąd zapisu zdarzenia audytowego: action={}, entityType={}, error={}",
                    event.action(), event.entityType(), e.getMessage(), e);
            // Rzucamy ponownie żeby Spring AMQP mógł wykonać retry/DLQ
            throw new RuntimeException("Błąd zapisu zdarzenia audytowego", e);
        }
    }

    /** Konwertuje UUID na String lub null (dla null UUID). */
    private String uuidToString(UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }
}
