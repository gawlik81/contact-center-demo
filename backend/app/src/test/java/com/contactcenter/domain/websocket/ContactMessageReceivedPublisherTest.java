package com.contactcenter.domain.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("ContactMessageReceivedPublisher (BE-154)")
class ContactMessageReceivedPublisherTest {

    private final WebSocketEventBroadcaster broadcaster = mock(WebSocketEventBroadcaster.class);
    private final ContactMessageReceivedPublisher publisher = new ContactMessageReceivedPublisher(broadcaster);
    private final UUID tenant = UUID.randomUUID();
    private final UUID contact = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID message = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("bez agenta nic nie wysyla")
    void noAgentSendsNothing() {
        publisher.publish(tenant, contact, null, "EMAIL", message, Instant.now());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("w transakcji: nic przed commit, po commit dokladnie jedno wyslanie")
    void sendsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(tenant, contact, agent, "SOCIAL_FACEBOOK", message, Instant.now());
        verifyNoInteractions(broadcaster);

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);

        verify(broadcaster).sendToUser(org.mockito.ArgumentMatchers.eq(agent), any());
    }

    @Test
    @DisplayName("rollback: nic nie wysyla")
    void rollbackSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(tenant, contact, agent, "EMAIL", message, Instant.now());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(broadcaster, never()).sendToUser(any(), any());
    }

    @Test
    @DisplayName("blad broadcastera nie propaguje wyjatku")
    void failureIsSwallowed() {
        doThrow(new RuntimeException("boom")).when(broadcaster).sendToUser(any(), any());
        assertThatCode(() -> publisher.publish(tenant, contact, agent, "EMAIL", message, Instant.now()))
                .doesNotThrowAnyException();
    }
}
