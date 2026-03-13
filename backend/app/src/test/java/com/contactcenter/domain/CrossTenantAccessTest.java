package com.contactcenter.domain;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.repository.TenantAwareRepository;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe weryfikujące izolację tenantów w warstwie repozytorium.
 *
 * <p>Kryterium akceptacji BE-002:
 * <ul>
 *   <li>Próba dostępu do zasobu innego tenanta zwraca HTTP 403 (nie 404)</li>
 *   <li>Każde zapytanie do DB przez TenantAwareRepository automatycznie filtruje po tenant_id</li>
 *   <li>TenantContext czyszczony po zakończeniu żądania</li>
 * </ul>
 */
@DisplayName("CrossTenantAccess – izolacja tenantów w warstwie repozytorium")
@ExtendWith(MockitoExtension.class)
class CrossTenantAccessTest {

    private static final UUID TENANT_A   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID RESOURCE_1 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private TestableRepository repository;

    /**
     * Konkretna implementacja TenantAwareRepository do testów.
     * Udostępnia metody chronione jako publiczne.
     */
    static class TestableRepository extends TenantAwareRepository {

        public void callSetTenantContextInDb() {
            setTenantContextInDb();
        }

        public void callAssertSameTenant(UUID entityTenantId, UUID resourceId) {
            assertSameTenant(entityTenantId, resourceId);
        }

        public void callAssertSameTenantNoResource(UUID entityTenantId) {
            assertSameTenant(entityTenantId);
        }

        public void callDenyAccess(UUID resourceId) {
            denyAccess(resourceId);
        }

        public UUID callCurrentTenantId() {
            return currentTenantId();
        }
    }

    @BeforeEach
    void setUp() {
        repository = new TestableRepository();
        ReflectionTestUtils.setField(repository, "em", entityManager);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // =========================================================================
    // Testy CrossTenantAccessException
    // =========================================================================

    @Nested
    @DisplayName("CrossTenantAccessException – wyjątek izolacji tenantów")
    class CrossTenantExceptionTests {

        @Test
        @DisplayName("wyjątek zawiera tenant_id żądający i zasób")
        void exception_containsRequestingTenantAndResource() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_1, TENANT_A, TENANT_B
            );

            assertThat(ex.getResourceId()).isEqualTo(RESOURCE_1);
            assertThat(ex.getRequestingTenantId()).isEqualTo(TENANT_A);
            assertThat(ex.getResourceTenantId()).isEqualTo(TENANT_B);
        }

        @Test
        @DisplayName("wyjątek (bezpieczna wersja) nie ujawnia resourceTenantId")
        void exception_safeVersion_resourceTenantIdIsNull() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_1, TENANT_A
            );

            assertThat(ex.getResourceId()).isEqualTo(RESOURCE_1);
            assertThat(ex.getRequestingTenantId()).isEqualTo(TENANT_A);
            assertThat(ex.getResourceTenantId()).isNull();
        }

        @Test
        @DisplayName("wyjątek zawiera czytelny komunikat z identyfikatorami")
        void exception_messageContainsIds() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_1, TENANT_A, TENANT_B
            );

            assertThat(ex.getMessage())
                    .contains(RESOURCE_1.toString())
                    .contains(TENANT_A.toString())
                    .contains(TENANT_B.toString());
        }
    }

    // =========================================================================
    // Testy TenantAwareRepository.setTenantContextInDb()
    // =========================================================================

    @Nested
    @DisplayName("setTenantContextInDb() – wywołanie RLS w PostgreSQL")
    class SetTenantContextTests {

        @Test
        @DisplayName("setTenantContextInDb() wywołuje set_tenant_context() z aktualnym tenantId")
        void setTenantContextInDb_callsPostgresFunction() {
            TenantContext.setTenantId(TENANT_A);

            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(null);

            repository.callSetTenantContextInDb();

            verify(entityManager).createNativeQuery(contains("set_tenant_context"));
            verify(mockQuery).setParameter("tenantId", TENANT_A.toString());
        }

        @Test
        @DisplayName("setTenantContextInDb() rzuca ISE gdy TenantContext nie jest ustawiony")
        void setTenantContextInDb_withoutContext_throwsIllegalStateException() {
            assertThatThrownBy(() -> repository.callSetTenantContextInDb())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TenantContext");
        }
    }

    // =========================================================================
    // Testy TenantAwareRepository.assertSameTenant()
    // =========================================================================

    @Nested
    @DisplayName("assertSameTenant() – walidacja izolacji tenant_id")
    class AssertSameTenantTests {

        @Test
        @DisplayName("assertSameTenant() nie rzuca gdy tenant_id encji = kontekstowy tenant_id")
        void assertSameTenant_sameTenant_doesNotThrow() {
            TenantContext.setTenantId(TENANT_A);

            // Nie powinno rzucić
            repository.callAssertSameTenant(TENANT_A, RESOURCE_1);
        }

        @Test
        @DisplayName("assertSameTenant() rzuca CrossTenantAccessException gdy tenant_id się różni")
        void assertSameTenant_differentTenant_throwsCrossTenantAccessException() {
            TenantContext.setTenantId(TENANT_A);

            assertThatThrownBy(() -> repository.callAssertSameTenant(TENANT_B, RESOURCE_1))
                    .isInstanceOf(CrossTenantAccessException.class)
                    .satisfies(ex -> {
                        CrossTenantAccessException ctae = (CrossTenantAccessException) ex;
                        assertThat(ctae.getRequestingTenantId()).isEqualTo(TENANT_A);
                        assertThat(ctae.getResourceTenantId()).isEqualTo(TENANT_B);
                        assertThat(ctae.getResourceId()).isEqualTo(RESOURCE_1);
                    });
        }

        @Test
        @DisplayName("assertSameTenant() bez resourceId rzuca CrossTenantAccessException z resourceId=null")
        void assertSameTenant_withoutResourceId_throwsWithNullResourceId() {
            TenantContext.setTenantId(TENANT_A);

            assertThatThrownBy(() -> repository.callAssertSameTenantNoResource(TENANT_B))
                    .isInstanceOf(CrossTenantAccessException.class)
                    .satisfies(ex -> {
                        CrossTenantAccessException ctae = (CrossTenantAccessException) ex;
                        assertThat(ctae.getResourceId()).isNull();
                    });
        }

        @Test
        @DisplayName("denyAccess() zawsze rzuca CrossTenantAccessException")
        void denyAccess_alwaysThrows() {
            TenantContext.setTenantId(TENANT_A);

            assertThatThrownBy(() -> repository.callDenyAccess(RESOURCE_1))
                    .isInstanceOf(CrossTenantAccessException.class)
                    .satisfies(ex -> {
                        CrossTenantAccessException ctae = (CrossTenantAccessException) ex;
                        assertThat(ctae.getResourceId()).isEqualTo(RESOURCE_1);
                        assertThat(ctae.getRequestingTenantId()).isEqualTo(TENANT_A);
                        // Bezpieczna wersja – resourceTenantId nie jest ujawniany
                        assertThat(ctae.getResourceTenantId()).isNull();
                    });
        }

        @Test
        @DisplayName("currentTenantId() zwraca aktualny tenant_id z kontekstu")
        void currentTenantId_returnsContextTenantId() {
            TenantContext.setTenantId(TENANT_A);

            UUID result = repository.callCurrentTenantId();

            assertThat(result).isEqualTo(TENANT_A);
        }
    }

    // =========================================================================
    // Test scenariusza cross-tenant (kryterium akceptacji BE-002)
    // =========================================================================

    @Nested
    @DisplayName("Scenariusz BE-002: tenant A nie może odczytać danych tenanta B")
    class CrossTenantScenario {

        @Test
        @DisplayName("zasób tenanta B jest niedostępny dla tenanta A – CrossTenantAccessException")
        void tenantA_cannotAccessTenantBResource() {
            // Symulacja: tenant A jest zalogowany (z JWT)
            TenantContext.setTenantId(TENANT_A);
            TenantContext.setUserId(USER_ID);

            // Zasób (np. Contact) należy do tenanta B
            UUID resourceTenantId = TENANT_B;

            // Repozytorium musi wykryć naruszenie i rzucić CrossTenantAccessException
            assertThatThrownBy(() -> repository.callAssertSameTenant(resourceTenantId, RESOURCE_1))
                    .isInstanceOf(CrossTenantAccessException.class)
                    .satisfies(ex -> {
                        CrossTenantAccessException ctae = (CrossTenantAccessException) ex;
                        // Tenant A (żądający)
                        assertThat(ctae.getRequestingTenantId()).isEqualTo(TENANT_A);
                        // Zasób należy do tenanta B
                        assertThat(ctae.getResourceTenantId()).isEqualTo(TENANT_B);
                    });
        }

        @Test
        @DisplayName("zasób tenanta A jest dostępny dla tenanta A – brak wyjątku")
        void tenantA_canAccessOwnResource() {
            TenantContext.setTenantId(TENANT_A);

            // Nie rzuca – zasób należy do tego samego tenanta
            repository.callAssertSameTenant(TENANT_A, RESOURCE_1);
        }
    }
}
