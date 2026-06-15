package com.contactcenter.domain.agentbreak;

import com.contactcenter.api.agentbreak.dto.AgentCalendarResponse;

import java.time.Instant;
import java.util.UUID;

/**
 * Serwis kalendarza agenta (BE-051).
 *
 * <p>Agreguje trzy źródła danych w jedną odpowiedź:
 * <ul>
 *   <li>zaplanowane oddzwonienia agenta ({@code ScheduledCallbackService})</li>
 *   <li>kampanie powiązane z agentem przez kolejkę ({@code CampaignService})</li>
 *   <li>przerwy agenta w zakresie dat ({@code AgentBreakRepository})</li>
 * </ul>
 *
 * <p>Reguły zakresu dat:
 * <ul>
 *   <li>Oba null → bieżący tydzień (poniedziałek 00:00 UTC – niedziela 23:59:59 UTC)</li>
 *   <li>Tylko {@code from} null → poniedziałek tygodnia podanego {@code to}</li>
 *   <li>Tylko {@code to} null → niedziela tygodnia podanego {@code from}</li>
 *   <li>{@code from} po {@code to} → {@link IllegalArgumentException} (HTTP 400)</li>
 *   <li>Zakres > 90 dni → {@link IllegalArgumentException} (HTTP 400)</li>
 * </ul>
 */
public interface AgentCalendarService {

    /**
     * Zwraca zagregowany kalendarz agenta dla podanego zakresu dat.
     *
     * @param agentId  UUID agenta (z JWT)
     * @param tenantId UUID tenanta (z JWT)
     * @param from     początek zakresu (opcjonalny)
     * @param to       koniec zakresu (opcjonalny)
     * @return odpowiedź z listami callbacków, kampanii i przerw
     * @throws IllegalArgumentException gdy from > to lub zakres przekracza 90 dni
     */
    AgentCalendarResponse getCalendar(UUID agentId, UUID tenantId, Instant from, Instant to);
}
