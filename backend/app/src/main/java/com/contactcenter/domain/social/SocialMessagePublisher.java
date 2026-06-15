package com.contactcenter.domain.social;

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
public interface SocialMessagePublisher {

    /**
     * Publikuje przychodzące zdarzenie social media do kolejki asynchronicznego przetwarzania.
     *
     * @param message sparsowane zdarzenie social (z webhooka platformy)
     */
    void publish(IncomingSocialMessage message);
}
