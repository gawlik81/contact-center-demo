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
 * <p>Konsumenci: {@code TenantServiceImpl.createTenant} (seedowanie), {@code RetentionController}
 * (BE-118, CRUD), {@code RetentionEvaluationJob}/{@code RetentionPurgeService} (BE-112/BE-113)
 * — przez {@link #getRetentionMonths} — oraz {@code RecordingRetentionJob} (BE-116) — przez
 * {@link #getRetentionDays}.
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
     * Zwraca skonfigurowaną retencję (w DNIACH) dla kategorii danych tenanta.
     *
     * <p>Konwersja miesiące → dni: {@code retentionMonths * 30}. Kolumna
     * {@code tenant_retention_policy.retention_months} przechowuje wyłącznie miesiące (CHECK
     * BETWEEN 1 AND 120, V082) — to jedyne źródło prawdy. Mnożnik {@code * 30} (a nie np.
     * {@code * 30.44} czy uwzględnianie faktycznej liczby dni w danym miesiącu) jest CELOWO
     * najprostszą wierną odwrotnością konwersji dni → miesiące już użytej w backfillu V082
     * ({@code CEIL(dni / 30.0)}, patrz {@link RetentionPolicyServiceImpl}) — spójność z
     * istniejącym precedensem w projekcie, nie precyzja kalendarzowa. Różnica rzędu 1–2 dni na
     * 30-dniowy miesiąc jest nieistotna dla polityki retencji wyrażanej i konfigurowanej w
     * miesiącach (administrator ustawia "6 miesięcy", nie "182 dni").
     *
     * <p>Główny konsument: {@code RecordingRetentionJob} (BE-116) — wyznacza granicę czasową
     * ({@code Instant.now().minus(dni, ChronoUnit.DAYS)}) osobno dla KAŻDEGO tenanta wewnątrz
     * pętli po tenantach (nie jeden globalny cutoff przed pętlą, jak przed BE-116).
     *
     * @param tenantId UUID tenanta
     * @param category kategoria danych
     * @return retencja w dniach ({@code retentionMonths * 30})
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy brak
     *         skonfigurowanej polityki dla tej kombinacji (nie powinno się zdarzyć po
     *         seedowaniu/backfillu — sygnalizuje lukę w danych; wywołujący powinien złapać ten
     *         wyjątek per-tenant i pominąć tenanta w danym przebiegu zamiast przerywać cały job)
     */
    int getRetentionDays(UUID tenantId, RetentionDataCategory category);

    /**
     * Zwraca MINIMALNĄ skonfigurowaną retencję (w miesiącach) dla kategorii danych PO
     * WSZYSTKICH TENANTACH — zapytanie platformowe, celowo cross-tenant.
     *
     * <p>Używane przez {@code RetentionEvaluationJob} (BE-112) do wyznaczenia globalnego progu
     * zatrzymania skanowania partycji: partycja młodsza niż {@code now - findMinRetentionMonths(category)}
     * na pewno nie zawiera przeterminowanych danych dla ŻADNEGO tenanta (nawet tego z
     * najkrótszą skonfigurowaną retencją), więc job może bezpiecznie przerwać skanowanie
     * starszych partycji dla tej kategorii.
     *
     * @param category kategoria danych
     * @return minimalna retencja w miesiącach spośród wszystkich tenantów mających
     *         skonfigurowaną politykę dla tej kategorii
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy ŻADEN tenant nie
     *         ma skonfigurowanej polityki dla tej kategorii (teoretyczne — {@link #seedDefaultPolicies}
     *         gwarantuje wszystkie 4 kategorie dla każdego tenanta) — wywołujący powinien
     *         przechwycić ten wyjątek i pominąć kategorię w danym przebiegu (log WARN) zamiast
     *         przerywać cały job
     */
    int findMinRetentionMonths(RetentionDataCategory category);

    /**
     * Zwraca MAKSYMALNĄ skonfigurowaną retencję (w miesiącach) dla kategorii danych PO
     * WSZYSTKICH TENANTACH — zapytanie platformowe, celowo cross-tenant.
     *
     * <p>Używane przez {@code PartitionReclaimJob} (BE-115) do wyznaczenia globalnego,
     * zachowawczego progu fizycznego usuwania partycji ({@code DROP TABLE}): partycja starsza
     * niż {@code now - findMaxRetentionMonths(category)} jest na pewno za stara dla KAŻDEGO
     * tenanta (nawet tego z najdłuższą skonfigurowaną retencją), więc jej fizyczne usunięcie
     * nigdy nie naruszy retencji żadnego tenanta. Lustrzane odbicie {@link #findMinRetentionMonths}.
     *
     * @param category kategoria danych
     * @return maksymalna retencja w miesiącach spośród wszystkich tenantów mających
     *         skonfigurowaną politykę dla tej kategorii
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy ŻADEN tenant nie
     *         ma skonfigurowanej polityki dla tej kategorii (teoretyczne — {@link #seedDefaultPolicies}
     *         gwarantuje wszystkie 4 kategorie dla każdego tenanta) — wywołujący powinien
     *         przechwycić ten wyjątek i pominąć kategorię w danym przebiegu (log WARN) zamiast
     *         przerywać cały job
     */
    int findMaxRetentionMonths(RetentionDataCategory category);

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
