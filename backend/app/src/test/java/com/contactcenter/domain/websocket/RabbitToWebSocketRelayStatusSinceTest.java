package com.contactcenter.domain.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("RabbitToWebSocketRelay - statusSince (BE-153)")
class RabbitToWebSocketRelayStatusSinceTest {

    private final WebSocketEventBroadcaster broadcaster = mock(WebSocketEventBroadcaster.class);
    private final RabbitToWebSocketRelay relay = new RabbitToWebSocketRelay(broadcaster);
    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    private WebSocketEvent.AgentStatusPayload relayWith(String statusSince, boolean include) {
        Map<String, String> payload = new HashMap<>();
        payload.put("userId", agent.toString());
        payload.put("tenantId", tenant.toString());
        payload.put("newStatus", "BREAK");
        if (include) {
            payload.put("statusSince", statusSince);
        }
        relay.onAgentStatusEvent(payload, "agent.status.changed");
        ArgumentCaptor<WebSocketEvent> captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(broadcaster).sendToUser(eq(agent), captor.capture());
        verify(broadcaster).sendToTenantSupervisors(eq(tenant), any());
        return (WebSocketEvent.AgentStatusPayload) captor.getValue().payload();
    }

    @Test
    @DisplayName("poprawny ISO statusSince trafia do payloadu WS")
    void validValue() {
        assertThat(relayWith("2026-11-04T10:15:30.123456Z", true).statusSince())
                .isEqualTo(Instant.parse("2026-11-04T10:15:30.123456Z"));
    }

    @Test
    @DisplayName("smieciowa wartosc -> null, zdarzenie nie przepada")
    void garbageValue() {
        WebSocketEvent.AgentStatusPayload p = relayWith("not-a-date", true);
        assertThat(p.statusSince()).isNull();
        assertThat(p.status()).isEqualTo("BREAK");
    }

    @Test
    @DisplayName("brak pola (starszy producent) -> null")
    void missingValue() {
        assertThat(relayWith(null, false).statusSince()).isNull();
    }
}
