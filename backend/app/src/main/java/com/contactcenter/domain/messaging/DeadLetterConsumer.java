package com.contactcenter.domain.messaging;

import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Konsument Dead Letter Queue (DLQ) – loguje wiadomości odrzucone przez inne kolejki.
 *
 * <p>Wiadomości trafiają do DLQ ({@code cc.queue.dead-letter}) gdy:
 * <ul>
 *   <li>konsument odrzuca je po wyczerpaniu retry (nack bez requeue)</li>
 *   <li>TTL wiadomości w kolejce wygaśnie (x-message-ttl)</li>
 *   <li>kolejka jest pełna (x-max-length)</li>
 * </ul>
 *
 * <p>Konsument loguje treść wiadomości i oryginalną kolejkę na poziomie ERROR.
 * W docelowej implementacji produkcyjnej można dodać zapis do tabeli {@code dead_letter_log}
 * lub wysłanie alertu do systemu monitoringu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class DeadLetterConsumer {

    private static final int MAX_BODY_LOG_LENGTH = 500;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_DLQ)
    public void handleDeadLetter(Message message) {
        String body = message.getBody() != null
                ? new String(message.getBody(), StandardCharsets.UTF_8)
                : "<empty>";

        // Nagłówek x-death zawiera historię odrzuceń (kolejka, exchange, przyczyna)
        Object xDeath = message.getMessageProperties().getHeaders().get("x-death");
        String originalQueue = extractOriginalQueue(message);

        String truncatedBody = body.length() > MAX_BODY_LOG_LENGTH
                ? body.substring(0, MAX_BODY_LOG_LENGTH) + "..."
                : body;

        log.error("[DeadLetter] Wiadomość trafiła do DLQ. Oryginalna kolejka: {}, x-death: {}, body: {}",
                originalQueue, xDeath, truncatedBody);
    }

    /**
     * Wyciąga nazwę oryginalnej kolejki z nagłówka x-death.
     *
     * <p>RabbitMQ umieszcza nagłówek {@code x-death} jako listę map.
     * Pierwsza mapa zawiera dane ostatniego odrzucenia (queue, exchange, reason, count).
     */
    @SuppressWarnings("unchecked")
    private String extractOriginalQueue(Message message) {
        try {
            Object xDeath = message.getMessageProperties().getHeaders().get("x-death");
            if (xDeath instanceof java.util.List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof java.util.Map<?, ?> map) {
                    Object queue = map.get("queue");
                    return queue != null ? queue.toString() : "<unknown>";
                }
            }
        } catch (Exception e) {
            log.debug("[DeadLetter] Nie można odczytać x-death header: {}", e.getMessage());
        }
        return "<unknown>";
    }
}
