package com.contactcenter.api.email.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO żądania tworzenia szablonu email.
 *
 * @param name            nazwa szablonu – unikalna w ramach tenanta
 * @param subjectTemplate temat wiadomości (może zawierać placeholdery Mustache)
 * @param bodyHtml        treść HTML (może zawierać placeholdery Mustache)
 * @param variables       lista nazw zmiennych wymaganych przez szablon (może być pusta)
 */
public record CreateEmailTemplateRequest(

        @NotBlank(message = "Nazwa szablonu jest wymagana")
        @Size(max = 255, message = "Nazwa szablonu może mieć maksymalnie 255 znaków")
        String name,

        @NotBlank(message = "Temat szablonu jest wymagany")
        @Size(max = 500, message = "Temat szablonu może mieć maksymalnie 500 znaków")
        String subjectTemplate,

        @NotBlank(message = "Treść HTML szablonu jest wymagana")
        String bodyHtml,

        List<String> variables
) {
    /** Normalizacja: gdy variables jest null, zwraca pustą listę. */
    public List<String> variables() {
        return variables != null ? variables : List.of();
    }
}
