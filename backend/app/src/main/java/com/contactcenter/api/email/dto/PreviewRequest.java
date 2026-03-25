package com.contactcenter.api.email.dto;

import java.util.Map;

/**
 * DTO żądania podglądu wyrenderowanego szablonu.
 *
 * @param variables mapa zmiennych do podstawienia w szablonie (klucz: nazwa zmiennej, wartość: wartość)
 */
public record PreviewRequest(
        Map<String, Object> variables
) {
    /** Normalizacja: gdy variables jest null, zwraca pustą mapę. */
    public Map<String, Object> variables() {
        return variables != null ? variables : Map.of();
    }
}
