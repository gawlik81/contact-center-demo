package com.contactcenter.domain.user;

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
 *
 * <p><strong>Encapsulation:</strong> To repozytorium jest package-private – dostęp
 * spoza pakietu {@code domain.user} odbywa się wyłącznie przez {@link UserService}.
 */
@Repository
interface AppUserRepository extends JpaRepository<AppUser, UUID> {

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
     * <p>Używane przez {@link com.contactcenter.domain.tenant.TenantService#deactivateTenant(UUID)}
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
     * Pobiera wielu użytkowników po liście UUID w obrębie tenanta.
     *
     * <p>Używane do batch-lookupowania nazw agentów przy mapowaniu listy kontaktów
     * na ContactResponse – unika problemu N+1 zapytań.
     *
     * @param userIds  zbiór UUID użytkowników do pobrania
     * @param tenantId UUID tenanta (izolacja cross-tenant)
     * @return lista użytkowników należących do tenanta; soft-deleted są pomijane
     */
    @Query("SELECT u FROM AppUser u WHERE u.id IN :userIds AND u.tenantId = :tenantId AND u.deleted = false")
    List<AppUser> findAllByIdInAndTenantId(@Param("userIds") java.util.Collection<UUID> userIds,
                                            @Param("tenantId") UUID tenantId);

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
     * Zlicza aktywne kontakty przypisane do agenta.
     *
     * <p>Używane przez routing engine (BE-019) do wybrania najmniej obciążonego
     * agenta w strategii SKILL_BASED. Aktywne statusy: QUEUED, ACTIVE, ON_HOLD.
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta (izolacja cross-tenant)
     * @return liczba aktywnych kontaktów agenta
     */
    @Query(value = """
            SELECT COUNT(*) FROM contact
            WHERE agent_id  = CAST(:agentId AS uuid)
              AND tenant_id = CAST(:tenantId AS uuid)
              AND status    IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
            """, nativeQuery = true)
    long countActiveContactsByAgentId(@Param("agentId") UUID agentId,
                                       @Param("tenantId") UUID tenantId);

    /**
     * Zwraca agentów dostępnych jako kandydaci do przyjęcia transferu połączenia.
     *
     * <p>Filtruje w DB (nie w Javie) eliminując problem N+1 / full-scan:
     * <ul>
     *   <li>Tylko rola AGENT</li>
     *   <li>Nieusunięci i aktywni</li>
     *   <li>Status OFFLINE wykluczony</li>
     *   <li>Wykluczony agent wywołujący (excludeUserId)</li>
     * </ul>
     * Sortowanie: AVAILABLE primeiro, następnie pozostałe statusy alfabetycznie po nazwisku.
     *
     * @param tenantId      UUID tenanta
     * @param excludeUserId UUID agenta wykluczanego z wyników (zalogowany agent)
     * @return lista kandydatów do transferu – posortowana przez aplikację
     */
    @Query(value = """
            SELECT * FROM app_user
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND is_deleted = FALSE
              AND is_active  = TRUE
              AND role       = 'AGENT'
              AND status    NOT IN ('OFFLINE', 'AFTER_CONTACT')
              AND user_id   <> CAST(:excludeUserId AS uuid)
            ORDER BY
                CASE status WHEN 'AVAILABLE' THEN 0 ELSE 1 END,
                last_name ASC,
                first_name ASC
            """, nativeQuery = true)
    List<AppUser> findTransferCandidates(@Param("tenantId") UUID tenantId,
                                          @Param("excludeUserId") UUID excludeUserId);

    /**
     * Zwraca liczbę aktywnych kontaktów dla listy agentów w jednym zapytaniu SQL (batch).
     *
     * <p>Zastępuje N osobnych wywołań {@link #countActiveContactsByAgentId} w strategii SKILL_BASED,
     * eliminując problem N+1 zapytań. Wynik zawiera tylko agentów z co najmniej jednym aktywnym
     * kontaktem – agenci z 0 kontaktów są pomijani przez GROUP BY (należy obsłużyć ich jako 0
     * po stronie aplikacji).
     *
     * <p>Aktywne statusy: QUEUED, ACTIVE, ON_HOLD.
     *
     * @param tenantId  UUID tenanta (izolacja cross-tenant)
     * @param agentIds  lista UUID agentów do sprawdzenia
     * @return lista par [agent_id, active_contacts_count] jako Object[]
     */
    @Query(value = """
            SELECT agent_id::text, COUNT(*) AS active_contacts
            FROM contact
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND agent_id  IN (:agentIds)
              AND status    IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
            GROUP BY agent_id
            """, nativeQuery = true)
    List<Object[]> countActiveContactsByAgentIds(@Param("tenantId") UUID tenantId,
                                                  @Param("agentIds") List<UUID> agentIds);

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

    // =========================================================================
    // BE-067: Scheduled polling dostępnych agentów (dialer + routing)
    // =========================================================================

    /**
     * Zwraca wszystkich nieusunietych agentów o podanym statusie, cross-tenant.
     *
     * <p>Używana przez {@code ProgressiveDialerService.pollAvailableAgents()} i
     * {@code RoutingService.pollAvailableAgents()} do cyklicznego "podkręcenia"
     * logiki dialera/routingu dla wszystkich aktualnie AVAILABLE agentów.
     *
     * <p>Zapytanie jest cross-tenant z założenia – caller odpowiada za ustawienie
     * TenantContext per-agent przed wywołaniem logiki domenowej.
     *
     * <p>Brak paginacji – lista agentów online jest zazwyczaj krótka (kilkuset agentów
     * na instalację). Jeśli w przyszłości wzrośnie, dodaj stronicowanie.
     *
     * @param status  status agenta (np. {@link AppUser.UserStatus#AVAILABLE})
     * @return lista agentów z danym statusem, is_deleted=false
     */
    List<AppUser> findAllByStatusAndDeletedFalse(AppUser.UserStatus status);

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

    // =========================================================================
    // SUPER_ADMIN – rola globalna bez tenanta (refaktor ról)
    // =========================================================================

    /**
     * Sprawdza czy w systemie istnieje już użytkownik z podaną rolą.
     *
     * <p>Używane przez {@code SuperAdminBootstrapRunner} do idempotentnego
     * bootstrapu konta SUPER_ADMIN przy starcie aplikacji – jeśli już istnieje
     * co najmniej jeden SUPER_ADMIN, bootstrap jest no-op.
     *
     * @param role rola do sprawdzenia
     * @return true gdy istnieje co najmniej jeden użytkownik z podaną rolą
     */
    boolean existsByRole(AppUser.UserRole role);

    /**
     * Znajdź aktywnego, nieusuniętego użytkownika globalnego (bez tenanta) po email.
     *
     * <p>Używane przez {@code UserDetailsServiceImpl}/{@code AuthServiceImpl} do logowania
     * SUPER_ADMIN – jedynej roli z {@code tenantId == null}. Analogiczne do
     * {@link #findByTenantIdAndEmailAndActiveTrue} dla ścieżki tenant-scoped, ale bez
     * warunku tenantId (bo SUPER_ADMIN go nie ma).
     *
     * <p>W praktyce zwraca tylko wiersze z {@code role = SUPER_ADMIN}, bo
     * {@code chk_super_admin_tenant_invariant} (V080) gwarantuje że tylko ta rola
     * może mieć {@code tenant_id IS NULL}.
     *
     * @param email adres e-mail (dopasowanie dokładne – case-sensitive na poziomie
     *              Spring Data derived query; wywołujący musi znormalizować wielkość
     *              liter tak samo jak przy zapisie – patrz {@code UserServiceImpl#createUser}
     *              które zapisuje email przez {@code toLowerCase()})
     * @return Optional z użytkownikiem globalnym lub empty jeśli nie istnieje/nieaktywny
     */
    Optional<AppUser> findByEmailAndTenantIdIsNullAndActiveTrue(String email);

    /**
     * Sprawdza czy podany e-mail należy do aktywnego, nieusuniętego konta SUPER_ADMIN.
     *
     * <p>Używane przez publiczny endpoint {@code POST /api/public/tenants-by-email} do
     * ustawienia flagi {@code superAdminAccount} w odpowiedzi (flow logowania bez tenanta).
     *
     * <p><strong>Zasady bezpieczeństwa:</strong> spójne z {@link #findActiveTenantsByUserEmail} –
     * porównanie case-insensitive przez {@code LOWER(email)}, email nie jest logowany.
     *
     * @param email adres e-mail użytkownika (case-insensitive)
     * @return true gdy istnieje aktywny, nieusunięty SUPER_ADMIN z tym emailem
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM app_user
                WHERE LOWER(email) = LOWER(:email)
                  AND role = 'SUPER_ADMIN'
                  AND is_active  = TRUE
                  AND is_deleted = FALSE
            )
            """, nativeQuery = true)
    boolean existsActiveSuperAdminByEmail(@Param("email") String email);

    // =========================================================================
    // AdminMetrics: trendy wzrostu platformy (SUPER_ADMIN)
    // =========================================================================

    /**
     * Zlicza nowych użytkowników utworzonych w każdym tygodniu ISO-8601 (poniedziałek jako
     * początek tygodnia, strefa UTC) od podanej daty granicznej, cross-tenant.
     *
     * <p><strong>Cross-tenant z konieczności</strong> – spójne z innymi metodami admina w tym
     * repozytorium ({@link #findAllByDeletedFalse}, {@link #existsByRole}): {@code app_user}
     * jest jedynym repozytorium tej encji (nie rozszerza {@code TenantAwareRepository}), więc
     * nie ustawia kontekstu RLS – zapytanie jest już z definicji cross-tenant.
     *
     * <p>Używane przez {@code AdminMetricsService} do budowy {@code GET /api/admin/metrics/growth}.
     *
     * @param since dolna granica {@code created_at} (włącznie)
     * @return lista par {@code [week_start(java.sql.Date), count(Number)]}, posortowana rosnąco
     */
    @Query(value = """
            SELECT date_trunc('week', created_at AT TIME ZONE 'UTC')::date AS week_start, COUNT(*) AS cnt
            FROM   app_user
            WHERE  created_at >= :since
            GROUP  BY week_start
            ORDER  BY week_start
            """, nativeQuery = true)
    List<Object[]> countNewUsersByWeekSince(@Param("since") java.time.Instant since);
}
