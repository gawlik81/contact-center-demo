package com.contactcenter.infrastructure.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebSocketFanoutProperties - id instancji i kanal (BE-175 review S1/S2)")
class WebSocketFanoutPropertiesTest {

    @Test
    @DisplayName("id instancji ma zawsze losowy sufiks: dwie repliki z tym samym skonfigurowanym id roznia sie")
    void instanceIdAlwaysGetsRandomSuffix() {
        WebSocketFanoutProperties a = new WebSocketFanoutProperties();
        WebSocketFanoutProperties b = new WebSocketFanoutProperties();
        a.setInstanceId("shared");
        b.setInstanceId("shared");
        assertThat(a.resolvedInstanceId()).startsWith("shared-").isNotEqualTo(b.resolvedInstanceId());
        assertThat(a.resolvedInstanceId()).isEqualTo(a.resolvedInstanceId()); // stable per process
        assertThat(new WebSocketFanoutProperties().resolvedInstanceId()).hasSize(8);
    }

    @Test
    @DisplayName("kanal = baza:srodowisko, srodowisko sanityzowane (profile z przecinkiem)")
    void channelCarriesSanitizedEnvironment() {
        WebSocketFanoutProperties p = new WebSocketFanoutProperties();
        assertThat(p.effectiveChannel()).isEqualTo("cc:ws:fanout:default");
        p.setEnvironment("prod,eu west");
        assertThat(p.effectiveChannel()).isEqualTo("cc:ws:fanout:prod_eu_west");
        p.setEnvironment(" ");
        assertThat(p.effectiveChannel()).isEqualTo("cc:ws:fanout:default");
    }
}
