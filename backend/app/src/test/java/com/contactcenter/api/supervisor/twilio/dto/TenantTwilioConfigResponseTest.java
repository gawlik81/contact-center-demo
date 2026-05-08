package com.contactcenter.api.supervisor.twilio.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantTwilioConfigResponse – masking wrażliwych pól")
class TenantTwilioConfigResponseTest {

    @Test
    @DisplayName("mask null zwraca null")
    void mask_null_returnsNull() {
        assertThat(TenantTwilioConfigResponse.mask(null)).isNull();
    }

    @Test
    @DisplayName("mask wartości <= 4 znaki zwraca tylko bullet")
    void mask_shortValue_returnsBullets() {
        assertThat(TenantTwilioConfigResponse.mask("ab")).isEqualTo("●●●●");
    }

    @Test
    @DisplayName("mask długiej wartości pokazuje ostatnie 4 znaki")
    void mask_longValue_showsLastFourChars() {
        String result = TenantTwilioConfigResponse.mask("super-secret-1234");
        assertThat(result).endsWith("1234");
        assertThat(result).startsWith("●");
    }

    @Test
    @DisplayName("mask nie ujawnia środkowej części wartości")
    void mask_doesNotRevealMiddle() {
        String result = TenantTwilioConfigResponse.mask("ACaabbccddeeffgghh");
        assertThat(result).doesNotContain("aabbccddeeff");
    }
}
