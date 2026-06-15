package com.contactcenter.api.contact.dto;

/**
 * Odpowiedź endpointu generowania podsumowania AI (BE-090).
 *
 * @param summary    wygenerowane podsumowanie kontaktu
 * @param modelUsed  nazwa modelu AI użytego do generowania
 * @param tokensUsed łączna liczba tokenów zużytych w żądaniu i odpowiedzi
 */
public record AiSummaryResponse(
        String summary,
        String modelUsed,
        int tokensUsed
) {}
