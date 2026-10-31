package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link PartitionReclaimJob} (EPIC-29, BE-115; BE-123 EPIC-30).
 *
 * <p>{@link PartitionScanner}, {@link RetentionPolicyService} i {@link PlatformRetentionProperties}
 * są mockowane — logika progowa (MAX retencji/horyzont platformowy -> globalny cutoff -> porównanie
 * {@code rangeEnd}) jest czysto arytmetyczna, a weryfikacja rzeczywistego SQL (DROP TABLE IF EXISTS,
 * bezpiecznik identyfikatora) jest już pokryta w {@code PartitionScannerImplTest}.
 * <strong>UWAGA (BE-145):</strong> mock {@link PartitionScanner} nie potwierdza, że pominięty
 * {@code DROP} faktycznie NIE usunął partycji z {@code pg_class}/{@code information_schema} — to
 * jest weryfikowane osobno, na prawdziwym Postgresie, w {@code PartitionReclaimJobIntegrationTest}/
 * {@code PartitionReclaimPlatformHorizonIntegrationTest} (BE-123).
 *
 * <p>Domyślne stuby w {@link #setUp()}: {@code listPartitions} zwraca pustą listę dla
 * wszystkich tabel per-tenant ({@code social_message} BE-133, {@code email_message} BE-135 — {@code CONTACT_INTERACTIONS})
 * oraz platformowych ({@code audit_log}/{@code plugin_invocation_log} — BE-123), {@code findMaxRetentionMonths}
 * zwraca 60 dla obu kategorii, {@code platformRetentionProperties.monthsFor} zwraca 24 dla obu
 * kluczy — żeby testy skupione na jednej tabeli/kategorii nie musiały jawnie stubować pozostałych.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PartitionReclaimJob – fizyczne odzyskanie miejsca, globalny próg MAX retencji / horyzont platformowy (BE-115, BE-123)")
class PartitionReclaimJobTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private PartitionScanner partitionScanner;

    @Mock
    private RetentionPolicyService retentionPolicyService;

    @Mock
    private PlatformRetentionProperties platformRetentionProperties;

    @InjectMocks
    private PartitionReclaimJob job;

    @BeforeEach
    void setUp() {
        when(partitionScanner.listPartitions(anyString())).thenReturn(List.of());
        when(partitionScanner.countRowsByTenant(anyString())).thenReturn(List.of());
        when(partitionScanner.countRows(anyString())).thenReturn(0L);
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                .thenReturn(60);
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.TRANSCRIPTS))
                .thenReturn(60);
        when(platformRetentionProperties.monthsFor("audit-log-months")).thenReturn(24);
        when(platformRetentionProperties.monthsFor("plugin-invocation-log-months")).thenReturn(24);
    }

    private static PartitionScanner.PartitionInfo partitionEndingAt(String name, LocalDate rangeEnd) {
        return new PartitionScanner.PartitionInfo(name, rangeEnd.minusMonths(1), rangeEnd);
    }

    // =========================================================================
    // Scenariusz 1: partycja bezpiecznie pusta do usunięcia
    // =========================================================================

    @Nested
    @DisplayName("Partycja bezpiecznie pusta, starsza niż globalny próg")
    class SafelyEmptyPartitionEligibleForDrop {

        @Test
        @DisplayName("DROP TABLE dla partycji, której rangeEnd < (teraz - maxRetentionMonths), bez żywych wierszy")
        void dropsPartitionOlderThanGlobalThreshold_whenEmpty() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoff = today.minusMonths(60);
            PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("contact_2015_01", cutoff.minusMonths(6));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(oldPartition));
            when(partitionScanner.countRowsByTenant("contact_2015_01")).thenReturn(List.of());

            job.runReclaimJob();

            verify(partitionScanner).dropPartition("contact_2015_01");
        }
    }

    // =========================================================================
    // Scenariusz 2: partycja z żywymi danymi (w oknie retencji) NIE usunięta
    // =========================================================================

    @Nested
    @DisplayName("Partycja młodsza niż globalny próg — wciąż w oknie retencji")
    class YoungPartitionNotDropped {

        @Test
        @DisplayName("NIE wywołuje dropPartition dla partycji, której rangeEnd >= (teraz - maxRetentionMonths)")
        void doesNotDropPartitionWithinRetentionWindow() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoff = today.minusMonths(60);
            PartitionScanner.PartitionInfo youngPartition = partitionEndingAt("contact_2024_01", cutoff.plusMonths(3));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(youngPartition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition(anyString());
        }
    }

    // =========================================================================
    // Scenariusz 3: próg liczony po MAX, nie po MIN
    // =========================================================================

    @Nested
    @DisplayName("Globalny próg liczony po MAX retencji spośród wszystkich tenantów")
    class ThresholdUsesMaxNotMin {

        @Test
        @DisplayName("partycja starsza niż MIN retencji, ale młodsza niż MAX -> NIE usunięta (próg = MAX, nie MIN)")
        void doesNotDropPartitionOlderThanMinButYoungerThanMax() {
            // Tenant A ma politykę 3 mies., Tenant B 60 mies. — gdyby job liczył próg po MIN (3),
            // partycja sprzed 10 mies. zostałaby błędnie usunięta mimo że Tenant B (60 mies.)
            // wciąż ma do niej prawo. Job MUSI użyć MAX (60).
            when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate tenMonthsAgo = today.minusMonths(10);
            String partitionName = "contact_%04d_%02d".formatted(tenMonthsAgo.getYear(), tenMonthsAgo.getMonthValue());
            PartitionScanner.PartitionInfo partition = partitionEndingAt(partitionName, tenMonthsAgo);

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(partition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition(partitionName);
        }

        @Test
        @DisplayName("job woła findMaxRetentionMonths (nigdy findMinRetentionMonths) do wyznaczenia progu")
        void callsFindMaxRetentionMonths_neverFindMin() {
            job.runReclaimJob();

            // CONTACT_INTERACTIONS obejmuje 4 tabele (contact, contact_event, social_message od
            // BE-133, email_message od BE-135), TRANSCRIPTS 2 tabele (contact_transcription,
            // contact_ai_summary) — próg liczony niezależnie per tabela, stąd 4/2 wywołania na
            // kategorię (patrz javadoc PartitionReclaimJob).
            verify(retentionPolicyService, org.mockito.Mockito.times(4))
                    .findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS);
            verify(retentionPolicyService, org.mockito.Mockito.times(2))
                    .findMaxRetentionMonths(RetentionDataCategory.TRANSCRIPTS);
            verify(retentionPolicyService, never()).findMinRetentionMonths(org.mockito.ArgumentMatchers.any());
        }
    }

    // =========================================================================
    // Scenariusz 4: partycja DEFAULT nigdy nie jest kandydatem do DROP
    // =========================================================================

    @Nested
    @DisplayName("Partycja DEFAULT nigdy nie jest kandydatem do DROP")
    class DefaultPartitionNeverDropped {

        @Test
        @DisplayName("nawet gdyby PartitionScanner defensywnie zwrócił wpis dla _default, bezpiecznik wzorca nazwy blokuje DROP")
        void neverDropsDefaultPartition_evenIfReturnedDefensively() {
            // PartitionScannerImpl.listPartitions już strukturalnie wyklucza <tabela>_default
            // (patrz jego javadoc) — ten test dowodzi DODATKOWEGO bezpiecznika w PartitionReclaimJob
            // (wzorzec nazwy <tabela>_YYYY_MM), na wypadek nieoczekiwanego wpisu z bardzo starą
            // granicą czasową, który mógłby wyglądać na "oczywistego kandydata" do DROP.
            LocalDate veryOldCutoff = LocalDate.of(1970, 1, 1);
            PartitionScanner.PartitionInfo defaultPartition =
                    new PartitionScanner.PartitionInfo("contact_default", veryOldCutoff, veryOldCutoff.plusYears(1));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(defaultPartition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("contact_default");
        }
    }

    // =========================================================================
    // Scenariusz 5: wiele tabel przetwarzanych niezależnie
    // =========================================================================

    @Nested
    @DisplayName("Wiele tabel przetwarzanych niezależnie — błąd jednej nie przerywa pozostałych")
    class TablesProcessedIndependently {

        @Test
        @DisplayName("błąd przy listPartitions() dla jednej tabeli nie blokuje przetwarzania pozostałych trzech")
        void errorInOneTable_doesNotStopOtherTables() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate oldCutoff = today.minusMonths(60).minusMonths(6);
            PartitionScanner.PartitionInfo eligible = partitionEndingAt("contact_ai_summary_2015_01", oldCutoff);

            when(partitionScanner.listPartitions("contact")).thenThrow(new RuntimeException("boom - błąd DB"));
            when(partitionScanner.listPartitions("contact_ai_summary")).thenReturn(List.of(eligible));
            when(partitionScanner.countRowsByTenant("contact_ai_summary_2015_01")).thenReturn(List.of());

            job.runReclaimJob();

            // Tabela "contact" zawiodła, ale "contact_event"/"social_message"/"contact_transcription"/
            // "contact_ai_summary" wciąż zostały przetworzone niezależnie — dropPartition wywołany
            // dla eligible partycji.
            verify(partitionScanner).listPartitions("contact_event");
            verify(partitionScanner).listPartitions("social_message");
            verify(partitionScanner).listPartitions("contact_transcription");
            verify(partitionScanner).listPartitions("contact_ai_summary");
            verify(partitionScanner).dropPartition("contact_ai_summary_2015_01");
        }

        @Test
        @DisplayName("brak polityki retencji dla kategorii (ResourceNotFoundException) pomija TYLKO tabele tej kategorii")
        void skipsOnlyTablesOfCategoryWithoutPolicy_othersStillProcessed() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate oldCutoff = today.minusMonths(60).minusMonths(6);
            PartitionScanner.PartitionInfo eligible = partitionEndingAt("contact_transcription_2015_01", oldCutoff);

            when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenThrow(new ResourceNotFoundException("brak polityki"));
            when(partitionScanner.listPartitions("contact_transcription")).thenReturn(List.of(eligible));
            when(partitionScanner.countRowsByTenant("contact_transcription_2015_01")).thenReturn(List.of());

            job.runReclaimJob();

            // Brak polityki dla CONTACT_INTERACTIONS -> reclaimTable() zwraca wcześnie, zanim
            // listPartitions() w ogóle zostanie wywołane dla "contact"/"contact_event"/"social_message".
            verify(partitionScanner, never()).listPartitions("contact");
            verify(partitionScanner, never()).listPartitions("contact_event");
            verify(partitionScanner, never()).listPartitions("social_message");
            verify(partitionScanner).dropPartition("contact_transcription_2015_01");
        }
    }

    // =========================================================================
    // Scenariusz dodatkowy (BE-145): niespójność z Poziomem 1 BLOKUJE DROP
    // =========================================================================

    @Nested
    @DisplayName("Partycja z niespójnością Poziomu 1 — wciąż ma wiersze -> DROP zablokowany (BE-145)")
    class InconsistencyWithLevelOneBlocksDrop {

        @Test
        @DisplayName("DROP POMINIĘTY, gdy countRowsByTenant zwraca niepustą listę (do 2026-09-26/BE-145: tylko WARN, DROP kontynuowany — zmienione)")
        void doesNotDropPartition_whenRowsUnexpectedlyPresent() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate oldCutoff = today.minusMonths(60).minusMonths(6);
            PartitionScanner.PartitionInfo eligible = partitionEndingAt("contact_2015_01", oldCutoff);

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(eligible));
            when(partitionScanner.countRowsByTenant("contact_2015_01"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 3),
                            new PartitionScanner.TenantRowCount(TENANT_B, 1)));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("contact_2015_01");
        }

        @Test
        @DisplayName("WARN w logu zawiera nazwę partycji, łączną liczbę wierszy (suma tenantów) i liczbę tenantów")
        void logsWarnWithRowCountAndTenantCount() {
            ch.qos.logback.classic.Logger jobLogger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PartitionReclaimJob.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            jobLogger.addAppender(appender);
            try {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                LocalDate oldCutoff = today.minusMonths(60).minusMonths(6);
                PartitionScanner.PartitionInfo eligible = partitionEndingAt("contact_2015_01", oldCutoff);

                when(partitionScanner.listPartitions("contact")).thenReturn(List.of(eligible));
                when(partitionScanner.countRowsByTenant("contact_2015_01"))
                        .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 3),
                                new PartitionScanner.TenantRowCount(TENANT_B, 1)));

                job.runReclaimJob();

                assertThat(appender.list)
                        .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .anySatisfy(msg -> assertThat(msg)
                                .contains("contact_2015_01")
                                .contains("4 wierszy")
                                .contains("2 tenantów")
                                .contains("POMIJAM DROP"));
            } finally {
                jobLogger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    // =========================================================================
    // Scenariusz BE-133: social_message dziedziczy mechanizm BE-145 (ONLY_IF_EMPTY)
    // =========================================================================

    @Nested
    @DisplayName("social_message (BE-133) — dziedziczy blokadę DROP niepustej partycji z BE-145")
    class SocialMessagePartitionReclaim {

        @Test
        @DisplayName("partycja social_message_* pusta, starsza niż globalny próg CONTACT_INTERACTIONS -> DROP WYKONANY")
        void emptySocialMessagePartitionOlderThanThreshold_isDropped() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoff = today.minusMonths(60);
            PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("social_message_2015_01", cutoff.minusMonths(6));

            when(partitionScanner.listPartitions("social_message")).thenReturn(List.of(oldPartition));
            when(partitionScanner.countRowsByTenant("social_message_2015_01")).thenReturn(List.of());

            job.runReclaimJob();

            verify(partitionScanner).dropPartition("social_message_2015_01");
        }

        @Test
        @DisplayName("partycja social_message_* niepusta, starsza niż globalny próg -> DROP POMINIĘTY (WARN), tak jak contact* (BE-145)")
        void nonEmptySocialMessagePartitionOlderThanThreshold_dropIsSkipped() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoff = today.minusMonths(60);
            PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("social_message_2015_01", cutoff.minusMonths(6));

            when(partitionScanner.listPartitions("social_message")).thenReturn(List.of(oldPartition));
            when(partitionScanner.countRowsByTenant("social_message_2015_01"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 7)));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("social_message_2015_01");
        }

        @Test
        @DisplayName("social_message_default nigdy nie jest kandydatem do DROP, nawet gdyby zwrócony defensywnie")
        void socialMessageDefaultPartition_isNeverDropped() {
            LocalDate veryOldCutoff = LocalDate.of(1970, 1, 1);
            PartitionScanner.PartitionInfo defaultPartition =
                    new PartitionScanner.PartitionInfo("social_message_default", veryOldCutoff, veryOldCutoff.plusYears(1));

            when(partitionScanner.listPartitions("social_message")).thenReturn(List.of(defaultPartition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("social_message_default");
        }
    }

    // =========================================================================
    // Scenariusz BE-135: email_message — blokada DROP niepustej partycji (WP-5, obiekty S3)
    // =========================================================================

    @Nested
    @DisplayName("email_message (BE-135) — DROP tylko pustej partycji; obiekty S3 (WP-5) nigdy nie są osierocone przez DROP")
    class EmailMessagePartitionReclaim {

        @Test
        @DisplayName("partycja email_message_* pusta, starsza niż globalny próg CONTACT_INTERACTIONS -> DROP WYKONANY")
        void emptyEmailMessagePartitionOlderThanThreshold_isDropped() {
            LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(60);
            PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("email_message_2015_01", cutoff.minusMonths(6));

            when(partitionScanner.listPartitions("email_message")).thenReturn(List.of(oldPartition));
            when(partitionScanner.countRowsByTenant("email_message_2015_01")).thenReturn(List.of());

            job.runReclaimJob();

            verify(partitionScanner).dropPartition("email_message_2015_01");
        }

        @Test
        @DisplayName("partycja email_message_* z wierszem z załącznikiem (lub bez) po max retencji -> DROP POMINIĘTY, nawet gdy wiersz jest jeden")
        void nonEmptyEmailMessagePartition_dropIsSkipped_evenForSingleRow() {
            LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(60);
            PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("email_message_2015_01", cutoff.minusMonths(6));

            when(partitionScanner.listPartitions("email_message")).thenReturn(List.of(oldPartition));
            when(partitionScanner.countRowsByTenant("email_message_2015_01"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 1)));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("email_message_2015_01");
        }

        @Test
        @DisplayName("WARN: partycja email_message_* z wierszami -> log zawiera nazwę partycji, liczbę wierszy, liczbę tenantów i wskazówkę 'uruchom purge Poziom 1'")
        void logsWarnWithRowCountTenantCountAndLevelOneHint() {
            ch.qos.logback.classic.Logger jobLogger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PartitionReclaimJob.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            jobLogger.addAppender(appender);
            try {
                LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(60);
                PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("email_message_2015_01", cutoff.minusMonths(6));

                when(partitionScanner.listPartitions("email_message")).thenReturn(List.of(oldPartition));
                when(partitionScanner.countRowsByTenant("email_message_2015_01"))
                        .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 3),
                                new PartitionScanner.TenantRowCount(TENANT_B, 2)));

                job.runReclaimJob();

                assertThat(appender.list)
                        .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .anySatisfy(msg -> assertThat(msg)
                                .contains("email_message_2015_01")
                                .contains("5 wierszy")
                                .contains("2 tenantów")
                                .contains("POMIJAM DROP")
                                .contains("uruchom purge Poziom 1"));
            } finally {
                jobLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("email_message_default nigdy nie jest kandydatem do DROP, nawet gdyby zwrócony defensywnie")
        void emailMessageDefaultPartition_isNeverDropped() {
            LocalDate veryOldCutoff = LocalDate.of(1970, 1, 1);
            PartitionScanner.PartitionInfo defaultPartition =
                    new PartitionScanner.PartitionInfo("email_message_default", veryOldCutoff, veryOldCutoff.plusYears(1));

            when(partitionScanner.listPartitions("email_message")).thenReturn(List.of(defaultPartition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("email_message_default");
        }

        @Test
        @DisplayName("bufor: partycja email_message_* młodsza niż globalny próg (wewnątrz okna retencji) -> DROP NIE jest wykonywany, nawet gdy pusta")
        void emptyEmailMessagePartitionWithinRetentionWindow_isNotDropped() {
            LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(60);
            // rangeEnd = próg + 1 miesiąc -> partycja jeszcze w oknie retencji
            PartitionScanner.PartitionInfo youngPartition = partitionEndingAt("email_message_2020_06", cutoff.plusMonths(1));

            when(partitionScanner.listPartitions("email_message")).thenReturn(List.of(youngPartition));
            when(partitionScanner.countRowsByTenant("email_message_2020_06")).thenReturn(List.of());

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("email_message_2020_06");
        }
    }

    // =========================================================================
    // Scenariusz BE-123: audit_log/plugin_invocation_log — horyzont platformowy (AFTER_CUTOFF)
    // =========================================================================

    @Nested
    @DisplayName("audit_log/plugin_invocation_log (BE-123) — DropMode.AFTER_CUTOFF: DROP mimo niepustej partycji, INFO nie WARN")
    class PlatformHorizonReclaim {

        @Test
        @DisplayName("audit_log: partycja starsza niż horyzont (24 mies.), PUSTA -> DROP WYKONANY")
        void emptyAuditLogPartitionOlderThanHorizon_isDropped() {
            LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(24);
            PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("audit_log_2015_01", cutoff.minusMonths(6));

            when(partitionScanner.listPartitions("audit_log")).thenReturn(List.of(oldPartition));
            when(partitionScanner.countRows("audit_log_2015_01")).thenReturn(0L);

            job.runReclaimJob();

            verify(partitionScanner).dropPartition("audit_log_2015_01");
        }

        @Test
        @DisplayName("audit_log: partycja starsza niż horyzont, NIEPUSTA (wiersze tenant_id IS NULL i z tenantem) -> DROP WYKONANY, brak NPE (BE-123 pkt 5)")
        void nonEmptyAuditLogPartitionOlderThanHorizon_isDroppedAnyway_noNpe() {
            LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(24);
            PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("audit_log_2015_02", cutoff.minusMonths(6));

            when(partitionScanner.listPartitions("audit_log")).thenReturn(List.of(oldPartition));
            // countRows (BEZ grupowania po tenancie) jest użyte dla ścieżki platformowej — nie
            // countRowsByTenant, które PRZED BE-123 pkt 5 rzucałoby NullPointerException dla
            // wiersza z tenant_id IS NULL (zdarzenie globalne audit_log). Patrz też
            // PartitionScannerImplTest (jednostkowy dowód bugu na countRowsByTenant) i
            // PartitionReclaimPlatformHorizonIntegrationTest (dowód na prawdziwej bazie).
            when(partitionScanner.countRows("audit_log_2015_02")).thenReturn(2L);

            job.runReclaimJob();

            verify(partitionScanner).dropPartition("audit_log_2015_02");
            verify(partitionScanner, never()).countRowsByTenant("audit_log_2015_02");
        }

        @Test
        @DisplayName("audit_log: INFO (nie WARN) zawiera nazwę partycji i liczbę wierszy, brak 'POMIJAM DROP'")
        void logsInfoNotWarn_withRowCount() {
            ch.qos.logback.classic.Logger jobLogger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PartitionReclaimJob.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            jobLogger.addAppender(appender);
            try {
                LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(24);
                PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("audit_log_2015_03", cutoff.minusMonths(6));

                when(partitionScanner.listPartitions("audit_log")).thenReturn(List.of(oldPartition));
                when(partitionScanner.countRows("audit_log_2015_03")).thenReturn(5L);

                job.runReclaimJob();

                assertThat(appender.list)
                        .filteredOn(e -> e.getFormattedMessage().contains("audit_log_2015_03"))
                        .isNotEmpty()
                        .allSatisfy(e -> assertThat(e.getLevel())
                                .as("brak WARN dla partycji platformowej niepustej po horyzoncie (DropMode.AFTER_CUTOFF)")
                                .isNotEqualTo(ch.qos.logback.classic.Level.WARN))
                        .anySatisfy(e -> assertThat(e.getFormattedMessage())
                                .contains("5 wierszy")
                                .doesNotContain("POMIJAM DROP"));
            } finally {
                jobLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("audit_log: partycja młodsza niż horyzont (23 mies.) -> DROP NIE jest wykonywany")
        void auditLogPartitionYoungerThanHorizon_isNotDropped() {
            LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(23);
            PartitionScanner.PartitionInfo youngPartition = partitionEndingAt("audit_log_2024_11", cutoff.plusMonths(1));

            when(partitionScanner.listPartitions("audit_log")).thenReturn(List.of(youngPartition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("audit_log_2024_11");
        }

        @Test
        @DisplayName("audit_log_default nigdy nie jest kandydatem do DROP, nawet gdyby zwrócony defensywnie")
        void auditLogDefaultPartition_isNeverDropped() {
            LocalDate veryOldCutoff = LocalDate.of(1970, 1, 1);
            PartitionScanner.PartitionInfo defaultPartition =
                    new PartitionScanner.PartitionInfo("audit_log_default", veryOldCutoff, veryOldCutoff.plusYears(1));

            when(partitionScanner.listPartitions("audit_log")).thenReturn(List.of(defaultPartition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("audit_log_default");
        }

        @Test
        @DisplayName("plugin_invocation_log: partycja starsza niż horyzont, PUSTA -> DROP WYKONANY; młodsza -> NIE")
        void pluginInvocationLog_dropsOldEmpty_keepsYoung() {
            LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(24);
            PartitionScanner.PartitionInfo oldPartition =
                    partitionEndingAt("plugin_invocation_log_2015_01", cutoff.minusMonths(6));
            PartitionScanner.PartitionInfo youngPartition =
                    partitionEndingAt("plugin_invocation_log_2024_11", cutoff.plusMonths(1));

            when(partitionScanner.listPartitions("plugin_invocation_log"))
                    .thenReturn(List.of(oldPartition, youngPartition));
            when(partitionScanner.countRows("plugin_invocation_log_2015_01")).thenReturn(0L);

            job.runReclaimJob();

            verify(partitionScanner).dropPartition("plugin_invocation_log_2015_01");
            verify(partitionScanner, never()).dropPartition("plugin_invocation_log_2024_11");
        }

        @Test
        @DisplayName("ścieżka horyzontu platformowego NIE zależy od RetentionPolicyService — usługa rzuca wyjątek dla KAŻDEGO wywołania, audit_log i plugin_invocation_log są nadal przetwarzane")
        void platformHorizonPath_isIndependentOfRetentionPolicyService() {
            when(retentionPolicyService.findMaxRetentionMonths(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new ResourceNotFoundException("brak polityki (symulacja awarii usługi)"));

            LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusMonths(24);
            PartitionScanner.PartitionInfo oldAuditLog = partitionEndingAt("audit_log_2015_04", cutoff.minusMonths(6));
            PartitionScanner.PartitionInfo oldPluginLog =
                    partitionEndingAt("plugin_invocation_log_2015_04", cutoff.minusMonths(6));

            when(partitionScanner.listPartitions("audit_log")).thenReturn(List.of(oldAuditLog));
            when(partitionScanner.listPartitions("plugin_invocation_log")).thenReturn(List.of(oldPluginLog));
            when(partitionScanner.countRows("audit_log_2015_04")).thenReturn(0L);
            when(partitionScanner.countRows("plugin_invocation_log_2015_04")).thenReturn(0L);

            // Nie powinno wysadzić joba (RetentionPolicyService nigdy nie jest wołane dla
            // ThresholdSource.PlatformHorizon — patrz PartitionReclaimJob#resolveThresholdMonths).
            job.runReclaimJob();

            verify(partitionScanner).dropPartition("audit_log_2015_04");
            verify(partitionScanner).dropPartition("plugin_invocation_log_2015_04");
            // Tabele per-tenant (CategoryMaxRetention) są tymi, które faktycznie zależą od usługi —
            // brak polityki powoduje ich pominięcie w tym przebiegu (zachowanie bez zmian, BE-115).
            verify(partitionScanner, never()).dropPartition(org.mockito.ArgumentMatchers.startsWith("contact_"));
        }
    }

    // =========================================================================
    // Scenariusz BE-123: partycja _default niepusta -> WARN (sygnał awarii rotacji), dla KAŻDEJ tabeli
    // =========================================================================

    @Nested
    @DisplayName("Partycja _default niepusta (BE-123, pkt 3) — WARN, sygnał awarii rotacji, DLA KAŻDEJ tabeli")
    class DefaultPartitionPollutedWarns {

        @Test
        @DisplayName("contact_default z wierszami -> WARN z nazwą partycji i liczbą wierszy; DROP wciąż nie dotyka _default")
        void nonEmptyDefaultPartition_logsWarn() {
            ch.qos.logback.classic.Logger jobLogger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PartitionReclaimJob.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            jobLogger.addAppender(appender);
            try {
                when(partitionScanner.countRows("contact_default")).thenReturn(7L);

                job.runReclaimJob();

                assertThat(appender.list)
                        .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .anySatisfy(msg -> assertThat(msg)
                                .contains("contact_default")
                                .contains("7 wierszy")
                                .contains("DEFAULT")
                                .contains("AWARII ROTACJI"));
                assertThat(appender.list)
                        .filteredOn(e -> e.getFormattedMessage().contains("contact_default"))
                        .allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
            } finally {
                jobLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("audit_log_default z wierszami -> WARN (ten sam mechanizm dla tabel platformowych)")
        void nonEmptyAuditLogDefaultPartition_logsWarn() {
            ch.qos.logback.classic.Logger jobLogger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PartitionReclaimJob.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            jobLogger.addAppender(appender);
            try {
                when(partitionScanner.countRows("audit_log_default")).thenReturn(3L);

                job.runReclaimJob();

                assertThat(appender.list)
                        .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .anySatisfy(msg -> assertThat(msg)
                                .contains("audit_log_default")
                                .contains("3 wierszy")
                                .contains("AWARII ROTACJI"));
            } finally {
                jobLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("wszystkie partycje _default puste (domyślny stub countRows=0) -> brak WARN o AWARII ROTACJI")
        void allDefaultPartitionsEmpty_noWarn() {
            ch.qos.logback.classic.Logger jobLogger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PartitionReclaimJob.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            jobLogger.addAppender(appender);
            try {
                job.runReclaimJob();

                assertThat(appender.list)
                        .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .noneMatch(msg -> msg.contains("AWARII ROTACJI"));
            } finally {
                jobLogger.detachAppender(appender);
                appender.stop();
            }
        }
    }
}
