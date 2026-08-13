package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
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
 * wołając {@link #seedDefaultPolicies}). Personalizacja domyślnej polityki {@code RECORDINGS}
 * (patrz {@link #resolveRecordingsRetentionMonths}) czyta {@code tenant.config} bezpośrednio
 * przez natywne zapytanie w {@link TenantRetentionPolicyRepository#findConfiguredRecordingRetentionDays}
 * zamiast przez serwis tenanta — analogicznie do {@code TenantRepository.countActiveAgentsByTenantId}
 * i podobnych metod, które już dziś odpytują tabele spoza własnej domeny bezpośrednio przez SQL.
 * Dzięki temu {@code TenantServiceImpl} wstrzykuje {@link RetentionPolicyService} jako zwykłe
 * pole finalne (bez {@code @Autowired @Lazy}) — cykl {@code tenant} ↔ {@code retention},
 * którego spodziewał się ticket BE-111, w praktyce nie występuje.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class RetentionPolicyServiceImpl implements RetentionPolicyService {

    private static final int MIN_RETENTION_MONTHS = 1;
    private static final int MAX_RETENTION_MONTHS = 120;

    // Wartości domyślne zgodne z backfillem migracji V082 (DB-046).
    private static final int DEFAULT_CONTACT_INTERACTIONS_MONTHS = 60;
    private static final int DEFAULT_CAMPAIGN_DATA_MONTHS = 60;
    // CEIL(90 dni / 30.0) — platformowy default transkrypcji, brak personalizacji per-tenant dziś.
    private static final int DEFAULT_TRANSCRIPTS_MONTHS = 3;
    // Fallback RECORDINGS gdy odczyt tenant.config zawiedzie (patrz resolveRecordingsRetentionMonths) —
    // w normalnym przepływie (seedDefaultPolicies wołane zaraz po tenantRepository.save()) nieużywany,
    // bo tenant zawsze już istnieje w bazie w tym momencie.
    private static final int FALLBACK_RECORDINGS_MONTHS = 3;

    private final TenantRetentionPolicyRepository repository;

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
                resolveRecordingsRetentionMonths(tenantId), false);

        log.info("[RetentionPolicyService] Zasiano domyślne polityki retencji (4 kategorie): tenant={}", tenantId);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Wyznacza domyślną retencję RECORDINGS w miesiącach dla nowego tenanta.
     *
     * <p>{@code CreateTenantRequest.limits().recordingRetentionDays()} JEST dziś dostępne w
     * {@code TenantServiceImpl.createTenant} — trafia do {@code tenant.config} przez
     * {@code buildConfig()} (z fallbackiem 90 dni gdy nie podano), zanim wywoływane jest
     * {@link #seedDefaultPolicies}. Zamiast rozszerzać interfejs serwisu o dodatkowy parametr
     * (co złamałoby sygnaturę {@code seedDefaultPolicies(UUID)} z ticketu), odczytujemy tę
     * wartość z już zapisanej encji {@code Tenant} — patrz javadoc klasy oraz
     * {@link TenantRetentionPolicyRepository#findConfiguredRecordingRetentionDays}.
     *
     * <p>Przeliczenie dni → miesiące identyczne jak w backfillu V082:
     * {@code GREATEST(1, LEAST(120, CEIL(dni / 30.0)))}.
     *
     * @param tenantId UUID tenanta (musi już istnieć w tabeli {@code tenant})
     * @return retencja RECORDINGS w miesiącach, w zakresie [1,120]
     */
    private int resolveRecordingsRetentionMonths(UUID tenantId) {
        return repository.findConfiguredRecordingRetentionDays(tenantId)
                .map(days -> clamp((int) Math.ceil(days / 30.0)))
                .orElseGet(() -> {
                    // Defensywnie — nie powinno wystąpić, bo seedDefaultPolicies jest wołane
                    // zaraz po tenantRepository.save() w TenantServiceImpl.createTenant, więc
                    // tenant zawsze już istnieje w tym momencie transakcji.
                    log.warn("[RetentionPolicyService] Nie znaleziono tenanta {} przy seedowaniu RECORDINGS "
                            + "— fallback {} mies.", tenantId, FALLBACK_RECORDINGS_MONTHS);
                    return FALLBACK_RECORDINGS_MONTHS;
                });
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
