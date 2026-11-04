package com.contactcenter.domain.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@code CONTACT_MESSAGE_RECEIVED} (BE-154) to the agent currently assigned to the contact.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>unicast only to {@code agentId} (no agent -> nothing is sent);</li>
 *   <li>when a transaction is active the event is sent in {@code afterCommit}, so the frontend never
 *       refetches before the message row is visible; on rollback nothing is sent;</li>
 *   <li>any failure is logged and swallowed - message ingestion is never failed or rolled back.</li>
 * </ul>
 * The caller must pass an agent resolved from a tenant-scoped contact lookup (same tenant by construction).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactMessageReceivedPublisher {

    private final WebSocketEventBroadcaster broadcaster;

    public void publish(UUID tenantId, UUID contactId, UUID agentId, String channel,
                        UUID messageId, Instant receivedAt) {
        if (agentId == null || tenantId == null || contactId == null || messageId == null) {
            log.debug("[WS-MessageReceived] Pomijam - brak agenta/identyfikatorów: contactId={}", contactId);
            return;
        }
        Runnable send = () -> {
            try {
                broadcaster.sendToUser(agentId, WebSocketEvent.contactMessageReceived(
                        tenantId, contactId, channel, messageId, receivedAt));
            } catch (Exception e) {
                log.warn("[WS-MessageReceived] Błąd publikacji: contactId={}, error={}", contactId, e.getMessage());
            }
        };
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        send.run();
                    }
                });
            } else {
                send.run();
            }
        } catch (Exception e) {
            log.warn("[WS-MessageReceived] Błąd rejestracji publikacji: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }
}
