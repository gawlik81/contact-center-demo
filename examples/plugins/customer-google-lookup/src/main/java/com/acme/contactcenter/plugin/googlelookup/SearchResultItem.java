package com.acme.contactcenter.plugin.googlelookup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jeden wynik wyszukiwania Google, zredukowany do pól prezentowanych w panelu agenta.
 *
 * @param title   tytuł strony
 * @param link    adres URL strony
 * @param snippet krótki opis/fragment treści zwrócony przez Google
 */
record SearchResultItem(String title, String link, String snippet) {

    /**
     * Konwertuje do {@code Map<String,Object>} — kształt wymagany przez
     * {@code PreContactConnectResult#displayData}/{@code ManualActionResult#resultData}
     * (muszą być JSON-serializowalne, nie dowolny typ Javy).
     */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("link", link);
        map.put("snippet", snippet);
        return map;
    }
}
