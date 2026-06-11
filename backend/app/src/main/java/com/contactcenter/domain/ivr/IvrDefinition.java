package com.contactcenter.domain.ivr;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Definicja drzewa IVR przechowywana jako JSONB w kolumnie {@code definition} tabeli {@code ivr_tree}.
 *
 * <p>Mapuje strukturę JSON: lista węzłów + identyfikator węzła wejściowego + layout (pozycje węzłów na canvasie).
 *
 * @param nodes       lista węzłów drzewa IVR
 * @param entryNodeId UUID węzła startowego (musi istnieć w liście nodes)
 * @param layout      mapa pozycji węzłów na canvasie edytora (node_id -> pozycja); metadane UI,
 *                    nieużywane przez silnik IVR
 */
public record IvrDefinition(
        @JsonProperty("nodes") List<IvrNode> nodes,
        @JsonProperty("entry_node_id") String entryNodeId,
        @JsonProperty("layout") Map<String, IvrNodePosition> layout
) {
    public IvrDefinition {
        if (layout == null) layout = Map.of();
    }

    /**
     * Konstruktor pomocniczy bez layoutu (np. dla testów silnika IVR, które nie operują na pozycjach UI).
     *
     * @param nodes       lista węzłów drzewa IVR
     * @param entryNodeId UUID węzła startowego (musi istnieć w liście nodes)
     */
    public IvrDefinition(List<IvrNode> nodes, String entryNodeId) {
        this(nodes, entryNodeId, Map.of());
    }

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
