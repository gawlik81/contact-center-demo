package com.contactcenter.api.ivr.dto;

import com.contactcenter.domain.ivr.IvrDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Żądanie tworzenia nowego drzewa IVR.
 *
 * @param name       nazwa drzewa IVR (unikalna w tenancie)
 * @param definition definicja grafu węzłów IVR
 */
public record CreateIvrRequest(
        @NotBlank(message = "Nazwa IVR jest wymagana")
        @Size(max = 255, message = "Nazwa nie może przekraczać 255 znaków")
        String name,

        @NotNull(message = "Definicja IVR jest wymagana")
        IvrDefinition definition
) {
}
