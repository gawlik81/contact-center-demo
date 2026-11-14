package com.contactcenter.infrastructure.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The only place that hands a WebSocket event to this instance's {@code SimpleBroker}.
 *
 * <p>An event is serialized exactly once, by the broker messaging template's own converter (so the wire format seen
 * by clients is identical to the pre-fan-out {@code convertAndSend}), and the resulting JSON bytes are delivered
 * as-is - on the publishing instance directly, on the others after travelling through Redis.
 */
@Component
public class WebSocketLocalDispatcher {

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketLocalDispatcher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /** @return the event as JSON bytes, produced by the same converter {@code convertAndSend} would use */
    public byte[] serialize(Object event) {
        Message<?> message = messagingTemplate.getMessageConverter().toMessage(event,
                new MessageHeaders(Map.of(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)));
        if (message == null) {
            throw new IllegalStateException("No message converter for " + event.getClass().getName());
        }
        Object payload = message.getPayload();
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        if (payload instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("Unexpected serialized payload type " + payload.getClass().getName());
    }

    /** Sends JSON bytes to the local broker (which fans out to local subscriptions of {@code destination}). */
    public void deliver(String destination, byte[] jsonPayload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);
        messagingTemplate.send(destination, MessageBuilder.createMessage(jsonPayload, accessor.getMessageHeaders()));
    }
}
