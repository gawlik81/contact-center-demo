package com.contactcenter.domain.disposition;

import com.contactcenter.domain.disposition.dto.ApplySetResponse;
import com.contactcenter.domain.disposition.dto.CreateDispositionSetItemRequest;
import com.contactcenter.domain.disposition.dto.CreateDispositionSetRequest;
import com.contactcenter.domain.disposition.dto.DispositionSetDetailDto;
import com.contactcenter.domain.disposition.dto.DispositionSetDto;
import com.contactcenter.domain.disposition.dto.DispositionSetItemDto;
import com.contactcenter.domain.disposition.dto.UpdateDispositionSetItemRequest;
import com.contactcenter.domain.disposition.dto.UpdateDispositionSetRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Serwis zarządzający zestawami dyspozycji wielokrotnego użytku (BE-095).
 *
 * <p>Odpowiada za:
 * <ul>
 *   <li>CRUD zestawów ({@link DispositionSet}) i ich elementów ({@link DispositionSetItem})</li>
 *   <li>Aplikowanie zestawu do kampanii lub kolejki przez kopiowanie elementów
 *       jako {@link CustomDisposition}</li>
 * </ul>
 *
 * <p>Izolacja multi-tenant przez przekazanie {@code tenantId} do każdej metody repozytorium.
 */
public interface DispositionSetService {

    /**
     * Zwraca listę wszystkich zestawów dyspozycji tenanta z liczbą elementów.
     *
     * @param tenantId UUID tenanta
     * @return lista zestawów posortowana po name ASC
     */
    List<DispositionSetDto> listSets(UUID tenantId);

    /**
     * Zwraca szczegóły zestawu dyspozycji wraz z listą elementów.
     *
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @return DTO szczegółów zestawu
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     */
    DispositionSetDetailDto getSet(UUID setId, UUID tenantId);

    /**
     * Tworzy nowy zestaw dyspozycji.
     *
     * @param req      dane nowego zestawu
     * @param tenantId UUID tenanta
     * @return DTO nowo utworzonego zestawu
     * @throws ConflictException gdy zestaw o tej nazwie już istnieje (HTTP 409)
     */
    DispositionSetDto createSet(CreateDispositionSetRequest req, UUID tenantId);

    /**
     * Aktualizuje istniejący zestaw dyspozycji.
     *
     * @param setId    UUID zestawu
     * @param req      dane do aktualizacji
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego zestawu
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     * @throws ConflictException         gdy nowa nazwa jest już zajęta (HTTP 409)
     */
    DispositionSetDto updateSet(UUID setId, UpdateDispositionSetRequest req, UUID tenantId);

    /**
     * Usuwa zestaw dyspozycji wraz z jego elementami (CASCADE w bazie).
     *
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     */
    void deleteSet(UUID setId, UUID tenantId);

    /**
     * Zwraca listę elementów zestawu posortowaną po ordinal ASC.
     *
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @return lista elementów
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     */
    List<DispositionSetItemDto> listItems(UUID setId, UUID tenantId);

    /**
     * Dodaje nowy element do zestawu dyspozycji.
     *
     * @param setId    UUID zestawu
     * @param req      dane nowego elementu
     * @param tenantId UUID tenanta
     * @return DTO nowo utworzonego elementu
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     * @throws ConflictException         gdy kod dyspozycji już istnieje w zestawie (HTTP 409)
     */
    DispositionSetItemDto addItem(UUID setId, CreateDispositionSetItemRequest req, UUID tenantId);

    /**
     * Aktualizuje element zestawu dyspozycji.
     *
     * @param setId    UUID zestawu
     * @param itemId   UUID elementu
     * @param req      dane do aktualizacji
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego elementu
     * @throws ResourceNotFoundException gdy element nie istnieje (HTTP 404)
     */
    DispositionSetItemDto updateItem(UUID setId, UUID itemId, UpdateDispositionSetItemRequest req, UUID tenantId);

    /**
     * Usuwa element z zestawu dyspozycji.
     *
     * @param setId    UUID zestawu
     * @param itemId   UUID elementu
     * @param tenantId UUID tenanta
     * @throws ResourceNotFoundException gdy element nie istnieje (HTTP 404)
     */
    void removeItem(UUID setId, UUID itemId, UUID tenantId);

    /**
     * Kopiuje wszystkie elementy zestawu jako dyspozycje przypisane do kampanii.
     *
     * <p>Operacja best-effort: duplikaty kodów są pomijane i zliczane w {@code skipped}.
     * Umożliwia to wielokrotne wywołanie bez ryzyka utraty istniejących dyspozycji.
     *
     * @param setId      UUID zestawu do zastosowania
     * @param campaignId UUID kampanii docelowej
     * @param tenantId   UUID tenanta
     * @return podsumowanie operacji z liczbą skopiowanych i pominiętych elementów
     * @throws ResourceNotFoundException gdy zestaw nie istnieje lub jest pusty (HTTP 404)
     */
    ApplySetResponse applyToCampaign(UUID setId, UUID campaignId, UUID tenantId);

    /**
     * Kopiuje wszystkie elementy zestawu jako dyspozycje przypisane do kolejki.
     *
     * <p>Operacja best-effort: duplikaty kodów są pomijane i zliczane w {@code skipped}.
     * Umożliwia to wielokrotne wywołanie bez ryzyka utraty istniejących dyspozycji.
     *
     * @param setId    UUID zestawu do zastosowania
     * @param queueId  UUID kolejki docelowej
     * @param tenantId UUID tenanta
     * @return podsumowanie operacji z liczbą skopiowanych i pominiętych elementów
     * @throws ResourceNotFoundException gdy zestaw nie istnieje lub jest pusty (HTTP 404)
     */
    ApplySetResponse applyToQueue(UUID setId, UUID queueId, UUID tenantId);
}
