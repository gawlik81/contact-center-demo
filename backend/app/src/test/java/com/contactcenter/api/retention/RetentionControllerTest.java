package com.contactcenter.api.retention;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.retention.PurgeTriggerType;
import com.contactcenter.domain.retention.RetentionDataCategory;
import com.contactcenter.domain.retention.RetentionEvaluationService;
import com.contactcenter.domain.retention.RetentionPolicyService;
import com.contactcenter.domain.retention.RetentionPurgeService;
import com.contactcenter.domain.retention.TenantRetentionPolicy;
import com.contactcenter.domain.retention.dto.PurgeRequestDto;
import com.contactcenter.domain.retention.dto.PurgeResultDto;
import com.contactcenter.domain.retention.dto.RetentionPolicyDto;
import com.contactcenter.domain.retention.dto.RetentionSummaryDto;
import com.contactcenter.domain.retention.dto.UpdateRetentionPolicyRequest;
import com.contactcenter.security.TenantContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link RetentionController} (EPIC-29, BE-118).
 *
 * <p><strong>Wzorzec testów kontrolera w tym projekcie:</strong> wywołanie metod kontrolera
 * bezpośrednio, {@code TenantContext} mockowany statycznie — ten sam wzorzec co {@code
 * PluginAdminControllerTest}/{@code PluginManualActionControllerTest} (zweryfikowano: projekt
 * NIE ma {@code @WebMvcTest} z aktywnym łańcuchem Spring Security dla kontrolerów pakietu
 * {@code api.*} — nawet istniejące {@code @WebMvcTest} jawnie wyłączają filtry, {@code
 * addFilters = false}). {@code @PreAuthorize("hasRole('ADMIN')")} na klasie {@link
 * RetentionController} jest zweryfikowana deklaratywnie (code review), NIE testem MockMvc —
 * decyzja opisana w notatce implementacyjnej BE-118 ({@code TASKS-BACKEND.md}).
 *
 * <p><strong>Co jest tu pokryte:</strong>
 * <ul>
 *   <li>Happy path wszystkich 7 endpointów (w tym {@code POST .../recompute}, rozszerzenie
 *       BE-112/BE-118 — ręczne przeliczenie na żądanie administratora) — delegacja do właściwej
 *       metody serwisu z poprawnymi argumentami, poprawny status HTTP i body odpowiedzi.</li>
 *   <li>403 cross-tenant: {@code {tenantId}} ze ścieżki != {@code TenantContext.getTenantId()}
 *       → {@link CrossTenantAccessException}, rzucona PRZED jakąkolwiek interakcją z serwisem
 *       (weryfikowane przez {@code verifyNoInteractions}) — to jest zwykła logika Javy w
 *       {@code assertOwnTenant}, testowalna bez Spring Security.</li>
 *   <li>404 purgeId innego tenanta: propagacja {@link ResourceNotFoundException} rzuconej przez
 *       {@code RetentionPurgeService.getPurgeStatus} (logika filtrowania po tenant_id żyje w
 *       repozytorium/serwisie, już pokryta przez {@code RetentionPurgeServiceImplTest} — tu
 *       weryfikujemy tylko, że kontroler NIE łapie/maskuje tego wyjątku).</li>
 *   <li>Walidacja Bean Validation na {@link UpdateRetentionPolicyRequest}/{@link PurgeRequestDto}
 *       — weryfikowana bezpośrednio przez {@link Validator} (ten sam wzorzec co {@code
 *       UserPreferencesServiceTest}), bo Bean Validation jest infrastrukturą Spring MVC
 *       ({@code @Valid}), nieaktywną przy bezpośrednim wywołaniu metody kontrolera bez
 *       {@code MockMvc}.</li>
 *   <li>Niezgodność {@code {category}} ze ścieżki i {@code dataCategory} z body w
 *       {@code updatePolicy} → {@link IllegalArgumentException} (→ 422 przez istniejący
 *       handler) — decyzja projektowa opisana w Javadoc {@link RetentionController}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetentionController – REST API retencji danych per tenant (BE-118)")
class RetentionControllerTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private RetentionPolicyService retentionPolicyService;

    @Mock
    private RetentionPurgeService retentionPurgeService;

    @Mock
    private RetentionEvaluationService retentionEvaluationService;

    private RetentionController controller;
    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        controller = new RetentionController(retentionPolicyService, retentionPurgeService, retentionEvaluationService);
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_A);
        tenantContextMock.when(TenantContext::getUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    private TenantRetentionPolicy policy(RetentionDataCategory category, int months, boolean autoPurge) {
        return TenantRetentionPolicy.builder()
                .policyId(UUID.randomUUID())
                .tenantId(TENANT_A)
                .dataCategory(category)
                .retentionMonths(months)
                .autoPurgeEnabled(autoPurge)
                .updatedBy(USER_ID)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // =========================================================================
    // GET .../policies
    // =========================================================================

    @Nested
    @DisplayName("GET .../policies")
    class ListPolicies {

        @Test
        @DisplayName("happy path: deleguje do RetentionPolicyService.listPolicies i mapuje na DTO")
        void happyPath_delegatesAndMaps() {
            List<TenantRetentionPolicy> policies = List.of(
                    policy(RetentionDataCategory.CONTACT_INTERACTIONS, 60, false),
                    policy(RetentionDataCategory.TRANSCRIPTS, 3, true));
            when(retentionPolicyService.listPolicies(TENANT_A)).thenReturn(policies);

            ResponseEntity<List<RetentionPolicyDto>> response = controller.listPolicies(TENANT_A);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).dataCategory()).isEqualTo(RetentionDataCategory.CONTACT_INTERACTIONS);
        }

        @Test
        @DisplayName("403: {tenantId} ze ścieżki != TenantContext.getTenantId() -> CrossTenantAccessException, brak wywołania serwisu")
        void crossTenant_throwsBeforeServiceCall() {
            assertThatThrownBy(() -> controller.listPolicies(TENANT_B))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(retentionPolicyService);
        }
    }

    // =========================================================================
    // PUT .../policies/{category}
    // =========================================================================

    @Nested
    @DisplayName("PUT .../policies/{category}")
    class UpdatePolicy {

        @Test
        @DisplayName("happy path: deleguje do RetentionPolicyService.updatePolicy z userId z TenantContext")
        void happyPath_delegatesWithUserIdFromContext() {
            UpdateRetentionPolicyRequest request = new UpdateRetentionPolicyRequest(
                    RetentionDataCategory.TRANSCRIPTS, 6, true);
            TenantRetentionPolicy updated = policy(RetentionDataCategory.TRANSCRIPTS, 6, true);
            when(retentionPolicyService.updatePolicy(TENANT_A, RetentionDataCategory.TRANSCRIPTS, 6, true, USER_ID))
                    .thenReturn(updated);

            ResponseEntity<RetentionPolicyDto> response =
                    controller.updatePolicy(TENANT_A, RetentionDataCategory.TRANSCRIPTS, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().retentionMonths()).isEqualTo(6);
            assertThat(response.getBody().autoPurgeEnabled()).isTrue();
            verify(retentionPolicyService).updatePolicy(TENANT_A, RetentionDataCategory.TRANSCRIPTS, 6, true, USER_ID);
        }

        @Test
        @DisplayName("403: {tenantId} niezgodny -> CrossTenantAccessException, brak wywołania serwisu")
        void crossTenant_throwsBeforeServiceCall() {
            UpdateRetentionPolicyRequest request = new UpdateRetentionPolicyRequest(
                    RetentionDataCategory.TRANSCRIPTS, 6, true);

            assertThatThrownBy(() -> controller.updatePolicy(TENANT_B, RetentionDataCategory.TRANSCRIPTS, request))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(retentionPolicyService);
        }

        @Test
        @DisplayName("dataCategory w body != {category} ze ścieżki -> IllegalArgumentException (422), brak wywołania serwisu")
        void mismatchedCategory_throwsIllegalArgumentException() {
            UpdateRetentionPolicyRequest request = new UpdateRetentionPolicyRequest(
                    RetentionDataCategory.CAMPAIGN_DATA, 6, true);

            assertThatThrownBy(() -> controller.updatePolicy(TENANT_A, RetentionDataCategory.TRANSCRIPTS, request))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(retentionPolicyService);
        }
    }

    // =========================================================================
    // GET .../summary
    // =========================================================================

    @Nested
    @DisplayName("GET .../summary")
    class GetSummary {

        @Test
        @DisplayName("happy path: deleguje do RetentionPurgeService.getPendingSummary, zwraca body serwisu bez zmian")
        void happyPath_delegatesToService() {
            List<RetentionSummaryDto> summary = List.of(
                    new RetentionSummaryDto(RetentionDataCategory.CONTACT_INTERACTIONS, 10L,
                            LocalDate.of(2020, 1, 1), LocalDate.of(2020, 2, 1), Instant.now(), true),
                    new RetentionSummaryDto(RetentionDataCategory.RECORDINGS, 0L, null, null, null, false));
            when(retentionPurgeService.getPendingSummary(TENANT_A)).thenReturn(summary);

            ResponseEntity<List<RetentionSummaryDto>> response = controller.getSummary(TENANT_A);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(summary);
        }

        @Test
        @DisplayName("403: {tenantId} niezgodny -> CrossTenantAccessException, brak wywołania serwisu")
        void crossTenant_throwsBeforeServiceCall() {
            assertThatThrownBy(() -> controller.getSummary(TENANT_B))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(retentionPurgeService);
        }
    }

    // =========================================================================
    // POST .../recompute
    // =========================================================================

    @Nested
    @DisplayName("POST .../recompute")
    class Recompute {

        @Test
        @DisplayName("happy path: deleguje do RetentionEvaluationService.runForTenant, zwraca body serwisu bez zmian")
        void happyPath_delegatesToService() {
            List<RetentionSummaryDto> summary = List.of(
                    new RetentionSummaryDto(RetentionDataCategory.CONTACT_INTERACTIONS, 10L,
                            LocalDate.of(2020, 1, 1), LocalDate.of(2020, 2, 1), Instant.now(), true),
                    new RetentionSummaryDto(RetentionDataCategory.RECORDINGS, 0L, null, null, null, false));
            when(retentionEvaluationService.runForTenant(TENANT_A)).thenReturn(summary);

            ResponseEntity<List<RetentionSummaryDto>> response = controller.recompute(TENANT_A);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(summary);
            verify(retentionEvaluationService).runForTenant(TENANT_A);
        }

        @Test
        @DisplayName("403: {tenantId} niezgodny -> CrossTenantAccessException, brak wywołania serwisu")
        void crossTenant_throwsBeforeServiceCall() {
            assertThatThrownBy(() -> controller.recompute(TENANT_B))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(retentionEvaluationService);
        }
    }

    // =========================================================================
    // POST .../purge
    // =========================================================================

    @Nested
    @DisplayName("POST .../purge")
    class Purge {

        @Test
        @DisplayName("happy path: 202 Accepted + purgeId w body, MANUAL trigger + userId z TenantContext")
        void happyPath_returns202WithPurgeId() {
            PurgeRequestDto request = new PurgeRequestDto(RetentionDataCategory.CONTACT_INTERACTIONS);
            UUID purgeId = UUID.randomUUID();
            when(retentionPurgeService.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS,
                    PurgeTriggerType.MANUAL, USER_ID)).thenReturn(purgeId);

            ResponseEntity<Map<String, String>> response = controller.purge(TENANT_A, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).containsEntry("purgeId", purgeId.toString());
            verify(retentionPurgeService).purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS,
                    PurgeTriggerType.MANUAL, USER_ID);
        }

        @Test
        @DisplayName("403: {tenantId} niezgodny -> CrossTenantAccessException, brak wywołania serwisu")
        void crossTenant_throwsBeforeServiceCall() {
            PurgeRequestDto request = new PurgeRequestDto(RetentionDataCategory.CONTACT_INTERACTIONS);

            assertThatThrownBy(() -> controller.purge(TENANT_B, request))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(retentionPurgeService);
        }

        @Test
        @DisplayName("kategoria nieobsługiwana (RECORDINGS) -> wyjątek serwisu propagowany, kontroler nie łapie")
        void unsupportedCategory_propagatesServiceException() {
            PurgeRequestDto request = new PurgeRequestDto(RetentionDataCategory.RECORDINGS);
            when(retentionPurgeService.purge(eq(TENANT_A), eq(RetentionDataCategory.RECORDINGS),
                    eq(PurgeTriggerType.MANUAL), eq(USER_ID)))
                    .thenThrow(new UnsupportedOperationException("RECORDINGS nieobsługiwane"));

            assertThatThrownBy(() -> controller.purge(TENANT_A, request))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // GET .../purge/{purgeId}
    // =========================================================================

    @Nested
    @DisplayName("GET .../purge/{purgeId}")
    class GetPurgeStatus {

        @Test
        @DisplayName("happy path: deleguje do RetentionPurgeService.getPurgeStatus")
        void happyPath_delegatesToService() {
            UUID purgeId = UUID.randomUUID();
            PurgeResultDto result = new PurgeResultDto(purgeId, TENANT_A, RetentionDataCategory.TRANSCRIPTS,
                    PurgeTriggerType.MANUAL, USER_ID, LocalDate.of(2026, 1, 1), 7L,
                    "COMPLETED", Instant.now(), Instant.now(), null);
            when(retentionPurgeService.getPurgeStatus(TENANT_A, purgeId)).thenReturn(result);

            ResponseEntity<PurgeResultDto> response = controller.getPurgeStatus(TENANT_A, purgeId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(result);
        }

        @Test
        @DisplayName("403: {tenantId} niezgodny -> CrossTenantAccessException, brak wywołania serwisu")
        void crossTenant_throwsBeforeServiceCall() {
            UUID purgeId = UUID.randomUUID();

            assertThatThrownBy(() -> controller.getPurgeStatus(TENANT_B, purgeId))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(retentionPurgeService);
        }

        @Test
        @DisplayName("404: purgeId należący do innego tenanta -> ResourceNotFoundException propagowana (nie CrossTenantAccessException)")
        void purgeIdOfOtherTenant_propagatesResourceNotFoundException() {
            UUID purgeId = UUID.randomUUID();
            // {tenantId}==TENANT_A przechodzi weryfikację 403 (to własny tenant wywołującego) —
            // ale purgeId w rzeczywistości należy do TENANT_B (zgadywanie UUID). Serwis/repozytorium
            // filtruje po (purgeId, tenantId) i zwraca "nie znaleziono" -> 404, NIE 403 (patrz
            // Javadoc RetentionController, sekcja bezpieczeństwa, punkt 2).
            when(retentionPurgeService.getPurgeStatus(TENANT_A, purgeId))
                    .thenThrow(new ResourceNotFoundException("Operacja purge nie istnieje: purgeId=" + purgeId));

            assertThatThrownBy(() -> controller.getPurgeStatus(TENANT_A, purgeId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .isNotInstanceOf(CrossTenantAccessException.class);
        }
    }

    // =========================================================================
    // GET .../history
    // =========================================================================

    @Nested
    @DisplayName("GET .../history")
    class GetHistory {

        @Test
        @DisplayName("happy path: deleguje do RetentionPurgeService.getPurgeHistory, opakowuje w PagedResponse")
        void happyPath_wrapsInPagedResponse() {
            PurgeResultDto entry = new PurgeResultDto(UUID.randomUUID(), TENANT_A,
                    RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.AUTO, null,
                    LocalDate.of(2026, 1, 1), 3L, "COMPLETED", Instant.now(), Instant.now(), null);
            Pageable pageable = PageRequest.of(0, 20);
            Page<PurgeResultDto> page = new PageImpl<>(List.of(entry), pageable, 1);
            when(retentionPurgeService.getPurgeHistory(TENANT_A, pageable)).thenReturn(page);

            var response = controller.getHistory(TENANT_A, pageable);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().content()).containsExactly(entry);
            assertThat(response.getBody().totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("403: {tenantId} niezgodny -> CrossTenantAccessException, brak wywołania serwisu")
        void crossTenant_throwsBeforeServiceCall() {
            Pageable pageable = PageRequest.of(0, 20);

            assertThatThrownBy(() -> controller.getHistory(TENANT_B, pageable))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(retentionPurgeService);
        }
    }

    // =========================================================================
    // 403 cross-tenant – pokrycie parametryzowane wszystkich 6 endpointów
    // =========================================================================

    @Nested
    @DisplayName("403 cross-tenant – wszystkie endpointy odrzucają {tenantId} != własny tenant")
    class CrossTenantAllEndpoints {

        @ParameterizedTest(name = "{0}")
        @EnumSource(RetentionDataCategory.class)
        @DisplayName("updatePolicy odrzuca cross-tenant niezależnie od kategorii")
        void updatePolicy_rejectsCrossTenantForAnyCategory(RetentionDataCategory category) {
            UpdateRetentionPolicyRequest request = new UpdateRetentionPolicyRequest(category, 12, false);

            assertThatThrownBy(() -> controller.updatePolicy(TENANT_B, category, request))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    // =========================================================================
    // Walidacja Bean Validation (@Valid) – UpdateRetentionPolicyRequest / PurgeRequestDto
    // =========================================================================

    @Nested
    @DisplayName("Walidacja @Valid – UpdateRetentionPolicyRequest / PurgeRequestDto")
    class BeanValidation {

        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        @Test
        @DisplayName("UpdateRetentionPolicyRequest: retentionMonths=0 -> violation (poniżej minimum 1)")
        void retentionMonths_belowMinimum_isRejected() {
            var request = new UpdateRetentionPolicyRequest(RetentionDataCategory.TRANSCRIPTS, 0, false);

            Set<ConstraintViolation<UpdateRetentionPolicyRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("UpdateRetentionPolicyRequest: retentionMonths=121 -> violation (powyżej maksimum 120)")
        void retentionMonths_aboveMaximum_isRejected() {
            var request = new UpdateRetentionPolicyRequest(RetentionDataCategory.TRANSCRIPTS, 121, false);

            Set<ConstraintViolation<UpdateRetentionPolicyRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("UpdateRetentionPolicyRequest: retentionMonths=1 i 120 (granice) -> brak violation")
        void retentionMonths_atBoundaries_isAccepted() {
            assertThat(validator.validate(
                    new UpdateRetentionPolicyRequest(RetentionDataCategory.TRANSCRIPTS, 1, false))).isEmpty();
            assertThat(validator.validate(
                    new UpdateRetentionPolicyRequest(RetentionDataCategory.TRANSCRIPTS, 120, false))).isEmpty();
        }

        @Test
        @DisplayName("UpdateRetentionPolicyRequest: dataCategory=null -> violation")
        void dataCategory_null_isRejected() {
            var request = new UpdateRetentionPolicyRequest(null, 12, false);

            Set<ConstraintViolation<UpdateRetentionPolicyRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("UpdateRetentionPolicyRequest: retentionMonths=null -> violation")
        void retentionMonths_null_isRejected() {
            var request = new UpdateRetentionPolicyRequest(RetentionDataCategory.TRANSCRIPTS, null, false);

            Set<ConstraintViolation<UpdateRetentionPolicyRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("PurgeRequestDto: dataCategory=null -> violation")
        void purgeRequest_dataCategoryNull_isRejected() {
            var request = new PurgeRequestDto(null);

            Set<ConstraintViolation<PurgeRequestDto>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("PurgeRequestDto: dataCategory wypełnione -> brak violation")
        void purgeRequest_dataCategoryPresent_isAccepted() {
            var request = new PurgeRequestDto(RetentionDataCategory.CONTACT_INTERACTIONS);

            assertThat(validator.validate(request)).isEmpty();
        }
    }
}
