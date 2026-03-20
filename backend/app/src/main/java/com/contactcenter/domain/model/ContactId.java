package com.contactcenter.domain.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Klucz złożony dla encji {@link Contact}.
 *
 * <p>Tabela {@code contact} jest partycjonowana RANGE po kolumnie {@code started_at},
 * dlatego klucz główny PostgreSQL musi zawierać obie kolumny: {@code contact_id} i {@code started_at}.
 * Jest to twarde wymaganie PostgreSQL dla tabel partycjonowanych z PRIMARY KEY.
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ContactId implements Serializable {

    private UUID contactId;
    private Instant startedAt;
}
