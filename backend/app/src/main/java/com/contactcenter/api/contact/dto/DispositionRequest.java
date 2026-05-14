package com.contactcenter.api.contact.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Żądanie ustawienia kodu dyspozycji kontaktu.
 *
 * <p>Używane przez {@code PATCH /api/contacts/{id}/disposition}.
 * Agent ustawia wynik kontaktu po jego zakończeniu.
 *
 * @param dispositionCode kod wyniku (np. SALE, DECLINED, NO_ANSWER, CALLBACK) – wymagany, max 50 znaków
 * @param notes           opcjonalna notatka agenta – nullable, max 5000 znaków
 */
public record DispositionRequest(
        @NotBlank(message = "dispositionCode jest wymagany")
        @Size(max = 50, message = "dispositionCode nie może przekraczać 50 znaków")
        String dispositionCode,

        @Size(max = 5000, message = "notes nie może przekraczać 5000 znaków")
        String notes
) {
}
