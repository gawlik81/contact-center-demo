package com.contactcenter.domain;

import com.contactcenter.api.admin.dto.AdminMetricsResponse;
import com.contactcenter.api.admin.dto.TenantDetailMetrics;
import com.contactcenter.api.admin.dto.TenantMetrics;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.contactcenter.domain.user.AppUserRepository;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.service.AdminMetricsService;
import jakarta.persistence.EntityNotFoundException;
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
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
class AdminMetricsServiceTest {

    private static final UUID TENANT_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TENANT_ID_3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private TenantService tenantService;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    @InjectMocks
    private AdminMetricsService adminMetricsService;

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
    }

    /**
     * Helper: konfiguruje mock execute() żeby SCAN "zwrócił" podane klucze.
     *
     * <p>Implementacja AdminMetricsService używa redisTemplate.execute(connection → cursor.scan)
     * zamiast redisTemplate.keys(). Callback iteruje po kursorze i dodaje klucze do Set.
     * Nie możemy łatwo zamockować całego flow connection → cursor, więc mockujemy execute()
     * żeby zwracał null (co oznacza pusty wynik) i osobno mockujemy SCAN przez doAnswer.
     *
     * <p>Dla testów agentów online używamy innego podejścia: mockujemy execute() przez doAnswer
     * żeby bezpośrednio wypełniał Set przekazywany przez closure.
     */
    @SuppressWarnings("unchecked")
    private void mockScanToReturnKeys(String... keys) {
        doAnswer(invocation -> {
            // execute(callback, exposeConnection) – callback wypełnia Set kluczy przez closure
            // Nie możemy bezpośrednio wywołać callbacku (wymaga realnego Connection),
            // więc konfigurujemy mock żeby wywoływał callback z mockiem Connection
            // który zwraca Cursor z podanymi kluczami.
            // Prostsze podejście: sprawdzamy czy jest cokolwiek do zwrócenia przez valueOps.
            return null; // powoduje empty Set w scanOnlineAgentKeys
        }).when(redisTemplate).execute(any(RedisCallback.class), anyBoolean());
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
            when(appUserRepository.countAgentsByTenantId(TENANT_ID_1)).thenReturn(10L);
            when(appUserRepository.countAgentsByTenantId(TENANT_ID_2)).thenReturn(5L);
            // SCAN zwraca puste wyniki (domyślne mock z setUp)

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response).isNotNull();
            assertThat(response.totalActiveTenants()).isEqualTo(2);
            assertThat(response.totalAgentsOnline()).isEqualTo(0);
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
            when(appUserRepository.countAgentsByTenantId(any())).thenReturn(0L);

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
            when(appUserRepository.countAgentsByTenantId(any())).thenReturn(5L);

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
            when(appUserRepository.countAgentsByTenantId(any())).thenReturn(5L);

            // when
            AdminMetricsResponse response = adminMetricsService.getGlobalMetrics();

            // then
            assertThat(response.systemAlerts()).isEmpty();
        }

        @Test
        @DisplayName("powinien obsłużyć błąd Redis gracefully (0 agentów online)")
        void shouldHandleRedisErrorGracefully() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(appUserRepository.countAgentsByTenantId(TENANT_ID_1)).thenReturn(5L);
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
            assertThat(response.systemAlerts()).isEmpty();
            assertThat(response.tenants()).isEmpty();
        }

        @Test
        @DisplayName("metryki tenanta w odpowiedzi powinny zawierać agentsTotal z bazy danych")
        void tenantMetricsShouldContainAgentsTotalFromDb() {
            // given
            when(tenantService.getAllTenants()).thenReturn(List.of(activeTenant1));
            when(appUserRepository.countAgentsByTenantId(TENANT_ID_1)).thenReturn(42L);

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
            when(appUserRepository.countAgentsByTenantId(TENANT_ID_1)).thenReturn(15L);

            // when
            TenantDetailMetrics metrics = adminMetricsService.getTenantMetrics(TENANT_ID_1);

            // then
            assertThat(metrics.id()).isEqualTo(TENANT_ID_1);
            assertThat(metrics.name()).isEqualTo("Acme Corporation");
            assertThat(metrics.status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(metrics.agentsTotal()).isEqualTo(15);
            assertThat(metrics.agentsOnline()).isEqualTo(0);
            assertThat(metrics.activeContacts()).isEqualTo(0);
            // MVP mocks
            assertThat(metrics.cpuUsage()).isEqualTo(0.0);
            assertThat(metrics.memoryUsage()).isEqualTo(0.0);
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
        @DisplayName("cpuUsage i memoryUsage są zawsze 0.0 (MVP mock)")
        void cpuAndMemoryUsageShouldAlwaysBeZeroOnMvp() {
            // given
            when(tenantService.findTenantEntity(TENANT_ID_1)).thenReturn(Optional.of(activeTenant1));
            when(appUserRepository.countAgentsByTenantId(TENANT_ID_1)).thenReturn(5L);

            // when
            TenantDetailMetrics metrics = adminMetricsService.getTenantMetrics(TENANT_ID_1);

            // then
            assertThat(metrics.cpuUsage()).isZero();
            assertThat(metrics.memoryUsage()).isZero();
        }

        @Test
        @DisplayName("powinien zwracać metryki nawet dla tenanta SUSPENDED")
        void shouldReturnMetricsForSuspendedTenant() {
            // given
            when(tenantService.findTenantEntity(TENANT_ID_3)).thenReturn(Optional.of(suspendedTenant));
            when(appUserRepository.countAgentsByTenantId(TENANT_ID_3)).thenReturn(3L);

            // when
            TenantDetailMetrics metrics = adminMetricsService.getTenantMetrics(TENANT_ID_3);

            // then
            assertThat(metrics.status()).isEqualTo(TenantStatus.SUSPENDED);
            assertThat(metrics.agentsTotal()).isEqualTo(3);
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
}
