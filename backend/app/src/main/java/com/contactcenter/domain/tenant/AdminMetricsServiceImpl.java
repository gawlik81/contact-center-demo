package com.contactcenter.domain.tenant;

import com.contactcenter.api.admin.dto.AdminMetricsResponse;
import com.contactcenter.api.admin.dto.ContactChannelMatrix;
import com.contactcenter.api.admin.dto.GrowthMetrics;
import com.contactcenter.api.admin.dto.QueueDepth;
import com.contactcenter.api.admin.dto.SystemResourceMetrics;
import com.contactcenter.api.admin.dto.TenantChannelRow;
import com.contactcenter.api.admin.dto.TenantDetailMetrics;
import com.contactcenter.api.admin.dto.TenantMetrics;
import com.contactcenter.api.admin.dto.TopPlugin;
import com.contactcenter.api.admin.dto.UsageMetrics;
import com.contactcenter.api.admin.dto.WeeklyGrowthPoint;
import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.infrastructure.config.RedisConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementacja {@link AdminMetricsService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AdminMetricsServiceImpl implements AdminMetricsService {

    /** Prefix kluczy sesji agenta w Redis. Pełny klucz: {@code session:agent:{userId}}. */
    private static final String AGENT_SESSION_KEY_PREFIX = "session:agent:";

    /** Pattern do skanowania wszystkich kluczy sesji agentów (Redis SCAN). */
    private static final String AGENT_SESSION_KEY_PATTERN = AGENT_SESSION_KEY_PREFIX + "*";

    /**
     * Statusy {@link com.contactcenter.domain.user.AppUser.UserStatus} liczone jako "online"
     * w metrykach RT – zgodnie z już ustaloną definicją udokumentowaną w Javadoc
     * {@link com.contactcenter.api.admin.dto.TenantMetrics#agentsOnline()} /
     * {@link com.contactcenter.api.admin.dto.TenantDetailMetrics#agentsOnline()} oraz w klasowym
     * Javadoc {@link AdminMetricsService}.
     *
     * <p>{@code BREAK} jest CELOWO wykluczony – agent na przerwie jest zalogowany, ale
     * niedostępny operacyjnie; ta sama konwencja obowiązuje w {@code SupervisorMetricsService}
     * (tam {@code BREAK} nigdy nie jest liczony jako {@code AVAILABLE}/{@code BUSY}).
     * {@code OFFLINE} i {@code INACTIVE} nigdy nie są "online".
     */
    private static final Set<String> ONLINE_AGENT_STATUSES = Set.of("AVAILABLE", "BUSY", "AFTER_CONTACT");

    /** Kolejki RabbitMQ monitorowane przez {@code GET /api/admin/metrics/resources}. */
    private static final List<String> MONITORED_QUEUES = List.of(
            RabbitMQConfig.QUEUE_CALL_EVENTS,
            RabbitMQConfig.QUEUE_CONTACT_ROUTING,
            RabbitMQConfig.QUEUE_CAMPAIGN_DIALER,
            RabbitMQConfig.QUEUE_DLQ
    );

    /** Nagłówki CSV eksportu metryk tenantów. */
    private static final String[] TENANT_METRICS_CSV_HEADERS = {
            "Tenant Name", "Status", "Agents Online", "Agents Total", "Contacts Today",
            "Avg Handle Time (s)", "FCR (%)", "Agent Limit Utilization (%)"
    };

    /**
     * Kanoniczna, stała kolejność kanałów komunikacji – odpowiada wprost wartościom
     * dozwolonym przez CHECK constraint kolumny {@code contact.channel} (V025 – kolumna
     * VARCHAR, bez osobnego enuma Java). Używana jako kolumny {@link ContactChannelMatrix}
     * oraz do zero-fill brakujących kanałów w {@link TenantChannelRow#countsByChannel()}.
     */
    private static final List<String> CONTACT_CHANNELS = List.of(
            "PHONE", "EMAIL", "SOCIAL_FACEBOOK", "SOCIAL_INSTAGRAM", "SOCIAL_WHATSAPP"
    );

    /**
     * Jedyne dozwolone wartości parametru {@code days} dla {@link #getContactChannelMatrix(int)} –
     * zgodnie z opcjami selektora zakresu "7/30/90 dni" na froncie (ten sam selektor napędza
     * {@code GET /api/admin/metrics/growth?weeks=N}, ale tam zakres jest ciągły 1-52 tygodni,
     * podczas gdy tutaj z założenia dozwolony jest wyłącznie ten dyskretny zbiór wartości).
     */
    private static final Set<Integer> ALLOWED_CHANNEL_MATRIX_DAYS = Set.of(7, 30, 90);

    private final TenantService tenantService;
    private final UserService userService;
    private final ContactService contactService;
    private final CampaignService campaignService;
    private final PluginCatalogQueryService pluginCatalogQueryService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin rabbitAdmin;
    private final MeterRegistry meterRegistry;

    // =========================================================================
    // Globalne metryki platformy
    // =========================================================================

    @Override
    @Cacheable(cacheNames = RedisConfig.CacheNames.ADMIN_METRICS, key = "'global'")
    @Transactional(readOnly = true)
    public AdminMetricsResponse getGlobalMetrics() {
        log.debug("[AdminMetrics] Pobieranie globalnych metryk platformy (cache miss)");

        List<Tenant> allTenants = tenantService.getAllTenants();

        // Pobierz wszystkich agentów online z Redis jednorazowo (batch)
        Set<String> onlineAgentKeys = scanOnlineAgentKeys();
        log.debug("[AdminMetrics] Agentów online (klucze Redis): {}", onlineAgentKeys.size());

        // Dla każdego tenanta zbuduj metryki
        List<TenantMetrics> tenantMetrics = new ArrayList<>();
        List<String> systemAlerts = new ArrayList<>();
        int totalActiveContacts = 0;
        LocalDate today = LocalDate.now();

        for (Tenant tenant : allTenants) {
            int agentsOnline = countOnlineAgentsForTenant(tenant.getId(), onlineAgentKeys);
            long agentsTotal = userService.countAgentsByTenantId(tenant.getId());

            // Kontakty aktywne (w toku) – jedno zapytanie per tenant, sumowane globalnie
            long activeContacts = contactService.countActiveContactsByTenantId(tenant.getId());
            totalActiveContacts += (int) activeContacts;

            // Agregacja dzienna (kontakty dzisiaj, śr. czas obsługi, FCR) – jedno zapytanie per tenant
            Object[] dailyAggregate = contactService.getDailyContactAggregate(tenant.getId(), today);
            long contactsToday = toLong(dailyAggregate[0]);
            long sumHandleTime = toLong(dailyAggregate[1]);
            long fcrCount = toLong(dailyAggregate[3]);

            int avgHandleTimeSeconds = contactsToday > 0
                    ? (int) Math.round((double) sumHandleTime / contactsToday) : 0;
            double fcrPercentage = contactsToday > 0
                    ? round2(((double) fcrCount / contactsToday) * 100) : 0.0;

            // Wykorzystanie limitu agentów (capacity planning) – NIE mylić z agentsOnline/agentsTotal
            int maxAgents = tenant.getMaxAgents();
            int agentLimitUtilizationPercent = maxAgents > 0
                    ? (int) Math.round((agentsTotal * 100.0) / maxAgents) : 0;

            tenantMetrics.add(new TenantMetrics(
                    tenant.getId(),
                    tenant.getName(),
                    tenant.getStatus(),
                    agentsOnline,
                    (int) agentsTotal,
                    (int) contactsToday,
                    avgHandleTimeSeconds,
                    fcrPercentage,
                    agentLimitUtilizationPercent
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
                totalActiveContacts,
                systemAlerts,
                tenantMetrics,
                Instant.now()
        );

        log.info("[AdminMetrics] Globalne metryki: activeTenants={}, agentsOnline={}, activeContacts={}, alerts={}",
                totalActiveTenants, totalAgentsOnline, totalActiveContacts, systemAlerts.size());

        return response;
    }

    // =========================================================================
    // Metryki per tenant
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public TenantDetailMetrics getTenantMetrics(UUID tenantId) {
        log.debug("[AdminMetrics] Pobieranie metryk tenanta: id={}", tenantId);

        Tenant tenant = tenantService.findTenantEntity(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant nie istnieje: " + tenantId));

        Set<String> onlineAgentKeys = scanOnlineAgentKeys();
        int agentsOnline = countOnlineAgentsForTenant(tenantId, onlineAgentKeys);
        long agentsTotal = userService.countAgentsByTenantId(tenantId);
        long activeContacts = contactService.countActiveContactsByTenantId(tenantId);

        log.debug("[AdminMetrics] Metryki tenanta {}: agentsOnline={}, agentsTotal={}, activeContacts={}",
                tenantId, agentsOnline, agentsTotal, activeContacts);

        return new TenantDetailMetrics(
                tenant.getId(),
                tenant.getName(),
                tenant.getStatus(),
                agentsOnline,
                (int) agentsTotal,
                (int) activeContacts
        );
    }

    // =========================================================================
    // Inwalidacja cache
    // =========================================================================

    @Override
    @CacheEvict(cacheNames = RedisConfig.CacheNames.ADMIN_METRICS, key = "'global'")
    public void evictGlobalMetricsCache() {
        log.info("[AdminMetrics] Cache globalnych metryk inwalidowany (evict)");
    }

    // =========================================================================
    // Metryki wykorzystania platformy (usage)
    // =========================================================================

    @Override
    @Cacheable(cacheNames = RedisConfig.CacheNames.ADMIN_METRICS_USAGE, key = "'usage'")
    @Transactional(readOnly = true)
    public UsageMetrics getUsageMetrics() {
        log.debug("[AdminMetrics] Pobieranie metryk wykorzystania platformy (cache miss)");

        List<Tenant> allTenants = tenantService.getAllTenants();
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        long contactsToday = 0;
        long contactsYesterday = 0;
        long sumHandleTime = 0;
        long sumWaitTime = 0;
        long fcrCount = 0;
        long campaignsRunning = 0;
        long campaignsTotal = 0;

        // Pętla per tenant (N zapytań = N tenantów) – NIE N zapytań per agent/kampanię.
        for (Tenant tenant : allTenants) {
            Object[] todayAggregate = contactService.getDailyContactAggregate(tenant.getId(), today);
            contactsToday += toLong(todayAggregate[0]);
            sumHandleTime += toLong(todayAggregate[1]);
            sumWaitTime += toLong(todayAggregate[2]);
            fcrCount += toLong(todayAggregate[3]);

            Object[] yesterdayAggregate = contactService.getDailyContactAggregate(tenant.getId(), yesterday);
            contactsYesterday += toLong(yesterdayAggregate[0]);

            Object[] campaignCounts = campaignService.countTotalAndRunningByTenantId(tenant.getId());
            campaignsTotal += toLong(campaignCounts[0]);
            campaignsRunning += toLong(campaignCounts[1]);
        }

        int avgHandleTimeSeconds = contactsToday > 0
                ? (int) Math.round((double) sumHandleTime / contactsToday) : 0;
        int avgWaitTimeSeconds = contactsToday > 0
                ? (int) Math.round((double) sumWaitTime / contactsToday) : 0;
        double fcrPercentage = contactsToday > 0
                ? round2(((double) fcrCount / contactsToday) * 100) : 0.0;

        // Delta % vs wczoraj – null gdy brak danych z wczoraj (unikamy dzielenia przez 0)
        Integer contactsHandledDeltaPercent = null;
        if (contactsYesterday > 0) {
            contactsHandledDeltaPercent = (int) Math.round(
                    ((double) (contactsToday - contactsYesterday) / contactsYesterday) * 100);
        }

        UsageMetrics metrics = new UsageMetrics(
                (int) contactsToday,
                contactsHandledDeltaPercent,
                avgHandleTimeSeconds,
                avgWaitTimeSeconds,
                fcrPercentage,
                (int) campaignsRunning,
                (int) campaignsTotal,
                Instant.now()
        );

        log.info("[AdminMetrics] Metryki wykorzystania: contactsToday={}, deltaPercent={}, " +
                        "avgHandle={}s, avgWait={}s, fcr={}%, campaigns={}/{}",
                contactsToday, contactsHandledDeltaPercent, avgHandleTimeSeconds, avgWaitTimeSeconds,
                fcrPercentage, campaignsRunning, campaignsTotal);

        return metrics;
    }

    // =========================================================================
    // Macierz kontaktów per tenant i kanał
    // =========================================================================

    @Override
    @Cacheable(cacheNames = RedisConfig.CacheNames.ADMIN_METRICS_CHANNEL_BREAKDOWN, key = "'channel-breakdown-' + #days")
    @Transactional(readOnly = true)
    public ContactChannelMatrix getContactChannelMatrix(int days) {
        if (!ALLOWED_CHANNEL_MATRIX_DAYS.contains(days)) {
            throw new IllegalArgumentException(
                    "Parametr days musi być jedną z wartości 7, 30, 90. Podano: " + days);
        }

        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(days - 1L);

        log.debug("[AdminMetrics] Pobieranie macierzy kontaktów per tenant/kanał: days={}, "
                + "fromDate={}, toDate={} (cache miss)", days, fromDate, toDate);

        List<Tenant> allTenants = tenantService.getAllTenants();

        List<TenantChannelRow> tenantRows = new ArrayList<>();
        for (Tenant tenant : allTenants) {
            // Zero-fill: wszystkie kanały startują od 0, LinkedHashMap zachowuje kanoniczną
            // kolejność CONTACT_CHANNELS w serializowanym JSON (czytelniejsze dla FE).
            Map<String, Integer> countsByChannel = new LinkedHashMap<>();
            for (String channel : CONTACT_CHANNELS) {
                countsByChannel.put(channel, 0);
            }

            List<Object[]> rows = contactService.getContactCountsByChannelInRange(
                    tenant.getId(), fromDate, toDate);
            int total = 0;
            for (Object[] row : rows) {
                String channel = (String) row[0];
                int count = (int) toLong(row[1]);
                // Kanał spoza CONTACT_CHANNELS teoretycznie niemożliwy (CHECK constraint w DB),
                // ale defensywnie pomijamy nieznane wartości zamiast rzucać NPE na put() do mapy
                // z brakującym kluczem lub cichego rozjazdu total vs suma countsByChannel.
                if (countsByChannel.containsKey(channel)) {
                    countsByChannel.put(channel, count);
                    total += count;
                } else {
                    log.warn("[AdminMetrics] Nieoczekiwany kanał kontaktu poza CONTACT_CHANNELS: {} "
                            + "(tenant={})", channel, tenant.getId());
                }
            }

            tenantRows.add(new TenantChannelRow(tenant.getId(), tenant.getName(), countsByChannel, total));
        }

        ContactChannelMatrix matrix = new ContactChannelMatrix(
                CONTACT_CHANNELS, tenantRows, fromDate, toDate, Instant.now());

        log.info("[AdminMetrics] Macierz kontaktów per tenant/kanał: days={}, fromDate={}, toDate={}, "
                + "tenantów={}", days, fromDate, toDate, tenantRows.size());

        return matrix;
    }

    // =========================================================================
    // Metryki wzrostu platformy (growth)
    // =========================================================================

    @Override
    @Cacheable(cacheNames = RedisConfig.CacheNames.ADMIN_METRICS_GROWTH, key = "'growth-' + #weeks")
    @Transactional(readOnly = true)
    public GrowthMetrics getGrowthMetrics(int weeks) {
        if (weeks < 1 || weeks > 52) {
            throw new IllegalArgumentException(
                    "Parametr weeks musi być w zakresie 1-52. Podano: " + weeks);
        }

        log.debug("[AdminMetrics] Pobieranie metryk wzrostu platformy: weeks={} (cache miss)", weeks);

        List<WeeklyGrowthPoint> weeklyPoints = buildWeeklyGrowthPoints(weeks);
        List<TopPlugin> topPlugins = buildTopPlugins();

        GrowthMetrics metrics = new GrowthMetrics(weeklyPoints, topPlugins, Instant.now());

        log.info("[AdminMetrics] Metryki wzrostu: weeks={}, punkty={}, topPlugins={}",
                weeks, weeklyPoints.size(), topPlugins.size());

        return metrics;
    }

    // =========================================================================
    // Metryki zasobów systemowych (resources) – live, bez cache
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public SystemResourceMetrics getSystemResourceMetrics() {
        log.debug("[AdminMetrics] Pobieranie metryk zasobów systemowych (live, bez cache)");

        double cpuUsagePercent = round2(Math.max(0.0, findGaugeValue("system.cpu.usage")) * 100);

        // Heap used/max odczytywane bezpośrednio z java.lang.management (JDK), a nie przez
        // Micrometer JvmMemoryMetrics – ten ostatni rejestruje "jvm.memory.max" OSOBNO per pula
        // pamięci (tag "id" = "G1 Eden Space", "G1 Old Gen" itd., wszystkie z area=heap przy G1GC).
        // Filtrowanie tylko po tag("area","heap") bez "id" trafia losowo na jedną z wielu gauge'y –
        // część pul G1 (Eden/Survivor) legalnie zwraca -1 jako "brak zdefiniowanego maksimum".
        // getHeapMemoryUsage() zwraca CAŁY heap jako jedną spójną wartość, bez tej dwuznaczności.
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        long heapUsedBytes = Math.max(0L, memoryMXBean.getHeapMemoryUsage().getUsed());
        long heapMaxRaw = memoryMXBean.getHeapMemoryUsage().getMax();
        long heapMaxBytes = heapMaxRaw >= 0 ? heapMaxRaw : 0L;

        int threadsLive = (int) findGaugeValue("jvm.threads.live");

        // Uptime odczytywany przez RuntimeMXBean (JDK) – autorytatywne źródło, niezależne od
        // dokładnej nazwy/tagowania metryki Micrometer w danej wersji Spring Boot/Micrometer.
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        long uptimeSeconds = runtimeMXBean.getUptime() / 1000L;

        int dbPoolActive = (int) findGaugeValue("hikaricp.connections.active");
        int dbPoolMax = (int) findGaugeValue("hikaricp.connections.max");

        String redisStatus = checkRedisStatus();
        // redisAgentSessions = surowa liczba kluczy "session:agent:*" w Redis, CELOWO bez filtra
        // tenanta/statusu – to metryka Ops "ile kluczy sesji faktycznie zajmuje Redis" (capacity
        // planning/monitoring pamięci Redis), a nie biznesowa metryka "agentsOnline" (ta ostatnia
        // jest już dostępna jako suma TenantMetrics.agentsOnline w GET /api/admin/metrics – global).
        // Sesje agentów OFFLINE żyją w Redis do ~8h TTL (patrz countOnlineAgentsForTenant Javadoc),
        // więc ta liczba może być WYŻSZA niż totalAgentsOnline – to zamierzone i udokumentowane
        // w Schema#description SystemResourceMetrics.redisAgentSessions, nie błąd.
        // Filtrowanie po statusie wymagałoby dodatkowego GET per klucz (ten endpoint jest live,
        // bez cache – patrz klasowy Javadoc) – nieuzasadniony koszt dla metryki czysto
        // infrastrukturalnej (rozmiar Redis), nie operacyjnej (obsada agentów).
        int redisAgentSessions = scanOnlineAgentKeys().size();
        String rabbitStatus = checkRabbitStatus();
        List<QueueDepth> queueDepths = buildQueueDepths();

        SystemResourceMetrics metrics = new SystemResourceMetrics(
                cpuUsagePercent, heapUsedBytes, heapMaxBytes, threadsLive, uptimeSeconds,
                dbPoolActive, dbPoolMax, redisStatus, redisAgentSessions, rabbitStatus,
                queueDepths, Instant.now()
        );

        log.info("[AdminMetrics] Metryki zasobów: cpu={}%, heapUsed={}MB, heapMax={}MB, " +
                        "dbPool={}/{}, redis={}, rabbit={}",
                cpuUsagePercent, heapUsedBytes / 1_000_000, heapMaxBytes / 1_000_000,
                dbPoolActive, dbPoolMax, redisStatus, rabbitStatus);

        return metrics;
    }

    // =========================================================================
    // Eksport CSV metryk tenantów
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public byte[] exportTenantMetricsCsv() {
        log.info("[AdminMetrics] Eksport CSV metryk tenantów");

        // Uwaga: wywołanie własnej metody @Cacheable przez self-invocation pomija proxy Springa
        // (cache nie zostanie użyty) – akceptowalne, eksport nie jest ścieżką hot-path.
        AdminMetricsResponse metrics = getGlobalMetrics();

        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", TENANT_METRICS_CSV_HEADERS)).append("\n");

        for (TenantMetrics tenant : metrics.tenants()) {
            csv.append(escapeCsv(tenant.name())).append(",");
            csv.append(tenant.status()).append(",");
            csv.append(tenant.agentsOnline()).append(",");
            csv.append(tenant.agentsTotal()).append(",");
            csv.append(tenant.contactsToday()).append(",");
            csv.append(tenant.avgHandleTimeSeconds()).append(",");
            csv.append(round2(tenant.fcrPercentage())).append(",");
            csv.append(tenant.agentLimitUtilizationPercent()).append("\n");
        }

        log.debug("[AdminMetrics] CSV eksport metryk tenantów: {} wierszy", metrics.tenants().size());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Skanuje klucze {@code session:agent:*} z Redis używając iteracyjnego SCAN.
     *
     * <p>Używamy {@code SCAN} zamiast {@code KEYS} – {@code KEYS *} blokuje Redis event loop
     * na czas pełnego skanowania (O(N) gdzie N = liczba kluczy). W środowisku produkcyjnym
     * z tysiącami kluczy może to spowodować widoczne opóźnienia dla innych klientów Redis.
     * {@code SCAN} przebiega iteracyjnie po {@code count=200} kluczy na iterację – nie blokuje.
     *
     * @return zbiór kluczy Redis reprezentujących agentów online
     */
    private Set<String> scanOnlineAgentKeys() {
        try {
            Set<String> keys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(AGENT_SESSION_KEY_PATTERN)
                    .count(200)
                    .build();

            redisTemplate.execute(connection -> {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next()));
                    }
                } catch (Exception e) {
                    log.warn("[AdminMetrics] Błąd podczas SCAN kluczy sesji agentów: {}", e.getMessage());
                }
                return null;
            }, true);

            return keys;
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
     * z Redis i zweryfikować czy dany agent należy do podanego tenanta ORAZ czy jego
     * status jest jednym z {@link #ONLINE_AGENT_STATUSES}. Weryfikacja tenanta jest konieczna –
     * klucze sesji zawierają UUID agenta, a nie tenantId. Weryfikacja statusu jest konieczna,
     * bo sesja NIE jest usuwana przy wylogowaniu agenta – zamiast tego jej wartość jest
     * aktualizowana in-place na status {@code OFFLINE} i żyje do wygaśnięcia TTL (~8h,
     * patrz {@code UserServiceImpl.REDIS_AGENT_STATUS_TTL}). Bez tego filtra wylogowany
     * agent byłby liczony jako online przez cały pozostały czas TTL.
     *
     * <p>W celu minimalizacji zapytań do bazy danych, weryfikacja przynależności agenta
     * do tenanta i jego status odbywają się przez odczyt pól {@code tenantId}/{@code status}
     * bezpośrednio z obiektu zapisanego w Redis (bez round-tripu do DB per agent).
     * Jeśli wartość Redis nie zawiera tenantId lub status (niekompletne dane), agent
     * jest pomijany.
     *
     * <p><strong>Uwaga:</strong> jedyny writer klucza {@code session:agent:{userId}} to
     * {@code UserServiceImpl}, który ZAWSZE zapisuje {@code Map<String, String>} (zweryfikowano
     * w całym repo – brak innego producenta tego klucza). Starszy fallback dla formatu
     * "zwykły String zawierający tenantId" został USUNIĘTY – to był martwy kod (nigdy
     * nie istniał writer tego formatu) i dodatkowo nie pozwalał sprawdzić statusu, więc
     * gdyby pozostał, defeatowałby cel tej poprawki.
     *
     * @param tenantId      UUID tenanta
     * @param onlineAgentKeys zbiór kluczy sesji agentów z Redis
     * @return liczba agentów online dla danego tenanta
     */
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

                // Wartość sesji agenta to Map z polami tenantId/status (patrz UserServiceImpl).
                if (value instanceof java.util.Map<?, ?> sessionData) {
                    Object storedTenantId = sessionData.get("tenantId");
                    Object storedStatus = sessionData.get("status");
                    if (storedTenantId != null && tenantIdStr.equals(storedTenantId.toString())
                            && storedStatus != null
                            && ONLINE_AGENT_STATUSES.contains(storedStatus.toString())) {
                        count++;
                    }
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

    // =========================================================================
    // Metody pomocnicze – growth (trendy tygodniowe, top pluginy)
    // =========================================================================

    /**
     * Buduje listę {@code weeks} punktów tygodniowego wzrostu (nowi tenanci/użytkownicy),
     * chronologicznie od najstarszego, zawsze dokładnie {@code weeks} punktów – tygodnie
     * bez żadnych nowych rekordów są wypełniane zerem (przewidywalny kontrakt dla wykresów FE).
     *
     * <p>Tydzień = ISO-8601 (poniedziałek jako początek), liczony w strefie UTC – zgodnie
     * z {@code date_trunc('week', ... AT TIME ZONE 'UTC')} w zapytaniach repozytoriów.
     */
    private List<WeeklyGrowthPoint> buildWeeklyGrowthPoints(int weeks) {
        LocalDate currentWeekStart = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate since = currentWeekStart.minusWeeks(weeks - 1L);
        Instant sinceInstant = since.atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, Long> newTenantsByWeek = toWeekCountMap(tenantService.countNewTenantsByWeekSince(sinceInstant));
        Map<LocalDate, Long> newUsersByWeek = toWeekCountMap(userService.countNewUsersByWeekSince(sinceInstant));

        List<WeeklyGrowthPoint> points = new ArrayList<>(weeks);
        for (int i = 0; i < weeks; i++) {
            LocalDate weekStart = since.plusWeeks(i);
            int newTenants = newTenantsByWeek.getOrDefault(weekStart, 0L).intValue();
            int newUsers = newUsersByWeek.getOrDefault(weekStart, 0L).intValue();
            points.add(new WeeklyGrowthPoint(weekStart, newTenants, newUsers));
        }
        return points;
    }

    /** Konwertuje wynik zapytania {@code [week_start, count]} na mapę week_start → count. */
    private Map<LocalDate, Long> toWeekCountMap(List<Object[]> rows) {
        Map<LocalDate, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(toLocalDate(row[0]), toLong(row[1]));
        }
        return map;
    }

    /**
     * Buduje ranking top 5 pluginów wg liczby aktywnych instalacji (cross-tenant).
     *
     * <p>Grupuje po nazwie wyświetlanej pluginu (nie po pluginVersionId) – jeśli tenant
     * upgrade'ował wersję, stara instalacja ma {@code enabled=false} i nie jest liczona
     * podwójnie (patrz Javadoc {@link TopPlugin}).
     */
    private List<TopPlugin> buildTopPlugins() {
        List<TenantPluginInstallation> installations = pluginCatalogQueryService.findAllEnabledAcrossAllTenants();

        Map<UUID, Long> countByVersionId = installations.stream()
                .collect(Collectors.groupingBy(TenantPluginInstallation::getPluginVersionId, Collectors.counting()));

        Map<String, Long> countByPluginName = new HashMap<>();
        for (Map.Entry<UUID, Long> entry : countByVersionId.entrySet()) {
            pluginCatalogQueryService.findVersionById(entry.getKey()).ifPresent(version ->
                    countByPluginName.merge(version.getPlugin().getDisplayName(), entry.getValue(), Long::sum));
        }

        return countByPluginName.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(entry -> new TopPlugin(entry.getKey(), entry.getValue()))
                .toList();
    }

    // =========================================================================
    // Metody pomocnicze – resources (Micrometer, Redis, RabbitMQ)
    // =========================================================================

    /** Ping Redis – graceful degradation: błąd/timeout → "DOWN" zamiast wyjątku. */
    private String checkRedisStatus() {
        try {
            Boolean pong = redisTemplate.execute((RedisCallback<Boolean>) connection -> {
                String result = connection.ping();
                return result != null && result.equalsIgnoreCase("PONG");
            });
            return Boolean.TRUE.equals(pong) ? "UP" : "DOWN";
        } catch (Exception e) {
            log.warn("[AdminMetrics] Redis ping nieudany: {}", e.getMessage());
            return "DOWN";
        }
    }

    /** Sprawdzenie połączenia RabbitMQ – graceful degradation: błąd → "DOWN" zamiast wyjątku. */
    private String checkRabbitStatus() {
        try {
            rabbitTemplate.execute(channel -> null);
            return "UP";
        } catch (Exception e) {
            log.warn("[AdminMetrics] Sprawdzenie połączenia RabbitMQ nieudane: {}", e.getMessage());
            return "DOWN";
        }
    }

    /** Głębokość (liczba wiadomości) kolejek monitorowanych operacyjnie ({@link #MONITORED_QUEUES}). */
    private List<QueueDepth> buildQueueDepths() {
        List<QueueDepth> depths = new ArrayList<>();
        for (String queueName : MONITORED_QUEUES) {
            int messageCount = 0;
            try {
                QueueInformation queueInfo = rabbitAdmin.getQueueInfo(queueName);
                if (queueInfo != null) {
                    messageCount = queueInfo.getMessageCount();
                } else {
                    log.warn("[AdminMetrics] Kolejka RabbitMQ nie istnieje lub niedostępna: {}", queueName);
                }
            } catch (Exception e) {
                log.warn("[AdminMetrics] Błąd odczytu głębokości kolejki {}: {}", queueName, e.getMessage());
            }
            depths.add(new QueueDepth(queueName, messageCount));
        }
        return depths;
    }

    /** Odczytuje wartość gauge'a Micrometer po nazwie (bez tagów). 0.0 gdy metryka niedostępna/błąd. */
    private double findGaugeValue(String meterName) {
        try {
            var gauge = meterRegistry.find(meterName).gauge();
            if (gauge == null) {
                log.debug("[AdminMetrics] Metryka Micrometer niedostępna: {}", meterName);
                return 0.0;
            }
            double value = gauge.value();
            // Niektóre gauge'e (np. wygasły WeakReference stanu) zwracają NaN zamiast rzucać –
            // traktujemy identycznie jak brak metryki (graceful degradation).
            return Double.isNaN(value) ? 0.0 : value;
        } catch (Exception e) {
            log.warn("[AdminMetrics] Błąd odczytu metryki Micrometer {}: {}", meterName, e.getMessage());
            return 0.0;
        }
    }

    /** Odczytuje wartość gauge'a Micrometer po nazwie i tagu. 0.0 gdy metryka niedostępna/błąd. */
    private double findGaugeValue(String meterName, String tagKey, String tagValue) {
        try {
            var gauge = meterRegistry.find(meterName).tag(tagKey, tagValue).gauge();
            if (gauge == null) {
                log.debug("[AdminMetrics] Metryka Micrometer niedostępna: {} [{}={}]", meterName, tagKey, tagValue);
                return 0.0;
            }
            double value = gauge.value();
            return Double.isNaN(value) ? 0.0 : value;
        } catch (Exception e) {
            log.warn("[AdminMetrics] Błąd odczytu metryki Micrometer {} [{}={}]: {}",
                    meterName, tagKey, tagValue, e.getMessage());
            return 0.0;
        }
    }

    // =========================================================================
    // Metody pomocnicze – ogólne (konwersje, zaokrąglanie, CSV)
    // =========================================================================

    /** Konwertuje wynik natywnego zapytania SQL (Number) na long. 0 gdy null/niepoprawny typ. */
    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** Konwertuje kolumnę {@code week_start} natywnego zapytania (java.sql.Date/Timestamp) na LocalDate. */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        throw new IllegalStateException("Nieoczekiwany typ dla week_start: "
                + (value != null ? value.getClass() : "null"));
    }

    /** Zaokrągla do 2 miejsc po przecinku (BigDecimal dla precyzji) – wzorzec z ReportsServiceImpl. */
    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Ucieczka wartości dla CSV (obsługa przecinków i cudzysłowów) – wzorzec z ReportsServiceImpl. */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
