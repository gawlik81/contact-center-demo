package com.contactcenter.domain.service;

import com.contactcenter.api.supervisor.ai.dto.TenantAiConfigRequest;
import com.contactcenter.api.supervisor.ai.dto.TenantAiConfigResponse;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.AiProvider;
import com.contactcenter.domain.model.TenantAiConfig;
import com.contactcenter.domain.repository.TenantAiConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantAiConfigService {

    private final TenantAiConfigRepository configRepository;

    // =========================================================================
    // Zapis (upsert)
    // =========================================================================

    @Transactional
    public TenantAiConfigResponse saveConfig(UUID tenantId, TenantAiConfigRequest request) {
        // Walidacja: AZURE_OPENAI wymaga azureEndpoint
        if (request.provider() == AiProvider.AZURE_OPENAI
                && !StringUtils.hasText(request.azureEndpoint())) {
            throw new IllegalArgumentException(
                    "azureEndpoint jest wymagany dla dostawcy AZURE_OPENAI");
        }

        // Upsert: znajdź lub utwórz
        TenantAiConfig config = configRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantAiConfig.builder().tenantId(tenantId).build());

        config.setProvider(request.provider());
        // apiKey: nadpisuj tylko gdy podano niepustą wartość
        // Puste/null oznacza "zachowaj istniejący" — frontend wysyła puste dla zamaskowanych pól
        if (StringUtils.hasText(request.apiKey())) {
            config.setApiKeyEncrypted(request.apiKey());
        }
        config.setModelName(request.modelName());
        config.setAzureEndpoint(request.azureEndpoint());
        config.setAzureDeploymentName(request.azureDeploymentName());
        config.setSummaryPromptTemplate(request.summaryPromptTemplate());
        config.setActive(true);

        TenantAiConfig saved = configRepository.save(config);
        log.info("[TenantAiConfigService] Zapisano konfigurację AI: tenant={}, provider={}",
                tenantId, request.provider());
        return TenantAiConfigResponse.from(saved);
    }

    // =========================================================================
    // Odczyt (z maskingiem)
    // =========================================================================

    @Transactional(readOnly = true)
    public Optional<TenantAiConfigResponse> getConfig(UUID tenantId) {
        return configRepository.findByTenantId(tenantId)
                .map(TenantAiConfigResponse::from);
    }

    // =========================================================================
    // Odczyt odszyfrowany (INTERNAL – tylko dla adapterów/serwisów AI, nie dla REST)
    // =========================================================================

    @Transactional(readOnly = true)
    Optional<TenantAiConfigDecrypted> getDecryptedConfig(UUID tenantId) {
        return configRepository.findByTenantId(tenantId)
                .map(TenantAiConfigDecrypted::from);
    }

    // =========================================================================
    // Usuwanie
    // =========================================================================

    @Transactional
    public void deleteConfig(UUID tenantId) {
        TenantAiConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Konfiguracja AI nie istnieje dla tenanta: " + tenantId));
        configRepository.delete(config);
        log.info("[TenantAiConfigService] Usunięto konfigurację AI: tenant={}", tenantId);
    }
}
