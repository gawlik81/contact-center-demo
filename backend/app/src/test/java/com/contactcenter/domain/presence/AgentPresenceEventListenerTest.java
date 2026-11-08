package com.contactcenter.domain.presence;

import com.contactcenter.security.StompPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("AgentPresenceEventListener (BE-210)")
class AgentPresenceEventListenerTest {

    private final AgentPresenceRegistry registry = mock(AgentPresenceRegistry.class);
    private final AgentPresenceEventListener listener = new AgentPresenceEventListener(registry);
    private final StompPrincipal user = new StompPrincipal(UUID.randomUUID(), UUID.randomUUID(), "AGENT");

    private Message<byte[]> frame(StompCommand command, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("CONNECTED i SUBSCRIBE odswiezaja obecnosc sesji")
    void connectAndSubscribeTouch() {
        listener.onConnected(new SessionConnectedEvent(this, frame(StompCommand.CONNECTED, "s1"), user));
        listener.onSubscribe(new SessionSubscribeEvent(this, frame(StompCommand.SUBSCRIBE, "s1"), user));

        verify(registry, org.mockito.Mockito.times(2)).touch(user, "s1");
    }

    @Test
    @DisplayName("DISCONNECT zapisuje zamkniecie sesji (nie zmienia statusu)")
    void disconnectRecordsClose() {
        listener.onDisconnect(new SessionDisconnectEvent(this, frame(StompCommand.DISCONNECT, "s1"), "s1",
                CloseStatus.NORMAL, user));

        verify(registry).sessionClosed(user, "s1");
    }
}
