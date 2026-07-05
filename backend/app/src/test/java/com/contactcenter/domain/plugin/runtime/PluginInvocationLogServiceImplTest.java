package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginInvocationLog;
import com.contactcenter.domain.plugin.PluginInvocationLogRepository;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.dto.PluginInvocationLogDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy {@link PluginInvocationLogServiceImpl} (BE-105) — zapis (z redakcją PII) i odczyt
 * paginowanej historii wywołań pluginu.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginInvocationLogServiceImpl – record(...) i findByInstallation(...)")
class PluginInvocationLogServiceImplTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INSTALLATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONTACT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private PluginInvocationLogRepository repository;
    @Mock private PluginRegistrationService pluginRegistrationService;

    private PluginInvocationLogServiceImpl service;

    @BeforeEach
    void setUp() {
        // ObjectMapper realny (nie mock) – test PII musi weryfikować zawartość JSON faktycznie
        // wyprodukowanego przez serializację, nie zachowanie mocka.
        service = new PluginInvocationLogServiceImpl(repository, pluginRegistrationService, new ObjectMapper());
    }

    @Nested
    @DisplayName("record(...)")
    class RecordTests {

        @Test
        @DisplayName("mapuje argumenty na PluginInvocationLog i woła repository.insert(...)")
        void mapsArgumentsToEntity() {
            service.record(TENANT_ID, INSTALLATION_ID, ExtensionPoint.DISPOSITION_SET,
                    InvocationStatus.SUCCESS, 123L, null, CONTACT_ID, null);

            ArgumentCaptor<PluginInvocationLog> captor = ArgumentCaptor.forClass(PluginInvocationLog.class);
            verify(repository).insert(captor.capture());

            PluginInvocationLog entry = captor.getValue();
            assertThat(entry.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(entry.getTenantPluginInstallationId()).isEqualTo(INSTALLATION_ID);
            assertThat(entry.getExtensionPoint()).isEqualTo("DISPOSITION_SET");
            assertThat(entry.getStatus()).isEqualTo("SUCCESS");
            assertThat(entry.getDurationMs()).isEqualTo(123);
            assertThat(entry.getErrorSummary()).isNull();
            assertThat(entry.getRelatedContactId()).isEqualTo(CONTACT_ID);
        }

        @Test
        @DisplayName("errorSummary niepusty jest zachowany na encji")
        void errorSummary_isPreserved() {
            service.record(TENANT_ID, INSTALLATION_ID, ExtensionPoint.PRE_CONTACT_CONNECT,
                    InvocationStatus.FAILED, 50L, "boom", null, null);

            ArgumentCaptor<PluginInvocationLog> captor = ArgumentCaptor.forClass(PluginInvocationLog.class);
            verify(repository).insert(captor.capture());

            assertThat(captor.getValue().getErrorSummary()).isEqualTo("boom");
            assertThat(captor.getValue().getRelatedContactId()).isNull();
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: requestPayload z phoneNumber/email -> JSON zapisany NIE zawiera surowych wartości PII")
        void requestPayloadWithPii_isRedactedBeforeSave() {
            Map<String, Object> payload = Map.of(
                    "phoneNumber", "+48123456789",
                    "email", "jan.kowalski@example.com",
                    "note", "klient pytał o status zamówienia");

            service.record(TENANT_ID, INSTALLATION_ID, ExtensionPoint.MANUAL_ACTION,
                    InvocationStatus.SUCCESS, 10L, null, CONTACT_ID, payload);

            ArgumentCaptor<PluginInvocationLog> captor = ArgumentCaptor.forClass(PluginInvocationLog.class);
            verify(repository).insert(captor.capture());

            String redactedJson = captor.getValue().getRequestPayloadRedacted();
            assertThat(redactedJson).isNotNull();
            assertThat(redactedJson).doesNotContain("+48123456789");
            assertThat(redactedJson).doesNotContain("jan.kowalski@example.com");
            assertThat(redactedJson).contains(PiiRedactor.REDACTED_PLACEHOLDER);
            // Pole nie-PII musi przetrwać redakcję w niezmienionej formie (debugowalność).
            assertThat(redactedJson).contains("klient pytał o status zamówienia");
        }

        @Test
        @DisplayName("requestPayload == null -> requestPayloadRedacted == null, brak wywołania redakcji")
        void nullRequestPayload_resultsInNullRedactedColumn() {
            service.record(TENANT_ID, INSTALLATION_ID, ExtensionPoint.CUSTOMER_SYNC,
                    InvocationStatus.SKIPPED_DISABLED, 0L, "wyłączone", null, null);

            ArgumentCaptor<PluginInvocationLog> captor = ArgumentCaptor.forClass(PluginInvocationLog.class);
            verify(repository).insert(captor.capture());

            assertThat(captor.getValue().getRequestPayloadRedacted()).isNull();
        }

        @Test
        @DisplayName("repository.insert(...) rzuca RuntimeException -> błąd jest złapany, NIE propaguje się do wołającego")
        void repositoryInsertThrows_isCaughtAndDoesNotPropagate() {
            org.mockito.Mockito.doThrow(new RuntimeException("DB unavailable"))
                    .when(repository).insert(any());

            // Nie powinno rzucić – record(...) jest best-effort względem dispatchu pluginu, który
            // już się zakończył w momencie wołania tej metody.
            service.record(TENANT_ID, INSTALLATION_ID, ExtensionPoint.POST_CONTACT_END,
                    InvocationStatus.SUCCESS, 5L, null, null, null);
        }
    }

    @Nested
    @DisplayName("findByInstallation(...)")
    class FindByInstallationTests {

        @Test
        @DisplayName("happy path: ownership OK -> repo.findByInstallation wołane, wynik zmapowany na DTO")
        void happyPath_mapsEntitiesToDto() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(null); // tylko ownership – wartość zwracana nieistotna dla tego serwisu

            PluginInvocationLog entity = PluginInvocationLog.builder()
                    .id(UUID.randomUUID())
                    .invokedAt(Instant.now())
                    .tenantId(TENANT_ID)
                    .tenantPluginInstallationId(INSTALLATION_ID)
                    .extensionPoint("PRE_CONTACT_CONNECT")
                    .status("SUCCESS")
                    .durationMs(42)
                    .build();
            Pageable pageable = PageRequest.of(0, 20);
            Page<PluginInvocationLog> entityPage = new PageImpl<>(List.of(entity), pageable, 1);

            when(repository.findByInstallation(eq(INSTALLATION_ID), eq(TENANT_ID), isNull(), eq(pageable)))
                    .thenReturn(entityPage);

            Page<PluginInvocationLogDto> result =
                    service.findByInstallation(TENANT_ID, INSTALLATION_ID, null, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).status()).isEqualTo("SUCCESS");
            assertThat(result.getContent().get(0).extensionPoint()).isEqualTo("PRE_CONTACT_CONNECT");
        }

        @Test
        @DisplayName("filtr status != null jest przekazany do repo jako status.name()")
        void statusFilter_passedAsName() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID)).thenReturn(null);
            Pageable pageable = PageRequest.of(0, 20);
            when(repository.findByInstallation(eq(INSTALLATION_ID), eq(TENANT_ID), eq("FAILED"), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(), pageable, 0));

            service.findByInstallation(TENANT_ID, INSTALLATION_ID, InvocationStatus.FAILED, pageable);

            verify(repository).findByInstallation(INSTALLATION_ID, TENANT_ID, "FAILED", pageable);
        }

        @Test
        @DisplayName("KRYTERIUM 404: getInstallation rzuca ResourceNotFoundException -> propaguje się, repo.findByInstallation NIE wołane")
        void ownershipFails_propagatesAndSkipsRepository() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenThrow(new ResourceNotFoundException("Instalacja nie istnieje: " + INSTALLATION_ID));

            Pageable pageable = PageRequest.of(0, 20);

            assertThatThrownBy(() -> service.findByInstallation(TENANT_ID, INSTALLATION_ID, null, pageable))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(repository, never()).findByInstallation(any(), any(), any(), any());
        }

        @Test
        @DisplayName("instalacja innego tenanta -> identyczny ResourceNotFoundException jak nieistniejąca (RLS, decyzja 404 dla obu)")
        void crossTenantInstallation_alsoResourceNotFound() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenThrow(new ResourceNotFoundException("Instalacja nie istnieje: " + INSTALLATION_ID));

            Pageable pageable = PageRequest.of(0, 20);

            assertThatThrownBy(() -> service.findByInstallation(TENANT_ID, INSTALLATION_ID, null, pageable))
                    .isInstanceOf(ResourceNotFoundException.class);

            verifyNoInteractions(repository);
        }
    }
}
