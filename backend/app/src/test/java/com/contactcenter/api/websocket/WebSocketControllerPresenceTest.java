package com.contactcenter.api.websocket;

import com.contactcenter.domain.presence.AgentPresenceRegistry;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.security.StompPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("WebSocketController /app/ping - sygnal obecnosci (BE-210)")
class WebSocketControllerPresenceTest {

    private final AgentPresenceRegistry registry = mock(AgentPresenceRegistry.class);
    private final WebSocketController controller = new WebSocketController(registry);

    private SimpMessageHeaderAccessor headers(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(sessionId);
        return accessor;
    }

    @Test
    @DisplayName("ping odswieza obecnosc sesji i dalej odpowiada PONG")
    void pingTouchesPresenceAndReturnsPong() {
        UUID tenant = UUID.randomUUID();
        StompPrincipal principal = new StompPrincipal(UUID.randomUUID(), tenant, "AGENT");

        WebSocketEvent pong = controller.handlePing(principal, headers("sess-1"));

        verify(registry).touch(principal, "sess-1");
        assertThat(pong.eventType()).isEqualTo(WebSocketEvent.TYPE_PONG);
        assertThat(pong.tenantId()).isEqualTo(tenant);
    }

    @Test
    @DisplayName("ping od nieznanego principal nie dotyka rejestru")
    void unknownPrincipalDoesNotTouch() {
        WebSocketEvent pong = controller.handlePing(() -> "anon", headers("sess-1"));

        verifyNoInteractions(registry);
        assertThat(pong.eventType()).isEqualTo(WebSocketEvent.TYPE_PONG);
    }
}
