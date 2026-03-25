package com.contactcenter.domain.ivr;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * Definicja drzewa IVR przechowywana jako JSONB w kolumnie {@code definition} tabeli {@code ivr_tree}.
 *
 * <p>Mapuje strukturę JSON: lista węzłów + identyfikator węzła wejściowego.
 *
 * @param nodes       lista węzłów drzewa IVR
 * @param entryNodeId UUID węzła startowego (musi istnieć w liście nodes)
 */
public record IvrDefinition(
        @JsonProperty("nodes") List<IvrNode> nodes,
        @JsonProperty("entry_node_id") String entryNodeId
) {
    /**
     * Wyszukuje węzeł po identyfikatorze.
     *
     * @param nodeId UUID węzła (jako string)
     * @return Optional z węzłem lub empty gdy nie znaleziono
     */
    public Optional<IvrNode> findNode(String nodeId) {
        if (nodes == null || nodeId == null) return Optional.empty();
        return nodes.stream()
                .filter(n -> nodeId.equals(n.nodeId()))
                .findFirst();
    }

    /**
     * Zwraca węzeł wejściowy drzewa IVR.
     *
     * @return Optional z węzłem wejściowym lub empty gdy brak
     */
    public Optional<IvrNode> entryNode() {
        return findNode(entryNodeId);
    }
}
