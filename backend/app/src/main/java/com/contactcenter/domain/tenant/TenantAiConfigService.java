package com.contactcenter.domain.tenant;

import com.contactcenter.api.supervisor.ai.dto.TenantAiConfigRequest;
import com.contactcenter.api.supervisor.ai.dto.TenantAiConfigResponse;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający per-tenant konfiguracją dostawcy AI (do generowania podsumowań).
 */
public interface TenantAiConfigService {

    /**
     * Zapisuje (upsert) konfigurację AI dla tenanta.
     *
     * <p>apiKey jest nadpisywany tylko gdy podano niepustą wartość – puste/null
     * oznacza "zachowaj istniejący" (frontend wysyła puste dla zamaskowanych pól).
     *
     * @param tenantId UUID tenanta
     * @param request  dane konfiguracji AI
     * @return DTO z zapisaną konfiguracją (zamaskowaną)
     * @throws IllegalArgumentException gdy provider AZURE_OPENAI bez azureEndpoint
     */
    TenantAiConfigResponse saveConfig(UUID tenantId, TenantAiConfigRequest request);

    /**
     * Zwraca zamaskowaną konfigurację AI tenanta (do prezentacji w REST API).
     *
     * @param tenantId UUID tenanta
     * @return konfiguracja (zamaskowana) lub {@link Optional#empty()} gdy nie istnieje
     */
    Optional<TenantAiConfigResponse> getConfig(UUID tenantId);

    /**
     * Zwraca odszyfrowaną konfigurację AI tenanta.
     *
     * <p>INTERNAL – wyłącznie dla serwisów generujących podsumowania AI, nigdy nie zwracać przez REST.
     *
     * @param tenantId UUID tenanta
     * @return odszyfrowana konfiguracja lub {@link Optional#empty()} gdy nie istnieje
     */
    Optional<TenantAiConfigDecrypted> getDecryptedConfig(UUID tenantId);

    /**
     * Usuwa konfigurację AI tenanta.
     *
     * @param tenantId UUID tenanta
     * @throws ResourceNotFoundException gdy konfiguracja nie istnieje
     */
    void deleteConfig(UUID tenantId);
}
