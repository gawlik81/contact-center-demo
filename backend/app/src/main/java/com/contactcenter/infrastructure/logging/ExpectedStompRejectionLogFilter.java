package com.contactcenter.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.contactcenter.security.StompDestinationAuthorizationInterceptor;
import org.slf4j.Marker;

/**
 * Wycisza DUPLIKAT logu przy oczekiwanym odrzuceniu ramki SUBSCRIBE/SEND (BE-236).
 *
 * <p>Przy odrzuceniu ramki przez {@link StompDestinationAuthorizationInterceptor} Spring
 * ({@code StompSubProtocolHandler}) loguje dodatkowo na ERROR
 * {@code "Failed to send message to MessageChannel in session X:<message>"} (bez stack trace, poziomu nie da się
 * zmienić konfiguracją - to jedno wywołanie {@code logger.error}). Odrzucenie jest już raportowane przez
 * interceptor (WARN z ograniczeniem 1/10 s na powód + licznik {@code ws.stomp.rejected}), więc ten ERROR jest
 * szumem w alertach. Filtr odrzuca WYŁĄCZNIE wpis, który jednocześnie: pochodzi z loggera
 * {@code StompSubProtocolHandler}, ma poziom ERROR, zaczyna się od znanego prefiksu i kończy stałym komunikatem
 * odrzucenia ({@link StompDestinationAuthorizationInterceptor#REJECTION_MESSAGE}). Wszystkie inne błędy tego
 * loggera (w tym {@code WS_INTERNAL}, które loguje {@code StompErrorCodeHandler} ze stack trace) pozostają bez zmian.
 *
 * <p>Kompromis: filtr dopasowuje tekst komunikatu Springa; zmiana tego tekstu w nowej wersji Springa tylko
 * przywróci dodatkowy wpis ERROR (fail-safe - nic nie zniknie).
 */
public class ExpectedStompRejectionLogFilter extends TurboFilter {

    static final String HANDLER_LOGGER = "org.springframework.web.socket.messaging.StompSubProtocolHandler";
    static final String SPRING_PREFIX = "Failed to send message to MessageChannel in session ";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (Level.ERROR.equals(level)
                && format != null
                && HANDLER_LOGGER.equals(logger.getName())
                && format.startsWith(SPRING_PREFIX)
                && format.endsWith(StompDestinationAuthorizationInterceptor.REJECTION_MESSAGE)) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
