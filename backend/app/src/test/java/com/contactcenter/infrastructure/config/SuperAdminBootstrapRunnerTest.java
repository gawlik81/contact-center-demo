package com.contactcenter.infrastructure.config;

import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link SuperAdminBootstrapRunner}.
 *
 * <p>Weryfikuje: idempotencję (drugi start nie tworzy duplikatu), fail-fast w profilu
 * innym niż dev bez zmiennych środowiskowych, fallback w dev, oraz priorytet zmiennych
 * środowiskowych nad fallbackiem gdy są ustawione.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SuperAdminBootstrapRunner – bootstrap konta SUPER_ADMIN przy starcie")
class SuperAdminBootstrapRunnerTest {

    @Mock private UserService userService;
    @Mock private Environment environment;

    private SuperAdminBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SuperAdminBootstrapRunner(userService, environment);
    }

    private void setConfiguredEmail(String email) {
        ReflectionTestUtils.setField(runner, "configuredEmail", email);
    }

    private void setConfiguredPassword(String password) {
        ReflectionTestUtils.setField(runner, "configuredPassword", password);
    }

    private AppUser buildCreatedSuperAdmin(String email) {
        return AppUser.builder()
                .id(UUID.randomUUID())
                .tenantId(null)
                .email(email)
                .passwordHash("$2a$12$hash")
                .role(UserRole.SUPER_ADMIN)
                .active(true)
                .status(UserStatus.ACTIVE)
                .passwordResetRequired(true)
                .build();
    }

    @Nested
    @DisplayName("Idempotencja")
    class Idempotency {

        @Test
        @DisplayName("gdy SUPER_ADMIN już istnieje – nie tworzy duplikatu (no-op)")
        void existingSuperAdmin_doesNotCreateDuplicate() throws Exception {
            when(userService.existsSuperAdmin()).thenReturn(true);

            runner.run(null);

            verify(userService, never()).createSuperAdminBootstrap(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Profil prod (lub inny niż dev) – fail-fast bez zmiennych środowiskowych")
    class ProdFailFast {

        @Test
        @DisplayName("brak SUPER_ADMIN_EMAIL i SUPER_ADMIN_PASSWORD w profilu prod rzuca IllegalStateException")
        void missingEnvVarsInProd_throwsIllegalStateException() throws Exception {
            when(userService.existsSuperAdmin()).thenReturn(false);
            when(environment.matchesProfiles("dev")).thenReturn(false);
            setConfiguredEmail("");
            setConfiguredPassword("");

            assertThatThrownBy(() -> runner.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SUPER_ADMIN_EMAIL")
                    .hasMessageContaining("SUPER_ADMIN_PASSWORD");

            verify(userService, never()).createSuperAdminBootstrap(anyString(), anyString());
        }

        @Test
        @DisplayName("brak wyłącznie SUPER_ADMIN_PASSWORD w profilu prod również rzuca fail-fast")
        void missingOnlyPasswordInProd_throwsIllegalStateException() throws Exception {
            when(userService.existsSuperAdmin()).thenReturn(false);
            when(environment.matchesProfiles("dev")).thenReturn(false);
            setConfiguredEmail("ops@contactcenter.io");
            setConfiguredPassword("");

            assertThatThrownBy(() -> runner.run(null))
                    .isInstanceOf(IllegalStateException.class);

            verify(userService, never()).createSuperAdminBootstrap(anyString(), anyString());
        }

        @Test
        @DisplayName("SUPER_ADMIN_EMAIL i SUPER_ADMIN_PASSWORD ustawione w profilu prod – tworzy konto z tych wartości")
        void envVarsSetInProd_createsAccountFromEnvVars() throws Exception {
            when(userService.existsSuperAdmin()).thenReturn(false);
            when(environment.matchesProfiles("dev")).thenReturn(false);
            setConfiguredEmail("ops@contactcenter.io");
            setConfiguredPassword("StrongPass1!");
            when(userService.createSuperAdminBootstrap(eq("ops@contactcenter.io"), eq("StrongPass1!")))
                    .thenReturn(buildCreatedSuperAdmin("ops@contactcenter.io"));

            runner.run(null);

            verify(userService).createSuperAdminBootstrap("ops@contactcenter.io", "StrongPass1!");
        }
    }

    @Nested
    @DisplayName("Profil dev – fallback gdy zmienne puste")
    class DevFallback {

        @Test
        @DisplayName("brak zmiennych środowiskowych w dev – używa fallbacku domyślnego")
        void missingEnvVarsInDev_usesFallback() throws Exception {
            when(userService.existsSuperAdmin()).thenReturn(false);
            when(environment.matchesProfiles("dev")).thenReturn(true);
            setConfiguredEmail("");
            setConfiguredPassword("");
            when(userService.createSuperAdminBootstrap(anyString(), anyString()))
                    .thenAnswer(inv -> buildCreatedSuperAdmin(inv.getArgument(0)));

            runner.run(null);

            ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
            verify(userService).createSuperAdminBootstrap(emailCaptor.capture(), passwordCaptor.capture());

            assertThat(emailCaptor.getValue()).isEqualTo("superadmin@contactcenter.dev");
            assertThat(passwordCaptor.getValue()).isNotBlank();
        }

        @Test
        @DisplayName("zmienne środowiskowe ustawione w dev mają priorytet nad fallbackiem")
        void envVarsSetInDev_takePriorityOverFallback() throws Exception {
            when(userService.existsSuperAdmin()).thenReturn(false);
            when(environment.matchesProfiles("dev")).thenReturn(true);
            setConfiguredEmail("custom-dev-admin@contactcenter.dev");
            setConfiguredPassword("CustomDevPass1!");
            when(userService.createSuperAdminBootstrap(anyString(), anyString()))
                    .thenAnswer(inv -> buildCreatedSuperAdmin(inv.getArgument(0)));

            runner.run(null);

            verify(userService).createSuperAdminBootstrap("custom-dev-admin@contactcenter.dev", "CustomDevPass1!");
        }
    }
}
