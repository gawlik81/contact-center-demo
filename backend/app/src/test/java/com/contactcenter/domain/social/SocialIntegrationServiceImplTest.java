package com.contactcenter.domain.social;

import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link SocialIntegrationServiceImpl#getDecryptedIntegration(UUID)} (świeżo
 * dodana metoda, BE – podłączenie WhatsApp Business, brak wcześniejszych testów dla
 * {@code domain.social.SocialIntegrationServiceImpl}).
 *
 * <p><strong>Zakres celowo ograniczony</strong> do {@code getDecryptedIntegration()} oraz (od
 * naprawy CRITICAL – kolizja {@code phoneNumberId}/{@code pageId} między tenantami, code review
 * 2026-08-29) nowej walidacji konfliktu w {@code saveIntegration()} (zobacz zagnieżdżoną klasę
 * {@code SaveIntegrationConflict}) – pozostałe aspekty {@code saveIntegration} (deduplikacja
 * update-in-place, audit log), {@code deleteIntegration} i {@code refreshExpiringTokens} pozostają
 * nieprzetestowane (dług istniejący sprzed tej zmiany, poza zakresem zadania).
 *
 * <p><strong>Multi-tenancy:</strong> logika filtrowania po {@code tenant_id} (RLS) żyje
 * w {@link SocialIntegrationRepository} – tutaj (podobnie jak w {@code RetentionControllerTest})
 * weryfikujemy wyłącznie, że serwis odpytuje repozytorium tenantId wziętym z
 * {@link TenantContext#getTenantId()}, a nie z żadnego innego źródła, i że integracja
 * nieznaleziona dla danego (tenantId, integrationId) skutkuje 404 – bez leakowania danych innego
 * tenanta.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SocialIntegrationServiceImpl.getDecryptedIntegration()")
class SocialIntegrationServiceImplTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID INTEGRATION_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final String PHONE_NUMBER_ID = "1234567890";

    @Mock
    private SocialIntegrationRepository repository;

    @Mock
    private SocialTokenEncryptionService encryptionService;

    @Mock
    private AuditLogService auditLogService;

    private SocialIntegrationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SocialIntegrationServiceImpl(repository, encryptionService, auditLogService);
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SocialIntegration buildIntegration(UUID tenantId, byte[] encryptedToken) {
        return SocialIntegration.builder()
                .integrationId(INTEGRATION_ID)
                .tenantId(tenantId)
                .platform(SocialPlatform.WHATSAPP)
                .pageId(PHONE_NUMBER_ID)
                .webhookStatus("ACTIVE")
                .accessTokenEncrypted(encryptedToken)
                .build();
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("integracja istnieje z tokenem -> zwraca SocialIntegrationDecrypted z odszyfrowanym tokenem")
        void integrationWithToken_returnsDecrypted() {
            byte[] encryptedToken = "cipher-bytes".getBytes(StandardCharsets.UTF_8);
            SocialIntegration integration = buildIntegration(TENANT_ID, encryptedToken);

            when(repository.findByTenantIdAndIntegrationId(TENANT_ID, INTEGRATION_ID))
                    .thenReturn(Optional.of(integration));
            when(encryptionService.decrypt(encryptedToken)).thenReturn("plaintext-access-token");

            SocialIntegrationDecrypted result = service.getDecryptedIntegration(INTEGRATION_ID);

            assertThat(result.integrationId()).isEqualTo(INTEGRATION_ID);
            assertThat(result.platform()).isEqualTo(SocialPlatform.WHATSAPP);
            assertThat(result.pageId()).isEqualTo(PHONE_NUMBER_ID);
            assertThat(result.accessToken()).isEqualTo("plaintext-access-token");
        }
    }

    // =========================================================================
    // 404 – integracja nie istnieje dla tenanta
    // =========================================================================

    @Nested
    @DisplayName("404 – integracja nie istnieje")
    class NotFound {

        @Test
        @DisplayName("brak integracji dla (tenantId, integrationId) -> ResponseStatusException 404, brak wywołania deszyfrowania")
        void integrationNotFound_throws404() {
            when(repository.findByTenantIdAndIntegrationId(TENANT_ID, INTEGRATION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDecryptedIntegration(INTEGRATION_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            verify(encryptionService, never()).decrypt(any());
        }
    }

    // =========================================================================
    // 422 – integracja bez skonfigurowanego tokenu
    // =========================================================================

    @Nested
    @DisplayName("422 – integracja bez tokenu")
    class MissingToken {

        @Test
        @DisplayName("accessTokenEncrypted == null -> ResponseStatusException 422, brak wywołania deszyfrowania")
        void nullToken_throws422() {
            SocialIntegration integration = buildIntegration(TENANT_ID, null);
            when(repository.findByTenantIdAndIntegrationId(TENANT_ID, INTEGRATION_ID))
                    .thenReturn(Optional.of(integration));

            assertThatThrownBy(() -> service.getDecryptedIntegration(INTEGRATION_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            verify(encryptionService, never()).decrypt(any());
        }

        @Test
        @DisplayName("accessTokenEncrypted == pusta tablica bajtów -> ResponseStatusException 422")
        void emptyToken_throws422() {
            SocialIntegration integration = buildIntegration(TENANT_ID, new byte[0]);
            when(repository.findByTenantIdAndIntegrationId(TENANT_ID, INTEGRATION_ID))
                    .thenReturn(Optional.of(integration));

            assertThatThrownBy(() -> service.getDecryptedIntegration(INTEGRATION_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            verify(encryptionService, never()).decrypt(any());
        }
    }

    // =========================================================================
    // Multi-tenancy
    // =========================================================================

    @Nested
    @DisplayName("multi-tenancy")
    class MultiTenancy {

        @Test
        @DisplayName("serwis odpytuje repozytorium tenantId z TenantContext, nie z innego źródła")
        void queriesRepositoryWithTenantIdFromContext() {
            when(repository.findByTenantIdAndIntegrationId(TENANT_ID, INTEGRATION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDecryptedIntegration(INTEGRATION_ID))
                    .isInstanceOf(ResponseStatusException.class);

            verify(repository).findByTenantIdAndIntegrationId(TENANT_ID, INTEGRATION_ID);
        }

        @Test
        @DisplayName("zmiana TenantContext -> zmienia tenantId przekazywany do repozytorium (izolacja per-wywołanie)")
        void tenantContextChange_changesRepositoryTenantIdArgument() {
            SocialIntegration integrationForOtherTenant = buildIntegration(
                    OTHER_TENANT_ID, "enc-other".getBytes(StandardCharsets.UTF_8));

            when(repository.findByTenantIdAndIntegrationId(OTHER_TENANT_ID, INTEGRATION_ID))
                    .thenReturn(Optional.of(integrationForOtherTenant));
            when(encryptionService.decrypt(any())).thenReturn("token-other-tenant");

            TenantContext.setTenantId(OTHER_TENANT_ID);
            SocialIntegrationDecrypted result = service.getDecryptedIntegration(INTEGRATION_ID);

            assertThat(result.accessToken()).isEqualTo("token-other-tenant");
            verify(repository).findByTenantIdAndIntegrationId(OTHER_TENANT_ID, INTEGRATION_ID);
            // TENANT_ID ustawiony w @BeforeEach NIE powinien być użyty do tego wywołania
            verify(repository, never()).findByTenantIdAndIntegrationId(TENANT_ID, INTEGRATION_ID);
        }

        @Test
        @DisplayName("integrationId istnieje faktycznie dla innego tenanta -> zapytanie (tenantId, integrationId) własnego tenanta zwraca empty -> 404, brak wycieku danych")
        void integrationBelongingToOtherTenant_isNotLeakedAs404() {
            // Repozytorium filtruje jednym zapytaniem po (tenantId, integrationId) - integracja
            // istniejąca fizycznie, ale należąca do innego tenanta, nie może zostać zwrócona dla
            // TENANT_ID. Mock odzwierciedla dokładnie taki wynik (empty), jak zrobiłoby to RLS/JPQL.
            when(repository.findByTenantIdAndIntegrationId(TENANT_ID, INTEGRATION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDecryptedIntegration(INTEGRATION_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // =========================================================================
    // 409 – kolizja (platform, pageId) między tenantami (saveIntegration)
    // =========================================================================

    /**
     * Testy dla naprawy CRITICAL (code review 2026-08-29): {@code saveIntegration()} musi
     * odrzucić próbę podłączenia integracji, gdy dana para {@code (platform, pageId)} jest już
     * przypisana INNEMU tenantowi – w przeciwnym razie webhook (identyfikacja tenanta wyłącznie po
     * tej parze, {@link SocialIntegrationRepository#findByPlatformAndPageId}) mógłby routować
     * wiadomości klienta jednego tenanta do innego.
     */
    @Nested
    @DisplayName("saveIntegration() – 409 przy kolizji (platform, pageId) między tenantami")
    class SaveIntegrationConflict {

        @Test
        @DisplayName("pageId już przypisany innemu tenantowi -> ConflictException, brak szyfrowania i zapisu")
        void pageIdAssignedToOtherTenant_throwsConflictWithoutSaving() {
            when(repository.existsByPlatformAndPageIdAndTenantIdNot(SocialPlatform.WHATSAPP, PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.saveIntegration(
                    SocialPlatform.WHATSAPP, PHONE_NUMBER_ID, "Display", "token", null, null))
                    .isInstanceOf(ConflictException.class);

            verify(repository, never()).findByTenantIdAndPlatformAndPageId(any(), any(), any());
            verify(repository, never()).save(any());
            verify(encryptionService, never()).encrypt(any());
        }

        @Test
        @DisplayName("pageId wolny (lub należy do własnego tenanta) -> saveIntegration kontynuuje normalnie")
        void pageIdNotAssignedToOtherTenant_proceedsNormally() {
            // saveIntegration() publikuje audit event z TenantContext.getUserId() – wymagany
            // w kontekście tego wątku testowego (BeforeEach ustawia tylko tenantId).
            TenantContext.setUserId(UUID.fromString("dddddddd-1111-1111-1111-111111111111"));

            when(repository.existsByPlatformAndPageIdAndTenantIdNot(SocialPlatform.WHATSAPP, PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(false);
            when(repository.findByTenantIdAndPlatformAndPageId(TENANT_ID, SocialPlatform.WHATSAPP, PHONE_NUMBER_ID))
                    .thenReturn(Optional.empty());
            when(encryptionService.encrypt("token")).thenReturn("cipher".getBytes(StandardCharsets.UTF_8));
            when(repository.save(any())).thenAnswer(inv -> {
                SocialIntegration toSave = inv.getArgument(0);
                toSave.setIntegrationId(INTEGRATION_ID);
                return toSave;
            });

            var dto = service.saveIntegration(
                    SocialPlatform.WHATSAPP, PHONE_NUMBER_ID, "Display", "token", null, null);

            assertThat(dto.integrationId()).isEqualTo(INTEGRATION_ID);
            assertThat(dto.pageId()).isEqualTo(PHONE_NUMBER_ID);
            verify(repository).existsByPlatformAndPageIdAndTenantIdNot(SocialPlatform.WHATSAPP, PHONE_NUMBER_ID, TENANT_ID);
            verify(repository).save(any());
        }
    }
}
