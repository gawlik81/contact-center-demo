package com.contactcenter.domain.ivr;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Opcja wyboru w węźle IVR.
 *
 * <p>Mapuje klawisz DTMF (lub specjalne wartości "timeout"/"no-input")
 * na identyfikator następnego węzła.
 *
 * @param key        klawisz DTMF ("0"–"9", "*", "#") lub specjalna wartość ("timeout", "no-input")
 * @param nextNodeId UUID węzła do przejścia po wybraniu klawisza
 */
public record IvrOption(
        @JsonProperty("key") String key,
        @JsonProperty("next_node_id") String nextNodeId
) {
}
