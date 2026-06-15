package com.contactcenter.domain.tenant;

import com.contactcenter.api.admin.dto.AdminMetricsResponse;
import com.contactcenter.api.admin.dto.TenantDetailMetrics;
import jakarta.persistence.EntityNotFoundException;

import java.util.UUID;

/**
 * Serwis domenowy dostarczający metryki RT (real-time) dla endpointów admin.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Pobieranie listy aktywnych tenantów z bazy danych</li>
 *   <li>Zliczanie agentów online per tenant na podstawie kluczy Redis {@code session:agent:{userId}}</li>
 *   <li>Cachowanie wyników w Redis (TTL 30s) przez {@code @Cacheable(ADMIN_METRICS)}</li>
 *   <li>Inwalidacja cache po zmianie statusu tenanta (wywoływana przez {@link TenantService})</li>
 * </ul>
 *
 * <p>Agent jest uznany za "online" gdy jego klucz {@code session:agent:{userId}} istnieje w Redis
 * z wartością wskazującą status AVAILABLE, BUSY lub AFTER_CONTACT.
 *
 * <p>Dostęp wyłącznie dla roli ADMIN – weryfikacja w {@code AdminMetricsController}
 * przez {@code @PreAuthorize("hasRole('ADMIN')")}.
 */
public interface AdminMetricsService {

    /**
     * Zwraca zagregowane metryki RT platformy – lista tenantów z agentami online.
     *
     * <p>Wynik jest cachowany w Redis ({@code admin-metrics} → klucz {@code "global"})
     * z TTL 30s zdefiniowanym w {@code RedisConfig}.
     *
     * <p>Logika liczenia agentów online:
     * <ol>
     *   <li>Skanuje klucze {@code session:agent:*} z Redis (SCAN – bezpieczniejsze niż KEYS)</li>
     *   <li>Dla każdego klucza pobiera UUID agenta</li>
     *   <li>Liczy agentów należących do danego tenanta (weryfikacja przez bazę danych)</li>
     * </ol>
     *
     * @return odpowiedź z globalnymi metrykami platformy
     */
    AdminMetricsResponse getGlobalMetrics();

    /**
     * Zwraca szczegółowe metryki dla konkretnego tenanta.
     *
     * <p>Endpoint MVP – pola {@code cpuUsage} i {@code memoryUsage} są mockami (0.0).
     * Docelowo dane te powinny być pobierane z systemu monitoringu (np. Prometheus/Grafana).
     *
     * <p>Brak cachowania na tym endpoincie – dane per tenant są mniej popularne
     * i wymagają zawsze aktualnych wartości (wywołanie rzadsze niż globalny dashboard).
     *
     * @param tenantId UUID tenanta
     * @return szczegółowe metryki tenanta
     * @throws EntityNotFoundException gdy tenant nie istnieje
     */
    TenantDetailMetrics getTenantMetrics(UUID tenantId);

    /**
     * Inwaliduje cache globalnych metryk admin.
     *
     * <p>Powinien być wywoływany po każdej zmianie statusu tenanta
     * (np. deactivateTenant, updateTenant ze zmianą statusu) aby zapewnić
     * spójność danych w dashboardzie ADMIN.
     *
     * <p>Używaj bezpośrednio z serwisów domenowych lub przez zdarzenia Spring.
     * Przykład wywołania z {@link TenantService}:
     * <pre>
     *   adminMetricsService.evictGlobalMetricsCache();
     * </pre>
     */
    void evictGlobalMetricsCache();
}
