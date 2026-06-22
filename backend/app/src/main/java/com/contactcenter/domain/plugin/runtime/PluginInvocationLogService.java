package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.dto.PluginInvocationLogDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Zapis i odczyt dziennika wywołań pluginu (ARCHITECTURE.md §11.9, §11.12, EPIC-28, BE-105).
 *
 * <p>{@link #record} jest wołane na KAŻDEJ z 5 ścieżek statusu wynikających z dispatchu —
 * przez {@code ExtensionPointPublisherImpl} (ścieżka blocking, BE-102/103) i przez
 * {@code PluginInvocationConsumer} (ścieżka fire-and-forget przez RabbitMQ, BE-104). Te dwie
 * klasy wołają tę metodę bezpośrednio (DI), bez pośredniej klasy-placeholdera
 * ({@code PluginInvocationLogger}, BE-102/104, usunięta w BE-105).
 */
public interface PluginInvocationLogService {

    /**
     * Zapisuje wynik jednej próby wywołania pluginu.
     *
     * <p>{@code requestPayload} jest redagowany ({@link PiiRedactor}) przed zapisem do
     * {@code request_payload_redacted} — nigdy surowe dane klienta (np. numer telefonu wpisany
     * jako parametr {@code ManualActionRequest}).
     *
     * @param tenantId        tenant-właściciel instalacji
     * @param installationId   instalacja, której wynik dotyczy (może być {@code null} dla
     *                         {@code MANUAL_ACTION} na nieznalezionej instalacji)
     * @param extensionPoint   punkt rozszerzenia, dla którego wywołanie nastąpiło
     * @param status            wynik próby (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED)
     * @param durationMs        czas trwania wywołania w milisekundach (0 gdy plugin nie został
     *                         faktycznie wywołany, np. {@code CIRCUIT_OPEN}/{@code SKIPPED_DISABLED})
     * @param errorSummary      skrót błędu, lub {@code null} gdy nie dotyczy
     * @param relatedContactId  kontakt powiązany z wywołaniem, jeśli dostępny w payloadzie
     *                         wołającego ({@code null} gdy niedostępny, np. {@code CUSTOMER_SYNC})
     * @param requestPayload    payload żądania pluginu (rekord SDK lub {@code Map<String,Object>}),
     *                         redagowany przed zapisem, może być {@code null}
     */
    void record(
            UUID tenantId,
            UUID installationId,
            ExtensionPoint extensionPoint,
            InvocationStatus status,
            long durationMs,
            String errorSummary,
            UUID relatedContactId,
            Object requestPayload);

    /**
     * Strona dziennika wywołań dla jednej instalacji, opcjonalnie filtrowana po statusie.
     *
     * @param tenantId       tenant z kontekstu żądania (weryfikacja ownership — zob. Javadoc
     *                       implementacji dla decyzji 404 vs 403)
     * @param installationId instalacja, której historia jest przeglądana
     * @param status          opcjonalny filtr statusu ({@code null} = wszystkie statusy)
     * @param pageable         parametry paginacji
     * @return strona wyników, posortowana {@code invokedAt DESC}
     */
    Page<PluginInvocationLogDto> findByInstallation(
            UUID tenantId, UUID installationId, InvocationStatus status, Pageable pageable);
}
