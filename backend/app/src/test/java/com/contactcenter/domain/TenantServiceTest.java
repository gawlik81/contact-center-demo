package com.contactcenter.domain;

import com.contactcenter.api.tenant.dto.CreateTenantRequest;
import com.contactcenter.api.tenant.dto.TenantFilterParams;
import com.contactcenter.api.tenant.dto.TenantResourceLimitsDto;
import com.contactcenter.api.tenant.dto.TenantResponse;
import com.contactcenter.api.tenant.dto.UpdateTenantRequest;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.AppUser.UserRole;
import com.contactcenter.domain.model.AppUser.UserStatus;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.model.Tenant.TenantStatus;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.domain.service.TenantService;
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
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link TenantService}.
 *
 * <p>Weryfikuje logikę biznesową bez dostępu do bazy danych (mock repository).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantService – zarządzanie tenantami")
class TenantServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @InjectMocks
    private TenantService tenantService;

    private Tenant activeTenant;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = new HashMap<>();
        config.put("max_agents", 100);
        config.put("max_queues", 50);
        config.put("max_campaigns", 20);
        config.put("recording_retention_days", 90);
        config.put("timezone", "Europe/Warsaw");

        activeTenant = Tenant.builder()
                .id(TENANT_ID)
                .name("Acme Corporation")
                .status(TenantStatus.ACTIVE)
                .config(config)
                .createdAt(Instant.now())
                .build();
    }

    // =========================================================================
    // Tworzenie tenanta
    // =========================================================================

    @Nested
    @DisplayName("createTenant()")
    class CreateTenant {

        @Test
        @DisplayName("powinien utworzyć tenanta z domyślnymi limitami gdy limits == null")
        void shouldCreateTenantWithDefaultLimitsWhenLimitsIsNull() {
            // given
            CreateTenantRequest request = new CreateTenantRequest("New Tenant", null);
            when(tenantRepository.existsByNameIgnoreCase("New Tenant")).thenReturn(false);
            when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
                Tenant t = inv.getArgument(0);
                t = Tenant.builder()
                        .id(UUID.randomUUID())
                        .name(t.getName())
                        .status(t.getStatus())
                        .config(t.getConfig())
                        .createdAt(t.getCreatedAt())
                        .build();
                return t;
            });

            // when
            TenantResponse response = tenantService.createTenant(request);

            // then
            assertThat(response.name()).isEqualTo("New Tenant");
            assertThat(response.status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(response.limits().maxAgents()).isEqualTo(100);
            assertThat(response.limits().maxQueues()).isEqualTo(50);
            assertThat(response.limits().maxCampaigns()).isEqualTo(20);
            verify(tenantRepository).save(any(Tenant.class));
        }

        @Test
        @DisplayName("powinien utworzyć tenanta z podanymi limitami")
        void shouldCreateTenantWithCustomLimits() {
            // given
            TenantResourceLimitsDto limits = new TenantResourceLimitsDto(50, 25, 10, 30, "Europe/London");
            CreateTenantRequest request = new CreateTenantRequest("Custom Tenant", limits);
            when(tenantRepository.existsByNameIgnoreCase("Custom Tenant")).thenReturn(false);
            when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
                Tenant t = inv.getArgument(0);
                t = Tenant.builder()
                        .id(UUID.randomUUID())
                        .name(t.getName())
                        .status(t.getStatus())
                        .config(t.getConfig())
                        .createdAt(t.getCreatedAt())
                        .build();
                return t;
            });

            // when
            TenantResponse response = tenantService.createTenant(request);

            // then
            assertThat(response.limits().maxAgents()).isEqualTo(50);
            assertThat(response.limits().maxQueues()).isEqualTo(25);
            assertThat(response.limits().maxCampaigns()).isEqualTo(10);
            assertThat(response.limits().recordingRetentionDays()).isEqualTo(30);
            assertThat(response.limits().timezone()).isEqualTo("Europe/London");
        }

        @Test
        @DisplayName("powinien rzucić IllegalArgumentException gdy nazwa jest już zajęta")
        void shouldThrowExceptionWhenNameAlreadyExists() {
            // given
            CreateTenantRequest request = new CreateTenantRequest("Existing Tenant", null);
            when(tenantRepository.existsByNameIgnoreCase("Existing Tenant")).thenReturn(true);

            // when / then
            assertThatThrownBy(() -> tenantService.createTenant(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Existing Tenant");

            verify(tenantRepository, never()).save(any());
        }
    }

    // =========================================================================
    // Pobieranie tenanta
    // =========================================================================

    @Nested
    @DisplayName("getTenant()")
    class GetTenant {

        @Test
        @DisplayName("powinien zwrócić DTO gdy tenant istnieje")
        void shouldReturnTenantResponseWhenTenantExists() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant));

            // when
            TenantResponse response = tenantService.getTenant(TENANT_ID);

            // then
            assertThat(response.id()).isEqualTo(TENANT_ID);
            assertThat(response.name()).isEqualTo("Acme Corporation");
            assertThat(response.status()).isEqualTo(TenantStatus.ACTIVE);
        }

        @Test
        @DisplayName("powinien rzucić EntityNotFoundException gdy tenant nie istnieje")
        void shouldThrowEntityNotFoundExceptionWhenTenantNotFound() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> tenantService.getTenant(TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(TENANT_ID.toString());
        }
    }

    // =========================================================================
    // Lista tenantów
    // =========================================================================

    @Nested
    @DisplayName("listTenants()")
    class ListTenants {

        @Test
        @DisplayName("powinien zwrócić wszystkich tenantów gdy filtry są null")
        void shouldReturnAllTenantsWhenFiltersAreNull() {
            // given
            when(tenantRepository.findAllByOptionalFilters(null, null))
                    .thenReturn(List.of(activeTenant));

            // when
            List<TenantResponse> result = tenantService.listTenants(null);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Acme Corporation");
            verify(tenantRepository).findAllByOptionalFilters(null, null);
        }

        @Test
        @DisplayName("powinien filtrować po fragmencie nazwy (case-insensitive)")
        void shouldFilterByNameFragment() {
            // given
            TenantFilterParams filters = new TenantFilterParams("acme", null);
            when(tenantRepository.findAllByOptionalFilters("acme", null))
                    .thenReturn(List.of(activeTenant));

            // when
            List<TenantResponse> result = tenantService.listTenants(filters);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Acme Corporation");
            verify(tenantRepository).findAllByOptionalFilters("acme", null);
        }

        @Test
        @DisplayName("powinien filtrować po statusie")
        void shouldFilterByStatus() {
            // given
            TenantFilterParams filters = new TenantFilterParams(null, TenantStatus.ACTIVE);
            // Repozytorium przyjmuje String (nazwa enum) – serwis konwertuje przez .name()
            when(tenantRepository.findAllByOptionalFilters(null, "ACTIVE"))
                    .thenReturn(List.of(activeTenant));

            // when
            List<TenantResponse> result = tenantService.listTenants(filters);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).status()).isEqualTo(TenantStatus.ACTIVE);
            verify(tenantRepository).findAllByOptionalFilters(null, "ACTIVE");
        }

        @Test
        @DisplayName("powinien filtrować jednocześnie po nazwie i statusie")
        void shouldFilterByNameAndStatus() {
            // given
            TenantFilterParams filters = new TenantFilterParams("acme", TenantStatus.ACTIVE);
            when(tenantRepository.findAllByOptionalFilters("acme", "ACTIVE"))
                    .thenReturn(List.of(activeTenant));

            // when
            List<TenantResponse> result = tenantService.listTenants(filters);

            // then
            assertThat(result).hasSize(1);
            verify(tenantRepository).findAllByOptionalFilters("acme", "ACTIVE");
        }

        @Test
        @DisplayName("powinien traktować pustą nazwę jako brak filtra")
        void shouldTreatBlankNameAsNoFilter() {
            // given – name jest pustym stringiem, powinien być traktowany jak null
            TenantFilterParams filters = new TenantFilterParams("   ", null);
            when(tenantRepository.findAllByOptionalFilters(null, null))
                    .thenReturn(List.of(activeTenant));

            // when
            List<TenantResponse> result = tenantService.listTenants(filters);

            // then
            verify(tenantRepository).findAllByOptionalFilters(null, null);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("powinien zwrócić pustą listę gdy żaden tenant nie pasuje do filtrów")
        void shouldReturnEmptyListWhenNoTenantsMatchFilters() {
            // given
            TenantFilterParams filters = new TenantFilterParams("nonexistent", TenantStatus.SUSPENDED);
            when(tenantRepository.findAllByOptionalFilters("nonexistent", "SUSPENDED"))
                    .thenReturn(List.of());

            // when
            List<TenantResponse> result = tenantService.listTenants(filters);

            // then
            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // Dezaktywacja tenanta
    // =========================================================================

    @Nested
    @DisplayName("deactivateTenant()")
    class DeactivateTenant {

        @Test
        @DisplayName("powinien dezaktywować tenanta i zablokować użytkowników")
        void shouldDeactivateTenantAndDisableUsers() {
            // given
            AppUser activeUser = AppUser.builder()
                    .id(USER_ID)
                    .tenantId(TENANT_ID)
                    .email("agent@acme.com")
                    .passwordHash("$2a$12$hash")
                    .role(UserRole.AGENT)
                    .active(true)
                    .mfaEnabled(false)
                    .passwordResetRequired(false)
                    .status(UserStatus.ACTIVE)
                    .build();

            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant));
            when(tenantRepository.save(any(Tenant.class))).thenReturn(activeTenant);
            when(appUserRepository.findAll()).thenReturn(List.of(activeUser));
            when(appUserRepository.save(any(AppUser.class))).thenReturn(activeUser);

            // when
            tenantService.deactivateTenant(TENANT_ID);

            // then
            verify(tenantRepository).save(argThat(t -> t.getStatus() == TenantStatus.INACTIVE));
            verify(appUserRepository).save(argThat(u -> !u.isActive()));
        }

        @Test
        @DisplayName("powinien pominąć dezaktywację gdy tenant jest już nieaktywny")
        void shouldSkipDeactivationWhenTenantAlreadyInactive() {
            // given
            activeTenant.setStatus(TenantStatus.INACTIVE);
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant));

            // when
            tenantService.deactivateTenant(TENANT_ID);

            // then – nie zapisuje tenanta ani użytkowników
            verify(tenantRepository, never()).save(any());
            verify(appUserRepository, never()).findAll();
        }

        @Test
        @DisplayName("powinien rzucić EntityNotFoundException gdy tenant nie istnieje")
        void shouldThrowEntityNotFoundExceptionWhenTenantNotFound() {
            // given
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> tenantService.deactivateTenant(TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // =========================================================================
    // Aktualizacja tenanta
    // =========================================================================

    @Nested
    @DisplayName("updateTenant()")
    class UpdateTenant {

        @Test
        @DisplayName("powinien zaktualizować nazwę tenanta gdy nowa nazwa jest unikalna")
        void shouldUpdateTenantNameWhenUnique() {
            // given
            UpdateTenantRequest request = new UpdateTenantRequest("New Name", null, null);
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant));
            when(tenantRepository.existsByNameIgnoreCaseAndIdNot("New Name", TENANT_ID)).thenReturn(false);
            when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            TenantResponse response = tenantService.updateTenant(TENANT_ID, request);

            // then
            assertThat(response.name()).isEqualTo("New Name");
        }

        @Test
        @DisplayName("powinien rzucić IllegalArgumentException gdy nowa nazwa jest zajęta")
        void shouldThrowExceptionWhenNewNameAlreadyTaken() {
            // given
            UpdateTenantRequest request = new UpdateTenantRequest("Existing Name", null, null);
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant));
            when(tenantRepository.existsByNameIgnoreCaseAndIdNot("Existing Name", TENANT_ID)).thenReturn(true);

            // when / then
            assertThatThrownBy(() -> tenantService.updateTenant(TENANT_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Existing Name");
        }

        @Test
        @DisplayName("powinien zaktualizować limity zasobów")
        void shouldUpdateResourceLimits() {
            // given
            TenantResourceLimitsDto newLimits = new TenantResourceLimitsDto(200, 100, 40, null, null);
            UpdateTenantRequest request = new UpdateTenantRequest(null, null, newLimits);
            when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(activeTenant));
            when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            TenantResponse response = tenantService.updateTenant(TENANT_ID, request);

            // then
            assertThat(response.limits().maxAgents()).isEqualTo(200);
            assertThat(response.limits().maxQueues()).isEqualTo(100);
            assertThat(response.limits().maxCampaigns()).isEqualTo(40);
        }
    }

    // =========================================================================
    // Sprawdzenie dostępności nazwy
    // =========================================================================

    @Nested
    @DisplayName("isNameAvailable()")
    class IsNameAvailable {

        @Test
        @DisplayName("powinien zwrócić true gdy nazwa jest wolna")
        void shouldReturnTrueWhenNameFree() {
            when(tenantRepository.existsByNameIgnoreCase("Free Name")).thenReturn(false);
            assertThat(tenantService.isNameAvailable("Free Name")).isTrue();
        }

        @Test
        @DisplayName("powinien zwrócić false gdy nazwa jest zajęta")
        void shouldReturnFalseWhenNameTaken() {
            when(tenantRepository.existsByNameIgnoreCase("Taken Name")).thenReturn(true);
            assertThat(tenantService.isNameAvailable("Taken Name")).isFalse();
        }

        @Test
        @DisplayName("powinien zwrócić true gdy nazwa należy do tego samego tenanta (edycja)")
        void shouldReturnTrueWhenNameBelongsToSameTenant() {
            when(tenantRepository.existsByNameIgnoreCaseAndIdNot("Acme Corporation", TENANT_ID))
                    .thenReturn(false);
            assertThat(tenantService.isNameAvailable("Acme Corporation", TENANT_ID)).isTrue();
        }
    }
}
