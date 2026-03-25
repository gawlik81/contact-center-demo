package com.contactcenter.api.email.dto;

/**
 * DTO odpowiedzi z wyrenderowanym szablonem email.
 *
 * @param subject  wyrenderowany temat wiadomości (po podstawieniu zmiennych)
 * @param bodyHtml wyrenderowana treść HTML (po podstawieniu zmiennych)
 */
public record PreviewResponse(
        String subject,
        String bodyHtml
) {}
