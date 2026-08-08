package com.contactcenter.domain.tenant;

import com.contactcenter.api.admin.dto.AdminMetricsResponse;
import com.contactcenter.api.admin.dto.ContactChannelMatrix;
import com.contactcenter.api.admin.dto.GrowthMetrics;
import com.contactcenter.api.admin.dto.TenantChannelRow;
import com.contactcenter.api.admin.dto.TenantDetailMetrics;
import com.contactcenter.api.admin.dto.TenantMetrics;
import com.contactcenter.api.admin.dto.TopPlugin;
import com.contactcenter.api.admin.dto.UsageMetrics;
import com.contactcenter.api.admin.dto.WeeklyGrowthPoint;
import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.plugin.Plugin;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.contactcenter.domain.user.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link AdminMetricsService}.
 *
 * <p>Weryfikuje logikę biznesową bez dostępu do bazy danych i prawdziwego Redis
 * (mock repository i RedisTemplate).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminMetricsService – metryki RT platformy")
class AdminMetricsServiceImplTest {

    private static final UUID TENANT_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TENANT_ID_3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private TenantService tenantService;

    @Mock
    private UserService userService;

    @Mock
    private ContactService contactService;

    @Mock
    private CampaignService campaignService;

    @Mock
    private PluginCatalogQueryService pluginCatalogQueryService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private AmqpAdmin rabbitAdmin;

    /**
     * Real {@link SimpleMeterRegistry} zamiast mocka – fluent API Micrometer
     * ({@code MeterRegistry.find(...).tag(...).gauge()}) jest kruche do mockowania,
     * a SimpleMeterRegistry jest lekki i deterministyczny w testach.
     */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private AdminMetricsServiceImpl adminMetricsService;

    private Tenant activeTenant1;
    private Tenant activeTenant2;
    private Tenant suspendedTenant;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = buildDefaultConfig();

        activeTenant1 = Tenant.builder()
                .id(TENANT_ID_1)
                .name("Acme Corporation")
                .status(TenantStatus.ACTIVE)
                .config(config)
                .createdAt(Instant.now())
                .build();

        activeTenant2 = Tenant.builder()
                .id(TENANT_ID_2)
                .name("Beta Corp")
                .status(TenantStatus.ACTIVE)
                .config(config)
                .createdAt(Instant.now())
                .build();

        suspendedTenant = Tenant.builder()
                .id(TENANT_ID_3)
                .name("Gamma Ltd")
                .status(TenantStatus.SUSPENDED)
                .config(config)
                .createdAt(Instant.now())
                .build();

        // Domyślne stubowanie redisTemplate
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Domyślnie: SCAN zwraca puste wyniki (execute nic nie robi – callback nie dodaje kluczy)
        when(redisTemplate.execute(any(RedisCallback.class), anyBoolean())).thenReturn(null);

        // Domyślne stubowanie agregacji dziennej kontaktów – 0 wszędzie (unika NPE w pętli tenantów)
        when(contactService.getDailyContactAggregate(any(), any()))
                .thenReturn(new Object[]{0L, 0L, 0L, 0L});
        when(contactService.countActiveContactsByTenantId(any())).thenReturn(0L);
        // Domyślnie: brak kontaktów per kanał (lista pusta – zero-fill przez AdminMetricsService)
        when(contactService.getContactCountsByChannelInRange(any(), any(), any())).thenReturn(List.of());

        adminMetricsService = new AdminMetricsServiceImpl(
                tenantService, userService, contactService, campaignService,
                pluginCatalogQueryService, redisTemplate, rabbitTemplate, rabbitAdmin, meterRegistry);
    }

    // =========================================================================
    // getGlobalMetrics()
    // =========================================================================

    @Nested
    @DisplayName("getGlobalMetrics()")
    class GetGlobalMetrics {

        @Test
        @DisplayName("powinien zwrócić metryki dla wszystkich tenantów (bez agentów online)")
        void shouldReturnMetricsForAllTenantsWithNoAgentsOnline() {
            // given
            when(tenantService.getAllTenants())
                    .thenReturn(List.of(activeTenant1, activeTenant2));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(10L);
            when(userService.countAgentsByTenantId(TENANT_ID_2)).thenReturn(5L);
            // SCAN zwraca puste wyniki (domyślne mock z setUp)

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response).isNotNull();
            assertThat(response.totalActiveTenants()).isEqualTo(2);
            assertThat(response.totalAgentsOnline()).isEqualTo(0);
            assertThat(response.totalActiveContacts()).isEqualTo(0);
            assertThat(response.systemAlerts()).isEmpty();
            assertThat(response.tenants()).hasSize(2);
            assertThat(response.generatedAt()).isNotNull();
        }

        @Test
        @DisplayName("powinien poprawnie liczyć aktywnych tenantów (tylko status=ACTIVE)")
        void shouldCountOnlyActiveTenants() {
            // given – 2 aktywne + 1 SUSPENDED
            when(tenantService.getAllTenants())
                    .thenReturn(List.of(activeTenant1, activeTenant2, suspendedTenant));
            when(userService.countAgentsByTenantId(any())).thenReturn(0L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response.totalActiveTenants()).isEqualTo(2);
        }

        @Test
        @DisplayName("powinien generować alert dla tenanta SUSPENDED")
        void shouldGenerateAlertForSuspendedTenant() {
            // given
            when(tenantService.getAllTenants())
                    .thenReturn(List.of(activeTenant1, suspendedTenant));
            when(userService.countAgentsByTenantId(any())).thenReturn(5L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response.systemAlerts()).hasSize(1);
            assertThat(response.systemAlerts().get(0)).contains("SUSPENDED");
            assertThat(response.systemAlerts().get(0)).contains("Gamma Ltd");
        }

        @Test
        @DisplayName("powinien zwrócić pustą listę alertów gdy wszystkie tenanty są ACTIVE")
        void shouldReturnNoAlertsWhenAllTenantsActive() {
            // given
            when(tenantService.getAllTenants())
                    .thenReturn(List.of(activeTenant1, activeTenant2));
            when(userService.countAgentsByTenantId(any())).thenReturn(5L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response.systemAlerts()).isEmpty();
        }

        @Test
        @DisplayName("agent wylogowany (status=OFFLINE w sesji Redis) NIE powinien być liczony jako online")
        void shouldNotCountLoggedOutAgentAsOnline() {
            // given – klucz sesji istnieje z poprawnym tenantId, ale status=OFFLINE (agent się wylogował,
            // klucz Redis NIE jest usuwany przy wylogowaniu – żyje do wygaśnięcia TTL ~8h)
            String agentKey = "session:agent:" + UUID.randomUUID();
            mockScanResult(agentKey);
            mockSessionValue(agentKey, TENANT_ID_1, "OFFLINE");

            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(1L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then – agent NIE jest liczony jako online mimo istniejącego, aktywnego (TTL) klucza sesji
            assertThat(response.tenants().get(0).agentsOnline()).isZero();
            assertThat(response.totalAgentsOnline()).isZero();
        }

        @ParameterizedTest(name = "status={0}")
        @ValueSource(strings = {"AVAILABLE", "BUSY", "AFTER_CONTACT"})
        @DisplayName("agent ze statusem AVAILABLE/BUSY/AFTER_CONTACT powinien być liczony jako online")
        void shouldCountAgentAsOnlineForEachOnlineStatus(String status) {
            // given
            String agentKey = "session:agent:" + UUID.randomUUID();
            mockScanResult(agentKey);
            mockSessionValue(agentKey, TENANT_ID_1, status);

            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(1L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response.tenants().get(0).agentsOnline()).isEqualTo(1);
        }

        @Test
        @DisplayName("agent ze statusem BREAK NIE powinien być liczony jako online (zalogowany, ale niedostępny)")
        void shouldNotCountBreakAgentAsOnline() {
            // given
            String agentKey = "session:agent:" + UUID.randomUUID();
            mockScanResult(agentKey);
            mockSessionValue(agentKey, TENANT_ID_1, "BREAK");

            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(1L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response.tenants().get(0).agentsOnline()).isZero();
        }

        @Test
        @DisplayName("agent innego tenanta w tej samej puli kluczy Redis nie powinien być liczony")
        void shouldNotCountAgentFromDifferentTenant() {
            // given – sesja agenta należy do TENANT_ID_2, ale liczymy metryki dla TENANT_ID_1
            String agentKey = "session:agent:" + UUID.randomUUID();
            mockScanResult(agentKey);
            mockSessionValue(agentKey, TENANT_ID_2, "AVAILABLE");

            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(1L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response.tenants().get(0).agentsOnline()).isZero();
        }

        @Test
        @DisplayName("powinien obsłużyć błąd Redis gracefully (0 agentów online)")
        void shouldHandleRedisErrorGracefully() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(5L);
            when(redisTemplate.execute(any(RedisCallback.class), anyBoolean()))
                    .thenThrow(new RuntimeException("Redis timeout"));

            // when – nie powinno rzucać wyjątku
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then – graceful degradation: 0 agentów online
            assertThat(response.totalAgentsOnline()).isEqualTo(0);
            assertThat(response.tenants()).hasSize(1);
            assertThat(response.tenants().get(0).agentsTotal()).isEqualTo(5);
        }

        @Test
        @DisplayName("powinien zwrócić pustą listę tenantów i 0 aktywnych gdy baza jest pusta")
        void shouldReturnEmptyMetricsWhenNoTenants() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of());

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response.totalActiveTenants()).isEqualTo(0);
            assertThat(response.totalAgentsOnline()).isEqualTo(0);
            assertThat(response.totalActiveContacts()).isEqualTo(0);
            assertThat(response.systemAlerts()).isEmpty();
            assertThat(response.tenants()).isEmpty();
        }

        @Test
        @DisplayName("metryki tenanta w odpowiedzi powinny zawierać agentsTotal z bazy danych")
        void tenantMetricsShouldContainAgentsTotalFromDb() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(42L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            TenantMetrics tenant = response.tenants().get(0);
            assertThat(tenant.id()).isEqualTo(TENANT_ID_1);
            assertThat(tenant.name()).isEqualTo("Acme Corporation");
            assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(tenant.agentsTotal()).isEqualTo(42);
            assertThat(tenant.agentsOnline()).isEqualTo(0);
        }

        @Test
        @DisplayName("powinien zsumować activeContacts wszystkich tenantów w totalActiveContacts")
        void shouldSumActiveContactsAcrossTenants() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1, activeTenant2));
            when(userService.countAgentsByTenantId(any())).thenReturn(0L);
            when(contactService.countActiveContactsByTenantId(TENANT_ID_1)).thenReturn(3L);
            when(contactService.countActiveContactsByTenantId(TENANT_ID_2)).thenReturn(4L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response.totalActiveContacts()).isEqualTo(7);
        }

        @Test
        @DisplayName("powinien wyliczyć contactsToday, avgHandleTimeSeconds i fcrPercentage per tenant")
        void shouldComputeDailyAggregatePerTenant() {
            // given – 10 kontaktów, suma czasu obsługi 1000s (avg 100s), 8 z FCR (80%)
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(0L);
            when(contactService.getDailyContactAggregate(eq(TENANT_ID_1), any()))
                    .thenReturn(new Object[]{10L, 1000L, 200L, 8L});

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            TenantMetrics tenant = response.tenants().get(0);
            assertThat(tenant.contactsToday()).isEqualTo(10);
            assertThat(tenant.avgHandleTimeSeconds()).isEqualTo(100);
            assertThat(tenant.fcrPercentage()).isEqualTo(80.0);
        }

        @Test
        @DisplayName("powinien wyliczyć agentLimitUtilizationPercent (agentsTotal/maxAgents*100)")
        void shouldComputeAgentLimitUtilizationPercent() {
            // given – maxAgents=100 (domyślny config), agentsTotal=68 -> 68%
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(68L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            TenantMetrics tenant = response.tenants().get(0);
            assertThat(tenant.agentLimitUtilizationPercent()).isEqualTo(68);
        }
    }

    // =========================================================================
    // getTenantMetrics()
    // =========================================================================

    @Nested
    @DisplayName("getTenantMetrics()")
    class GetTenantMetrics {

        @Test
        @DisplayName("powinien zwrócić szczegółowe metryki istniejącego tenanta")
        void shouldReturnDetailMetricsForExistingTenant() {
            // given
            when(tenantService.findTenantEntity(TENANT_ID_1)).thenReturn(Optional.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(15L);
            when(contactService.countActiveContactsByTenantId(TENANT_ID_1)).thenReturn(6L);

            // when
            TenantDetailMetrics metrics = adminMetricsService.getTenantMetrics(TENANT_ID_1);

            // then
            assertThat(metrics.id()).isEqualTo(TENANT_ID_1);
            assertThat(metrics.name()).isEqualTo("Acme Corporation");
            assertThat(metrics.status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(metrics.agentsTotal()).isEqualTo(15);
            assertThat(metrics.agentsOnline()).isEqualTo(0);
            assertThat(metrics.activeContacts()).isEqualTo(6);
        }

        @Test
        @DisplayName("powinien rzucić EntityNotFoundException gdy tenant nie istnieje")
        void shouldThrowEntityNotFoundWhenTenantDoesNotExist() {
            // given
            UUID nonExistentId = UUID.randomUUID();
            when(tenantService.findTenantEntity(nonExistentId)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> adminMetricsService.getTenantMetrics(nonExistentId))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(nonExistentId.toString());
        }

        @Test
        @DisplayName("activeContacts pochodzi realnie z ContactService (nie mock 0)")
        void activeContactsShouldReflectContactServiceValue() {
            // given
            when(tenantService.findTenantEntity(TENANT_ID_1)).thenReturn(Optional.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(5L);
            when(contactService.countActiveContactsByTenantId(TENANT_ID_1)).thenReturn(9L);

            // when
            TenantDetailMetrics metrics = adminMetricsService.getTenantMetrics(TENANT_ID_1);

            // then
            assertThat(metrics.activeContacts()).isEqualTo(9);
        }

        @Test
        @DisplayName("powinien zwracać metryki nawet dla tenanta SUSPENDED")
        void shouldReturnMetricsForSuspendedTenant() {
            // given
            when(tenantService.findTenantEntity(TENANT_ID_3)).thenReturn(Optional.of(suspendedTenant));
            when(userService.countAgentsByTenantId(TENANT_ID_3)).thenReturn(3L);

            // when
            TenantDetailMetrics metrics = adminMetricsService.getTenantMetrics(TENANT_ID_3);

            // then
            assertThat(metrics.status()).isEqualTo(TenantStatus.SUSPENDED);
            assertThat(metrics.agentsTotal()).isEqualTo(3);
        }
    }

    // =========================================================================
    // getUsageMetrics()
    // =========================================================================

    @Nested
    @DisplayName("getUsageMetrics()")
    class GetUsageMetrics {

        @Test
        @DisplayName("powinien zagregować kontakty/kampanie ważone liczbą kontaktów wszystkich tenantów")
        void shouldAggregateWeightedAcrossTenants() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1, activeTenant2));

            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);

            // Tenant 1: dzisiaj 10 kontaktów (suma handle=1000, suma wait=500, fcr=8), wczoraj 5
            when(contactService.getDailyContactAggregate(eq(TENANT_ID_1), eq(today)))
                    .thenReturn(new Object[]{10L, 1000L, 500L, 8L});
            when(contactService.getDailyContactAggregate(eq(TENANT_ID_1), eq(yesterday)))
                    .thenReturn(new Object[]{5L, 0L, 0L, 0L});

            // Tenant 2: dzisiaj 20 kontaktów (suma handle=2000, suma wait=1000, fcr=16), wczoraj 5
            when(contactService.getDailyContactAggregate(eq(TENANT_ID_2), eq(today)))
                    .thenReturn(new Object[]{20L, 2000L, 1000L, 16L});
            when(contactService.getDailyContactAggregate(eq(TENANT_ID_2), eq(yesterday)))
                    .thenReturn(new Object[]{5L, 0L, 0L, 0L});

            when(campaignService.countTotalAndRunningByTenantId(TENANT_ID_1))
                    .thenReturn(new Object[]{5L, 2L});
            when(campaignService.countTotalAndRunningByTenantId(TENANT_ID_2))
                    .thenReturn(new Object[]{3L, 1L});

            // when
            UsageMetrics metrics = adminMetricsService.getUsageMetrics();

            // then – contactsToday = 30, avgHandle = 3000/30=100, avgWait=1500/30=50, fcr=24/30*100=80%
            assertThat(metrics.contactsHandledToday()).isEqualTo(30);
            assertThat(metrics.avgHandleTimeSeconds()).isEqualTo(100);
            assertThat(metrics.avgWaitTimeSeconds()).isEqualTo(50);
            assertThat(metrics.fcrPercentage()).isEqualTo(80.0);
            // contactsYesterday = 10, delta = (30-10)/10*100 = 200%
            assertThat(metrics.contactsHandledDeltaPercent()).isEqualTo(200);
            assertThat(metrics.campaignsTotal()).isEqualTo(8);
            assertThat(metrics.campaignsRunning()).isEqualTo(3);
            assertThat(metrics.generatedAt()).isNotNull();
        }

        @Test
        @DisplayName("deltaPercent powinien być null gdy brak kontaktów wczoraj (unikamy dzielenia przez 0)")
        void deltaPercentShouldBeNullWhenNoDataYesterday() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));

            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);

            when(contactService.getDailyContactAggregate(eq(TENANT_ID_1), eq(today)))
                    .thenReturn(new Object[]{5L, 500L, 100L, 4L});
            when(contactService.getDailyContactAggregate(eq(TENANT_ID_1), eq(yesterday)))
                    .thenReturn(new Object[]{0L, 0L, 0L, 0L});
            when(campaignService.countTotalAndRunningByTenantId(TENANT_ID_1))
                    .thenReturn(new Object[]{0L, 0L});

            // when
            UsageMetrics metrics = adminMetricsService.getUsageMetrics();

            // then
            assertThat(metrics.contactsHandledDeltaPercent()).isNull();
        }

        @Test
        @DisplayName("powinien zwrócić zera gdy brak tenantów")
        void shouldReturnZeroesWhenNoTenants() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of());

            // when
            UsageMetrics metrics = adminMetricsService.getUsageMetrics();

            // then
            assertThat(metrics.contactsHandledToday()).isZero();
            assertThat(metrics.avgHandleTimeSeconds()).isZero();
            assertThat(metrics.avgWaitTimeSeconds()).isZero();
            assertThat(metrics.fcrPercentage()).isZero();
            assertThat(metrics.contactsHandledDeltaPercent()).isNull();
            assertThat(metrics.campaignsRunning()).isZero();
            assertThat(metrics.campaignsTotal()).isZero();
        }
    }

    // =========================================================================
    // getContactChannelMatrix(int days)
    // =========================================================================

    @Nested
    @DisplayName("getContactChannelMatrix(int days)")
    class GetContactChannelMatrix {

        @Test
        @DisplayName("powinien zwrócić kanoniczną listę 5 kanałów w stałej kolejności oraz fromDate/toDate")
        void shouldReturnCanonicalChannelList() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));

            // when
            ContactChannelMatrix matrix = adminMetricsService.getContactChannelMatrix(7);

            // then
            assertThat(matrix.channels()).containsExactly(
                    "PHONE", "EMAIL", "SOCIAL_FACEBOOK", "SOCIAL_INSTAGRAM", "SOCIAL_WHATSAPP");
            assertThat(matrix.generatedAt()).isNotNull();
            assertThat(matrix.toDate()).isEqualTo(LocalDate.now());
            assertThat(matrix.fromDate()).isEqualTo(LocalDate.now().minusDays(6));
        }

        @Test
        @DisplayName("powinien rzucić IllegalArgumentException gdy days poza dozwolonym zbiorem {7, 30, 90}")
        void shouldThrowWhenDaysNotAllowed() {
            assertThatThrownBy(() -> adminMetricsService.getContactChannelMatrix(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adminMetricsService.getContactChannelMatrix(1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adminMetricsService.getContactChannelMatrix(5))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adminMetricsService.getContactChannelMatrix(14))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adminMetricsService.getContactChannelMatrix(-7))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("dla days=7 zakres powinien wynosić [dziś-6, dziś]: kontakt sprzed 6 dni policzony, "
                + "sprzed 8 dni – nie")
        void shouldRespectRangeBoundaryForDays() {
            // given – symulacja zachowania repozytorium: kontakt A z dnia -6 (W zakresie dla days=7),
            // kontakt B z dnia -8 (POZA zakresem dla days=7). Odpowiedź zależy od fromDate/toDate
            // faktycznie przekazanych przez AdminMetricsService do ContactService.
            LocalDate contactADate = LocalDate.now().minusDays(6);
            LocalDate contactBDate = LocalDate.now().minusDays(8);

            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(contactService.getContactCountsByChannelInRange(eq(TENANT_ID_1), any(), any()))
                    .thenAnswer(invocation -> {
                        LocalDate fromDate = invocation.getArgument(1);
                        LocalDate toDate = invocation.getArgument(2);
                        List<Object[]> rows = new java.util.ArrayList<>();
                        if (!contactADate.isBefore(fromDate) && !contactADate.isAfter(toDate)) {
                            rows.add(new Object[]{"PHONE", 1L});
                        }
                        if (!contactBDate.isBefore(fromDate) && !contactBDate.isAfter(toDate)) {
                            rows.add(new Object[]{"EMAIL", 1L});
                        }
                        return rows;
                    });

            // when
            ContactChannelMatrix matrix = adminMetricsService.getContactChannelMatrix(7);

            // then – fromDate=dziś-6, toDate=dziś: kontakt A (dziś-6) W zakresie, kontakt B (dziś-8) POZA
            assertThat(matrix.fromDate()).isEqualTo(LocalDate.now().minusDays(6));
            assertThat(matrix.toDate()).isEqualTo(LocalDate.now());
            TenantChannelRow row = matrix.tenants().get(0);
            assertThat(row.countsByChannel().get("PHONE")).isEqualTo(1);
            assertThat(row.countsByChannel().get("EMAIL")).isEqualTo(0);
            assertThat(row.total()).isEqualTo(1);
        }

        @Test
        @DisplayName("kanał bez żadnego kontaktu powinien mieć wartość 0, nie brakujący klucz")
        void shouldZeroFillChannelsWithoutContacts() {
            // given – tylko PHONE ma kontakty, reszta kanałów nie występuje w wyniku zapytania
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(contactService.getContactCountsByChannelInRange(eq(TENANT_ID_1), any(), any()))
                    .thenReturn(List.<Object[]>of(new Object[]{"PHONE", 7L}));

            // when
            ContactChannelMatrix matrix = adminMetricsService.getContactChannelMatrix(7);

            // then
            TenantChannelRow row = matrix.tenants().get(0);
            assertThat(row.countsByChannel()).containsOnlyKeys(
                    "PHONE", "EMAIL", "SOCIAL_FACEBOOK", "SOCIAL_INSTAGRAM", "SOCIAL_WHATSAPP");
            assertThat(row.countsByChannel().get("PHONE")).isEqualTo(7);
            assertThat(row.countsByChannel().get("EMAIL")).isEqualTo(0);
            assertThat(row.countsByChannel().get("SOCIAL_FACEBOOK")).isEqualTo(0);
            assertThat(row.countsByChannel().get("SOCIAL_INSTAGRAM")).isEqualTo(0);
            assertThat(row.countsByChannel().get("SOCIAL_WHATSAPP")).isEqualTo(0);
        }

        @Test
        @DisplayName("total powinien się zgadzać z sumą wartości countsByChannel")
        void totalShouldMatchSumOfCountsByChannel() {
            // given – wiele kanałów z kontaktami
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(contactService.getContactCountsByChannelInRange(eq(TENANT_ID_1), any(), any()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{"PHONE", 10L},
                            new Object[]{"EMAIL", 5L},
                            new Object[]{"SOCIAL_WHATSAPP", 3L}
                    ));

            // when
            ContactChannelMatrix matrix = adminMetricsService.getContactChannelMatrix(30);

            // then
            TenantChannelRow row = matrix.tenants().get(0);
            int sumOfMap = row.countsByChannel().values().stream().mapToInt(Integer::intValue).sum();
            assertThat(row.total()).isEqualTo(18);
            assertThat(row.total()).isEqualTo(sumOfMap);
        }

        @Test
        @DisplayName("total dla dzisiejszego zakresu (days=7, kontakty tylko dzisiaj) musi się zgadzać "
                + "z TenantMetrics.contactsToday dla tego samego tenanta (ta sama definicja kontaktu)")
        void totalShouldMatchTenantMetricsContactsToday() {
            // given – ta sama para tenant/dzień zwraca 12 z getDailyContactAggregate (contactsToday)
            // i sumarycznie 12 z getContactCountsByChannelInRange (rozbite na kanały)
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(0L);
            when(contactService.getDailyContactAggregate(eq(TENANT_ID_1), any()))
                    .thenReturn(new Object[]{12L, 0L, 0L, 0L});
            when(contactService.getContactCountsByChannelInRange(eq(TENANT_ID_1), any(), any()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{"PHONE", 9L},
                            new Object[]{"EMAIL", 3L}
                    ));

            // when
            AdminMetricsResponse globalMetrics = adminMetricsService.getGlobalMetrics();
            ContactChannelMatrix matrix = adminMetricsService.getContactChannelMatrix(7);

            // then
            int contactsTodayFromGlobal = globalMetrics.tenants().get(0).contactsToday();
            int totalFromMatrix = matrix.tenants().get(0).total();
            assertThat(totalFromMatrix).isEqualTo(contactsTodayFromGlobal);
            assertThat(totalFromMatrix).isEqualTo(12);
        }

        @Test
        @DisplayName("tenant bez żadnych kontaktów w zakresie powinien mieć wszystkie zera, nie być pominięty")
        void shouldIncludeTenantWithNoContactsInRangeAsAllZeroes() {
            // given – domyślny stub setUp() zwraca pustą listę dla getContactCountsByChannelInRange
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1, activeTenant2));

            // when
            ContactChannelMatrix matrix = adminMetricsService.getContactChannelMatrix(90);

            // then
            assertThat(matrix.tenants()).hasSize(2);
            assertThat(matrix.tenants()).allSatisfy(row -> {
                assertThat(row.total()).isZero();
                assertThat(row.countsByChannel().values()).allMatch(count -> count == 0);
            });
        }

        @Test
        @DisplayName("powinien zwrócić pustą listę tenantów gdy brak tenantów na platformie")
        void shouldReturnEmptyTenantListWhenNoTenants() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of());

            // when
            ContactChannelMatrix matrix = adminMetricsService.getContactChannelMatrix(7);

            // then
            assertThat(matrix.tenants()).isEmpty();
            assertThat(matrix.channels()).hasSize(5);
        }

        @Test
        @DisplayName("powinien zbudować wiersz per tenant z poprawnym tenantId i tenantName")
        void shouldBuildRowWithTenantIdAndName() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));

            // when
            ContactChannelMatrix matrix = adminMetricsService.getContactChannelMatrix(7);

            // then
            TenantChannelRow row = matrix.tenants().get(0);
            assertThat(row.tenantId()).isEqualTo(TENANT_ID_1);
            assertThat(row.tenantName()).isEqualTo("Acme Corporation");
        }
    }

    // =========================================================================
    // getGrowthMetrics()
    // =========================================================================

    @Nested
    @DisplayName("getGrowthMetrics()")
    class GetGrowthMetrics {

        @Test
        @DisplayName("powinien rzucić IllegalArgumentException gdy weeks poza zakresem 1-52")
        void shouldThrowWhenWeeksOutOfRange() {
            assertThatThrownBy(() -> adminMetricsService.getGrowthMetrics(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adminMetricsService.getGrowthMetrics(53))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("powinien zwrócić dokładnie N punktów tygodniowych z zero-fill dla brakujących tygodni")
        void shouldReturnExactlyNWeeklyPointsWithZeroFill() {
            // given
            LocalDate currentWeekStart = LocalDate.now(ZoneOffset.UTC)
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate since = currentWeekStart.minusWeeks(2); // weeks=3 -> since, since+7, since+14

            when(tenantService.countNewTenantsByWeekSince(any())).thenReturn(List.<Object[]>of(
                    new Object[]{java.sql.Date.valueOf(since), 2L},
                    new Object[]{java.sql.Date.valueOf(currentWeekStart), 5L}
            ));
            when(userService.countNewUsersByWeekSince(any())).thenReturn(List.<Object[]>of(
                    new Object[]{java.sql.Date.valueOf(since.plusWeeks(1)), 10L}
            ));
            when(pluginCatalogQueryService.findAllEnabledAcrossAllTenants()).thenReturn(List.of());

            // when
            GrowthMetrics metrics = adminMetricsService.getGrowthMetrics(3);

            // then
            assertThat(metrics.weeklyPoints()).hasSize(3);

            WeeklyGrowthPoint week0 = metrics.weeklyPoints().get(0);
            assertThat(week0.weekStart()).isEqualTo(since);
            assertThat(week0.newTenants()).isEqualTo(2);
            assertThat(week0.newUsers()).isEqualTo(0);

            WeeklyGrowthPoint week1 = metrics.weeklyPoints().get(1);
            assertThat(week1.weekStart()).isEqualTo(since.plusWeeks(1));
            assertThat(week1.newTenants()).isEqualTo(0);
            assertThat(week1.newUsers()).isEqualTo(10);

            WeeklyGrowthPoint week2 = metrics.weeklyPoints().get(2);
            assertThat(week2.weekStart()).isEqualTo(currentWeekStart);
            assertThat(week2.newTenants()).isEqualTo(5);
            assertThat(week2.newUsers()).isEqualTo(0);
        }

        @Test
        @DisplayName("powinien zbudować top pluginy posortowane malejąco po liczbie aktywnych instalacji")
        void shouldBuildTopPluginsSortedByInstallCount() {
            // given
            when(tenantService.countNewTenantsByWeekSince(any())).thenReturn(List.of());
            when(userService.countNewUsersByWeekSince(any())).thenReturn(List.of());

            UUID versionAId = UUID.randomUUID();
            UUID versionBId = UUID.randomUUID();

            Plugin pluginA = Plugin.builder()
                    .id(UUID.randomUUID()).pluginKey("acme-crm-sync")
                    .displayName("Acme CRM Sync").vendor("Acme").createdAt(Instant.now()).build();
            Plugin pluginB = Plugin.builder()
                    .id(UUID.randomUUID()).pluginKey("slack-notifier")
                    .displayName("Slack Notifier").vendor("Contoso").createdAt(Instant.now()).build();

            PluginVersion versionA = PluginVersion.builder()
                    .id(versionAId).plugin(pluginA).tenantId(UUID.randomUUID()).version("1.0.0")
                    .jarObjectKey("k1").checksumSha256("abc").manifestJson(new HashMap<>())
                    .sdkVersion("1.0").status(PluginVersion.PluginVersionStatus.VALIDATED).build();
            PluginVersion versionB = PluginVersion.builder()
                    .id(versionBId).plugin(pluginB).tenantId(UUID.randomUUID()).version("1.0.0")
                    .jarObjectKey("k2").checksumSha256("def").manifestJson(new HashMap<>())
                    .sdkVersion("1.0").status(PluginVersion.PluginVersionStatus.VALIDATED).build();

            TenantPluginInstallation install1 = buildInstallation(versionAId);
            TenantPluginInstallation install2 = buildInstallation(versionAId);
            TenantPluginInstallation install3 = buildInstallation(versionBId);

            when(pluginCatalogQueryService.findAllEnabledAcrossAllTenants())
                    .thenReturn(List.of(install1, install2, install3));
            when(pluginCatalogQueryService.findVersionById(versionAId)).thenReturn(Optional.of(versionA));
            when(pluginCatalogQueryService.findVersionById(versionBId)).thenReturn(Optional.of(versionB));

            // when
            GrowthMetrics metrics = adminMetricsService.getGrowthMetrics(1);

            // then
            assertThat(metrics.topPlugins()).hasSize(2);
            TopPlugin first = metrics.topPlugins().get(0);
            assertThat(first.pluginName()).isEqualTo("Acme CRM Sync");
            assertThat(first.installCount()).isEqualTo(2);
            TopPlugin second = metrics.topPlugins().get(1);
            assertThat(second.pluginName()).isEqualTo("Slack Notifier");
            assertThat(second.installCount()).isEqualTo(1);
        }

        private TenantPluginInstallation buildInstallation(UUID pluginVersionId) {
            TenantPluginInstallation installation = new TenantPluginInstallation();
            installation.setId(UUID.randomUUID());
            installation.setTenantId(UUID.randomUUID());
            installation.setPluginVersionId(pluginVersionId);
            installation.setEnabled(true);
            installation.setHealthStatus(TenantPluginInstallation.HealthStatus.HEALTHY);
            installation.setConsecutiveFailureCount(0);
            installation.setInstalledAt(Instant.now());
            installation.setUpdatedAt(Instant.now());
            return installation;
        }
    }

    // =========================================================================
    // getSystemResourceMetrics()
    // =========================================================================

    @Nested
    @DisplayName("getSystemResourceMetrics()")
    class GetSystemResourceMetrics {

        // Micrometer's MeterRegistry.gauge(name, tags, number) keeps only a WEAK reference
        // to the passed Number – ephemeral boxed literals can be garbage collected between
        // registration and read, causing gauge.value() to return NaN (flaky test failure).
        // Holding them as instance fields keeps a strong reference alive for the test's lifetime.
        private final Double threadsLiveValue = 42.0;
        private final Double dbPoolActiveValue = 4.0;
        private final Double dbPoolMaxValue = 20.0;
        private final Double cpuUsageValue = 0.25;

        @BeforeEach
        void registerGauges() {
            // heapUsed/heapMax/uptime NIE są już czytane z MeterRegistry, tylko z
            // java.lang.management (ManagementFactory) – patrz AdminMetricsServiceImpl.
            // Rejestrowanie ich tutaj tylko potwierdzałoby, że kod POPRAWNIE je ignoruje;
            // pomijamy to i asercjonujemy realne wartości JVM procesu testowego poniżej.
            meterRegistry.gauge("jvm.threads.live", threadsLiveValue);
            meterRegistry.gauge("hikaricp.connections.active", dbPoolActiveValue);
            meterRegistry.gauge("hikaricp.connections.max", dbPoolMaxValue);
            meterRegistry.gauge("system.cpu.usage", cpuUsageValue);
        }

        @Test
        @DisplayName("powinien odczytać metryki JVM/DB z MeterRegistry")
        void shouldReadJvmAndDbMetricsFromMeterRegistry() {
            // given – Redis/Rabbit UP (domyślne mocki poniżej), brak kolejek
            mockRedisPing(true);
            mockRabbitExecute(true);

            // when
            var metrics = adminMetricsService.getSystemResourceMetrics();

            // then – heapUsed/heapMax/uptime pochodzą z ManagementFactory (realny proces testowy JVM),
            // nie da się ich w pełni kontrolować w teście – sprawdzamy sensowne wartości zamiast
            // konkretnych liczb (used > 0, max >= used, uptime > 0).
            assertThat(metrics.heapUsedBytes()).isGreaterThan(0L);
            assertThat(metrics.heapMaxBytes()).isGreaterThanOrEqualTo(metrics.heapUsedBytes());
            assertThat(metrics.threadsLive()).isEqualTo(42);
            assertThat(metrics.uptimeSeconds()).isGreaterThan(0L);
            assertThat(metrics.dbPoolActive()).isEqualTo(4);
            assertThat(metrics.dbPoolMax()).isEqualTo(20);
            assertThat(metrics.cpuUsagePercent()).isEqualTo(25.0);
        }

        @Test
        @DisplayName("powinien zwrócić redisStatus=UP gdy ping odpowiada PONG")
        void shouldReturnRedisUpWhenPingSucceeds() {
            mockRedisPing(true);
            mockRabbitExecute(true);

            var metrics = adminMetricsService.getSystemResourceMetrics();

            assertThat(metrics.redisStatus()).isEqualTo("UP");
        }

        @Test
        @DisplayName("powinien zwrócić redisStatus=DOWN gdy ping rzuca wyjątek (graceful degradation)")
        void shouldReturnRedisDownWhenPingThrows() {
            when(redisTemplate.execute(any(RedisCallback.class)))
                    .thenThrow(new RuntimeException("Redis timeout"));
            mockRabbitExecute(true);

            var metrics = adminMetricsService.getSystemResourceMetrics();

            assertThat(metrics.redisStatus()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("powinien zwrócić rabbitStatus=DOWN gdy sprawdzenie połączenia rzuca wyjątek")
        void shouldReturnRabbitDownWhenExecuteThrows() {
            mockRedisPing(true);
            when(rabbitTemplate.execute(any())).thenThrow(new RuntimeException("Connection refused"));

            var metrics = adminMetricsService.getSystemResourceMetrics();

            assertThat(metrics.rabbitStatus()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("powinien zwrócić głębokość monitorowanych kolejek RabbitMQ")
        void shouldReturnQueueDepths() {
            mockRedisPing(true);
            mockRabbitExecute(true);

            when(rabbitAdmin.getQueueInfo("cc.queue.call-events"))
                    .thenReturn(new QueueInformation("cc.queue.call-events", 3, 2));
            when(rabbitAdmin.getQueueInfo("cc.queue.contact-routing"))
                    .thenReturn(new QueueInformation("cc.queue.contact-routing", 0, 1));
            when(rabbitAdmin.getQueueInfo("cc.queue.campaign-dialer"))
                    .thenReturn(new QueueInformation("cc.queue.campaign-dialer", 0, 1));
            when(rabbitAdmin.getQueueInfo("cc.queue.dead-letter"))
                    .thenReturn(new QueueInformation("cc.queue.dead-letter", 7, 0));

            var metrics = adminMetricsService.getSystemResourceMetrics();

            assertThat(metrics.queueDepths()).hasSize(4);
            assertThat(metrics.queueDepths())
                    .filteredOn(q -> q.queueName().equals("cc.queue.call-events"))
                    .extracting("messageCount").containsExactly(3);
            assertThat(metrics.queueDepths())
                    .filteredOn(q -> q.queueName().equals("cc.queue.dead-letter"))
                    .extracting("messageCount").containsExactly(7);
        }

        @Test
        @DisplayName("powinien obsłużyć brakującą kolejkę gracefully (messageCount=0, bez wyjątku)")
        void shouldHandleMissingQueueGracefully() {
            mockRedisPing(true);
            mockRabbitExecute(true);
            when(rabbitAdmin.getQueueInfo(any())).thenReturn(null);

            var metrics = adminMetricsService.getSystemResourceMetrics();

            assertThat(metrics.queueDepths()).hasSize(4);
            assertThat(metrics.queueDepths()).allSatisfy(q -> assertThat(q.messageCount()).isZero());
        }

        @SuppressWarnings("unchecked")
        private void mockRedisPing(boolean success) {
            RedisConnection connection = mock(RedisConnection.class);
            when(connection.ping()).thenReturn(success ? "PONG" : "NOPE");
            when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
                RedisCallback<Object> callback = invocation.getArgument(0);
                return callback.doInRedis(connection);
            });
        }

        private void mockRabbitExecute(boolean success) {
            if (success) {
                when(rabbitTemplate.execute(any())).thenReturn(null);
            } else {
                when(rabbitTemplate.execute(any())).thenThrow(new RuntimeException("down"));
            }
        }
    }

    // =========================================================================
    // exportTenantMetricsCsv()
    // =========================================================================

    @Nested
    @DisplayName("exportTenantMetricsCsv()")
    class ExportTenantMetricsCsv {

        @Test
        @DisplayName("powinien wygenerować CSV z nagłówkami i wierszami per tenant")
        void shouldGenerateCsvWithHeadersAndRows() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(userService.countAgentsByTenantId(TENANT_ID_1)).thenReturn(10L);
            when(contactService.getDailyContactAggregate(eq(TENANT_ID_1), any()))
                    .thenReturn(new Object[]{5L, 300L, 60L, 4L});

            // when
            byte[] csvBytes = adminMetricsService.exportTenantMetricsCsv();
            String csv = new String(csvBytes, java.nio.charset.StandardCharsets.UTF_8);

            // then
            assertThat(csv).contains("Tenant Name,Status,Agents Online,Agents Total,Contacts Today,"
                    + "Avg Handle Time (s),FCR (%),Agent Limit Utilization (%)");
            assertThat(csv).contains("Acme Corporation");
            assertThat(csv).contains("ACTIVE");
        }

        @Test
        @DisplayName("powinien zwrócić CSV zawierający tylko nagłówki gdy brak tenantów")
        void shouldReturnHeaderOnlyCsvWhenNoTenants() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of());

            // when
            byte[] csvBytes = adminMetricsService.exportTenantMetricsCsv();
            String csv = new String(csvBytes, java.nio.charset.StandardCharsets.UTF_8);

            // then
            assertThat(csv.strip().lines().count()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Map<String, Object> buildDefaultConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("max_agents", 100);
        config.put("max_queues", 50);
        config.put("max_campaigns", 20);
        config.put("recording_retention_days", 90);
        config.put("timezone", "Europe/Warsaw");
        return config;
    }

    /**
     * Mockuje wynik SCAN Redis ({@code redisTemplate.execute(RedisCallback, boolean)}) tak,
     * aby zwrócić podane klucze – symuluje {@code scanOnlineAgentKeys()} z produkcyjnego kodu.
     *
     * <p>Nadpisuje domyślny stub z {@link #setUp()} (który zwraca puste wyniki SCAN).
     */
    @SuppressWarnings("unchecked")
    private void mockScanResult(String... keys) {
        RedisConnection connection = mock(RedisConnection.class);
        RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
        when(connection.keyCommands()).thenReturn(keyCommands);

        List<byte[]> keyBytes = Arrays.stream(keys)
                .map(k -> k.getBytes(StandardCharsets.UTF_8))
                .collect(Collectors.toList());
        Iterator<byte[]> iterator = keyBytes.iterator();

        Cursor<byte[]> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        when(cursor.next()).thenAnswer(invocation -> iterator.next());

        when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

        when(redisTemplate.execute(any(RedisCallback.class), anyBoolean())).thenAnswer(invocation -> {
            RedisCallback<Object> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
    }

    /** Mockuje wartość sesji agenta w Redis (Map z polami tenantId/status – zgodnie z UserServiceImpl). */
    private void mockSessionValue(String key, UUID tenantId, String status) {
        Map<String, String> sessionData = new HashMap<>();
        sessionData.put("tenantId", tenantId.toString());
        sessionData.put("status", status);
        when(valueOps.get(key)).thenReturn(sessionData);
    }
}
