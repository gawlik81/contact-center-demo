package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Testy {@link CircuitBreakerState} — licznik kolejnych błędów per instalacja, otwarcie obwodu
 * po {@link CircuitBreakerState#FAILURE_THRESHOLD} kolejnych błędach, zamknięcie po pierwszym
 * sukcesie ("closed on first success", bez stanu half-open — BE-102).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CircuitBreakerState – otwarcie po N błędach, zamknięcie po sukcesie")
class CircuitBreakerStateTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INSTALLATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private PluginRegistrationService pluginRegistrationService;

    private CircuitBreakerState circuitBreakerState;

    @BeforeEach
    void setUp() {
        circuitBreakerState = new CircuitBreakerState(pluginRegistrationService);
    }

    @Test
    @DisplayName("Obwód zamknięty (isOpen=false) dla instalacji bez historii błędów")
    void isOpenFalseForUnknownInstallation() {
        assertThat(circuitBreakerState.isOpen(INSTALLATION_ID)).isFalse();
    }

    @Test
    @DisplayName("Obwód pozostaje zamknięty po mniej niż FAILURE_THRESHOLD kolejnych błędach")
    void staysClosedBelowThreshold() {
        for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD - 1; i++) {
            circuitBreakerState.recordResult(TENANT_ID, INSTALLATION_ID, false);
        }

        assertThat(circuitBreakerState.isOpen(INSTALLATION_ID)).isFalse();
        verify(pluginRegistrationService, never()).updateHealthStatus(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("KRYTERIUM AKCEPTACJI: otwiera się (DEGRADED w DB) po FAILURE_THRESHOLD kolejnych błędach")
    void opensAfterThresholdConsecutiveFailures() {
        for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
            circuitBreakerState.recordResult(TENANT_ID, INSTALLATION_ID, false);
        }

        assertThat(circuitBreakerState.isOpen(INSTALLATION_ID)).isTrue();
        verify(pluginRegistrationService).updateHealthStatus(
                TENANT_ID, INSTALLATION_ID, TenantPluginInstallation.HealthStatus.DEGRADED,
                CircuitBreakerState.FAILURE_THRESHOLD);
    }

    @Test
    @DisplayName("Obwód otwarty wraca do HEALTHY i licznik resetuje się po pierwszym SUCCESS")
    void closesOnFirstSuccessAfterBeingOpen() {
        for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
            circuitBreakerState.recordResult(TENANT_ID, INSTALLATION_ID, false);
        }
        assertThat(circuitBreakerState.isOpen(INSTALLATION_ID)).isTrue();

        circuitBreakerState.recordResult(TENANT_ID, INSTALLATION_ID, true);

        assertThat(circuitBreakerState.isOpen(INSTALLATION_ID)).isFalse();
        verify(pluginRegistrationService).updateHealthStatus(
                TENANT_ID, INSTALLATION_ID, TenantPluginInstallation.HealthStatus.HEALTHY, 0);
    }

    @Test
    @DisplayName("Sukces na instalacji bez historii błędów nie woła DB (brak zmiany stanu)")
    void successWithoutPriorFailuresDoesNotTouchDb() {
        circuitBreakerState.recordResult(TENANT_ID, INSTALLATION_ID, true);

        verify(pluginRegistrationService, never()).updateHealthStatus(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("Liczniki są niezależne per installationId (jedna instalacja nie wpływa na inną)")
    void countersAreIndependentPerInstallation() {
        UUID otherInstallationId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
            circuitBreakerState.recordResult(TENANT_ID, INSTALLATION_ID, false);
        }

        assertThat(circuitBreakerState.isOpen(INSTALLATION_ID)).isTrue();
        assertThat(circuitBreakerState.isOpen(otherInstallationId)).isFalse();
    }
}
