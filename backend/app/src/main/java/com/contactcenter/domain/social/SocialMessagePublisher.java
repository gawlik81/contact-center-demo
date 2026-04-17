package com.contactcenter.domain.social;

import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Publisher wiadomości przychodzących social media do kolejki RabbitMQ.
 *
 * <p>Webhook handler wywołuje tę klasę natychmiast po sparsowaniu payloadu,
 * a następnie zwraca HTTP 200. Właściwe przetwarzanie odbywa się asynchronicznie
 * przez {@link SocialMessageConsumer}.
 *
 * <p>Wzorzec analogiczny do {@link com.contactcenter.domain.email.EmailEventPublisher}:
 * błąd publikacji jest logowany, ale nie przerywany wyjątkiem –
 * webhook zawsze musi zwracać 200 (platformy retryują 5xx).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialMessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publikuje przychodzące zdarzenie social media do kolejki asynchronicznego przetwarzania.
     *
     * @param message sparsowane zdarzenie social (z webhooka platformy)
     */
    public void publish(IncomingSocialMessage message) {
        log.debug("[SocialPublisher] Publikuję zdarzenie: platform={}, pageId={}, sender={}, externalMsgId={}",
                message.platform(), message.pageId(), message.senderExternalId(),
                message.externalMessageId());

        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.QUEUE_SOCIAL_INCOMING, message);
            log.info("[SocialPublisher] Zdarzenie opublikowane: platform={}, externalMsgId={}",
                    message.platform(), message.externalMessageId());
        } catch (AmqpException e) {
            // Błąd publikacji nie może przerywać pracy webhook handlera –
            // platformy retryują 5xx, ale oczekują 200 w <3s
            log.error("[SocialPublisher] Błąd publikacji zdarzenia social: platform={}, externalMsgId={}, error={}",
                    message.platform(), message.externalMessageId(), e.getMessage(), e);
        }
    }
}
