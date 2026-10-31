package com.contactcenter.domain.retention;

import com.contactcenter.domain.audit.AuditLogEvent;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.retention.dto.CampaignArchivePreviewDto;
import com.contactcenter.domain.retention.dto.CampaignArchiveSettingDto;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * BE-151: okres archiwizacji per tenant na PRAWDZIWYM PostgreSQL z pełnym łańcuchem Flyway.
 *
 * <p>Serwis/repozytorium działają na puli roli BEZ BYPASSRLS ({@code app_user}, pula o rozmiarze 1 —
 * to samo połączenie jest reużywane, więc test łapie pułapkę GUC {@code ''} → {@code 22P02}); dane
 * seedujemy i asertujemy superuserem; job archiwizacji działa pod superuserem (jak {@code ccapp} z
 * BYPASSRLS w demo).
 */
@DisplayName("Okres archiwizacji per tenant - integracja (BE-151)")
class CampaignArchiveSettingIntegrationTest {

    private static HikariDataSource superuserPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static CampaignArchiveSettingService service;
    private static TenantCampaignArchiveSettingRepository repository;
    private static AuditLogService auditLogService;
    private static CampaignArchiveJob job;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void start() {
        superuserPool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(superuserPool);
        String role = "be151_app";
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
        appPool = PostgresTestDatabase.pool(role, password, 1);
        auditLogService = mock(AuditLogService.class);

        ctx = JpaTestContext.create(
                appPool,
                new Class<?>[]{TenantCampaignArchiveSetting.class},
                new Class<?>[]{TenantCampaignArchiveSettingRepository.class, CampaignArchiveSettingServiceImpl.class},
                c -> c.getBeanFactory().registerSingleton("auditLogService", auditLogService));
        service = ctx.getBean(CampaignArchiveSettingService.class);
        repository = ctx.getBean(TenantCampaignArchiveSettingRepository.class);

        DataSourceTransactionManager tm = new DataSourceTransactionManager(superuserPool);
        job = new CampaignArchiveJob(new CampaignArchiveJobRepository(jdbc, tm),
                new CronFailureLogRepository(jdbc, tm), true);
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        appPool.close();
        superuserPool.close();
    }

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenantA = PostgresTestDatabase.insertTenant(jdbc, "BE-151 A " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "BE-151 B " + UUID.randomUUID());
        org.mockito.Mockito.reset(auditLogService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("GET: brak wiersza = 180 + isDefault; po zapisie wartosc jawna; tenanty niezalezne")
    void get_defaultThenExplicit_perTenant() {
        TenantContext.setTenantId(tenantA);
        assertThat(service.get(tenantA).archiveAfterDays()).isEqualTo(180);
        assertThat(service.get(tenantA).isDefault()).isTrue();

        service.update(tenantA, 45, null);

        CampaignArchiveSettingDto a = service.get(tenantA);
        assertThat(a.archiveAfterDays()).isEqualTo(45);
        assertThat(a.isDefault()).isFalse();

        TenantContext.setTenantId(tenantB);
        CampaignArchiveSettingDto b = service.get(tenantB);
        assertThat(b.archiveAfterDays()).isEqualTo(180);
        assertThat(b.isDefault()).isTrue();
    }

    @Test
    @DisplayName("PUT 30/180/730 zapisane (CHECK w bazie przechodzi); upsert nie duplikuje wiersza")
    void put_boundaries() {
        TenantContext.setTenantId(tenantA);
        for (int days : new int[]{30, 730, 180, 30}) {
            assertThat(service.update(tenantA, days, null).archiveAfterDays()).isEqualTo(days);
            assertThat(jdbc.queryForObject(
                    "SELECT archive_after_days FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                    Integer.class, tenantA)).isEqualTo(days);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Integer.class, tenantA)).isEqualTo(1);
    }

    @Test
    @DisplayName("zapis dla cudzego tenanta (kontekst B, tenantId A) -> CrossTenantAccessException, brak wiersza")
    void upsert_crossTenant_rejected() {
        TenantContext.setTenantId(tenantB);

        assertThatThrownBy(() -> service.update(tenantA, 30, null))
                .isInstanceOf(CrossTenantAccessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Integer.class, tenantA)).isZero();
    }

    @Test
    @DisplayName("audyt: zmiana efektywna publikuje wpis; PUT z ta sama wartoscia nie spamuje")
    void audit_onlyOnEffectiveChange() {
        TenantContext.setTenantId(tenantA);

        service.update(tenantA, 30, null);
        ArgumentCaptor<AuditLogEvent> cap = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(auditLogService).publishAuditEvent(cap.capture());
        assertThat(cap.getValue().tenantId()).isEqualTo(tenantA);
        assertThat(cap.getValue().oldValue()).contains("180");
        assertThat(cap.getValue().newValue()).contains("30").contains("\"shortened\":true");

        org.mockito.Mockito.reset(auditLogService);
        service.update(tenantA, 30, null);
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("zmiana okresu przez API zmienia wynik joba; podglad zgodny z wynikiem i bez modyfikacji danych")
    void periodChangeDrivesJob_andPreviewMatches() {
        // Tenant A: domyslne 180. Kampania 100 dni (2 kontakty) + 200 dni (1 kontakt).
        // Tenant B: 30 dni. Kampania 100 dni (3 kontakty).
        UUID a100 = campaign(tenantA, "COMPLETED", 100);
        UUID a200 = campaign(tenantA, "STOPPED", 200);
        UUID b100 = campaign(tenantB, "COMPLETED", 100);
        UUID aRunning = campaign(tenantA, "RUNNING", 400);
        contacts(tenantA, a100, 2);
        contacts(tenantA, a200, 1);
        contacts(tenantB, b100, 3);
        contacts(tenantA, aRunning, 4);

        // --- podglad (tenant A, N=30): kampanie 100d i 200d; obecny okres 180: tylko 200d
        TenantContext.setTenantId(tenantA);
        long[] before = snapshot(tenantA, tenantB);
        CampaignArchivePreviewDto preview = service.preview(tenantA, 30);
        assertThat(preview.currentDays()).isEqualTo(180);
        assertThat(preview.newDays()).isEqualTo(30);
        assertThat(preview.campaignsToArchive()).isEqualTo(2);
        assertThat(preview.contactsToArchive()).isEqualTo(3);
        assertThat(preview.campaignsToArchiveAtCurrentDays()).isEqualTo(1);
        assertThat(preview.contactsToArchiveAtCurrentDays()).isEqualTo(1);
        assertThat(preview.additionalCampaigns()).isEqualTo(1);
        assertThat(preview.additionalContacts()).isEqualTo(2);
        assertThat(preview.shortensPeriod()).isTrue();
        // wydluzenie: 365 -> nic do archiwizacji, roznica ujemna
        CampaignArchivePreviewDto longer = service.preview(tenantA, 365);
        assertThat(longer.campaignsToArchive()).isZero();
        assertThat(longer.additionalContacts()).isEqualTo(-1);
        // tylko odczyt: zadne licznik ani wiersz ustawienia nie drgnal
        assertThat(snapshot(tenantA, tenantB)).containsExactly(before);
        assertThat(settingRows(tenantA)).isZero();

        // --- tenant B ustawia 30 przez API
        TenantContext.setTenantId(tenantB);
        service.update(tenantB, 30, null);

        // --- job: A (180) NIE rusza kampanii 100d, archiwizuje 200d; B (30) archiwizuje 100d
        Instant t0 = Instant.now().minusSeconds(5);
        assertThatNoException().isThrownBy(job::run);
        assertThat(liveCount(a100)).isEqualTo(2);
        assertThat(archiveCount(a100)).isZero();
        assertThat(liveCount(a200)).isZero();
        assertThat(archiveCount(a200)).isEqualTo(1);
        assertThat(liveCount(b100)).isZero();
        assertThat(archiveCount(b100)).isEqualTo(3);
        assertThat(liveCount(aRunning)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cron_log WHERE job_name = ? AND status = 'SUCCESS' AND finished_at >= ?",
                Integer.class, CampaignArchiveJobRepository.JOB_NAME, Timestamp.from(t0))).isGreaterThanOrEqualTo(1);

        // --- tenant A skraca do 30 -> kolejny przebieg archiwizuje 100d; razem = podglad
        TenantContext.setTenantId(tenantA);
        service.update(tenantA, 30, null);
        assertThatNoException().isThrownBy(job::run);
        assertThat(liveCount(a100)).isZero();
        assertThat(archiveCount(a100) + archiveCount(a200)).isEqualTo(preview.contactsToArchive());
        assertThat(liveCount(aRunning)).isEqualTo(4);
        // po zmianie podglad dla A nie widzi juz nic do zrobienia
        assertThat(service.preview(tenantA, 30).campaignsToArchive()).isZero();
    }

    @Test
    @DisplayName("podglad pomija kampanie bez kontaktow (nic do przeniesienia), cudze i w zlym statusie")
    void preview_countsEmptyCampaigns_ignoresOthers() {
        campaign(tenantA, "COMPLETED", 60);          // bez kontaktow - nic do przeniesienia
        UUID withContacts = campaign(tenantA, "COMPLETED", 61);
        contacts(tenantA, withContacts, 2);
        campaign(tenantB, "COMPLETED", 60);          // inny tenant
        campaign(tenantA, "PAUSED", 60);             // zly status
        TenantContext.setTenantId(tenantA);

        CampaignArchivePreviewDto p = service.preview(tenantA, 30);

        assertThat(p.campaignsToArchive()).isEqualTo(1);
        assertThat(p.contactsToArchive()).isEqualTo(2);
    }

    @Test
    @DisplayName("V139: po pelnym lancuchu Flyway opis bez '> 30 dni'; UPDATE z migracji naprawia stary opis "
            + "i przy ponownym wykonaniu zmienia 0 wierszy")
    void v139_descriptionFixed_idempotent() throws Exception {
        String sql = new String(new ClassPathResource(
                "db/migration/V139__scheduled_job_archive_description_per_tenant.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String migrationUpdate = sql.substring(sql.indexOf("UPDATE scheduled_job"));
        String current = description();
        assertThat(current).doesNotContain("> 30 dni").contains("TENANTA").contains("180");

        try {
            // przywracamy opis z V133 (z "> 30 dni") i wykonujemy sam UPDATE z migracji
            // (SET LOCAL lock_timeout poza transakcja nie ma znaczenia dla efektu)
            jdbc.update("UPDATE scheduled_job SET description = ? WHERE job_name = ?",
                    "Archiwizacja kontaktow kampanii (COMPLETED/STOPPED, > 30 dni) do archiwum",
                    CampaignArchiveJobRepository.JOB_NAME);

            assertThat(jdbc.update(migrationUpdate)).as("pierwsze wykonanie zmienia wiersz").isEqualTo(1);
            assertThat(description()).doesNotContain("> 30 dni").contains("TENANTA").contains("180");
            assertThat(jdbc.update(migrationUpdate)).as("ponowne wykonanie = no-op").isZero();
        } finally {
            jdbc.update("UPDATE scheduled_job SET description = ? WHERE job_name = ?",
                    current, CampaignArchiveJobRepository.JOB_NAME);
        }
    }

    @Test
    @DisplayName("COALESCE(ended_at, updated_at): ended_at STARE + updated_at SWIEZE -> archiwizowana/liczona; "
            + "ended_at SWIEZE + updated_at STARE -> NIE (wykrywa regresje do samego updated_at)")
    void endedAtBranch_ofCoalesce_decidesOverUpdatedAt() {
        // ended_at wstawiane jawnie - trigger (DB-085) respektuje jawna wartosc i nadaje NOW() tylko gdy NULL
        UUID oldEndedFreshUpdated = campaignEnded(tenantA, "COMPLETED", 100, 1);
        UUID freshEndedOldUpdated = campaignEnded(tenantA, "STOPPED", 1, 100);
        contacts(tenantA, oldEndedFreshUpdated, 2);
        contacts(tenantA, freshEndedOldUpdated, 3);
        TenantContext.setTenantId(tenantA);

        // podglad dla 30 dni: tylko kampania z ended_at sprzed 100 dni (2 kontakty);
        // przy regresji do updated_at wynik bylby odwrocony (0 kampanii / 3 kontakty z tej drugiej)
        CampaignArchivePreviewDto p = service.preview(tenantA, 30);
        assertThat(p.campaignsToArchive()).isEqualTo(1);
        assertThat(p.contactsToArchive()).isEqualTo(2);

        // job: po skroceniu do 30 dni archiwizuje te sama kampanie co podglad
        service.update(tenantA, 30, null);
        assertThatNoException().isThrownBy(job::run);
        assertThat(liveCount(oldEndedFreshUpdated)).isZero();
        assertThat(archiveCount(oldEndedFreshUpdated)).isEqualTo(2);
        assertThat(liveCount(freshEndedOldUpdated)).isEqualTo(3);
        assertThat(archiveCount(freshEndedOldUpdated)).isZero();
    }

    @Test
    @DisplayName("COALESCE(ended_at, updated_at, created_at): terminalna kampania z ended_at i updated_at = NULL "
            + "oraz starym created_at jest liczona w podgladzie (zgodnie z funkcja SQL od V140)")
    void createdAtFallback_nullEndedAndUpdated_isCountedInPreview() throws Exception {
        UUID id = campaignWithNullClocks(tenantA, 400);
        contacts(tenantA, id, 4);
        TenantContext.setTenantId(tenantA);

        CampaignArchivePreviewDto p = service.preview(tenantA, 30);
        assertThat(p.campaignsToArchive()).isEqualTo(1);
        assertThat(p.contactsToArchive()).isEqualTo(4);

        // zgodnosc z funkcja SQL: job archiwizuje dokladnie to, co pokazal podglad
        service.update(tenantA, 30, null);
        assertThatNoException().isThrownBy(job::run);
        assertThat(liveCount(id)).isZero();
        assertThat(archiveCount(id)).isEqualTo(4);
    }

    @Test
    @DisplayName("COALESCE(..., created_at): terminalna kampania z ended_at i updated_at = NULL oraz SWIEZYM "
            + "created_at NIE jest liczona w podgladzie ani archiwizowana (dolna granica predykatu)")
    void createdAtFallback_nullEndedAndUpdated_freshCreatedAt_isNotCounted() throws Exception {
        UUID id = campaignWithNullClocks(tenantA, 5);
        contacts(tenantA, id, 4);
        TenantContext.setTenantId(tenantA);

        CampaignArchivePreviewDto p = service.preview(tenantA, 30);
        assertThat(p.campaignsToArchive()).isZero();
        assertThat(p.contactsToArchive()).isZero();

        service.update(tenantA, 30, null);
        assertThatNoException().isThrownBy(job::run);
        assertThat(liveCount(id)).isEqualTo(4);
        assertThat(archiveCount(id)).isZero();
    }

    /**
     * Inserts a COMPLETED campaign with NULL ended_at/updated_at and the given created_at age. Triggers are
     * disabled only for this statement; RESET is guaranteed in finally so the pooled connection is never
     * returned with session_replication_role = replica.
     */
    private UUID campaignWithNullClocks(UUID tenant, int createdDaysAgo) throws Exception {
        UUID id = UUID.randomUUID();
        try (java.sql.Connection c = superuserPool.getConnection(); java.sql.Statement st = c.createStatement()) {
            try {
                st.execute("SET session_replication_role = replica");
                st.execute("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at, "
                        + "ended_at) VALUES ('" + id + "', '" + tenant + "', 'BE-151 null clock " + id
                        + "', 'COMPLETED', now() - interval '" + createdDaysAgo + " days', NULL, NULL)");
            } finally {
                st.execute("RESET session_replication_role");
            }
        }
        return id;
    }

    @Test
    @DisplayName("odczyty (get/preview/repo) cudzego tenanta -> CrossTenantAccessException")
    void reads_crossTenant_rejected() {
        TenantContext.setTenantId(tenantB);

        assertThatThrownBy(() -> service.get(tenantA)).isInstanceOf(CrossTenantAccessException.class);
        assertThatThrownBy(() -> service.preview(tenantA, 30)).isInstanceOf(CrossTenantAccessException.class);
        assertThatThrownBy(() -> repository.findByTenantId(tenantA)).isInstanceOf(CrossTenantAccessException.class);
        assertThatThrownBy(() -> repository.countArchivable(tenantA, 30))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    @DisplayName("wspolbieznosc: PUT czeka na niezacommitowany INSERT konkurenta i audytuje jego wartosc jako "
            + "stara (nie 180)")
    void concurrentPut_afterUncommittedInsert_auditsCompetitorValueAsOld() throws Exception {
        try (java.sql.Connection competitor = superuserPool.getConnection()) {
            competitor.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = competitor.prepareStatement(
                    "INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES (?, 90)")) {
                ps.setObject(1, tenantA);
                ps.executeUpdate();
            }

            java.util.concurrent.Future<CampaignArchiveSettingDto> put = putInOtherThread(tenantA, 45);
            awaitBlockedOnSettingTable();
            competitor.commit();

            assertThat(put.get(15, java.util.concurrent.TimeUnit.SECONDS).archiveAfterDays()).isEqualTo(45);
        }

        AuditLogEvent event = singleAuditEvent();
        assertThat(event.oldValue()).contains("\"archiveAfterDays\":90").contains("\"isDefault\":false");
        assertThat(event.newValue()).contains("\"archiveAfterDays\":45").contains("\"shortened\":true");
        assertThat(settingDays(tenantA)).isEqualTo(45);
    }

    @Test
    @DisplayName("wspolbieznosc: PUT czeka na niezacommitowany UPDATE konkurenta; flaga shortened liczona "
            + "od JEGO wartosci (60 -> 75 = wydluzenie, mimo ze wczesniej bylo 90)")
    void concurrentPut_afterUncommittedUpdate_usesCompetitorValue() throws Exception {
        jdbc.update("INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES (?, 90)",
                tenantA);
        try (java.sql.Connection competitor = superuserPool.getConnection()) {
            competitor.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = competitor.prepareStatement(
                    "UPDATE tenant_campaign_archive_setting SET archive_after_days = 60 WHERE tenant_id = ?")) {
                ps.setObject(1, tenantA);
                ps.executeUpdate();
            }

            java.util.concurrent.Future<CampaignArchiveSettingDto> put = putInOtherThread(tenantA, 75);
            awaitBlockedOnSettingTable();
            competitor.commit();

            assertThat(put.get(15, java.util.concurrent.TimeUnit.SECONDS).archiveAfterDays()).isEqualTo(75);
        }

        AuditLogEvent event = singleAuditEvent();
        assertThat(event.oldValue()).contains("\"archiveAfterDays\":60");
        assertThat(event.newValue()).contains("\"archiveAfterDays\":75").contains("\"shortened\":false");
        assertThat(settingDays(tenantA)).isEqualTo(75);
    }

    // ---- pomocnicze ----

    private java.util.concurrent.Future<CampaignArchiveSettingDto> putInOtherThread(UUID tenant, int days) {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            return executor.submit(() -> {
                TenantContext.setTenantId(tenant);
                try {
                    return service.update(tenant, days, null);
                } finally {
                    TenantContext.clear();
                }
            });
        } finally {
            executor.shutdown(); // zadanie juz zgloszone - watek konczy sie po jego wykonaniu
        }
    }

    /** Czeka (max 10 s), az inna sesja zablokuje sie na zapytaniu do tabeli ustawien (wait_event_type=Lock). */
    private void awaitBlockedOnSettingTable() throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()
                       AND query ILIKE '%tenant_campaign_archive_setting%'
                    """, Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("PUT nie zablokowal sie na wierszu konkurenta w ciagu 10 s");
    }

    private AuditLogEvent singleAuditEvent() {
        ArgumentCaptor<AuditLogEvent> cap = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(auditLogService).publishAuditEvent(cap.capture());
        return cap.getValue();
    }

    private int settingDays(UUID tenant) {
        return jdbc.queryForObject(
                "SELECT archive_after_days FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Integer.class, tenant);
    }

    /** Kampania terminalna z JAWNYM ended_at (trigger DB-085 respektuje jawna wartosc, NOW() tylko gdy NULL). */
    private UUID campaignEnded(UUID tenant, String status, int endedDaysAgo, int updatedDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at, ended_at) "
                        + "VALUES (?, ?, ?, '" + status + "', now() - make_interval(days => ?), "
                        + "now() - make_interval(days => ?), now() - make_interval(days => ?))",
                id, tenant, "BE-151 ended " + id, Math.max(endedDaysAgo, updatedDaysAgo) + 1,
                updatedDaysAgo, endedDaysAgo);
        return id;
    }

    private String description() {
        return jdbc.queryForObject("SELECT description FROM scheduled_job WHERE job_name = ?",
                String.class, CampaignArchiveJobRepository.JOB_NAME);
    }

    private long settingRows(UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Long.class, tenant);
    }

    private long[] snapshot(UUID a, UUID b) {
        java.util.Map<String, Object> m = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM campaign_contact WHERE tenant_id IN (?, ?)) AS c1,
                       (SELECT count(*) FROM campaign_contact_archive WHERE tenant_id IN (?, ?)) AS c2,
                       (SELECT count(*) FROM tenant_campaign_archive_setting WHERE tenant_id IN (?, ?)) AS c3,
                       (SELECT count(*) FROM cron_log) AS c4
                """, a, b, a, b, a, b);
        return new long[]{((Number) m.get("c1")).longValue(), ((Number) m.get("c2")).longValue(),
                ((Number) m.get("c3")).longValue(), ((Number) m.get("c4")).longValue()};
    }

    private UUID campaign(UUID tenant, String status, int daysOld) {
        UUID id = UUID.randomUUID();
        // DB-085: trigger ustawia ended_at=NOW() dla kampanii terminalnej wstawionej bez ended_at,
        // wiec wiek kampanii terminalnej musi byc podany jawnie w ended_at (zegar archiwizacji).
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at, ended_at) "
                        + "VALUES (?, ?, ?, '" + status + "', now() - make_interval(days => ?), "
                        + "now() - make_interval(days => ?), "
                        + "CASE WHEN '" + status + "' IN ('COMPLETED', 'STOPPED') "
                        + "THEN now() - make_interval(days => ?) END)",
                id, tenant, "BE-151 " + id, daysOld + 1, daysOld, daysOld);
        return id;
    }

    private void contacts(UUID tenant, UUID campaign, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status) "
                            + "VALUES (?, ?, ?, ?, ?, 'COMPLETED')",
                    UUID.randomUUID(), campaign, tenant, "+4850" + String.format("%07d", (int) (Math.random() * 9_999_999)),
                    "n" + i);
        }
    }

    private long liveCount(UUID campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM campaign_contact WHERE campaign_id = ?", Long.class, campaign);
    }

    private long archiveCount(UUID campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM campaign_contact_archive WHERE campaign_id = ?",
                Long.class, campaign);
    }
}
