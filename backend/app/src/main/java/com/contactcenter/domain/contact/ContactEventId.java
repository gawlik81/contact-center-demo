package com.contactcenter.domain.contact;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Klucz złożony dla encji {@link ContactEvent}.
 *
 * <p>Tabela {@code contact_event} jest partycjonowana RANGE po kolumnie {@code started_at}
 * (migracja V085, DB-049), dlatego klucz główny PostgreSQL musi zawierać obie kolumny:
 * {@code event_id} i {@code started_at}. Jest to twarde wymaganie PostgreSQL dla tabel
 * partycjonowanych z PRIMARY KEY.
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ContactEventId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID eventId;
    private Instant startedAt;
}
