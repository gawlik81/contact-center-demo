package com.contactcenter.domain.retention;

/**
 * Sposób wywołania operacji usuwania (purge) danych retencyjnych.
 *
 * <p>Wartości zgodne z CHECK constraint kolumny {@code retention_purge_log.trigger_type}
 * (V084, DB-048).
 */
public enum PurgeTriggerType {

    /** Uruchomione ręcznie przez administratora (przyszły {@code POST /purge}, BE-118). */
    MANUAL,

    /** Uruchomione automatycznie przez system (przyszły auto-purge scheduler, BE-112). */
    AUTO
}
