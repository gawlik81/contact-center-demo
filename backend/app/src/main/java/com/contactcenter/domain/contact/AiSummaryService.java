package com.contactcenter.domain.contact;

import com.contactcenter.domain.exception.AiConfigNotFoundException;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Serwis generowania podsumowań AI dla kontaktów (BE-089).
 *
 * <p>Odpowiada za:
 * <ol>
 *   <li>Pobranie kontaktu i walidację jego istnienia.</li>
 *   <li>Pobranie odszyfrowanej konfiguracji AI dla tenanta.</li>
 *   <li>Wyodrębnienie treści zależnie od kanału kontaktu.</li>
 *   <li>Wywołanie zewnętrznego serwisu Python (AI summarize).</li>
 *   <li>Zapis wyniku (summary, modelUsed, timestamp) do tabeli {@code contact_ai_summary}
 *       przez {@code ContactService#saveAiSummary}.</li>
 * </ol>
 */
public interface AiSummaryService {

    /**
     * Wynik generowania podsumowania AI.
     *
     * @param summary    treść podsumowania
     * @param modelUsed  nazwa modelu AI, który wygenerował podsumowanie
     * @param tokensUsed łączna liczba tokenów użytych w żądaniu i odpowiedzi
     */
    record AiSummaryResult(String summary, String modelUsed, int tokensUsed) {}

    /**
     * Generuje podsumowanie AI dla wskazanego kontaktu i zapisuje je w bazie.
     *
     * <p>Metoda jest transakcyjna – jeśli zapis do bazy się nie powiedzie,
     * wygenerowane podsumowanie zostanie odrzucone (brak side effects).
     *
     * @param contactId UUID kontaktu do podsumowania
     * @param tenantId  UUID tenanta
     * @return wynik z podsumowaniem, nazwą modelu i liczbą tokenów
     * @throws ResourceNotFoundException    gdy kontakt nie istnieje
     * @throws AiConfigNotFoundException    gdy tenant nie ma konfiguracji AI
     * @throws com.contactcenter.domain.exception.AiSummaryGenerationException
     *         gdy serwis AI zwrócił błąd lub jest niedostępny
     */
    AiSummaryResult generateSummary(UUID contactId, UUID tenantId);

    /**
     * Wyodrębnia treść kontaktu do podsumowania zależnie od kanału.
     *
     * <p>Dla kanału PHONE czyta transkrypcję z tabeli {@code contact_transcription}.
     * Dla kanałów EMAIL i SOCIAL* – szuka treści w {@code channelMetadata}
     * pod kluczami {@code "body"} i {@code "content"}.
     *
     * @param contact  encja kontaktu
     * @param tenantId UUID tenanta (potrzebny do zapytania o transkrypcję)
     * @return treść do podsumowania lub komunikat zastępczy gdy brak treści
     */
    String extractContent(Contact contact, UUID tenantId);

    /**
     * Zachowana dla kompatybilności wstecznej z testami wywołującymi bezparametrową wersję.
     * Dla kanału PHONE zwraca zawsze komunikat zastępczy (brak tenantId → nie odpytuje DB).
     *
     * @deprecated Używaj {@link #extractContent(Contact, UUID)}.
     */
    @Deprecated
    String extractContent(Contact contact);
}
