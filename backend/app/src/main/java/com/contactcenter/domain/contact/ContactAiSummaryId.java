package com.contactcenter.domain.contact;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Klucz złożony dla encji {@link ContactAiSummary}.
 *
 * <p>Tabela {@code contact_ai_summary} jest partycjonowana RANGE po kolumnie {@code generated_at}
 * (migracja V087, DB-051), dlatego klucz główny PostgreSQL musi zawierać obie kolumny:
 * {@code ai_summary_id} i {@code generated_at}. Jest to twarde wymaganie PostgreSQL dla tabel
 * partycjonowanych z PRIMARY KEY.
 *
 * <p><strong>UWAGA:</strong> kolumną partycjonowania jest {@code generated_at} (moment
 * wygenerowania treści przez model AI), a NIE {@code created_at} (techniczny znacznik zapisu
 * wiersza) – świadoma decyzja architektoniczna uzasadniona w nagłówku migracji V087.
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ContactAiSummaryId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID aiSummaryId;
    private Instant generatedAt;
}
