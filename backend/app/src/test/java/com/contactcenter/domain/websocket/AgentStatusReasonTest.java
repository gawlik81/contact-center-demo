package com.contactcenter.domain.websocket;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

/** BE-210: optional {@code reason} through the whole chain event -> RabbitMQ JSON -> relay -> WS frame. */
@DisplayName("agent.status.changed reason (BE-210)")
class AgentStatusReasonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final UUID tenant = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    @Test
    @DisplayName("event z reason=PRESENCE_TIMEOUT serializuje pole; reczna zmiana (null) pomija je w JSON")
    void eventJson() throws Exception {
        Instant since = Instant.now();
        JsonNode auto = mapper.valueToTree(AgentStatusChangedEvent.of(agent, tenant, UserStatus.AVAILABLE,
                UserStatus.OFFLINE, since, AgentStatusChangedEvent.REASON_PRESENCE_TIMEOUT));
        JsonNode manual = mapper.valueToTree(AgentStatusChangedEvent.of(agent, tenant, UserStatus.AVAILABLE,
                UserStatus.BREAK, since));

        assertThat(auto.get("reason").asText()).isEqualTo("PRESENCE_TIMEOUT");
        assertThat(manual.has("reason")).isFalse();
    }

    @Test
    @DisplayName("starszy komunikat bez reason deserializuje sie z reason=null")
    void legacyMessageDeserializes() throws Exception {
        String json = "{\"userId\":\"" + agent + "\",\"tenantId\":\"" + tenant
                + "\",\"oldStatus\":\"AVAILABLE\",\"newStatus\":\"BUSY\",\"timestamp\":\"2026-03-17T10:00:00Z\"}";

        AgentStatusChangedEvent e = mapper.readValue(json, AgentStatusChangedEvent.class);

        assertThat(e.reason()).isNull();
        assertThat(e.statusSince()).isNull();
    }

    private WebSocketEvent relay(String reason) {
        WebSocketEventBroadcaster broadcaster = mock(WebSocketEventBroadcaster.class);
        RabbitToWebSocketRelay relay = new RabbitToWebSocketRelay(broadcaster);
        Map<String, String> payload = new HashMap<>();
        payload.put("userId", agent.toString());
        payload.put("tenantId", tenant.toString());
        payload.put("newStatus", "OFFLINE");
        if (reason != null) {
            payload.put("reason", reason);
        }
        relay.onAgentStatusEvent(payload, "agent.status.changed");
        ArgumentCaptor<WebSocketEvent> captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(broadcaster).sendToUser(eq(agent), captor.capture());
        verify(broadcaster).sendToTenantSupervisors(eq(tenant), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("relay: ramka WS AGENT_STATUS_CHANGED niesie payload.reason=PRESENCE_TIMEOUT")
    void relayCarriesReason() throws Exception {
        WebSocketEvent ws = relay("PRESENCE_TIMEOUT");

        assertThat(ws.eventType()).isEqualTo("AGENT_STATUS_CHANGED");
        JsonNode json = mapper.valueToTree(ws);
        assertThat(json.get("payload").get("reason").asText()).isEqualTo("PRESENCE_TIMEOUT");
        assertThat(json.get("payload").get("status").asText()).isEqualTo("OFFLINE");
        assertThat(json.get("payload").get("agentId").asText()).isEqualTo(agent.toString());
    }

    @Test
    @DisplayName("relay: bez reason pole nie wystepuje w ramce WS (kompatybilnosc wsteczna)")
    void relayWithoutReasonOmitsField() {
        JsonNode json = mapper.valueToTree(relay(null));

        assertThat(json.get("payload").has("reason")).isFalse();
    }
}
