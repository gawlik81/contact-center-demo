package com.contactcenter.domain.retention;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cykliczna archiwizacja kontaktów zakończonych kampanii (BE-120, EPIC-30) — domknięcie martwego
 * harmonogramu: funkcja SQL {@code archive_completed_campaign_contacts()} (V015) dla każdej kampanii
 * {@code COMPLETED}/{@code STOPPED} z {@code updated_at < now() - 30 dni} kopiuje
 * {@code campaign_contact} do {@code campaign_contact_archive}, usuwa je z tabeli operacyjnej,
 * zapisuje {@code cron_log} i {@code scheduled_job.last_run_at}. Pg_cron jest wyłączony (V014),
 * więc dotąd nikt jej nie wołał.
 *
 * <h2>Flaga (D8) — domyślnie WYŁĄCZONY</h2>
 * {@code retention.campaign-archive.enabled=false} (domyślnie): job loguje INFO i NIE wykonuje
 * żadnego SQL. Powód: po archiwizacji kontakty zakończonych kampanii (&gt; 30 dni) znikają z UI i
 * statystyk — żaden czytelnik {@code campaign_contact} nie czyta archiwum (patrz niżej). Włączenie
 * wymaga zgody właściciela produktu. Skutek uboczny (D6): pierwsze uruchomienie archiwizuje całą
 * zaległość, więc zegar {@code archived_at} (podstawa retencji CAMPAIGN_DATA) startuje od tego dnia.
 *
 * <h2>Harmonogram</h2>
 * {@code retention.campaign-archive-cron}, domyślnie {@code 0 0 4 * * *} UTC (zgodnie z wpisem w
 * {@code scheduled_job}). Zajęte sloty: 00:30 PartitionMaintenanceJob, 01:00 RetentionEvaluationJob,
 * 02:00 RecordingRetentionJob, niedziela 03:00 PartitionReclaimJob.
 *
 * <h2>Kontekst tenanta (WP-2) — prekontrakt</h2>
 * Metoda działa na wątku schedulera BEZ {@code TenantContext} i NIE ustawia ani nie czyści go (nie ma
 * pętli per tenant: funkcja jest cross-tenant z założenia i nie używa GUC
 * {@code app.current_tenant_id}). Rola DB użyta przez połączenie MUSI więc widzieć i modyfikować
 * wiersze wszystkich tenantów (BYPASSRLS / właściciel tabel bez FORCE — dziś {@code ccapp} ma
 * BYPASSRLS).
 *
 * <h2>Zachowanie pod RLS bez GUC (DESIGN R5) — ZMIERZONE</h2>
 * Od V111/V112 {@code campaign_contact} i {@code campaign_contact_archive} mają FORCE RLS
 * (polityka {@code tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid}), podobnie
 * {@code campaign}. Test {@code CampaignArchiveJobIntegrationTest} (Testcontainers, pełny łańcuch
 * Flyway) wykonuje funkcję pod {@code SET ROLE app_user} (bez BYPASSRLS) w trzech wariantach:
 * <ul>
 *   <li><b>bez GUC:</b> {@code SELECT ... FROM campaign} widzi 0 kampanii → pętla funkcji nie wykonuje
 *       żadnej iteracji → ZERO zarchiwizowanych wierszy, brak błędu (cichy no-op; funkcja mimo to
 *       zapisuje {@code cron_log} z 0 — wtedy {@code cron_log}/{@code scheduled_job} dają fałszywy
 *       obraz "sukcesu");</li>
 *   <li><b>z GUC tenanta A:</b> archiwizowana jest WYŁĄCZNIE kampania tenanta A; kampania
 *       kwalifikująca się tenanta B pozostaje nietknięta (jedno wywołanie = jeden tenant);</li>
 *   <li>pod rolą z BYPASSRLS (superuser/{@code ccapp}) archiwizowane są wszystkie tenanty.</li>
 * </ul>
 * Wniosek: dopóki połączenie aplikacji ma BYPASSRLS, job działa poprawnie; przy przełączeniu roli
 * połączenia na {@code app_user} (odłożona decyzja DB-071) job zacznie po cichu nic nie robić.
 * Rozwiązanie (rola serwisowa / {@code SECURITY DEFINER} / pętla per tenant z
 * {@code p_tenant_id} / zmiana funkcji) przekazane do DB-072 i BE-139 — tutaj NIE zmieniamy funkcji.
 *
 * <h2>Czytelnicy {@code campaign_contact} (co zniknie po archiwizacji)</h2>
 * {@code CampaignContactRepository#findByCampaign} ({@code GET /api/campaigns/{id}/contacts}),
 * {@code #countByStatusGroupedByCampaign} (statystyki listy kampanii, {@code CampaignServiceImpl}),
 * {@code #findPendingByCampaignIds}/{@code #findRecordForManualDial} (dialer, {@code DialerController}
 * {@code /api/dialer/manual/records}, {@code /manual/call}), {@code CampaignRepository#countContacts}
 * (walidacja startu kampanii), {@code EtlSyncServiceImpl} (ETL do DW),
 * {@code GdprRepository} (guard rekordów w toku). Archiwum {@code campaign_contact_archive} nie ma
 * żadnego czytelnika poza SQL retencji i RODO ({@code export_customer_data}/{@code anonymize_customer}).
 * {@code GET .../contacts/{recordId}/attempts} czyta tabelę {@code contact}, nie
 * {@code campaign_contact} — historia prób nie znika.
 *
 * <h2>Wydajność pierwszego uruchomienia</h2>
 * Pierwszy przebieg to JEDNA transakcja na wszystkie zaległe kampanie (patrz Javadoc
 * {@code CampaignArchiveJobIntegrationTest} — pomiar na scratch). Powyżej 30 s należy zlecić
 * wariant per kampania (ticket DB).
 *
 * <h2>Odporność</h2>
 * Wyjątek (np. z funkcji SQL) jest łapany i logowany na ERROR — nie zatrzymuje schedulera;
 * transakcja jest wycofana w całości, a kolejny przebieg nastąpi następnej doby. Operacja jest
 * idempotentna ({@code ON CONFLICT DO NOTHING}, kampanie bez wierszy nic nie zmieniają).
 */
@Slf4j
@Component
class CampaignArchiveJob {

    private final CampaignArchiveJobRepository repository;
    private final boolean enabled;

    CampaignArchiveJob(CampaignArchiveJobRepository repository,
                       @Value("${retention.campaign-archive.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    /** Punkt wejścia schedulera; nigdy nie rzuca wyjątku. */
    @Scheduled(cron = "${retention.campaign-archive-cron:0 0 4 * * *}", zone = "UTC")
    void run() {
        if (!enabled) {
            log.info("[CampaignArchiveJob] Wyłączony (retention.campaign-archive.enabled=false) — pomijam");
            return;
        }
        try {
            long rows = repository.archiveCompletedCampaigns();
            log.info("[CampaignArchiveJob] Zarchiwizowano {} wierszy campaign_contact", rows);
        } catch (RuntimeException e) {
            log.error("[CampaignArchiveJob] Archiwizacja kontaktów kampanii nie powiodła się", e);
        }
    }
}
