package com.contactcenter.domain.plugin;

import com.contactcenter.domain.repository.TenantAwareRepository;
import com.contactcenter.infrastructure.persistence.converter.EncryptedStringConverter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium instalacji pluginów per tenant (CRUD + zmiana stanu enabled/disabled).
 *
 * <p>Operuje na tabeli {@code tenant_plugin_installation} (V075). Wszystkie zapytania
 * używają natywnego SQL przez {@link jakarta.persistence.EntityManager} z jawnym CAST
 * dla UUID ({@code CAST(:param AS uuid)}) — wzorzec analogiczny do
 * {@code CustomDispositionRepository} (EPIC-27).
 *
 * <p>Każde zapytanie poprzedzone jest wywołaniem {@code setTenantContextInDb(tenantId)}
 * w celu ustawienia kontekstu RLS w PostgreSQL. Każdy zapis poprzedzony jest
 * {@code assertSameTenant(entity.getTenantId())}.
 *
 * <p><strong>Szyfrowanie {@code installation_config} (BE-108):</strong> ta klasa używa
 * wyłącznie natywnego SQL (patrz wyżej), więc mechanizm JPA {@code @Convert} NIE działa
 * tutaj — Hibernate aplikuje konwertery atrybutów tylko przy ładowaniu/zapisie przez
 * JPQL/Criteria/{@code EntityManager.find}, nigdy przy ręcznym mapowaniu wierszy z natywnego
 * SQL. Z tego powodu {@link TenantPluginInstallation#installationConfig} nie ma adnotacji
 * {@code @Convert} (byłaby myląca — wyglądałaby jak działa, a nie działałaby w tym repo).
 * Szyfrowanie/deszyfrowanie jest wołane RĘCZNIE: {@link #encryptInstallationConfig} przy
 * zapisie ({@link #updateInstallationConfig}), {@link #decryptInstallationConfig} przy
 * każdym odczycie wiersza ({@link #mapRow}). Format w kolumnie {@code jsonb}: ciphertext
 * Base64 zawijany w obiekt JSON {@code {"encrypted": "<base64>"}} (nie goły skalar JSON) —
 * pozwala to na zwykły {@code CAST(:json AS jsonb)} bez ręcznego escapingu cytowania przy
 * odczycie przez {@code row[N].toString()}.
 *
 * <p><strong>Widoczność package-private:</strong> dostępne wyłącznie wewnątrz pakietu
 * {@code domain.plugin}, przez {@link PluginRegistrationService}.
 */
@Slf4j
@Repository
class TenantPluginInstallationRepository extends TenantAwareRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    /** Klucz JSON pod którym przechowywany jest ciphertext Base64 w kolumnie {@code jsonb}. */
    private static final String ENCRYPTED_JSON_KEY = "encrypted";

    /** Metryka pominiętych wierszy listy "wszystkie włączone" (BE-245 W3), tag {@code reason=decrypt|format}. */
    static final String METRIC_SKIPPED = "plugin.sync.installation.skipped";

    private final EncryptedStringConverter encryptedStringConverter;
    private final MeterRegistry meters;

    TenantPluginInstallationRepository(EncryptedStringConverter encryptedStringConverter, MeterRegistry meters) {
        this.encryptedStringConverter = encryptedStringConverter;
        this.meters = meters;
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    @Transactional(readOnly = true)
    Optional<TenantPluginInstallation> findByIdAndTenantId(UUID id, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, plugin_version_id, enabled, granted_permissions,
                       health_status, consecutive_failure_count, installation_config,
                       installed_by_user_id, installed_at, updated_at, degraded_reason
                FROM tenant_plugin_installation
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                LIMIT 1
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    /**
     * Wyszukuje instalacje z {@code enabled=true} dla danej wersji pluginu, w ramach
     * kontekstu RLS aktualnie ustawionego na tenanta {@code tenantId} (wołający musi sam
     * wywołać {@link #findAllEnabledByPluginVersionIdForTenant} — ta metoda ustawia kontekst
     * jawnie przed zapytaniem, więc bezpieczna do wywołania w pętli po wielu tenantach, bo
     * każde wywołanie resetuje {@code app.current_tenant_id} na nowo przed SELECT-em).
     *
     * <p>Używana wyłącznie przez {@code PluginCatalogQueryServiceImpl#findAllEnabledAcrossTenantsByVersionId}
     * (BE-106, platform-level kill switch) — N wywołań, jedno per tenant, zero bypassu RLS.
     */
    @Transactional(readOnly = true)
    List<TenantPluginInstallation> findAllEnabledByPluginVersionIdForTenant(UUID pluginVersionId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, plugin_version_id, enabled, granted_permissions,
                       health_status, consecutive_failure_count, installation_config,
                       installed_by_user_id, installed_at, updated_at, degraded_reason
                FROM tenant_plugin_installation
                WHERE plugin_version_id = CAST(:pluginVersionId AS uuid)
                  AND tenant_id         = CAST(:tenantId AS uuid)
                  AND enabled           = TRUE
                """)
                .setParameter("pluginVersionId", pluginVersionId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    /**
     * Wyszukuje WSZYSTKIE instalacje z {@code enabled=true} dla danego tenanta (niezależnie od
     * {@code plugin_version_id}), w ramach kontekstu RLS aktualnie ustawionego na tenanta
     * {@code tenantId} — analogicznie do {@link #findAllEnabledByPluginVersionIdForTenant},
     * ta metoda ustawia kontekst jawnie przed zapytaniem, więc bezpieczna do wywołania w pętli
     * po wielu tenantach.
     *
     * <p>Używana przez {@code PluginCatalogQueryServiceImpl#findAllEnabledAcrossAllTenants} (odbudowa
     * {@code PluginRegistry} po restarcie i rekoncyliacja między instancjami, BE-178) oraz
     * {@code PluginCatalogQueryServiceImpl#findAllEnabledForTenant} (leniwe doładowanie w ścieżce wywołania).
     */
    @Transactional(readOnly = true)
    List<TenantPluginInstallation> findAllEnabledForTenant(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, plugin_version_id, enabled, granted_permissions,
                       health_status, consecutive_failure_count, installation_config,
                       installed_by_user_id, installed_at, updated_at, degraded_reason
                FROM tenant_plugin_installation
                WHERE tenant_id = CAST(:tenantId AS uuid)
                  AND enabled   = TRUE
                """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return mapRowsIsolated(rows);
    }

    /**
     * Usuwa fizycznie (DELETE) jedną instalację — uninstall (BE-106, ARCHITECTURE.md §11.11:
     * "deletes the TENANT_PLUGIN_INSTALLATION + bindings; PLUGIN_INVOCATION_LOG history is
     * retained (tenant_plugin_installation_id FK uses ON DELETE SET NULL)"). Bindingi
     * ({@code tenant_plugin_extension_binding}) są usuwane automatycznie przez
     * {@code ON DELETE CASCADE} (V076) — nie wymaga dodatkowego zapytania.
     *
     * @return liczba usuniętych wierszy (0 = instalacja nie istnieje dla tego tenanta)
     */
    @Transactional
    int delete(UUID id, UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        int deleted = em.createNativeQuery("""
                DELETE FROM tenant_plugin_installation
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[TenantPluginInstallationRepo] DELETE instalacja: id={}, tenant={}, wierszy={}",
                id, tenantId, deleted);

        return deleted;
    }

    @Transactional(readOnly = true)
    List<TenantPluginInstallation> findAllByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[TenantPluginInstallationRepo] findAllByTenantId: tenant={}", tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, plugin_version_id, enabled, granted_permissions,
                       health_status, consecutive_failure_count, installation_config,
                       installed_by_user_id, installed_at, updated_at, degraded_reason
                FROM tenant_plugin_installation
                WHERE tenant_id = CAST(:tenantId AS uuid)
                ORDER BY installed_at DESC
                """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Tworzy nową instalację przez natywny INSERT ... RETURNING.
     *
     * <p>UUID generowany przez {@code gen_random_uuid()} w bazie. Naruszenie unikalnego
     * indeksu {@code uq_tenant_plugin_installation_version} (duplikat tenant_id+plugin_version_id)
     * propaguje się jako {@link org.springframework.dao.DataIntegrityViolationException}
     * (translacja Spring na klasie oznaczonej {@code @Repository}) — mapowane globalnie
     * na HTTP 409 przez {@code GlobalExceptionHandler}.
     *
     * @param installation encja do zapisania (id/installedAt/updatedAt mogą być null — ustawi je DB).
     *                      {@code installationConfig}, jeśli ustawione, MUSI być plaintext JSON —
     *                      ta metoda go szyfruje przed zapisem (tak jak {@link #updateInstallationConfig}),
     *                      nigdy nie zapisuje surowej wartości encji 1:1 do kolumny (BE-108, finding
     *                      code review: install() ustawia dziś zawsze {@code null}, ale ta metoda
     *                      musi pozostać bezpieczna nawet jeśli to się zmieni w przyszłości — inaczej
     *                      kolejny odczyt przez {@link #decryptInstallationConfig} rzuciłby
     *                      {@code IllegalStateException}, bo wartość nie byłaby zawinięta w
     *                      {@code {"encrypted": "..."}}).
     * @return zapisana encja z uzupełnionym id i timestamps
     */
    @Transactional
    TenantPluginInstallation insert(TenantPluginInstallation installation) {
        assertSameTenant(installation.getTenantId());
        // V153 invariant: DEGRADED needs a cause (a pair check like the one on the update paths, not a silent legacy NULL)
        if (TenantPluginInstallation.HealthStatus.DEGRADED.equals(installation.getHealthStatus())
                && installation.getDegradedReason() == null) {
            throw new IllegalArgumentException("degraded_reason must be set when inserting health_status = DEGRADED");
        }
        setTenantContextInDb(installation.getTenantId());

        log.info("[TenantPluginInstallationRepo] INSERT instalacja: tenant={}, pluginVersion={}",
                installation.getTenantId(), installation.getPluginVersionId());

        String encryptedInstallationConfig = encryptInstallationConfig(installation.getInstallationConfig());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                INSERT INTO tenant_plugin_installation
                    (id, tenant_id, plugin_version_id, enabled, granted_permissions,
                     health_status, consecutive_failure_count, installation_config,
                     installed_by_user_id, installed_at, updated_at, degraded_reason)
                VALUES (
                    gen_random_uuid(),
                    CAST(:tenantId AS uuid),
                    CAST(:pluginVersionId AS uuid),
                    :enabled,
                    CAST(:grantedPermissions AS jsonb),
                    :healthStatus,
                    :consecutiveFailureCount,
                    CAST(:installationConfig AS jsonb),
                    CAST(:installedByUserId AS uuid),
                    NOW(),
                    NOW(),
                    :degradedReason
                )
                RETURNING id, tenant_id, plugin_version_id, enabled, granted_permissions,
                          health_status, consecutive_failure_count, installation_config,
                          installed_by_user_id, installed_at, updated_at, degraded_reason
                """)
                .setParameter("tenantId", installation.getTenantId().toString())
                .setParameter("pluginVersionId", installation.getPluginVersionId().toString())
                .setParameter("enabled", installation.isEnabled())
                .setParameter("grantedPermissions", writeJsonList(installation.getGrantedPermissions()))
                .setParameter("healthStatus", installation.getHealthStatus())
                .setParameter("consecutiveFailureCount", installation.getConsecutiveFailureCount())
                // the reason is meaningful only for DEGRADED (V153 invariant, kept by the application)
                .setParameter("degradedReason", TenantPluginInstallation.HealthStatus.DEGRADED
                        .equals(installation.getHealthStatus()) && installation.getDegradedReason() != null
                        ? installation.getDegradedReason().name() : null)
                .setParameter("installationConfig", encryptedInstallationConfig)
                .setParameter("installedByUserId",
                        installation.getInstalledByUserId() != null
                                ? installation.getInstalledByUserId().toString() : null)
                .getResultList();

        TenantPluginInstallation saved = mapRow(rows.get(0));

        log.info("[TenantPluginInstallationRepo] Instalacja utworzona: id={}, tenant={}",
                saved.getId(), saved.getTenantId());

        return saved;
    }

    /**
     * Zmienia flagę {@code enabled} jednej instalacji.
     *
     * @return liczba zaktualizowanych wierszy (0 = instalacja nie istnieje dla tego tenanta)
     */
    @Transactional
    int updateEnabled(UUID id, UUID tenantId, boolean enabled) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        int updated = em.createNativeQuery("""
                UPDATE tenant_plugin_installation
                SET enabled    = :enabled,
                    updated_at = NOW()
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("enabled", enabled)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[TenantPluginInstallationRepo] UPDATE enabled={}: id={}, tenant={}, wierszy={}",
                enabled, id, tenantId, updated);

        return updated;
    }

    /**
     * SQL twin of {@link TenantPluginInstallation#effectiveDegradedReason()} == CIRCUIT_OPEN: a DEGRADED row caused by
     * the circuit breaker. The second disjunct is the LEGACY fallback (DEGRADED written by an old instance during a
     * rolling deploy has no reason: counter >= threshold = circuit).
     * remove after strict CHECK migration (DB follow-up): the {@code degraded_reason IS NULL} branch.
     * Requires the named parameter {@code :threshold}.
     */
    private static final String CIRCUIT_DEGRADED = """
            (health_status = 'DEGRADED'
             AND (degraded_reason = 'CIRCUIT_OPEN'
                  OR (degraded_reason IS NULL AND consecutive_failure_count >= :threshold)))
            """;

    /**
     * SQL twin of {@code effectiveDegradedReason() == CONFIG_LOAD_FAILED} (complement of {@link #CIRCUIT_DEGRADED}
     * inside DEGRADED), including the legacy fallback. Requires {@code :threshold}.
     * remove after strict CHECK migration (DB follow-up): the {@code degraded_reason IS NULL} branch.
     */
    private static final String CONFIG_DEGRADED = """
            (health_status = 'DEGRADED'
             AND (degraded_reason = 'CONFIG_LOAD_FAILED'
                  OR (degraded_reason IS NULL AND consecutive_failure_count < :threshold)))
            """;

    /**
     * The circuit breaker opened: {@code health_status = DEGRADED}, {@code degraded_reason = CIRCUIT_OPEN} and the
     * failure counter IN ONE statement (V153 invariant). It overwrites {@code CONFIG_LOAD_FAILED} (circuit wins, see
     * {@link DegradedReason}) but - conditional UPDATE (CAS) - NEVER {@code DISABLED_BY_ADMIN}: a delayed circuit write
     * after the admin disabled the plugin must not resurrect it as DEGRADED.
     *
     * <p>This is the only unconditional-looking health write left; every transition back to HEALTHY goes through the
     * {@code clear*} CAS methods (the former general {@code updateHealthStatus} was removed: one caller, and a way
     * around the CAS).
     *
     * @return number of updated rows (0 = the installation does not exist for this tenant or is DISABLED_BY_ADMIN)
     */
    @Transactional
    int markDegradedByCircuit(UUID id, UUID tenantId, int consecutiveFailureCount) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        int updated = em.createNativeQuery("""
                UPDATE tenant_plugin_installation
                SET health_status              = 'DEGRADED',
                    degraded_reason            = 'CIRCUIT_OPEN',
                    consecutive_failure_count  = :consecutiveFailureCount,
                    updated_at                 = NOW()
                WHERE id            = CAST(:id AS uuid)
                  AND tenant_id     = CAST(:tenantId AS uuid)
                  AND health_status <> 'DISABLED_BY_ADMIN'
                """)
                .setParameter("consecutiveFailureCount", consecutiveFailureCount)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[TenantPluginInstallationRepo] DEGRADED(CIRCUIT_OPEN), failures={}: id={}, tenant={}, wierszy={}",
                consecutiveFailureCount, id, tenantId, updated);

        return updated;
    }

    /**
     * BE-245 W2: {@code HEALTHY -> DEGRADED(CONFIG_LOAD_FAILED)} after a failed plugin load with a new configuration.
     * Conditional UPDATE (CAS): only from {@code HEALTHY} - never overwrites a circuit DEGRADED nor
     * DISABLED_BY_ADMIN, idempotent. The failure counter is NOT touched (a failed load is not an invocation failure).
     *
     * @return {@code 1} when the status changed, {@code 0} when the row was not {@code HEALTHY} (or does not exist)
     */
    @Transactional
    int markDegradedByConfigLoad(UUID id, UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        int updated = em.createNativeQuery("""
                UPDATE tenant_plugin_installation
                SET health_status   = 'DEGRADED',
                    degraded_reason = 'CONFIG_LOAD_FAILED',
                    updated_at      = NOW()
                WHERE id            = CAST(:id AS uuid)
                  AND tenant_id     = CAST(:tenantId AS uuid)
                  AND health_status = 'HEALTHY'
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        if (updated > 0) {
            log.info("[TenantPluginInstallationRepo] CAS HEALTHY->DEGRADED(CONFIG_LOAD_FAILED): id={}, tenant={}",
                    id, tenantId);
        }
        return updated;
    }

    /**
     * BE-245 W2: removes {@code DEGRADED} (conditional UPDATE, CAS on the cause) and clears the reason.
     *
     * @param includeCircuit {@code false} - only a DEGRADED caused by a failed load/configuration; {@code true} - also
     *                       a circuit DEGRADED (after a circuit reset by an installation change, when the open gate
     *                       was dropped on every instance anyway)
     * @return {@code 1} when the status was removed, {@code 0} when there was nothing to remove
     */
    @Transactional
    int clearDegraded(UUID id, UUID tenantId, boolean includeCircuit) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        int updated = em.createNativeQuery("""
                UPDATE tenant_plugin_installation
                SET health_status             = 'HEALTHY',
                    degraded_reason           = NULL,
                    consecutive_failure_count = 0,
                    updated_at                = NOW()
                WHERE id            = CAST(:id AS uuid)
                  AND tenant_id     = CAST(:tenantId AS uuid)
                  AND health_status = 'DEGRADED'
                  AND (:includeCircuit = TRUE OR\s""" + CONFIG_DEGRADED + """
                      )
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .setParameter("includeCircuit", includeCircuit)
                .setParameter("threshold", TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD)
                .executeUpdate();

        if (updated > 0) {
            log.info("[TenantPluginInstallationRepo] CAS DEGRADED->HEALTHY (includeCircuit={}): id={}, tenant={}",
                    includeCircuit, id, tenantId);
        }
        return updated;
    }

    /**
     * BE-245 W2: circuit recovery. Conditional UPDATE: removes ONLY a circuit DEGRADED and resets a stray failure
     * counter of a {@code HEALTHY} row; it does NOT touch a DEGRADED caused by a failed configuration load nor
     * DISABLED_BY_ADMIN. The reason is cleared in the same statement.
     *
     * @return number of changed rows
     */
    @Transactional
    int clearCircuitDegraded(UUID id, UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        return em.createNativeQuery("""
                UPDATE tenant_plugin_installation
                SET health_status             = 'HEALTHY',
                    degraded_reason           = NULL,
                    consecutive_failure_count = 0,
                    updated_at                = NOW()
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                  AND (""" + CIRCUIT_DEGRADED + """
                       OR (health_status = 'HEALTHY' AND consecutive_failure_count <> 0))
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .setParameter("threshold", TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD)
                .executeUpdate();
    }

    /**
     * Zapisuje (REPLACE) konfigurację instalacji — szyfruje JSON plaintext przekazany przez
     * wołającego ({@link PluginRegistrationServiceImpl#updateConfig}, BE-108) i zapisuje
     * ciphertext owinięty w {@code {"encrypted": "..."}} do kolumny {@code installation_config}.
     *
     * @param plaintextConfigJson plaintext JSON (mapa klucz→wartość) do zaszyfrowania i zapisania
     * @return liczba zaktualizowanych wierszy (0 = instalacja nie istnieje dla tego tenanta)
     */
    @Transactional
    int updateInstallationConfig(UUID id, UUID tenantId, String plaintextConfigJson) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        String encryptedJsonWrapper = encryptInstallationConfig(plaintextConfigJson);

        int updated = em.createNativeQuery("""
                UPDATE tenant_plugin_installation
                SET installation_config = CAST(:installationConfig AS jsonb),
                    updated_at          = NOW()
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("installationConfig", encryptedJsonWrapper)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[TenantPluginInstallationRepo] UPDATE installation_config (zaszyfrowane): id={}, tenant={}, wierszy={}",
                id, tenantId, updated);

        return updated;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Mapuje wiersze listy "wszystkie włączone" tak, by JEDEN uszkodzony wiersz ({@code installation_config} nie do
     * odszyfrowania / zły format wrappera) nie przerywał całej listy - od BE-245 rekoncyliacja jest jedyną siatką
     * bezpieczeństwa propagacji konfiguracji, a ta lista zasila ją dla WSZYSTKICH tenantów. Uszkodzony wiersz jest
     * pomijany, głośno logowany (ERROR z id instalacji, bez treści konfiguracji) i liczony w metryce
     * {@value #METRIC_SKIPPED}{@code {reason=decrypt|format}}.
     *
     * <p><strong>Awaria systemowa nie może wyglądać jak jeden uszkodzony wiersz (BE-245 W3).</strong> Rotacja/zły klucz
     * AES psuje KAŻDY wiersz, więc gdy pominięto wszystkie wiersze (&gt;= 1) albo więcej niż połowę, metoda rzuca
     * {@link InstallationConfigUnreadableException} zamiast zwracać (pustą/okrojoną) listę: rekoncyliacja kończy się
     * błędem (ERROR, bez zmian w załadowanych wtyczkach), a nie "nie ma żadnej włączonej instalacji".
     */
    private List<TenantPluginInstallation> mapRowsIsolated(List<Object[]> rows) {
        List<TenantPluginInstallation> result = new ArrayList<>(rows.size());
        int skippedDecrypt = 0;
        int skippedFormat = 0;
        for (Object[] row : rows) {
            try {
                result.add(mapRow(row));
            } catch (InstallationConfigFormatException e) {
                skippedFormat++;
                meters.counter(METRIC_SKIPPED, "reason", "format").increment();
                log.error("[TenantPluginInstallationRepo] Pominięto instalację {}: uszkodzony format zaszyfrowanej "
                        + "konfiguracji ({}). Nie będzie ładowana ani rekoncyliowana do czasu naprawy konfiguracji.",
                        row[0], e.getMessage());
            } catch (RuntimeException e) {
                skippedDecrypt++;
                meters.counter(METRIC_SKIPPED, "reason", "decrypt").increment();
                log.error("[TenantPluginInstallationRepo] Pominięto instalację {}: nie da się odszyfrować/zmapować "
                        + "konfiguracji ({}). Nie będzie ładowana ani rekoncyliowana do czasu naprawy.",
                        row[0], e.toString());
            }
        }
        int skipped = skippedDecrypt + skippedFormat;
        if (skipped > 0 && (skipped == rows.size() || skipped * 2 > rows.size())) {
            log.error("[TenantPluginInstallationRepo] Nie da się odczytać {} z {} włączonych instalacji (decrypt={}, "
                    + "format={}) - to wygląda na awarię SYSTEMOWĄ (rotacja/zły klucz szyfrowania {}), a nie "
                    + "pojedynczy uszkodzony wiersz. Przerywam odczyt zamiast zwracać okrojoną listę.",
                    skipped, rows.size(), skippedDecrypt, skippedFormat, "app.encryption.secret");
            throw new InstallationConfigUnreadableException(rows.size(), skippedDecrypt, skippedFormat);
        }
        return result;
    }

    /**
     * Mapuje wiersz zwrócony przez natywne zapytanie na encję {@link TenantPluginInstallation}.
     *
     * <p>Kolejność kolumn: id, tenant_id, plugin_version_id, enabled, granted_permissions,
     * health_status, consecutive_failure_count, installation_config, installed_by_user_id,
     * installed_at, updated_at, degraded_reason.
     */
    private TenantPluginInstallation mapRow(Object[] row) {
        TenantPluginInstallation i = new TenantPluginInstallation();
        i.setId(UUID.fromString(row[0].toString()));
        i.setTenantId(UUID.fromString(row[1].toString()));
        i.setPluginVersionId(UUID.fromString(row[2].toString()));
        i.setEnabled((Boolean) row[3]);
        i.setGrantedPermissions(readJsonList(row[4] != null ? row[4].toString() : null));
        i.setHealthStatus(row[5].toString());
        i.setConsecutiveFailureCount(((Number) row[6]).intValue());
        i.setInstallationConfig(decryptInstallationConfig(row[7]));
        i.setInstalledByUserId(row[8] != null ? UUID.fromString(row[8].toString()) : null);
        i.setInstalledAt(toInstant(row[9]));
        i.setUpdatedAt(toInstant(row[10]));
        i.setDegradedReason(row[11] != null ? DegradedReason.valueOf(row[11].toString()) : null);
        return i;
    }

    /**
     * Szyfruje plaintext JSON konfiguracji i zawija ciphertext Base64 w obiekt JSON
     * {@code {"encrypted": "<base64>"}} — format kolumny {@code jsonb}, patrz Javadoc klasy.
     *
     * @param plaintextConfigJson plaintext JSON do zaszyfrowania ({@code null} → zapisuje SQL NULL)
     * @return JSON do zapisania w kolumnie {@code installation_config}, lub {@code null}
     */
    private String encryptInstallationConfig(String plaintextConfigJson) {
        if (plaintextConfigJson == null) {
            return null;
        }
        String ciphertext = encryptedStringConverter.convertToDatabaseColumn(plaintextConfigJson);
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(ENCRYPTED_JSON_KEY, ciphertext));
        } catch (JsonProcessingException e) {
            log.error("[TenantPluginInstallationRepo] Błąd serializacji wrappera szyfrowania installation_config: {}",
                    e.getMessage());
            throw new IllegalStateException("Nie można zserializować zaszyfrowanej konfiguracji instalacji", e);
        }
    }

    /**
     * Odszyfrowuje wartość kolumny {@code installation_config} odczytaną z wiersza natywnego SQL.
     *
     * <p>Rozpakowuje {@code {"encrypted": "<base64>"}}, deszyfruje ciphertext przez
     * {@link EncryptedStringConverter#convertToEntityAttribute}, zwraca plaintext JSON. Wywoływane
     * konsekwentnie we WSZYSTKICH metodach odczytu tego repozytorium ({@link #findByIdAndTenantId},
     * {@link #findAllByTenantId}, {@link #findAllEnabledByPluginVersionIdForTenant}) przez
     * wspólne {@link #mapRow} — jedno miejsce logiki deszyfrowania, bez duplikacji.
     *
     * @param rawValue surowa wartość kolumny {@code jsonb} z {@code row[7]} ({@code null} dopuszczalny)
     * @return odszyfrowany plaintext JSON, lub {@code null} gdy kolumna jest {@code NULL}
     */
    private String decryptInstallationConfig(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String rawJson = rawValue.toString();
        String ciphertext;
        try {
            Map<String, String> wrapper = OBJECT_MAPPER.readValue(rawJson,
                    new TypeReference<Map<String, String>>() {});
            ciphertext = wrapper.get(ENCRYPTED_JSON_KEY);
        } catch (JsonProcessingException e) {
            log.error("[TenantPluginInstallationRepo] installation_config nie jest poprawnym JSON-em "
                    + "wrappera szyfrowania, nie można odszyfrować: {}", e.getMessage());
            throw new InstallationConfigFormatException("Nieprawidłowy format zaszyfrowanej konfiguracji instalacji", e);
        }
        if (ciphertext == null) {
            log.error("[TenantPluginInstallationRepo] installation_config nie zawiera klucza '{}' — "
                    + "nieprawidłowy format wrappera szyfrowania", ENCRYPTED_JSON_KEY);
            throw new InstallationConfigFormatException(
                    "Zaszyfrowana konfiguracja instalacji nie zawiera klucza '" + ENCRYPTED_JSON_KEY + "'", null);
        }
        return encryptedStringConverter.convertToEntityAttribute(ciphertext);
    }

    private static String writeJsonList(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values != null ? values : List.of());
        } catch (JsonProcessingException e) {
            log.error("[TenantPluginInstallationRepo] Błąd serializacji grantedPermissions: {}", e.getMessage());
            throw new IllegalArgumentException("Nie można serializować grantedPermissions do JSON", e);
        }
    }

    private static List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.error("[TenantPluginInstallationRepo] Błąd deserializacji grantedPermissions: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert to Instant: " + value.getClass());
    }
}
