package com.contactcenter.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * JPA AttributeConverter serializujący/deserializujący {@code Map<String, Object>}
 * do/z formatu JSON (JSONB w PostgreSQL).
 *
 * <p>Używany przez encje posiadające pola JSONB, np. {@code Tenant.config}.
 * Konwerter jest autodetektowany przez JPA (anotacja {@code @Converter(autoApply = false)})
 * – rejestracja jawna przez {@code @Convert(converter = JsonMapConverter.class)}.
 */
@Slf4j
@Converter(autoApply = false)
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("[JsonMapConverter] Błąd serializacji Map do JSON: {}", e.getMessage());
            throw new IllegalArgumentException("Nie można serializować konfiguracji do JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new HashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.error("[JsonMapConverter] Błąd deserializacji JSON do Map: {}", e.getMessage());
            throw new IllegalArgumentException("Nie można deserializować konfiguracji z JSON", e);
        }
    }
}
