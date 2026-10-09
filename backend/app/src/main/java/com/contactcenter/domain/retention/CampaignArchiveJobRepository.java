package com.contactcenter.domain.retention;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Wywołanie funkcji SQL {@code archive_completed_campaign_contacts()} (V015) dla
 * {@link CampaignArchiveJob} (BE-120, EPIC-30).
 *
 * <p>Celowo NIE rozszerza {@code TenantAwareRepository} i nie woła {@code assertSameTenant}:
 * funkcja jest cross-tenant z założenia (jedna transakcja, wszystkie tenanty), a wołający
 * (scheduler) nie ma {@code TenantContext}. Osobna klasa (a nie metoda w
 * {@link CampaignArchiveRetentionRepository}) — tamto repozytorium jest per-tenant
 * ({@code countEligible}/{@code purgeEligible}), a ta operacja ma przeciwny kontrakt.
 *
 * <p>Funkcja zwraca {@code VOID}, więc liczba przeniesionych wierszy jest odczytywana z wpisu
 * {@code cron_log.rows_affected}, który ta sama funkcja zapisuje na końcu — w TEJ SAMEJ transakcji
 * (deterministyczne: brak wyścigu z innym wywołaniem).
 */
@Slf4j
@Repository
class CampaignArchiveJobRepository {

    static final String JOB_NAME = "archive_completed_campaign_contacts";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    CampaignArchiveJobRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Uruchamia archiwizację (jedna transakcja dla wszystkich kwalifikujących się kampanii).
     *
     * @return liczba wierszy skopiowanych do archiwum wg {@code cron_log.rows_affected}
     *         (0, gdy brak wpisu — np. gdy {@code cron_log} jest niewidoczny dla roli)
     * @throws org.springframework.dao.DataAccessException gdy funkcja SQL rzuci wyjątek
     *         (transakcja jest wtedy wycofana w całości)
     */
    long archiveCompletedCampaigns() {
        Long rows = tx.execute(status -> {
            jdbc.execute("SELECT archive_completed_campaign_contacts()");
            List<Integer> logged = jdbc.queryForList(
                    "SELECT rows_affected FROM cron_log WHERE job_name = ? AND status = 'SUCCESS' "
                            + "ORDER BY log_id DESC LIMIT 1",
                    Integer.class, JOB_NAME);
            return logged.isEmpty() || logged.get(0) == null ? 0L : logged.get(0).longValue();
        });
        return rows == null ? 0L : rows;
    }
}
