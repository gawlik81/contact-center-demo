package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CircuitBreakerState - propagacja stanu OPEN/CLOSED między instancjami (BE-178)")
class CircuitBreakerStatePropagationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();

    @Mock private PluginRegistrationService registrationService;
    @Mock private PluginSyncBus bus;

    private CircuitBreakerState state;

    @BeforeEach
    void setUp() {
        state = new CircuitBreakerState(registrationService, bus);
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            state.recordResult(TENANT, INSTALLATION, false);
        }
    }

    private static PluginSyncEnvelope remote(PluginSyncEnvelope.Type type, UUID installation) {
        return new PluginSyncEnvelope(1, "other", type, TENANT, installation, null, System.currentTimeMillis());
    }

    @Test
    @DisplayName("osiągnięcie progu publikuje CIRCUIT_OPENED dokładnie raz (kolejne błędy już nie)")
    void thresholdPublishesOpenedOnce() {
        fail(CircuitBreakerState.FAILURE_THRESHOLD - 1);
        verify(bus, never()).publish(any(), any(), any(), any());

        fail(3);

        verify(bus).publish(PluginSyncEnvelope.Type.CIRCUIT_OPENED, TENANT, INSTALLATION, null);
        org.mockito.Mockito.verifyNoMoreInteractions(bus);
        assertThat(state.isOpen(INSTALLATION)).isTrue();
    }

    @Test
    @DisplayName("zdalne CIRCUIT_OPENED otwiera obwód lokalnie bez dotykania DB i bez ponownej publikacji (brak pętli)")
    void remoteOpenedOpensLocally() {
        state.applyRemote(remote(PluginSyncEnvelope.Type.CIRCUIT_OPENED, INSTALLATION));

        assertThat(state.isOpen(INSTALLATION)).isTrue();
        assertThat(state.isOpen(UUID.randomUUID())).isFalse(); // inna instalacja nietknięta
        org.mockito.Mockito.verifyNoInteractions(bus, registrationService);
    }

    @Test
    @DisplayName("zdalne CIRCUIT_CLOSED zamyka obwód i zeruje licznik lokalny")
    void remoteClosedClosesLocally() {
        fail(2);
        state.applyRemote(remote(PluginSyncEnvelope.Type.CIRCUIT_OPENED, INSTALLATION));

        state.applyRemote(remote(PluginSyncEnvelope.Type.CIRCUIT_CLOSED, INSTALLATION));

        assertThat(state.isOpen(INSTALLATION)).isFalse();
        // licznik wyzerowany: do ponownego otwarcia trzeba pełnego progu
        fail(CircuitBreakerState.FAILURE_THRESHOLD - 1);
        assertThat(state.isOpen(INSTALLATION)).isFalse();
    }

    @Test
    @DisplayName("SUCCESS po otwarciu (własnym lub zdalnym) publikuje CIRCUIT_CLOSED; SUCCESS bez otwarcia - nie")
    void successAfterOpenPublishesClosed() {
        state.recordResult(TENANT, INSTALLATION, true);
        verify(bus, never()).publish(eq(PluginSyncEnvelope.Type.CIRCUIT_CLOSED), any(), any(), any());

        state.applyRemote(remote(PluginSyncEnvelope.Type.CIRCUIT_OPENED, INSTALLATION));
        state.recordResult(TENANT, INSTALLATION, true);

        verify(bus).publish(PluginSyncEnvelope.Type.CIRCUIT_CLOSED, TENANT, INSTALLATION, null);
        assertThat(state.isOpen(INSTALLATION)).isFalse();
    }

    @Test
    @DisplayName("forget() resetuje licznik i obwód otwarty zdalnie (interwencja administratora)")
    void forgetResetsEverything() {
        fail(CircuitBreakerState.FAILURE_THRESHOLD);
        state.applyRemote(remote(PluginSyncEnvelope.Type.CIRCUIT_OPENED, INSTALLATION));
        assertThat(state.isOpen(INSTALLATION)).isTrue();

        state.forget(INSTALLATION);

        assertThat(state.isOpen(INSTALLATION)).isFalse();
    }

    @Test
    @DisplayName("bez magistrali (testy/konstruktor wygodny) działa lokalnie jak dotąd")
    void worksWithoutBus() {
        CircuitBreakerState local = new CircuitBreakerState(registrationService);
        for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
            local.recordResult(TENANT, INSTALLATION, false);
        }
        assertThat(local.isOpen(INSTALLATION)).isTrue();
        local.recordResult(TENANT, INSTALLATION, true);
        assertThat(local.isOpen(INSTALLATION)).isFalse();
    }
}
