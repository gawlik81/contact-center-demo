package com.contactcenter.domain;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.reports.dto.AgentReportParams;
import com.contactcenter.api.reports.dto.AgentReportRow;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.service.ReportsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link ReportsService}.
 *
 * <p>Weryfikuje logikę biznesową bez dostępu do prawdziwej bazy danych i Redis.
 * Używa mocków {@link ContactRepository} i {@link StringRedisTemplate}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReportsService – raporty historyczne agentów")
class ReportsServiceTest {

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private ReportsService reportsService;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID AGENT_ID  = UUID.randomUUID();

    private static final LocalDate DATE_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate DATE_TO   = LocalDate.of(2026, 1, 31);

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // Domyślnie cache miss
        when(valueOperations.get(anyString())).thenReturn(null);
    }

    // =========================================================================
    // Walidacja zakresu dat
    // =========================================================================

    @Nested
    @DisplayName("Walidacja zakresu dat")
    class DateRangeValidation {

        @Test
        @DisplayName("Powinien rzucić wyjątek gdy zakres dat przekracza 90 dni")
        void shouldThrowWhenDateRangeExceeds90Days() {
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to   = from.plusDays(91);

            AgentReportParams params = new AgentReportParams(from, to, null, null, null, 0, 20);

            assertThatThrownBy(() -> reportsService.getAgentReport(params, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("90");
        }

        @Test
        @DisplayName("Powinien rzucić wyjątek gdy dateTo < dateFrom")
        void shouldThrowWhenDateToBeforeDateFrom() {
            LocalDate from = LocalDate.of(2026, 1, 31);
            LocalDate to   = LocalDate.of(2026, 1, 1);

            AgentReportParams params = new AgentReportParams(from, to, null, null, null, 0, 20);

            assertThatThrownBy(() -> reportsService.getAgentReport(params, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dateTo");
        }

        @Test
        @DisplayName("Powinien zaakceptować dokładnie 90 dni zakresu")
        void shouldAcceptExactly90DaysRange() {
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to   = from.plusDays(90);

            AgentReportParams params = new AgentReportParams(from, to, null, null, null, 0, 20);

            when(contactRepository.findAgentReportRows(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(rowList());
            when(contactRepository.countAgentReportRows(any(), any(), any(), any(), any(), any()))
                    .thenReturn(0L);

            // Nie powinno rzucić wyjątku
            PagedResponse<AgentReportRow> result = reportsService.getAgentReport(params, TENANT_ID);
            assertThat(result).isNotNull();
        }
    }

    // =========================================================================
    // Pobieranie raportu – logika
    // =========================================================================

    @Nested
    @DisplayName("Pobieranie raportu agentów")
    class GetAgentReport {

        @Test
        @DisplayName("Powinien zwrócić paginowaną listę wierszy raportu")
        void shouldReturnPagedAgentReport() {
            Object[] row = buildSampleRow(AGENT_ID, "Jan Kowalski", DATE_FROM, 42L, 180.0, 30.0, 0.75, "PHONE");

            when(contactRepository.findAgentReportRows(
                    eq(TENANT_ID), isNull(), isNull(), isNull(),
                    eq(DATE_FROM), eq(DATE_TO), eq(0), eq(20)))
                    .thenReturn(rowList(row));
            when(contactRepository.countAgentReportRows(
                    eq(TENANT_ID), isNull(), isNull(), isNull(),
                    eq(DATE_FROM), eq(DATE_TO)))
                    .thenReturn(1L);

            AgentReportParams params = new AgentReportParams(DATE_FROM, DATE_TO, null, null, null, 0, 20);
            PagedResponse<AgentReportRow> response = reportsService.getAgentReport(params, TENANT_ID);

            assertThat(response.content()).hasSize(1);
            assertThat(response.totalElements()).isEqualTo(1L);
            assertThat(response.page()).isEqualTo(0);
            assertThat(response.size()).isEqualTo(20);

            AgentReportRow reportRow = response.content().get(0);
            assertThat(reportRow.agentId()).isEqualTo(AGENT_ID);
            assertThat(reportRow.agentName()).isEqualTo("Jan Kowalski");
            assertThat(reportRow.contactsCount()).isEqualTo(42L);
            assertThat(reportRow.avgHandleTimeSeconds()).isEqualTo(180.0);
            assertThat(reportRow.avgWaitTimeSeconds()).isEqualTo(30.0);
            assertThat(reportRow.firstContactResolution()).isEqualTo(0.75);
            assertThat(reportRow.channel()).isEqualTo("PHONE");
        }

        @Test
        @DisplayName("Powinien zwrócić cache hit gdy dane są w Redis")
        void shouldReturnCachedResponseOnCacheHit() throws Exception {
            AgentReportParams params = new AgentReportParams(DATE_FROM, DATE_TO, null, null, null, 0, 20);

            PagedResponse<AgentReportRow> cachedResponse = new PagedResponse<>(
                    List.of(), 0, 20, 0L, 0, true, true);
            String cachedJson = objectMapper.writeValueAsString(cachedResponse);
            when(valueOperations.get(anyString())).thenReturn(cachedJson);

            PagedResponse<AgentReportRow> result = reportsService.getAgentReport(params, TENANT_ID);

            assertThat(result).isEqualTo(cachedResponse);

            // Repozytorium nie powinno być odpytywane przy cache hit
            verifyNoInteractions(contactRepository);
        }

        @Test
        @DisplayName("Powinien zapisać wynik do Redis po cache miss")
        void shouldSaveResultToRedisAfterCacheMiss() {
            when(contactRepository.findAgentReportRows(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(rowList());
            when(contactRepository.countAgentReportRows(any(), any(), any(), any(), any(), any()))
                    .thenReturn(0L);

            AgentReportParams params = new AgentReportParams(DATE_FROM, DATE_TO, null, null, null, 0, 20);
            reportsService.getAgentReport(params, TENANT_ID);

            verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("Powinien obliczyć totalPages poprawnie")
        void shouldCalculateTotalPagesCorrectly() {
            when(contactRepository.findAgentReportRows(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(rowList());
            when(contactRepository.countAgentReportRows(any(), any(), any(), any(), any(), any()))
                    .thenReturn(45L);

            AgentReportParams params = new AgentReportParams(DATE_FROM, DATE_TO, null, null, null, 0, 20);
            PagedResponse<AgentReportRow> response = reportsService.getAgentReport(params, TENANT_ID);

            // 45 / 20 = 3 (ceil)
            assertThat(response.totalPages()).isEqualTo(3);
            assertThat(response.first()).isTrue();
            assertThat(response.last()).isFalse();
        }

        @Test
        @DisplayName("Powinien przekazać filtry do repozytorium")
        void shouldPassFiltersToRepository() {
            UUID queueId = UUID.randomUUID();
            AgentReportParams params = new AgentReportParams(DATE_FROM, DATE_TO, AGENT_ID, queueId, "PHONE", 0, 20);

            when(contactRepository.findAgentReportRows(
                    eq(TENANT_ID), eq(AGENT_ID), eq(queueId), eq("PHONE"),
                    eq(DATE_FROM), eq(DATE_TO), eq(0), eq(20)))
                    .thenReturn(rowList());
            when(contactRepository.countAgentReportRows(
                    eq(TENANT_ID), eq(AGENT_ID), eq(queueId), eq("PHONE"),
                    eq(DATE_FROM), eq(DATE_TO)))
                    .thenReturn(0L);

            reportsService.getAgentReport(params, TENANT_ID);

            verify(contactRepository).findAgentReportRows(
                    TENANT_ID, AGENT_ID, queueId, "PHONE", DATE_FROM, DATE_TO, 0, 20);
        }
    }

    // =========================================================================
    // Eksport CSV
    // =========================================================================

    @Nested
    @DisplayName("Eksport CSV")
    class ExportCsv {

        @Test
        @DisplayName("Powinien zwrócić niepusty plik CSV z nagłówkami")
        void shouldReturnCsvWithHeaders() {
            Object[] row = buildSampleRow(AGENT_ID, "Anna Nowak", DATE_FROM, 10L, 120.0, 15.0, 0.9, "EMAIL");

            when(contactRepository.findAgentReportRows(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(rowList(row));

            AgentReportParams params = new AgentReportParams(DATE_FROM, DATE_TO, null, null, null, 0, 100);
            byte[] csv = reportsService.exportAgentReportCsv(params, TENANT_ID);

            String csvContent = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(csvContent).startsWith("Agent ID,Agent Name,Date");
            assertThat(csvContent).contains("Anna Nowak");
            assertThat(csvContent).contains("EMAIL");
            assertThat(csvContent).contains("10");
        }

        @Test
        @DisplayName("Powinien rzucić wyjątek przy zakresie > 90 dni")
        void shouldThrowForInvalidDateRangeInExport() {
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to   = from.plusDays(91);

            AgentReportParams params = new AgentReportParams(from, to, null, null, null, 0, 100);

            assertThatThrownBy(() -> reportsService.exportAgentReportCsv(params, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Powinien escapować wartości CSV z przecinkami")
        void shouldEscapeCsvValuesWithCommas() {
            Object[] row = buildSampleRow(AGENT_ID, "Smith, John", DATE_FROM, 5L, 60.0, 10.0, 1.0, "PHONE");

            when(contactRepository.findAgentReportRows(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(rowList(row));

            AgentReportParams params = new AgentReportParams(DATE_FROM, DATE_TO, null, null, null, 0, 100);
            byte[] csv = reportsService.exportAgentReportCsv(params, TENANT_ID);

            String csvContent = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(csvContent).contains("\"Smith, John\"");
        }
    }

    // =========================================================================
    // Eksport XLSX
    // =========================================================================

    @Nested
    @DisplayName("Eksport XLSX")
    class ExportXlsx {

        @Test
        @DisplayName("Powinien zwrócić niepuste bajty XLSX")
        void shouldReturnNonEmptyXlsxBytes() {
            Object[] row = buildSampleRow(AGENT_ID, "Agent X", DATE_FROM, 7L, 90.0, 20.0, 0.85, "CHAT");

            when(contactRepository.findAgentReportRows(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(rowList(row));

            AgentReportParams params = new AgentReportParams(DATE_FROM, DATE_TO, null, null, null, 0, 100);
            byte[] xlsx = reportsService.exportAgentReportXlsx(params, TENANT_ID);

            assertThat(xlsx).isNotNull();
            assertThat(xlsx.length).isGreaterThan(0);
            // Sprawdź sygnaturę pliku OOXML (PK zip): 0x50 0x4B 0x03 0x04
            assertThat(xlsx[0]).isEqualTo((byte) 0x50);
            assertThat(xlsx[1]).isEqualTo((byte) 0x4B);
        }

        @Test
        @DisplayName("Powinien rzucić wyjątek przy zakresie > 90 dni")
        void shouldThrowForInvalidDateRangeInXlsx() {
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to   = from.plusDays(91);

            AgentReportParams params = new AgentReportParams(from, to, null, null, null, 0, 100);

            assertThatThrownBy(() -> reportsService.exportAgentReportXlsx(params, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Buduje przykładowy wiersz Object[] symulujący wynik natywnego zapytania SQL.
     *
     * <p>Kolejność kolumn odpowiada zapytaniu w ContactRepository.findAgentReportRows:
     * [agent_id, agent_name, report_date, contacts_count, avg_handle_time, avg_wait_time, fcr, channel]
     */
    private Object[] buildSampleRow(UUID agentId, String agentName, LocalDate date,
                                     long contactsCount, double avgHandleTime,
                                     double avgWaitTime, double fcr, String channel) {
        return new Object[]{
                agentId.toString(),   // agent_id (text)
                agentName,            // agent_name
                Date.valueOf(date),   // report_date (java.sql.Date)
                contactsCount,        // contacts_count
                avgHandleTime,        // avg_handle_time
                avgWaitTime,          // avg_wait_time
                fcr,                  // fcr
                channel               // channel
        };
    }

    /**
     * Pomocnik zwracający List&lt;Object[]&gt; z poprawnym typem generycznym dla Mockito.
     * {@code List.of(row)} zwraca {@code List<Object>} – niekompatybilny z {@code List<Object[]>}.
     */
    private List<Object[]> rowList(Object[]... rows) {
        java.util.ArrayList<Object[]> list = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            list.add(row);
        }
        return list;
    }
}
