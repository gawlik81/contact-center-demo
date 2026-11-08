package com.contactcenter.domain.presence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * BE-210: feeds {@link AgentPresenceRegistry} from STOMP session lifecycle events. The disconnect event only
 * records the fact (the sweeper is the single executor of the OFFLINE transition); a missed disconnect
 * is covered by the session TTL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AgentPresenceEventListener {

    private final AgentPresenceRegistry registry;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        registry.touch(event.getUser(), sessionId(event.getMessage().getHeaders()));
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        registry.touch(event.getUser(), sessionId(event.getMessage().getHeaders()));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        registry.sessionClosed(event.getUser(), event.getSessionId());
    }

    private static String sessionId(org.springframework.messaging.MessageHeaders headers) {
        Object id = headers.get(org.springframework.messaging.simp.SimpMessageHeaderAccessor.SESSION_ID_HEADER);
        return id != null ? id.toString() : null;
    }
}
