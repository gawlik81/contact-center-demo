package com.contactcenter.domain.service;

import com.contactcenter.domain.exception.AiConfigNotFoundException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.ContactAiSummary;
import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.tenant.TenantAiConfigDecrypted;
import com.contactcenter.domain.tenant.TenantAiConfigService;
import com.contactcenter.domain.repository.ContactAiSummaryRepository;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.ContactTranscriptionRepository;
import com.contactcenter.domain.repository.EmailMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 *   <li>Zapis wyniku (summary, modelUsed, timestamp) do tabeli {@code contact_ai_summary} przez {@link com.contactcenter.domain.repository.ContactAiSummaryRepository#save}.</li>
 * </ol>
 *
 * <p>Nie implementuje kontrolera – ten zostanie dodany w BE-090.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryService {

    private final ContactRepository contactRepository;
    private final ContactTranscriptionRepository transcriptionRepository;
    private final EmailMessageRepository emailMessageRepository;
    private final TenantAiConfigService aiConfigService;
    private final AiSummaryClient aiSummaryClient;
    private final ContactAiSummaryRepository aiSummaryRepository;

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
        String content = extractContent(contact, tenantId);

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

        // 5. Zapisz wynik w tabeli contact_ai_summary
        Instant now = Instant.now();
        ContactAiSummary aiSummary = ContactAiSummary.builder()
                .aiSummaryId(UUID.randomUUID())
                .contactId(contactId)
                .tenantId(tenantId)
                .summary(response.summary())
                .model(response.modelUsed())
                .generatedAt(now)
                .createdAt(now)
                .build();
        aiSummaryRepository.save(aiSummary);

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
     * <p>Dla kanału PHONE czyta transkrypcję z tabeli {@code contact_transcription}
     * (zapisywanej przez Whisper po uploadzie nagrania do S3).
     * Pole {@code contact.notes} jest zarezerwowane dla ręcznych notatek agenta
     * i nie jest tu odczytywane.
     *
     * <p>Dla kanałów EMAIL i SOCIAL* – szuka treści w {@code channelMetadata} pod kluczami
     * {@code "body"} i {@code "content"} (standard adaptera email i social media).
     *
     * @param contact  encja kontaktu
     * @param tenantId UUID tenanta (potrzebny do zapytania o transkrypcję)
     * @return treść do podsumowania lub komunikat zastępczy gdy brak treści
     */
    public String extractContent(Contact contact, UUID tenantId) {
        String channel = contact.getChannel();

        if ("PHONE".equals(channel)) {
            return transcriptionRepository
                    .findContentByContactId(contact.getContactId(), tenantId)
                    .filter(t -> !t.isBlank())
                    .orElse("[Brak transkrypcji rozmowy]");
        }

        if ("EMAIL".equals(channel)) {
            String emailBody = emailMessageRepository
                    .findByContactId(contact.getContactId(), tenantId,
                            PageRequest.of(0, 20, Sort.by("receivedAt").ascending()))
                    .getContent().stream()
                    .filter(m -> "INBOUND".equals(m.getDirection()))
                    .map(EmailMessage::getBodyText)
                    .filter(t -> t != null && !t.isBlank())
                    .reduce("", (a, b) -> a.isBlank() ? b : a + "\n---\n" + b);
            return emailBody.isBlank() ? "[Brak treści emaila]" : emailBody;
        }

        // SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP
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

    /**
     * Zachowana dla kompatybilności wstecznej z testami wywołującymi bezparametrową wersję.
     * Dla kanału PHONE zwraca zawsze komunikat zastępczy (brak tenantId → nie odpytuje DB).
     *
     * @deprecated Używaj {@link #extractContent(Contact, UUID)}.
     */
    @Deprecated
    public String extractContent(Contact contact) {
        String channel = contact.getChannel();
        if ("PHONE".equals(channel)) {
            return "[Brak transkrypcji rozmowy]";
        }
        return extractContent(contact, null);
    }
}
