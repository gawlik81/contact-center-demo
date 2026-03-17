package com.contactcenter.domain.service;

import com.contactcenter.api.user.dto.*;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.AppUser.UserRole;
import com.contactcenter.domain.model.AppUser.UserStatus;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający użytkownikami i agentami.
 *
 * <p>Implementuje BE-008: CRUD użytkowników z obsługą skills (JSONB),
 * soft delete, zmiana statusu agenta z propagacją przez RabbitMQ i Redis.
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Każdy odczyt i zapis filtruje po tenantId z {@link com.contactcenter.security.TenantContext}</li>
 *   <li>Hasła hashowane BCrypt(12) przed zapisem – nigdy nie przechowujemy plain text</li>
 *   <li>Pola wrażliwe (passwordHash, mfaSecret) nigdy nie trafiają do DTO</li>
 *   <li>Operacje modyfikujące logują zdarzenia audytowe przez {@link Audited}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String REDIS_AGENT_STATUS_KEY = "session:agent:%s";
    private static final Duration REDIS_AGENT_STATUS_TTL = Duration.ofHours(8);

    /** Statusy dozwolone dla endpointu PATCH /users/{id}/status */
    private static final Set<UserStatus> ALLOWED_AGENT_STATUSES = Set.of(
            UserStatus.AVAILABLE, UserStatus.BUSY, UserStatus.BREAK, UserStatus.AFTER_CONTACT
    );

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantResourceLimitService tenantResourceLimitService;
    private final RabbitTemplate rabbitTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    // =========================================================================
    // Tworzenie użytkownika
    // =========================================================================

    /**
     * Tworzy nowego użytkownika w tenancie.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Sprawdza limit agentów (gdy role=AGENT) przez {@link TenantResourceLimitService}</li>
     *   <li>Sprawdza unikalność email w tenancie</li>
     *   <li>Hashuje hasło BCrypt(12)</li>
     *   <li>Zapisuje encję z domyślnym statusem ACTIVE</li>
     * </ol>
     *
     * @param request  dane nowego użytkownika
     * @param tenantId UUID tenanta z TenantContext
     * @return DTO nowo utworzonego użytkownika
     * @throws com.contactcenter.domain.exception.ResourceLimitExceededException HTTP 422 gdy limit agentów przekroczony
     * @throws IllegalArgumentException HTTP 422 gdy email już zajęty w tenancie
     */
    @Transactional
    @Audited(action = "USER_CREATED", entityType = "USER")
    public UserResponse createUser(CreateUserRequest request, UUID tenantId) {
        // Sprawdź limit agentów (tylko dla roli AGENT)
        if (UserRole.AGENT.equals(request.role())) {
            tenantResourceLimitService.checkAgentLimit(tenantId);
        }

        // Walidacja unikalności email w tenancie
        if (appUserRepository.existsByTenantIdAndEmail(tenantId, request.email())) {
            throw new IllegalArgumentException(
                    "Email '" + request.email() + "' jest już zajęty w tym tenancie");
        }

        AppUser user = AppUser.builder()
                .tenantId(tenantId)
                .email(request.email().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(request.password()))
                .firstName(request.firstName())
                .lastName(request.lastName())
                .role(request.role())
                .status(UserStatus.ACTIVE)
                .active(true)
                .mfaEnabled(false)
                .passwordResetRequired(false)
                .deleted(false)
                .skills(request.skills() != null ? new ArrayList<>(request.skills()) : new ArrayList<>())
                .build();

        AppUser saved = appUserRepository.save(user);
        log.info("[UserService] Utworzono użytkownika: userId={}, tenantId={}, role={}",
                saved.getId(), tenantId, saved.getRole());

        return UserResponse.from(saved);
    }

    // =========================================================================
    // Lista użytkowników
    // =========================================================================

    /**
     * Lista użytkowników tenanta z paginacją.
     *
     * @param tenantId UUID tenanta
     * @param pageable parametry stronicowania
     * @return strona DTO użytkowników
     */
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(UUID tenantId, Pageable pageable) {
        return appUserRepository
                .findAllByTenantIdAndDeletedFalse(tenantId, pageable)
                .map(UserResponse::from);
    }

    // =========================================================================
    // Odczyt użytkownika
    // =========================================================================

    /**
     * Pobiera użytkownika po ID w ramach tenanta.
     *
     * @param userId   UUID użytkownika
     * @param tenantId UUID tenanta
     * @return DTO użytkownika
     * @throws EntityNotFoundException HTTP 422 gdy użytkownik nie istnieje
     */
    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId, UUID tenantId) {
        AppUser user = findUserOrThrow(userId, tenantId);
        return UserResponse.from(user);
    }

    // =========================================================================
    // Aktualizacja użytkownika
    // =========================================================================

    /**
     * Aktualizuje dane użytkownika (PATCH semantics).
     *
     * <p>Pola null w żądaniu są ignorowane – wartości pozostają bez zmian.
     * Nie pozwala na zmianę email, roli ani hasła (osobne endpointy).
     *
     * @param userId   UUID użytkownika
     * @param request  dane do aktualizacji (null = bez zmiany)
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego użytkownika
     * @throws EntityNotFoundException HTTP 422 gdy użytkownik nie istnieje
     */
    @Transactional
    @Audited(action = "USER_UPDATED", entityType = "USER", captureOldValue = true,
             fetchOldValueMethod = "getUser", entityIdParamIndex = 0)
    public UserResponse updateUser(UUID userId, UpdateUserRequest request, UUID tenantId) {
        AppUser user = findUserOrThrow(userId, tenantId);

        if (request.firstName() != null) {
            user.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            user.setLastName(request.lastName());
        }
        if (request.skills() != null) {
            user.setSkills(new ArrayList<>(request.skills()));
        }
        if (request.mfaEnabled() != null && !request.mfaEnabled()) {
            // Pozwalamy wyłączyć MFA (włączenie przez /auth/mfa/setup)
            user.setMfaEnabled(false);
        }

        AppUser saved = appUserRepository.save(user);
        log.info("[UserService] Zaktualizowano użytkownika: userId={}, tenantId={}",
                saved.getId(), tenantId);

        return UserResponse.from(saved);
    }

    // =========================================================================
    // Soft delete
    // =========================================================================

    /**
     * Soft delete użytkownika (is_deleted=true, is_active=false).
     *
     * <p>Reguły:
     * <ul>
     *   <li>Nie można usunąć siebie samego</li>
     *   <li>Nie można usunąć agenta z aktywnymi kontaktami (HTTP 409)</li>
     * </ul>
     *
     * @param userId          UUID użytkownika do usunięcia
     * @param tenantId        UUID tenanta
     * @param requestingUserId UUID użytkownika wykonującego żądanie
     * @throws ConflictException       HTTP 409 gdy agent ma aktywne kontakty
     * @throws EntityNotFoundException HTTP 422 gdy użytkownik nie istnieje
     * @throws IllegalArgumentException HTTP 422 gdy próbuje usunąć siebie
     */
    @Transactional
    @Audited(action = "USER_DELETED", entityType = "USER", captureOldValue = true,
             fetchOldValueMethod = "getUser", entityIdParamIndex = 0)
    public void deleteUser(UUID userId, UUID tenantId, UUID requestingUserId) {
        // Nie można usunąć siebie
        if (userId.equals(requestingUserId)) {
            throw new IllegalArgumentException("Nie można usunąć własnego konta");
        }

        // Sprawdź czy użytkownik istnieje
        AppUser user = findUserOrThrow(userId, tenantId);

        // Sprawdź aktywne kontakty (dla agentów)
        if (UserRole.AGENT.equals(user.getRole())) {
            boolean hasActiveContacts = appUserRepository.existsActiveContactsByUserId(userId, tenantId);
            if (hasActiveContacts) {
                throw new ConflictException(
                        "Nie można usunąć agenta z aktywnymi kontaktami. " +
                        "Poczekaj na zakończenie obsługi lub przekieruj kontakty.");
            }
        }

        int updated = appUserRepository.softDeleteUser(userId, tenantId);
        if (updated == 0) {
            throw new EntityNotFoundException("Użytkownik nie istnieje: " + userId);
        }

        log.info("[UserService] Soft delete użytkownika: userId={}, tenantId={}, deletedBy={}",
                userId, tenantId, requestingUserId);
    }

    // =========================================================================
    // Skills
    // =========================================================================

    /**
     * Lista unikalnych skills wszystkich agentów w tenancie.
     *
     * @param tenantId UUID tenanta
     * @return posortowana lista unikalnych skill tagów
     */
    @Transactional(readOnly = true)
    public List<String> listSkills(UUID tenantId) {
        return appUserRepository.findAllDistinctSkillsByTenantId(tenantId);
    }

    // =========================================================================
    // Zmiana statusu agenta
    // =========================================================================

    /**
     * Zmienia status agenta i propaguje event przez RabbitMQ i Redis.
     *
     * <p>Dozwolone statusy: AVAILABLE, BUSY, BREAK, AFTER_CONTACT.
     * Statusy ACTIVE/INACTIVE zarządzane przez CRUD (nie przez ten endpoint).
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Walidacja dozwolonego statusu</li>
     *   <li>Zapis nowego statusu w bazie</li>
     *   <li>Zapis statusu w Redis (klucz session:agent:{userId}, TTL 8h)</li>
     *   <li>Publikacja eventu na RabbitMQ (cc.events, routing key: agent.status.changed)</li>
     * </ol>
     *
     * @param userId   UUID agenta
     * @param request  nowy status
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego użytkownika
     * @throws IllegalArgumentException HTTP 422 gdy status jest niedozwolony (ACTIVE/INACTIVE)
     * @throws EntityNotFoundException  HTTP 422 gdy użytkownik nie istnieje
     */
    @Transactional
    public UserResponse updateStatus(UUID userId, UpdateStatusRequest request, UUID tenantId) {
        // Walidacja dozwolonych statusów
        if (!ALLOWED_AGENT_STATUSES.contains(request.status())) {
            throw new IllegalArgumentException(
                    "Niedozwolony status: " + request.status() +
                    ". Dozwolone: AVAILABLE, BUSY, BREAK, AFTER_CONTACT");
        }

        // Agent może zmieniać tylko własny status
        String requestingRole = TenantContext.getUserRole();
        UUID requestingUserId = TenantContext.getUserId();
        if ("AGENT".equalsIgnoreCase(requestingRole) && !userId.equals(requestingUserId)) {
            throw new AccessDeniedException("Agent może zmieniać tylko własny status");
        }

        AppUser user = findUserOrThrow(userId, tenantId);
        UserStatus oldStatus = user.getStatus();

        user.setStatus(request.status());
        AppUser saved = appUserRepository.save(user);

        // Zapis statusu w Redis jako structured Map (wymagane przez AdminMetricsService
        // do zliczania agentów online per tenant – musi zawierać tenantId).
        // Poprzednia implementacja zapisywała plain String (np. "AVAILABLE") co uniemożliwiało
        // filtrowanie po tenantId w countOnlineAgentsForTenant → zawsze zwracało 0.
        String redisKey = String.format(REDIS_AGENT_STATUS_KEY, userId);
        try {
            Map<String, String> sessionData = Map.of(
                    "tenantId", tenantId.toString(),
                    "userId", userId.toString(),
                    "status", request.status().name()
            );
            redisTemplate.opsForValue().set(redisKey, sessionData, REDIS_AGENT_STATUS_TTL);
            log.debug("[UserService] Status agenta zapisany w Redis: key={}, status={}, tenantId={}",
                    redisKey, request.status(), tenantId);
        } catch (Exception e) {
            // Redis failure nie blokuje operacji – logujemy warning
            log.warn("[UserService] Błąd zapisu statusu w Redis: key={}, error={}",
                    redisKey, e.getMessage());
        }

        // Publikacja eventu RabbitMQ
        publishStatusChangedEvent(userId, tenantId, oldStatus, request.status());

        log.info("[UserService] Status agenta zmieniony: userId={}, {} -> {}",
                userId, oldStatus, request.status());

        return UserResponse.from(saved);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private AppUser findUserOrThrow(UUID userId, UUID tenantId) {
        return appUserRepository
                .findByIdAndTenantIdAndDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Użytkownik nie istnieje lub nie należy do tego tenanta: " + userId));
    }

    private void publishStatusChangedEvent(UUID userId, UUID tenantId,
                                            UserStatus oldStatus, UserStatus newStatus) {
        AgentStatusChangedEvent event = AgentStatusChangedEvent.of(userId, tenantId, oldStatus, newStatus);
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_EVENTS,
                    "agent.status.changed",
                    event
            );
            log.debug("[UserService] Event agent.status.changed opublikowany: userId={}, {} -> {}",
                    userId, oldStatus, newStatus);
        } catch (Exception e) {
            // Failure RabbitMQ nie blokuje operacji HTTP – logujemy error
            log.error("[UserService] Błąd publikacji eventu agent.status.changed: userId={}, error={}",
                    userId, e.getMessage());
        }
    }
}
