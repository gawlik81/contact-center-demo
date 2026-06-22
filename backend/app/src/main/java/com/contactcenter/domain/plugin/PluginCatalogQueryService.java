package com.contactcenter.domain.plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port odczytu dla konsumentów spoza pakietu {@code domain.plugin} (w szczególności
 * {@code domain.plugin.runtime.PluginRuntimeManagerImpl}, BE-101), który potrzebuje
 * {@link PluginVersion} i {@link TenantPluginInstallation} bez dostępu do package-private
 * repozytoriów ({@code PluginVersionRepository}, {@code TenantPluginInstallationRepository}).
 *
 * <p>Wzorzec analogiczny do {@code CustomerService#findById}/{@code ContactService#findContactEntity}
 * — delegacja zwracająca encję (nie DTO) dla konsumentów wewnątrz backendu, nie dla API REST.
 */
public interface PluginCatalogQueryService {

    /**
     * Pobiera wersję pluginu po identyfikatorze.
     *
     * @param pluginVersionId identyfikator {@code plugin_version}
     * @return encja {@link PluginVersion} lub empty gdy nie istnieje (tabela globalna, brak RLS)
     */
    Optional<PluginVersion> findVersionById(UUID pluginVersionId);

    /**
     * Pobiera instalację pluginu, zweryfikowaną pod kątem przynależności do tenanta (RLS +
     * {@code assertSameTenant}).
     *
     * @param tenantId       tenant właściciel instalacji
     * @param installationId identyfikator {@code tenant_plugin_installation}
     * @return encja {@link TenantPluginInstallation} lub empty gdy nie istnieje dla tego tenanta
     */
    Optional<TenantPluginInstallation> findInstallation(UUID tenantId, UUID installationId);

    /**
     * Ustawia {@code plugin_version.status = REVOKED} (BE-106, ARCHITECTURE.md §11.11
     * "platform-level kill switch") — operacja administratora systemowego, nieodwracalna
     * w ramach tego serwisu (brak metody "un-revoke", zgodnie z założeniem kill switcha).
     *
     * @param pluginVersionId wersja pluginu do zablokowania globalnie
     * @return zaktualizowana encja
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy wersja nie istnieje
     */
    PluginVersion revokeVersion(UUID pluginVersionId);

    /**
     * Wyszukuje wszystkie instalacje (wszystkich tenantów) danej wersji pluginu, które mają
     * {@code enabled=true} w DB — niezależnie od ich tenanta.
     *
     * <p><strong>Cross-tenant z konieczności</strong> (BE-106, "platform-level kill switch",
     * ARCHITECTURE.md §11.11): operacja administratora systemowego musi dotknąć instalacje
     * wszystkich tenantów na raz. Tabela {@code tenant_plugin_installation} ma RLS
     * ({@code FORCE ROW LEVEL SECURITY}, V075) — projekt nie posiada ani jednego wzorca
     * bypassu RLS (zweryfikowano), więc implementacja iteruje po wszystkich tenantach
     * ({@code TenantService#getAllTenants}, tabela {@code tenant} jest globalna/bez RLS) i dla
     * każdego ustawia kontekst RLS jawnie przed zapytaniem — N zapytań, ale zero bypassu
     * bezpieczeństwa. Akceptowalne, bo {@code revoke} jest rzadką operacją administracyjną,
     * nie ścieżką "hot path".
     *
     * @param pluginVersionId wersja pluginu, której instalacje szukamy
     * @return lista instalacji z {@code enabled=true}, wszystkich tenantów, w porządku
     *         iteracji po tenantach (bez zagwarantowanego sortowania ponadto)
     */
    List<TenantPluginInstallation> findAllEnabledAcrossTenantsByVersionId(UUID pluginVersionId);
}
