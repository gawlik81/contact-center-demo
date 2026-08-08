package com.contactcenter.domain.tenant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium JPA dla encji {@link Tenant}.
 *
 * <p>Tabela {@code tenant} nie jest objęta RLS (Row Level Security) w trybie per-tenant
 * – jej rekordami są same tenanty. Dlatego repozytorium implementuje {@link JpaRepository}
 * bezpośrednio (bez rozszerzania {@link TenantAwareRepository}).
 *
 * <p>Wszystkie operacje zapisu/odczytu wymagają roli ADMIN (weryfikowanej przez
 * Spring Security w {@code TenantController} i {@code TenantService}).
 *
 * <p><strong>Widoczność package-private:</strong> repozytorium jest dostępne wyłącznie
 * wewnątrz pakietu {@code domain.tenant} (przez {@link TenantServiceImpl} i
 * {@link TenantResourceLimitServiceImpl}). Klasy z innych pakietów powinny korzystać
 * z publicznego interfejsu {@link TenantService} (np. {@link TenantService#getActiveTenants()},
 * {@link TenantService#getAllTenants()}, {@link TenantService#findTenantEntity(UUID)},
 * {@link TenantService#updateTenantConfig(UUID, java.util.Map)}).
 */
@Repository
interface TenantRepository extends JpaRepository<Tenant, UUID> {

    /**
     * Sprawdza unikalność nazwy tenanta (case-insensitive).
     * Używane przy tworzeniu i aktualizacji tenanta.
     *
     * @param name nazwa do sprawdzenia (będzie porównywana LOWER())
     * @return true jeśli nazwa jest już zajęta
     */
    @Query("SELECT COUNT(t) > 0 FROM Tenant t WHERE LOWER(t.name) = LOWER(:name)")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    /**
     * Sprawdza unikalność nazwy tenanta z wyłączeniem konkretnego tenanta.
     * Używane przy aktualizacji – pozwala zachować własną aktualną nazwę.
     *
     * @param name     nowa nazwa
     * @param tenantId UUID tenanta do wyłączenia z porównania
     * @return true jeśli nazwa jest zajęta przez innego tenanta
     */
    @Query("SELECT COUNT(t) > 0 FROM Tenant t WHERE LOWER(t.name) = LOWER(:name) AND t.id <> :tenantId")
    boolean existsByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("tenantId") UUID tenantId);

    /**
     * Znajdź tenanta po nazwie (case-insensitive).
     * Używane do walidacji duplikatów.
     *
     * @param name nazwa tenanta
     * @return Optional z encją tenanta
     */
    @Query("SELECT t FROM Tenant t WHERE LOWER(t.name) = LOWER(:name)")
    Optional<Tenant> findByNameIgnoreCase(@Param("name") String name);

    /**
     * Lista wszystkich tenantów posortowana po nazwie.
     * Używana przez endpoint GET /api/tenants (ADMIN).
     *
     * @return lista tenantów posortowana po name ASC
     */
    List<Tenant> findAllByOrderByNameAsc();

    /**
     * Lista tenantów z opcjonalnym filtrowaniem po nazwie i/lub statusie.
     *
     * <p>Parametry {@code name} i {@code status} są opcjonalne – wartość {@code null}
     * wyłącza filtrowanie po danym kryterium.
     *
     * <p>Zapytanie jest natywne (SQL), ponieważ Hibernate 6 błędnie typuje parametr
     * String jako {@code bytea} gdy ten sam bind parameter pojawia się zarówno
     * w predykacie {@code IS NULL} jak i w wywołaniu {@code LOWER()} w tym samym
     * wyrażeniu JPQL – PostgreSQL zgłasza {@code function lower(bytea) does not exist}.
     * Zapytanie natywne z jawnym {@code CAST(:name AS TEXT)} rozwiązuje problem.
     *
     * <p>Logika filtrowania:
     * <ul>
     *   <li>Filtr nazwy: {@code CAST(:name AS TEXT) IS NULL OR LOWER(name) LIKE LOWER('%' || :name || '%')}
     *       – wyszukiwanie fragmentu nazwy case-insensitive (LIKE)</li>
     *   <li>Filtr statusu: {@code CAST(:status AS TEXT) IS NULL OR status::TEXT = :status}
     *       – dokładne dopasowanie enum {@code tenant_status}</li>
     * </ul>
     *
     * @param name   fragment nazwy do wyszukania (LIKE %name%), lub {@code null} by pominąć
     * @param status status tenanta jako String (wartość enum: ACTIVE/INACTIVE/SUSPENDED),
     *               lub {@code null} by pominąć
     * @return lista pasujących tenantów posortowana po name ASC
     */
    @Query(value = """
            SELECT tenant_id, config, created_at, name, status, updated_at
            FROM   tenant
            WHERE  (CAST(:name AS TEXT) IS NULL OR LOWER(name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%'))
              AND  (CAST(:status AS TEXT) IS NULL OR status::TEXT = CAST(:status AS TEXT))
            ORDER  BY name ASC
            """, nativeQuery = true)
    List<Tenant> findAllByOptionalFilters(
            @Param("name") String name,
            @Param("status") String status
    );

    /**
     * Paginowana wersja {@link #findAllByOptionalFilters} – do użycia przez list endpoint API.
     */
    @Query(value = """
            SELECT tenant_id, config, created_at, name, status, updated_at
            FROM   tenant
            WHERE  (CAST(:name AS TEXT) IS NULL OR LOWER(name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%'))
              AND  (CAST(:status AS TEXT) IS NULL OR status::TEXT = CAST(:status AS TEXT))
            ORDER  BY name ASC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM   tenant
            WHERE  (CAST(:name AS TEXT) IS NULL OR LOWER(name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%'))
              AND  (CAST(:status AS TEXT) IS NULL OR status::TEXT = CAST(:status AS TEXT))
            """,
            nativeQuery = true)
    Page<Tenant> findPageByOptionalFilters(
            @Param("name") String name,
            @Param("status") String status,
            Pageable pageable
    );

    /**
     * Zliczenie aktywnych agentów dla tenanta.
     * Używane przez {@code TenantResourceLimitService} do sprawdzenia limitu agentów.
     *
     * @param tenantId UUID tenanta
     * @return liczba aktywnych agentów (is_deleted=false, role=AGENT)
     */
    @Query(value = """
            SELECT COUNT(*) FROM app_user
            WHERE tenant_id = :tenantId
              AND role = 'AGENT'
              AND is_deleted = FALSE
            """, nativeQuery = true)
    long countActiveAgentsByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Zliczenie aktywnych kolejek dla tenanta.
     * Używane przez {@code TenantResourceLimitService} do sprawdzenia limitu kolejek.
     * Tabela {@code queue} posiada kolumnę {@code is_active} (bez is_deleted).
     *
     * @param tenantId UUID tenanta
     * @return liczba aktywnych kolejek (is_active=true)
     */
    @Query(value = """
            SELECT COUNT(*) FROM queue
            WHERE tenant_id = :tenantId
              AND is_active = TRUE
            """, nativeQuery = true)
    long countActiveQueuesByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Zliczenie aktywnych kampanii dla tenanta.
     * Używane przez {@code TenantResourceLimitService} do sprawdzenia limitu kampanii.
     * Tabela {@code campaign} nie ma kolumny is_deleted – filtrujemy tylko po statusie.
     *
     * @param tenantId UUID tenanta
     * @return liczba aktywnych kampanii (status != STOPPED i != COMPLETED)
     */
    @Query(value = """
            SELECT COUNT(*) FROM campaign
            WHERE tenant_id = :tenantId
              AND status NOT IN ('STOPPED', 'COMPLETED')
            """, nativeQuery = true)
    long countActiveCampaignsByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Zlicza nowych tenantów utworzonych w każdym tygodniu ISO-8601 (poniedziałek jako początek
     * tygodnia, strefa UTC) od podanej daty granicznej.
     *
     * <p>Tabela {@code tenant} jest globalna (nie ma RLS – patrz Javadoc klasy) – zapytanie
     * jest bezpieczne cross-tenant z definicji.
     *
     * <p>Używane przez {@code AdminMetricsService} do budowy {@code GET /api/admin/metrics/growth}.
     *
     * @param since dolna granica {@code created_at} (włącznie)
     * @return lista par {@code [week_start(java.sql.Date), count(Number)]}, posortowana rosnąco
     */
    @Query(value = """
            SELECT date_trunc('week', created_at AT TIME ZONE 'UTC')::date AS week_start, COUNT(*) AS cnt
            FROM   tenant
            WHERE  created_at >= :since
            GROUP  BY week_start
            ORDER  BY week_start
            """, nativeQuery = true)
    List<Object[]> countNewTenantsByWeekSince(@Param("since") Instant since);
}
