package com.contactcenter.domain.email;

import java.util.Map;
import java.util.UUID;

/**
 * Resolwer zmiennych predefiniowanych dla szablonów email (dane klienta i agenta).
 */
public interface TemplateVariableResolver {

    /**
     * Resolwuje predefiniowane zmienne (dane klienta i agenta) na podstawie kontekstu kontaktu.
     *
     * @param contactId UUID kontaktu (nullable)
     * @param agentId   UUID agenta (nullable)
     * @return mapa zmiennych predefiniowanych (puste wartości gdy brak danych)
     */
    Map<String, Object> resolveForContext(UUID contactId, UUID agentId);

    /**
     * Łączy zmienne predefiniowane z niestandardowymi – niestandardowe mają priorytet.
     *
     * @param predefined mapa zmiennych predefiniowanych
     * @param custom     mapa zmiennych niestandardowych (nullable)
     * @return połączona mapa zmiennych
     */
    Map<String, Object> mergeWithCustom(Map<String, Object> predefined, Map<String, Object> custom);
}
