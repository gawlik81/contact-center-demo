package com.contactcenter.domain.retention;

import java.util.List;
import java.util.UUID;

/**
 * Zarządzanie politykami retencji danych per tenant (EPIC-29).
 *
 * <p>Cztery kategorie danych ({@link RetentionDataCategory}) mają zawsze dokładnie jeden
 * wiersz konfiguracji per tenant — zapewnia to {@link #seedDefaultPolicies} (wołane przy
 * tworzeniu tenanta) oraz backfill migracji V082 (dla tenantów istniejących przed BE-111).
 *
 * <p>Konsumenci: {@code TenantServiceImpl.createTenant} (seedowanie), przyszły REST
 * kontroler BE-118 (CRUD), oraz przyszłe {@code RetentionEvaluationJob}/{@code RetentionPurgeService}
 * (BE-112/BE-113) i {@code RecordingRetentionJob} (BE-116) — przez {@link #getRetentionMonths}.
 */
public interface RetentionPolicyService {

    /**
     * Zwraca wszystkie polityki retencji tenanta.
     *
     * @param tenantId UUID tenanta
     * @return lista polityk (maks. 4, jedna per kategoria), posortowana po kategorii
     */
    List<TenantRetentionPolicy> listPolicies(UUID tenantId);

    /**
     * Aktualizuje politykę retencji dla podanej kategorii — lub tworzy ją, jeśli jeszcze
     * nie istnieje (upsert, odporny na race condition dzięki atomowemu
     * {@code INSERT ... ON CONFLICT DO UPDATE} w repozytorium).
     *
     * @param tenantId        UUID tenanta
     * @param category        kategoria danych
     * @param retentionMonths nowa retencja w miesiącach — musi mieścić się w [1,120]
     * @param autoPurgeEnabled czy włączyć automatyczne usuwanie po upływie retencji
     * @param updatedByUserId UUID użytkownika wykonującego zmianę (nullable — np. zadania systemowe)
     * @return zapisana/zaktualizowana polityka
     * @throws IllegalArgumentException gdy {@code retentionMonths} spoza zakresu [1,120]
     */
    TenantRetentionPolicy updatePolicy(UUID tenantId, RetentionDataCategory category,
                                        int retentionMonths, boolean autoPurgeEnabled,
                                        UUID updatedByUserId);

    /**
     * Zwraca skonfigurowaną retencję (w miesiącach) dla kategorii danych tenanta.
     *
     * <p>Używane przez przyszłe {@code RetentionEvaluationJob} / {@code RetentionPurgeService}
     * (BE-112/BE-113) oraz {@code RecordingRetentionJob} (BE-119) do wyznaczenia granicy
     * czasowej przy liczeniu/usuwaniu danych partition-aware.
     *
     * @param tenantId UUID tenanta
     * @param category kategoria danych
     * @return retencja w miesiącach
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy brak
     *         skonfigurowanej polityki dla tej kombinacji (nie powinno się zdarzyć po
     *         seedowaniu/backfillu — sygnalizuje lukę w danych)
     */
    int getRetentionMonths(UUID tenantId, RetentionDataCategory category);

    /**
     * Zasiewa domyślne polityki retencji dla nowo utworzonego tenanta — dokładnie 4 wiersze,
     * jeden per {@link RetentionDataCategory}. Idempotentne (bezpieczne do wielokrotnego
     * wywołania — nie nadpisuje już istniejących wierszy).
     *
     * <p>Wołane z {@code TenantServiceImpl.createTenant} zaraz po zapisaniu nowego tenanta.
     *
     * @param tenantId UUID nowo utworzonego tenanta
     */
    void seedDefaultPolicies(UUID tenantId);
}
