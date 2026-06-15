package com.contactcenter.domain.social;

import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Konsumer wiadomości przychodzących social media z kolejki RabbitMQ.
 *
 * <p>Odbiera zdarzenia z kolejki {@code cc.queue.social-incoming} i deleguje
 * przetwarzanie do {@link SocialMessageService#processIncomingMessage(IncomingSocialMessage)}.
 *
 * <p>Kolejka jest zasilana przez {@link SocialMessagePublisher} wywoływanego
 * przez webhook handler. Wzorzec ten gwarantuje:
 * <ul>
 *   <li>Webhook zwraca HTTP 200 natychmiast (< 3s) – platformy retryują 5xx</li>
 *   <li>Przetwarzanie odbywa się asynchronicznie w osobnym wątku RabbitMQ</li>
 *   <li>TenantContext jest zarządzany przez SocialMessageService (ustawiany po identyfikacji tenanta)</li>
 * </ul>
 *
 * <p>Wzorzec analogiczny do {@link com.contactcenter.domain.email.EmailContactCreator}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SocialMessageConsumer {

    private final SocialMessageService socialMessageService;

    /**
     * Przetwarza przychodzące zdarzenie social media z kolejki.
     *
     * <p>Błąd przetwarzania jest logowany – wiadomość trafia do DLQ po wyczerpaniu retry.
     * TenantContext NIE jest ustawiany tutaj – zarządza nim {@link SocialMessageService}.
     *
     * @param message zdarzenie do przetworzenia
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_SOCIAL_INCOMING)
    public void onSocialMessage(IncomingSocialMessage message) {
        log.info("[SocialConsumer] Odebrano zdarzenie: platform={}, pageId={}, externalMsgId={}",
                message.platform(), message.pageId(), message.externalMessageId());

        try {
            socialMessageService.processIncomingMessage(message);
        } catch (Exception e) {
            log.error("[SocialConsumer] Błąd przetwarzania zdarzenia social: platform={}, " +
                      "externalMsgId={}, error={}",
                    message.platform(), message.externalMessageId(), e.getMessage(), e);
            // Rzucenie wyjątku powoduje nack → wiadomość do DLQ po retry
            throw e;
        }
    }
}
