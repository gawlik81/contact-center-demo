package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium JPA dla encji {@link AppUser}.
 *
 * <p>Email jest unikalny per tenant (nie globalnie), dlatego wszystkie
 * zapytania po email muszą zawierać tenantId w warunku WHERE.
 */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Znajdź użytkownika po email i tenantId.
     *
     * <p>Używane przez {@code UserDetailsServiceImpl} podczas autentykacji.
     * Indeks na (tenant_id, email) zapewnia wydajność O(log n).
     */
    Optional<AppUser> findByTenantIdAndEmail(UUID tenantId, String email);

    /**
     * Znajdź aktywnego użytkownika po email i tenantId.
     * Pomija użytkowników z {@code is_active = false}.
     */
    Optional<AppUser> findByTenantIdAndEmailAndActiveTrue(UUID tenantId, String email);

    /** Sprawdź czy email jest zajęty w danym tenancie. */
    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    /**
     * Zapisz MFA secret i ustaw mfaEnabled=false (pending verification).
     * Używane przez endpoint /auth/mfa/setup.
     */
    @Modifying
    @Query("UPDATE AppUser u SET u.mfaSecret = :secret WHERE u.id = :userId")
    void updateMfaSecret(@Param("userId") UUID userId, @Param("secret") String secret);

    /**
     * Aktywuj MFA po pomyślnej weryfikacji TOTP.
     * Używane przez endpoint /auth/mfa/verify.
     */
    @Modifying
    @Query("UPDATE AppUser u SET u.mfaEnabled = true WHERE u.id = :userId")
    void enableMfa(@Param("userId") UUID userId);

    /**
     * Zmień hasło i wyczyść flagę wymaganej zmiany hasła.
     *
     * <p>Używane przez endpoint /auth/change-password. Operacja atomowa – hash
     * i flaga aktualizowane w jednym UPDATE.
     *
     * @param userId UUID użytkownika
     * @param hash   nowy hash bcrypt (cost=12)
     */
    @Modifying
    @Query("UPDATE AppUser u SET u.passwordHash = :hash, u.passwordResetRequired = false WHERE u.id = :userId")
    void updatePasswordAndClearReset(@Param("userId") UUID userId, @Param("hash") String hash);

    /**
     * Ustaw flagę wymaganej zmiany hasła (force reset przez admina/supervisora).
     *
     * <p>Sprawdza tenant_id aby uniemożliwić cross-tenant reset.
     *
     * @param userId   UUID docelowego użytkownika
     * @param tenantId UUID tenanta (izolacja danych)
     * @return liczba zaktualizowanych wierszy (0 jeśli użytkownik nie istnieje lub inny tenant)
     */
    @Modifying
    @Query("UPDATE AppUser u SET u.passwordResetRequired = true WHERE u.id = :userId AND u.tenantId = :tenantId")
    int setPasswordResetRequired(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);
}
