package com.contactcenter.domain;

import com.contactcenter.api.queue.dto.CreateQueueRequest;
import com.contactcenter.api.queue.dto.QueueResponse;
import com.contactcenter.api.queue.dto.UpdateQueueRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.exception.ResourceLimitExceededException;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.service.QueueService;
import com.contactcenter.domain.service.TenantResourceLimitService;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link QueueService}.
 *
 * <p>Weryfikuje logikę biznesową: tworzenie kolejek (z walidacją limitu),
 * odczyt, aktualizację i usuwanie (z blokadą aktywnych kontaktów).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueService – CRUD kolejek")
class QueueServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID QUEUE_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock private QueueRepository queueRepository;
    @Mock private TenantResourceLimitService tenantResourceLimitService;

    @InjectMocks
    private QueueService queueService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(UUID.randomUUID());
        TenantContext.setUserRole("SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Tworzenie kolejki
    // =========================================================================

    @Nested
    @DisplayName("createQueue()")
    class CreateQueue {

        @Test
        @DisplayName("sukces – tworzy kolejkę z podstawowymi danymi")
        void shouldCreateQueueSuccessfully() {
            // given
            CreateQueueRequest request = new CreateQueueRequest(
                    "Kolejka testowa", "ROUND_ROBIN",
                    List.of("SALES"), null, null, null, null, null
            );

            Queue savedQueue = buildQueue(QUEUE_ID, TENANT_ID, "Kolejka testowa", "ROUND_ROBIN");
            doNothing().when(tenantResourceLimitService).checkQueueLimit(TENANT_ID);
            when(queueRepository.insert(any(Queue.class))).thenReturn(savedQueue);

            // when
            QueueResponse result = queueService.createQueue(request, TENANT_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Kolejka testowa");
            assertThat(result.routingStrategy()).isEqualTo("ROUND_ROBIN");
            assertThat(result.tenantId()).isEqualTo(TENANT_ID);

            verify(tenantResourceLimitService).checkQueueLimit(TENANT_ID);
            verify(queueRepository).insert(any(Queue.class));
        }

        @Test
        @DisplayName("sukces – domyślne wartości dla pól opcjonalnych")
        void shouldApplyDefaultValuesForOptionalFields() {
            // given
            CreateQueueRequest request = new CreateQueueRequest(
                    "Kolejka", "SKILL_BASED",
                    null, null, null, null, null, null
            );

            Queue savedQueue = buildQueue(QUEUE_ID, TENANT_ID, "Kolejka", "SKILL_BASED");
            doNothing().when(tenantResourceLimitService).checkQueueLimit(TENANT_ID);
            when(queueRepository.insert(any(Queue.class))).thenAnswer(inv -> {
                Queue q = inv.getArgument(0);
                // weryfikujemy wartości domyślne
                assertThat(q.getStickyAgentTimeoutSeconds()).isEqualTo(60);
                assertThat(q.getMaxConcurrentContactsPerAgent()).isEqualTo(1);
                assertThat(q.isActive()).isTrue();
                assertThat(q.getRequiredSkills()).isEmpty();
                return savedQueue;
            });

            // when
            queueService.createQueue(request, TENANT_ID);

            // then – weryfikacja jest wewnątrz answera
            verify(queueRepository).insert(any(Queue.class));
        }

        @Test
        @DisplayName("błąd – przekroczono limit kolejek (HTTP 422)")
        void shouldThrowWhenQueueLimitExceeded() {
            // given
            CreateQueueRequest request = new CreateQueueRequest(
                    "Kolejka", "ROUND_ROBIN", null, null, null, null, null, null
            );
            doThrow(new ResourceLimitExceededException("queues", 5, 5))
                    .when(tenantResourceLimitService).checkQueueLimit(TENANT_ID);

            // when / then
            assertThatThrownBy(() -> queueService.createQueue(request, TENANT_ID))
                    .isInstanceOf(ResourceLimitExceededException.class)
                    .hasMessageContaining("queues");

            verify(queueRepository, never()).insert(any());
        }
    }

    // =========================================================================
    // Odczyt kolejki
    // =========================================================================

    @Nested
    @DisplayName("getQueue()")
    class GetQueue {

        @Test
        @DisplayName("sukces – zwraca dane kolejki")
        void shouldReturnQueueWhenFound() {
            // given
            Queue queue = buildQueue(QUEUE_ID, TENANT_ID, "Kolejka VIP", "SKILL_BASED");
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(queue));

            // when
            QueueResponse result = queueService.getQueue(QUEUE_ID, TENANT_ID);

            // then
            assertThat(result.queueId()).isEqualTo(QUEUE_ID);
            assertThat(result.name()).isEqualTo("Kolejka VIP");
            assertThat(result.routingStrategy()).isEqualTo("SKILL_BASED");
        }

        @Test
        @DisplayName("błąd – kolejka nie istnieje (EntityNotFoundException)")
        void shouldThrowWhenQueueNotFound() {
            // given
            UUID unknownId = UUID.randomUUID();
            when(queueRepository.findByIdAndTenantId(unknownId, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> queueService.getQueue(unknownId, TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(unknownId.toString());
        }
    }

    // =========================================================================
    // Usunięcie (deaktywacja) kolejki
    // =========================================================================

    @Nested
    @DisplayName("deleteQueue()")
    class DeleteQueue {

        @Test
        @DisplayName("sukces – deaktywuje kolejkę bez aktywnych kontaktów")
        void shouldDeactivateQueueWhenNoActiveContacts() {
            // given
            Queue queue = buildQueue(QUEUE_ID, TENANT_ID, "Stara kolejka", "ROUND_ROBIN");
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(queue));
            when(queueRepository.hasActiveContacts(QUEUE_ID, TENANT_ID)).thenReturn(false);
            when(queueRepository.softDelete(QUEUE_ID, TENANT_ID)).thenReturn(1);

            // when
            assertThatNoException().isThrownBy(() -> queueService.deleteQueue(QUEUE_ID, TENANT_ID));

            // then
            verify(queueRepository).softDelete(QUEUE_ID, TENANT_ID);
        }

        @Test
        @DisplayName("błąd – kolejka ma aktywne kontakty (InvalidOperationException HTTP 409)")
        void shouldThrowWhenQueueHasActiveContacts() {
            // given
            Queue queue = buildQueue(QUEUE_ID, TENANT_ID, "Aktywna kolejka", "ROUND_ROBIN");
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(queue));
            when(queueRepository.hasActiveContacts(QUEUE_ID, TENANT_ID)).thenReturn(true);

            // when / then
            assertThatThrownBy(() -> queueService.deleteQueue(QUEUE_ID, TENANT_ID))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("aktywnymi kontaktami");

            verify(queueRepository, never()).softDelete(any(), any());
        }

        @Test
        @DisplayName("błąd – kolejka nie istnieje (EntityNotFoundException)")
        void shouldThrowWhenQueueNotFoundOnDelete() {
            // given
            UUID unknownId = UUID.randomUUID();
            when(queueRepository.findByIdAndTenantId(unknownId, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> queueService.deleteQueue(unknownId, TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class);

            verify(queueRepository, never()).softDelete(any(), any());
        }
    }

    // =========================================================================
    // Aktualizacja kolejki
    // =========================================================================

    @Nested
    @DisplayName("updateQueue()")
    class UpdateQueue {

        @Test
        @DisplayName("sukces – aktualizuje podane pola, ignoruje null")
        void shouldUpdateQueueSuccessfully() {
            // given
            Queue existing = buildQueue(QUEUE_ID, TENANT_ID, "Stara nazwa", "ROUND_ROBIN");
            Queue refreshed = buildQueue(QUEUE_ID, TENANT_ID, "Nowa nazwa", "SKILL_BASED");

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(existing))   // pierwsze wywołanie: findQueueOrThrow przed update
                    .thenReturn(Optional.of(refreshed)); // drugie wywołanie: odczyt po update
            when(queueRepository.update(any(Queue.class))).thenReturn(1);

            UpdateQueueRequest request = new UpdateQueueRequest(
                    "Nowa nazwa", "SKILL_BASED", null, null, null, null, null, null
            );

            // when
            QueueResponse result = queueService.updateQueue(QUEUE_ID, request, TENANT_ID);

            // then
            assertThat(result.name()).isEqualTo("Nowa nazwa");
            assertThat(result.routingStrategy()).isEqualTo("SKILL_BASED");
            verify(queueRepository).update(argThat(q ->
                    "Nowa nazwa".equals(q.getName()) && "SKILL_BASED".equals(q.getRoutingStrategy())
            ));
        }

        @Test
        @DisplayName("sukces – pola null w żądaniu nie nadpisują istniejących wartości")
        void shouldIgnoreNullFieldsOnPatch() {
            // given
            Queue existing = buildQueue(QUEUE_ID, TENANT_ID, "Oryginalna", "FIRST_AVAILABLE");
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(existing))
                    .thenReturn(Optional.of(existing));
            when(queueRepository.update(any(Queue.class))).thenReturn(1);

            // tylko stickyAgentTimeoutSeconds zmienione, reszta null
            UpdateQueueRequest request = new UpdateQueueRequest(
                    null, null, null, null, 120, null, null, null
            );

            // when
            queueService.updateQueue(QUEUE_ID, request, TENANT_ID);

            // then – name i routingStrategy nie powinny być zmienione
            verify(queueRepository).update(argThat(q ->
                    "Oryginalna".equals(q.getName())
                    && "FIRST_AVAILABLE".equals(q.getRoutingStrategy())
                    && q.getStickyAgentTimeoutSeconds() == 120
            ));
        }

        @Test
        @DisplayName("błąd – kolejka nie istnieje (EntityNotFoundException)")
        void shouldThrowWhenQueueNotFoundOnUpdate() {
            // given
            UUID unknownId = UUID.randomUUID();
            when(queueRepository.findByIdAndTenantId(unknownId, TENANT_ID))
                    .thenReturn(Optional.empty());

            UpdateQueueRequest request = new UpdateQueueRequest(
                    "Nowa nazwa", null, null, null, null, null, null, null
            );

            // when / then
            assertThatThrownBy(() -> queueService.updateQueue(unknownId, request, TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(unknownId.toString());

            verify(queueRepository, never()).update(any());
        }
    }

    // =========================================================================
    // Race condition w deleteQueue
    // =========================================================================

    @Nested
    @DisplayName("deleteQueue() – race condition (TOCTOU)")
    class DeleteQueueRaceCondition {

        @Test
        @DisplayName("błąd – kolejka usunięta przez inną sesję między findQueueOrThrow a softDelete")
        void shouldThrowWhenSoftDeleteReturnsZero() {
            // given: findQueueOrThrow zwraca kolejkę (widoczna przy check)
            Queue queue = buildQueue(QUEUE_ID, TENANT_ID, "Kolejka", "ROUND_ROBIN");
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(queue));
            when(queueRepository.hasActiveContacts(QUEUE_ID, TENANT_ID)).thenReturn(false);
            // softDelete zwraca 0 – kolejka zniknęła między check a delete (TOCTOU)
            when(queueRepository.softDelete(QUEUE_ID, TENANT_ID)).thenReturn(0);

            // when / then
            assertThatThrownBy(() -> queueService.deleteQueue(QUEUE_ID, TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(QUEUE_ID.toString());

            verify(queueRepository).softDelete(QUEUE_ID, TENANT_ID);
        }
    }

    // =========================================================================
    // Lista strategii routingu
    // =========================================================================

    @Nested
    @DisplayName("listRoutingStrategies()")
    class ListRoutingStrategies {

        @Test
        @DisplayName("zwraca kompletną listę strategii routingu")
        void shouldReturnAllRoutingStrategies() {
            // when
            List<String> strategies = queueService.listRoutingStrategies();

            // then
            assertThat(strategies)
                    .hasSize(3)
                    .containsExactlyInAnyOrder("ROUND_ROBIN", "FIRST_AVAILABLE", "SKILL_BASED");
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Queue buildQueue(UUID queueId, UUID tenantId, String name, String strategy) {
        return Queue.builder()
                .queueId(queueId)
                .tenantId(tenantId)
                .name(name)
                .routingStrategy(strategy)
                .requiredSkills(List.of())
                .stickyAgentTimeoutSeconds(60)
                .maxConcurrentContactsPerAgent(1)
                .waitConfig("{\"announce_wait_time\": true, \"announce_interval_seconds\": 30}")
                .active(true)
                .createdAt(Instant.now())
                .build();
    }
}
