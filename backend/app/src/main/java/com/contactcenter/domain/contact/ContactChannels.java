package com.contactcenter.domain.contact;

/**
 * Single mapping point between the persisted contact channel ({@code contact.channel}) and the
 * contact type used by the agent desktop (frontend {@code ContactType}: PHONE, CHAT, EMAIL, SOCIAL).
 *
 * <p>The persisted channel is NOT changed: {@code contact.channel} keeps {@code SOCIAL_FACEBOOK},
 * {@code SOCIAL_INSTAGRAM} and {@code SOCIAL_WHATSAPP} (CHECK constraint from V025, reports,
 * admin metrics matrix, {@code channel} filters, social platform lookup). Only the agent-facing
 * boundaries (WebSocket {@code CONTACT_ASSIGNED.type}, queue updates, {@code assigned-contact})
 * collapse the SOCIAL_* family into {@code SOCIAL} through {@link #toAgentDesktopType(String)}.
 */
public final class ContactChannels {

    /** Agent desktop type for the whole social channel family. */
    public static final String SOCIAL_TYPE = "SOCIAL";

    /** Agent desktop type used when the channel is unknown (null). */
    public static final String DEFAULT_TYPE = "PHONE";

    private static final String SOCIAL_PREFIX = "SOCIAL_";

    private ContactChannels() {
    }

    /** True for {@code SOCIAL_FACEBOOK}/{@code SOCIAL_INSTAGRAM}/{@code SOCIAL_WHATSAPP} (and plain {@code SOCIAL}). */
    public static boolean isSocial(String channel) {
        return channel != null && (SOCIAL_TYPE.equals(channel) || channel.startsWith(SOCIAL_PREFIX));
    }

    /** True for the channels handled asynchronously by agents (no live audio): EMAIL, CHAT and SOCIAL_*. */
    public static boolean isAsync(String channel) {
        return "EMAIL".equals(channel) || "CHAT".equals(channel) || isSocial(channel);
    }

    /**
     * Maps a persisted channel to the agent desktop contact type.
     * {@code null} (legacy rows without a channel) maps to {@code PHONE} - the single null contract for
     * every agent-facing boundary; other non-social values are returned unchanged.
     */
    public static String toAgentDesktopType(String channel) {
        if (channel == null) {
            return DEFAULT_TYPE;
        }
        return isSocial(channel) ? SOCIAL_TYPE : channel;
    }
}
