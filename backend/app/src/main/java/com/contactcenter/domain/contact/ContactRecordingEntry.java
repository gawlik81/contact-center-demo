package com.contactcenter.domain.contact;

import java.util.UUID;

/**
 * Para (contactId, recordingUrl) zwracana przez {@link ContactService#findExpiredRecordings}.
 *
 * <p>Wydzielona jako publiczny top-level record (poprzednio zagnieżdżona w {@code ContactRepository}),
 * aby pozostała dostępna dla {@code RecordingRetentionJob} po przełączeniu repozytorium
 * na {@code package-private} (encapsulation pass, pkt 9 wzorca refaktoru domenowego).
 */
public record ContactRecordingEntry(UUID contactId, String recordingUrl) {
}
