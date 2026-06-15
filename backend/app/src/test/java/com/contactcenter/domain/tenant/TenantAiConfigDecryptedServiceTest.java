package com.contactcenter.domain.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Testy package-private metody {@link TenantAiConfigService#getDecryptedConfig(UUID)}.
 * Muszą być w tym samym pakiecie co serwis.
 */
@DisplayName("TenantAiConfigService – getDecryptedConfig() (package-private)")
@ExtendWith(MockitoExtension.class)
class TenantAiConfigDecryptedServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Mock
    private TenantAiConfigRepository configRepository;

    @InjectMocks
    private TenantAiConfigServiceImpl service;

    @Nested
    @DisplayName("getDecryptedConfig() – plaintext dla serwisów wewnętrznych")
    class GetDecryptedConfigTests {

        @Test
        @DisplayName("zwraca plaintext apiKey (nie zamaskowany)")
        void getDecryptedConfig_returnsPlaintextApiKey() {
            TenantAiConfig config = TenantAiConfig.builder()
                    .id(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .provider(AiProvider.ANTHROPIC)
                    .apiKeyEncrypted("plaintext-secret-key")
                    .modelName("claude-3-opus")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(config));

            TenantAiConfigDecrypted decrypted = service.getDecryptedConfig(TENANT_ID).orElseThrow();

            assertThat(decrypted.apiKey()).isEqualTo("plaintext-secret-key");
            assertThat(decrypted.provider()).isEqualTo(AiProvider.ANTHROPIC);
            assertThat(decrypted.modelName()).isEqualTo("claude-3-opus");
        }

        @Test
        @DisplayName("zwraca Optional.empty() gdy brak konfiguracji")
        void getDecryptedConfig_noConfig_returnsEmpty() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThat(service.getDecryptedConfig(TENANT_ID)).isEmpty();
        }
    }
}
