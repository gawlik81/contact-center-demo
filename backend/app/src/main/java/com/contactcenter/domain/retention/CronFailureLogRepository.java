package com.contactcenter.domain.retention;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Trwały ślad awarii zadania cyklicznego w {@code cron_log}/{@code scheduled_job} (DB-082, EPIC-30)
 * przez funkcję SQL {@code log_cron_failure(job, message, started_at)} (V135).
 *
 * <p><strong>Dlaczego osobna transakcja:</strong> wpis ERROR zapisany wewnątrz transakcji, która
 * się nie powiodła (jak handler {@code EXCEPTION ... RAISE} w V015), jest wycofywany razem z nią.
 * Dlatego wołający MUSI wywołać {@link #recordFailure} PO wyjściu z nieudanej transakcji; ta
 * klasa otwiera własną transakcję {@code PROPAGATION_REQUIRES_NEW}, więc zapis jest trwały
 * nawet gdyby wołający był (omyłkowo) wewnątrz transakcji zewnętrznej.
 *
 * <p>Celowo NIE rozszerza {@code TenantAwareRepository} i nie woła {@code assertSameTenant}:
 * {@code cron_log} i {@code scheduled_job} są tabelami globalnymi (bez {@code tenant_id}, bez RLS),
 * a wołający (scheduler / wątek purge) nie musi mieć żadnego {@code TenantContext}. Klasa nie
 * dotyka {@code TenantContext}.
 *
 * <p><strong>Kontrakt bezpieczeństwa:</strong> {@link #recordFailure} NIGDY nie rzuca — awaria
 * zapisu śladu nie może przerwać schedulera ani zamaskować oryginalnego wyjątku (jest logowana
 * na WARN).
 */
@Slf4j
@Repository
class CronFailureLogRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    CronFailureLogRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Zapisuje {@code cron_log(status=ERROR)} i {@code scheduled_job.last_run_status='ERROR'}
     * dla zadania {@code jobName} w osobnej transakcji.
     *
     * @param jobName   nazwa zadania ({@code scheduled_job.job_name}); nieznana nazwa nie jest błędem
     *                  (powstaje tylko wiersz {@code cron_log})
     * @param message   komunikat błędu (może być null/pusty — wtedy zapisywany jest opis zastępczy;
     *                  funkcja SQL obcina do 2000 znaków)
     * @param startedAt czas startu nieudanego przebiegu (null = teraz)
     */
    void recordFailure(String jobName, String message, Instant startedAt) {
        try {
            String safeMessage = (message == null || message.isBlank()) ? "(brak komunikatu błędu)" : message;
            Timestamp started = startedAt == null ? null : Timestamp.from(startedAt);
            tx.execute(status -> jdbc.queryForObject(
                    "SELECT log_cron_failure(?, ?, ?)", Long.class, jobName, safeMessage, started));
        } catch (RuntimeException e) {
            log.warn("[CronFailureLogRepository] Nie udało się zapisać śladu awarii zadania '{}': {}",
                    jobName, e.getMessage());
        }
    }

    /**
     * Komunikat z najgłębszej przyczyny wyjątku (np. treść {@code RAISE} z PostgreSQL zamiast
     * ogólnego "StatementCallback; SQL ..."), nigdy null.
     */
    static String rootCauseMessage(Throwable t) {
        if (t == null) {
            return "(brak komunikatu błędu)";
        }
        Throwable root = NestedExceptionUtils.getMostSpecificCause(t);
        String msg = root.getMessage();
        return (msg == null || msg.isBlank()) ? root.getClass().getName() : msg;
    }
}
