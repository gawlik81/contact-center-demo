package com.contactcenter.domain.social;

import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
class SocialMessagePublisherImpl implements SocialMessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
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
