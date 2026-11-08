package com.contactcenter.domain.websocket;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** BE-210: rolling deploy - old and new instances exchange {@code agent.status.changed} with/without reason. */
@DisplayName("AgentStatusChangedEvent - zgodnosc wsteczna (BE-210)")
class AgentStatusChangedEventCompatibilityTest {

    // Deliberately a STRICT mapper (FAIL_ON_UNKNOWN_PROPERTIES on): the record itself must tolerate new fields.
    private final ObjectMapper strict = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private static final String BASE = "\"userId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"tenantId\":\"22222222-2222-2222-2222-222222222222\",\"oldStatus\":\"AVAILABLE\","
            + "\"newStatus\":\"OFFLINE\",\"timestamp\":\"2026-11-08T10:00:00Z\"";

    @Test
    @DisplayName("brak reason (stary producent) -> reason == null")
    void missingReasonIsNull() throws Exception {
        AgentStatusChangedEvent e = strict.readValue("{" + BASE + "}", AgentStatusChangedEvent.class);

        assertThat(e.reason()).isNull();
        assertThat(e.statusSince()).isNull();
    }

    @Test
    @DisplayName("nieznane pole (przyszly producent) nie psuje deserializacji nawet na scislym mapperze")
    void unknownFieldIgnored() throws Exception {
        AgentStatusChangedEvent e = strict.readValue(
                "{" + BASE + ",\"reason\":\"PRESENCE_TIMEOUT\",\"futureField\":42}", AgentStatusChangedEvent.class);

        assertThat(e.reason()).isEqualTo(AgentStatusChangedEvent.REASON_PRESENCE_TIMEOUT);
    }
}
