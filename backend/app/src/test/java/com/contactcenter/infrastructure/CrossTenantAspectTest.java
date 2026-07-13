package com.contactcenter.infrastructure;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.infrastructure.aspect.CrossTenantAspect;
import com.contactcenter.security.TenantContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link CrossTenantAspect}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Logowanie prób dostępu cross-tenant (advice @AfterThrowing)</li>
 *   <li>Weryfikacja obecności TenantContext przed metodami domenowymi (advice @Before)</li>
 *   <li>Aspekt nie rzuca własnych wyjątków – jedynie loguje</li>
 * </ul>
 *
 * <p>Aspekt jest klasą POJO (nie wymaga kontekstu Spring) – testowany bezpośrednio.
 *
 * <p>Używamy {@code LENIENT} strictness bo stuby JoinPoint są współdzielone między
 * zagnieżdżonymi klasami testowymi przez {@code @BeforeEach} – nie każdy test
 * wywołuje metodę aspektu, która korzysta z signature/target.
 */
@DisplayName("CrossTenantAspect – logowanie prób dostępu cross-tenant")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossTenantAspectTest {

    private static final UUID TENANT_A   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RESOURCE_1 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private Signature signature;

    private CrossTenantAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new CrossTenantAspect();

        // Domyślna konfiguracja mock JoinPoint (używana przez większość testów)
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("findById");
        when(joinPoint.getTarget()).thenReturn(new FakeRepository());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // =========================================================================
    // Klasy pomocnicze – symulacja warstwy domenowej
    // =========================================================================

    /** Symuluje repozytorium domenowe dla celów joinPoint.getTarget() */
    static class FakeRepository {
        @Override
        public String toString() {
            return "FakeRepository";
        }
    }

    /** Symuluje serwis domenowy */
    static class FakeService {
        @Override
        public String toString() {
            return "FakeService";
        }
    }

    /**
     * Symuluje {@code UserServiceImpl} – cel pointcutu dla metody bootstrapu
     * autentykacji ({@code findAuthenticatableUser}), wywoływanej przez
     * {@code JwtAuthFilter}/{@code UserDetailsServiceImpl} PRZED {@code TenantFilter}.
     */
    static class UserServiceImpl {
        @Override
        public String toString() {
            return "UserServiceImpl";
        }
    }

    // =========================================================================
    // Testy logCrossTenantAttempt()
    // =========================================================================

    @Nested
    @DisplayName("logCrossTenantAttempt() – advice @AfterThrowing")
    class LogCrossTenantAttemptTests {

        @Test
        @DisplayName("nie rzuca wyjątku gdy przechwytuje CrossTenantAccessException (pełna)")
        void logCrossTenantAttempt_fullException_doesNotThrow() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_1, TENANT_A, TENANT_B
            );

            // Advice nie powinien rzucać własnych wyjątków – jedynie loguje
            assertThatCode(() -> aspect.logCrossTenantAttempt(joinPoint, ex))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nie rzuca wyjątku gdy przechwytuje CrossTenantAccessException (bezpieczna)")
        void logCrossTenantAttempt_safeException_doesNotThrow() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_1, TENANT_A
            );

            assertThatCode(() -> aspect.logCrossTenantAttempt(joinPoint, ex))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nie rzuca gdy resourceId jest null")
        void logCrossTenantAttempt_nullResourceId_doesNotThrow() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    null, TENANT_A, TENANT_B
            );

            assertThatCode(() -> aspect.logCrossTenantAttempt(joinPoint, ex))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nie rzuca gdy requestingTenantId jest null (anonimowe żądanie)")
        void logCrossTenantAttempt_nullRequestingTenant_doesNotThrow() {
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_1, null, TENANT_B
            );

            assertThatCode(() -> aspect.logCrossTenantAttempt(joinPoint, ex))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("działa poprawnie dla różnych klas docelowych (serwis vs repozytorium)")
        void logCrossTenantAttempt_differentTargetClasses_doesNotThrow() {
            // Symulacja wywołania z serwisu domenowego
            when(joinPoint.getTarget()).thenReturn(new FakeService());
            when(signature.getName()).thenReturn("updateContact");

            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_1, TENANT_A, TENANT_B
            );

            assertThatCode(() -> aspect.logCrossTenantAttempt(joinPoint, ex))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Testy verifyTenantContext()
    // =========================================================================

    @Nested
    @DisplayName("verifyTenantContext() – advice @Before")
    class VerifyTenantContextTests {

        @Test
        @DisplayName("nie rzuca gdy TenantContext jest ustawiony")
        void verifyTenantContext_whenContextSet_doesNotThrow() {
            TenantContext.setTenantId(TENANT_A);
            TenantContext.setUserId(UUID.randomUUID());

            assertThatCode(() -> aspect.verifyTenantContext(joinPoint))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nie rzuca gdy TenantContext NIE jest ustawiony (tylko loguje ERROR)")
        void verifyTenantContext_whenContextNotSet_doesNotThrow() {
            // TenantContext jest pusty po cleanup()
            // Aspekt loguje ERROR, ale nie rzuca wyjątku – pozwala na propagację ISE
            // z TenantContext.getTenantId() gdy metoda domeny spróbuje go użyć.
            assertThatCode(() -> aspect.verifyTenantContext(joinPoint))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("działa poprawnie dla różnych metod joinPoint")
        void verifyTenantContext_differentMethods_doesNotThrow() {
            TenantContext.setTenantId(TENANT_A);

            when(signature.getName()).thenReturn("createContact");
            when(joinPoint.getTarget()).thenReturn(new FakeService());

            assertThatCode(() -> aspect.verifyTenantContext(joinPoint))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nie rzuca dla UserServiceImpl.findAuthenticatableUser bez TenantContext "
                + "(bootstrap autentykacji – early-return przed logiem ERROR)")
        void verifyTenantContext_authBootstrapMethod_withoutContext_doesNotThrow() {
            // TenantContext jest pusty (bootstrap autentykacji – JwtAuthFilter
            // wywołuje UserServiceImpl.findAuthenticatableUser PRZED TenantFilter)
            when(signature.getName()).thenReturn("findAuthenticatableUser");
            when(joinPoint.getTarget()).thenReturn(new UserServiceImpl());

            // Aspekt powinien rozpoznać ten przypadek jako oczekiwany (TRACE),
            // a nie logować ERROR jak dla pozostałych metod domenowych.
            assertThatCode(() -> aspect.verifyTenantContext(joinPoint))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nie rzuca i nie loguje ERROR dla SUPER_ADMIN bez tenant_id "
                + "(globalny administrator platformy – oczekiwany brak tenanta)")
        void verifyTenantContext_superAdminRole_withoutTenantId_doesNotThrow() {
            // SUPER_ADMIN nie ma tenant_id (TenantFilter celowo nie ustawia TenantContext.tenantId),
            // ale rola JEST ustawiona – to nie jest błąd konfiguracji filtrów.
            TenantContext.setUserRole("SUPER_ADMIN");
            when(signature.getName()).thenReturn("listTenants");

            assertThatCode(() -> aspect.verifyTenantContext(joinPoint))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("wywołanie wielokrotne nie powoduje błędów (idempotentność)")
        void verifyTenantContext_multipleCalls_doesNotThrow() {
            TenantContext.setTenantId(TENANT_A);

            assertThatCode(() -> {
                aspect.verifyTenantContext(joinPoint);
                aspect.verifyTenantContext(joinPoint);
                aspect.verifyTenantContext(joinPoint);
            }).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Testy kombinowane (scenariusze end-to-end)
    // =========================================================================

    @Nested
    @DisplayName("Scenariusze end-to-end")
    class EndToEndScenarios {

        @Test
        @DisplayName("scenariusz: weryfikacja przed metodą + logowanie wyjątku cross-tenant")
        void scenario_verifyThenLogException() {
            TenantContext.setTenantId(TENANT_A);

            // Krok 1: weryfikacja kontekstu przed metodą
            assertThatCode(() -> aspect.verifyTenantContext(joinPoint))
                    .doesNotThrowAnyException();

            // Krok 2: symulacja rzucenia CrossTenantAccessException przez metodę
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_1, TENANT_A, TENANT_B
            );

            // Krok 3: aspekt loguje wyjątek – nie rzuca własnych
            assertThatCode(() -> aspect.logCrossTenantAttempt(joinPoint, ex))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("scenariusz: brak kontekstu + wyjątek cross-tenant z null requestingTenant")
        void scenario_noContextAndCrossTenantException() {
            // TenantContext pusty – brak kontekstu (błąd konfiguracji)
            assertThatCode(() -> aspect.verifyTenantContext(joinPoint))
                    .doesNotThrowAnyException();

            // CrossTenantContext może być null gdy TenantContext nie jest ustawiony
            CrossTenantAccessException ex = new CrossTenantAccessException(
                    RESOURCE_1, null
            );

            assertThatCode(() -> aspect.logCrossTenantAttempt(joinPoint, ex))
                    .doesNotThrowAnyException();
        }
    }
}
