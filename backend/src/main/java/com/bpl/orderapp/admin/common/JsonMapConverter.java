package com.bpl.orderapp.admin.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;

/**
 * Stores a {@code Map<String,Object>} as JSON text in a TEXT column (guarded
 * by {@code CHECK (json_valid(...))} in the schema). Used instead of
 * Hibernate's SqlTypes.JSON so we do not depend on the SQLite dialect's JSON
 * support.
 */
@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize audit detail to JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored audit detail is not valid JSON", e);
        }
    }
}
