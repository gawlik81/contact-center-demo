package com.contactcenter.api.ivr.dto;

import com.contactcenter.domain.ivr.IvrDefinition;
import jakarta.validation.constraints.Size;

/**
 * Żądanie aktualizacji drzewa IVR (PATCH semantics – pola null są ignorowane).
 *
 * @param name       nowa nazwa drzewa IVR (opcjonalne)
 * @param definition nowa definicja grafu węzłów (opcjonalne)
 */
public record UpdateIvrRequest(
        @Size(max = 255, message = "Nazwa nie może przekraczać 255 znaków")
        String name,

        IvrDefinition definition
) {
}
