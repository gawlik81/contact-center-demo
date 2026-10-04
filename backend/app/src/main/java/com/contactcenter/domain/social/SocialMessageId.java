package com.contactcenter.domain.social;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Klucz złożony dla encji {@link SocialMessage}.
 *
 * <p>Tabela {@code social_message} jest partycjonowana RANGE po kolumnie {@code sent_at}
 * (migracja V100, DB-065), dlatego klucz główny PostgreSQL musi zawierać obie kolumny:
 * {@code message_id} i {@code sent_at}. Jest to twarde wymaganie PostgreSQL dla tabel
 * partycjonowanych z PRIMARY KEY.
 *
 * <p>Wzorzec 1:1 z {@code ContactEventId} (BE-117, EPIC-29).
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SocialMessageId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID messageId;
    private Instant sentAt;
}
