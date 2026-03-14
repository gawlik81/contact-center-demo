package com.contactcenter.domain.service;

import com.contactcenter.api.tenant.dto.CreateTenantRequest;
import com.contactcenter.api.tenant.dto.TenantResourceLimitsDto;
import com.contactcenter.api.tenant.dto.TenantResponse;
import com.contactcenter.api.tenant.dto.UpdateTenantRequest;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.model.Tenant.TenantStatus;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.TenantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;

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
    @Transactional
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
     * Zwraca listę wszystkich tenantów posortowaną po nazwie.
     *
     * @return lista DTO tenantów
     */
    @Transactional(readOnly = true)
    public List<TenantResponse> listTenants() {
        return tenantRepository.findAllByOrderByNameAsc()
                .stream()
                .map(TenantResponse::from)
                .toList();
    }

    /**
     * Zwraca szczegóły jednego tenanta.
     *
     * @param tenantId UUID tenanta
     * @return DTO z danymi tenanta
     * @throws EntityNotFoundException gdy tenant nie istnieje
     */
    @Transactional(readOnly = true)
    public TenantResponse getTenant(UUID tenantId) {
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
    @Transactional
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
        if (request.status() != null) {
            tenant.setStatus(request.status());
            log.info("[TenantService] Zmiana statusu tenanta id={}: {} -> {}",
                    tenantId, tenant.getStatus(), request.status());
        }

        // Aktualizacja limitów (merge z aktualnym config)
        if (request.limits() != null) {
            Map<String, Object> updatedConfig = mergeConfig(tenant.getConfig(), request.limits());
            tenant.setConfig(updatedConfig);
        }

        tenant.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.save(tenant);

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
    @Transactional
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

        // Zablokuj logowanie wszystkich użytkowników tenanta
        // Pobieramy wszystkich użytkowników (nie ma bezpośredniego @Modifying query na AppUserRepository
        // per tenantId bez is_active, więc używamy findAllByTenantId lub dedukcji z JPA)
        List<AppUser> tenantUsers = appUserRepository.findAll().stream()
                .filter(u -> tenantId.equals(u.getTenantId()))
                .toList();

        int disabledCount = 0;
        for (AppUser user : tenantUsers) {
            if (user.isActive()) {
                user.setActive(false);
                appUserRepository.save(user);
                disabledCount++;
            }
        }

        log.warn("[TenantService] Tenant id={} dezaktywowany. Zablokowano {} użytkowników.",
                tenantId, disabledCount);
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
    public boolean isNameAvailable(String name, UUID tenantId) {
        return !tenantRepository.existsByNameIgnoreCaseAndIdNot(name, tenantId);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Tenant findTenantOrThrow(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant nie istnieje: " + tenantId));
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
}
