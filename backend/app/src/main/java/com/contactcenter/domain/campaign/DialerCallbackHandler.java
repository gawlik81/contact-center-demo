package com.contactcenter.domain.campaign;

import java.time.Instant;
import java.util.UUID;

/**
 * Handler wyników połączeń inicjowanych przez Progressive Dialer.
 *
 * <p>Obsługuje trzy scenariusze zakończenia połączenia kampanijnego:
 * <ol>
 *   <li><strong>NO_ANSWER</strong> – brak odpowiedzi po 30s timeout: oznacza rekord
 *       campaign_contact jako NO_ANSWER i ustawia next_attempt_at na teraz + 4h.</li>
 *   <li><strong>ANSWERED</strong> – klient odebrał: aktualizuje status rekordu na CONNECTED
 *       i przekazuje połączenie agentowi przez TelephonyAdapter.</li>
 *   <li><strong>CALLBACK disposition</strong> – agent ustawił dyspozycję CALLBACK:
 *       tworzy rekord {@link ScheduledCallback} z datą/godziną wybraną przez agenta.</li>
 * </ol>
 *
 * <p>Wejście do handlera może być:
 * <ul>
 *   <li>Event RabbitMQ z routingiem {@code call.hangup} lub {@code call.answered}
 *       (gdy provider telefonii obsługuje eventy stanu połączenia)</li>
 *   <li>Wywołanie bezpośrednie przez {@link ProgressiveDialerService} lub kontroler
 *       po otrzymaniu dyspozycji od agenta</li>
 * </ul>
 */
public interface DialerCallbackHandler {

    /**
     * Tworzy zaplanowane oddzwonienie po dyspozycji agenta CALLBACK.
     *
     * <p>Aktualizuje status rekordu campaign_contact na CALLBACK
     * i tworzy nowy rekord {@link ScheduledCallback} z podaną datą i godziną.
     *
     * @param tenantId          UUID tenanta
     * @param campaignId        UUID kampanii
     * @param recordId          UUID rekordu campaign_contact
     * @param agentId           UUID agenta ustawiającego dyspozycję
     * @param phone             numer telefonu do oddzwonienia
     * @param firstName         imię klienta (może być null)
     * @param lastName          nazwisko klienta (może być null)
     * @param scheduledAt       zaplanowany moment oddzwonienia
     * @param notes             notatka agenta (może być null)
     * @return utworzony rekord ScheduledCallback
     */
    ScheduledCallback handleCallbackDisposition(UUID tenantId, UUID campaignId,
                                                  UUID recordId, UUID agentId,
                                                  String phone, String firstName, String lastName,
                                                  Instant scheduledAt, String notes);
}
