package com.contactcenter.domain.ivr;

/**
 * Magazyn stanu oczekującego IVR ({@link IvrPendingStateStore}) jest niedostępny - nie udało się zapisać stanu
 * wymaganego przez kolejny webhook (BE-177). Wyjątek techniczny (nie trafia do klienta HTTP).
 */
class IvrPendingStateUnavailableException extends RuntimeException {

    IvrPendingStateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
