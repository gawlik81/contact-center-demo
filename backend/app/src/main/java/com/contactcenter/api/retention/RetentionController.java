package com.contactcenter.api.retention;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.retention.PurgeTriggerType;
import com.contactcenter.domain.retention.RetentionDataCategory;
import com.contactcenter.domain.retention.RetentionPolicyService;
import com.contactcenter.domain.retention.RetentionPurgeService;
import com.contactcenter.domain.retention.TenantRetentionPolicy;
import com.contactcenter.domain.retention.dto.PurgeRequestDto;
import com.contactcenter.domain.retention.dto.PurgeResultDto;
import com.contactcenter.domain.retention.dto.RetentionPolicyDto;
import com.contactcenter.domain.retention.dto.RetentionSummaryDto;
import com.contactcenter.domain.retention.dto.UpdateRetentionPolicyRequest;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API zarządzania retencją danych per tenant (EPIC-29, BE-118) — CRUD polityk, dashboard
 * „ile do usunięcia", ręczne uruchomienie purge, status i historia operacji.
 *
 * <p>Konsumuje serwisy już ukończone w tym epiku: {@link RetentionPolicyService} (BE-111),
 * {@link RetentionPurgeService} (BE-113 + odczyty dodane w tym tickecie). {@code
 * RetentionEvaluationJob} (BE-112) pozostaje wewnętrznym schedulerem, bez REST API.
 *
 * <h2>Bezpieczeństwo: dwa NIEZALEŻNE mechanizmy dla {@code {tenantId}}/{@code {purgeId}}</h2>
 *
 * <p><strong>1) {@code {tenantId}} w ścieżce ≠ własny tenant wywołującego → HTTP 403.</strong>
 * Ani {@link RetentionPolicyService}, ani {@link RetentionPurgeService} NIE weryfikują, że
 * przekazany {@code tenantId} to "własny" tenant wywołującego — CELOWO, bo {@code
 * RetentionEvaluationJob} (BE-112) legalnie wywołuje je dla WSZYSTKICH tenantów po kolei
 * (scheduler, nie żądanie HTTP; dodanie sztywnej weryfikacji "tylko własny tenant" w tych
 * serwisach złamałoby BE-112). Repozytoria odczytu w pakiecie {@code domain.retention} też nie
 * wołają {@code assertSameTenant} (tylko zapisy to robią — patrz ich Javadoc). Dlatego JEDYNĄ
 * realną barierą dla {@code {tenantId}} ze ścieżki URL jest jawna weryfikacja W TYM
 * KONTROLERZE, PRZED wywołaniem jakiegokolwiek serwisu: {@link #assertOwnTenant(UUID)},
 * wzorowana na {@code TenantServiceImpl.assertSameTenantUnlessSuperAdmin} (ten kontroler nie
 * przewiduje SUPER_ADMIN — wyłącznie ADMIN, tenant-scoped — więc wersja jest prostsza, bez
 * rozgałęzienia na rolę).
 *
 * <p><strong>2) {@code {tenantId}} == własny tenant (przeszło punkt 1), ale {@code {purgeId}}
 * należy do INNEGO tenanta (zgadywanie UUID) → HTTP 404.</strong> {@code
 * RetentionPurgeLogRepository.findById(purgeId, tenantId)} filtruje po {@code tenant_id} w SQL
 * i zwraca {@code Optional.empty()} gdy nie pasuje — {@link RetentionPurgeService#getPurgeStatus}
 * mapuje to na {@link com.contactcenter.domain.exception.ResourceNotFoundException}, globalnie
 * obsługiwane jako 404. To celowo INNY kod błędu niż punkt 1 — nie ujawniamy administratorowi
 * jednego tenanta istnienia purgeId innego tenanta (403 sugerowałoby "istnieje, ale nie twoje";
 * 404 nie ujawnia nic).
 *
 * <p><strong>Poziom filtra Spring Security:</strong> {@code SecurityConfig} ma regułę
 * {@code /api/tenants/** -> hasRole(SUPER_ADMIN)} (zarządzanie tenantami, BE-006) — ścieżka
 * {@code /api/tenants/{tenantId}/retention/**} tego kontrolera wymaga osobnego wpisu {@code
 * /api/tenants/*&#47;retention/** -> hasRole(ADMIN)} PRZED tą ogólną regułą (Spring Security
 * dopasowuje matchery po kolei, pierwsze trafienie wygrywa) — bez tego request nigdy nie
 * dotarłby do {@code @PreAuthorize} tej klasy. Ta reguła sama NIE zastępuje {@link
 * #assertOwnTenant(UUID)} — dopuszcza tylko rolę ADMIN, nie sprawdza "czyj to tenant".
 *
 * <h2>Decyzje projektowe (odstępstwa od dosłownego opisu ticketu, udokumentowane)</h2>
 * <ul>
 *   <li><strong>Brak osobnych {@code PurgeStatusDto}/{@code PurgeHistoryEntryDto}:</strong>
 *       ticket je wymienia jako nowe pliki, ale miałyby identyczny kształt co już istniejący
 *       {@link PurgeResultDto} (BE-111). Używamy go dla obu endpointów ({@code GET
 *       .../purge/{purgeId}} i {@code GET .../history}) zamiast tworzyć dwa zbędne duplikaty.</li>
 *   <li><strong>{@link UpdateRetentionPolicyRequest#dataCategory()} vs {@code {category}} z
 *       path:</strong> DTO ma już pole {@code dataCategory} (z BE-111, {@code @NotNull}).
 *       Zamiast je ignorować (co ukryłoby błąd klienta wysyłającego niespójne dane), {@link
 *       #updatePolicy} weryfikuje zgodność obu wartości i rzuca {@link IllegalArgumentException}
 *       (→ HTTP 422 przez istniejący handler) przy niezgodności — {@code {category}} z path
 *       pozostaje źródłem prawdy (REST semantyka: PUT adresuje zasób przez URL).</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/tenants/{tenantId}/retention")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Retention Management",
        description = "Zarządzanie retencją danych per tenant (polityki, purge, historia) — "
                + "tylko ADMIN własnego tenanta (EPIC-29, BE-118).")
public class RetentionController {

    private final RetentionPolicyService retentionPolicyService;
    private final RetentionPurgeService retentionPurgeService;

    // =========================================================================
    // Polityki retencji (BE-111)
    // =========================================================================

    @GetMapping("/policies")
    @Operation(
            summary = "Lista polityk retencji tenanta",
            description = "Zwraca wszystkie skonfigurowane polityki retencji (maks. 4 — jedna "
                    + "per RetentionDataCategory).",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Lista polityk"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN lub {tenantId} != własny tenant")
            }
    )
    public ResponseEntity<List<RetentionPolicyDto>> listPolicies(
            @Parameter(description = "UUID tenanta (musi być zgodny z tenantem z JWT)")
            @PathVariable UUID tenantId
    ) {
        assertOwnTenant(tenantId);

        List<TenantRetentionPolicy> policies = retentionPolicyService.listPolicies(tenantId);
        return ResponseEntity.ok(policies.stream().map(RetentionPolicyDto::from).toList());
    }

    @PutMapping("/policies/{category}")
    @Operation(
            summary = "Aktualizuj politykę retencji dla kategorii danych",
            description = "Zmienia retentionMonths/autoPurgeEnabled dla podanej kategorii "
                    + "(upsert — tworzy politykę, jeśli jeszcze nie istnieje).",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Polityka zaktualizowana"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN lub {tenantId} != własny tenant"),
                    @ApiResponse(responseCode = "422", description = "retentionMonths poza zakresem [1,120] "
                            + "lub dataCategory w body niezgodna z {category} ze ścieżki")
            }
    )
    public ResponseEntity<RetentionPolicyDto> updatePolicy(
            @Parameter(description = "UUID tenanta (musi być zgodny z tenantem z JWT)")
            @PathVariable UUID tenantId,
            @Parameter(description = "Kategoria danych, której dotyczy zmiana")
            @PathVariable RetentionDataCategory category,
            @Valid @RequestBody UpdateRetentionPolicyRequest request
    ) {
        assertOwnTenant(tenantId);

        // {category} ze ścieżki jest źródłem prawdy (REST semantyka) — dataCategory w body musi
        // być z nią zgodna, inaczej odrzucamy żądanie zamiast po cichu ignorować pole klienta
        // (patrz Javadoc klasy, sekcja "Decyzje projektowe").
        if (request.dataCategory() != category) {
            throw new IllegalArgumentException(
                    "Kategoria w treści żądania (%s) nie zgadza się z kategorią w ścieżce (%s)"
                            .formatted(request.dataCategory(), category));
        }

        UUID updatedByUserId = TenantContext.getUserId();
        TenantRetentionPolicy updated = retentionPolicyService.updatePolicy(
                tenantId, category, request.retentionMonths(), request.autoPurgeEnabled(), updatedByUserId);

        return ResponseEntity.ok(RetentionPolicyDto.from(updated));
    }

    // =========================================================================
    // Dashboard „ile do usunięcia" (BE-112)
    // =========================================================================

    @GetMapping("/summary")
    @Operation(
            summary = "Dashboard danych kwalifikujących się do usunięcia",
            description = "Zwraca ZAWSZE dokładnie 4 wpisy (jeden per RetentionDataCategory) "
                    + "z cache tenant_retention_pending_summary (RetentionEvaluationJob, BE-112). "
                    + "Kategoria jeszcze niepoliczona przez job ma computed=false — odróżnialne "
                    + "od computed=true z eligibleRowCount=0.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "4 wpisy, po jednym per kategoria"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN lub {tenantId} != własny tenant")
            }
    )
    public ResponseEntity<List<RetentionSummaryDto>> getSummary(
            @Parameter(description = "UUID tenanta (musi być zgodny z tenantem z JWT)")
            @PathVariable UUID tenantId
    ) {
        assertOwnTenant(tenantId);

        return ResponseEntity.ok(retentionPurgeService.getPendingSummary(tenantId));
    }

    // =========================================================================
    // Ręczne uruchomienie purge (BE-113)
    // =========================================================================

    @PostMapping("/purge")
    @Operation(
            summary = "Uruchom ręczne usuwanie danych dla kategorii",
            description = "Inicjuje asynchroniczne usuwanie (RetentionPurgeService.purge, "
                    + "@Async) — zwraca purgeId NATYCHMIAST, bez czekania na zakończenie. "
                    + "Status operacji odpytuj przez GET .../purge/{purgeId}.",
            responses = {
                    @ApiResponse(responseCode = "202", description = "Purge zainicjowany — body zawiera purgeId",
                            content = @Content(schema = @Schema(example = "{\"purgeId\": \"uuid\"}"))),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN lub {tenantId} != własny tenant"),
                    @ApiResponse(responseCode = "422", description = "Błąd walidacji (brak dataCategory)"),
                    @ApiResponse(responseCode = "501", description = "Kategoria RECORDINGS — "
                            + "nieobsługiwana przez RetentionPurgeService, patrz RecordingRetentionJob (BE-116)")
            }
    )
    public ResponseEntity<Map<String, String>> purge(
            @Parameter(description = "UUID tenanta (musi być zgodny z tenantem z JWT)")
            @PathVariable UUID tenantId,
            @Valid @RequestBody PurgeRequestDto request
    ) {
        assertOwnTenant(tenantId);

        UUID triggeredByUserId = TenantContext.getUserId();
        UUID purgeId = retentionPurgeService.purge(
                tenantId, request.dataCategory(), PurgeTriggerType.MANUAL, triggeredByUserId);

        log.info("[RetentionController] Purge zainicjowany ręcznie: tenant={}, category={}, purgeId={}, user={}",
                tenantId, request.dataCategory(), purgeId, triggeredByUserId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("purgeId", purgeId.toString()));
    }

    @GetMapping("/purge/{purgeId}")
    @Operation(
            summary = "Status operacji purge",
            description = "Zwraca status (RUNNING/COMPLETED/FAILED) trwającej lub zakończonej "
                    + "operacji purge.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Status operacji"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN lub {tenantId} != własny tenant"),
                    @ApiResponse(responseCode = "404", description = "purgeId nie istnieje lub należy do innego tenanta")
            }
    )
    public ResponseEntity<PurgeResultDto> getPurgeStatus(
            @Parameter(description = "UUID tenanta (musi być zgodny z tenantem z JWT)")
            @PathVariable UUID tenantId,
            @Parameter(description = "UUID operacji purge")
            @PathVariable UUID purgeId
    ) {
        assertOwnTenant(tenantId);

        return ResponseEntity.ok(retentionPurgeService.getPurgeStatus(tenantId, purgeId));
    }

    // =========================================================================
    // Historia operacji purge
    // =========================================================================

    @GetMapping("/history")
    @Operation(
            summary = "Historia operacji purge tenanta",
            description = "Paginowana historia wszystkich operacji purge (retention_purge_log), "
                    + "sortowana malejąco po started_at.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Strona historii operacji"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN lub {tenantId} != własny tenant")
            }
    )
    public ResponseEntity<PagedResponse<PurgeResultDto>> getHistory(
            @Parameter(description = "UUID tenanta (musi być zgodny z tenantem z JWT)")
            @PathVariable UUID tenantId,
            @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        assertOwnTenant(tenantId);

        Page<PurgeResultDto> page = retentionPurgeService.getPurgeHistory(tenantId, pageable);
        return ResponseEntity.ok(PagedResponse.from(page));
    }

    // =========================================================================
    // Bezpieczeństwo — weryfikacja {tenantId} ze ścieżki (patrz Javadoc klasy)
    // =========================================================================

    /**
     * Weryfikuje, że {@code tenantId} ze ścieżki URL jest zgodny z tenantem wywołującego
     * (z JWT, przez {@link TenantContext#getTenantId()}) — jedyna realna bariera bezpieczeństwa
     * dla tego parametru (patrz Javadoc klasy). Ten kontroler NIE przewiduje SUPER_ADMIN
     * (wyłącznie ADMIN, tenant-scoped — zgodnie z zakresem ticketu BE-118), więc w
     * przeciwieństwie do {@code TenantServiceImpl.assertSameTenantUnlessSuperAdmin} nie ma
     * rozgałęzienia na rolę.
     *
     * <p>MUSI być wywołane jako pierwsza instrukcja każdej metody endpointu, przed jakimkolwiek
     * wywołaniem {@link RetentionPolicyService}/{@link RetentionPurgeService} — te serwisy
     * celowo NIE weryfikują "własności" tenanta same (patrz Javadoc klasy).
     *
     * @param tenantId UUID tenanta ze ścieżki URL
     * @throws CrossTenantAccessException gdy {@code tenantId} != tenant wywołującego (HTTP 403)
     */
    private void assertOwnTenant(UUID tenantId) {
        UUID callerTenantId = TenantContext.getTenantId();
        if (!tenantId.equals(callerTenantId)) {
            log.warn("[RetentionController][Security] Próba cross-tenant: pathTenantId={}, callerTenantId={}",
                    tenantId, callerTenantId);
            throw new CrossTenantAccessException(tenantId, callerTenantId);
        }
    }
}
