package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.AuditLog;
import com.contactcenter.domain.model.AuditLogId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Repozytorium dziennika audytu.
 *
 * <p><strong>Zapis:</strong> Odbywa się przez natywne zapytanie SQL ({@link #insertAuditLog}),
 * ponieważ JPA nie obsługuje poprawnie INSERT na tabelach partycjonowanych PostgreSQL
 * z kluczem głównym obejmującym kolumnę partycjonowania.
 *
 * <p><strong>Odczyt:</strong> Standardowe metody Spring Data JPA – Hibernate odpytuje
 * tabelę nadrzędną {@code audit_log}, która automatycznie przekierowuje do właściwych partycji.
 *
 * <p><strong>RLS:</strong> Tabela {@code audit_log} jest dostępna wyłącznie dla ADMIN
 * (endpoint {@code GET /api/audit-logs}). Brak filtrowania po tenant_id w SQL –
 * filtrowanie odbywa się przez parametr zapytania {@code tenantId} (opcjonalny).
 * Dostęp do endpointu jest chroniony przez {@code @PreAuthorize("hasRole('ADMIN')")}
 * w kontrolerze.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, AuditLogId> {

    /**
     * Wstawia wpis audytowy przez natywny SQL (wymagane dla tabel partycjonowanych PostgreSQL).
     *
     * <p>Metoda jest oznaczona {@code @Modifying} i {@code @Transactional}, ponieważ
     * jest to operacja DML. Musi być wywoływana w kontekście transakcji.
     *
     * @param logId      UUID wpisu audytowego
     * @param tenantId   UUID tenanta (może być null)
     * @param userId     UUID użytkownika (może być null)
     * @param action     kod akcji
     * @param entityType typ encji
     * @param entityId   UUID encji (może być null)
     * @param oldValue   stary stan jako JSON string (może być null)
     * @param newValue   nowy stan jako JSON string (może być null)
     * @param ipAddress  adres IP (może być null)
     * @param userAgent  user agent (może być null)
     * @param createdAt  czas zdarzenia
     */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            INSERT INTO audit_log
                (log_id, tenant_id, user_id, action, entity_type, entity_id,
                 old_value, new_value, ip_address, user_agent, created_at)
            VALUES
                (CAST(:logId AS uuid),
                 CAST(:tenantId AS uuid),
                 CAST(:userId AS uuid),
                 :action,
                 :entityType,
                 CAST(:entityId AS uuid),
                 CAST(:oldValue AS jsonb),
                 CAST(:newValue AS jsonb),
                 CAST(:ipAddress AS inet),
                 :userAgent,
                 :createdAt)
            """)
    void insertAuditLog(
            @Param("logId")      String logId,
            @Param("tenantId")   String tenantId,
            @Param("userId")     String userId,
            @Param("action")     String action,
            @Param("entityType") String entityType,
            @Param("entityId")   String entityId,
            @Param("oldValue")   String oldValue,
            @Param("newValue")   String newValue,
            @Param("ipAddress")  String ipAddress,
            @Param("userAgent")  String userAgent,
            @Param("createdAt")  Instant createdAt
    );

    /**
     * Filtruje wpisy audytowe według opcjonalnych kryteriów z paginacją.
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
    @Query("""
            SELECT a FROM AuditLog a
            WHERE (:tenantId IS NULL OR a.tenantId = :tenantId)
              AND (:entityType IS NULL OR a.entityType = :entityType)
              AND (:userId IS NULL OR a.userId = :userId)
              AND (:dateFrom IS NULL OR a.createdAt >= :dateFrom)
              AND (:dateTo IS NULL OR a.createdAt <= :dateTo)
            ORDER BY a.createdAt DESC
            """)
    Page<AuditLog> findByFilters(
            @Param("tenantId")   UUID tenantId,
            @Param("entityType") String entityType,
            @Param("userId")     UUID userId,
            @Param("dateFrom")   Instant dateFrom,
            @Param("dateTo")     Instant dateTo,
            Pageable pageable
    );
}
