package com.contactcenter.domain.retention;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link TenantRetentionPolicy}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} — wszystkie zapytania ustawiają kontekst RLS
 * przez {@code setTenantContextInDb(tenantId)}, a każdy zapis poprzedzony jest
 * {@code assertSameTenant(tenantId)} zgodnie z regułą z CLAUDE.md.
 *
 * <p>Wszystkie operacje (odczyt i zapis) mapowane są ręcznie ({@link #mapRow}) z jawnej listy
 * kolumn na {@code List<Object[]>} — wzorzec z {@code AgentBreakRepository}. CELOWO NIE używamy
 * {@code em.createNativeQuery(sql, TenantRetentionPolicy.class)} (mapowanie przez
 * {@code resultClass}): ta encja ma jednocześnie {@code @Enumerated(EnumType.STRING)} i Lombokowy
 * {@code @AllArgsConstructor}, a Hibernate w tej wersji dla natywnych zapytań z {@code resultClass}
 * na takiej encji wybiera ścieżkę {@code NativeQueryConstructorTransformer} — wywołuje konstruktor
 * wszystkoargumentowy POZYCYJNIE z surową wartością JDBC ({@code String} dla kolumny VARCHAR) tam,
 * gdzie oczekiwany jest typ enum, co rzuca {@code ClassCastException} przy NIEPUSTYM wyniku
 * (błąd produkcyjny zaobserwowany na {@code GET /api/tenants/{id}/retention/policies} — patrz
 * historia commitów). Testy jednostkowe z mockowanym {@code EntityManager} tego nie łapały, bo
 * mock zwraca dokładnie ten obiekt, który mu każemy zwrócić — nigdy nie przechodzi przez
 * rzeczywisty mechanizm hydratacji Hibernate.
 */
@Slf4j
@Repository
class TenantRetentionPolicyRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera wszystkie polityki retencji tenanta (maks. 4 — jedna per kategoria).
     *
     * @param tenantId UUID tenanta
     * @return lista polityk posortowana po {@code data_category} ASC (może być pusta,
     *         np. tenant utworzony przed BE-111 i pominięty w backfillu — nie powinno
     *         się zdarzyć po V082, ale metoda nie zakłada dokładnie 4 wierszy)
     */
    @Transactional(readOnly = true)
    public List<TenantRetentionPolicy> findAllByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        SELECT policy_id, tenant_id, data_category, retention_months,
                               auto_purge_enabled, updated_by, created_at, updated_at
                        FROM tenant_retention_policy
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        ORDER BY data_category ASC
                        """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        List<TenantRetentionPolicy> results = rows.stream().map(this::mapRow).toList();

        log.debug("[RetentionPolicyRepo] Lista polityk: tenant={}, znaleziono={}", tenantId, results.size());
        return results;
    }

    /**
     * Pobiera politykę retencji dla konkretnej kategorii.
     *
     * @param tenantId UUID tenanta
     * @param category kategoria danych
     * @return Optional z polityką lub empty gdy nie istnieje (np. tenant sprzed pełnego
     *         seedowania — patrz {@link RetentionPolicyServiceImpl#updatePolicy})
     */
    @Transactional(readOnly = true)
    public Optional<TenantRetentionPolicy> findByTenantIdAndCategory(UUID tenantId, RetentionDataCategory category) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        SELECT policy_id, tenant_id, data_category, retention_months,
                               auto_purge_enabled, updated_by, created_at, updated_at
                        FROM tenant_retention_policy
                        WHERE tenant_id     = CAST(:tenantId AS uuid)
                          AND data_category = :category
                        LIMIT 1
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("category", category.name())
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    /**
     * Odczytuje skonfigurowaną per-tenant liczbę dni retencji nagrań z {@code tenant.config}
     * (JSONB, klucz {@code recording_retention_days}).
     *
     * <p>Używane wyłącznie przez {@link RetentionPolicyServiceImpl#seedDefaultPolicies} do
     * personalizacji domyślnej polityki {@code RECORDINGS} — odzwierciedla dokładnie ten sam
     * fallback (90 dni), co {@code TenantServiceImpl.buildConfig()} i backfill V082.
     *
     * <p>Tabela {@code tenant} NIE jest objęta RLS per-tenant (sama JEST tenantami — patrz
     * Javadoc klasy {@code Tenant}), więc odczyt nie wymaga {@code set_tenant_context} i nie
     * stanowi naruszenia izolacji. Zapytanie celowo zostaje w pakiecie {@code domain.retention}
     * zamiast wprowadzać zależność serwisową do {@code domain.tenant} (patrz javadoc
     * {@link RetentionPolicyServiceImpl#seedDefaultPolicies} — brak cyklu).
     *
     * @param tenantId UUID tenanta
     * @return liczba dni z konfiguracji (z fallbackiem 90 gdy klucz nie ustawiony),
     *         lub empty gdy tenant o podanym ID nie istnieje
     */
    @Transactional(readOnly = true)
    public Optional<Integer> findConfiguredRecordingRetentionDays(UUID tenantId) {
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                        """
                        SELECT COALESCE((config->>'recording_retention_days')::int, 90)
                        FROM tenant
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0).intValue());
    }

    /**
     * Zwraca minimalną skonfigurowaną retencję (w miesiącach) dla kategorii danych PO
     * WSZYSTKICH TENANTACH — używane przez {@link RetentionPolicyServiceImpl#findMinRetentionMonths}
     * (BE-112, {@code RetentionEvaluationJob}) do wyznaczenia globalnego progu zatrzymania
     * skanowania partycji.
     *
     * <p>ŚWIADOMIE bez {@code set_tenant_context}/{@code assertSameTenant} — to zapytanie
     * platformowe, cross-tenant PO ZAMIERZENIU (ten sam precedens co
     * {@link #findConfiguredRecordingRetentionDays}, patrz jego javadoc).
     *
     * <p>{@code MIN(retention_months)} nad pustym zbiorem zwraca pojedynczy wiersz z wartością
     * {@code NULL} (nie pusty wynik) — stąd jawne sprawdzenie {@code rows.get(0) == null}
     * poniżej, obok sprawdzenia {@code rows.isEmpty()}.
     *
     * @param category kategoria danych
     * @return Optional z minimalną retencją w miesiącach, lub empty gdy żaden tenant nie ma
     *         skonfigurowanej polityki dla tej kategorii (teoretycznie nieosiągalne po
     *         {@code seedDefaultPolicies} — patrz jego javadoc)
     */
    @Transactional(readOnly = true)
    public Optional<Integer> findMinRetentionMonths(RetentionDataCategory category) {
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                        """
                        SELECT MIN(retention_months) FROM tenant_retention_policy
                        WHERE data_category = :category
                        """)
                .setParameter("category", category.name())
                .getResultList();

        if (rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0).intValue());
    }

    /**
     * Zwraca maksymalną skonfigurowaną retencję (w miesiącach) dla kategorii danych PO
     * WSZYSTKICH TENANTACH — używane przez {@link RetentionPolicyServiceImpl#findMaxRetentionMonths}
     * (BE-115, {@code PartitionReclaimJob}) do wyznaczenia globalnego, zachowawczego progu
     * fizycznego usuwania partycji ({@code DROP TABLE}): partycja starsza niż
     * {@code now - findMaxRetentionMonths(category)} jest na pewno za stara dla KAŻDEGO tenanta
     * (nawet tego z najdłuższą skonfigurowaną retencją), więc jej fizyczne usunięcie nigdy nie
     * naruszy retencji żadnego tenanta.
     *
     * <p>Lustrzane odbicie {@link #findMinRetentionMonths} — ta sama semantyka cross-tenant
     * (patrz jego javadoc), ten sam wzorzec obsługi pustego zbioru.
     *
     * <p>{@code MAX(retention_months)} nad pustym zbiorem zwraca pojedynczy wiersz z wartością
     * {@code NULL} (nie pusty wynik) — stąd jawne sprawdzenie {@code rows.get(0) == null}
     * poniżej, obok sprawdzenia {@code rows.isEmpty()}.
     *
     * @param category kategoria danych
     * @return Optional z maksymalną retencją w miesiącach, lub empty gdy żaden tenant nie ma
     *         skonfigurowanej polityki dla tej kategorii (teoretycznie nieosiągalne po
     *         {@code seedDefaultPolicies} — patrz jego javadoc)
     */
    @Transactional(readOnly = true)
    public Optional<Integer> findMaxRetentionMonths(RetentionDataCategory category) {
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                        """
                        SELECT MAX(retention_months) FROM tenant_retention_policy
                        WHERE data_category = :category
                        """)
                .setParameter("category", category.name())
                .getResultList();

        if (rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0).intValue());
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Wstawia politykę retencji jeśli nie istnieje (idempotentne seedowanie).
     *
     * <p>Używane wyłącznie przez {@link RetentionPolicyServiceImpl#seedDefaultPolicies}.
     * Celowo NIE nadpisuje istniejącego wiersza ({@code ON CONFLICT DO NOTHING}) — w
     * przeciwieństwie do {@link #upsert}, który świadomie nadpisuje wartości przy
     * ręcznej aktualizacji przez administratora.
     *
     * @param tenantId        UUID tenanta
     * @param category        kategoria danych
     * @param retentionMonths retencja w miesiącach (już zwalidowana przez serwis)
     * @param autoPurgeEnabled czy auto-purge włączony domyślnie
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional
    public void insertIfMissing(UUID tenantId, RetentionDataCategory category,
                                 int retentionMonths, boolean autoPurgeEnabled) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        int inserted = em.createNativeQuery(
                        """
                        INSERT INTO tenant_retention_policy
                            (tenant_id, data_category, retention_months, auto_purge_enabled, created_at, updated_at)
                        VALUES (
                            CAST(:tenantId AS uuid), :category, :retentionMonths, :autoPurgeEnabled, NOW(), NOW()
                        )
                        ON CONFLICT (tenant_id, data_category) DO NOTHING
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("category", category.name())
                .setParameter("retentionMonths", retentionMonths)
                .setParameter("autoPurgeEnabled", autoPurgeEnabled)
                .executeUpdate();

        if (inserted > 0) {
            log.debug("[RetentionPolicyRepo] Zasiano politykę: tenant={}, category={}, months={}",
                    tenantId, category, retentionMonths);
        } else {
            log.debug("[RetentionPolicyRepo] Polityka już istnieje, pomijam seedowanie: tenant={}, category={}",
                    tenantId, category);
        }
    }

    /**
     * Aktualizuje politykę retencji lub tworzy ją, jeśli jeszcze nie istnieje (upsert).
     *
     * <p>Atomowa operacja {@code INSERT ... ON CONFLICT (tenant_id, data_category) DO UPDATE}
     * — odporna na race condition przy równoczesnych wywołaniach (unikalny indeks
     * {@code uq_tenant_retention_policy} wymusza atomowość na poziomie PostgreSQL, bez
     * potrzeby jawnego blokowania po stronie aplikacji).
     *
     * @param tenantId         UUID tenanta
     * @param category         kategoria danych
     * @param retentionMonths  retencja w miesiącach (już zwalidowana przez serwis, [1,120])
     * @param autoPurgeEnabled czy auto-purge włączony
     * @param updatedByUserId  UUID użytkownika wykonującego zmianę (nullable — np. zadania systemowe)
     * @return zapisana/zaktualizowana encja
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional
    public TenantRetentionPolicy upsert(UUID tenantId, RetentionDataCategory category,
                                         int retentionMonths, boolean autoPurgeEnabled,
                                         UUID updatedByUserId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        INSERT INTO tenant_retention_policy
                            (tenant_id, data_category, retention_months, auto_purge_enabled,
                             updated_by, created_at, updated_at)
                        VALUES (
                            CAST(:tenantId AS uuid), :category, :retentionMonths, :autoPurgeEnabled,
                            CAST(:updatedBy AS uuid), NOW(), NOW()
                        )
                        ON CONFLICT (tenant_id, data_category)
                        DO UPDATE SET
                            retention_months   = EXCLUDED.retention_months,
                            auto_purge_enabled = EXCLUDED.auto_purge_enabled,
                            updated_by         = EXCLUDED.updated_by,
                            updated_at         = NOW()
                        RETURNING policy_id, tenant_id, data_category, retention_months,
                                  auto_purge_enabled, updated_by, created_at, updated_at
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("category", category.name())
                .setParameter("retentionMonths", retentionMonths)
                .setParameter("autoPurgeEnabled", autoPurgeEnabled)
                .setParameter("updatedBy", updatedByUserId != null ? updatedByUserId.toString() : null)
                .getResultList();

        TenantRetentionPolicy saved = mapRow(rows.get(0));

        log.info("[RetentionPolicyRepo] Upsert polityki: tenant={}, category={}, months={}, autoPurge={}",
                tenantId, category, retentionMonths, autoPurgeEnabled);

        return saved;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Mapuje wiersz wynikowy natywnego INSERT ... RETURNING na encję {@link TenantRetentionPolicy}.
     *
     * <p>Kolejność kolumn zgodna z klauzulą RETURNING w {@link #upsert}: policy_id,
     * tenant_id, data_category, retention_months, auto_purge_enabled, updated_by,
     * created_at, updated_at.
     */
    private TenantRetentionPolicy mapRow(Object[] row) {
        return TenantRetentionPolicy.builder()
                .policyId(UUID.fromString(row[0].toString()))
                .tenantId(UUID.fromString(row[1].toString()))
                .dataCategory(RetentionDataCategory.valueOf(row[2].toString()))
                .retentionMonths(((Number) row[3]).intValue())
                .autoPurgeEnabled((Boolean) row[4])
                .updatedBy(row[5] != null ? UUID.fromString(row[5].toString()) : null)
                .createdAt(toInstant(row[6]))
                .updatedAt(row[7] != null ? toInstant(row[7]) : null)
                .build();
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert to Instant: " + value.getClass());
    }
}
