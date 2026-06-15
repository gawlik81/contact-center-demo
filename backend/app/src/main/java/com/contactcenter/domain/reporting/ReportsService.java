package com.contactcenter.domain.reporting;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.reports.dto.AgentReportParams;
import com.contactcenter.api.reports.dto.AgentReportRow;

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
 * <p>Dostęp wyłącznie dla ról SUPERVISOR i ADMIN – weryfikacja w {@link com.contactcenter.api.reports.ReportsController}.
 */
public interface ReportsService {

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
    PagedResponse<AgentReportRow> getAgentReport(AgentReportParams params, UUID tenantId);

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
    byte[] exportAgentReportCsv(AgentReportParams params, UUID tenantId);

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
    byte[] exportAgentReportXlsx(AgentReportParams params, UUID tenantId);
}
