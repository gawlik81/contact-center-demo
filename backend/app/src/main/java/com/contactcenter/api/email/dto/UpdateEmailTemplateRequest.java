package com.contactcenter.api.email.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO żądania częściowej aktualizacji szablonu email (partial update – PATCH).
 *
 * <p>Wszystkie pola są opcjonalne. Null oznacza brak zmiany wartości.
 *
 * @param name            nowa nazwa szablonu (null = bez zmiany)
 * @param subjectTemplate nowy temat szablonu (null = bez zmiany)
 * @param bodyHtml        nowa treść HTML (null = bez zmiany)
 * @param variables       nowa lista zmiennych (null = bez zmiany)
 */
public record UpdateEmailTemplateRequest(

        @Size(max = 255, message = "Nazwa szablonu może mieć maksymalnie 255 znaków")
        String name,

        @Size(max = 500, message = "Temat szablonu może mieć maksymalnie 500 znaków")
        String subjectTemplate,

        String bodyHtml,

        List<String> variables
) {}
