package com.contactcenter.api.supervisor.ai.dto;

import com.contactcenter.domain.model.AiProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TenantAiConfigRequest(
        @NotNull(message = "provider jest wymagany")
        AiProvider provider,

        String apiKey,                  // plaintext; null/blank = "nie zmieniaj istniejącego"

        @NotBlank(message = "modelName jest wymagany")
        String modelName,

        String azureEndpoint,           // nullable; wymagane tylko dla AZURE_OPENAI
        String azureDeploymentName,     // nullable; opcjonalne dla AZURE_OPENAI
        String summaryPromptTemplate    // nullable; null = użyj domyślnego
) {}
