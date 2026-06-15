package com.contactcenter.domain.tenant;

import com.contactcenter.domain.exception.ResourceLimitExceededException;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link TenantResourceLimitService}.
 *
 * <p>Weryfikuje logikę sprawdzania limitów zasobów tenanta dla:
 * <ul>
 *   <li>Agentów (max_agents)</li>
 *   <li>Kolejek (max_queues)</li>
 *   <li>Kampanii (max_campaigns)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantResourceLimitService – sprawdzanie limitów zasobów")
class TenantResourceLimitServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final int  MAX_AGENTS    = 10;
    private static final int  MAX_QUEUES    = 5;
    private static final int  MAX_CAMPAIGNS = 3;

    @Mock
    private TenantRepository tenantRepository;

    @InjectMocks
    private TenantResourceLimitServiceImpl limitService;

    private Tenant tenantWithLimits;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = new HashMap<>();
        config.put("max_agents",    MAX_AGENTS);
        config.put("max_queues",    MAX_QUEUES);
        config.put("max_campaigns", MAX_CAMPAIGNS);
        config.put("timezone", "Europe/Warsaw");

        tenantWithLimits = Tenant.builder()
                .id(TENANT_ID)
                .name("Test Tenant")
                .status(TenantStatus.ACTIVE)
                .config(config)
                .createdAt(Instant.now())
                .build();
    }

    // =========================================================================
    // Limit agentów
    // =========================================================================

    @Nested
    @DisplayName("checkAgentLimit()")
    class CheckAgentLimit {

        @Test
        @DisplayName("powinien nie rzucać wyjątku gdy liczba agentów poniżej limitu")
        void shouldNotThrowWhenAgentCountBelowLimit() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveAgentsByTenantId(TENANT_ID)).thenReturn(9L);

            // when / then
            assertThatCode(() -> limitService.checkAgentLimit(TENANT_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("powinien rzucić ResourceLimitExceededException gdy limit agentów osiągnięty")
        void shouldThrowWhenAgentLimitReached() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveAgentsByTenantId(TENANT_ID)).thenReturn((long) MAX_AGENTS);

            // when / then
            assertThatThrownBy(() -> limitService.checkAgentLimit(TENANT_ID))
                    .isInstanceOf(ResourceLimitExceededException.class)
                    .satisfies(ex -> {
                        ResourceLimitExceededException rle = (ResourceLimitExceededException) ex;
                        assertThat(rle.getResourceType()).isEqualTo("agents");
                        assertThat(rle.getLimit()).isEqualTo(MAX_AGENTS);
                        assertThat(rle.getCurrent()).isEqualTo(MAX_AGENTS);
                    });
        }

        @Test
        @DisplayName("powinien rzucić ResourceLimitExceededException gdy liczba agentów przekracza limit")
        void shouldThrowWhenAgentCountExceedsLimit() {
            // given: niespójny stan DB – więcej agentów niż limit
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveAgentsByTenantId(TENANT_ID)).thenReturn(15L);

            // when / then
            assertThatThrownBy(() -> limitService.checkAgentLimit(TENANT_ID))
                    .isInstanceOf(ResourceLimitExceededException.class)
                    .satisfies(ex -> {
                        ResourceLimitExceededException rle = (ResourceLimitExceededException) ex;
                        assertThat(rle.getCurrent()).isEqualTo(15L);
                    });
        }

        @Test
        @DisplayName("powinien nie rzucać wyjątku gdy liczba agentów = 0 a limit > 0")
        void shouldNotThrowWhenNoAgents() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveAgentsByTenantId(TENANT_ID)).thenReturn(0L);

            // when / then
            assertThatCode(() -> limitService.checkAgentLimit(TENANT_ID))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Limit kolejek
    // =========================================================================

    @Nested
    @DisplayName("checkQueueLimit()")
    class CheckQueueLimit {

        @Test
        @DisplayName("powinien nie rzucać wyjątku gdy liczba kolejek poniżej limitu")
        void shouldNotThrowWhenQueueCountBelowLimit() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveQueuesByTenantId(TENANT_ID)).thenReturn(4L);

            // when / then
            assertThatCode(() -> limitService.checkQueueLimit(TENANT_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("powinien rzucić ResourceLimitExceededException gdy limit kolejek osiągnięty")
        void shouldThrowWhenQueueLimitReached() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveQueuesByTenantId(TENANT_ID)).thenReturn((long) MAX_QUEUES);

            // when / then
            assertThatThrownBy(() -> limitService.checkQueueLimit(TENANT_ID))
                    .isInstanceOf(ResourceLimitExceededException.class)
                    .satisfies(ex -> {
                        ResourceLimitExceededException rle = (ResourceLimitExceededException) ex;
                        assertThat(rle.getResourceType()).isEqualTo("queues");
                        assertThat(rle.getLimit()).isEqualTo(MAX_QUEUES);
                    });
        }
    }

    // =========================================================================
    // Limit kampanii
    // =========================================================================

    @Nested
    @DisplayName("checkCampaignLimit()")
    class CheckCampaignLimit {

        @Test
        @DisplayName("powinien nie rzucać wyjątku gdy liczba kampanii poniżej limitu")
        void shouldNotThrowWhenCampaignCountBelowLimit() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveCampaignsByTenantId(TENANT_ID)).thenReturn(2L);

            // when / then
            assertThatCode(() -> limitService.checkCampaignLimit(TENANT_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("powinien rzucić ResourceLimitExceededException gdy limit kampanii osiągnięty")
        void shouldThrowWhenCampaignLimitReached() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveCampaignsByTenantId(TENANT_ID)).thenReturn((long) MAX_CAMPAIGNS);

            // when / then
            assertThatThrownBy(() -> limitService.checkCampaignLimit(TENANT_ID))
                    .isInstanceOf(ResourceLimitExceededException.class)
                    .satisfies(ex -> {
                        ResourceLimitExceededException rle = (ResourceLimitExceededException) ex;
                        assertThat(rle.getResourceType()).isEqualTo("campaigns");
                        assertThat(rle.getLimit()).isEqualTo(MAX_CAMPAIGNS);
                    });
        }
    }

    // =========================================================================
    // LimitCheckResult – metody zapytań bez wyjątków
    // =========================================================================

    @Nested
    @DisplayName("getAgentLimitStatus() – wynik bez wyjątku")
    class GetLimitStatus {

        @Test
        @DisplayName("LimitCheckResult.isExceeded() = false gdy poniżej limitu")
        void shouldReturnNotExceededWhenBelowLimit() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveAgentsByTenantId(TENANT_ID)).thenReturn(5L);

            // when
            LimitCheckResult result = limitService.getAgentLimitStatus(TENANT_ID);

            // then
            assertThat(result.isExceeded()).isFalse();
            assertThat(result.available()).isEqualTo(5L);
            assertThat(result.limit()).isEqualTo(MAX_AGENTS);
            assertThat(result.current()).isEqualTo(5L);
        }

        @Test
        @DisplayName("LimitCheckResult.isExceeded() = true gdy limit osiągnięty")
        void shouldReturnExceededWhenAtLimit() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenantWithLimits));
            when(tenantRepository.countActiveAgentsByTenantId(TENANT_ID)).thenReturn((long) MAX_AGENTS);

            // when
            LimitCheckResult result = limitService.getAgentLimitStatus(TENANT_ID);

            // then
            assertThat(result.isExceeded()).isTrue();
            assertThat(result.available()).isEqualTo(0L);
        }
    }

    // =========================================================================
    // Tenant nie istnieje
    // =========================================================================

    @Nested
    @DisplayName("Brak tenanta")
    class TenantNotFound {

        @Test
        @DisplayName("powinien rzucić EntityNotFoundException gdy tenant nie istnieje przy checkAgentLimit")
        void shouldThrowEntityNotFoundWhenTenantMissing() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> limitService.checkAgentLimit(TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(TENANT_ID.toString());
        }
    }
}
