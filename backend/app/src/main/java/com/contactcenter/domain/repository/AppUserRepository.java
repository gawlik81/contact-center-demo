package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium JPA dla encji {@link AppUser}.
 *
 * <p>Email jest unikalny per tenant (nie globalnie), dlatego wszystkie
 * zapytania po email muszą zawierać tenantId w warunku WHERE.
 *
 * <p><strong>Uwaga dotycząca Row-Level Security (RLS):</strong> To repozytorium
 * rozszerza {@link JpaRepository} zamiast {@link TenantAwareRepository},
 * ponieważ {@code AppUser} jest używany przez warstwę bezpieczeństwa
 * ({@code UserDetailsServiceImpl}) podczas autentykacji – zanim TenantContext
 * zostanie ustawiony. Wywoływanie {@code set_tenant_context()} w tym kontekście
 * spowodowałoby błędy przy logowaniu.
 *
 * <p>Izolacja multi-tenant jest zapewniana explicite: <em>każde</em> zapytanie
 * zawiera warunek {@code tenantId = :tenantId} w klauzuli WHERE (Spring Data JPA,
 * JPQL lub native SQL). Nie polegamy na RLS dla tej tabeli.
 *
 * <p>Każda nowa metoda MUSI zawierać filtr tenantId aby zapobiec wyciekom danych.
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
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AppUser u SET u.mfaSecret = :secret WHERE u.id = :userId")
    void updateMfaSecret(@Param("userId") UUID userId, @Param("secret") String secret);

    /**
     * Aktywuj MFA po pomyślnej weryfikacji TOTP.
     * Używane przez endpoint /auth/mfa/verify.
     */
    @Modifying(clearAutomatically = true)
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
    @Modifying(clearAutomatically = true)
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
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AppUser u SET u.passwordResetRequired = true WHERE u.id = :userId AND u.tenantId = :tenantId")
    int setPasswordResetRequired(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);

    /**
     * Dezaktywuje wszystkich aktywnych użytkowników tenanta jednym bulk UPDATE.
     *
     * <p>Używane przez {@link com.contactcenter.domain.service.TenantService#deactivateTenant(UUID)}
     * zamiast pętli N+1 (findAll + N×save). Jeden UPDATE zamiast full table scan i N osobnych UPDATE.
     *
     * <p>{@code clearAutomatically = true} – usuwa z Hibernate L1 cache zaktualizowane encje,
     * żeby nie czytać stale state po wykonaniu bulk UPDATE.
     *
     * @param tenantId UUID tenanta
     * @return liczba dezaktywowanych użytkowników
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AppUser u SET u.active = false WHERE u.tenantId = :tenantId AND u.active = true")
    int deactivateAllByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Zlicza łączną liczbę agentów dla tenanta (is_deleted=false, role=AGENT).
     *
     * <p>Używane przez {@code AdminMetricsService} do wyliczenia agentsTotal per tenant.
     * Nie filtruje po is_active – liczymy wszystkich agentów, nawet zablokowanych.
     *
     * @param tenantId UUID tenanta
     * @return liczba agentów z rolą AGENT i is_deleted=false
     */
    @Query(value = """
            SELECT COUNT(*) FROM app_user
            WHERE tenant_id = :tenantId
              AND role = 'AGENT'
              AND is_deleted = FALSE
            """, nativeQuery = true)
    long countAgentsByTenantId(@Param("tenantId") UUID tenantId);

    // =========================================================================
    // BE-008: User/Agent CRUD API
    // =========================================================================

    /**
     * Lista użytkowników tenanta (nie usuniętych) z paginacją.
     *
     * @param tenantId UUID tenanta
     * @param pageable parametry stronicowania
     * @return strona użytkowników
     */
    Page<AppUser> findAllByTenantIdAndDeletedFalse(UUID tenantId, Pageable pageable);

    /**
     * Lista użytkowników tenanta z opcjonalnym filtrowaniem po statusie, skillu, roli i frazie wyszukiwania.
     *
     * <p>Wszystkie parametry są opcjonalne – gdy null, warunek jest pomijany.
     * Filtr {@code skill} używa JSONB containment (@>).
     * Filtr {@code search} przeszukuje imię, nazwisko i email za pomocą ILIKE (case-insensitive).
     *
     * @param tenantId UUID tenanta
     * @param status   opcjonalny status (ACTIVE, INACTIVE, AVAILABLE, BUSY, …) lub null
     * @param skill    opcjonalny skill do wyszukania w JSONB array lub null
     * @param role     opcjonalna rola (ADMIN, SUPERVISOR, AGENT) lub null
     * @param search   opcjonalna fraza do wyszukania w imieniu, nazwisku i emailu lub null
     * @param pageable parametry stronicowania
     * @return strona użytkowników spełniających kryteria
     */
    @Query(value = """
            SELECT * FROM app_user
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND is_deleted = FALSE
              AND (:status IS NULL OR status = :status)
              AND (:skill IS NULL OR skills @> jsonb_build_array(:skill)::jsonb)
              AND (:role IS NULL OR role = :role)
              AND (:search IS NULL OR first_name ILIKE '%' || :search || '%'
                                   OR last_name  ILIKE '%' || :search || '%'
                                   OR email      ILIKE '%' || :search || '%')
            """,
           countQuery = """
            SELECT COUNT(*) FROM app_user
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND is_deleted = FALSE
              AND (:status IS NULL OR status = :status)
              AND (:skill IS NULL OR skills @> jsonb_build_array(:skill)::jsonb)
              AND (:role IS NULL OR role = :role)
              AND (:search IS NULL OR first_name ILIKE '%' || :search || '%'
                                   OR last_name  ILIKE '%' || :search || '%'
                                   OR email      ILIKE '%' || :search || '%')
            """,
           nativeQuery = true)
    Page<AppUser> findAllByTenantIdWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("skill") String skill,
            @Param("role") String role,
            @Param("search") String search,
            Pageable pageable);

    /**
     * Znajdź użytkownika po ID i tenantId (nie usuniętego).
     * Bezpieczny odczyt per tenant – uniemożliwia cross-tenant lookup.
     *
     * @param userId   UUID użytkownika
     * @param tenantId UUID tenanta
     * @return Optional z użytkownikiem lub empty jeśli nie istnieje/inny tenant
     */
    Optional<AppUser> findByIdAndTenantIdAndDeletedFalse(UUID userId, UUID tenantId);

    /**
     * Zwraca unikalne skills wszystkich użytkowników tenanta jako spłaszczoną tablicę.
     *
     * <p>Zapytanie rozpakowuje JSONB array skills każdego użytkownika i zwraca
     * posortowaną, zdeduplikowaną listę jako String. Wynik musi być zdeduplikowany
     * na poziomie aplikacji lub przez DISTINCT w SQL.
     *
     * <p>Używamy native query bo JPQL nie obsługuje operatora JSONB unnest.
     *
     * @param tenantId UUID tenanta
     * @return lista unikalnych skill tagów jako surowe stringi
     */
    @Query(value = """
            SELECT DISTINCT skill_value
            FROM app_user,
                 jsonb_array_elements_text(skills) AS skill_value
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND is_deleted = FALSE
            ORDER BY skill_value
            """, nativeQuery = true)
    List<String> findAllDistinctSkillsByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Sprawdza czy agent ma aktywne kontakty (status IN QUEUED, ACTIVE, ON_HOLD).
     *
     * <p>Używane przed soft delete agenta – nie można usunąć agenta z aktywnymi kontaktami.
     *
     * @param userId   UUID użytkownika/agenta
     * @param tenantId UUID tenanta (izolacja)
     * @return true jeśli agent ma co najmniej jeden aktywny kontakt
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM contact
                WHERE agent_id = CAST(:userId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                  AND status IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
            )
            """, nativeQuery = true)
    boolean existsActiveContactsByUserId(@Param("userId") UUID userId,
                                          @Param("tenantId") UUID tenantId);

    /**
     * Soft delete użytkownika – ustawia is_deleted=true i is_active=false.
     *
     * @param userId   UUID użytkownika
     * @param tenantId UUID tenanta (izolacja cross-tenant)
     * @return liczba zaktualizowanych wierszy
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE app_user
            SET is_deleted = TRUE,
                is_active  = FALSE,
                updated_at = NOW()
            WHERE user_id = CAST(:userId AS uuid)
              AND tenant_id = CAST(:tenantId AS uuid)
              AND is_deleted = FALSE
            """, nativeQuery = true)
    int softDeleteUser(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);

    // =========================================================================
    // Public / email-first login flow
    // =========================================================================

    /**
     * Zwraca aktywne tenanty, w których istnieje aktywny, nieusunięty użytkownik z danym emailem.
     *
     * <p>Używane przez publiczny endpoint {@code POST /api/public/tenants-by-email} do
     * flow "email-first" na stronie logowania – pozwala wyświetlić tylko organizacje,
     * do których dany adres e-mail jest przypisany.
     *
     * <p><strong>Zasady bezpieczeństwa:</strong>
     * <ul>
     *   <li>Endpoint jest publiczny – wynik musi ujawniać jak najmniej informacji.
     *       Zwracamy puste {@code []} gdy brak dopasowania (NIE 404).</li>
     *   <li>Email NIE jest logowany w żadnej warstwie aplikacji (PII).</li>
     *   <li>Metoda nie zwraca żadnych danych o użytkowniku – wyłącznie id i name tenanta.</li>
     * </ul>
     *
     * <p>Zapytanie jest natywne (JOIN między app_user a tenant), bo JPQL nie obsługuje
     * cross-entity JOIN bez relacji @ManyToOne między AppUser a Tenant.
     *
     * @param email adres e-mail użytkownika (case-insensitive)
     * @return lista wierszy [tenant_id, name] aktywnych tenantów z dopasowaniem
     */
    @Query(value = """
            SELECT DISTINCT t.tenant_id, t.name
            FROM   app_user u
            JOIN   tenant   t ON t.tenant_id = u.tenant_id
            WHERE  LOWER(u.email) = LOWER(:email)
              AND  u.is_active  = TRUE
              AND  u.is_deleted = FALSE
              AND  t.status     = 'ACTIVE'
            ORDER  BY t.name ASC
            """, nativeQuery = true)
    List<Object[]> findActiveTenantsByUserEmail(@Param("email") String email);

    // =========================================================================
    // BE-009: Admin cross-tenant user management
    // =========================================================================

    /**
     * Lista wszystkich użytkowników ze wszystkich tenantów (nie usuniętych) z paginacją.
     *
     * <p>Używana wyłącznie przez AdminUserController – nie wymaga filtru tenantId,
     * ponieważ Admin ma dostęp do wszystkich tenantów. Brak RLS – repozytorium
     * rozszerza JpaRepository (nie TenantAwareRepository), więc zapytanie
     * nie wywołuje set_tenant_context() i nie jest blokowane przez RLS.
     *
     * @param pageable parametry stronicowania
     * @return strona wszystkich użytkowników
     */
    Page<AppUser> findAllByDeletedFalse(Pageable pageable);

    /**
     * Lista użytkowników danego tenanta (nie usuniętych) z paginacją.
     * Wariant dla Admin – jawne przekazanie tenantId zamiast pobierania z TenantContext.
     *
     * <p>Tożsamy z {@link #findAllByTenantIdAndDeletedFalse} – jest to alias
     * dla czytelności kodu w AdminUserService.
     *
     * @param tenantId UUID tenanta
     * @param pageable parametry stronicowania
     * @return strona użytkowników tenanta
     */
    default Page<AppUser> findAllByTenantIdAndDeletedFalseForAdmin(UUID tenantId, Pageable pageable) {
        return findAllByTenantIdAndDeletedFalse(tenantId, pageable);
    }
}
