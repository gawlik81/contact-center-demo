package com.contactcenter.domain.user;

import com.contactcenter.api.user.dto.AdminCreateUserRequest;
import com.contactcenter.api.user.dto.AdminUpdateUserRequest;
import com.contactcenter.domain.tenant.TenantResourceLimitService;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link AdminUserServiceImpl}.
 *
 * <p>Zakres: refaktor ról SUPER_ADMIN/ADMIN/SUPERVISOR/AGENT – SUPER_ADMIN może powstać
 * przez bootstrap systemu ({@code SuperAdminBootstrapRunner}) LUB przez ten endpoint
 * (kolejne konto SUPER_ADMIN, tworzone świadomie przez istniejącego SUPER_ADMIN).
 * Operacje update/delete na własnym koncie wywołującego są zablokowane (ochrona przed
 * przypadkową samo-blokadą dostępu do panelu cross-tenant).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminUserServiceImpl – zarządzanie użytkownikami cross-tenant")
class AdminUserServiceImplTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CALLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private AppUserRepository appUserRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TenantResourceLimitService tenantResourceLimitService;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    private AdminUserServiceImpl adminUserService;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserServiceImpl(appUserRepository, passwordEncoder, tenantResourceLimitService, refreshTokenRepository);
        TenantContext.setUserId(CALLER_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("createUser")
    class CreateUserTests {

        @Test
        @DisplayName("tworzy kolejne konto SUPER_ADMIN gdy tenantId nie podano")
        void shouldCreateAnotherSuperAdmin() {
            AdminCreateUserRequest request = new AdminCreateUserRequest(
                    null, "second-super-admin@example.com", "SecretPass1!",
                    "Ala", "Nowak", UserRole.SUPER_ADMIN, null
            );
            when(appUserRepository.existsActiveSuperAdminByEmail("second-super-admin@example.com"))
                    .thenReturn(false);
            when(passwordEncoder.encode("SecretPass1!")).thenReturn("$2a$12$hash");
            when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

            var response = adminUserService.createUser(request);

            assertThat(response.role()).isEqualTo(UserRole.SUPER_ADMIN);
            assertThat(response.tenantId()).isNull();
            verify(appUserRepository, never()).existsByTenantIdAndEmail(any(), any());
        }

        @Test
        @DisplayName("odrzuca utworzenie SUPER_ADMIN z podanym tenantId (HTTP 400)")
        void shouldRejectSuperAdminCreationWithTenantId() {
            AdminCreateUserRequest request = new AdminCreateUserRequest(
                    TENANT_ID, "wannabe-super-admin@example.com", "SecretPass1!",
                    null, null, UserRole.SUPER_ADMIN, null
            );

            assertThatThrownBy(() -> adminUserService.createUser(request))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verify(appUserRepository, never()).save(any());
        }

        @Test
        @DisplayName("odrzuca utworzenie SUPER_ADMIN z emailem zajętym przez inne konto SUPER_ADMIN")
        void shouldRejectDuplicateSuperAdminEmail() {
            AdminCreateUserRequest request = new AdminCreateUserRequest(
                    null, "existing-super-admin@example.com", "SecretPass1!",
                    null, null, UserRole.SUPER_ADMIN, null
            );
            when(appUserRepository.existsActiveSuperAdminByEmail("existing-super-admin@example.com"))
                    .thenReturn(true);

            assertThatThrownBy(() -> adminUserService.createUser(request))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(appUserRepository, never()).save(any());
        }

        @Test
        @DisplayName("odrzuca utworzenie użytkownika innej roli bez tenantId (HTTP 400)")
        void shouldRejectNonSuperAdminCreationWithoutTenantId() {
            AdminCreateUserRequest request = new AdminCreateUserRequest(
                    null, "no-tenant@example.com", "SecretPass1!",
                    null, null, UserRole.ADMIN, null
            );

            assertThatThrownBy(() -> adminUserService.createUser(request))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verify(appUserRepository, never()).save(any());
        }

        @Test
        @DisplayName("tworzy użytkownika ADMIN w podanym tenancie gdy rola inna niż SUPER_ADMIN")
        void shouldCreateAdminUser() {
            AdminCreateUserRequest request = new AdminCreateUserRequest(
                    TENANT_ID, "new-admin@example.com", "SecretPass1!",
                    "Jan", "Kowalski", UserRole.ADMIN, List.of()
            );
            when(appUserRepository.existsByTenantIdAndEmail(TENANT_ID, "new-admin@example.com"))
                    .thenReturn(false);
            when(passwordEncoder.encode("SecretPass1!")).thenReturn("$2a$12$hash");
            when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

            var response = adminUserService.createUser(request);

            assertThat(response.role()).isEqualTo(UserRole.ADMIN);
            verify(appUserRepository).save(any(AppUser.class));
        }
    }

    @Nested
    @DisplayName("Ochrona własnego konta (self-service protection)")
    class SelfProtectionTests {

        @Test
        @DisplayName("odrzuca próbę edycji własnego konta wywołującego (HTTP 403)")
        void shouldRejectSelfUpdate() {
            AdminUpdateUserRequest request = new AdminUpdateUserRequest(
                    null, null, null, null, null, false
            );

            assertThatThrownBy(() -> adminUserService.updateUser(CALLER_ID, request))
                    .isInstanceOf(AccessDeniedException.class);

            verify(appUserRepository, never()).findById(any());
            verify(appUserRepository, never()).save(any());
        }

        @Test
        @DisplayName("odrzuca próbę usunięcia własnego konta wywołującego (HTTP 403)")
        void shouldRejectSelfDelete() {
            assertThatThrownBy(() -> adminUserService.deleteUser(CALLER_ID))
                    .isInstanceOf(AccessDeniedException.class);

            verify(appUserRepository, never()).findById(any());
            verify(appUserRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateUser")
    class UpdateUserTests {

        @Test
        @DisplayName("odrzuca próbę zmiany roli na SUPER_ADMIN (HTTP 400)")
        void shouldRejectSuperAdminRoleUpdateAttempt() {
            AdminUpdateUserRequest request = new AdminUpdateUserRequest(
                    null, null, null, UserRole.SUPER_ADMIN, null, null
            );

            assertThatThrownBy(() -> adminUserService.updateUser(USER_ID, request))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verify(appUserRepository, never()).findById(any());
            verify(appUserRepository, never()).save(any());
        }

        @Test
        @DisplayName("pozwala zaktualizować rolę na SUPERVISOR")
        void shouldAllowRoleUpdateToSupervisor() {
            AppUser existing = AppUser.builder()
                    .id(USER_ID)
                    .tenantId(TENANT_ID)
                    .email("agent@example.com")
                    .passwordHash("$2a$12$hash")
                    .role(UserRole.AGENT)
                    .active(true)
                    .status(UserStatus.ACTIVE)
                    .deleted(false)
                    .build();
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
            when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

            AdminUpdateUserRequest request = new AdminUpdateUserRequest(
                    null, null, null, UserRole.SUPERVISOR, null, null
            );

            var response = adminUserService.updateUser(USER_ID, request);

            assertThat(response.role()).isEqualTo(UserRole.SUPERVISOR);
        }
    }

    @Nested
    @DisplayName("forcePasswordReset")
    class ForcePasswordResetTests {

        @Test
        @DisplayName("ustawia passwordResetRequired i unieważnia refresh tokeny użytkownika")
        void shouldSetPasswordResetRequiredAndRevokeRefreshTokens() {
            AppUser user = AppUser.builder()
                    .id(USER_ID)
                    .tenantId(TENANT_ID)
                    .email("agent@example.com")
                    .passwordHash("$2a$12$hash")
                    .role(UserRole.AGENT)
                    .active(true)
                    .status(UserStatus.ACTIVE)
                    .passwordResetRequired(false)
                    .deleted(false)
                    .build();
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(2);

            adminUserService.forcePasswordReset(USER_ID);

            assertThat(user.isPasswordResetRequired()).isTrue();
            verify(appUserRepository).save(user);
            verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
        }

        @Test
        @DisplayName("unieważnia refresh tokeny PRZED zapisem encji – @Modifying(clearAutomatically=true) " +
                "na revokeAllByUserId() wywołuje entityManager.clear(), które po cichu odrzuca " +
                "niezflushowane zmiany na już załadowanej encji AppUser bez żadnego wyjątku")
        void shouldRevokeRefreshTokensBeforeSavingUserEntity() {
            AppUser user = AppUser.builder()
                    .id(USER_ID)
                    .tenantId(TENANT_ID)
                    .email("agent@example.com")
                    .passwordHash("$2a$12$hash")
                    .role(UserRole.AGENT)
                    .active(true)
                    .status(UserStatus.ACTIVE)
                    .passwordResetRequired(false)
                    .deleted(false)
                    .build();
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(refreshTokenRepository.revokeAllByUserId(USER_ID)).thenReturn(1);

            adminUserService.forcePasswordReset(USER_ID);

            InOrder order = inOrder(refreshTokenRepository, appUserRepository);
            order.verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
            order.verify(appUserRepository).save(user);
        }
    }
}
