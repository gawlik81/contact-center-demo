package com.contactcenter.security;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for the broadcast STOMP destinations the platform publishes to and clients may subscribe to
 * (BE-229 allow-list, reused by the cross-instance WebSocket fan-out, BE-175).
 *
 * <p>Both the SUBSCRIBE authorization ({@link StompDestinationAuthorizationInterceptor}) and the fan-out receiver
 * ({@code WebSocketFanoutSubscriber}) validate against exactly these patterns, so a message arriving from the shared
 * Redis channel can never target a destination a client could not legitimately subscribe to (no {@code /user/**},
 * {@code /app/**}, wildcards or unknown prefixes). UUIDs are matched in canonical lower case.
 */
public final class StompDestinations {

    /** Unicast to one user: {@code /topic/user/{userId}/events}. */
    public static final String USER_EVENTS_FORMAT = "/topic/user/%s/events";
    /** Broadcast to supervisors of a tenant: {@code /topic/tenant/{tenantId}/supervisor}. */
    public static final String TENANT_SUPERVISOR_FORMAT = "/topic/tenant/%s/supervisor";
    /** Broadcast to every user of a tenant: {@code /topic/tenant/{tenantId}/agents}. */
    public static final String TENANT_AGENTS_FORMAT = "/topic/tenant/%s/agents";

    static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    static final Pattern USER_EVENTS = Pattern.compile("/topic/user/(" + UUID_REGEX + ")/events");
    static final Pattern TENANT_TOPIC = Pattern.compile("/topic/tenant/(" + UUID_REGEX + ")/(agents|supervisor)");

    /** Kind of a recognised broadcast destination. */
    public enum Kind { USER_EVENTS, TENANT_AGENTS, TENANT_SUPERVISOR }

    /** Parsed allow-listed destination: its kind and the id embedded in the path (user id or tenant id). */
    public record Parsed(Kind kind, UUID id) {
        public boolean isTenantScoped() {
            return kind != Kind.USER_EVENTS;
        }
    }

    private StompDestinations() {
    }

    /** @return the parsed destination, or {@code null} when it is not one of the three allow-listed shapes. */
    public static Parsed parse(String destination) {
        if (destination == null) {
            return null;
        }
        Matcher user = USER_EVENTS.matcher(destination);
        if (user.matches()) {
            return new Parsed(Kind.USER_EVENTS, UUID.fromString(user.group(1)));
        }
        Matcher tenant = TENANT_TOPIC.matcher(destination);
        if (tenant.matches()) {
            Kind kind = "agents".equals(tenant.group(2)) ? Kind.TENANT_AGENTS : Kind.TENANT_SUPERVISOR;
            return new Parsed(kind, UUID.fromString(tenant.group(1)));
        }
        return null;
    }
}
