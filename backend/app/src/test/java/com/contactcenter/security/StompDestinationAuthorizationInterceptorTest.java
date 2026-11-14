package com.contactcenter.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("StompDestinationAuthorizationInterceptor - allow-lista SUBSCRIBE/SEND (BE-229)")
class StompDestinationAuthorizationInterceptorTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_USER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final MessageChannel channel = mock(MessageChannel.class);
    private SimpleMeterRegistry meters;
    private StompDestinationAuthorizationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        interceptor = new StompDestinationAuthorizationInterceptor(meters);
    }

    private static Message<byte[]> frame(StompCommand command, String destination, StompPrincipal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("s1");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (principal != null) {
            accessor.setUser(principal);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static StompPrincipal principal(String role) {
        return new StompPrincipal(USER, TENANT, role);
    }

    private Message<?> subscribe(String destination, StompPrincipal principal) {
        return interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination, principal), channel);
    }

    private double rejected(String command, String reason) {
        var c = meters.find(StompDestinationAuthorizationInterceptor.METRIC_REJECTED)
                .tags("command", command, "reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    // ---- SUBSCRIBE: dozwolone ----

    @ParameterizedTest(name = "{0} subskrybuje wlasny user topic")
    @ValueSource(strings = {"AGENT", "SUPERVISOR", "ADMIN", "SUPER_ADMIN"})
    void ownUserTopicAllowedForEveryRole(String role) {
        assertThat(subscribe("/topic/user/" + USER + "/events", principal(role))).isNotNull();
    }

    @ParameterizedTest(name = "{0} subskrybuje agents wlasnego tenanta")
    @ValueSource(strings = {"AGENT", "SUPERVISOR", "ADMIN"})
    void ownTenantAgentsAllowed(String role) {
        assertThat(subscribe("/topic/tenant/" + TENANT + "/agents", principal(role))).isNotNull();
    }

    @ParameterizedTest(name = "{0} subskrybuje {1} wlasnego tenanta")
    @CsvSource({"SUPERVISOR,supervisor", "ADMIN,supervisor"})
    void supervisorTopicsAllowedForSupervisorAndAdmin(String role, String suffix) {
        assertThat(subscribe("/topic/tenant/" + TENANT + "/" + suffix, principal(role))).isNotNull();
    }

    // ---- SUBSCRIBE: odrzucone ----

    @ParameterizedTest(name = "{0} NIE moze subskrybowac {1}")
    @CsvSource({"AGENT,supervisor"})
    void supervisorTopicsRejectedForOtherRoles(String role, String suffix) {
        assertThatThrownBy(() -> subscribe("/topic/tenant/" + TENANT + "/" + suffix, principal(role)))
                .isInstanceOf(MessagingException.class);
        assertThat(rejected("SUBSCRIBE", "insufficient_role")).isEqualTo(1);
    }

    @ParameterizedTest(name = "/supervisor/metrics nie jest na allow-list ({0})")
    @ValueSource(strings = {"SUPERVISOR", "ADMIN"})
    void supervisorMetricsSubtopicNoLongerAllowed(String role) {
        assertThatThrownBy(() -> subscribe("/topic/tenant/" + TENANT + "/supervisor/metrics", principal(role)))
                .isInstanceOf(MessagingException.class);
        assertThat(rejected("SUBSCRIBE", "destination_not_allowed")).isEqualTo(1);
    }

    @Test
    @DisplayName("cudzy tenant - agents i supervisor odrzucone, licznik foreign_tenant")
    void foreignTenantRejected() {
        assertThatThrownBy(() -> subscribe("/topic/tenant/" + OTHER_TENANT + "/agents", principal("AGENT")))
                .isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> subscribe("/topic/tenant/" + OTHER_TENANT + "/supervisor", principal("ADMIN")))
                .isInstanceOf(MessagingException.class);
        assertThat(rejected("SUBSCRIBE", "foreign_tenant")).isEqualTo(2);
    }

    @Test
    @DisplayName("cudzy user topic odrzucony")
    void foreignUserRejected() {
        assertThatThrownBy(() -> subscribe("/topic/user/" + OTHER_USER + "/events", principal("ADMIN")))
                .isInstanceOf(MessagingException.class);
        assertThat(rejected("SUBSCRIBE", "foreign_user")).isEqualTo(1);
    }

    @Test
    @DisplayName("principal bez tenanta (SUPER_ADMIN) nie dostaje zadnego topicu tenanta")
    void principalWithoutTenantRejectedOnTenantTopics() {
        StompPrincipal superAdmin = new StompPrincipal(USER, null, "SUPER_ADMIN");
        assertThatThrownBy(() -> subscribe("/topic/tenant/" + TENANT + "/agents", superAdmin))
                .isInstanceOf(MessagingException.class);
        assertThat(subscribe("/topic/user/" + USER + "/events", superAdmin)).isNotNull();
    }

    @ParameterizedTest(name = "odrzucona destynacja: {0}")
    @ValueSource(strings = {
            "/topic/**", "/topic/*", "/topic/#", "/topic", "/topic/", "/topic/user/*/events",
            "/topic/user/**", "/topic/tenant/*/agents", "/topic/tenant/**", "/topic/tenant/?/agents",
            "/topic/tenant/{id}/agents", "/topic/user/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/*",
            "/topic/user/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/events/",
            "/topic/user/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/events/extra",
            "/topic/user/AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA/events",
            "/topic/tenant/11111111-1111-1111-1111-111111111111/agents/**",
            "/topic/tenant/11111111-1111-1111-1111-111111111111/supervisor/metrics/x",
            "/topic/tenant/11111111-1111-1111-1111-111111111111/supervisor/metrics",
            "/topic/tenant/11111111-1111-1111-1111-111111111111/other",
            "/topic/tenant.11111111-1111-1111-1111-111111111111.agents",
            "/topic/simp-user-registry", "/topic/unresolved-user-destination",
            "/user/events", "/user/queue/events", "/user/**", "/queue/events",
            "/app/ping", "/app/topic/tenant/11111111-1111-1111-1111-111111111111/supervisor",
            "/other/x", "topic/user/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/events",
            "/topic/user/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/events\n/topic/**",
            "/topic/tenant/11111111-1111-1111-1111-111111111111/../22222222-2222-2222-2222-222222222222/agents"
    })
    void disallowedDestinationsRejected(String destination) {
        assertThatThrownBy(() -> subscribe(destination, principal("ADMIN")))
                .isInstanceOf(MessagingException.class)
                .hasMessageNotContaining(destination.isEmpty() ? "\u0000" : destination);
    }

    @Test
    @DisplayName("brak destynacji lub principala -> odrzucone")
    void missingDestinationOrPrincipalRejected() {
        assertThatThrownBy(() -> subscribe(null, principal("ADMIN"))).isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> subscribe("/topic/user/" + USER + "/events", null))
                .isInstanceOf(MessagingException.class);
        assertThat(rejected("SUBSCRIBE", "no_destination")).isEqualTo(1);
        assertThat(rejected("SUBSCRIBE", "no_principal")).isEqualTo(1);
    }

    @Test
    @DisplayName("principal innego typu niz StompPrincipal jest traktowany jak brak")
    void foreignPrincipalTypeRejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/user/" + USER + "/events");
        accessor.setUser(() -> USER.toString());
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThatThrownBy(() -> interceptor.preSend(msg, channel)).isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("naglowki klienta (tenantId/role/userId) nie wplywaja na decyzje")
    void clientHeadersAreIgnored() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/tenant/" + OTHER_TENANT + "/supervisor");
        accessor.setUser(principal("AGENT"));
        accessor.addNativeHeader("tenantId", OTHER_TENANT.toString());
        accessor.addNativeHeader("role", "ADMIN");
        accessor.addNativeHeader("userId", OTHER_USER.toString());
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThatThrownBy(() -> interceptor.preSend(msg, channel)).isInstanceOf(MessagingException.class);
    }

    // ---- SEND ----

    @Test
    @DisplayName("SEND /app/ping przepuszczony, bez principala odrzucony")
    void sendPing() {
        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/app/ping", principal("AGENT")), channel)).isNotNull();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/app/ping", null), channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("legacy SEND /app/agent/status jest po cichu porzucany (null), bez wyjatku")
    void legacyAgentStatusDropped() {
        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/app/agent/status", principal("AGENT")), channel))
                .isNull();
    }

    @ParameterizedTest(name = "SEND na {0} odrzucony")
    @ValueSource(strings = {
            "/topic/tenant/22222222-2222-2222-2222-222222222222/agents",
            "/topic/tenant/11111111-1111-1111-1111-111111111111/agents",
            "/topic/user/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/events",
            "/topic/**", "/topic", "/user/events", "/queue/x", "/app/unknown", "/app/ping/x", "/app/**", "/app/"
    })
    void sendToBrokerOrUnknownRejected(String destination) {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, destination, principal("ADMIN")), channel))
                .isInstanceOf(MessagingException.class);
        assertThat(rejected("SEND", "destination_not_allowed")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEND bez destynacji odrzucony")
    void sendWithoutDestinationRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, null, principal("ADMIN")), channel))
                .isInstanceOf(MessagingException.class);
    }

    // ---- inne ramki ----

    @ParameterizedTest
    @ValueSource(strings = {"MESSAGE", "CONNECTED", "RECEIPT", "ERROR"})
    @DisplayName("ramki zarezerwowane dla serwera wysylane przez klienta -> odrzucone (server_only_command)")
    void serverOnlyCommandsRejected(String command) {
        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.valueOf(command), "/topic/tenant/" + OTHER_TENANT + "/agents", principal("ADMIN")), channel))
                .isInstanceOf(MessagingException.class);
        assertThat(rejected("OTHER", "server_only_command")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEGIN", "COMMIT", "ABORT"})
    @DisplayName("transakcje STOMP (BEGIN/COMMIT/ABORT) -> default-deny (unsupported_command)")
    void transactionCommandsRejected(String command) {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.valueOf(command), null, principal("ADMIN")), channel))
                .isInstanceOf(MessagingException.class);
        assertThat(rejected("OTHER", "unsupported_command")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECT", "STOMP", "DISCONNECT", "UNSUBSCRIBE", "ACK", "NACK"})
    @DisplayName("jawnie dozwolone komendy bez regul destynacji sa przepuszczane")
    void allowedCommandsPass(String command) {
        Message<byte[]> msg = frame(StompCommand.valueOf(command), null, null);
        assertThat(interceptor.preSend(msg, channel)).isSameAs(msg);
    }

    @Test
    @DisplayName("heartbeat przechodzi; wiadomosc bez komendy i nie-heartbeat jest odrzucana")
    void heartbeatPassesButCommandlessOtherDoesNot() {
        StompHeaderAccessor hb = StompHeaderAccessor.createForHeartbeat();
        Message<byte[]> heartbeat = MessageBuilder.createMessage(new byte[0], hb.getMessageHeaders());
        assertThat(interceptor.preSend(heartbeat, channel)).isSameAs(heartbeat);

        Message<byte[]> noAccessor = MessageBuilder.withPayload(new byte[0]).build();
        assertThatThrownBy(() -> interceptor.preSend(noAccessor, channel)).isInstanceOf(MessagingException.class);

        org.springframework.messaging.simp.SimpMessageHeaderAccessor other =
                org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
                        org.springframework.messaging.simp.SimpMessageType.OTHER);
        Message<byte[]> otherType = MessageBuilder.createMessage(new byte[0], other.getMessageHeaders());
        assertThatThrownBy(() -> interceptor.preSend(otherType, channel)).isInstanceOf(MessagingException.class);
        assertThat(rejected("OTHER", "unsupported_command")).isEqualTo(2);
    }

    @Test
    @DisplayName("porzucenie legacy /app/agent/status liczy ws.stomp.dropped")
    void legacyDropIsCounted() {
        interceptor.preSend(frame(StompCommand.SEND, "/app/agent/status", principal("AGENT")), channel);
        interceptor.preSend(frame(StompCommand.SEND, "/app/agent/status", principal("AGENT")), channel);
        assertThat(meters.get(StompDestinationAuthorizationInterceptor.METRIC_DROPPED)
                .tag("reason", "legacy_agent_status").counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("licznik odrzuceń rośnie przy każdym odrzuceniu mimo rate-limitu logu")
    void counterCountsEveryRejectionRegardlessOfLogRateLimit() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> subscribe("/topic/**", principal("AGENT"))).isInstanceOf(MessagingException.class);
        }
        assertThat(rejected("SUBSCRIBE", "destination_not_allowed")).isEqualTo(5);
    }

    @Test
    @DisplayName("sanitize usuwa znaki sterujace i przycina dlugosc")
    void sanitizeStripsControlCharsAndTruncates() {
        assertThat(StompDestinationAuthorizationInterceptor.sanitize("/topic/x\r\nFAKE LOG\u0000"))
                .doesNotContain("\n").doesNotContain("\r").doesNotContain("\u0000");
        assertThat(StompDestinationAuthorizationInterceptor.sanitize("a".repeat(500)).length()).isLessThanOrEqualTo(103);
        assertThat(StompDestinationAuthorizationInterceptor.sanitize(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("WARN ograniczony do 1/10 s per powod; foreign_* loguje zsanityzowana destynacje, tenantId i role")
    void warnIsRateLimitedPerReason() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000_000_000L);
        StompDestinationAuthorizationInterceptor timed = new StompDestinationAuthorizationInterceptor(meters, now::get);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(StompDestinationAuthorizationInterceptor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String foreign = "/topic/tenant/" + OTHER_TENANT + "/agents";
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> timed.preSend(frame(StompCommand.SUBSCRIBE, foreign, principal("AGENT")), channel))
                        .isInstanceOf(MessagingException.class);
            }
            assertThat(appender.list).hasSize(1);
            String msg = appender.list.get(0).getFormattedMessage();
            assertThat(msg).contains("tenantId=" + TENANT, "role=AGENT", foreign);

            assertThatThrownBy(() -> timed.preSend(frame(StompCommand.SUBSCRIBE, "/topic/**", principal("AGENT")), channel))
                    .isInstanceOf(MessagingException.class);
            assertThat(appender.list).as("inny powod ma wlasny limit").hasSize(2);
            assertThat(appender.list.get(1).getFormattedMessage()).contains("dest=-").doesNotContain("/topic/**");

            now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(11));
            assertThatThrownBy(() -> timed.preSend(frame(StompCommand.SUBSCRIBE, foreign, principal("AGENT")), channel))
                    .isInstanceOf(MessagingException.class);
            assertThat(appender.list).hasSize(3);
            assertThat(rejected("SUBSCRIBE", "foreign_tenant")).isEqualTo(4);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
