package com.contactcenter.domain.routing;

import java.util.UUID;

/**
 * Wynik rozwiązania trasy dla przychodzącego połączenia.
 *
 * <p>Trzy możliwe typy trasy:
 * <ul>
 *   <li>{@link RouteType#IVR}   – przekieruj do konkretnego drzewa IVR ({@code targetId} = ivrTreeId)</li>
 *   <li>{@link RouteType#QUEUE} – przekieruj bezpośrednio do kolejki ({@code targetId} = queueId)</li>
 *   <li>{@link RouteType#REJECT} – odrzuć połączenie ({@code targetId} = null)</li>
 * </ul>
 */
public record RouteResult(RouteType type, UUID targetId) {

    public enum RouteType {
        IVR, QUEUE, REJECT
    }

    /**
     * Przekierowanie do konkretnego drzewa IVR.
     *
     * @param ivrTreeId UUID drzewa IVR
     * @return RouteResult z typem IVR
     */
    public static RouteResult ivr(UUID ivrTreeId) {
        return new RouteResult(RouteType.IVR, ivrTreeId);
    }

    /**
     * Przekierowanie bezpośrednio do kolejki.
     *
     * @param queueId UUID kolejki
     * @return RouteResult z typem QUEUE
     */
    public static RouteResult queue(UUID queueId) {
        return new RouteResult(RouteType.QUEUE, queueId);
    }

    /**
     * Odrzucenie połączenia (brak pasującej reguły lub nieznany numer).
     *
     * @return RouteResult z typem REJECT
     */
    public static RouteResult reject() {
        return new RouteResult(RouteType.REJECT, null);
    }

    public boolean isReject() {
        return type == RouteType.REJECT;
    }

    public boolean isIvr() {
        return type == RouteType.IVR;
    }

    public boolean isQueue() {
        return type == RouteType.QUEUE;
    }
}
