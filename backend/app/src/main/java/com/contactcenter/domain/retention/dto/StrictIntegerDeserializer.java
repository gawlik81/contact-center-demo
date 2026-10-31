package com.contactcenter.domain.retention.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * Akceptuje WYŁĄCZNIE literał całkowity JSON ({@code 30}). Domyślny Jackson (ACCEPT_FLOAT_AS_INT,
 * koercja tekstu) przepuściłby {@code 30.7} jako 30 i {@code "90"} jako 90 — dla ustawienia, którego
 * skutkiem jest masowa archiwizacja, ciche obcinanie/zgadywanie wartości jest niedopuszczalne.
 * Odrzucenie kończy się {@code HttpMessageNotReadableException} (mapowane lokalnie na HTTP 422).
 * {@code null} nie trafia tutaj (Jackson używa wartości null; wyłapie go {@code @NotNull}).
 */
public class StrictIntegerDeserializer extends StdDeserializer<Integer> {

    public StrictIntegerDeserializer() {
        super(Integer.class);
    }

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return p.getIntValue(); // przepełnienie int -> InputCoercionException (też 422)
        }
        return (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
    }
}
