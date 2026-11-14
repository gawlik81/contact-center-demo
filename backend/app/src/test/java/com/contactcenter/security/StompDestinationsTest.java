package com.contactcenter.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StompDestinations - wspolna allow-lista destynacji (BE-229/BE-175)")
class StompDestinationsTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("formaty uzywane przez nadawcę parsują się na wlasciwy rodzaj i id")
    void formatsParse() {
        assertThat(StompDestinations.parse(StompDestinations.USER_EVENTS_FORMAT.formatted(ID)))
                .isEqualTo(new StompDestinations.Parsed(StompDestinations.Kind.USER_EVENTS, ID));
        assertThat(StompDestinations.parse(StompDestinations.TENANT_AGENTS_FORMAT.formatted(ID)))
                .isEqualTo(new StompDestinations.Parsed(StompDestinations.Kind.TENANT_AGENTS, ID));
        assertThat(StompDestinations.parse(StompDestinations.TENANT_SUPERVISOR_FORMAT.formatted(ID)))
                .isEqualTo(new StompDestinations.Parsed(StompDestinations.Kind.TENANT_SUPERVISOR, ID));
    }

    @Test
    @DisplayName("wszystko inne daje null")
    void everythingElseIsRejected() {
        assertThat(StompDestinations.parse(null)).isNull();
        for (String d : new String[] {"", "/topic/**", "/topic/tenant/" + ID + "/supervisor/metrics", "/user/" + ID,
                "/topic/user/" + ID, "/app/ping", "/topic/tenant/" + "ABCDEF01-0000-0000-0000-000000000000" + "/agents"}) {
            assertThat(StompDestinations.parse(d)).as(d).isNull();
        }
    }
}
