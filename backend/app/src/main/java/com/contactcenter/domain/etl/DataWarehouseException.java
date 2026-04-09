package com.contactcenter.domain.etl;

/**
 * Wyjątek rzucany gdy zapis do Data Warehouse się nie powiedzie.
 */
public class DataWarehouseException extends RuntimeException {

    public DataWarehouseException(String message) {
        super(message);
    }

    public DataWarehouseException(String message, Throwable cause) {
        super(message, cause);
    }
}
