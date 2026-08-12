package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link RetentionPolicyServiceImpl}.
 *
 * <p>Repozytorium jest mockowane — testy weryfikują logikę biznesową serwisu:
 * walidację granic, seedowanie domyślnych polityk (BE-111 kryteria akceptacji) oraz
 * propagację upsertu do repozytorium.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RetentionPolicyService – CRUD polityk retencji i seeding")
class RetentionPolicyServiceImplTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private TenantRetentionPolicyRepository repository;

    @InjectMocks
    private RetentionPolicyServiceImpl service;

    private TenantRetentionPolicy buildPolicy(RetentionDataCategory category, int months, boolean autoPurge) {
        return TenantRetentionPolicy.builder()
                .policyId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .dataCategory(category)
                .retentionMonths(months)
                .autoPurgeEnabled(autoPurge)
                .updatedBy(USER_ID)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // =========================================================================
    // listPolicies
    // =========================================================================

    @Nested
    @DisplayName("listPolicies()")
    class ListPolicies {

        @Test
        @DisplayName("deleguje do repozytorium i zwraca listę polityk tenanta")
        void shouldDelegateToRepository() {
            List<TenantRetentionPolicy> policies = List.of(
                    buildPolicy(RetentionDataCategory.CONTACT_INTERACTIONS, 60, false),
                    buildPolicy(RetentionDataCategory.CAMPAIGN_DATA, 60, false)
            );
            when(repository.findAllByTenantId(TENANT_ID)).thenReturn(policies);

            List<TenantRetentionPolicy> result = service.listPolicies(TENANT_ID);

            assertThat(result).hasSize(2).isEqualTo(policies);
        }
    }

    // =========================================================================
    // updatePolicy
    // =========================================================================

    @Nested
    @DisplayName("updatePolicy()")
    class UpdatePolicy {

        @Test
        @DisplayName("aktualizuje istniejącą politykę przez upsert repozytorium")
        void shouldUpdateExistingPolicyViaUpsert() {
            TenantRetentionPolicy updated = buildPolicy(RetentionDataCategory.RECORDINGS, 12, true);
            when(repository.upsert(TENANT_ID, RetentionDataCategory.RECORDINGS, 12, true, USER_ID))
                    .thenReturn(updated);

            TenantRetentionPolicy result = service.updatePolicy(
                    TENANT_ID, RetentionDataCategory.RECORDINGS, 12, true, USER_ID);

            assertThat(result).isEqualTo(updated);
            verify(repository).upsert(TENANT_ID, RetentionDataCategory.RECORDINGS, 12, true, USER_ID);
        }

        @Test
        @DisplayName("tworzy politykę dla nieistniejącej kategorii przez upsert (nie 404)")
        void shouldCreateMissingPolicyViaUpsertInsteadOf404() {
            // Serwis nie sprawdza istnienia wiersza przed zapisem — deleguje bezpośrednio
            // do atomowego upsertu repozytorium (INSERT ... ON CONFLICT DO UPDATE), który
            // sam obsługuje zarówno przypadek istniejącego, jak i brakującego wiersza.
            TenantRetentionPolicy created = buildPolicy(RetentionDataCategory.TRANSCRIPTS, 6, false);
            when(repository.upsert(TENANT_ID, RetentionDataCategory.TRANSCRIPTS, 6, false, USER_ID))
                    .thenReturn(created);

            TenantRetentionPolicy result = service.updatePolicy(
                    TENANT_ID, RetentionDataCategory.TRANSCRIPTS, 6, false, USER_ID);

            assertThat(result).isEqualTo(created);
            verify(repository, never()).findByTenantIdAndCategory(any(), any());
        }

        @ParameterizedTest(name = "retentionMonths={0} jest dozwolone")
        @CsvSource({"1", "60", "120"})
        @DisplayName("akceptuje wartości graniczne [1,120]")
        void shouldAcceptBoundaryValues(int months) {
            when(repository.upsert(any(), any(), anyInt(), anyBoolean(), any()))
                    .thenReturn(buildPolicy(RetentionDataCategory.CAMPAIGN_DATA, months, false));

            service.updatePolicy(TENANT_ID, RetentionDataCategory.CAMPAIGN_DATA, months, false, USER_ID);

            verify(repository).upsert(TENANT_ID, RetentionDataCategory.CAMPAIGN_DATA, months, false, USER_ID);
        }

        @ParameterizedTest(name = "retentionMonths={0} rzuca IllegalArgumentException")
        @CsvSource({"0", "-1", "121", "500"})
        @DisplayName("odrzuca wartości spoza zakresu [1,120]")
        void shouldRejectOutOfRangeValues(int months) {
            assertThatThrownBy(() -> service.updatePolicy(
                    TENANT_ID, RetentionDataCategory.CAMPAIGN_DATA, months, false, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[1, 120]");

            verify(repository, never()).upsert(any(), any(), anyInt(), anyBoolean(), any());
        }
    }

    // =========================================================================
    // getRetentionMonths
    // =========================================================================

    @Nested
    @DisplayName("getRetentionMonths()")
    class GetRetentionMonths {

        @Test
        @DisplayName("zwraca retentionMonths istniejącej polityki")
        void shouldReturnRetentionMonthsWhenPolicyExists() {
            when(repository.findByTenantIdAndCategory(TENANT_ID, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(Optional.of(buildPolicy(RetentionDataCategory.CONTACT_INTERACTIONS, 60, false)));

            int result = service.getRetentionMonths(TENANT_ID, RetentionDataCategory.CONTACT_INTERACTIONS);

            assertThat(result).isEqualTo(60);
        }

        @Test
        @DisplayName("rzuca ResourceNotFoundException gdy brak polityki")
        void shouldThrowWhenPolicyMissing() {
            when(repository.findByTenantIdAndCategory(TENANT_ID, RetentionDataCategory.RECORDINGS))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getRetentionMonths(TENANT_ID, RetentionDataCategory.RECORDINGS))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(TENANT_ID.toString())
                    .hasMessageContaining("RECORDINGS");
        }
    }

    // =========================================================================
    // findMinRetentionMonths
    // =========================================================================

    @Nested
    @DisplayName("findMinRetentionMonths()")
    class FindMinRetentionMonths {

        @Test
        @DisplayName("zwraca minimalną retencję po wszystkich tenantach dla kategorii")
        void shouldReturnMinRetentionMonthsAcrossAllTenants() {
            when(repository.findMinRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(Optional.of(3));

            int result = service.findMinRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS);

            assertThat(result).isEqualTo(3);
        }

        @Test
        @DisplayName("rzuca ResourceNotFoundException gdy żaden tenant nie ma polityki dla kategorii")
        void shouldThrowWhenNoTenantHasPolicyForCategory() {
            when(repository.findMinRetentionMonths(RetentionDataCategory.TRANSCRIPTS))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findMinRetentionMonths(RetentionDataCategory.TRANSCRIPTS))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("TRANSCRIPTS");
        }
    }

    // =========================================================================
    // seedDefaultPolicies
    // =========================================================================

    @Nested
    @DisplayName("seedDefaultPolicies()")
    class SeedDefaultPolicies {

        @Test
        @DisplayName("zasiewa dokładnie 4 kategorie z domyślnymi wartościami (RECORDINGS wg config tenanta)")
        void shouldSeedAllFourCategoriesWithDefaults() {
            // recording_retention_days=90 (domyślny fallback) -> CEIL(90/30.0) = 3 miesiące
            when(repository.findConfiguredRecordingRetentionDays(TENANT_ID)).thenReturn(Optional.of(90));

            service.seedDefaultPolicies(TENANT_ID);

            verify(repository).insertIfMissing(TENANT_ID, RetentionDataCategory.CONTACT_INTERACTIONS, 60, false);
            verify(repository).insertIfMissing(TENANT_ID, RetentionDataCategory.CAMPAIGN_DATA, 60, false);
            verify(repository).insertIfMissing(TENANT_ID, RetentionDataCategory.TRANSCRIPTS, 3, false);
            verify(repository).insertIfMissing(TENANT_ID, RetentionDataCategory.RECORDINGS, 3, false);
            verify(repository, times(4)).insertIfMissing(eq(TENANT_ID), any(), anyInt(), eq(false));
        }

        @Test
        @DisplayName("personalizuje RECORDINGS z tenant.config (np. 45 dni -> 2 miesiące, zaokrąglenie w górę)")
        void shouldPersonalizeRecordingsFromTenantConfig() {
            when(repository.findConfiguredRecordingRetentionDays(TENANT_ID)).thenReturn(Optional.of(45));

            service.seedDefaultPolicies(TENANT_ID);

            // CEIL(45/30.0) = 2
            verify(repository).insertIfMissing(TENANT_ID, RetentionDataCategory.RECORDINGS, 2, false);
        }

        @Test
        @DisplayName("koryguje personalizowaną wartość RECORDINGS do górnej granicy 120 miesięcy")
        void shouldClampRecordingsMonthsToUpperBound() {
            // 4000 dni -> CEIL(4000/30.0) = 134 > 120 -> przycięte do 120
            when(repository.findConfiguredRecordingRetentionDays(TENANT_ID)).thenReturn(Optional.of(4000));

            service.seedDefaultPolicies(TENANT_ID);

            verify(repository).insertIfMissing(TENANT_ID, RetentionDataCategory.RECORDINGS, 120, false);
        }

        @Test
        @DisplayName("fallback 3 miesiące dla RECORDINGS gdy tenant nie znaleziony (defensywnie)")
        void shouldFallbackWhenTenantConfigUnavailable() {
            when(repository.findConfiguredRecordingRetentionDays(TENANT_ID)).thenReturn(Optional.empty());

            service.seedDefaultPolicies(TENANT_ID);

            verify(repository).insertIfMissing(TENANT_ID, RetentionDataCategory.RECORDINGS, 3, false);
        }

        @Test
        @DisplayName("jest idempotentne z punktu widzenia serwisu — zawsze wywołuje insertIfMissing (nie upsert)")
        void shouldUseInsertIfMissingNotUpsert() {
            when(repository.findConfiguredRecordingRetentionDays(TENANT_ID)).thenReturn(Optional.of(90));

            service.seedDefaultPolicies(TENANT_ID);

            verify(repository, never()).upsert(any(), any(), anyInt(), anyBoolean(), any());
        }
    }
}
