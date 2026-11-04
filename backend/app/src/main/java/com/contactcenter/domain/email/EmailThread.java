package com.contactcenter.domain.email;

import java.util.List;

/**
 * Conversation of one EMAIL contact for the preview: the newest messages (capped) in chronological order.
 *
 * @param messages  chronological ({@code message_at ASC, created_at ASC}); when {@code truncated}, the NEWEST
 *                  messages of the contact (the oldest ones are cut)
 * @param total     number of messages linked to the contact (not capped)
 * @param truncated true when {@code total} exceeds the number of returned messages
 */
public record EmailThread(List<EmailMessage> messages, long total, boolean truncated) {

    public EmailThread {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
