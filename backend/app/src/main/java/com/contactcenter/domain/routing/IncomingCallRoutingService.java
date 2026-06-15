package com.contactcenter.domain.routing;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Serwis rozwiązywania trasy dla przychodzącego połączenia głosowego.
 *
 * <p>Algorytm:
 * <ol>
 *   <li>Pobiera strefę czasową tenanta z konfiguracji JSONB</li>
 *   <li>Konwertuje czas połączenia do lokalnej strefy czasowej tenanta</li>
 *   <li>Wyszukuje konfigurację numeru docelowego ({@code phone_number})</li>
 *   <li>Iteruje po aktywnych regułach harmonogramu ({@code phone_routing_rule})
 *       posortowanych po {@code time_start} ASC</li>
 *   <li>Zwraca pierwszą pasującą regułę (dzień tygodnia + okno czasowe)</li>
 *   <li>Gdy brak pasującej reguły lub nieznany numer → {@link RouteResult#reject()}</li>
 * </ol>
 *
 * <p>Weryfikacja zakresu czasowego:
 * <ul>
 *   <li>{@code timeStart} jest <strong>inclusive</strong> (time >= timeStart)</li>
 *   <li>{@code timeEnd} jest <strong>exclusive</strong> (time < timeEnd)</li>
 * </ul>
 */
public interface IncomingCallRoutingService {

    /**
     * Rozwiązuje trasę dla przychodzącego połączenia.
     *
     * @param tenantId     UUID tenanta
     * @param calledNumber numer docelowy w formacie E.164 (parametr {@code To} z Twilio)
     * @param callTime     czas odebrania połączenia (Twilio wysyła UTC)
     * @return {@link RouteResult} wskazujący docelowe IVR, kolejkę lub odrzucenie
     */
    RouteResult resolveRoute(UUID tenantId, String calledNumber, ZonedDateTime callTime);
}
