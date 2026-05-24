package com.contactcenter.domain.service;

import com.contactcenter.domain.exception.AiConfigNotFoundException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Serwis generowania podsumowań AI dla kontaktów (BE-089).
 *
 * <p>Odpowiada za:
 * <ol>
 *   <li>Pobranie kontaktu i walidację jego istnienia.</li>
 *   <li>Pobranie odszyfrowanej konfiguracji AI dla tenanta.</li>
 *   <li>Wyodrębnienie treści zależnie od kanału kontaktu.</li>
 *   <li>Wywołanie {@link AiSummaryClient} (serwis Python).</li>
 *   <li>Zapis wyniku (summary, modelUsed, timestamp) do bazy przez {@link ContactRepository#updateAiSummary}.</li>
 * </ol>
 *
 * <p>Nie implementuje kontrolera – ten zostanie dodany w BE-090.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryService {

    private final ContactRepository contactRepository;
    private final TenantAiConfigService aiConfigService;
    private final AiSummaryClient aiSummaryClient;

    // =========================================================================
    // Record wynikowy
    // =========================================================================

    /**
     * Wynik generowania podsumowania AI.
     *
     * @param summary    treść podsumowania
     * @param modelUsed  nazwa modelu AI, który wygenerował podsumowanie
     * @param tokensUsed łączna liczba tokenów użytych w żądaniu i odpowiedzi
     */
    public record AiSummaryResult(String summary, String modelUsed, int tokensUsed) {}

    // =========================================================================
    // Logika biznesowa
    // =========================================================================

    /**
     * Generuje podsumowanie AI dla wskazanego kontaktu i zapisuje je w bazie.
     *
     * <p>Metoda jest transakcyjna – jeśli zapis do bazy się nie powiedzie,
     * wygenerowane podsumowanie zostanie odrzucone (brak side effects).
     * Wywołanie zewnętrzne ({@link AiSummaryClient}) następuje WEWNĄTRZ transakcji,
     * co jest akceptowalne bo metoda jest wywoływana jawnie przez agenta
     * (nie w pętli), a czas generowania (do 30s) jest znany klientowi.
     *
     * @param contactId UUID kontaktu do podsumowania
     * @param tenantId  UUID tenanta
     * @return wynik z podsumowaniem, nazwą modelu i liczbą tokenów
     * @throws ResourceNotFoundException    gdy kontakt nie istnieje
     * @throws AiConfigNotFoundException    gdy tenant nie ma konfiguracji AI
     * @throws com.contactcenter.domain.exception.AiSummaryGenerationException
     *         gdy serwis AI zwrócił błąd lub jest niedostępny
     */
    @Transactional
    public AiSummaryResult generateSummary(UUID contactId, UUID tenantId) {
        // 1. Pobierz kontakt
        Contact contact = contactRepository.findById(contactId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Kontakt nie istnieje: " + contactId));

        // 2. Pobierz odszyfrowaną konfigurację AI
        TenantAiConfigDecrypted aiConfig = aiConfigService.getDecryptedConfig(tenantId)
                .orElseThrow(() -> new AiConfigNotFoundException(
                        "Brak konfiguracji AI dla tenanta. Skonfiguruj dostawcę AI w ustawieniach."));

        // 3. Wyodrębnij treść zależnie od kanału
        String content = extractContent(contact);

        // 4. Wywołaj Python AI service
        log.info("[AiSummaryService] Generowanie podsumowania: contactId={}, tenant={}, provider={}, channel={}",
                contactId, tenantId, aiConfig.provider(), contact.getChannel());

        AiSummaryClient.AiSummarizeRequest request = new AiSummaryClient.AiSummarizeRequest(
                contact.getChannel(),
                content,
                aiConfig.provider().name(),
                aiConfig.apiKey(),              // plaintext – tylko przez sieć wewnętrzną
                aiConfig.modelName(),
                aiConfig.azureEndpoint(),
                aiConfig.azureDeploymentName(),
                aiConfig.summaryPromptTemplate()
        );

        AiSummaryClient.AiSummarizeResponse response = aiSummaryClient.summarize(request);

        // 5. Zapisz wynik w bazie
        Instant now = Instant.now();
        contactRepository.updateAiSummary(
                contactId, tenantId, response.summary(), response.modelUsed(), now);

        log.info("[AiSummaryService] Podsumowanie zapisane: contactId={}, model={}, tokens={}",
                contactId, response.modelUsed(), response.tokensUsed());

        return new AiSummaryResult(response.summary(), response.modelUsed(), response.tokensUsed());
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Wyodrębnia treść kontaktu do podsumowania zależnie od kanału.
     *
     * <p>Dla kanału PHONE próbuje odczytać pole {@code notes} (transkrypcja lub notatki agenta).
     * Dla kanałów EMAIL i SOCIAL* – szuka treści w {@code channelMetadata} pod kluczami
     * {@code "body"} i {@code "content"} (standard adaptera email i social media).
     *
     * @param contact encja kontaktu
     * @return treść do podsumowania lub komunikat zastępczy gdy brak treści
     */
    public String extractContent(Contact contact) {
        String channel = contact.getChannel();

        if ("PHONE".equals(channel)) {
            String notes = contact.getNotes();
            return (notes != null && !notes.isBlank()) ? notes : "[Brak transkrypcji/notatek]";
        }

        // EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP
        // Treść powinna być w channelMetadata pod kluczem "body" lub "content"
        if (contact.getChannelMetadata() != null) {
            Object body = contact.getChannelMetadata().get("body");
            if (body instanceof String s && !s.isBlank()) {
                return s;
            }
            Object content = contact.getChannelMetadata().get("content");
            if (content instanceof String s && !s.isBlank()) {
                return s;
            }
        }

        return "[Brak treści kontaktu]";
    }
}
