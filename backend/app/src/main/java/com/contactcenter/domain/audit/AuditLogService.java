package com.contactcenter.domain.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

/**
 * Serwis odpowiedzialny za publikowanie zdarzeń audytowych do RabbitMQ
 * oraz odczyt dziennika audytu.
 *
 * <p>Publikacja jest asynchroniczna ({@code @Async}) – nie blokuje głównego wątku HTTP.
 * Wiadomości trafiają do exchange {@code cc.audit} z routing key {@code audit.entity.changed},
 * skąd konsument ({@code AuditLogConsumer}) pobiera je i zapisuje do bazy danych.
 *
 * <p>Błędy publikacji są logowane (nie rzucane) – audit log nie powinien przerywać
 * operacji biznesowych (fire-and-forget).
 */
public interface AuditLogService {

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
    void publishAuditEvent(AuditLogEvent event);

    /**
     * Zwraca stronę wpisów dziennika audytu z opcjonalnym filtrowaniem.
     *
     * <p>Wszystkie parametry filtrów są opcjonalne – null oznacza brak filtrowania.
     * Data początkowa i końcowa są włączone ({@code >=} i {@code <=}).
     *
     * @param tenantId   filtruj po tenant_id (null = wszystkie tenanty)
     * @param entityType filtruj po typie encji (null = wszystkie typy)
     * @param userId     filtruj po user_id (null = wszyscy użytkownicy)
     * @param dateFrom   filtruj od tej daty (null = bez ograniczenia)
     * @param dateTo     filtruj do tej daty (null = bez ograniczenia)
     * @param pageable   parametry paginacji (page, size, sort)
     * @return strona wyników zgodna z kryteriami
     */
    Page<AuditLog> findAuditLogs(UUID tenantId, String entityType, UUID userId,
                                  Instant dateFrom, Instant dateTo, Pageable pageable);
}
