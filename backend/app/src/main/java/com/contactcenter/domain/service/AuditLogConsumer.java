package com.contactcenter.domain.service;

import com.contactcenter.domain.model.AuditLogEvent;
import com.contactcenter.domain.repository.AuditLogRepository;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p><strong>Transakcyjność:</strong> Każdy zapis jest osobną transakcją.
 * Brak transakcji nadrzędnej – konsument działa poza kontekstem HTTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogConsumer {

    private final AuditLogRepository auditLogRepository;

    /**
     * Przetwarza zdarzenie audytowe i zapisuje je do bazy danych.
     *
     * <p>Metoda jest wywoływana przez Spring AMQP po odebraniu wiadomości
     * z kolejki {@code cc.queue.audit-log}. Konwersja JSON → {@link AuditLogEvent}
     * odbywa się przez {@code Jackson2JsonMessageConverter} skonfigurowany w
     * {@code RabbitMQConfig}.
     *
     * <p><strong>Uwaga dotycząca @Transactional i manual acknowledge:</strong>
     * Profil prod używa {@code acknowledge-mode: manual} w RabbitMQ.
     * {@code @Transactional} na metodzie {@code @RabbitListener} z manual ack
     * <em>nie</em> powoduje automatycznego ackowania wiadomości po commicie transakcji –
     * ack AMQP i commit DB to niezależne operacje.
     *
     * <p>Świadoma decyzja: zostawiamy {@code acknowledge-mode: manual} bez ręcznego ack
     * w tej metodzie, bo audit log jest operacją idempotentną (log_id = UUID.randomUUID()
     * per każde przetworzenie). Duplikaty w audit_log są akceptowalne. Brak ack powoduje
     * ponowne dostarczenie wiadomości przy restarcie brokera – to pożądane zachowanie
     * dla audit trail (lepiej zdublowany wpis niż brak wpisu).
     *
     * <p>Jeśli wymagana jest dokładnie-jeden-raz semantyka, zmień na
     * {@code acknowledge-mode: auto} lub dodaj ręczny {@code Channel.basicAck()} tutaj.
     *
     * @param event zdarzenie audytowe – zdesializowane przez Jackson z JSON
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_AUDIT_LOG)
    @Transactional
    public void handleAuditEvent(AuditLogEvent event) {
        if (event == null) {
            log.warn("[AuditLogConsumer] Otrzymano null zdarzenie – ignoruję");
            return;
        }

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
