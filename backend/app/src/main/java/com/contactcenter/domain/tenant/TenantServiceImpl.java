package com.contactcenter.domain.tenant;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.tenant.dto.CreateTenantRequest;
import com.contactcenter.api.tenant.dto.TenantFilterParams;
import com.contactcenter.api.tenant.dto.TenantResourceLimitsDto;
import com.contactcenter.api.tenant.dto.TenantResponse;
import com.contactcenter.api.tenant.dto.TenantTwilioConfigRequest;
import com.contactcenter.api.tenant.dto.UpdateTenantRequest;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementacja {@link TenantService}.
 *
 * <p>Operacje dostępne wyłącznie dla roli ADMIN (weryfikacja przez {@code @PreAuthorize}
 * w {@code TenantController}).
 *
 * <p>Dezaktywacja tenanta ({@link #deactivateTenant(UUID)}) blokuje logowanie wszystkich
 * użytkowników tenanta przez ustawienie {@code is_active = false} na koncie AppUser.
 * Dane nie są usuwane (soft delete pattern).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;

    /**
     * Wstrzykiwany przez setter z {@code @Lazy} aby uniknąć cyklicznej zależności.
     *
     * <p>Cykl: {@code TenantService} → {@code AdminMetricsService} →
     * {@code TenantRepository} ← {@code TenantService}. Spring CGLIB proxy Cache
     * powoduje, że bez {@code @Lazy} Spring nie może zainicjalizować kontekstu.
     * Setter injection z {@code @Lazy} jest rozwiązaniem rekomendowanym przez Spring
     * dla tego wzorca (patrz: Spring docs – Circular Dependencies).
     *
     * <p><strong>Alternatywa – refaktor na ApplicationEvent:</strong>
     * Można by wyemitować {@code TenantStatusChangedEvent} (Spring {@code ApplicationEvent})
     * i obsłużyć go w {@code AdminMetricsService} przez {@code @EventListener} –
     * to usunęłoby bezpośrednią zależność. Jednak przy obecnym zakresie MVP
     * (jeden typ eventu, jeden konsument) overhead ApplicationEvent nie jest
     * uzasadniony. Rozważ migrację gdy liczba zależnych serwisów wzrośnie.
     *
     * <p>Pole nie-final – nie jest częścią konstruktora Lombok (@RequiredArgsConstructor).
     * W testach jednostkowych wstrzykuj przez metodę {@link #setAdminMetricsService}.
     */
    private AdminMetricsService adminMetricsService;

    @Autowired
    @Lazy
    public void setAdminMetricsService(AdminMetricsService adminMetricsService) {
        this.adminMetricsService = adminMetricsService;
    }

    /**
     * Wstrzykiwany przez setter z {@code @Lazy} aby uniknąć cyklicznej zależności.
     *
     * <p>Cykl: {@code UserServiceImpl} → {@code ContactServiceImpl} →
     * {@code TwilioTelephonyAdapter} → {@code TenantServiceImpl} → {@code UserServiceImpl}.
     * Setter injection z {@code @Lazy} jest rozwiązaniem rekomendowanym przez Spring
     * dla tego wzorca (patrz: Spring docs – Circular Dependencies).
     *
     * <p>Pole nie-final – nie jest częścią konstruktora Lombok (@RequiredArgsConstructor).
     * W testach jednostkowych wstrzykuj przez metodę {@link #setUserService}.
     */
    private UserService userService;

    @Autowired
    @Lazy
    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    // =========================================================================
    // CRUD operacje na tenantach
    // =========================================================================

    /**
     * Tworzy nowego tenanta.
     *
     * <p>Weryfikuje unikalność nazwy (case-insensitive) przed zapisem.
     *
     * @param request dane nowego tenanta
     * @return DTO z danymi utworzonego tenanta
     * @throws IllegalArgumentException gdy nazwa jest już zajęta
     */
    @Audited(action = "TENANT_CREATED", entityType = "TENANT")
    @Transactional
    @Override
    public TenantResponse createTenant(CreateTenantRequest request) {
        log.info("[TenantService] Tworzenie tenanta: name={}", request.name());

        if (tenantRepository.existsByNameIgnoreCase(request.name())) {
            throw new IllegalArgumentException(
                    "Nazwa tenanta jest już zajęta: '" + request.name() + "'");
        }

        Map<String, Object> config = buildConfig(request.limits());

        Tenant tenant = Tenant.builder()
                .name(request.name().trim())
                .status(TenantStatus.ACTIVE)
                .config(config)
                .createdAt(Instant.now())
                .build();

        Tenant saved = tenantRepository.save(tenant);
        log.info("[TenantService] Tenant utworzony: id={}, name={}", saved.getId(), saved.getName());

        return TenantResponse.from(saved);
    }

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
    @Transactional(readOnly = true)
    @Override
    public List<TenantResponse> listTenants(TenantFilterParams filters) {
        String nameFilter = (filters != null && filters.name() != null && !filters.name().isBlank())
                ? filters.name().trim()
                : null;
        // Przekazywany jako String (nazwa enum), bo repozytorium używa natywnego SQL
        // i porównuje status::TEXT = :status. Null oznacza brak filtrowania.
        String statusFilter = (filters != null && filters.status() != null)
                ? filters.status().name()
                : null;

        log.debug("[TenantService] listTenants: name={}, status={}", nameFilter, statusFilter);

        return tenantRepository.findAllByOptionalFilters(nameFilter, statusFilter)
                .stream()
                .map(TenantResponse::from)
                .toList();
    }

    /**
     * Paginowana lista tenantów – używana przez REST endpoint GET /api/tenants.
     */
    @Transactional(readOnly = true)
    @Override
    public PagedResponse<TenantResponse> listTenantsPaged(TenantFilterParams filters, Pageable pageable) {
        String nameFilter = (filters != null && filters.name() != null && !filters.name().isBlank())
                ? filters.name().trim()
                : null;
        String statusFilter = (filters != null && filters.status() != null)
                ? filters.status().name()
                : null;

        log.debug("[TenantService] listTenantsPaged: name={}, status={}, page={}, size={}",
                nameFilter, statusFilter, pageable.getPageNumber(), pageable.getPageSize());

        return PagedResponse.from(
                tenantRepository.findPageByOptionalFilters(nameFilter, statusFilter, pageable)
                        .map(TenantResponse::from)
        );
    }

    /**
     * Zwraca szczegóły jednego tenanta.
     *
     * @param tenantId UUID tenanta
     * @return DTO z danymi tenanta
     * @throws EntityNotFoundException gdy tenant nie istnieje
     */
    @Transactional(readOnly = true)
    @Override
    public TenantResponse getTenant(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        return TenantResponse.from(tenant);
    }

    /**
     * Zwraca konfigurację tenanta dostępną dla SUPER_ADMIN, ADMIN i SUPERVISOR.
     *
     * <p>ADMIN i SUPERVISOR są tenant-scoped od refaktoru ról (EPIC ról SUPER_ADMIN) –
     * obie role mogą odczytywać wyłącznie konfigurację własnego tenanta
     * (tenant_id z JWT musi zgadzać się z żądanym {@code tenantId}).
     * SUPER_ADMIN (globalny administrator platformy, bez tenant_id) może odczytywać
     * konfigurację dowolnego tenanta.
     *
     * <p>Autoryzacja roli (@PreAuthorize) jest wykonywana w warstwie kontrolera.
     * Serwis weryfikuje własność tenanta dla ról ADMIN i SUPERVISOR.
     *
     * @param tenantId UUID tenanta do odczytu
     * @return DTO z danymi tenanta (włącznie z config JSONB)
     * @throws EntityNotFoundException   gdy tenant nie istnieje
     * @throws CrossTenantAccessException gdy ADMIN/SUPERVISOR próbuje odczytać cudzego tenanta
     */
    @Transactional(readOnly = true)
    @Override
    public TenantResponse getTenantConfig(UUID tenantId) {
        assertSameTenantUnlessSuperAdmin(tenantId, "odczytać");
        Tenant tenant = findTenantOrThrow(tenantId);
        return TenantResponse.from(tenant);
    }

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
    @Audited(
        action = "TENANT_UPDATED",
        entityType = "TENANT",
        captureOldValue = true,
        fetchOldValueMethod = "getTenant",
        entityIdParamIndex = 0
    )
    @Transactional
    @Override
    public TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request) {
        log.info("[TenantService] Aktualizacja tenanta: id={}", tenantId);

        Tenant tenant = findTenantOrThrow(tenantId);

        // Zmiana nazwy – weryfikacja unikalności (case-insensitive, z wyłączeniem siebie)
        if (StringUtils.hasText(request.name())
                && !request.name().trim().equalsIgnoreCase(tenant.getName())) {
            if (tenantRepository.existsByNameIgnoreCaseAndIdNot(request.name(), tenantId)) {
                throw new IllegalArgumentException(
                        "Nazwa tenanta jest już zajęta: '" + request.name() + "'");
            }
            tenant.setName(request.name().trim());
        }

        // Zmiana statusu
        boolean statusChanged = false;
        if (request.status() != null) {
            log.info("[TenantService] Zmiana statusu tenanta id={}: {} -> {}",
                    tenantId, tenant.getStatus(), request.status());
            tenant.setStatus(request.status());
            statusChanged = true;
        }

        // Aktualizacja limitów (merge z aktualnym config)
        if (request.limits() != null) {
            Map<String, Object> updatedConfig = mergeConfig(tenant.getConfig(), request.limits());
            tenant.setConfig(updatedConfig);
        }

        tenant.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.save(tenant);

        // Inwaliduj cache metryk admin gdy zmienił się status tenanta
        if (statusChanged) {
            adminMetricsService.evictGlobalMetricsCache();
        }

        log.info("[TenantService] Tenant zaktualizowany: id={}", saved.getId());
        return TenantResponse.from(saved);
    }

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
    @Audited(
        action = "TENANT_DEACTIVATED",
        entityType = "TENANT",
        captureOldValue = true,
        fetchOldValueMethod = "getTenant",
        entityIdParamIndex = 0
    )
    @Transactional
    @Override
    public void deactivateTenant(UUID tenantId) {
        log.warn("[TenantService] Dezaktywacja tenanta: id={}", tenantId);

        Tenant tenant = findTenantOrThrow(tenantId);

        if (tenant.getStatus() == TenantStatus.INACTIVE) {
            log.info("[TenantService] Tenant {} jest już nieaktywny – pomijam", tenantId);
            return;
        }

        // Ustaw status tenanta na INACTIVE
        tenant.setStatus(TenantStatus.INACTIVE);
        tenant.setUpdatedAt(Instant.now());
        tenantRepository.save(tenant);

        // Zablokuj logowanie wszystkich użytkowników tenanta przez jeden bulk UPDATE.
        // Poprzednia implementacja używała findAll() (full table scan wszystkich tenantów!)
        // + pętla N×save – rażące N+1 problem naprawiony przez dedykowany @Modifying query.
        int disabledCount = userService.deactivateAllUsersByTenantId(tenantId);

        log.warn("[TenantService] Tenant id={} dezaktywowany. Zablokowano {} użytkowników.",
                tenantId, disabledCount);

        // Inwaliduj cache metryk admin – tenant zmienił status na INACTIVE
        adminMetricsService.evictGlobalMetricsCache();
    }

    /**
     * Aktualizuje konfigurację Twilio per-tenant w JSONB.
     *
     * <p>Operacja PATCH semantics: pole {@code null} w żądaniu usuwa dany klucz
     * z konfiguracji (powrót do globalnego fallbacku w {@code TwilioProperties}).
     * Pole non-null zapisuje wartość pod odpowiednim kluczem JSONB.
     *
     * <p>Nie dotyka pozostałych kluczy konfiguracji (max_agents, max_queues itp.).
     *
     * <p>ADMIN i SUPERVISOR (tenant-scoped) mogą aktualizować wyłącznie konfigurację
     * własnego tenanta (tenant_id z JWT musi zgadzać się z żądanym {@code tenantId}).
     * SUPER_ADMIN może aktualizować konfigurację dowolnego tenanta.
     *
     * @param tenantId UUID tenanta do aktualizacji
     * @param request  nowa konfiguracja Twilio (pola nullable)
     * @return DTO z zaktualizowanymi danymi tenanta
     * @throws jakarta.persistence.EntityNotFoundException gdy tenant nie istnieje
     * @throws CrossTenantAccessException gdy ADMIN/SUPERVISOR próbuje zaktualizować cudzego tenanta
     */
    @Audited(action = "TENANT_TWILIO_CONFIG_UPDATED", entityType = "TENANT")
    @Transactional
    @Override
    public TenantResponse updateTwilioConfig(UUID tenantId, TenantTwilioConfigRequest request) {
        log.info("[TenantService] Aktualizacja konfiguracji Twilio dla tenanta: id={}", tenantId);
        assertSameTenantUnlessSuperAdmin(tenantId, "zaktualizować");

        Tenant tenant = findTenantOrThrow(tenantId);

        Map<String, Object> config = tenant.getConfig() != null
                ? new HashMap<>(tenant.getConfig())
                : new HashMap<>();

        if (request.twilioPhoneNumber() != null) {
            config.put("twilio_phone_number", request.twilioPhoneNumber());
            log.info("[TenantService] Ustawiono per-tenant numer Twilio dla tenantId={}", tenantId);
        } else {
            config.remove("twilio_phone_number");
            log.info("[TenantService] Usunięto per-tenant numer Twilio dla tenantId={}", tenantId);
        }

        if (request.twilioStatusCallbackUrl() != null) {
            config.put("twilio_status_callback_url", stripTenantIdParam(request.twilioStatusCallbackUrl()));
        } else {
            config.remove("twilio_status_callback_url");
        }

        tenant.setConfig(config);
        tenant.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.save(tenant);

        log.info("[TenantService] Konfiguracja Twilio zaktualizowana: tenantId={}", tenantId);
        return TenantResponse.from(saved);
    }

    /**
     * Sprawdza czy nazwa tenanta jest dostępna (nie zajęta przez innego tenanta).
     *
     * <p>Porównanie jest case-insensitive (zgodnie z indeksem bazy danych).
     *
     * @param name nazwa do sprawdzenia
     * @return true gdy nazwa jest wolna
     */
    @Transactional(readOnly = true)
    @Override
    public boolean isNameAvailable(String name) {
        return !tenantRepository.existsByNameIgnoreCase(name);
    }

    /**
     * Sprawdza czy nazwa jest dostępna z wyłączeniem konkretnego tenanta.
     * Używane przy walidacji podczas edycji (tenant może zachować własną nazwę).
     *
     * @param name     nazwa do sprawdzenia
     * @param tenantId UUID tenanta do wyłączenia z porównania
     * @return true gdy nazwa jest wolna (lub należy do podanego tenanta)
     */
    @Transactional(readOnly = true)
    @Override
    public boolean isNameAvailable(String name, UUID tenantId) {
        return !tenantRepository.existsByNameIgnoreCaseAndIdNot(name, tenantId);
    }

    /**
     * Zwraca listę aktywnych tenantów (status {@code ACTIVE}) posortowaną po nazwie.
     *
     * @return lista aktywnych tenantów posortowana po nazwie ASC
     */
    @Transactional(readOnly = true)
    @Override
    public List<Tenant> getActiveTenants() {
        return tenantRepository.findAllByOrderByNameAsc().stream()
                .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                .toList();
    }

    /**
     * Zwraca listę wszystkich tenantów (niezależnie od statusu) posortowaną po nazwie.
     *
     * @return lista wszystkich tenantów posortowana po nazwie ASC
     */
    @Transactional(readOnly = true)
    @Override
    public List<Tenant> getAllTenants() {
        return tenantRepository.findAllByOrderByNameAsc();
    }

    /**
     * Znajduje encję tenanta po identyfikatorze.
     *
     * @param tenantId UUID tenanta
     * @return Optional z encją tenanta, lub empty gdy nie istnieje
     */
    @Transactional(readOnly = true)
    @Override
    public Optional<Tenant> findTenantEntity(UUID tenantId) {
        return tenantRepository.findById(tenantId);
    }

    /**
     * Aktualizuje konfigurację (JSONB) tenanta i zapisuje encję.
     *
     * @param tenantId UUID tenanta do aktualizacji
     * @param config   nowa konfiguracja JSONB
     * @throws ResourceNotFoundException gdy tenant nie istnieje
     */
    @Transactional
    @Override
    public void updateTenantConfig(UUID tenantId, Map<String, Object> config) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant nie istnieje: " + tenantId));
        tenant.setConfig(config);
        tenant.setUpdatedAt(Instant.now());
        tenantRepository.save(tenant);
    }

    /**
     * Zlicza nowych tenantów utworzonych w każdym tygodniu ISO-8601 od podanej daty granicznej.
     *
     * @param since dolna granica {@code created_at} (włącznie)
     * @return lista par {@code [week_start, count]}, posortowana rosnąco
     */
    @Transactional(readOnly = true)
    @Override
    public List<Object[]> countNewTenantsByWeekSince(Instant since) {
        return tenantRepository.countNewTenantsByWeekSince(since);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Tenant findTenantOrThrow(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant nie istnieje: " + tenantId));
    }

    /**
     * Weryfikuje, że wywołujący ma prawo operować na {@code tenantId}.
     *
     * <p>SUPER_ADMIN (brak tenant_id, globalny administrator platformy) omija tę
     * weryfikację. ADMIN i SUPERVISOR są tenant-scoped – mogą operować wyłącznie
     * na własnym tenancie (tenant_id z JWT musi zgadzać się z żądanym {@code tenantId}).
     *
     * @param tenantId UUID tenanta, na którym wykonywana jest operacja
     * @param action   czasownik do komunikatu logu (np. "odczytać", "zaktualizować")
     * @throws CrossTenantAccessException gdy ADMIN/SUPERVISOR próbuje operować na cudzym tenancie
     */
    private void assertSameTenantUnlessSuperAdmin(UUID tenantId, String action) {
        String role = TenantContext.getUserRole();
        if ("SUPER_ADMIN".equals(role)) {
            return;
        }
        UUID callerTenantId = TenantContext.getTenantId();
        if (!tenantId.equals(callerTenantId)) {
            log.warn("[TenantService] {} {} próbuje {} config tenanta {}, własny tenant: {}",
                    role, TenantContext.getUserIdOrNull(), action, tenantId, callerTenantId);
            throw new CrossTenantAccessException(tenantId, callerTenantId);
        }
    }

    /**
     * Buduje mapę config JSONB z DTO limitów.
     * Jeśli limits jest null, zwraca domyślne wartości.
     */
    private Map<String, Object> buildConfig(TenantResourceLimitsDto limits) {
        Map<String, Object> config = new HashMap<>();

        if (limits == null) {
            config.put("max_agents", 100);
            config.put("max_queues", 50);
            config.put("max_campaigns", 20);
            config.put("recording_retention_days", 90);
            config.put("timezone", "Europe/Warsaw");
        } else {
            config.put("max_agents", limits.maxAgents() != null ? limits.maxAgents() : 100);
            config.put("max_queues", limits.maxQueues() != null ? limits.maxQueues() : 50);
            config.put("max_campaigns", limits.maxCampaigns() != null ? limits.maxCampaigns() : 20);
            config.put("recording_retention_days",
                    limits.recordingRetentionDays() != null ? limits.recordingRetentionDays() : 90);
            config.put("timezone",
                    StringUtils.hasText(limits.timezone()) ? limits.timezone() : "Europe/Warsaw");
        }

        return config;
    }

    /**
     * Scala aktualną konfigurację z nowymi wartościami z DTO.
     * Nadpisuje tylko pola, które są podane (nie-null) w DTO.
     */
    private Map<String, Object> mergeConfig(Map<String, Object> existing, TenantResourceLimitsDto updates) {
        Map<String, Object> config = existing != null ? new HashMap<>(existing) : new HashMap<>();

        if (updates.maxAgents() != null) {
            config.put("max_agents", updates.maxAgents());
        }
        if (updates.maxQueues() != null) {
            config.put("max_queues", updates.maxQueues());
        }
        if (updates.maxCampaigns() != null) {
            config.put("max_campaigns", updates.maxCampaigns());
        }
        if (updates.recordingRetentionDays() != null) {
            config.put("recording_retention_days", updates.recordingRetentionDays());
        }
        if (StringUtils.hasText(updates.timezone())) {
            config.put("timezone", updates.timezone());
        }

        return config;
    }

    private String stripTenantIdParam(String url) {
        if (url == null) return null;
        return url.replaceAll("[?&]tenantId=[^&]*", "").replaceAll("\\?$", "");
    }
}
