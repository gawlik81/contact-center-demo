package com.contactcenter.domain.service;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.reports.dto.AgentReportParams;
import com.contactcenter.api.reports.dto.AgentReportRow;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.infrastructure.config.RedisConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Serwis raportów historycznych – agregacje per agent i kampania (BE-028).
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Pobieranie i paginowanie zagregowanych statystyk agentów z bazy danych</li>
 *   <li>Cachowanie wyników w Redis (TTL 5 min, klucz: {@code cache:reports:agents:{md5}})</li>
 *   <li>Eksport do CSV i XLSX (Apache POI)</li>
 *   <li>Walidacja zakresu dat (max 90 dni)</li>
 * </ul>
 *
 * <p>Cache: zamiast {@code @Cacheable} używamy ręcznego cachowania przez {@link RedisTemplate},
 * ponieważ {@code PagedResponse<AgentReportRow>} jest generycznym rekordem z zagnieżdżoną listą
 * i {@code GenericJackson2JsonRedisSerializer} wymaga typów non-final z metadanymi @class.
 * Ręczne podejście pozwala uniknąć problemów serializacji (analogiczna sytuacja jak z adminMetricsConfig
 * w RedisConfig dla AdminMetricsResponse).
 *
 * <p>Dostęp wyłącznie dla ról SUPERVISOR i ADMIN – weryfikacja w {@link com.contactcenter.api.reports.ReportsController}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportsService {

    /** Prefix klucza Redis dla cache raportów agentów. */
    private static final String REPORT_CACHE_PREFIX = "cache:reports:agents:";

    /** Maksymalny dozwolony zakres dat (90 dni). */
    private static final long MAX_DATE_RANGE_DAYS = 90L;

    /** Nagłówki kolumn CSV/XLSX. */
    private static final String[] REPORT_HEADERS = {
            "Agent ID", "Agent Name", "Date", "Contacts Count",
            "Avg Handle Time (s)", "Avg Wait Time (s)", "FCR", "Channel"
    };

    private final ContactService contactService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Pobieranie raportu agentów z paginacją
    // =========================================================================

    /**
     * Pobiera paginowane, zagregowane statystyki agentów.
     *
     * <p>Wyniki są cachowane w Redis z TTL 5 min. Klucz cache to MD5 hash
     * parametrów zapytania (tenantId + params.toString()).
     *
     * @param params   parametry filtrowania i paginacji
     * @param tenantId UUID tenanta z TenantContext (wymuszany przez kontroler)
     * @return paginowana lista wierszy raportu
     * @throws IllegalArgumentException gdy zakres dat przekracza 90 dni
     */
    @Transactional(readOnly = true)
    public PagedResponse<AgentReportRow> getAgentReport(AgentReportParams params, UUID tenantId) {
        validateDateRange(params.dateFrom(), params.dateTo());

        String cacheKey = buildCacheKey(tenantId, params);

        // Próba odczytu z cache
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            try {
                PagedResponse<AgentReportRow> cached = objectMapper.readValue(
                        cachedJson, new TypeReference<PagedResponse<AgentReportRow>>() {});
                log.debug("[Reports] Cache hit: key={}", cacheKey);
                return cached;
            } catch (Exception e) {
                log.warn("[Reports] Błąd deserializacji cache, pomijam: key={}, error={}", cacheKey, e.getMessage());
                stringRedisTemplate.delete(cacheKey);
            }
        }

        log.debug("[Reports] Cache miss – odpytuje DB: tenant={}, dateFrom={}, dateTo={}, page={}, size={}",
                tenantId, params.dateFrom(), params.dateTo(), params.page(), params.size());

        List<Object[]> rows = contactService.findAgentReportRows(
                tenantId, params.agentId(), params.queueId(), params.channel(),
                params.dateFrom(), params.dateTo(),
                params.offset(), params.size());

        long totalElements = contactService.countAgentReportRows(
                tenantId, params.agentId(), params.queueId(), params.channel(),
                params.dateFrom(), params.dateTo());

        List<AgentReportRow> content = rows.stream()
                .map(this::mapRowToDto)
                .toList();

        int totalPages = params.size() > 0 ? (int) Math.ceil((double) totalElements / params.size()) : 0;

        PagedResponse<AgentReportRow> response = new PagedResponse<>(
                content,
                params.page(),
                params.size(),
                totalElements,
                totalPages,
                params.page() == 0,
                params.page() >= totalPages - 1
        );

        // Zapisz do cache (TTL 5 min) – JSON string, unika problemu @class z GenericJackson2JsonRedisSerializer
        try {
            String json = objectMapper.writeValueAsString(response);
            stringRedisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(RedisConfig.TTL_REPORT_CACHE.toSeconds()));
            log.debug("[Reports] Zapisano do cache: key={}", cacheKey);
        } catch (Exception e) {
            log.warn("[Reports] Błąd zapisu do cache Redis: {}", e.getMessage());
        }

        log.info("[Reports] Raport agentów: tenant={}, totalElements={}, page={}/{}",
                tenantId, totalElements, params.page(), totalPages);
        return response;
    }

    // =========================================================================
    // Eksport CSV
    // =========================================================================

    /**
     * Eksportuje raport agentów do formatu CSV (UTF-8).
     *
     * <p>Pobiera wszystkie dane (bez paginacji – max 90 dni * n agentów).
     * Separator: przecinek. Nagłówki w pierwszym wierszu.
     * Wartości numeryczne zaokrąglone do 2 miejsc po przecinku.
     *
     * @param params   parametry filtrowania (bez paginacji – size/page ignorowane)
     * @param tenantId UUID tenanta
     * @return bajty pliku CSV zakodowanego w UTF-8
     * @throws IllegalArgumentException gdy zakres dat przekracza 90 dni
     */
    @Transactional(readOnly = true)
    public byte[] exportAgentReportCsv(AgentReportParams params, UUID tenantId) {
        validateDateRange(params.dateFrom(), params.dateTo());

        log.info("[Reports] Eksport CSV: tenant={}, dateFrom={}, dateTo={}",
                tenantId, params.dateFrom(), params.dateTo());

        List<AgentReportRow> allRows = fetchAllRows(params, tenantId);

        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", REPORT_HEADERS)).append("\n");

        for (AgentReportRow row : allRows) {
            csv.append(escapeCsv(row.agentId().toString())).append(",");
            csv.append(escapeCsv(row.agentName())).append(",");
            csv.append(row.date()).append(",");
            csv.append(row.contactsCount()).append(",");
            csv.append(round2(row.avgHandleTimeSeconds())).append(",");
            csv.append(round2(row.avgWaitTimeSeconds())).append(",");
            csv.append(round2(row.firstContactResolution())).append(",");
            csv.append(escapeCsv(row.channel())).append("\n");
        }

        log.debug("[Reports] CSV eksport: {} wierszy danych", allRows.size());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Eksport XLSX (Apache POI)
    // =========================================================================

    /**
     * Eksportuje raport agentów do formatu XLSX (Excel).
     *
     * <p>Tworzy arkusz "Agent Report" z nagłówkami pogrubionymi i wypełnionymi kolorem.
     * Kolumny numeryczne (avgHandleTime, avgWaitTime, FCR) formatowane jako liczby
     * z 2 miejscami po przecinku.
     *
     * @param params   parametry filtrowania
     * @param tenantId UUID tenanta
     * @return bajty pliku XLSX
     * @throws IllegalArgumentException gdy zakres dat przekracza 90 dni
     * @throws ReportExportException    gdy nie można zapisać pliku XLSX
     */
    @Transactional(readOnly = true)
    public byte[] exportAgentReportXlsx(AgentReportParams params, UUID tenantId) {
        validateDateRange(params.dateFrom(), params.dateTo());

        log.info("[Reports] Eksport XLSX: tenant={}, dateFrom={}, dateTo={}",
                tenantId, params.dateFrom(), params.dateTo());

        List<AgentReportRow> allRows = fetchAllRows(params, tenantId);

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Agent Report");

            // Styl nagłówka – pogrubiony, szare tło
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            // Styl komórek numerycznych
            CellStyle numberStyle = workbook.createCellStyle();
            var dataFormat = workbook.createDataFormat();
            numberStyle.setDataFormat(dataFormat.getFormat("0.00"));

            // Wiersz nagłówka
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < REPORT_HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(REPORT_HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            // Wiersze danych
            int rowNum = 1;
            for (AgentReportRow row : allRows) {
                Row dataRow = sheet.createRow(rowNum++);

                dataRow.createCell(0).setCellValue(row.agentId().toString());
                dataRow.createCell(1).setCellValue(row.agentName());
                dataRow.createCell(2).setCellValue(row.date().toString());

                Cell countCell = dataRow.createCell(3);
                countCell.setCellValue(row.contactsCount());

                Cell ahtCell = dataRow.createCell(4);
                ahtCell.setCellValue(round2(row.avgHandleTimeSeconds()));
                ahtCell.setCellStyle(numberStyle);

                Cell awtCell = dataRow.createCell(5);
                awtCell.setCellValue(round2(row.avgWaitTimeSeconds()));
                awtCell.setCellStyle(numberStyle);

                Cell fcrCell = dataRow.createCell(6);
                fcrCell.setCellValue(round2(row.firstContactResolution()));
                fcrCell.setCellStyle(numberStyle);

                dataRow.createCell(7).setCellValue(row.channel());
            }

            // Autoresize kolumn (czytelność)
            for (int i = 0; i < REPORT_HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            log.debug("[Reports] XLSX eksport: {} wierszy danych", allRows.size());
            return out.toByteArray();

        } catch (IOException e) {
            log.error("[Reports] Błąd generowania pliku XLSX: {}", e.getMessage(), e);
            throw new ReportExportException("Nie można wygenerować pliku XLSX: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Pobiera wszystkie wiersze raportu (bez paginacji) – używane przez eksporty.
     *
     * <p>Zwraca max 10 000 wierszy – ochrona przed zbyt dużym zestawem danych.
     */
    private List<AgentReportRow> fetchAllRows(AgentReportParams params, UUID tenantId) {
        List<Object[]> rows = contactService.findAgentReportRows(
                tenantId, params.agentId(), params.queueId(), params.channel(),
                params.dateFrom(), params.dateTo(),
                0, 10_000);

        return rows.stream()
                .map(this::mapRowToDto)
                .toList();
    }

    /**
     * Mapuje tablicę Object[] z natywnego zapytania SQL na DTO {@link AgentReportRow}.
     *
     * <p>Kolejność kolumn musi odpowiadać zapytaniu w {@link com.contactcenter.domain.contact.ContactService#findAgentReportRows}:
     * {@code [agent_id(0), agent_name(1), report_date(2), contacts_count(3),
     *          avg_handle_time(4), avg_wait_time(5), fcr(6), channel(7)]}.
     */
    private AgentReportRow mapRowToDto(Object[] row) {
        UUID agentId = UUID.fromString(row[0].toString());
        String agentName = row[1] != null ? row[1].toString() : "Unknown";
        LocalDate date = ((java.sql.Date) row[2]).toLocalDate();
        long contactsCount = ((Number) row[3]).longValue();
        double avgHandleTime = row[4] != null ? ((Number) row[4]).doubleValue() : 0.0;
        double avgWaitTime = row[5] != null ? ((Number) row[5]).doubleValue() : 0.0;
        double fcr = row[6] != null ? ((Number) row[6]).doubleValue() : 0.0;
        String channel = row[7] != null ? row[7].toString() : "";

        return new AgentReportRow(agentId, agentName, date, contactsCount, avgHandleTime, avgWaitTime, fcr, channel);
    }

    /**
     * Waliduje zakres dat – max 90 dni.
     *
     * @throws IllegalArgumentException gdy zakres przekracza 90 dni lub dateTo < dateFrom
     */
    private void validateDateRange(LocalDate dateFrom, LocalDate dateTo) {
        if (dateTo.isBefore(dateFrom)) {
            throw new IllegalArgumentException(
                    "dateTo (" + dateTo + ") nie może być wcześniejsza niż dateFrom (" + dateFrom + ")");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(dateFrom, dateTo);
        if (days > MAX_DATE_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Zakres dat nie może przekraczać " + MAX_DATE_RANGE_DAYS + " dni. " +
                    "Podano: " + days + " dni (" + dateFrom + " – " + dateTo + ")");
        }
    }

    /**
     * Buduje klucz Redis dla cache raportu: {@code cache:reports:agents:{md5}}.
     *
     * <p>MD5 jest wystarczający dla celów cache key (nie kryptograficznych) –
     * wygodna metoda dostępna w Spring bez dodatkowych zależności.
     */
    private String buildCacheKey(UUID tenantId, AgentReportParams params) {
        String raw = tenantId + "|" + params.dateFrom() + "|" + params.dateTo() + "|"
                + params.agentId() + "|" + params.queueId() + "|" + params.channel()
                + "|" + params.page() + "|" + params.size();
        return REPORT_CACHE_PREFIX + DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Ucieczka wartości dla CSV (obsługa przecinków i cudzysłowów). */
    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Zaokrągla do 2 miejsc po przecinku (BigDecimal dla precyzji). */
    private double round2(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // =========================================================================
    // Wyjątek eksportu
    // =========================================================================

    /**
     * Wyjątek rzucany przy błędzie generowania pliku eksportu (CSV/XLSX).
     */
    public static class ReportExportException extends RuntimeException {
        public ReportExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
