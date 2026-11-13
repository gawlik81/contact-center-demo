package com.contactcenter.domain.contact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OccupiedSlot - definicje statusow (BE-156 / BE-207 / BE-224)")
class OccupiedSlotTest {

    @Test
    @DisplayName("slot = ASSIGNED/ACTIVE/ON_HOLD; obciazenie agenta i 'w toku' = slot + QUEUED")
    void definitions() {
        assertThat(OccupiedSlot.STATUSES_SQL).isEqualTo("'ASSIGNED', 'ACTIVE', 'ON_HOLD'");
        assertThat(OccupiedSlot.AGENT_LOAD_STATUSES_SQL).isEqualTo("'QUEUED', " + OccupiedSlot.STATUSES_SQL);
    }

    @Test
    @DisplayName("IN_PROGRESS == AGENT_LOAD swiadomie (rownosc dzis); rozjazd wymaga przegladu obu znaczen i tego testu")
    void inProgressEqualsAgentLoadToday() {
        assertThat(OccupiedSlot.IN_PROGRESS_STATUSES_SQL).isEqualTo(OccupiedSlot.AGENT_LOAD_STATUSES_SQL);
    }
}
