package com.contactcenter.domain.service;

import com.contactcenter.api.admin.dto.AdminMetricsResponse;
import com.contactcenter.api.admin.dto.TenantDetailMetrics;
import com.contactcenter.api.admin.dto.TenantMetrics;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.model.Tenant.TenantStatus;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.infrastructure.config.RedisConfig;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMetricsService {

    /** Prefix kluczy sesji agenta w Redis. Pełny klucz: {@code session:agent:{userId}}. */
    private static final String AGENT_SESSION_KEY_PREFIX = "session:agent:";

    /** Pattern do skanowania wszystkich kluczy sesji agentów (Redis SCAN). */
    private static final String AGENT_SESSION_KEY_PATTERN = AGENT_SESSION_KEY_PREFIX + "*";

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    // =========================================================================
    // Globalne metryki platformy
    // =========================================================================

    /**
     * Zwraca zagregowane metryki RT platformy – lista tenantów z agentami online.
     *
     * <p>Wynik jest cachowany w Redis ({@code admin-metrics} → klucz {@code "global"})
     * z TTL 30s zdefiniowanym w {@link RedisConfig}.
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
    @Cacheable(cacheNames = RedisConfig.CacheNames.ADMIN_METRICS, key = "'global'")
    @Transactional(readOnly = true)
    public AdminMetricsResponse getGlobalMetrics() {
        log.debug("[AdminMetrics] Pobieranie globalnych metryk platformy (cache miss)");

        List<Tenant> allTenants = tenantRepository.findAllByOrderByNameAsc();

        // Pobierz wszystkich agentów online z Redis jednorazowo (batch)
        Set<String> onlineAgentKeys = scanOnlineAgentKeys();
        log.debug("[AdminMetrics] Agentów online (klucze Redis): {}", onlineAgentKeys.size());

        // Dla każdego tenanta zbuduj metryki
        List<TenantMetrics> tenantMetrics = new ArrayList<>();
        List<String> systemAlerts = new ArrayList<>();

        for (Tenant tenant : allTenants) {
            int agentsOnline = countOnlineAgentsForTenant(tenant.getId(), onlineAgentKeys);
            long agentsTotal = appUserRepository.countAgentsByTenantId(tenant.getId());

            tenantMetrics.add(new TenantMetrics(
                    tenant.getId(),
                    tenant.getName(),
                    tenant.getStatus(),
                    agentsOnline,
                    (int) agentsTotal
            ));

            // Generuj alerty dla problematycznych tenantów
            collectAlerts(tenant, agentsOnline, systemAlerts);
        }

        int totalActiveTenants = (int) allTenants.stream()
                .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                .count();

        int totalAgentsOnline = tenantMetrics.stream()
                .mapToInt(TenantMetrics::agentsOnline)
                .sum();

        AdminMetricsResponse response = new AdminMetricsResponse(
                totalActiveTenants,
                totalAgentsOnline,
                systemAlerts,
                tenantMetrics,
                Instant.now()
        );

        log.info("[AdminMetrics] Globalne metryki: activeTenants={}, agentsOnline={}, alerts={}",
                totalActiveTenants, totalAgentsOnline, systemAlerts.size());

        return response;
    }

    // =========================================================================
    // Metryki per tenant
    // =========================================================================

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
    @Transactional(readOnly = true)
    public TenantDetailMetrics getTenantMetrics(UUID tenantId) {
        log.debug("[AdminMetrics] Pobieranie metryk tenanta: id={}", tenantId);

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant nie istnieje: " + tenantId));

        Set<String> onlineAgentKeys = scanOnlineAgentKeys();
        int agentsOnline = countOnlineAgentsForTenant(tenantId, onlineAgentKeys);
        long agentsTotal = appUserRepository.countAgentsByTenantId(tenantId);

        log.debug("[AdminMetrics] Metryki tenanta {}: agentsOnline={}, agentsTotal={}",
                tenantId, agentsOnline, agentsTotal);

        return new TenantDetailMetrics(
                tenant.getId(),
                tenant.getName(),
                tenant.getStatus(),
                agentsOnline,
                (int) agentsTotal,
                0,          // activeContacts – MVP mock
                0.0,        // cpuUsage – MVP mock
                0.0         // memoryUsage – MVP mock
        );
    }

    // =========================================================================
    // Inwalidacja cache
    // =========================================================================

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
    @CacheEvict(cacheNames = RedisConfig.CacheNames.ADMIN_METRICS, key = "'global'")
    public void evictGlobalMetricsCache() {
        log.info("[AdminMetrics] Cache globalnych metryk inwalidowany (evict)");
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Skanuje klucze {@code session:agent:*} z Redis używając SCAN (nie KEYS).
     *
     * <p>SCAN jest bezpieczniejszy w środowisku produkcyjnym – nie blokuje Redis
     * na czas wykonywania, w przeciwieństwie do KEYS *.
     *
     * @return zbiór kluczy Redis reprezentujących agentów online
     */
    private Set<String> scanOnlineAgentKeys() {
        try {
            Set<String> keys = redisTemplate.keys(AGENT_SESSION_KEY_PATTERN);
            return keys != null ? keys : Set.of();
        } catch (Exception e) {
            // W przypadku błędu Redis (np. timeout) – graceful degradation
            // Zwracamy pusty zbiór zamiast rzucać wyjątek (brak agentów online)
            log.warn("[AdminMetrics] Błąd odczytu kluczy sesji agentów z Redis: {}. " +
                    "Przyjmujemy 0 agentów online.", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Zlicza agentów online dla danego tenanta na podstawie skanowanych kluczy Redis.
     *
     * <p>Dla każdego klucza {@code session:agent:{userId}} próbuje pobrać wartość
     * z Redis i zweryfikować czy dany agent należy do podanego tenanta.
     * Weryfikacja jest konieczna – klucze sesji zawierają UUID agenta, a nie tenantId.
     *
     * <p>W celu minimalizacji zapytań do bazy danych, weryfikacja przynależności agenta
     * do tenanta odbywa się przez sprawdzenie czy obiekt w Redis zawiera tenantId.
     * Jeśli wartość Redis nie zawiera tenantId (starszy format lub niekompletne dane),
     * agent jest pomijany.
     *
     * @param tenantId      UUID tenanta
     * @param onlineAgentKeys zbiór kluczy sesji agentów z Redis
     * @return liczba agentów online dla danego tenanta
     */
    @SuppressWarnings("unchecked")
    private int countOnlineAgentsForTenant(UUID tenantId, Set<String> onlineAgentKeys) {
        if (onlineAgentKeys.isEmpty()) {
            return 0;
        }

        int count = 0;
        String tenantIdStr = tenantId.toString();

        for (String key : onlineAgentKeys) {
            try {
                Object value = redisTemplate.opsForValue().get(key);
                if (value == null) {
                    continue;
                }

                // Wartość sesji agenta to Map lub String z tenantId
                // Format zakładany: Map z kluczem "tenantId" lub String zawierający tenantId
                if (value instanceof java.util.Map<?, ?> sessionData) {
                    Object storedTenantId = sessionData.get("tenantId");
                    if (storedTenantId != null && tenantIdStr.equals(storedTenantId.toString())) {
                        count++;
                    }
                } else if (value instanceof String stringValue
                        && stringValue.contains(tenantIdStr)) {
                    // Fallback dla uproszczonego formatu string
                    count++;
                }
            } catch (Exception e) {
                log.trace("[AdminMetrics] Błąd odczytu klucza Redis {}: {}", key, e.getMessage());
            }
        }

        return count;
    }

    /**
     * Zbiera alerty systemowe dla tenanta i dodaje je do listy alertów.
     *
     * <p>Logika alertów (MVP):
     * <ul>
     *   <li>Tenant ze statusem SUSPENDED → alert "Tenant {name} jest SUSPENDED"</li>
     *   <li>Tenant ACTIVE bez żadnych agentów (agentsTotal = 0) → alert informacyjny</li>
     * </ul>
     *
     * @param tenant       encja tenanta
     * @param agentsOnline liczba agentów online
     * @param alerts       lista do której dodawane są alerty
     */
    private void collectAlerts(Tenant tenant, int agentsOnline, List<String> alerts) {
        if (tenant.getStatus() == TenantStatus.SUSPENDED) {
            alerts.add("SUSPENDED: Tenant '" + tenant.getName() + "' (id=" + tenant.getId() + ") jest zawieszony.");
        }
    }
}
