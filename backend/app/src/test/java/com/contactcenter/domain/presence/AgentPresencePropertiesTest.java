package com.contactcenter.domain.presence;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** BE-210: a misconfigured window must be rejected at startup, not silently switch every agent OFFLINE. */
@DisplayName("AgentPresenceProperties - walidacja (BE-210)")
class AgentPresencePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("wartosci domyslne sa poprawne")
    void defaultsAreValid() {
        assertThat(validator.validate(new AgentPresenceProperties())).isEmpty();
    }

    @Test
    @DisplayName("zero / ujemne / krotsze niz 2 pingi okna sa odrzucane")
    void invalidWindowsRejected() {
        AgentPresenceProperties p = new AgentPresenceProperties();
        p.setSessionTtlMs(0);
        p.setGracePeriodMs(-1);
        p.setSweepIntervalMs(0);
        p.setTouchThrottleMs(0);
        p.setBusyOrphanTimeoutMs(30_000);

        assertThat(validator.validate(p)).extracting(v -> v.getPropertyPath().toString())
                .contains("sessionTtlMs", "gracePeriodMs", "sweepIntervalMs", "touchThrottleMs", "busyOrphanTimeoutMs");
    }

    @Test
    @DisplayName("busy-orphan-timeout krotszy niz grace oraz throttle > ttl/3 sa odrzucane")
    void relationsRejected() {
        AgentPresenceProperties p = new AgentPresenceProperties();
        p.setGracePeriodMs(120_000);
        p.setBusyOrphanTimeoutMs(90_000);
        p.setTouchThrottleMs(40_000);

        assertThat(validator.validate(p)).extracting(v -> v.getPropertyPath().toString())
                .contains("busyOrphanTimeoutNotShorterThanGrace", "touchThrottleWellBelowSessionTtl");
    }
}
