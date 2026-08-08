package com.contactcenter.domain.tenant;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.tenant.dto.CreateTenantRequest;
import com.contactcenter.api.tenant.dto.TenantFilterParams;
import com.contactcenter.api.tenant.dto.TenantResponse;
import com.contactcenter.api.tenant.dto.TenantTwilioConfigRequest;
import com.contactcenter.api.tenant.dto.UpdateTenantRequest;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający tenantami.
 *
 * <p>Operacje dostępne wyłącznie dla roli ADMIN (weryfikacja przez {@code @PreAuthorize}
 * w {@code TenantController}).
 *
 * <p>Dezaktywacja tenanta ({@link #deactivateTenant(UUID)}) blokuje logowanie wszystkich
 * użytkowników tenanta przez ustawienie {@code is_active = false} na koncie AppUser.
 * Dane nie są usuwane (soft delete pattern).
 */
public interface TenantService {

    /**
     * Tworzy nowego tenanta.
     *
     * <p>Weryfikuje unikalność nazwy (case-insensitive) przed zapisem.
     *
     * @param request dane nowego tenanta
     * @return DTO z danymi utworzonego tenanta
     * @throws IllegalArgumentException gdy nazwa jest już zajęta
     */
    TenantResponse createTenant(CreateTenantRequest request);

    /**
     * Zwraca listę tenantów z opcjonalnym filtrowaniem.
     *
     * <p>Filtrowanie jest case-insensitive i obsługuje częściowe dopasowanie nazwy (LIKE).
     * Gdy {@code filters} jest null lub nie zawiera żadnych kryteriów, zwracane są
     * wszystkie tenanty posortowane po nazwie.
     *
     * @param filters parametry filtrowania (name, status) – może być null
     * @return lista DTO tenantów spełniających kryteria, posortowana po nazwie ASC
     */
    List<TenantResponse> listTenants(TenantFilterParams filters);

    /**
     * Paginowana lista tenantów – używana przez REST endpoint GET /api/tenants.
     */
    PagedResponse<TenantResponse> listTenantsPaged(TenantFilterParams filters, Pageable pageable);

    /**
     * Zwraca szczegóły jednego tenanta.
     *
     * @param tenantId UUID tenanta
     * @return DTO z danymi tenanta
     * @throws EntityNotFoundException gdy tenant nie istnieje
     */
    TenantResponse getTenant(UUID tenantId);

    /**
     * Zwraca konfigurację tenanta dostępną dla SUPERVISOR i ADMIN.
     *
     * <p>SUPERVISOR może odczytywać wyłącznie konfigurację własnego tenanta
     * (tenant_id z JWT musi zgadzać się z żądanym {@code tenantId}).
     * ADMIN może odczytywać dowolnego tenanta (brak weryfikacji własności).
     *
     * <p>Autoryzacja roli (@PreAuthorize) jest wykonywana w warstwie kontrolera.
     * Serwis weryfikuje własność tenanta dla roli SUPERVISOR.
     *
     * @param tenantId UUID tenanta do odczytu
     * @return DTO z danymi tenanta (włącznie z config JSONB)
     * @throws EntityNotFoundException   gdy tenant nie istnieje
     * @throws CrossTenantAccessException gdy SUPERVISOR próbuje odczytać cudzego tenanta
     */
    TenantResponse getTenantConfig(UUID tenantId);

    /**
     * Aktualizuje dane tenanta (PATCH semantics – null oznacza brak zmiany).
     *
     * <p>Dozwolone zmiany: name, status, limits (config JSONB).
     * Weryfikuje unikalność nowej nazwy jeśli jest zmieniana.
     *
     * @param tenantId UUID tenanta do aktualizacji
     * @param request  dane do aktualizacji (pola null = bez zmiany)
     * @return DTO z zaktualizowanymi danymi tenanta
     * @throws EntityNotFoundException  gdy tenant nie istnieje
     * @throws IllegalArgumentException gdy nowa nazwa jest już zajęta
     */
    TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request);

    /**
     * Dezaktywuje tenanta – ustawia status INACTIVE i blokuje logowanie użytkowników.
     *
     * <p>Operacja jest idempotentna – dezaktywacja już nieaktywnego tenanta nie rzuca błędu.
     * Dane nie są usuwane (soft delete). Wszyscy użytkownicy tenanta mają ustawione
     * {@code is_active = false} (blokada logowania bez usuwania kont).
     *
     * @param tenantId UUID tenanta do dezaktywacji
     * @throws EntityNotFoundException gdy tenant nie istnieje
     */
    void deactivateTenant(UUID tenantId);

    /**
     * Aktualizuje konfigurację Twilio per-tenant w JSONB.
     *
     * <p>Operacja PATCH semantics: pole {@code null} w żądaniu usuwa dany klucz
     * z konfiguracji (powrót do globalnego fallbacku w {@code TwilioProperties}).
     * Pole non-null zapisuje wartość pod odpowiednim kluczem JSONB.
     *
     * <p>Nie dotyka pozostałych kluczy konfiguracji (max_agents, max_queues itp.).
     *
     * @param tenantId UUID tenanta do aktualizacji
     * @param request  nowa konfiguracja Twilio (pola nullable)
     * @return DTO z zaktualizowanymi danymi tenanta
     * @throws EntityNotFoundException gdy tenant nie istnieje
     */
    TenantResponse updateTwilioConfig(UUID tenantId, TenantTwilioConfigRequest request);

    /**
     * Sprawdza czy nazwa tenanta jest dostępna (nie zajęta przez innego tenanta).
     *
     * <p>Porównanie jest case-insensitive (zgodnie z indeksem bazy danych).
     *
     * @param name nazwa do sprawdzenia
     * @return true gdy nazwa jest wolna
     */
    boolean isNameAvailable(String name);

    /**
     * Sprawdza czy nazwa jest dostępna z wyłączeniem konkretnego tenanta.
     * Używane przy walidacji podczas edycji (tenant może zachować własną nazwę).
     *
     * @param name     nazwa do sprawdzenia
     * @param tenantId UUID tenanta do wyłączenia z porównania
     * @return true gdy nazwa jest wolna (lub należy do podanego tenanta)
     */
    boolean isNameAvailable(String name, UUID tenantId);

    /**
     * Zwraca listę aktywnych tenantów (status {@code ACTIVE}) posortowaną po nazwie.
     *
     * <p>Używana przez zadania harmonogramowe ({@code @Scheduled}) i adaptery,
     * które muszą iterować po wszystkich aktywnych tenantach (np. broadcast metryk,
     * aktywacja kampanii, polling email, oddzwonienia).
     *
     * @return lista aktywnych tenantów posortowana po nazwie ASC
     */
    List<Tenant> getActiveTenants();

    /**
     * Zwraca listę wszystkich tenantów (niezależnie od statusu) posortowaną po nazwie.
     *
     * <p>Używana przez {@code AdminMetricsService} do budowy globalnych metryk platformy.
     *
     * @return lista wszystkich tenantów posortowana po nazwie ASC
     */
    List<Tenant> getAllTenants();

    /**
     * Znajduje encję tenanta po identyfikatorze.
     *
     * <p>W przeciwieństwie do {@link #getTenant(UUID)} zwraca encję domenową
     * {@link Tenant} (nie DTO) i nie rzuca wyjątku gdy tenant nie istnieje –
     * obsługa braku należy do wywołującego.
     *
     * @param tenantId UUID tenanta
     * @return Optional z encją tenanta, lub empty gdy nie istnieje
     */
    Optional<Tenant> findTenantEntity(UUID tenantId);

    /**
     * Aktualizuje konfigurację (JSONB) tenanta i zapisuje encję.
     *
     * <p>Nadpisuje całą mapę {@code config} – wywołujący odpowiada za scalenie
     * z istniejącą konfiguracją (np. poprzez {@link #findTenantEntity(UUID)}
     * i modyfikację {@code tenant.getConfig()}).
     *
     * @param tenantId UUID tenanta do aktualizacji
     * @param config   nowa konfiguracja JSONB
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy tenant nie istnieje
     */
    void updateTenantConfig(UUID tenantId, Map<String, Object> config);

    /**
     * Zlicza nowych tenantów utworzonych w każdym tygodniu ISO-8601 od podanej daty granicznej.
     *
     * <p>Używane przez {@code AdminMetricsService} do budowy {@code GET /api/admin/metrics/growth}.
     *
     * @param since dolna granica {@code created_at} (włącznie)
     * @return lista par {@code [week_start, count]}, posortowana rosnąco
     */
    List<Object[]> countNewTenantsByWeekSince(java.time.Instant since);
}
