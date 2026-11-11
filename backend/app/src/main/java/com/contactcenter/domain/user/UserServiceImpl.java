package com.contactcenter.domain.user;

import com.contactcenter.domain.exception.BusinessValidationException;
import com.contactcenter.api.user.dto.*;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.tenant.TenantResourceLimitService;
import com.contactcenter.domain.contact.ContactService;
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
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class UserServiceImpl implements UserService {

    private static final String REDIS_AGENT_STATUS_KEY = "session:agent:%s";
    private static final Duration REDIS_AGENT_STATUS_TTL = Duration.ofHours(8);

    /** BE-210: statuses that claim "agent is at the desk" and therefore need a live presence. */
    private static final Set<UserStatus> PRESENCE_TRACKED_STATUSES = Set.of(
            UserStatus.AVAILABLE, UserStatus.AFTER_CONTACT, UserStatus.BREAK, UserStatus.BUSY
    );

    /** Statusy dozwolone dla endpointu PATCH /users/{id}/status */
    private static final Set<UserStatus> ALLOWED_AGENT_STATUSES = Set.of(
            UserStatus.AVAILABLE, UserStatus.BUSY, UserStatus.BREAK, UserStatus.AFTER_CONTACT, UserStatus.OFFLINE
    );

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantResourceLimitService tenantResourceLimitService;
    private final RabbitTemplate rabbitTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ContactService contactService;
    private final ApplicationEventPublisher eventPublisher;

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
     * @throws BusinessValidationException HTTP 422 gdy email już zajęty w tenancie
     */
    @Transactional
    @Audited(action = "USER_CREATED", entityType = "USER", entityIdResultAccessor = "id")
    @Override
    public UserResponse createUser(CreateUserRequest request, UUID tenantId) {
        // SUPER_ADMIN nie może powstać przez ten endpoint (ani żaden inny poza bootstrapem
        // przy starcie systemu – patrz SuperAdminBootstrapRunner). Defense-in-depth: rola
        // tenant-scoped nie może mieć tenantId (naruszyłoby chk_super_admin_tenant_invariant),
        // a UI świadomie nie udostępnia tej opcji.
        if (UserRole.SUPER_ADMIN.equals(request.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Rola SUPER_ADMIN nie może zostać przypisana przez ten endpoint – " +
                    "konto SUPER_ADMIN jest tworzone wyłącznie przez bootstrap systemu");
        }

        // Sprawdź limit agentów (tylko dla roli AGENT)
        if (UserRole.AGENT.equals(request.role())) {
            tenantResourceLimitService.checkAgentLimit(tenantId);
        }

        // Walidacja unikalności email w tenancie
        if (appUserRepository.existsByTenantIdAndEmail(tenantId, request.email())) {
            throw new BusinessValidationException(
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
     * Lista użytkowników tenanta z paginacją i opcjonalnym filtrowaniem.
     *
     * @param tenantId UUID tenanta
     * @param status   opcjonalny filtr statusu (null = brak filtru)
     * @param skill    opcjonalny filtr skill (null = brak filtru)
     * @param role     opcjonalny filtr roli (null = brak filtru)
     * @param search   opcjonalna fraza wyszukiwania w imieniu, nazwisku, emailu (null = brak filtru)
     * @param pageable parametry stronicowania
     * @return strona DTO użytkowników
     */
    @Transactional(readOnly = true)
    @Override
    public Page<UserResponse> listUsers(UUID tenantId, String status, String skill, String role, String search, Pageable pageable) {
        String statusParam = blank(status) ? null : status.trim();
        String skillParam  = blank(skill)  ? null : skill.trim();
        String roleParam   = blank(role)   ? null : role.trim();
        String searchParam = blank(search) ? null : search.trim();
        return appUserRepository
                .findAllByTenantIdWithFilters(tenantId, statusParam, skillParam, roleParam, searchParam, pageable)
                .map(UserResponse::from);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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
    @Override
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
     * <p>SUPERVISOR stracił zarządzanie użytkownikami (refaktor ról) – może wyłącznie
     * modyfikować pole {@code skills} agentów (rola AGENT). Próba zmiany innego pola
     * lub edycji użytkownika z inną rolą jest odrzucana. ADMIN nie ma tego ograniczenia.
     *
     * @param userId   UUID użytkownika
     * @param request  dane do aktualizacji (null = bez zmiany)
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego użytkownika
     * @throws EntityNotFoundException HTTP 422 gdy użytkownik nie istnieje
     * @throws AccessDeniedException   HTTP 403 gdy SUPERVISOR próbuje zmienić coś poza
     *                                 skills agenta
     */
    @Transactional
    @Audited(action = "USER_UPDATED", entityType = "USER", captureOldValue = true,
             fetchOldValueMethod = "getUser", entityIdParamIndex = 0)
    @Override
    public UserResponse updateUser(UUID userId, UpdateUserRequest request, UUID tenantId) {
        AppUser user = findUserOrThrow(userId, tenantId);

        if ("SUPERVISOR".equalsIgnoreCase(TenantContext.getUserRole())) {
            if (!UserRole.AGENT.equals(user.getRole())) {
                throw new AccessDeniedException(
                        "Supervisor może modyfikować wyłącznie skille agentów");
            }
            if (request.firstName() != null || request.lastName() != null || request.mfaEnabled() != null) {
                throw new AccessDeniedException(
                        "Supervisor może modyfikować wyłącznie pole skills");
            }
        }

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
     * @throws BusinessValidationException HTTP 422 gdy próbuje usunąć siebie
     */
    @Transactional
    @Audited(action = "USER_DELETED", entityType = "USER", captureOldValue = true,
             fetchOldValueMethod = "getUser", entityIdParamIndex = 0)
    @Override
    public void deleteUser(UUID userId, UUID tenantId, UUID requestingUserId) {
        // Nie można usunąć siebie
        if (userId.equals(requestingUserId)) {
            throw new BusinessValidationException("Nie można usunąć własnego konta");
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
    @Override
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
     * @throws BusinessValidationException HTTP 422 gdy status jest niedozwolony (ACTIVE/INACTIVE)
     * @throws EntityNotFoundException  HTTP 422 gdy użytkownik nie istnieje
     */
    @Transactional
    @Override
    public UserResponse updateStatus(UUID userId, UpdateStatusRequest request, UUID tenantId) {
        // Walidacja dozwolonych statusów
        if (!ALLOWED_AGENT_STATUSES.contains(request.status())) {
            throw new BusinessValidationException(
                    "Niedozwolony status: " + request.status() +
                    ". Dozwolone: AVAILABLE, BUSY, BREAK, AFTER_CONTACT");
        }

        // Agent może zmieniać tylko własny status
        String requestingRole = TenantContext.getUserRole();
        UUID requestingUserId = TenantContext.getUserIdOrNull();
        if ("AGENT".equalsIgnoreCase(requestingRole) && !userId.equals(requestingUserId)) {
            throw new AccessDeniedException("Agent może zmieniać tylko własny status");
        }

        AppUser user = findUserOrThrow(userId, tenantId);
        UserStatus oldStatus = user.getStatus();

        // BE-166: true only on a real transition; same-status write keeps status_changed_at untouched
        boolean changed = user.changeStatus(request.status(), Instant.now());
        AppUser saved = changed ? appUserRepository.save(user) : user;

        // Zapis statusu w Redis jako structured Map (wymagane przez AdminMetricsService
        // do zliczania agentów online per tenant – musi zawierać tenantId).
        String breakStartedAt = "";
        if (request.status() == UserStatus.BREAK) {
            // Tylko przy nowym wejściu na przerwę ustawiamy timestamp; kontynuacja BREAK zachowuje poprzedni
            breakStartedAt = oldStatus != UserStatus.BREAK
                    ? Instant.now().toString()
                    : Optional.ofNullable(getExistingBreakStartedAt(String.format(REDIS_AGENT_STATUS_KEY, userId)))
                            .orElseGet(() -> Instant.now().toString());
        }
        writeAgentSessionToRedis(userId, tenantId, request.status(), breakStartedAt);

        // Publikacja eventu RabbitMQ – tylko przy faktycznej zmianie (BE-166). Odświeżenie TTL w Redis
        // powyżej zostaje także bez zmiany.
        if (changed) {
            publishStatusChangedEvent(userId, tenantId, oldStatus, request.status(), saved.getStatusChangedAt());
            log.info("[UserService] Status agenta zmieniony: userId={}, {} -> {}",
                    userId, oldStatus, request.status());
        } else {
            log.debug("[UserService] Status agenta bez zmiany ({}): userId={} – pomijam event, odświeżono TTL Redis",
                    oldStatus, userId);
            requestRoutingRetryIfAvailable(userId, tenantId, request.status());
        }

        // Przy przejściu na AVAILABLE: oczyść błędne/przeterminowane kontakty w kolejce,
        // żeby agent nie dostał do obsługi martwych kontaktów.
        // Celowo także przy niezmienionym AVAILABLE (BE-166 review): to idempotentny cleanup, a ponowny zapis
        // AVAILABLE jest też "heartbeatem" retry routingu (patrz requestRoutingRetryIfAvailable).
        if (request.status() == UserStatus.AVAILABLE) {
            try {
                contactService.terminateStaleQueuedContacts(tenantId);
            } catch (Exception e) {
                // Cleanup failure is non-critical – log warning and continue.
                // Agent status change must succeed regardless.
                log.warn("[UserService] Błąd cleanup przeterminowanych kontaktów: tenantId={}, error={}",
                        tenantId, e.getMessage());
            }
        }

        return UserResponse.from(saved);
    }

    // =========================================================================
    // Operacje wewnętrzne telefonii
    // =========================================================================

    /**
     * Ustawia status agenta na AVAILABLE po anulowaniu konsultacji (attended transfer bez bridge).
     *
     * <p>Wywoływana przez TwilioTelephonyAdapter gdy Agent1 rozłączy nogę konsultacyjną
     * zanim wywoła bridge. Agent2 (targetAgentId) wraca do AVAILABLE bez przechodzenia
     * przez AFTER_CONTACT.
     *
     * <p>Operacja jest best-effort: błędy są logowane, ale nie blokują publikacji eventu
     * CALL_CONSULT_CANCELLED przez adapter.
     *
     * @param agentId  UUID Agent2
     * @param tenantId UUID tenanta
     */
    @Transactional
    @Override
    public void setAgentAvailableAfterConsultCancelled(UUID agentId, UUID tenantId) {
        log.info("[UserService] Konsultacja anulowana – ustawiam AVAILABLE: agentId={}, tenant={}",
                agentId, tenantId);

        AppUser agent = findUserOrThrow(agentId, tenantId);
        UserStatus oldStatus = agent.getStatus();

        boolean changed = agent.changeStatus(UserStatus.AVAILABLE, Instant.now());
        AppUser savedAgent = changed ? appUserRepository.save(agent) : agent;

        // Aktualizuj Redis żeby AdminMetricsService widział poprawny stan agenta
        writeAgentSessionToRedis(agentId, tenantId, UserStatus.AVAILABLE, "");

        // Propaguj zmianę statusu przez RabbitMQ → WebSocket (agentStatusChanged do agenta i supervisorów)
        if (changed) {
            publishStatusChangedEvent(agentId, tenantId, oldStatus, UserStatus.AVAILABLE,
                    savedAgent.getStatusChangedAt());
        } else {
            requestRoutingRetryIfAvailable(agentId, tenantId, UserStatus.AVAILABLE);
        }
    }

    /**
     * BE-166: unchanged AVAILABLE no longer publishes {@code agent.status.changed}, yet it must keep
     * triggering the routing retry of QUEUED contacts (RoutingService is the only retry path for
     * inbound/social/e-mail). An in-JVM event avoids both the user-facing WS event and a user -> routing
     * bean dependency (RoutingServiceImpl already depends on UserService). The dialer is deliberately not
     * notified - it polls available agents. Failures never break the status write.
     */
    private void requestRoutingRetryIfAvailable(UUID userId, UUID tenantId, UserStatus status) {
        if (status != UserStatus.AVAILABLE) {
            return;
        }
        try {
            eventPublisher.publishEvent(new AgentAvailabilityRefreshedEvent(userId, tenantId));
        } catch (Exception e) {
            log.warn("[UserService] Błąd publikacji AgentAvailabilityRefreshedEvent: userId={}, error={}",
                    userId, e.getMessage());
        }
    }

    @Override
    public void publishStatusChangedAfterCommit(UUID userId, UUID tenantId,
                                                UserStatus oldStatus, UserStatus newStatus,
                                                Instant statusSince) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishStatusChangedEvent(userId, tenantId, oldStatus, newStatus, statusSince);
                }
            });
        } else {
            publishStatusChangedEvent(userId, tenantId, oldStatus, newStatus, statusSince);
        }
    }

    // =========================================================================
    // Encapsulation pass: dostęp do AppUserRepository wyłącznie przez UserService
    // =========================================================================

    @Transactional(readOnly = true)
    @Override
    public Optional<AppUser> findAuthenticatableUser(UUID tenantId, String email) {
        return appUserRepository.findByTenantIdAndEmailAndActiveTrue(tenantId, email);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<AppUser> findAuthenticatableGlobalUser(String email) {
        return appUserRepository.findByEmailAndTenantIdIsNullAndActiveTrue(email);
    }

    @Transactional
    @Override
    public int deactivateAllUsersByTenantId(UUID tenantId) {
        return appUserRepository.deactivateAllByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<AppUser> findAgentByIdAndTenantId(UUID agentId, UUID tenantId) {
        return appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<java.time.Instant> findLastLoginAt(UUID userId, UUID tenantId) {
        return appUserRepository.findLastLoginAt(userId, tenantId);
    }

    @Transactional(readOnly = true)
    @Override
    public long countAgentsByTenantId(UUID tenantId) {
        return appUserRepository.countAgentsByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    @Override
    public List<AppUser> findAgentsByIdsAndTenantId(Collection<UUID> agentIds, UUID tenantId) {
        return appUserRepository.findAllByIdInAndTenantId(agentIds, tenantId);
    }

    @Transactional(readOnly = true)
    @Override
    public Page<AppUser> findAgentsByTenantIdWithFilters(UUID tenantId, String status, String skill, String role,
                                                          String search, Pageable pageable) {
        return appUserRepository.findAllByTenantIdWithFilters(tenantId, status, skill, role, search, pageable);
    }

    @Transactional(readOnly = true)
    @Override
    public List<AppUser> findTransferCandidates(UUID tenantId, UUID excludeUserId) {
        return appUserRepository.findTransferCandidates(tenantId, excludeUserId);
    }

    @Transactional(readOnly = true)
    @Override
    public List<AppUser> findAvailableAgents() {
        return appUserRepository.findAllByStatusAndRoleAndDeletedFalse(UserStatus.AVAILABLE, AppUser.UserRole.AGENT);
    }

    @Transactional(readOnly = true)
    @Override
    public List<AgentPresenceCandidate> findPresenceTrackedAgents() {
        return appUserRepository.findAgentsByStatusIn(PRESENCE_TRACKED_STATUSES);
    }

    @Transactional
    @Override
    public boolean markOfflineOnPresenceTimeout(UUID userId, UUID tenantId, UserStatus expectedStatus) {
        // Defense in depth (service-level equivalent of assertSameTenant): the CAS below trusts the parameter.
        UUID contextTenantId = TenantContext.getTenantIdOrNull();
        if (!tenantId.equals(contextTenantId)) {
            log.warn("[UserService][Security] Auto-OFFLINE odrzucony - tenant spoza kontekstu: userId={}, "
                    + "contextTenant={}, requestedTenant={}", userId, contextTenantId, tenantId);
            throw new com.contactcenter.domain.exception.CrossTenantAccessException(userId, contextTenantId, tenantId);
        }
        if (expectedStatus == UserStatus.OFFLINE) {
            return false;
        }
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // Compare-and-set: no row is touched when the status moved on (manual change, other instance)
        int updated = appUserRepository.changeStatusIfCurrent(
                userId, tenantId, expectedStatus, UserStatus.OFFLINE, now);
        if (updated == 0) {
            log.debug("[UserService] Auto-OFFLINE pominiety - status juz inny niz {}: userId={}", expectedStatus, userId);
            return false;
        }

        // Redis session entry + event only after the DB commit (consumers read the committed row)
        Runnable afterCommit = () -> {
            writeAgentSessionToRedis(userId, tenantId, UserStatus.OFFLINE, "");
            publishStatusChangedEvent(userId, tenantId, expectedStatus, UserStatus.OFFLINE, now,
                    AgentStatusChangedEvent.REASON_PRESENCE_TIMEOUT);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    afterCommit.run();
                }
            });
        } else {
            afterCommit.run();
        }
        return true;
    }

    /**
     * Single writer of the structured {@code session:agent:{userId}} entry (read by AdminMetricsService and
     * routing). Best-effort: a Redis failure never blocks the status change.
     */
    private void writeAgentSessionToRedis(UUID userId, UUID tenantId, UserStatus status, String breakStartedAt) {
        String redisKey = String.format(REDIS_AGENT_STATUS_KEY, userId);
        try {
            Map<String, String> sessionData = new HashMap<>();
            sessionData.put("tenantId", tenantId.toString());
            sessionData.put("userId", userId.toString());
            sessionData.put("status", status.name());
            sessionData.put("breakStartedAt", breakStartedAt);
            redisTemplate.opsForValue().set(redisKey, sessionData, REDIS_AGENT_STATUS_TTL);
            log.debug("[UserService] Status agenta zapisany w Redis: key={}, status={}, tenantId={}",
                    redisKey, status, tenantId);
        } catch (Exception e) {
            log.warn("[UserService] Blad zapisu statusu {} w Redis: key={}, error={}", status, redisKey, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    @Override
    public List<Object[]> findActiveTenantsByUserEmail(String email) {
        return appUserRepository.findActiveTenantsByUserEmail(email);
    }

    @Transactional(readOnly = true)
    @Override
    public boolean isSuperAdminEmail(String email) {
        return appUserRepository.existsActiveSuperAdminByEmail(email);
    }

    // =========================================================================
    // Bootstrap SUPER_ADMIN (SuperAdminBootstrapRunner)
    // =========================================================================

    @Transactional(readOnly = true)
    @Override
    public boolean existsSuperAdmin() {
        return appUserRepository.existsByRole(UserRole.SUPER_ADMIN);
    }

    @Transactional
    @Audited(action = "SUPER_ADMIN_BOOTSTRAPPED", entityType = "USER")
    @Override
    public AppUser createSuperAdminBootstrap(String email, String rawPassword) {
        AppUser superAdmin = AppUser.builder()
                .tenantId(null)
                .email(email.toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(UserRole.SUPER_ADMIN)
                .status(UserStatus.ACTIVE)
                .active(true)
                .mfaEnabled(false)
                // Wymuszamy zmianę hasła przy pierwszym logowaniu – szczególnie ważne dla
                // fallbacku dev, ale też sensowny default dla prod.
                .passwordResetRequired(true)
                .deleted(false)
                .skills(new ArrayList<>())
                .build();

        AppUser saved = appUserRepository.save(superAdmin);
        log.info("[UserService] Bootstrap: utworzono konto SUPER_ADMIN: userId={}, email={}",
                saved.getId(), saved.getEmail());

        return saved;
    }

    @Transactional(readOnly = true)
    @Override
    public List<Object[]> countActiveContactsByAgentIds(UUID tenantId, List<UUID> agentIds) {
        return appUserRepository.countActiveContactsByAgentIds(tenantId, agentIds);
    }

    @Transactional(readOnly = true)
    @Override
    public List<AppUser> findUsersByIds(Collection<UUID> userIds) {
        return appUserRepository.findAllById(userIds);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<AppUser> findUserById(UUID userId) {
        return appUserRepository.findById(userId);
    }

    @Transactional(readOnly = true)
    @Override
    public List<Object[]> countNewUsersByWeekSince(java.time.Instant since) {
        return appUserRepository.countNewUsersByWeekSince(since);
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

    @SuppressWarnings("unchecked")
    private String getExistingBreakStartedAt(String redisKey) {
        try {
            Object val = redisTemplate.opsForValue().get(redisKey);
            if (val instanceof Map<?, ?> m) {
                Object ts = m.get("breakStartedAt");
                return (ts != null && !ts.toString().isBlank()) ? ts.toString() : null;
            }
        } catch (Exception e) {
            log.trace("[UserService] Błąd odczytu breakStartedAt z Redis: {}", e.getMessage());
        }
        return null;
    }

    private void publishStatusChangedEvent(UUID userId, UUID tenantId,
                                            UserStatus oldStatus, UserStatus newStatus,
                                            Instant statusSince) {
        publishStatusChangedEvent(userId, tenantId, oldStatus, newStatus, statusSince, null);
    }

    private void publishStatusChangedEvent(UUID userId, UUID tenantId,
                                            UserStatus oldStatus, UserStatus newStatus,
                                            Instant statusSince, String reason) {
        AgentStatusChangedEvent event =
                AgentStatusChangedEvent.of(userId, tenantId, oldStatus, newStatus, statusSince, reason);
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
