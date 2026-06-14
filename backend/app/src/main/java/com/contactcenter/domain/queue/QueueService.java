package com.contactcenter.domain.queue;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.queue.dto.CreateQueueRequest;
import com.contactcenter.api.queue.dto.QueueResponse;
import com.contactcenter.api.queue.dto.UpdateQueueRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import jakarta.persistence.EntityNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający kolejkami kontaktów.
 *
 * <p>Implementuje BE-020: Queue API – CRUD kolejek i konfiguracja routingu.
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Każdy odczyt i zapis filtruje po tenantId z TenantContext</li>
 *   <li>Przed utworzeniem kolejki sprawdzany jest limit zasobów tenanta</li>
 *   <li>Usunięcie (deaktywacja) blokowane gdy kolejka ma aktywne kontakty</li>
 *   <li>Operacje modyfikujące logują zdarzenia audytowe przez {@code @Audited}</li>
 * </ul>
 */
public interface QueueService {

    /**
     * Tworzy nową kolejkę w tenancie.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Sprawdza limit kolejek przez {@code TenantResourceLimitService}</li>
     *   <li>Buduje encję z wartościami domyślnymi dla pól opcjonalnych</li>
     *   <li>Zapisuje przez natywny INSERT</li>
     * </ol>
     *
     * @param request  dane nowej kolejki
     * @param tenantId UUID tenanta z TenantContext
     * @return DTO nowo utworzonej kolejki
     * @throws com.contactcenter.domain.exception.ResourceLimitExceededException HTTP 422 gdy limit przekroczony
     */
    QueueResponse createQueue(CreateQueueRequest request, UUID tenantId);

    /**
     * Zwraca paginowaną listę kolejek tenanta.
     *
     * @param tenantId UUID tenanta
     * @param name     opcjonalny filtr nazwy (ILIKE)
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return strona DTO kolejek
     */
    PagedResponse<QueueResponse> listQueues(UUID tenantId, String name, int page, int size);

    /**
     * Pobiera kolejkę po ID.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return DTO kolejki
     * @throws EntityNotFoundException HTTP 422 gdy kolejka nie istnieje lub nie należy do tenanta
     */
    QueueResponse getQueue(UUID queueId, UUID tenantId);

    /**
     * Aktualizuje kolejkę (PATCH semantics).
     *
     * <p>Pola null w żądaniu są ignorowane – wartości pozostają bez zmian.
     *
     * @param queueId  UUID kolejki
     * @param request  dane do aktualizacji
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanej kolejki
     * @throws EntityNotFoundException HTTP 422 gdy kolejka nie istnieje
     */
    QueueResponse updateQueue(UUID queueId, UpdateQueueRequest request, UUID tenantId);

    /**
     * Deaktywuje kolejkę (odpowiednik soft-delete dla tabel bez is_deleted).
     *
     * <p>Reguły:
     * <ul>
     *   <li>Nie można deaktywować kolejki z aktywnymi kontaktami (QUEUED/ACTIVE/ON_HOLD)</li>
     * </ul>
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @throws InvalidOperationException HTTP 409 gdy kolejka ma aktywne kontakty
     * @throws EntityNotFoundException   HTTP 422 gdy kolejka nie istnieje
     */
    void deleteQueue(UUID queueId, UUID tenantId);

    /**
     * Zwraca listę dostępnych strategii routingu.
     *
     * <p>Wartości zgodne z ENUM {@code routing_strategy} w PostgreSQL.
     *
     * @return lista nazw strategii routingu
     */
    List<String> listRoutingStrategies();

    // =========================================================================
    // Metody delegujące (encapsulation pass – pkt 9 wzorca)
    // =========================================================================

    /**
     * Pobiera encję kolejki po ID z weryfikacją tenanta.
     *
     * <p>Odpowiednik {@code QueueRepository.findByIdAndTenantId} dla konsumentów
     * spoza pakietu {@code domain.queue} (routing, IVR, telefonia, kontakty).
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return Optional z encją kolejki lub empty gdy nie istnieje
     */
    Optional<Queue> findQueueEntity(UUID queueId, UUID tenantId);

    /**
     * Zwraca wszystkie kolejki tenanta (aktywne i nieaktywne), posortowane
     * po {@code is_active DESC, name ASC}.
     *
     * <p>Odpowiednik {@code QueueRepository.findAllByTenantId(tenantId, null, 0, 1000).content()}
     * używany przez {@code WaitTimeEstimationService} (EWT) i {@code IvrEngineService}
     * (domyślna kolejka).
     *
     * @param tenantId UUID tenanta
     * @return lista kolejek tenanta (może być pusta)
     */
    List<Queue> getAllQueues(UUID tenantId);

    /**
     * Pobiera aktywną kolejkę przypisaną do danego adresu email.
     *
     * <p>Używane przez {@code EmailRoutingService} jako drugi krok routingu:
     * po sprawdzeniu reguł routingu, przed fallbackiem na kolejkę domyślną.
     *
     * @param emailAddress adres email (case-insensitive)
     * @param tenantId     UUID tenanta
     * @return Optional z kolejką lub empty gdy brak dopasowania
     */
    Optional<Queue> findQueueByEmailAddress(String emailAddress, UUID tenantId);

    /**
     * Pobiera aktywne kolejki tenanta wraz z nazwami (queue_id, name) – jedno zapytanie.
     *
     * <p>Odpowiednik {@code TransferQueueStatsRepository.findActiveQueues}, używany
     * przez {@code TransferService} do budowy listy transferu.
     *
     * @param tenantId UUID tenanta
     * @return lista par [queue_id::text, name] posortowanych po name
     */
    List<Object[]> findActiveQueuesForTransfer(UUID tenantId);

    /**
     * Zlicza kontakty w statusie QUEUED per kolejka – jedno zapytanie GROUP BY.
     *
     * <p>Odpowiednik {@code TransferQueueStatsRepository.countWaitingContactsByQueueIds}.
     *
     * @param tenantId UUID tenanta
     * @param queueIds lista UUID kolejek do zliczenia
     * @return mapa queue_id → liczba oczekujących kontaktów
     */
    Map<UUID, Integer> countWaitingContactsByQueueIds(UUID tenantId, List<UUID> queueIds);

    /**
     * Zlicza agentów w statusie AVAILABLE per kolejka – jedno zapytanie GROUP BY.
     *
     * <p>Odpowiednik {@code TransferQueueStatsRepository.countAvailableAgentsByQueueIds}.
     *
     * @param tenantId UUID tenanta
     * @param queueIds lista UUID kolejek do zliczenia
     * @return mapa queue_id → liczba dostępnych agentów
     */
    Map<UUID, Integer> countAvailableAgentsByQueueIds(UUID tenantId, List<UUID> queueIds);

    /**
     * Pobiera nazwy kolejek dla wielu agentów jednym zapytaniem SQL.
     *
     * <p>Odpowiednik {@code TransferAgentQueueRepository.findQueueNamesByAgentIds},
     * używany przez {@code TransferService} do budowy listy transferu (eliminacja N+1).
     *
     * @param tenantId UUID tenanta
     * @param agentIds lista UUID agentów
     * @return mapa agent_id → posortowana lista nazw kolejek
     */
    Map<UUID, List<String>> findQueueNamesByAgentIds(UUID tenantId, List<UUID> agentIds);
}
