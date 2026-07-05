package com.contactcenter.domain.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Walidacja {@code META-INF/plugin-manifest.json} względem JSON Schema
 * (ARCHITECTURE.md §11.4 krok 3) oraz parsowanie do {@link PluginManifest}.
 *
 * <p>Schema jest wczytywana jednorazowo z {@code classpath:plugin/plugin-manifest.schema.json}
 * — {@link JsonSchema} z biblioteki networknt jest bezstanowy/thread-safe dla wielokrotnego
 * użycia {@code validate()}, więc instancja jest tworzona raz (pole statyczne), nie per wywołanie.
 */
@Slf4j
final class PluginManifestValidator {

    private static final String SCHEMA_CLASSPATH_LOCATION = "plugin/plugin-manifest.schema.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonSchema SCHEMA = loadSchema();

    private PluginManifestValidator() {
    }

    /**
     * Wynik parsowania/walidacji manifestu — albo {@link PluginManifest} sparsowany,
     * albo lista błędów schema (manifest jest wtedy {@code null}).
     */
    record ManifestValidationResult(PluginManifest manifest, List<String> schemaErrors) {

        boolean isValid() {
            return manifest != null && schemaErrors.isEmpty();
        }
    }

    /**
     * Parsuje i waliduje surowy JSON manifestu.
     *
     * @param manifestJsonBytes surowe bajty pliku {@code META-INF/plugin-manifest.json}
     * @return wynik walidacji schema + sparsowany manifest (jeśli poprawny)
     */
    static ManifestValidationResult validate(byte[] manifestJsonBytes) {
        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(manifestJsonBytes);
        } catch (IOException e) {
            return new ManifestValidationResult(null,
                    List.of("Manifest nie jest poprawnym JSON-em: " + e.getMessage()));
        }

        Set<ValidationMessage> schemaViolations = SCHEMA.validate(node);
        if (!schemaViolations.isEmpty()) {
            List<String> errors = new ArrayList<>();
            for (ValidationMessage violation : schemaViolations) {
                errors.add(violation.getMessage());
            }
            return new ManifestValidationResult(null, errors);
        }

        PluginManifest manifest;
        try {
            manifest = OBJECT_MAPPER.treeToValue(node, PluginManifest.class);
        } catch (IOException e) {
            return new ManifestValidationResult(null,
                    List.of("Manifest zgodny ze schema, ale nie można go zmapować na PluginManifest: "
                            + e.getMessage()));
        }
        return new ManifestValidationResult(manifest, List.of());
    }

    private static JsonSchema loadSchema() {
        try (InputStream schemaStream = PluginManifestValidator.class.getClassLoader()
                .getResourceAsStream(SCHEMA_CLASSPATH_LOCATION)) {
            if (schemaStream == null) {
                throw new IllegalStateException(
                        "Nie znaleziono schema na classpath: " + SCHEMA_CLASSPATH_LOCATION);
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaStream);
        } catch (IOException e) {
            throw new IllegalStateException("Nie można wczytać plugin-manifest.schema.json", e);
        }
    }
}
