package com.contactcenter.domain.tenant;

import com.contactcenter.api.admin.dto.AdminMetricsResponse;
import com.contactcenter.api.admin.dto.ContactChannelMatrix;
import com.contactcenter.api.admin.dto.GrowthMetrics;
import com.contactcenter.api.admin.dto.SystemResourceMetrics;
import com.contactcenter.api.admin.dto.TenantDetailMetrics;
import com.contactcenter.api.admin.dto.UsageMetrics;
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
     *   <li>Dla każdego klucza pobiera dane sesji (Map z polami {@code tenantId}/{@code status})</li>
     *   <li>Liczy agenta jako online, gdy {@code tenantId} pasuje do tenanta ORAZ {@code status}
     *       jest jednym z AVAILABLE/BUSY/AFTER_CONTACT – patrz Javadoc klasy powyżej</li>
     * </ol>
     *
     * @return odpowiedź z globalnymi metrykami platformy
     */
    AdminMetricsResponse getGlobalMetrics();

    /**
     * Zwraca szczegółowe metryki dla konkretnego tenanta.
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

    // =========================================================================
    // Metryki wykorzystania platformy (usage)
    // =========================================================================

    /**
     * Zwraca zagregowane wskaźniki wykorzystania platformy "dzisiaj" (cross-tenant):
     * liczba obsłużonych kontaktów, zmiana względem wczoraj, średnie czasy obsługi/oczekiwania,
     * FCR oraz stan kampanii.
     *
     * <p>Wynik jest cachowany w Redis (TTL 5 min, {@code CacheNames.ADMIN_METRICS_USAGE}).
     *
     * @return odpowiedź z metrykami wykorzystania platformy
     */
    UsageMetrics getUsageMetrics();

    /**
     * Zwraca macierz liczby kontaktów w ostatnich {@code days} dniach (kalendarzowo, dzień
     * dzisiejszy + {@code days - 1} poprzednich dni, oba krańce włącznie) w podziale na tenanta
     * i kanał komunikacji (PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP).
     *
     * <p>Zwraca WSZYSTKICH tenantów platformy (spójnie z {@link #getGlobalMetrics()}), każdy
     * z pełnym zestawem 5 kanałów – kanał bez kontaktów w zakresie ma wartość 0, nie jest
     * pomijany. Dla {@code days=1} (zakres = tylko dziś) suma kontaktów per tenant zgadzałaby
     * się z {@code TenantMetrics.contactsToday} tego samego tenanta (ta sama definicja
     * "kontaktu") – w praktyce {@code days=1} nie jest dozwoloną wartością publiczną, patrz niżej.
     *
     * <p>Wynik jest cachowany w Redis (TTL 5 min, {@code CacheNames.ADMIN_METRICS_CHANNEL_BREAKDOWN}),
     * osobno per wartość {@code days}.
     *
     * @param days liczba dni wstecz do uwzględnienia (dozwolone WYŁĄCZNIE: 7, 30, 90 –
     *             zgodnie z selektorem zakresu na froncie)
     * @return macierz liczby kontaktów w zakresie per tenant i kanał
     * @throws IllegalArgumentException gdy {@code days} nie jest jedną z wartości 7, 30, 90
     */
    ContactChannelMatrix getContactChannelMatrix(int days);

    // =========================================================================
    // Metryki wzrostu platformy (growth)
    // =========================================================================

    /**
     * Zwraca trendy wzrostu platformy: nowych tenantów/użytkowników pogrupowanych tygodniowo
     * za ostatnie {@code weeks} tygodni, oraz ranking top 5 najpopularniejszych pluginów.
     *
     * <p>Wynik jest cachowany w Redis (TTL 20 min, {@code CacheNames.ADMIN_METRICS_GROWTH}).
     *
     * @param weeks liczba ostatnich tygodni do uwzględnienia (1-52)
     * @return odpowiedź z trendami wzrostu platformy
     * @throws IllegalArgumentException gdy {@code weeks} jest poza zakresem 1-52
     */
    GrowthMetrics getGrowthMetrics(int weeks);

    // =========================================================================
    // Metryki zasobów systemowych (resources)
    // =========================================================================

    /**
     * Zwraca metryki zasobów WSPÓŁDZIELONEJ instancji JVM/procesu backendu: CPU, pamięć,
     * wątki, pulę połączeń DB, status Redis/RabbitMQ i głębokość kluczowych kolejek.
     *
     * <p><strong>Dane NIE są cachowane</strong> – zawsze aktualny stan procesu.
     *
     * @return odpowiedź z metrykami zasobów systemowych
     */
    SystemResourceMetrics getSystemResourceMetrics();

    // =========================================================================
    // Eksport CSV
    // =========================================================================

    /**
     * Eksportuje rozszerzoną tabelę metryk tenantów do formatu CSV (UTF-8).
     *
     * <p>Kolumny: name, status, agentsOnline, agentsTotal, contactsToday,
     * avgHandleTimeSeconds, fcrPercentage, agentLimitUtilizationPercent.
     *
     * @return bajty pliku CSV zakodowanego w UTF-8
     */
    byte[] exportTenantMetricsCsv();
}
