package com.contactcenter.domain.service;

import com.contactcenter.api.user.dto.AdminCreateUserRequest;
import com.contactcenter.api.user.dto.AdminUpdateUserRequest;
import com.contactcenter.api.user.dto.UserResponse;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.AppUser.UserRole;
import com.contactcenter.domain.model.AppUser.UserStatus;
import com.contactcenter.domain.repository.AppUserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * który jest zabezpieczony {@code @PreAuthorize("hasRole('ADMIN')")}.
 * Nie wywołuje RLS (AppUserRepository rozszerza JpaRepository, nie TenantAwareRepository).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantResourceLimitService tenantResourceLimitService;

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
     * Tworzy nowego użytkownika w podanym tenancie.
     *
     * <p>tenantId pochodzi z ciała żądania (nie z TenantContext) –
     * Admin może tworzyć użytkowników w dowolnym tenancie.
     *
     * @param request żądanie z tenantId, danymi użytkownika i hasłem
     * @return DTO nowo utworzonego użytkownika
     * @throws IllegalArgumentException HTTP 422 gdy email zajęty w tenancie
     */
    @Transactional
    public UserResponse createUser(AdminCreateUserRequest request) {
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
     */
    @Transactional
    public UserResponse updateUser(UUID userId, AdminUpdateUserRequest request) {
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
     */
    @Transactional
    public void deleteUser(UUID userId) {
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
     * Ustawia flagę wymaganej zmiany hasła dla użytkownika.
     *
     * <p>Admin może wymusić zmianę hasła dla użytkownika z dowolnego tenanta.
     * Przy następnym logowaniu użytkownik zostanie przekierowany do formularza
     * zmiany hasła.
     *
     * @param userId UUID użytkownika
     * @throws EntityNotFoundException HTTP 404 gdy użytkownik nie istnieje lub usunięty
     */
    @Transactional
    public void forcePasswordReset(UUID userId) {
        AppUser user = appUserRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Użytkownik o ID " + userId + " nie istnieje lub został usunięty"));

        user.setPasswordResetRequired(true);
        appUserRepository.save(user);

        log.info("[AdminUserService] Wymuszono zmianę hasła przez Admina: userId={}, tenantId={}",
                userId, user.getTenantId());
    }
}
