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
     * Resolwuje predefiniowane zmienne (dane klienta i agenta) na podstawie kontekstu kontaktu i/lub klienta.
     *
     * <p>Gdy podano {@code customerId}, dane klienta są pobierane wprost (bez przechodzenia przez
     * {@code Contact}) — przydatne np. w podglądzie maila ad hoc, zanim kontakt zostanie utworzony.
     * Gdy {@code customerId} jest {@code null}, zachowanie jest identyczne jak
     * {@link #resolveForContext(UUID, UUID)} (rozwiązanie klienta przez {@code contactId}).
     *
     * @param contactId  UUID kontaktu (nullable)
     * @param customerId UUID klienta (nullable); ma priorytet nad {@code contactId} przy rozwiązywaniu danych klienta
     * @param agentId    UUID agenta (nullable)
     * @return mapa zmiennych predefiniowanych (puste wartości gdy brak danych)
     */
    Map<String, Object> resolveForContext(UUID contactId, UUID customerId, UUID agentId);

    /**
     * Łączy zmienne predefiniowane z niestandardowymi – niestandardowe mają priorytet.
     *
     * @param predefined mapa zmiennych predefiniowanych
     * @param custom     mapa zmiennych niestandardowych (nullable)
     * @return połączona mapa zmiennych
     */
    Map<String, Object> mergeWithCustom(Map<String, Object> predefined, Map<String, Object> custom);
}
