package com.contactcenter.domain.user;

import com.contactcenter.api.user.dto.AdminCreateUserRequest;
import com.contactcenter.api.user.dto.AdminUpdateUserRequest;
import com.contactcenter.api.user.dto.UserResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Serwis domenowy dla operacji administracyjnych na użytkownikach (cross-tenant).
 *
 * <p>Implementuje BE-009: Admin może listować i tworzyć użytkowników w dowolnym tenancie.
 *
 * <p>Kluczowa różnica względem {@link UserService}:
 * <ul>
 *   <li>{@code listUsers} z {@code tenantId == null} – nie filtruje po tenantId (cross-tenant query)</li>
 *   <li>{@code listUsers} z podanym {@code tenantId} – filtruje po explicite podanym tenantId</li>
 *   <li>{@code createUser} – przyjmuje tenantId z żądania (nie z TenantContext)</li>
 * </ul>
 *
 * <p>Bezpieczeństwo: wywołanie tylko z {@code AdminUserController},
 * który jest zabezpieczony {@code @PreAuthorize("hasRole('ADMIN')")}.
 * Nie wywołuje RLS (AppUserRepository rozszerza JpaRepository, nie TenantAwareRepository).
 */
public interface AdminUserService {

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
    Page<UserResponse> listUsers(UUID tenantId, Pageable pageable);

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
    UserResponse createUser(AdminCreateUserRequest request);

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
    UserResponse updateUser(UUID userId, AdminUpdateUserRequest request);

    /**
     * Usuwa użytkownika (soft delete) – ustawia is_deleted=true i is_active=false.
     *
     * <p>Admin może usuwać użytkownika z dowolnego tenanta.
     * Operacja nieodwracalna z perspektywy API.
     *
     * @param userId UUID użytkownika do usunięcia
     * @throws EntityNotFoundException HTTP 404 gdy użytkownik nie istnieje lub już usunięty
     */
    void deleteUser(UUID userId);

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
    void forcePasswordReset(UUID userId);
}
