package com.contactcenter.domain.email;

/**
 * Wynik renderowania szablonu Mustache.
 *
 * @param subject  wyrenderowany temat wiadomości
 * @param bodyHtml wyrenderowana treść HTML
 */
public record RenderedEmailTemplate(String subject, String bodyHtml) {}
