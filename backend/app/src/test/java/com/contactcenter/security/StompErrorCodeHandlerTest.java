package com.contactcenter.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StompErrorCodeHandler - nagłówek code w ramce ERROR (BE-236)")
class StompErrorCodeHandlerTest {

    private final StompErrorCodeHandler handler = new StompErrorCodeHandler();

    private static Message<byte[]> clientFrame(String receipt) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/x");
        if (receipt != null) {
            accessor.setReceipt(receipt);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static StompHeaderAccessor errorOf(Message<byte[]> msg) {
        return StompHeaderAccessor.wrap(msg);
    }

    @Test
    @DisplayName("każdy kod oczekiwanego odrzucenia trafia do nagłówka code, a message pozostaje bez zmian")
    void expectedRejections_carryCode_andKeepMessage() {
        for (WsErrorCode code : WsErrorCode.values()) {
            if (code == WsErrorCode.WS_INTERNAL) {
                continue;
            }
            Message<byte[]> error = handler.handleClientMessageProcessingError(clientFrame(null),
                    new StompRejectedException(code, "tekst-" + code));

            StompHeaderAccessor accessor = errorOf(error);
            assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
            assertThat(accessor.getFirstNativeHeader("code")).isEqualTo(code.name());
            assertThat(accessor.getMessage()).isEqualTo("tekst-" + code);
        }
    }

    @Test
    @DisplayName("rozpakowuje przyczynę zagnieżdżoną na dowolnym poziomie (MessageDeliveryException -> ... -> StompRejectedException)")
    void unwrapsNestedCauses() {
        Throwable nested = new MessageDeliveryException(clientFrame(null),
                new IllegalStateException("wrapper", new RuntimeException("another",
                        new StompRejectedException(WsErrorCode.WS_TOKEN_EXPIRED, "wygasl"))));

        Message<byte[]> error = handler.handleClientMessageProcessingError(clientFrame(null), nested);

        assertThat(errorOf(error).getFirstNativeHeader("code")).isEqualTo("WS_TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("nieznany wyjątek -> WS_INTERNAL z ogólnym message, bez szczegółów wyjątku")
    void unknownException_becomesInternal_withoutLeakingDetails() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(clientFrame(null),
                new MessagingException("password=hunter2 jdbc:postgresql://db/secret"));

        StompHeaderAccessor accessor = errorOf(error);
        assertThat(accessor.getFirstNativeHeader("code")).isEqualTo("WS_INTERNAL");
        assertThat(accessor.getMessage()).isEqualTo(StompErrorCodeHandler.INTERNAL_MESSAGE)
                .doesNotContain("hunter2").doesNotContain("jdbc");
    }

    @Test
    @DisplayName("brak wiadomości klienta (błąd parsowania) i wyjątek z null message -> WS_INTERNAL, bez NPE")
    void nullClientMessageAndNullMessageException() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(null, new RuntimeException());

        assertThat(errorOf(error).getFirstNativeHeader("code")).isEqualTo("WS_INTERNAL");
    }

    @Test
    @DisplayName("handleErrorMessageToClient: ERROR bez code dostaje WS_INTERNAL, istniejący code zostaje")
    void errorMessageToClient_getsDefaultCode_orKeepsExisting() {
        StompHeaderAccessor plain = StompHeaderAccessor.create(StompCommand.ERROR);
        plain.setMessage("app error");
        plain.setLeaveMutable(true);
        Message<byte[]> out = handler.handleErrorMessageToClient(
                MessageBuilder.createMessage(new byte[0], plain.getMessageHeaders()));
        assertThat(errorOf(out).getFirstNativeHeader("code")).isEqualTo("WS_INTERNAL");
        assertThat(errorOf(out).getMessage()).isEqualTo("app error");

        StompHeaderAccessor coded = StompHeaderAccessor.create(StompCommand.ERROR);
        coded.setMessage("x");
        coded.setNativeHeader("code", "WS_FORBIDDEN");
        coded.setLeaveMutable(true);
        Message<byte[]> kept = handler.handleErrorMessageToClient(
                MessageBuilder.createMessage(new byte[0], coded.getMessageHeaders()));
        assertThat(errorOf(kept).getFirstNativeHeader("code")).isEqualTo("WS_FORBIDDEN");
    }

    @Test
    @DisplayName("nieparsowalna ramka: WS_INTERNAL, tylko WARN bez stack trace i max 1 / 10 s (brak ERROR); "
            + "poprawna ramka z nieoczekiwanym wyjątkiem: ERROR ze stack trace")
    void badFrameIsRateLimitedWarn_unexpectedExceptionIsError() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(StompErrorCodeHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            long[] now = {0};
            StompErrorCodeHandler limited = new StompErrorCodeHandler(() -> now[0]);
            for (int i = 0; i < 100; i++) {
                Message<byte[]> out = limited.handleClientMessageProcessingError(null, new RuntimeException("junk"));
                assertThat(errorOf(out).getFirstNativeHeader("code")).isEqualTo("WS_INTERNAL");
            }
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(appender.list.get(0).getThrowableProxy()).isNull();

            now[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(11);
            limited.handleClientMessageProcessingError(null, new RuntimeException("junk"));
            assertThat(appender.list).hasSize(2);

            appender.list.clear();
            limited.handleClientMessageProcessingError(clientFrame(null), new RuntimeException("boom"));
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            assertThat(appender.list.get(0).getThrowableProxy()).isNotNull();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("receipt-id klienta jest zachowany (zachowanie bazowe nietknięte)")
    void receiptIdPreserved() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(clientFrame("r-1"),
                new StompRejectedException(WsErrorCode.WS_FORBIDDEN, "x"));

        assertThat(errorOf(error).getReceiptId()).isEqualTo("r-1");
    }

    @Test
    @DisplayName("cykl w łańcuchu przyczyn nie zapętla rozpakowania")
    void cyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(StompErrorCodeHandler.resolveCode(a)).isEqualTo(WsErrorCode.WS_INTERNAL);
    }

    @Test
    @DisplayName("zestaw kodów i nazwa nagłówka to kontrakt z FE - zmiana wymaga uzgodnienia")
    void contractIsStable() {
        assertThat(WsErrorCode.HEADER).isEqualTo("code");
        assertThat(WsErrorCode.values()).extracting(Enum::name).containsExactly(
                "WS_FORBIDDEN", "WS_NO_SESSION", "WS_TOKEN_MISSING", "WS_TOKEN_EXPIRED", "WS_TOKEN_INVALID",
                "WS_INTERNAL");
    }
}
