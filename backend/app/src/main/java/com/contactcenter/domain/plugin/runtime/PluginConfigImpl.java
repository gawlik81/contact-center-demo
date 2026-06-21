package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.pluginsdk.PluginConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

/**
 * Implementacja {@link PluginConfig}, oparta o {@code TenantPluginInstallation.installationConfig}
 * (surowy JSON string, klucz/wartość ustawiane przez admina tenanta w UI).
 *
 * <p><strong>Uwaga o szyfrowaniu:</strong> {@code installation_config} (V075, komentarz migracji)
 * jest zaplanowane do szyfrowania AES-256-GCM w późniejszym tickecie (wzorzec
 * {@code tenant_twilio_config}/{@code tenant_ai_config}) — w tym tickecie (BE-101) pole jest
 * jeszcze plain JSON (zob. {@code TenantPluginInstallation} Javadoc), więc ta klasa odczytuje
 * je bez deszyfrowania. Gdy szyfrowanie zostanie dodane, deszyfrowanie musi nastąpić PRZED
 * przekazaniem JSON-a do konstruktora tej klasy (host, nie plugin, widzi już odszyfrowane
 * wartości — kontrakt SDK, {@code PluginConfig} Javadoc w plugin-sdk).
 */
@Slf4j
final class PluginConfigImpl implements PluginConfig {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private final Map<String, String> values;

    PluginConfigImpl(String installationConfigJson) {
        this.values = parse(installationConfigJson);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public String getOrDefault(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    private static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, STRING_MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("[PluginConfig] Nie można sparsować installationConfig jako Map<String,String>: {}",
                    e.getMessage());
            return Map.of();
        }
    }
}
