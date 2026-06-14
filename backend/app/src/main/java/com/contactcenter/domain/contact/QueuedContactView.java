package com.contactcenter.domain.contact;

import java.time.Instant;
import java.util.UUID;

/**
 * Projekcja kontaktu QUEUED z detalami do sidebaru agenta.
 *
 * <p>Wydzielona jako publiczny top-level record (poprzednio zagnieżdżona w {@code ContactRepository}),
 * aby pozostała dostępna dla {@code RoutingService} po przełączeniu repozytorium
 * na {@code package-private} (encapsulation pass, pkt 9 wzorca refaktoru domenowego).
 *
 * @see ContactService#findQueuedContactsForAgentView(UUID)
 */
public record QueuedContactView(
    UUID contactId,
    String channel,
    String remoteAddress,
    Instant queuedAt,
    String customerName,
    String queueName
) {
}
