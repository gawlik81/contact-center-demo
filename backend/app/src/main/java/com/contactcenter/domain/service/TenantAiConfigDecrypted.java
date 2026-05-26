package com.contactcenter.domain.service;

import com.contactcenter.domain.model.AiProvider;
import com.contactcenter.domain.model.TenantAiConfig;

public record TenantAiConfigDecrypted(
        AiProvider provider,
        String apiKey,                  // odszyfrowany plaintext (JPA konwerter już odszyfrował)
        String modelName,
        String azureEndpoint,
        String azureDeploymentName,
        String summaryPromptTemplate
) {
    public static TenantAiConfigDecrypted from(TenantAiConfig entity) {
        return new TenantAiConfigDecrypted(
                entity.getProvider(),
                entity.getApiKeyEncrypted(),
                entity.getModelName(),
                entity.getAzureEndpoint(),
                entity.getAzureDeploymentName(),
                entity.getSummaryPromptTemplate()
        );
    }
}
