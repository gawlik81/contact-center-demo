package com.contactcenter.domain.user;

import com.contactcenter.api.user.dto.*;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.tenant.TenantResourceLimitService;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link UserService}.
 *
 * <p>Weryfikuje logikę biznesową: tworzenie, odczyt, aktualizacja,
 * soft delete, skills i zmiana statusu agenta.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserService – CRUD użytkowników i agentów")
class UserServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID     = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private AppUserRepository appUserRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TenantResourceLimitService tenantResourceLimitService;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Ustaw TenantContext dla testów wymagających getUserRole/getUserId
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(OTHER_USER_ID); // różny od USER_ID domyślnie
        TenantContext.setUserRole("SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Tworzenie użytkownika
    // =========================================================================

    @Nested
    @DisplayName("createUser")
    class CreateUserTests {

        @Test
        @DisplayName("powinien utworzyć agenta gdy dane są poprawne")
        void shouldCreateAgentSuccessfully() {
            CreateUserRequest request = new CreateUserRequest(
                    "agent@example.com", "SecretPass1!", "Jan", "Kowalski",
                    UserRole.AGENT, List.of("SALES", "TECH_SUPPORT")
            );

            when(appUserRepository.existsByTenantIdAndEmail(TENANT_ID, "agent@example.com"))
                    .thenReturn(false);
            when(passwordEncoder.encode("SecretPass1!")).thenReturn("$2b$12$hash");

            AppUser savedUser = buildUser(USER_ID, TENANT_ID, "agent@example.com",
                    UserRole.AGENT, UserStatus.ACTIVE, List.of("SALES", "TECH_SUPPORT"));
            when(appUserRepository.save(any(AppUser.class))).thenReturn(savedUser);

            UserResponse result = userService.createUser(request, TENANT_ID);

            assertThat(result.email()).isEqualTo("agent@example.com");
            assertThat(result.role()).isEqualTo(UserRole.AGENT);
            assertThat(result.skills()).containsExactlyInAnyOrder("SALES", "TECH_SUPPORT");

            // Limit agentów powinien być sprawdzony
            verify(tenantResourceLimitService).checkAgentLimit(TENANT_ID);
            verify(appUserRepository).save(any(AppUser.class));
        }

        @Test
        @DisplayName("powinien sprawdzić limit agentów dla roli AGENT")
        void shouldCheckAgentLimitForAgentRole() {
            CreateUserRequest request = new CreateUserRequest(
                    "agent@example.com", "SecretPass1!", null, null,
                    UserRole.AGENT, null
            );
            when(appUserRepository.existsByTenantIdAndEmail(any(), any())).thenReturn(false);
            when(appUserRepository.save(any())).thenReturn(
                    buildUser(USER_ID, TENANT_ID, "agent@example.com",
                            UserRole.AGENT, UserStatus.ACTIVE, List.of())
            );

            userService.createUser(request, TENANT_ID);

            verify(tenantResourceLimitService, times(1)).checkAgentLimit(TENANT_ID);
        }

        @Test
        @DisplayName("nie powinien sprawdzać limitu agentów dla roli SUPERVISOR")
        void shouldNotCheckAgentLimitForSupervisorRole() {
            CreateUserRequest request = new CreateUserRequest(
                    "sup@example.com", "SecretPass1!", null, null,
                    UserRole.SUPERVISOR, null
            );
            when(appUserRepository.existsByTenantIdAndEmail(any(), any())).thenReturn(false);
            when(appUserRepository.save(any())).thenReturn(
                    buildUser(USER_ID, TENANT_ID, "sup@example.com",
                            UserRole.SUPERVISOR, UserStatus.ACTIVE, List.of())
            );

            userService.createUser(request, TENANT_ID);

            verify(tenantResourceLimitService, never()).checkAgentLimit(any());
        }

        @Test
        @DisplayName("powinien rzucić wyjątek gdy email jest zajęty")
        void shouldThrowWhenEmailAlreadyTaken() {
            CreateUserRequest request = new CreateUserRequest(
                    "taken@example.com", "SecretPass1!", null, null,
                    UserRole.AGENT, null
            );
            when(appUserRepository.existsByTenantIdAndEmail(TENANT_ID, "taken@example.com"))
                    .thenReturn(true);

            assertThatThrownBy(() -> userService.createUser(request, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zajęty");

            verify(appUserRepository, never()).save(any());
        }
    }

    // =========================================================================
    // Lista użytkowników
    // =========================================================================

    @Nested
    @DisplayName("listUsers")
    class ListUsersTests {

        @Test
        @DisplayName("powinien zwrócić stronę użytkowników")
        void shouldReturnPageOfUsers() {
            AppUser user = buildUser(USER_ID, TENANT_ID, "agent@example.com",
                    UserRole.AGENT, UserStatus.AVAILABLE, List.of("SALES"));
            PageRequest pageable = PageRequest.of(0, 20);
            Page<AppUser> page = new PageImpl<>(List.of(user), pageable, 1);

            when(appUserRepository.findAllByTenantIdWithFilters(TENANT_ID, null, null, null, null, pageable))
                    .thenReturn(page);

            Page<UserResponse> result = userService.listUsers(TENANT_ID, null, null, null, null, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).email()).isEqualTo("agent@example.com");
        }
    }

    // =========================================================================
    // Odczyt użytkownika
    // =========================================================================

    @Nested
    @DisplayName("getUser")
    class GetUserTests {

        @Test
        @DisplayName("powinien zwrócić użytkownika gdy istnieje")
        void shouldReturnUserWhenExists() {
            AppUser user = buildUser(USER_ID, TENANT_ID, "agent@example.com",
                    UserRole.AGENT, UserStatus.ACTIVE, List.of());

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));

            UserResponse result = userService.getUser(USER_ID, TENANT_ID);

            assertThat(result.id()).isEqualTo(USER_ID);
            assertThat(result.email()).isEqualTo("agent@example.com");
        }

        @Test
        @DisplayName("powinien rzucić EntityNotFoundException gdy użytkownik nie istnieje")
        void shouldThrowWhenUserNotFound() {
            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.getUser(USER_ID, TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

    @Nested
    @DisplayName("updateUser")
    class UpdateUserTests {

        @Test
        @DisplayName("powinien zaktualizować imię, nazwisko i skills")
        void shouldUpdateNameAndSkills() {
            AppUser user = buildUser(USER_ID, TENANT_ID, "agent@example.com",
                    UserRole.AGENT, UserStatus.ACTIVE, new ArrayList<>(List.of("OLD_SKILL")));

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));
            when(appUserRepository.save(any(AppUser.class))).thenReturn(user);

            UpdateUserRequest request = new UpdateUserRequest("Jan", "Kowalski",
                    List.of("SALES", "BILLING"), null);

            UserResponse result = userService.updateUser(USER_ID, request, TENANT_ID);

            assertThat(user.getFirstName()).isEqualTo("Jan");
            assertThat(user.getLastName()).isEqualTo("Kowalski");
            assertThat(user.getSkills()).containsExactlyInAnyOrder("SALES", "BILLING");
        }

        @Test
        @DisplayName("powinien ignorować null w polach PATCH")
        void shouldIgnoreNullFields() {
            AppUser user = buildUser(USER_ID, TENANT_ID, "agent@example.com",
                    UserRole.AGENT, UserStatus.ACTIVE, new ArrayList<>(List.of("EXISTING")));
            user.setFirstName("OriginalFirst");

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));
            when(appUserRepository.save(any(AppUser.class))).thenReturn(user);

            // Wszystkie pola null – nic nie powinno się zmienić
            UpdateUserRequest request = new UpdateUserRequest(null, null, null, null);

            userService.updateUser(USER_ID, request, TENANT_ID);

            assertThat(user.getFirstName()).isEqualTo("OriginalFirst");
            assertThat(user.getSkills()).containsExactly("EXISTING");
        }
    }

    // =========================================================================
    // Soft delete
    // =========================================================================

    @Nested
    @DisplayName("deleteUser")
    class DeleteUserTests {

        @Test
        @DisplayName("powinien wykonać soft delete agenta bez aktywnych kontaktów")
        void shouldSoftDeleteAgentWithoutActiveContacts() {
            AppUser user = buildUser(USER_ID, TENANT_ID, "agent@example.com",
                    UserRole.AGENT, UserStatus.ACTIVE, List.of());

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));
            when(appUserRepository.existsActiveContactsByUserId(USER_ID, TENANT_ID))
                    .thenReturn(false);
            when(appUserRepository.softDeleteUser(USER_ID, TENANT_ID)).thenReturn(1);

            assertThatCode(() -> userService.deleteUser(USER_ID, TENANT_ID, OTHER_USER_ID))
                    .doesNotThrowAnyException();

            verify(appUserRepository).softDeleteUser(USER_ID, TENANT_ID);
        }

        @Test
        @DisplayName("powinien rzucić ConflictException gdy agent ma aktywne kontakty")
        void shouldThrowConflictWhenAgentHasActiveContacts() {
            AppUser user = buildUser(USER_ID, TENANT_ID, "agent@example.com",
                    UserRole.AGENT, UserStatus.BUSY, List.of());

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));
            when(appUserRepository.existsActiveContactsByUserId(USER_ID, TENANT_ID))
                    .thenReturn(true);

            assertThatThrownBy(() -> userService.deleteUser(USER_ID, TENANT_ID, OTHER_USER_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("aktywnymi kontaktami");

            verify(appUserRepository, never()).softDeleteUser(any(), any());
        }

        @Test
        @DisplayName("powinien rzucić wyjątek gdy próbuje usunąć siebie")
        void shouldThrowWhenDeletingSelf() {
            assertThatThrownBy(() -> userService.deleteUser(USER_ID, TENANT_ID, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("własnego konta");

            verify(appUserRepository, never()).findByIdAndTenantIdAndDeletedFalse(any(), any());
        }

        @Test
        @DisplayName("nie powinien sprawdzać aktywnych kontaktów dla SUPERVISORA")
        void shouldNotCheckContactsForSupervisor() {
            AppUser supervisor = buildUser(USER_ID, TENANT_ID, "sup@example.com",
                    UserRole.SUPERVISOR, UserStatus.ACTIVE, List.of());

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(supervisor));
            when(appUserRepository.softDeleteUser(USER_ID, TENANT_ID)).thenReturn(1);

            userService.deleteUser(USER_ID, TENANT_ID, OTHER_USER_ID);

            verify(appUserRepository, never()).existsActiveContactsByUserId(any(), any());
        }
    }

    // =========================================================================
    // Skills
    // =========================================================================

    @Nested
    @DisplayName("listSkills")
    class ListSkillsTests {

        @Test
        @DisplayName("powinien zwrócić unikalne skills tenanta")
        void shouldReturnDistinctSkills() {
            when(appUserRepository.findAllDistinctSkillsByTenantId(TENANT_ID))
                    .thenReturn(List.of("BILLING", "SALES", "TECH_SUPPORT"));

            List<String> result = userService.listSkills(TENANT_ID);

            assertThat(result).containsExactly("BILLING", "SALES", "TECH_SUPPORT");
        }

        @Test
        @DisplayName("powinien zwrócić pustą listę gdy brak użytkowników ze skills")
        void shouldReturnEmptyListWhenNoSkills() {
            when(appUserRepository.findAllDistinctSkillsByTenantId(TENANT_ID))
                    .thenReturn(List.of());

            List<String> result = userService.listSkills(TENANT_ID);

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // Zmiana statusu
    // =========================================================================

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("powinien zmienić status i opublikować event RabbitMQ")
        void shouldChangeStatusAndPublishEvent() {
            AppUser user = buildUser(USER_ID, TENANT_ID, "agent@example.com",
                    UserRole.AGENT, UserStatus.AVAILABLE, List.of());

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));
            when(appUserRepository.save(any(AppUser.class))).thenReturn(user);

            UpdateStatusRequest request = new UpdateStatusRequest(UserStatus.BUSY);

            userService.updateStatus(USER_ID, request, TENANT_ID);

            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS),
                    eq("agent.status.changed"),
                    any(AgentStatusChangedEvent.class)
            );
            // Po issue #15: Redis przechowuje Map z pełnymi danymi sesji (nie tylko status)
            verify(valueOperations).set(
                    eq("session:agent:" + USER_ID),
                    argThat(val -> {
                        if (!(val instanceof java.util.Map<?, ?> map)) return false;
                        return "BUSY".equals(map.get("status"))
                                && USER_ID.toString().equals(map.get("userId"))
                                && TENANT_ID.toString().equals(map.get("tenantId"));
                    }),
                    any()
            );
        }

        @Test
        @DisplayName("powinien rzucić wyjątek dla niedozwolonego statusu ACTIVE")
        void shouldThrowForInvalidStatusACTIVE() {
            UpdateStatusRequest request = new UpdateStatusRequest(UserStatus.ACTIVE);

            assertThatThrownBy(() -> userService.updateStatus(USER_ID, request, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Niedozwolony status");
        }

        @Test
        @DisplayName("agent powinien rzucić AccessDeniedException przy próbie zmiany cudzego statusu")
        void shouldThrowWhenAgentChangesOtherAgentStatus() {
            TenantContext.setUserRole("AGENT");
            TenantContext.setUserId(OTHER_USER_ID);

            UpdateStatusRequest request = new UpdateStatusRequest(UserStatus.BUSY);

            // USER_ID != OTHER_USER_ID → AccessDeniedException
            assertThatThrownBy(() -> userService.updateStatus(USER_ID, request, TENANT_ID))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("supervisor powinien móc zmienić status innego agenta")
        void supervisorShouldChangeOtherAgentStatus() {
            TenantContext.setUserRole("SUPERVISOR");
            TenantContext.setUserId(OTHER_USER_ID);

            AppUser user = buildUser(USER_ID, TENANT_ID, "agent@example.com",
                    UserRole.AGENT, UserStatus.AVAILABLE, List.of());

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));
            when(appUserRepository.save(any(AppUser.class))).thenReturn(user);

            UpdateStatusRequest request = new UpdateStatusRequest(UserStatus.BREAK);

            assertThatCode(() -> userService.updateStatus(USER_ID, request, TENANT_ID))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private AppUser buildUser(UUID id, UUID tenantId, String email,
                               UserRole role, UserStatus status, List<String> skills) {
        return AppUser.builder()
                .id(id)
                .tenantId(tenantId)
                .email(email)
                .passwordHash("$2b$12$hash")
                .role(role)
                .status(status)
                .active(true)
                .mfaEnabled(false)
                .passwordResetRequired(false)
                .deleted(false)
                .skills(new ArrayList<>(skills))
                .build();
    }
}
