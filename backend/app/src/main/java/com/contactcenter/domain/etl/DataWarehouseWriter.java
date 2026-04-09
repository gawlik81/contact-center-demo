package com.contactcenter.domain.etl;

import java.util.List;

/**
 * Port abstrakcji zapisu do Data Warehouse.
 *
 * <p>Implementacje:
 * <ul>
 *   <li>{@link PostgresDwWriter} – zapis do lokalnej tabeli {@code contacts_dw}
 *       (fallback gdy ClickHouse niedostępne lub w środowisku dev)</li>
 *   <li>{@code ClickHouseDwWriter} – zapis przez JDBC do zewnętrznego ClickHouse
 *       (aktywowany profilem Spring lub przez konfigurację)</li>
 * </ul>
 *
 * <p>Implementacja musi być idempotentna – ponowne wywołanie z tymi samymi danymi
 * nie może tworzyć duplikatów (upsert po {@code contact_id}).
 */
public interface DataWarehouseWriter {

    /**
     * Wykonuje upsert listy wierszy do Data Warehouse.
     *
     * <p>Operacja musi być idempotentna – ten sam {@code contactId} może pojawić się
     * wielokrotnie (np. przy retransmisji lub ponownym przetworzeniu).
     *
     * @param rows lista wierszy do wstawienia / aktualizacji
     * @throws DataWarehouseException gdy zapis się nie powiedzie
     */
    void upsert(List<ContactDwRow> rows);
}
