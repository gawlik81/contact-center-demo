package com.contactcenter.domain.exception;

/**
 * The ETL cycle of a table is already running (the ShedLock of the scheduled cycle is held, possibly by another
 * instance), so a manual trigger is refused (BE-172). Mapped to HTTP 409 with {@code code=ETL_SYNC_IN_PROGRESS}.
 */
public class EtlSyncInProgressException extends ConflictException {

    public static final String CODE = "ETL_SYNC_IN_PROGRESS";

    public EtlSyncInProgressException(String tableName) {
        super("Synchronizacja ETL tabeli '" + tableName + "' już trwa (blokada zajęta) - spróbuj ponownie po zakończeniu cyklu");
    }
}
