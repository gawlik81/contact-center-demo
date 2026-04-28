package com.contactcenter.api.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * DTO dla preferencji użytkownika (BE-054).
 *
 * <p>Na start obsługuje wyłącznie {@code preferredLanguage} – kod języka ISO 639-1.
 * Dozwolone kody: "pl", "en", "de".
 */
public record UserPreferencesDto(

        @NotNull(message = "preferredLanguage is required")
        @Pattern(regexp = "^(pl|en|de)$", message = "Unsupported language code")
        String preferredLanguage

) {}
