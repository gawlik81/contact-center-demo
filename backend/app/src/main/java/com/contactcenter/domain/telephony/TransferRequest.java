package com.contactcenter.domain.telephony;

import java.util.Objects;
import java.util.UUID;

/**
 * Żądanie przekazania połączenia z określonym typem celu.
 *
 * <p>Zastępuje i rozszerza stare API (tylko {@code target} jako String) –
 * pozwala przekazywać połączenia nie tylko na numery telefonu, ale też
 * do agentów ({@link TransferTargetType#AGENT}) i kolejek ({@link TransferTargetType#QUEUE}).
 *
 * <p>Wywołaj {@link #validate()} przed użyciem obiektu, aby upewnić się,
 * że wymagane pola dla danego {@link #targetType()} są wypełnione.
 *
 * @param transferType typ przekazania (BLIND lub ATTENDED)
 * @param targetType   typ celu przekazania (PHONE, AGENT, QUEUE)
 * @param phoneNumber  numer telefonu E.164 – wymagany gdy {@code targetType=PHONE}
 * @param agentId      UUID agenta – wymagany gdy {@code targetType=AGENT}
 * @param queueId      UUID kolejki – wymagany gdy {@code targetType=QUEUE}
 */
public record TransferRequest(
        TelephonyAdapter.TransferType transferType,
        TransferTargetType targetType,
        String phoneNumber,
        UUID agentId,
        UUID queueId
) {

    /**
     * Waliduje spójność pól żądania.
     *
     * @throws NullPointerException     gdy wymagane pole dla danego targetType jest null
     * @throws IllegalArgumentException gdy kombinacja targetType + transferType jest niedozwolona
     */
    public void validate() {
        Objects.requireNonNull(transferType, "transferType is required");
        Objects.requireNonNull(targetType, "targetType is required");

        switch (targetType) {
            case PHONE -> {
                Objects.requireNonNull(phoneNumber, "phoneNumber required for PHONE transfer");
                if (!phoneNumber.matches("^\\+[1-9]\\d{6,14}$")) {
                    throw new IllegalArgumentException(
                            "phoneNumber must be in E.164 format (e.g. +48123456789), got: " + phoneNumber);
                }
            }
            case AGENT -> Objects.requireNonNull(agentId, "agentId required for AGENT transfer");
            case QUEUE -> {
                Objects.requireNonNull(queueId, "queueId required for QUEUE transfer");
                if (transferType == TelephonyAdapter.TransferType.ATTENDED) {
                    throw new IllegalArgumentException("ATTENDED transfer to QUEUE is not supported");
                }
            }
        }
    }
}
