package com.contactcenter.api.email.dto;

import java.util.Map;

import java.util.UUID;

/**
 * DTO żądania podglądu wyrenderowanego szablonu.
 *
 * @param variables mapa zmiennych do podstawienia w szablonie
 * @param contactId opcjonalne UUID kontaktu — gdy podane, predefiniowane zmienne są rozwiązywane z prawdziwych danych
 */
public record PreviewRequest(
        Map<String, Object> variables,
        UUID contactId
) {
    public Map<String, Object> variables() {
        return variables != null ? variables : Map.of();
    }
}
