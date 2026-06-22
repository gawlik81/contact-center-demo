package com.contactcenter.domain.plugin;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Klucz złożony dla encji {@link PluginInvocationLog}.
 *
 * <p>Tabela {@code plugin_invocation_log} (V077, DB-045) jest partycjonowana po kolumnie
 * {@code invoked_at}, dlatego klucz główny musi zawierać obie kolumny: {@code id} i
 * {@code invoked_at} — to jest wymaganie PostgreSQL dla tabel partycjonowanych z PRIMARY KEY.
 * Wzorzec identyczny do {@code AuditLogId} ({@code audit_log}, V004).
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PluginInvocationLogId implements Serializable {

    private UUID id;
    private Instant invokedAt;
}
