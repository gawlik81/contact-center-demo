package com.contactcenter.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA AttributeConverter serializujący/deserializujący {@code List<String>}
 * do/z formatu JSON (JSONB w PostgreSQL).
 *
 * <p>Używany przez encje posiadające pola JSONB będące tablicami stringów,
 * np. {@code AppUser.skills}.
 *
 * <p>Null → "[]", błąd parsowania → pusta lista (z logiem).
 */
@Slf4j
@Converter(autoApply = false)
public class JsonStringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("[JsonStringListConverter] Błąd serializacji List<String> do JSON: {}", e.getMessage());
            throw new IllegalArgumentException("Nie można serializować listy skills do JSON", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.error("[JsonStringListConverter] Błąd deserializacji JSON do List<String>: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
