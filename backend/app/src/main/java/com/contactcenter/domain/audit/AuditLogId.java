package com.contactcenter.domain.audit;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Klucz złożony dla encji {@link AuditLog}.
 *
 * <p>Tabela {@code audit_log} jest partycjonowana po kolumnie {@code created_at},
 * dlatego klucz główny musi zawierać obie kolumny: {@code log_id} i {@code created_at}.
 * To jest wymaganie PostgreSQL dla tabel partycjonowanych z PRIMARY KEY.
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class AuditLogId implements Serializable {

    private UUID logId;
    private Instant createdAt;
}
