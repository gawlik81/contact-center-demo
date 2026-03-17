package com.contactcenter.api.public_;

import com.contactcenter.domain.model.Tenant.TenantStatus;
import com.contactcenter.domain.repository.TenantRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Publiczne endpointy – dostępne bez JWT (strona logowania itp.).
 *
 * <p>Ścieżka /api/public/** jest dodana do listy permitAll w SecurityConfig
 * oraz PUBLIC_PATH_PREFIXES w JwtAuthFilter i TenantFilter.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Tag(name = "Public", description = "Endpointy publiczne (bez autentykacji)")
public class PublicController {

    private final TenantRepository tenantRepository;

    /**
     * Lista aktywnych tenantów do wyświetlenia na stronie logowania.
     *
     * <p>Zwraca wyłącznie ACTIVE tenanty – deaktywowane nie powinny być widoczne
     * na stronie logowania. Odpowiedź zawiera tylko id i name (minimalne dane
     * potrzebne do renderowania listy wyboru organizacji).
     */
    @GetMapping("/tenants")
    @Operation(summary = "Lista aktywnych organizacji (publiczna)",
               description = "Zwraca id i name aktywnych tenantów do selecta na stronie logowania.")
    public ResponseEntity<List<TenantDto>> listActiveTenants() {
        List<TenantDto> tenants = tenantRepository.findAllByOrderByNameAsc().stream()
                .filter(t -> TenantStatus.ACTIVE == t.getStatus())
                .map(t -> new TenantDto(t.getId(), t.getName()))
                .toList();
        return ResponseEntity.ok(tenants);
    }

    public record TenantDto(UUID id, String name) {}
}
