package com.contactcenter.domain.reporting;

/**
 * Wyjątek rzucany przy błędzie generowania pliku eksportu (CSV/XLSX).
 */
public class ReportExportException extends RuntimeException {

    public ReportExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
