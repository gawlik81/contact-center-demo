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
 * (klucz/wartość ustawiane przez admina tenanta w UI, {@code PluginRegistrationService#updateConfig},
 * BE-108).
 *
 * <p><strong>Uwaga o szyfrowaniu:</strong> w bazie {@code installation_config} (V075) jest
 * zaszyfrowane AES-256-GCM, ale deszyfrowanie następuje WCZEŚNIEJ — w
 * {@code TenantPluginInstallationRepository} przy odczycie wiersza. Ta klasa zawsze otrzymuje
 * (przez konstruktor) już odszyfrowany plaintext JSON — host, nie plugin, widzi odszyfrowane
 * wartości (kontrakt SDK, {@code PluginConfig} Javadoc w plugin-sdk); ta klasa sama nie wie
 * nic o szyfrowaniu.
 */
@Slf4j
final class PluginConfigImpl implements PluginConfig {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private final Map<String, String> values;

    private final PluginRevocation revocation;

    PluginConfigImpl(String installationConfigJson) {
        this(installationConfigJson, new PluginRevocation());
    }

    /** @param revocation set when the instance is unloaded/revoked/timed out: the (decrypted) values are then unreadable (BE-244 W2) */
    PluginConfigImpl(String installationConfigJson, PluginRevocation revocation) {
        this.values = parse(installationConfigJson);
        this.revocation = revocation;
    }

    @Override
    public Optional<String> get(String key) {
        revocation.requireActive();
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public String getOrDefault(String key, String defaultValue) {
        revocation.requireActive();
        return values.getOrDefault(key, defaultValue);
    }

    /** Platform-internal read (the context constructor wiring the DB client); never reachable from plugin code. */
    Optional<String> peek(String key) {
        return Optional.ofNullable(values.get(key));
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
