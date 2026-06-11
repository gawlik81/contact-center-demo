package com.contactcenter.domain.ivr;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Pozycja węzła na canvasie edytora IVR (metadane UI, nieużywane przez silnik IVR).
 *
 * @param x współrzędna pozioma w pikselach
 * @param y współrzędna pionowa w pikselach
 */
public record IvrNodePosition(
        @JsonProperty("x") double x,
        @JsonProperty("y") double y
) {
}
