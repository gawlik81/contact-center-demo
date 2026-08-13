package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.infrastructure.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Implementacja {@link RetentionPolicyService}.
 *
 * <p><strong>Uwaga o zależnościach cyklicznych:</strong> ta klasa NIE zależy od
 * {@code TenantService} (mimo że {@code TenantServiceImpl.createTenant} zależy od niej,
 * wołając {@link #seedDefaultPolicies}). Dzięki temu {@code TenantServiceImpl} wstrzykuje
 * {@link RetentionPolicyService} jako zwykłe pole finalne (bez {@code @Autowired @Lazy}) —
 * cykl {@code tenant} ↔ {@code retention}, którego spodziewał się ticket BE-111, w praktyce
 * nie występuje.
 *
 * <p><strong>Domyślna wartość RECORDINGS przy seedowaniu (BE-116):</strong> do BE-116 domyślna
 * polityka {@code RECORDINGS} nowego tenanta była personalizowana z {@code tenant.config->>
 * 'recording_retention_days'} (odczyt przez natywne zapytanie w repozytorium). Od BE-116 ten
 * klucz JSONB NIE ISTNIEJE już w {@code tenant.config} (usunięty jako jedyne źródło prawdy —
 * zastąpiony przez {@code tenant_retention_policy}), więc personalizacja per-tenant przy
 * TWORZENIU tenanta przestała być możliwa. {@link #resolveRecordingsRetentionMonths} używa
 * teraz wprost {@link S3Properties#getRetentionDays()} — globalnej platformowej wartości
 * domyślnej (fallback/seed, jawnie dozwolony do pozostania w kodzie przez ticket BE-116) —
 * bez odpytywania tabeli {@code tenant}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class RetentionPolicyServiceImpl implements RetentionPolicyService {

    private static final int MIN_RETENTION_MONTHS = 1;
    private static final int MAX_RETENTION_MONTHS = 120;

    /** Konwersja miesiące → dni — patrz javadoc {@link RetentionPolicyService#getRetentionDays}. */
    private static final int DAYS_PER_MONTH = 30;

    // Wartości domyślne zgodne z backfillem migracji V082 (DB-046).
    private static final int DEFAULT_CONTACT_INTERACTIONS_MONTHS = 60;
    private static final int DEFAULT_CAMPAIGN_DATA_MONTHS = 60;
    // CEIL(90 dni / 30.0) — platformowy default transkrypcji, brak personalizacji per-tenant dziś.
    private static final int DEFAULT_TRANSCRIPTS_MONTHS = 3;

    private final TenantRetentionPolicyRepository repository;
    private final S3Properties s3Properties;

    // =========================================================================
    // Odczyt
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<TenantRetentionPolicy> listPolicies(UUID tenantId) {
        return repository.findAllByTenantId(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public int getRetentionMonths(UUID tenantId, RetentionDataCategory category) {
        return repository.findByTenantIdAndCategory(tenantId, category)
                .map(TenantRetentionPolicy::getRetentionMonths)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Brak polityki retencji: tenant=" + tenantId + ", kategoria=" + category));
    }

    @Override
    @Transactional(readOnly = true)
    public int getRetentionDays(UUID tenantId, RetentionDataCategory category) {
        return getRetentionMonths(tenantId, category) * DAYS_PER_MONTH;
    }

    @Override
    @Transactional(readOnly = true)
    public int findMinRetentionMonths(RetentionDataCategory category) {
        return repository.findMinRetentionMonths(category)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Brak jakiejkolwiek polityki retencji dla kategorii: " + category
                                + " (żaden tenant nie ma skonfigurowanej polityki)"));
    }

    @Override
    @Transactional(readOnly = true)
    public int findMaxRetentionMonths(RetentionDataCategory category) {
        return repository.findMaxRetentionMonths(category)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Brak jakiejkolwiek polityki retencji dla kategorii: " + category
                                + " (żaden tenant nie ma skonfigurowanej polityki)"));
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    @Override
    @Transactional
    public TenantRetentionPolicy updatePolicy(UUID tenantId, RetentionDataCategory category,
                                               int retentionMonths, boolean autoPurgeEnabled,
                                               UUID updatedByUserId) {
        validateRetentionMonths(retentionMonths);

        log.info("[RetentionPolicyService] Aktualizacja polityki: tenant={}, category={}, months={}, autoPurge={}",
                tenantId, category, retentionMonths, autoPurgeEnabled);

        // upsert — wszystkie 4 kategorie powinny już istnieć po seedowaniu/backfillu, ale metoda
        // musi być odporna na brakujący wiersz (dane historyczne, race condition z równoczesnym
        // wywołaniem) — patrz kryteria akceptacji BE-111.
        return repository.upsert(tenantId, category, retentionMonths, autoPurgeEnabled, updatedByUserId);
    }

    @Override
    @Transactional
    public void seedDefaultPolicies(UUID tenantId) {
        log.info("[RetentionPolicyService] Seedowanie domyślnych polityk retencji: tenant={}", tenantId);

        repository.insertIfMissing(tenantId, RetentionDataCategory.CONTACT_INTERACTIONS,
                DEFAULT_CONTACT_INTERACTIONS_MONTHS, false);
        repository.insertIfMissing(tenantId, RetentionDataCategory.CAMPAIGN_DATA,
                DEFAULT_CAMPAIGN_DATA_MONTHS, false);
        repository.insertIfMissing(tenantId, RetentionDataCategory.TRANSCRIPTS,
                DEFAULT_TRANSCRIPTS_MONTHS, false);
        repository.insertIfMissing(tenantId, RetentionDataCategory.RECORDINGS,
                resolveRecordingsRetentionMonths(), false);

        log.info("[RetentionPolicyService] Zasiano domyślne polityki retencji (4 kategorie): tenant={}", tenantId);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Wyznacza domyślną retencję RECORDINGS w miesiącach dla nowego tenanta.
     *
     * <p><strong>Zmiana BE-116:</strong> do BE-116 ta metoda personalizowała wynik z
     * {@code tenant.config->>'recording_retention_days'} (klucz JSONB ustawiany wcześniej przez
     * {@code TenantServiceImpl.buildConfig()} z wartości {@code CreateTenantRequest.limits()
     * .recordingRetentionDays()}). BE-116 usunął ten klucz z {@code tenant.config} (jedyne
     * źródło prawdy = {@code tenant_retention_policy}), więc personalizacja per-tenant przy
     * TWORZENIU tenanta nie jest już możliwa — parametr {@code recordingRetentionDays} zniknął
     * z {@code TenantResourceLimitsDto}. Metoda używa teraz wprost globalnej platformowej
     * wartości domyślnej {@link S3Properties#getRetentionDays()} (jawnie dozwolony fallback,
     * patrz ticket BE-116), tym samym przeliczeniem dni → miesiące co backfill V082:
     * {@code GREATEST(1, LEAST(120, CEIL(dni / 30.0)))}. Brak zależności od bazy — nie ma już
     * żadnego przypadku "tenant nie znaleziony" (poprzedni fallback {@code FALLBACK_RECORDINGS_MONTHS}
     * i parametr {@code tenantId} stały się zbędne i zostały usunięte).
     *
     * @return retencja RECORDINGS w miesiącach, w zakresie [1,120]
     */
    private int resolveRecordingsRetentionMonths() {
        int days = s3Properties.getRetentionDays();
        return clamp((int) Math.ceil(days / 30.0));
    }

    private int clamp(int months) {
        return Math.max(MIN_RETENTION_MONTHS, Math.min(MAX_RETENTION_MONTHS, months));
    }

    /**
     * Waliduje retencję w miesiącach na poziomie serwisu — spójne z CHECK w DB
     * ({@code retention_months BETWEEN 1 AND 120}), ale z czytelnym komunikatem dla API
     * zamiast surowego {@code DataIntegrityViolationException} przy naruszeniu constraintu.
     *
     * @throws IllegalArgumentException gdy wartość spoza zakresu [1,120] (mapowane na HTTP 422
     *         przez {@code GlobalExceptionHandler})
     */
    private void validateRetentionMonths(int retentionMonths) {
        if (retentionMonths < MIN_RETENTION_MONTHS || retentionMonths > MAX_RETENTION_MONTHS) {
            throw new IllegalArgumentException(
                    "Retencja musi mieścić się w zakresie [%d, %d] miesięcy, otrzymano: %d"
                            .formatted(MIN_RETENTION_MONTHS, MAX_RETENTION_MONTHS, retentionMonths));
        }
    }
}
