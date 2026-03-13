package com.contactcenter.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testy jednostkowe dla {@link TenantContext}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Podstawowe operacje set/get/clear dla tenantId i userId</li>
 *   <li>Wyjątki gdy kontekst nie jest ustawiony</li>
 *   <li>Czyszczenie kontekstu (brak wycieków między testami)</li>
 *   <li>Propagację do wątków potomnych przez InheritableThreadLocal</li>
 *   <li>Snapshot/restore dla async propagacji</li>
 * </ul>
 */
@DisplayName("TenantContext – ThreadLocal multi-tenant context")
class TenantContextTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_1   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @AfterEach
    void cleanup() {
        // Zawsze czyść kontekst po każdym teście – symuluje finally block filtra
        TenantContext.clear();
    }

    // =========================================================================
    // Testy podstawowych operacji
    // =========================================================================

    @Nested
    @DisplayName("Ustawianie i odczytywanie tenant_id")
    class TenantIdOperations {

        @Test
        @DisplayName("getTenantId() zwraca ustawiony tenantId")
        void getTenantId_whenSet_returnsCorrectValue() {
            TenantContext.setTenantId(TENANT_A);

            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_A);
        }

        @Test
        @DisplayName("getTenantId() rzuca ISE gdy kontekst nie jest ustawiony")
        void getTenantId_whenNotSet_throwsIllegalStateException() {
            assertThatThrownBy(TenantContext::getTenantId)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TenantContext.tenantId nie jest ustawiony");
        }

        @Test
        @DisplayName("getTenantIdOrNull() zwraca null gdy kontekst nie jest ustawiony")
        void getTenantIdOrNull_whenNotSet_returnsNull() {
            assertThat(TenantContext.getTenantIdOrNull()).isNull();
        }

        @Test
        @DisplayName("setTenantId() z null rzuca IAE")
        void setTenantId_withNull_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> TenantContext.setTenantId(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenantId nie może być null");
        }

        @Test
        @DisplayName("isSet() zwraca true po ustawieniu tenantId")
        void isSet_whenTenantIdSet_returnsTrue() {
            TenantContext.setTenantId(TENANT_A);
            assertThat(TenantContext.isSet()).isTrue();
        }

        @Test
        @DisplayName("isSet() zwraca false gdy kontekst nie jest ustawiony")
        void isSet_whenNotSet_returnsFalse() {
            assertThat(TenantContext.isSet()).isFalse();
        }
    }

    @Nested
    @DisplayName("Ustawianie i odczytywanie user_id")
    class UserIdOperations {

        @Test
        @DisplayName("getUserId() zwraca ustawiony userId")
        void getUserId_whenSet_returnsCorrectValue() {
            TenantContext.setTenantId(TENANT_A);
            TenantContext.setUserId(USER_1);

            assertThat(TenantContext.getUserId()).isEqualTo(USER_1);
        }

        @Test
        @DisplayName("getUserId() rzuca ISE gdy userId nie jest ustawiony")
        void getUserId_whenNotSet_throwsIllegalStateException() {
            assertThatThrownBy(TenantContext::getUserId)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TenantContext.userId nie jest ustawiony");
        }

        @Test
        @DisplayName("setUserId() z null rzuca IAE")
        void setUserId_withNull_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> TenantContext.setUserId(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("userId nie może być null");
        }

        @Test
        @DisplayName("getUserIdOrNull() zwraca null gdy userId nie jest ustawiony")
        void getUserIdOrNull_whenNotSet_returnsNull() {
            assertThat(TenantContext.getUserIdOrNull()).isNull();
        }
    }

    // =========================================================================
    // Testy czyszczenia kontekstu
    // =========================================================================

    @Nested
    @DisplayName("Czyszczenie kontekstu – clear()")
    class ClearOperations {

        @Test
        @DisplayName("clear() czyści tenantId")
        void clear_removesTenantId() {
            TenantContext.setTenantId(TENANT_A);
            TenantContext.clear();

            assertThat(TenantContext.getTenantIdOrNull()).isNull();
            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("clear() czyści wszystkie pola kontekstu")
        void clear_removesAllContextFields() {
            TenantContext.setTenantId(TENANT_A);
            TenantContext.setUserId(USER_1);
            TenantContext.setTenantName("Tenant A");
            TenantContext.setUserRole("ADMIN");

            TenantContext.clear();

            assertThat(TenantContext.getTenantIdOrNull()).isNull();
            assertThat(TenantContext.getUserIdOrNull()).isNull();
            assertThat(TenantContext.getTenantName()).isNull();
            assertThat(TenantContext.getUserRole()).isNull();
        }

        @Test
        @DisplayName("clear() jest bezpieczne gdy kontekst nie jest ustawiony")
        void clear_whenNotSet_doesNotThrow() {
            // Sprawdza, że clear() jest idempotentne (nie rzuca NPE)
            TenantContext.clear();
            TenantContext.clear(); // drugi raz – nie powinien rzucić
        }

        @Test
        @DisplayName("po clear() można ponownie ustawić tenantId")
        void afterClear_canSetNewTenantId() {
            TenantContext.setTenantId(TENANT_A);
            TenantContext.clear();

            TenantContext.setTenantId(TENANT_B);

            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_B);
        }
    }

    // =========================================================================
    // Testy izolacji między wątkami
    // =========================================================================

    @Nested
    @DisplayName("Izolacja między wątkami – ThreadLocal")
    class ThreadIsolation {

        @Test
        @DisplayName("kontekst w wątku A nie jest widoczny w wątku B (ThreadLocal izolacja)")
        void threadLocalIsolation_contextNotSharedBetweenThreads() throws ExecutionException, InterruptedException {
            // Ustaw kontekst w wątku testowym
            TenantContext.setTenantId(TENANT_A);

            // Nowy wątek (z osobną pulą – nie dziedziczy InheritableThreadLocal)
            AtomicReference<UUID> threadBTenantId = new AtomicReference<>();
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                // Nowy wątek z CompletableFuture.runAsync() MOŻE dziedziczyć
                // przez InheritableThreadLocal, ale czysty wątek nie będzie miał kontekstu
                threadBTenantId.set(TenantContext.getTenantIdOrNull());
            });
            future.get();

            // Wątek testowy nadal ma swój kontekst
            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_A);
        }

        @Test
        @DisplayName("InheritableThreadLocal propaguje kontekst do wątków potomnych")
        void inheritableThreadLocal_propagatesToChildThreads() throws ExecutionException, InterruptedException {
            // Ustaw kontekst w wątku głównym
            TenantContext.setTenantId(TENANT_A);
            TenantContext.setUserId(USER_1);

            AtomicReference<UUID> childTenantId = new AtomicReference<>();
            AtomicReference<UUID> childUserId   = new AtomicReference<>();

            // Wątek potomny przez Thread (nie z puli – dziedziczy InheritableThreadLocal)
            Thread childThread = new Thread(() -> {
                childTenantId.set(TenantContext.getTenantIdOrNull());
                childUserId.set(TenantContext.getUserIdOrNull());
            });
            childThread.start();
            childThread.join();

            // Wątek potomny powinien widzieć wartości z wątku rodzica
            assertThat(childTenantId.get()).isEqualTo(TENANT_A);
            assertThat(childUserId.get()).isEqualTo(USER_1);
        }
    }

    // =========================================================================
    // Testy Snapshot/Restore
    // =========================================================================

    @Nested
    @DisplayName("Snapshot i Restore – propagacja do async")
    class SnapshotRestore {

        @Test
        @DisplayName("snapshot() tworzy kopię aktualnego kontekstu")
        void snapshot_capturesCurrentContext() {
            TenantContext.setTenantId(TENANT_A);
            TenantContext.setUserId(USER_1);
            TenantContext.setTenantName("Acme Corp");
            TenantContext.setUserRole("SUPERVISOR");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            assertThat(snapshot.tenantId()).isEqualTo(TENANT_A);
            assertThat(snapshot.userId()).isEqualTo(USER_1);
            assertThat(snapshot.tenantName()).isEqualTo("Acme Corp");
            assertThat(snapshot.userRole()).isEqualTo("SUPERVISOR");
        }

        @Test
        @DisplayName("snapshot() zwraca puste wartości gdy kontekst nie jest ustawiony")
        void snapshot_whenNotSet_returnsNullValues() {
            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            assertThat(snapshot.tenantId()).isNull();
            assertThat(snapshot.userId()).isNull();
            assertThat(snapshot.tenantName()).isNull();
            assertThat(snapshot.userRole()).isNull();
        }

        @Test
        @DisplayName("restore() przywraca kontekst z snapshotu")
        void restore_restoresContextFromSnapshot() {
            TenantContext.setTenantId(TENANT_A);
            TenantContext.setUserId(USER_1);
            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            // Symuluj nowy wątek async – wyczyść kontekst
            TenantContext.clear();
            assertThat(TenantContext.isSet()).isFalse();

            // Przywróć kontekst
            TenantContext.restore(snapshot);

            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_A);
            assertThat(TenantContext.getUserId()).isEqualTo(USER_1);
        }

        @Test
        @DisplayName("snapshot jest niemutowalny – zmiana po snapshot nie wpływa na kopię")
        void snapshot_isImmutable() {
            TenantContext.setTenantId(TENANT_A);
            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            // Zmień kontekst po snapshot
            TenantContext.setTenantId(TENANT_B);

            // Snapshot nadal przechowuje TENANT_A
            assertThat(snapshot.tenantId()).isEqualTo(TENANT_A);
            // Aktualny kontekst to TENANT_B
            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_B);
        }

        @Test
        @DisplayName("restore() z null rzuca IAE")
        void restore_withNull_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> TenantContext.restore(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("snapshot nie może być null");
        }
    }
}
