package com.contactcenter.api.email.dto;

import com.contactcenter.domain.model.EmailTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO odpowiedzi z danymi szablonu email.
 *
 * <p>Nie zawiera pola {@code isDeleted} – usunięte szablony nie są zwracane przez API.
 *
 * @param id              UUID szablonu
 * @param tenantId        UUID tenanta
 * @param name            nazwa szablonu
 * @param subjectTemplate temat wiadomości (z placeholderami Mustache)
 * @param bodyHtml        treść HTML (z placeholderami Mustache)
 * @param variables       lista wymaganych nazw zmiennych
 * @param createdAt       czas utworzenia
 * @param updatedAt       czas ostatniej aktualizacji (null dla nowych rekordów)
 */
public record EmailTemplateResponse(
        UUID id,
        UUID tenantId,
        String name,
        String subjectTemplate,
        String bodyHtml,
        List<String> variables,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Mapowanie encji JPA na DTO odpowiedzi.
     *
     * @param template encja {@link EmailTemplate}
     * @return DTO z danymi szablonu
     */
    public static EmailTemplateResponse from(EmailTemplate template) {
        return new EmailTemplateResponse(
                template.getId(),
                template.getTenantId(),
                template.getName(),
                template.getSubjectTemplate(),
                template.getBodyHtml(),
                template.getVariables(),
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }
}
