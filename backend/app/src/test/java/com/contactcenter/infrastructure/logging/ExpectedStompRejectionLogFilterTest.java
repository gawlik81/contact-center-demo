package com.contactcenter.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import com.contactcenter.security.StompDestinationAuthorizationInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExpectedStompRejectionLogFilter - wyciszenie duplikatu ERROR Springa (BE-236)")
class ExpectedStompRejectionLogFilterTest {

    private final ExpectedStompRejectionLogFilter filter = new ExpectedStompRejectionLogFilter();
    private final LoggerContext ctx = new LoggerContext();
    private final Logger handlerLogger = ctx.getLogger(ExpectedStompRejectionLogFilter.HANDLER_LOGGER);

    private static final String EXPECTED = ExpectedStompRejectionLogFilter.SPRING_PREFIX + "abc123:"
            + StompDestinationAuthorizationInterceptor.REJECTION_MESSAGE;

    @Test
    @DisplayName("oczekiwane odrzucenie SUBSCRIBE/SEND (ERROR z loggera handlera) jest odrzucane")
    void expectedRejection_denied() {
        assertThat(filter.decide(null, handlerLogger, Level.ERROR, EXPECTED, null, null)).isEqualTo(FilterReply.DENY);
    }

    @Test
    @DisplayName("inny komunikat tego samego loggera (prawdziwy błąd) przechodzi")
    void otherErrorFromSameLogger_passes() {
        assertThat(filter.decide(null, handlerLogger, Level.ERROR,
                ExpectedStompRejectionLogFilter.SPRING_PREFIX + "abc123:Connection reset", null, null))
                .isEqualTo(FilterReply.NEUTRAL);
        assertThat(filter.decide(null, handlerLogger, Level.ERROR, "Error publishing event", null, null))
                .isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("ten sam tekst z innego loggera lub na innym poziomie przechodzi; null format bez NPE")
    void otherLoggerOrLevel_passes() {
        Logger other = ctx.getLogger("com.contactcenter.Other");
        assertThat(filter.decide(null, other, Level.ERROR, EXPECTED, null, null)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(filter.decide(null, handlerLogger, Level.DEBUG, EXPECTED, null, null)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(filter.decide(null, handlerLogger, Level.ERROR, null, null, null)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("BE-233: odrzucenie CONNECT z tokenem z blacklisty / bez weryfikacji blacklisty tez jest wyciszane")
    void connectRejectionsAreDenied() {
        for (String message : new String[]{com.contactcenter.security.WebSocketAuthInterceptor.BLACKLISTED_MESSAGE,
                com.contactcenter.security.WebSocketAuthInterceptor.UNVERIFIABLE_MESSAGE}) {
            assertThat(filter.decide(null, handlerLogger, Level.ERROR,
                    ExpectedStompRejectionLogFilter.SPRING_PREFIX + "abc123:" + message, null, null))
                    .isEqualTo(FilterReply.DENY);
        }
    }
}
