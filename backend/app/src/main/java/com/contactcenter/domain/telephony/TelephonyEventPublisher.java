package com.contactcenter.domain.telephony;

import com.contactcenter.domain.customer.CustomerCliResult;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import org.springframework.amqp.AmqpException;

import java.util.Map;
import java.util.UUID;

/**
 * Serwis publikujący domenowe eventy telefoniczne na RabbitMQ.
 *
 * <p>Exchange: {@code cc.events} (topic).
 * Routing key: {@code call.{eventType}} – np. {@code call.incoming}, {@code call.hangup}.
 *
 * <p>Konwencja routing keys zgodna z {@link RabbitMQConfig#RK_CALL_ALL} ({@code call.#}),
 * więc wszystkie eventy trafiają do {@code cc.queue.call-events}.
 */
public interface TelephonyEventPublisher {

    /**
     * Publikuje event domenowy na RabbitMQ.
     *
     * @param event event do opublikowania
     * @throws AmqpException gdy komunikacja z RabbitMQ nie powiedzie się
     */
    void publish(CallEvent event);

    /**
     * Publikuje zdarzenie CALL_INCOMING (przychodzące połączenie).
     *
     * @param callId    identyfikator sesji połączenia (format providera lub "mock-N")
     * @param contactId UUID rekordu kontaktu w tabeli {@code contact} (null gdy brak rekordu)
     * @param tenantId  UUID tenanta
     * @param agentId   UUID agenta (null gdy brak przypisania)
     * @param from      numer dzwoniącego
     * @param to        numer docelowy
     */
    void publishIncoming(String callId, UUID contactId, UUID tenantId, UUID agentId,
                          String from, String to);

    /**
     * Publikuje zdarzenie CALL_OUTBOUND (wychodzące połączenie kampanijne zainicjowane przez dialer).
     *
     * <p>Routing key: {@code call.outbound}.
     * Używane przez {@code TwilioTelephonyAdapter.initiateCall()} zamiast {@code publishIncoming},
     * aby frontend mógł odróżnić połączenie wychodzące od przychodzącego.
     *
     * @param callId    identyfikator sesji połączenia (Twilio Call SID)
     * @param contactId UUID rekordu kontaktu w tabeli {@code contact}
     * @param tenantId  UUID tenanta
     * @param agentId   UUID agenta inicjującego połączenie
     * @param from      numer wychodzący (Twilio phone number)
     * @param to        numer docelowy (numer klienta)
     */
    void publishOutbound(String callId, UUID contactId, UUID tenantId, UUID agentId,
                          String from, String to);

    /**
     * Publikuje zdarzenie CALL_ANSWERED (połączenie odebrane).
     */
    void publishAnswered(String callId, UUID tenantId, UUID agentId,
                          String from, String to);

    /**
     * Publikuje zdarzenie CALL_HANGUP (połączenie zakończone).
     *
     * @param contactId   UUID rekordu kontaktu w tabeli {@code contact} (null gdy brak rekordu)
     * @param callOutcome wynik połączenia zwrócony przez dostawcę telefonii
     *                    (np. "completed", "no-answer", "busy", "failed", "canceled"); może być null
     */
    void publishHangup(String callId, UUID contactId, UUID tenantId, UUID agentId,
                        String from, String to, String callOutcome);

    /**
     * Publikuje zdarzenie CALL_TRANSFERRED (połączenie przekazane).
     *
     * @param transferTarget numer docelowy przekazania
     * @param transferType   typ przekazania (BLIND/ATTENDED)
     */
    void publishTransferred(String callId, UUID tenantId, UUID agentId,
                             String from, String to,
                             String transferTarget, String transferType);

    /**
     * Publikuje zdarzenie CALL_TRANSFERRED z dodatkowymi metadanymi.
     *
     * <p>Używane przez {@code initiateTransfer()} gdy cel transferu to AGENT lub QUEUE –
     * metadane zawierają wtedy {@code target_type}, {@code target_agent_id} lub {@code target_queue_id}.
     *
     * @param transferTarget symboliczny identyfikator celu (numer E.164, UUID agenta lub UUID kolejki)
     * @param transferType   typ przekazania (BLIND/ATTENDED)
     * @param metadata       dodatkowe metadane specyficzne dla typu celu
     */
    void publishTransferred(String callId, UUID tenantId, UUID agentId,
                             String from, String to,
                             String transferTarget, String transferType,
                             Map<String, String> metadata);

    /**
     * Publikuje zdarzenie CALL_TRANSFER_CONSULT (druga noga attended transfer).
     *
     * <p>Routing key: {@code call.transfer_consult} – pominięty przez IvrCallListener
     * (zbindowany wyłącznie do {@code call.incoming}). Relay wysyła event do docelowego agenta.
     *
     * @param callId              identyfikator drugiej nogi (Twilio SID nowej nogi)
     * @param tenantId            UUID tenanta
     * @param targetAgentId       UUID agenta docelowego (może być null dla transferu na numer)
     * @param originatingAgentId  UUID agenta inicjującego konsultację
     * @param originalContactId   UUID oryginalnego kontaktu (klienta)
     * @param from                numer dzwoniącego (numer klienta)
     * @param to                  cel drugiej nogi (numer E.164 lub "client:agent-{uuid}")
     * @param customerInfo        wynik CLI lookup – imię/nazwisko klienta (może być null)
     */
    void publishTransferConsult(String callId, UUID tenantId, UUID targetAgentId,
                                 UUID originatingAgentId, UUID originalContactId,
                                 String from, String to, CustomerCliResult customerInfo);

    /**
     * Publikuje zdarzenie CALL_CONSULT_CANCELLED (konsultacja anulowana przed bridge).
     *
     * <p>Publikowane gdy Agent1 rozłączy nogę konsultacyjną bez wywołania bridge.
     * Agent2 (targetAgentId) powinien wrócić do statusu AVAILABLE bez ekranu ACW.
     *
     * @param callId            SID nogi konsultacyjnej (CA_...)
     * @param tenantId          UUID tenanta
     * @param targetAgentId     UUID Agent2 (odbiorca eventu)
     * @param originalContactId UUID oryginalnego kontaktu klienta
     * @param from              numer klienta
     * @param to                identyfikator Agent2
     */
    void publishConsultCancelled(String callId, UUID tenantId, UUID targetAgentId,
                                  UUID originalContactId, String from, String to);

    /**
     * Publikuje zdarzenie CALL_CONSULT_ANSWERED (konsultacja odebrana przez cel).
     *
     * <p>Routing key: {@code call.consult_answered}.
     * Publikowane gdy noga konsultacyjna wchodzi w stan in-progress. Adresowane do Agent1
     * (inicjatora konsultacji), który powinien aktywować przycisk "Przekaż".
     *
     * @param callId               SID nogi konsultacyjnej (CA_...)
     * @param tenantId             UUID tenanta
     * @param originatingAgentId   UUID Agent1 (inicjator konsultacji, odbiorca eventu)
     * @param originalContactId    UUID oryginalnego kontaktu klienta
     * @param from                 numer klienta
     * @param to                   identyfikator celu konsultacji
     */
    void publishConsultAnswered(String callId, UUID tenantId, UUID originatingAgentId,
                                 UUID originalContactId, String from, String to);

    /**
     * Publikuje zdarzenie CALL_BRIDGE_COMPLETE (bridge attended transfer zakończony).
     *
     * @param secondLegCallId SID drugiej nogi konsultacji (CA_...)
     * @param newContactId    UUID nowego kontaktu Agent2
     * @param tenantId        UUID tenanta
     * @param targetAgentId   UUID Agent2 (może być null)
     * @param from            numer klienta
     * @param to              identyfikator Agent2
     * @param queueName       nazwa kolejki z oryginalnego kontaktu (może być null)
     */
    void publishBridgeComplete(String secondLegCallId, UUID newContactId, UUID tenantId,
                                UUID targetAgentId, String from, String to, String queueName);

    /**
     * Publikuje zdarzenie CALL_OUTBOUND z dodatkowymi metadanymi.
     *
     * <p>Używane przy attended transfer do agenta – metadane zawierają {@code target_type}
     * i {@code target_agent_id}, które pozwalają frontendowi odróżnić drugą nogę transferu
     * od zwykłego połączenia wychodzącego.
     *
     * @param metadata dodatkowe metadane specyficzne dla kontekstu transferu
     */
    void publishOutbound(String callId, UUID contactId, UUID tenantId, UUID agentId,
                          String from, String to, Map<String, String> metadata);
}
