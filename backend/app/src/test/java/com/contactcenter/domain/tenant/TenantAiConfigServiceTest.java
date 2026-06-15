package com.contactcenter.domain.tenant;

import com.contactcenter.api.supervisor.ai.dto.TenantAiConfigRequest;
import com.contactcenter.api.supervisor.ai.dto.TenantAiConfigResponse;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("TenantAiConfigService – logika biznesowa")
@ExtendWith(MockitoExtension.class)
class TenantAiConfigServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String VALID_API_KEY = "sk-test-1234567890abcdefghijklmnop-secret";
    private static final String VALID_MODEL = "gpt-4o";

    @Mock
    private TenantAiConfigRepository configRepository;

    @InjectMocks
    private TenantAiConfigServiceImpl service;

    // =========================================================================
    // saveConfig()
    // =========================================================================

    @Nested
    @DisplayName("saveConfig() – upsert z walidacją")
    class SaveConfigTests {

        @Test
        @DisplayName("tworzy nowy rekord gdy nie istnieje (INSERT path)")
        void saveConfig_noExisting_createsNew() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantAiConfigRequest request = new TenantAiConfigRequest(
                    AiProvider.OPENAI, VALID_API_KEY, VALID_MODEL, null, null, null);

            TenantAiConfigResponse response = service.saveConfig(TENANT_ID, request);

            assertThat(response.provider()).isEqualTo(AiProvider.OPENAI);
            assertThat(response.modelName()).isEqualTo(VALID_MODEL);
            assertThat(response.isActive()).isTrue();
            verify(configRepository).save(any(TenantAiConfig.class));
        }

        @Test
        @DisplayName("aktualizuje istniejący rekord (upsert)")
        void saveConfig_existing_updates() {
            TenantAiConfig existing = TenantAiConfig.builder()
                    .id(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .provider(AiProvider.ANTHROPIC)
                    .apiKeyEncrypted("old-api-key")
                    .modelName("claude-2")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(existing));
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantAiConfigRequest request = new TenantAiConfigRequest(
                    AiProvider.OPENAI, VALID_API_KEY, VALID_MODEL, null, null, null);

            service.saveConfig(TENANT_ID, request);

            assertThat(existing.getProvider()).isEqualTo(AiProvider.OPENAI);
            assertThat(existing.getModelName()).isEqualTo(VALID_MODEL);
            assertThat(existing.getApiKeyEncrypted()).isEqualTo(VALID_API_KEY);
        }

        @Test
        @DisplayName("dla AZURE_OPENAI bez azureEndpoint rzuca IllegalArgumentException")
        void saveConfig_azureOpenAi_withoutEndpoint_throwsIllegalArgumentException() {
            TenantAiConfigRequest request = new TenantAiConfigRequest(
                    AiProvider.AZURE_OPENAI, VALID_API_KEY, VALID_MODEL, null, null, null);

            assertThatThrownBy(() -> service.saveConfig(TENANT_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("azureEndpoint");
        }

        @Test
        @DisplayName("dla AZURE_OPENAI z azureEndpoint zapisuje poprawnie")
        void saveConfig_azureOpenAi_withEndpoint_succeeds() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantAiConfigRequest request = new TenantAiConfigRequest(
                    AiProvider.AZURE_OPENAI, VALID_API_KEY, VALID_MODEL,
                    "https://my-instance.openai.azure.com", "gpt-4o-deploy", null);

            TenantAiConfigResponse response = service.saveConfig(TENANT_ID, request);

            assertThat(response.provider()).isEqualTo(AiProvider.AZURE_OPENAI);
            assertThat(response.azureEndpoint()).isEqualTo("https://my-instance.openai.azure.com");
        }

        @Test
        @DisplayName("apiKey null → nie nadpisuje istniejącego klucza")
        void saveConfig_nullApiKey_doesNotOverwriteExistingKey() {
            TenantAiConfig existing = TenantAiConfig.builder()
                    .id(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .provider(AiProvider.OPENAI)
                    .apiKeyEncrypted("existing-key-abcd")
                    .modelName("gpt-3.5-turbo")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(existing));
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantAiConfigRequest request = new TenantAiConfigRequest(
                    AiProvider.OPENAI, null, VALID_MODEL, null, null, null);

            service.saveConfig(TENANT_ID, request);

            assertThat(existing.getApiKeyEncrypted()).isEqualTo("existing-key-abcd");
        }

        @Test
        @DisplayName("apiKey blank → nie nadpisuje istniejącego klucza")
        void saveConfig_blankApiKey_doesNotOverwriteExistingKey() {
            TenantAiConfig existing = TenantAiConfig.builder()
                    .id(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .provider(AiProvider.OPENAI)
                    .apiKeyEncrypted("existing-key-abcd")
                    .modelName("gpt-3.5-turbo")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(existing));
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantAiConfigRequest request = new TenantAiConfigRequest(
                    AiProvider.OPENAI, "   ", VALID_MODEL, null, null, null);

            service.saveConfig(TENANT_ID, request);

            assertThat(existing.getApiKeyEncrypted()).isEqualTo("existing-key-abcd");
        }

        @Test
        @DisplayName("apiKey niepusty → nadpisuje istniejący klucz")
        void saveConfig_nonEmptyApiKey_overwritesExistingKey() {
            TenantAiConfig existing = TenantAiConfig.builder()
                    .id(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .provider(AiProvider.OPENAI)
                    .apiKeyEncrypted("old-key-zzzz")
                    .modelName("gpt-3.5-turbo")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(existing));
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantAiConfigRequest request = new TenantAiConfigRequest(
                    AiProvider.OPENAI, "new-key-1234", VALID_MODEL, null, null, null);

            service.saveConfig(TENANT_ID, request);

            assertThat(existing.getApiKeyEncrypted()).isEqualTo("new-key-1234");
        }
    }

    // =========================================================================
    // getConfig() – masking
    // =========================================================================

    @Nested
    @DisplayName("getConfig() – masking wrażliwych pól")
    class GetConfigTests {

        @Test
        @DisplayName("zwraca Optional.empty() gdy brak konfiguracji")
        void getConfig_noConfig_returnsEmpty() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThat(service.getConfig(TENANT_ID)).isEmpty();
        }

        @Test
        @DisplayName("apiKeyMasked zawiera ● i ostatnie 4 znaki (nie plaintext)")
        void getConfig_masksApiKey() {
            TenantAiConfig config = TenantAiConfig.builder()
                    .id(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .provider(AiProvider.OPENAI)
                    .apiKeyEncrypted("sk-super-secret-token-a3f2")
                    .modelName(VALID_MODEL)
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(config));

            TenantAiConfigResponse response = service.getConfig(TENANT_ID).orElseThrow();

            assertThat(response.apiKeyMasked()).doesNotContain("sk-super-secret-token");
            assertThat(response.apiKeyMasked()).endsWith("a3f2");
            assertThat(response.apiKeyMasked()).contains("●");
        }

        @Test
        @DisplayName("krótki klucz (≤4 znaki) → zwraca ●●●●")
        void getConfig_shortApiKey_returnsFullMask() {
            TenantAiConfig config = TenantAiConfig.builder()
                    .id(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .provider(AiProvider.OPENAI)
                    .apiKeyEncrypted("ab12")
                    .modelName(VALID_MODEL)
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(config));

            TenantAiConfigResponse response = service.getConfig(TENANT_ID).orElseThrow();

            assertThat(response.apiKeyMasked()).isEqualTo("●●●●");
        }

        @Test
        @DisplayName("zwraca pola konfiguracyjne bez maskowania")
        void getConfig_returnsNonSecretFieldsUnmasked() {
            TenantAiConfig config = TenantAiConfig.builder()
                    .id(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .provider(AiProvider.AZURE_OPENAI)
                    .apiKeyEncrypted(VALID_API_KEY)
                    .modelName(VALID_MODEL)
                    .azureEndpoint("https://my-instance.openai.azure.com")
                    .azureDeploymentName("gpt-4o-deploy")
                    .summaryPromptTemplate("Podsumuj rozmowę.")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(config));

            TenantAiConfigResponse response = service.getConfig(TENANT_ID).orElseThrow();

            assertThat(response.provider()).isEqualTo(AiProvider.AZURE_OPENAI);
            assertThat(response.modelName()).isEqualTo(VALID_MODEL);
            assertThat(response.azureEndpoint()).isEqualTo("https://my-instance.openai.azure.com");
            assertThat(response.azureDeploymentName()).isEqualTo("gpt-4o-deploy");
            assertThat(response.summaryPromptTemplate()).isEqualTo("Podsumuj rozmowę.");
        }
    }

    // =========================================================================
    // deleteConfig()
    // =========================================================================

    @Nested
    @DisplayName("deleteConfig() – usuwanie konfiguracji")
    class DeleteConfigTests {

        @Test
        @DisplayName("usuwa rekord gdy istnieje")
        void deleteConfig_existingConfig_deletes() {
            TenantAiConfig config = TenantAiConfig.builder()
                    .id(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .provider(AiProvider.OPENAI)
                    .apiKeyEncrypted(VALID_API_KEY)
                    .modelName(VALID_MODEL)
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(config));

            service.deleteConfig(TENANT_ID);

            verify(configRepository).delete(config);
        }

        @Test
        @DisplayName("rzuca ResourceNotFoundException gdy brak konfiguracji")
        void deleteConfig_noConfig_throwsResourceNotFoundException() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteConfig(TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Konfiguracja AI nie istnieje");
        }
    }
}
