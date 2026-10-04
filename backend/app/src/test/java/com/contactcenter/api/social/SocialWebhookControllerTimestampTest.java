package com.contactcenter.api.social;

import com.contactcenter.domain.social.IncomingSocialMessage;
import com.contactcenter.domain.social.SocialMessagePublisher;
import com.contactcenter.domain.social.SocialPlatform;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Testy jednostkowe (bez Springa/DB) dla naprawy BE-132 (EPIC-30): źródło {@code sentAt} dla
 * Facebooka i Instagramu musi być deterministyczne — czas zdarzenia Z PAYLOADU platformy
 * ({@code entry[].messaging[].timestamp}, Unix epoch MILISEKUND dla Messenger Platform/Instagram
 * Messaging API), NIE {@code Instant.now()} w chwili przetworzenia webhooka.
 *
 * <p><strong>Kontekst (OSTRZEŻENIE z V100/DB-065):</strong> redelivery tego samego zdarzenia (ten
 * sam {@code mid}) PRZED tą naprawą dostawał inny {@code sentAt} przy każdym odebraniu webhooka —
 * unikalność złożona {@code (tenant_id, external_message_id, sent_at)}, wymagana przez PostgreSQL
 * na tabeli partycjonowanej, nie wykrywała takiego duplikatu. Ten test dowodzi, że DWA WYWOŁANIA
 * handlera z IDENTYCZNYM payloadem (symulacja redelivery przez platformę) produkują
 * {@link IncomingSocialMessage} z IDENTYCZNYM {@code sentAt} — warunek niezbędny, żeby
 * {@code SocialMessageRepository}'s {@code ON CONFLICT ON CONSTRAINT uq_social_message_external_id}
 * (druga linia obrony, BE-132) miało szansę zadziałać.
 */
@DisplayName("SocialWebhookController – deterministyczny sentAt dla FB/IG z payloadu (BE-132)")
class SocialWebhookControllerTimestampTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // Facebook
    // =========================================================================

    @Test
    @DisplayName("Facebook: redelivery IDENTYCZNEGO payloadu -> IncomingSocialMessage.sentAt IDENTYCZNY przy obu dostawach")
    void facebook_redeliveryOfSamePayload_producesIdenticalSentAt() {
        SocialMessagePublisher publisher = mock(SocialMessagePublisher.class);
        SocialWebhookController controller = new SocialWebhookController(publisher, objectMapper);

        long eventTimestampMillis = 1_729_000_000_000L; // czas zdarzenia ustalony przez Meta
        String payload = facebookPayload("mid-fb-redelivery-1", eventTimestampMillis);

        // Pierwsza dostawa
        controller.handleFacebookWebhook(payload);
        // "Redelivery" tego samego zdarzenia -- platforma wysyła DOKŁADNIE ten sam payload ponownie,
        // niezależnie od tego, kiedy faktycznie doszło do ponownej dostawy (wall-clock różny, ale tu
        // nie ma to znaczenia -- testujemy wartość pola, nie czas testu).
        controller.handleFacebookWebhook(payload);

        ArgumentCaptor<IncomingSocialMessage> captor = ArgumentCaptor.forClass(IncomingSocialMessage.class);
        verify(publisher, times(2)).publish(captor.capture());

        Instant first = captor.getAllValues().get(0).sentAt();
        Instant second = captor.getAllValues().get(1).sentAt();

        assertThat(first)
                .as("sentAt deterministyczny z payloadu, nie Instant.now() przy odbiorze")
                .isEqualTo(Instant.ofEpochMilli(eventTimestampMillis))
                .isEqualTo(second);
    }

    @Test
    @DisplayName("Facebook: brak pola timestamp w zdarzeniu -> fallback Instant.now() (udokumentowane ograniczenie)")
    void facebook_missingTimestamp_fallsBackToNow() {
        SocialMessagePublisher publisher = mock(SocialMessagePublisher.class);
        SocialWebhookController controller = new SocialWebhookController(publisher, objectMapper);

        String payload = """
                {
                  "object": "page",
                  "entry": [{
                    "id": "PAGE_1",
                    "messaging": [{
                      "sender": {"id": "USER_1"},
                      "message": {"mid": "mid-fb-no-ts", "text": "Hello"}
                    }]
                  }]
                }
                """;

        Instant before = Instant.now();
        controller.handleFacebookWebhook(payload);
        Instant after = Instant.now();

        ArgumentCaptor<IncomingSocialMessage> captor = ArgumentCaptor.forClass(IncomingSocialMessage.class);
        verify(publisher).publish(captor.capture());

        Instant sentAt = captor.getValue().sentAt();
        assertThat(sentAt).isBetween(before, after);
    }

    // =========================================================================
    // Instagram (format identyczny z Facebook)
    // =========================================================================

    @Test
    @DisplayName("Instagram: redelivery IDENTYCZNEGO payloadu -> IncomingSocialMessage.sentAt IDENTYCZNY przy obu dostawach")
    void instagram_redeliveryOfSamePayload_producesIdenticalSentAt() {
        SocialMessagePublisher publisher = mock(SocialMessagePublisher.class);
        SocialWebhookController controller = new SocialWebhookController(publisher, objectMapper);

        long eventTimestampMillis = 1_729_100_000_000L;
        String payload = """
                {
                  "object": "instagram",
                  "entry": [{
                    "id": "IG_PAGE_1",
                    "messaging": [{
                      "sender": {"id": "IG_USER_1"},
                      "timestamp": %d,
                      "message": {"mid": "mid-ig-redelivery-1", "text": "Hej"}
                    }]
                  }]
                }
                """.formatted(eventTimestampMillis);

        controller.handleInstagramWebhook(payload);
        controller.handleInstagramWebhook(payload);

        ArgumentCaptor<IncomingSocialMessage> captor = ArgumentCaptor.forClass(IncomingSocialMessage.class);
        verify(publisher, times(2)).publish(captor.capture());

        assertThat(captor.getAllValues().get(0).sentAt())
                .isEqualTo(Instant.ofEpochMilli(eventTimestampMillis))
                .isEqualTo(captor.getAllValues().get(1).sentAt());
        assertThat(captor.getAllValues().get(0).platform()).isEqualTo(SocialPlatform.INSTAGRAM);
    }

    // =========================================================================
    // WhatsApp (bez regresji -- już deterministyczny przed BE-132, sekundy nie milisekundy)
    // =========================================================================

    @Test
    @DisplayName("WhatsApp: bez regresji -- timestamp w SEKUNDACH (nie milisekundach jak FB/IG)")
    void whatsapp_timestampInSeconds_unaffectedByFbIgFix() {
        SocialMessagePublisher publisher = mock(SocialMessagePublisher.class);
        SocialWebhookController controller = new SocialWebhookController(publisher, objectMapper);

        long timestampSeconds = 1_729_000_000L;
        String payload = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [{
                    "changes": [{
                      "value": {
                        "metadata": {"phone_number_id": "PHONE_1"},
                        "messages": [{
                          "from": "48123456789",
                          "id": "mid-wa-1",
                          "type": "text",
                          "text": {"body": "Hello"},
                          "timestamp": "%d"
                        }]
                      }
                    }]
                  }]
                }
                """.formatted(timestampSeconds);

        controller.handleWhatsAppWebhook(payload);

        ArgumentCaptor<IncomingSocialMessage> captor = ArgumentCaptor.forClass(IncomingSocialMessage.class);
        verify(publisher).publish(captor.capture());

        assertThat(captor.getValue().sentAt()).isEqualTo(Instant.ofEpochSecond(timestampSeconds));
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private String facebookPayload(String mid, long timestampMillis) {
        return """
                {
                  "object": "page",
                  "entry": [{
                    "id": "PAGE_1",
                    "messaging": [{
                      "sender": {"id": "USER_1"},
                      "timestamp": %d,
                      "message": {"mid": "%s", "text": "Hello"}
                    }]
                  }]
                }
                """.formatted(timestampMillis, mid);
    }
}
