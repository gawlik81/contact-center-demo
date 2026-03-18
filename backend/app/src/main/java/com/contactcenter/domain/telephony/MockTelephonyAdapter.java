package com.contactcenter.domain.telephony;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock implementacja adaptera telefonii – symuluje providera VoIP w pamięci.
 *
 * <p>Aktywna gdy {@code telephony.provider=mock} (domyślnie, matchIfMissing=true).
 * Przeznaczona wyłącznie dla środowisk dev/test – nie wywołuje żadnych zewnętrznych API.
 *
 * <p>Stan sesji przechowywany w {@link ConcurrentHashMap} – thread-safe, ale ulotny
 * (reset po restarcie aplikacji). W środowisku prod wymagana implementacja z persystencją
 * po stronie providera (Twilio, Vonage).
 *
 * <p>Zdarzenia telefoniczne publikowane są przez {@link TelephonyEventPublisher} na RabbitMQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "telephony.provider", havingValue = "mock", matchIfMissing = true)
public class MockTelephonyAdapter implements TelephonyAdapter {

    /** Rejestr aktywnych sesji: callId → CallSession. */
    private final ConcurrentHashMap<String, CallSession> sessions = new ConcurrentHashMap<>();

    /** Licznik do generowania unikalnych callId w formacie mock-{N}. */
    private final AtomicLong callIdCounter = new AtomicLong(1);

    private final TelephonyEventPublisher eventPublisher;

    // =========================================================================
    // TelephonyAdapter implementation
    // =========================================================================

    @Override
    public CallSession initiateCall(UUID tenantId, String from, String to, UUID agentId) {
        String callId = generateCallId();

        CallSession session = CallSession.builder()
                .callId(callId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .status(CallSession.CallStatus.RINGING)
                .startedAt(Instant.now())
                .build();

        sessions.put(callId, session);

        log.info("[MockTelephony] Połączenie inicjowane: callId={}, from={}, to={}, tenant={}",
                callId, from, to, tenantId);

        eventPublisher.publishIncoming(callId, tenantId, agentId, from, to);

        return session;
    }

    @Override
    public void answerCall(String callId) {
        CallSession session = requireSession(callId);

        if (session.getStatus() == CallSession.CallStatus.ENDED) {
            throw new TelephonyException(callId, "Nie można odebrać zakończonego połączenia: " + callId);
        }
        if (session.getStatus() == CallSession.CallStatus.ACTIVE) {
            log.debug("[MockTelephony] Połączenie {} już odebrane, ignoruję answerCall", callId);
            return;
        }

        CallSession updated = session
                .withStatus(CallSession.CallStatus.ACTIVE)
                .withAnsweredAt(Instant.now());

        sessions.put(callId, updated);

        log.info("[MockTelephony] Połączenie odebrane: callId={}, tenant={}",
                callId, updated.getTenantId());

        eventPublisher.publishAnswered(
                callId, updated.getTenantId(), updated.getAgentId(),
                updated.getFrom(), updated.getTo()
        );
    }

    @Override
    public void hangupCall(String callId) {
        CallSession session = requireSession(callId);

        // Idempotentne – zakończone połączenie nie rzuca
        if (session.getStatus() == CallSession.CallStatus.ENDED) {
            log.debug("[MockTelephony] Połączenie {} już zakończone, ignoruję hangupCall", callId);
            return;
        }

        CallSession updated = session
                .withStatus(CallSession.CallStatus.ENDED)
                .withEndedAt(Instant.now());

        sessions.put(callId, updated);

        log.info("[MockTelephony] Połączenie zakończone: callId={}, tenant={}",
                callId, updated.getTenantId());

        eventPublisher.publishHangup(
                callId, updated.getTenantId(), updated.getAgentId(),
                updated.getFrom(), updated.getTo()
        );
    }

    @Override
    public void holdCall(String callId, boolean hold) {
        CallSession session = requireSession(callId);

        CallSession.CallStatus expectedStatus = hold
                ? CallSession.CallStatus.ACTIVE
                : CallSession.CallStatus.ON_HOLD;

        if (session.getStatus() != expectedStatus) {
            throw new TelephonyException(callId,
                    String.format("Nie można %s połączenia %s w stanie %s",
                            hold ? "wstrzymać" : "wznowić", callId, session.getStatus()));
        }

        CallSession.CallStatus newStatus = hold
                ? CallSession.CallStatus.ON_HOLD
                : CallSession.CallStatus.ACTIVE;

        sessions.put(callId, session.withStatus(newStatus));

        log.info("[MockTelephony] Hold: callId={}, hold={}, newStatus={}", callId, hold, newStatus);
    }

    @Override
    public void muteCall(String callId, boolean mute) {
        CallSession session = requireSession(callId);

        if (session.getStatus() != CallSession.CallStatus.ACTIVE
                && session.getStatus() != CallSession.CallStatus.ON_HOLD) {
            throw new TelephonyException(callId,
                    "Nie można wyciszyć nieaktywnego połączenia: " + callId);
        }

        // Mock – stan mute nie jest przechowywany w CallSession (brak pola),
        // tylko logujemy operację
        log.info("[MockTelephony] Mute: callId={}, mute={}", callId, mute);
    }

    @Override
    public CallSession transferCall(String callId, String target, TransferType transferType) {
        CallSession session = requireSession(callId);

        if (session.getStatus() != CallSession.CallStatus.ACTIVE
                && session.getStatus() != CallSession.CallStatus.ON_HOLD) {
            throw new TelephonyException(callId,
                    "Przekazanie możliwe tylko dla połączenia ACTIVE lub ON_HOLD. Aktualny status: "
                            + session.getStatus());
        }

        if (transferType == TransferType.BLIND) {
            // Blind transfer: od razu oznaczamy oryginalne połączenie jako TRANSFERRED
            CallSession transferred = session
                    .withStatus(CallSession.CallStatus.TRANSFERRED)
                    .withEndedAt(Instant.now());

            sessions.put(callId, transferred);

            log.info("[MockTelephony] Blind transfer: callId={}, target={}, tenant={}",
                    callId, target, session.getTenantId());

            eventPublisher.publishTransferred(
                    callId, session.getTenantId(), session.getAgentId(),
                    session.getFrom(), session.getTo(),
                    target, transferType.name()
            );

            return transferred;

        } else {
            // Attended transfer: tworzymy drugą nogę do target
            // Oryginalne połączenie przechodzimy na ON_HOLD
            sessions.put(callId, session.withStatus(CallSession.CallStatus.ON_HOLD));

            String secondLegCallId = generateCallId();
            CallSession secondLeg = CallSession.builder()
                    .callId(secondLegCallId)
                    .tenantId(session.getTenantId())
                    .agentId(session.getAgentId())
                    .from(session.getTo())   // agent dzwoni do target
                    .to(target)
                    .status(CallSession.CallStatus.RINGING)
                    .startedAt(Instant.now())
                    .build();

            sessions.put(secondLegCallId, secondLeg);

            log.info("[MockTelephony] Attended transfer – 2nd leg: callId={}, secondLegCallId={}, target={}",
                    callId, secondLegCallId, target);

            eventPublisher.publishIncoming(
                    secondLegCallId, session.getTenantId(), session.getAgentId(),
                    secondLeg.getFrom(), target
            );

            return secondLeg;
        }
    }

    @Override
    public void bridgeCalls(String callId1, String callId2) {
        CallSession session1 = requireSession(callId1);
        CallSession session2 = requireSession(callId2);

        // Obie sesje muszą być aktywne lub na hold
        validateBridgeable(session1);
        validateBridgeable(session2);

        // Po bridge: pierwsza sesja zakończona (przekazana), druga aktywna
        CallSession transferred = session1
                .withStatus(CallSession.CallStatus.TRANSFERRED)
                .withEndedAt(Instant.now());

        CallSession active = session2.withStatus(CallSession.CallStatus.ACTIVE);

        sessions.put(callId1, transferred);
        sessions.put(callId2, active);

        log.info("[MockTelephony] Bridge: callId1={} (TRANSFERRED), callId2={} (ACTIVE)",
                callId1, callId2);

        eventPublisher.publishTransferred(
                callId1, session1.getTenantId(), session1.getAgentId(),
                session1.getFrom(), session1.getTo(),
                session2.getTo(), TelephonyAdapter.TransferType.ATTENDED.name()
        );
    }

    @Override
    public CallSession getCallSession(String callId) {
        return requireSession(callId);
    }

    // =========================================================================
    // Metody pomocnicze dla dev (nie są częścią interfejsu)
    // =========================================================================

    /**
     * Symuluje przychodzące połączenie od zewnętrznego numeru.
     * Używane przez MockCallController (profil dev).
     *
     * @param tenantId  identyfikator tenanta
     * @param from      numer dzwoniącego
     * @param to        numer odbierającego (numer tenant)
     * @param agentId   agent któremu przydzielono połączenie (może być null)
     * @return sesja połączenia
     */
    public CallSession simulateIncomingCall(UUID tenantId, String from, String to, UUID agentId) {
        String callId = generateCallId();

        CallSession session = CallSession.builder()
                .callId(callId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .status(CallSession.CallStatus.RINGING)
                .startedAt(Instant.now())
                .build();

        sessions.put(callId, session);

        log.info("[MockTelephony] Symulacja przychodzącego: callId={}, from={}, to={}", callId, from, to);

        eventPublisher.publishIncoming(callId, tenantId, agentId, from, to);

        return session;
    }

    /**
     * Zwraca liczbę aktywnych sesji (dla testów i monitoringu).
     */
    public int getActiveSessionCount() {
        return (int) sessions.values().stream()
                .filter(s -> s.getStatus() != CallSession.CallStatus.ENDED
                        && s.getStatus() != CallSession.CallStatus.TRANSFERRED)
                .count();
    }

    // =========================================================================
    // Prywatne metody pomocnicze
    // =========================================================================

    private CallSession requireSession(String callId) {
        CallSession session = sessions.get(callId);
        if (session == null) {
            throw new TelephonyException(callId, "Sesja połączenia nie istnieje: " + callId);
        }
        return session;
    }

    private void validateBridgeable(CallSession session) {
        if (session.getStatus() != CallSession.CallStatus.ACTIVE
                && session.getStatus() != CallSession.CallStatus.ON_HOLD) {
            throw new TelephonyException(session.getCallId(),
                    "Sesja nie może być bridgowana w stanie: " + session.getStatus());
        }
    }

    private String generateCallId() {
        return "mock-" + callIdCounter.getAndIncrement();
    }
}
