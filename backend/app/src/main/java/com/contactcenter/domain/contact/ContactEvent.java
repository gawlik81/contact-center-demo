package com.contactcenter.domain.contact;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encja reprezentująca pojedynczy etap (zdarzenie) w historii kontaktu.
 *
 * <p>Mapuje tabelę {@code contact_event} (schemat z V059). Każdy rekord opisuje
 * jeden etap przetwarzania kontaktu (IVR, QUEUE, AGENT itp.) wraz z czasem trwania
 * obliczanym przez trigger DB przy ustawieniu {@code ended_at}.
 *
 * <p>Zdarzenia punktowe (np. TRANSFER) mają {@code started_at = ended_at} i {@code duration_seconds = 0}.
 *
 * <p>Pole {@code metadata} przechowywane jako JSONB – zawiera dane specyficzne dla etapu
 * (np. agent_id, queue_name, transfer_type).
 *
 * <p>Od migracji V085 (DB-049) tabela jest partycjonowana RANGE po {@code started_at}
 * (miesięcznie), co wymaga – analogicznie do {@link Contact}/{@link ContactId} –
 * klucza złożonego {@code (event_id, started_at)} obsługiwanego przez {@link ContactEventId}.
 * PostgreSQL wymaga, żeby kolumna partycjonowania wchodziła w PRIMARY KEY.
 */
@Entity
@Table(name = "contact_event")
@IdClass(ContactEventId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Etap kontaktu: IVR, VOICEBOT, QUEUE, AGENT, ON_HOLD, CONSULTING, TRANSFER. */
    @Column(name = "stage", nullable = false, length = 20)
    private String stage;

    /**
     * Czas rozpoczęcia etapu – kolumna partycjonowania, NOT NULL.
     * Jest częścią klucza głównego {@link ContactEventId}.
     */
    @Id
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Czas zakończenia etapu. NULL = etap otwarty. Dla TRANSFER: started_at = ended_at. */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** Czas trwania w sekundach – obliczany przez trigger DB przy ustawieniu ended_at. */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Metadane specyficzne dla etapu (np. agent_id, queue_name, transfer_type). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
