package com.contactcenter.domain.social;

import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SocialDefaultQueueResolver (BE-155)")
class SocialDefaultQueueResolverTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID Q_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID Q_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private final TenantService tenantService = mock(TenantService.class);
    private final QueueService queueService = mock(QueueService.class);
    private final Map<String, Object> config = new HashMap<>();
    private SocialDefaultQueueResolver resolver;

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getConfig()).thenReturn(config);
        when(tenantService.findTenantEntity(TENANT)).thenReturn(Optional.of(tenant));
        resolver = new SocialDefaultQueueResolver(tenantService, queueService);
    }

    private static Queue queue(UUID id, boolean active) {
        return Queue.builder().queueId(id).tenantId(TENANT).name(id.toString())
                .routingStrategy("FIRST_AVAILABLE").active(active).build();
    }

    @Test
    @DisplayName("jawna konfiguracja wskazująca aktywną kolejkę tenanta")
    void configuredActiveQueue() {
        config.put(SocialDefaultQueueResolver.CONFIG_KEY, Q_B.toString());
        when(queueService.findQueueEntity(Q_B, TENANT)).thenReturn(Optional.of(queue(Q_B, true)));
        assertThat(resolver.resolve(TENANT)).contains(Q_B);
    }

    @Test
    @DisplayName("konfiguracja wskazująca nieaktywną/obcą kolejkę -> fallback na pierwszą aktywną")
    void configuredInactiveFallsBack() {
        config.put(SocialDefaultQueueResolver.CONFIG_KEY, Q_B.toString());
        when(queueService.findQueueEntity(Q_B, TENANT)).thenReturn(Optional.of(queue(Q_B, false)));
        when(queueService.getAllQueues(TENANT)).thenReturn(List.of(queue(Q_A, true), queue(Q_B, false)));
        assertThat(resolver.resolve(TENANT)).contains(Q_A);
    }

    @Test
    @DisplayName("niepoprawny UUID w konfiguracji -> fallback, bez wyjątku")
    void invalidUuidFallsBack() {
        config.put(SocialDefaultQueueResolver.CONFIG_KEY, "not-a-uuid");
        when(queueService.getAllQueues(TENANT)).thenReturn(List.of(queue(Q_A, true)));
        assertThat(resolver.resolve(TENANT)).contains(Q_A);
    }

    @Test
    @DisplayName("brak konfiguracji -> pierwsza aktywna kolejka (pomija nieaktywne)")
    void noConfigFirstActive() {
        when(queueService.getAllQueues(TENANT)).thenReturn(List.of(queue(Q_A, false), queue(Q_B, true)));
        assertThat(resolver.resolve(TENANT)).contains(Q_B);
    }

    @Test
    @DisplayName("brak aktywnych kolejek -> empty")
    void noQueues() {
        when(queueService.getAllQueues(TENANT)).thenReturn(List.of(queue(Q_A, false)));
        assertThat(resolver.resolve(TENANT)).isEmpty();
    }
}
