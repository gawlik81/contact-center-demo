package com.contactcenter.domain.user;

import com.contactcenter.api.user.dto.AdminCreateUserRequest;
import com.contactcenter.api.user.dto.AdminUpdateUserRequest;
import com.contactcenter.api.user.dto.UserResponse;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.tenant.TenantResourceLimitService;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Serwis domenowy dla operacji administracyjnych na użytkownikach (cross-tenant).
 *
 * <p>Implementuje BE-009: Admin może listować i tworzyć użytkowników w dowolnym tenancie.
 *
 * <p>Kluczowa różnica względem {@link UserService}:
 * <ul>
 *   <li>{@code listAllUsers} – nie filtruje po tenantId (cross-tenant query)</li>
 *   <li>{@code listUsersByTenant} – filtruje po explicite podanym tenantId</li>
 *   <li>{@code createUserForTenant} – przyjmuje tenantId z żądania (nie z TenantContext)</li>
 * </ul>
 *
 * <p>Bezpieczeństwo: wywołanie tylko z {@code AdminUserController},
 * który jest zabezpieczony {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}.
 * Nie wywołuje RLS (AppUserRepository rozszerza JpaRepository, nie TenantAwareRepository).
 * {@code updateUser}/{@code deleteUser} blokują operacje na własnym koncie wywołującego
 * (por. {@link TenantContext#getUserId()}), żeby SUPER_ADMIN nie mógł przypadkowo
 * zablokować sobie dostępu do panelu cross-tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AdminUserServiceImpl implements AdminUserService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantResourceLimitService tenantResourceLimitService;
    private final RefreshTokenRepository refreshTokenRepository;

    // =========================================================================
    // Lista użytkowników (cross-tenant)
    // =========================================================================

    /**
     * Lista użytkowników z opcjonalnym filtrem tenanta.
     *
     * <p>Gdy {@code tenantId} jest null – zwraca użytkowników ze wszystkich tenantów.
     * Gdy {@code tenantId} jest podany – zwraca tylko użytkowników tego tenanta.
     *
     * @param tenantId opcjonalny UUID tenanta (null = wszystkie tenanty)
     * @param pageable parametry stronicowania
     * @return paginowana odpowiedź z DTO użytkowników
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(UUID tenantId, Pageable pageable) {
        Page<AppUser> page;
        if (tenantId != null) {
            page = appUserRepository.findAllByTenantIdAndDeletedFalse(tenantId, pageable);
        } else {
            page = appUserRepository.findAllByDeletedFalse(pageable);
        }
        return page.map(UserResponse::from);
    }

    // =========================================================================
    // Tworzenie użytkownika (cross-tenant)
    // =========================================================================

    /**
     * Tworzy nowego użytkownika w podanym tenancie, lub kolejne konto SUPER_ADMIN
     * (globalne, bez tenanta) gdy {@code request.role() == SUPER_ADMIN}.
     *
     * <p>tenantId pochodzi z ciała żądania (nie z TenantContext) –
     * SUPER_ADMIN może tworzyć użytkowników w dowolnym tenancie.
     *
     * @param request żądanie z tenantId, danymi użytkownika i hasłem
     * @return DTO nowo utworzonego użytkownika
     * @throws IllegalArgumentException HTTP 422 gdy email zajęty w tenancie (lub wśród SUPER_ADMIN)
     */
    @Override
    @Transactional
    public UserResponse createUser(AdminCreateUserRequest request) {
        if (UserRole.SUPER_ADMIN.equals(request.role())) {
            return createSuperAdminUser(request);
        }

        if (request.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tenantId jest wymagany dla roli innej niż SUPER_ADMIN");
        }
        UUID tenantId = request.tenantId();

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
        log.info("[AdminUserService] Utworzono użytkownika przez Admina: userId={}, tenantId={}, role={}",
                saved.getId(), tenantId, saved.getRole());

        return UserResponse.from(saved);
    }

    /**
     * Tworzy kolejne konto SUPER_ADMIN (globalne, {@code tenant_id IS NULL}).
     *
     * <p>W odróżnieniu od bootstrapu przy starcie systemu ({@code SuperAdminBootstrapRunner},
     * uruchamiany raz, tylko gdy żaden SUPER_ADMIN jeszcze nie istnieje), ta metoda pozwala
     * istniejącemu SUPER_ADMIN świadomie dodać kolejnego globalnego administratora
     * (np. na wypadek utraty dostępu do jedynego konta).
     *
     * @throws ResponseStatusException  HTTP 400 gdy żądanie zawiera tenantId (SUPER_ADMIN go nie ma)
     * @throws IllegalArgumentException HTTP 422 gdy email jest już zajęty przez inne konto SUPER_ADMIN
     */
    private UserResponse createSuperAdminUser(AdminCreateUserRequest request) {
        if (request.tenantId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SUPER_ADMIN nie może być przypisany do tenanta – pomiń pole tenantId");
        }

        String email = request.email().toLowerCase().trim();
        if (appUserRepository.existsActiveSuperAdminByEmail(email)) {
            throw new IllegalArgumentException(
                    "Email '" + email + "' jest już zajęty przez inne konto SUPER_ADMIN");
        }

        AppUser user = AppUser.builder()
                .tenantId(null)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .firstName(request.firstName())
                .lastName(request.lastName())
                .role(UserRole.SUPER_ADMIN)
                .status(UserStatus.ACTIVE)
                .active(true)
                .mfaEnabled(false)
                .passwordResetRequired(false)
                .deleted(false)
                .skills(new ArrayList<>())
                .build();

        AppUser saved = appUserRepository.save(user);
        log.info("[AdminUserService] Utworzono kolejne konto SUPER_ADMIN: userId={}", saved.getId());

        return UserResponse.from(saved);
    }

    // =========================================================================
    // Aktualizacja użytkownika (cross-tenant)
    // =========================================================================

    /**
     * Aktualizuje dane użytkownika (PATCH semantics).
     *
     * <p>Admin może modyfikować użytkownika w dowolnym tenancie.
     * Null w polach żądania oznacza brak zmiany.
     *
     * @param userId  UUID użytkownika
     * @param request żądanie z polami do aktualizacji
     * @return DTO zaktualizowanego użytkownika
     * @throws EntityNotFoundException HTTP 404 gdy użytkownik nie istnieje lub usunięty
     * @throws IllegalArgumentException HTTP 422 gdy email zajęty w tenancie
     * @throws AccessDeniedException   HTTP 403 gdy SUPER_ADMIN próbuje edytować własne konto
     */
    @Override
    @Transactional
    public UserResponse updateUser(UUID userId, AdminUpdateUserRequest request) {
        // Promocja do SUPER_ADMIN nie jest wspierana przez ten endpoint (użytkownik ma już
        // przypisany tenantId, a zmiana roli na SUPER_ADMIN naruszyłaby
        // chk_super_admin_tenant_invariant). Nowe konta SUPER_ADMIN powstają wyłącznie
        // przez createUser (patrz createSuperAdminUser).
        if (UserRole.SUPER_ADMIN.equals(request.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Rola SUPER_ADMIN nie może zostać przypisana przez ten endpoint – " +
                    "utwórz nowe konto SUPER_ADMIN zamiast promować istniejącego użytkownika");
        }

        // Zapobiega przypadkowej samo-blokadzie (np. active=false na własnym koncie)
        // lub samo-degradacji roli z panelu cross-tenant.
        if (userId.equals(TenantContext.getUserId())) {
            throw new AccessDeniedException("Nie można edytować własnego konta z tego panelu");
        }

        AppUser user = appUserRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Użytkownik o ID " + userId + " nie istnieje lub został usunięty"));

        if (request.firstName() != null) {
            user.setFirstName(request.firstName().trim());
        }
        if (request.lastName() != null) {
            user.setLastName(request.lastName().trim());
        }
        if (request.email() != null) {
            String newEmail = request.email().toLowerCase().trim();
            if (!newEmail.equals(user.getEmail())) {
                if (appUserRepository.existsByTenantIdAndEmail(user.getTenantId(), newEmail)) {
                    throw new IllegalArgumentException(
                            "Email '" + newEmail + "' jest już zajęty w tym tenancie");
                }
                user.setEmail(newEmail);
            }
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.skills() != null) {
            user.setSkills(new ArrayList<>(request.skills()));
        }
        if (request.active() != null) {
            user.setActive(request.active());
        }

        AppUser saved = appUserRepository.save(user);
        log.info("[AdminUserService] Zaktualizowano użytkownika przez Admina: userId={}, tenantId={}",
                saved.getId(), saved.getTenantId());

        return UserResponse.from(saved);
    }

    // =========================================================================
    // Usunięcie użytkownika (soft delete, cross-tenant)
    // =========================================================================

    /**
     * Usuwa użytkownika (soft delete) – ustawia is_deleted=true i is_active=false.
     *
     * <p>Admin może usuwać użytkownika z dowolnego tenanta.
     * Operacja nieodwracalna z perspektywy API.
     *
     * @param userId UUID użytkownika do usunięcia
     * @throws EntityNotFoundException HTTP 404 gdy użytkownik nie istnieje lub już usunięty
     * @throws AccessDeniedException   HTTP 403 gdy SUPER_ADMIN próbuje usunąć własne konto
     */
    @Override
    @Transactional
    public void deleteUser(UUID userId) {
        // Zapobiega przypadkowemu samo-usunięciu (utrata dostępu do panelu cross-tenant).
        if (userId.equals(TenantContext.getUserId())) {
            throw new AccessDeniedException("Nie można usunąć własnego konta z tego panelu");
        }

        AppUser user = appUserRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Użytkownik o ID " + userId + " nie istnieje lub został już usunięty"));

        user.setDeleted(true);
        user.setActive(false);
        appUserRepository.save(user);

        log.info("[AdminUserService] Usunięto użytkownika przez Admina (soft delete): userId={}, tenantId={}",
                userId, user.getTenantId());
    }

    // =========================================================================
    // Wymuszenie zmiany hasła (force password reset, cross-tenant)
    // =========================================================================

    /**
     * Ustawia flagę wymaganej zmiany hasła dla użytkownika i unieważnia jego aktywne sesje.
     *
     * <p>Admin może wymusić zmianę hasła dla użytkownika z dowolnego tenanta.
     * Przy następnym logowaniu użytkownik zostanie przekierowany do formularza
     * zmiany hasła. Unieważnienie refresh tokenów jest konieczne – bez tego
     * użytkownik z aktywną sesją mógłby ją odnawiać (token refresh nie sprawdza
     * {@code passwordResetRequired}, tylko login) i nigdy nie zostać zmuszonym
     * do zmiany hasła.
     *
     * @param userId UUID użytkownika
     * @throws EntityNotFoundException HTTP 404 gdy użytkownik nie istnieje lub usunięty
     */
    @Override
    @Transactional
    public void forcePasswordReset(UUID userId) {
        AppUser user = appUserRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Użytkownik o ID " + userId + " nie istnieje lub został usunięty"));

        // Bulk-update (@Modifying(clearAutomatically = true)) MUSI wykonać się przed zapisem
        // encji user – clearAutomatically wywołuje entityManager.clear() po zapytaniu, co
        // odrywa (detach) już załadowaną encję i odrzuca jej niezflushowane zmiany bez błędu.
        // Odwrócenie kolejności (save() przed revokeAllByUserId()) po cichu gubi
        // passwordResetRequired=true – flaga nigdy nie trafia do bazy mimo braku wyjątku.
        int revokedCount = refreshTokenRepository.revokeAllByUserId(userId);
        log.info("[AdminUserService] Unieważniono {} refresh tokenów przy force-reset: userId={}",
                revokedCount, userId);

        user.setPasswordResetRequired(true);
        appUserRepository.save(user);

        log.info("[AdminUserService] Wymuszono zmianę hasła przez Admina: userId={}, tenantId={}",
                userId, user.getTenantId());
    }
}
