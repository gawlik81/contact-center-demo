package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

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
}
