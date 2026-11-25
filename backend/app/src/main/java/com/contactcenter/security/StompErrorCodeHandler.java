package com.contactcenter.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Dopisuje stabilny nagłówek {@value WsErrorCode#HEADER} do ramek STOMP {@code ERROR} (BE-236).
 *
 * <p>Obsługuje obie ścieżki Springa: {@link #handleClientMessageProcessingError} (ramka klienta odrzucona przez
 * interceptor lub nieparsowalna) i {@link #handleErrorMessageToClient} (ramka ERROR wysyłana do klienta przez
 * aplikację/broker; dostaje {@code code} tylko gdy go nie ma, domyślnie {@link WsErrorCode#WS_INTERNAL}).
 *
 * <p>Odrzucenie ramki przez interceptor kończy się {@code MessageDeliveryException} (lub bezpośrednio
 * {@link StompRejectedException}); przyczynę odnajdujemy w łańcuchu {@code getCause()} ({@link CauseChains}).
 * Brak {@link StompRejectedException} w łańcuchu = {@link WsErrorCode#WS_INTERNAL}: ramka dostaje wtedy ogólny
 * komunikat bez szczegółów wyjątku. Logowanie:
 * <ul>
 *   <li>wyjątek spoza {@link StompRejectedException} przy poprawnie sparsowanej ramce = naprawdę nieoczekiwany:
 *       ERROR ze stack trace (prawdziwe błędy nie giną, nawet gdy oczekiwane odrzucenia są wyciszane w logu Springa -
 *       patrz {@code ExpectedStompRejectionLogFilter});</li>
 *   <li>ramka nieparsowalna ({@code clientMessage == null}, błąd protokołu klienta, a endpointy są publiczne):
 *       kod nadal {@code WS_INTERNAL} (kontrakt ma 6 kodów), ale log to WARN bez stack trace, max 1 wpis / 10 s
 *       (ochrona przed zalewaniem logów przez nieuwierzytelnionego klienta).</li>
 * </ul>
 *
 * <p>Nagłówek {@code message} dla kodów oczekiwanych pozostaje bez zmian (kompatybilność wsteczna do FE-171).
 */
@Slf4j
public class StompErrorCodeHandler extends StompSubProtocolErrorHandler {

    /** Ogólny komunikat dla błędów nieoczekiwanych - bez szczegółów wewnętrznych. */
    static final String INTERNAL_MESSAGE = "Wewnętrzny błąd przetwarzania ramki STOMP";

    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final LongSupplier nanoClock;
    private final AtomicLong lastBadFrameWarn;

    public StompErrorCodeHandler() {
        this(System::nanoTime);
    }

    StompErrorCodeHandler(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.lastBadFrameWarn = new AtomicLong(nanoClock.getAsLong() - WARN_INTERVAL_NANOS);
    }

    @Override
    @Nullable
    public Message<byte[]> handleClientMessageProcessingError(@Nullable Message<byte[]> clientMessage, Throwable ex) {
        Message<byte[]> base = super.handleClientMessageProcessingError(clientMessage, ex);
        if (base == null) {
            return null;
        }
        WsErrorCode code = resolveCode(ex);
        boolean sanitizeMessage = false;
        if (code == WsErrorCode.WS_INTERNAL) {
            sanitizeMessage = true;
            if (clientMessage == null) {
                warnBadFrame(ex);
            } else if (isExpectedRejection(ex)) {
                // oczekiwane odrzucenie z kodem WS_INTERNAL (np. blacklista niedostępna przy CONNECT, BE-233):
                // interceptor loguje je sam, z ograniczeniem częstotliwości - bez ERROR + stack trace na każdą ramkę
                log.debug("[WS-Error] Odrzucenie ramki -> {}", code);
            } else {
                log.error("[WS-Error] Nieoczekiwany błąd przetwarzania ramki STOMP -> {}", code, ex);
            }
        }
        return withCode(base, code, sanitizeMessage);
    }

    @Override
    @Nullable
    public Message<byte[]> handleErrorMessageToClient(Message<byte[]> errorMessage) {
        Message<byte[]> base = super.handleErrorMessageToClient(errorMessage);
        if (base == null) {
            return null;
        }
        // kod z wcześniejszego etapu (np. ERROR zbudowany przez aplikację z własnym code) zostaje bez zmian
        if (StompHeaderAccessor.wrap(base).getFirstNativeHeader(WsErrorCode.HEADER) != null) {
            return base;
        }
        return withCode(base, WsErrorCode.WS_INTERNAL, false);
    }

    private static Message<byte[]> withCode(Message<byte[]> base, WsErrorCode code, boolean sanitizeMessage) {
        // wrap() daje mutowalną kopię nagłówków (pułapka: bez tego setNativeHeader rzuca na zamrożonych nagłówkach)
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(base);
        accessor.setNativeHeader(WsErrorCode.HEADER, code.name());
        if (sanitizeMessage) {
            accessor.setMessage(INTERNAL_MESSAGE);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(base.getPayload(), accessor.getMessageHeaders());
    }

    /** WARN bez stack trace, max 1 / 10 s; w logu tylko klasa wyjątku i zsanityzowana, przycięta treść. */
    private void warnBadFrame(Throwable ex) {
        long now = nanoClock.getAsLong();
        long prev = lastBadFrameWarn.get();
        if (now - prev >= WARN_INTERVAL_NANOS && lastBadFrameWarn.compareAndSet(prev, now)) {
            log.warn("[WS-Error] Nieparsowalna ramka STOMP od klienta -> WS_INTERNAL (log ograniczony do 1/10 s): {}: {}",
                    ex.getClass().getSimpleName(), StompDestinationAuthorizationInterceptor.sanitize(ex.getMessage()));
        }
    }

    private static boolean isExpectedRejection(@Nullable Throwable ex) {
        return CauseChains.of(ex).stream().anyMatch(StompRejectedException.class::isInstance);
    }

    /** Pierwszy {@link StompRejectedException} w łańcuchu przyczyn lub {@link WsErrorCode#WS_INTERNAL}. */
    static WsErrorCode resolveCode(@Nullable Throwable ex) {
        for (Throwable t : CauseChains.of(ex)) {
            if (t instanceof StompRejectedException rejected) {
                return rejected.getCode();
            }
        }
        return WsErrorCode.WS_INTERNAL;
    }
}
