package com.contactcenter.domain.campaign;

import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający zaplanowanymi oddzwonieniami ({@code scheduled_callback}).
 *
 * <p>Encapsulation pass (pkt 9 wzorca refaktoru {@code domain.campaign}) – udostępnia
 * konsumentom z innych pakietów (m.in. {@code api.dialer.DialerController},
 * {@code api.contact.ContactController}, {@code api.dialer.ManualCallbackController},
 * {@code domain.agentbreak.AgentCalendarService}) dostęp do {@link ScheduledCallbackRepository}
 * wyłącznie przez metody serwisu.
 *
 * <p>Logika biznesowa (statusy, autoryzacja, walidacja) pozostaje w kontrolerach – serwis
 * udostępnia operacje 1:1 odpowiadające metodom repozytorium.
 */
public interface ScheduledCallbackService {

    /**
     * Zapisuje lub aktualizuje callback.
     *
     * <p>Przed zapisem waliduje przynależność do tenanta.
     *
     * @param callback encja callbacku do zapisu
     * @return zapisana encja
     */
    ScheduledCallback saveCallback(ScheduledCallback callback);

    /**
     * Pobiera callback po ID z weryfikacją tenanta.
     *
     * @param callbackId UUID callbacku
     * @param tenantId   UUID tenanta
     * @return Optional z callbackiem lub empty gdy nie istnieje lub należy do innego tenanta
     */
    Optional<ScheduledCallback> findCallbackEntity(UUID callbackId, UUID tenantId);

    /**
     * Pobiera callback po ID lub rzuca {@link ResourceNotFoundException}.
     *
     * @param callbackId UUID callbacku
     * @param tenantId   UUID tenanta
     * @return encja callbacku
     * @throws ResourceNotFoundException gdy callback nie istnieje lub nie należy do tenanta
     */
    ScheduledCallback getCallbackOrThrow(UUID callbackId, UUID tenantId);

    /**
     * Zwraca listę callbacków, dla których dany kontakt jest kontaktem źródłowym.
     *
     * @param originContactId UUID kontaktu źródłowego
     * @param tenantId        UUID tenanta
     * @return lista callbacków powiązanych z danym kontaktem jako origin
     */
    List<ScheduledCallback> findCallbacksByOriginContactId(UUID originContactId, UUID tenantId);

    /**
     * Zwraca listę callbacków agenta w podanym zakresie czasowym (kalendarz agenta, BE-051).
     *
     * @param tenantId UUID tenanta
     * @param agentId  UUID agenta
     * @param from     początek zakresu (włącznie)
     * @param to       koniec zakresu (włącznie)
     * @return lista callbacków agenta w zakresie dat, posortowana po scheduled_at ASC
     */
    List<ScheduledCallback> getAgentCalendarCallbacks(UUID tenantId, UUID agentId, Instant from, Instant to);

    /**
     * Stronicowana lista callbacków przypisanych do konkretnego agenta z opcjonalnym filtrem statusu.
     *
     * @param tenantId      UUID tenanta
     * @param agentId       UUID agenta
     * @param status        filtr statusu (null = wszystkie statusy)
     * @param sortDirection kierunek sortowania po scheduled_at: "ASC" lub "DESC"
     * @param page          numer strony (0-based)
     * @param size          rozmiar strony
     * @return lista callbacków agenta
     */
    List<ScheduledCallback> findCallbacksByAgentId(UUID tenantId, UUID agentId, String status,
                                                     String sortDirection, int page, int size);

    /**
     * Zlicza callbacki agenta (do metadanych paginacji).
     *
     * @param tenantId UUID tenanta
     * @param agentId  UUID agenta
     * @param status   filtr statusu (null = wszystkie statusy)
     * @return liczba callbacków
     */
    long countCallbacksByAgentId(UUID tenantId, UUID agentId, String status);

    /**
     * Stronicowana lista wszystkich callbacków tenanta z opcjonalnymi filtrami statusu i agentId.
     *
     * @param tenantId      UUID tenanta
     * @param status        filtr statusu (null = wszystkie statusy)
     * @param agentIdFilter filtr po agentId (null = wszyscy agenci)
     * @param sortDirection kierunek sortowania po scheduled_at: "ASC" lub "DESC"
     * @param page          numer strony (0-based)
     * @param size          rozmiar strony
     * @return lista callbacków tenanta
     */
    List<ScheduledCallback> findCallbacksByTenantIdWithFilters(UUID tenantId, String status, UUID agentIdFilter,
                                                                 String sortDirection, int page, int size);

    /**
     * Zlicza callbacki tenanta z opcjonalnymi filtrami statusu i agentId (do metadanych paginacji).
     *
     * @param tenantId      UUID tenanta
     * @param status        filtr statusu (null = wszystkie statusy)
     * @param agentIdFilter filtr po agentId (null = wszyscy agenci)
     * @return liczba callbacków
     */
    long countCallbacksByTenantIdWithFilters(UUID tenantId, String status, UUID agentIdFilter);

    /**
     * Soft-delete callbacku przez zmianę statusu na CANCELLED.
     *
     * @param callbackId UUID callbacku
     * @param tenantId   UUID tenanta (walidacja cross-tenant)
     * @return liczba zaktualizowanych wierszy (0 gdy callback nie istnieje lub inny tenant)
     */
    int cancelCallback(UUID callbackId, UUID tenantId);
}
