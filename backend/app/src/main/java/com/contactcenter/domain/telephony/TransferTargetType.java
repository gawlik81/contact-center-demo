package com.contactcenter.domain.telephony;

/**
 * Typ celu przekazania połączenia.
 *
 * <p>Określa do jakiego rodzaju celu przekazywane jest połączenie:
 * <ul>
 *   <li>{@link #PHONE} – na konkretny numer telefonu (E.164)</li>
 *   <li>{@link #AGENT} – do innego agenta (identyfikowanego przez UUID)</li>
 *   <li>{@link #QUEUE} – do kolejki (tylko BLIND transfer)</li>
 * </ul>
 */
public enum TransferTargetType {
    /** Przekazanie na numer telefonu. Wymagane pole: {@code phoneNumber}. */
    PHONE,
    /** Przekazanie do agenta. Wymagane pole: {@code agentId}. */
    AGENT,
    /** Przekazanie do kolejki. Wymagane pole: {@code queueId}. Tylko BLIND transfer. */
    QUEUE
}
