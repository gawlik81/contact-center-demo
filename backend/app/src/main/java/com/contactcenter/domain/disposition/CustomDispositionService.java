package com.contactcenter.domain.disposition;

import com.contactcenter.domain.disposition.dto.AvailableDispositionDto;
import com.contactcenter.domain.disposition.dto.CreateCustomDispositionRequest;
import com.contactcenter.domain.disposition.dto.CustomDispositionDto;
import com.contactcenter.domain.disposition.dto.UpdateCustomDispositionRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Serwis zarządzający własnymi dyspozycjami po kontakcie (BE-092).
 *
 * <p>Odpowiada za:
 * <ul>
 *   <li>Resolucję dostępnych dyspozycji dla agenta według priorytetu: kampania → kolejka → system</li>
 *   <li>CRUD dyspozycji per kampania i per kolejka (widok supervisora)</li>
 * </ul>
 *
 * <p>Systemowe dyspozycje domyślne są zwracane gdy żaden custom
 * zestaw nie jest skonfigurowany — gwarantuje to nigdy niepustą listę dyspozycji dla agenta.
 *
 * <p>Izolacja multi-tenant przez przekazanie {@code tenantId} do każdej metody repozytorium.
 */
public interface CustomDispositionService {

    /**
     * Zwraca dostępne dyspozycje dla kontaktu według priorytetu: kampania → kolejka → system.
     *
     * <p>Priorytet resolucji:
     * <ol>
     *   <li>Jeśli {@code campaignId != null} i kampania ma skonfigurowane aktywne dyspozycje →
     *       zwróć dyspozycje kampanii</li>
     *   <li>Else jeśli {@code queueId != null} i kolejka ma skonfigurowane aktywne dyspozycje →
     *       zwróć dyspozycje kolejki</li>
     *   <li>Else → zwróć systemowe domyślne</li>
     * </ol>
     *
     * <p>Metoda nigdy nie zwraca pustej listy.
     *
     * @param campaignId UUID kampanii (nullable)
     * @param queueId    UUID kolejki (nullable)
     * @param tenantId   UUID tenanta
     * @return niepusta lista dostępnych dyspozycji dla agenta
     */
    List<AvailableDispositionDto> resolveForContact(UUID campaignId, UUID queueId, UUID tenantId);

    /**
     * Zwraca listę wszystkich dyspozycji (włącznie z nieaktywnymi) dla kampanii.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return lista dyspozycji posortowana po ordinal ASC
     */
    List<CustomDispositionDto> listForCampaign(UUID campaignId, UUID tenantId);

    /**
     * Tworzy nową dyspozycję przypisaną do kampanii.
     *
     * @param campaignId UUID kampanii
     * @param req        dane nowej dyspozycji
     * @param tenantId   UUID tenanta
     * @return DTO nowo utworzonej dyspozycji
     * @throws ConflictException gdy kod dyspozycji już istnieje w tej kampanii (HTTP 409)
     */
    CustomDispositionDto createForCampaign(UUID campaignId, CreateCustomDispositionRequest req, UUID tenantId);

    /**
     * Zwraca listę wszystkich dyspozycji (włącznie z nieaktywnymi) dla kolejki.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return lista dyspozycji posortowana po ordinal ASC
     */
    List<CustomDispositionDto> listForQueue(UUID queueId, UUID tenantId);

    /**
     * Tworzy nową dyspozycję przypisaną do kolejki.
     *
     * @param queueId  UUID kolejki
     * @param req      dane nowej dyspozycji
     * @param tenantId UUID tenanta
     * @return DTO nowo utworzonej dyspozycji
     * @throws ConflictException gdy kod dyspozycji już istnieje w tej kolejce (HTTP 409)
     */
    CustomDispositionDto createForQueue(UUID queueId, CreateCustomDispositionRequest req, UUID tenantId);

    /**
     * Aktualizuje dyspozycję w zakresie kampanii — weryfikuje, że dyspozycja należy do podanej kampanii.
     *
     * @param campaignId    UUID kampanii (scope guard)
     * @param dispositionId UUID dyspozycji
     * @param req           dane do aktualizacji
     * @param tenantId      UUID tenanta
     * @return DTO zaktualizowanej dyspozycji
     * @throws ResourceNotFoundException   gdy dyspozycja nie istnieje (HTTP 404)
     * @throws CrossTenantAccessException gdy dyspozycja nie należy do tej kampanii (HTTP 403)
     */
    CustomDispositionDto updateForCampaign(UUID campaignId, UUID dispositionId,
                                            UpdateCustomDispositionRequest req, UUID tenantId);

    /**
     * Usuwa dyspozycję z zakresu kampanii — weryfikuje, że dyspozycja należy do podanej kampanii.
     *
     * @param campaignId    UUID kampanii (scope guard)
     * @param dispositionId UUID dyspozycji
     * @param tenantId      UUID tenanta
     * @throws ResourceNotFoundException   gdy dyspozycja nie istnieje (HTTP 404)
     * @throws CrossTenantAccessException gdy dyspozycja nie należy do tej kampanii (HTTP 403)
     */
    void deleteFromCampaign(UUID campaignId, UUID dispositionId, UUID tenantId);

    /**
     * Aktualizuje dyspozycję w zakresie kolejki — weryfikuje, że dyspozycja należy do podanej kolejki.
     *
     * @param queueId       UUID kolejki (scope guard)
     * @param dispositionId UUID dyspozycji
     * @param req           dane do aktualizacji
     * @param tenantId      UUID tenanta
     * @return DTO zaktualizowanej dyspozycji
     * @throws ResourceNotFoundException   gdy dyspozycja nie istnieje (HTTP 404)
     * @throws CrossTenantAccessException gdy dyspozycja nie należy do tej kolejki (HTTP 403)
     */
    CustomDispositionDto updateForQueue(UUID queueId, UUID dispositionId,
                                         UpdateCustomDispositionRequest req, UUID tenantId);

    /**
     * Usuwa dyspozycję z zakresu kolejki — weryfikuje, że dyspozycja należy do podanej kolejki.
     *
     * @param queueId       UUID kolejki (scope guard)
     * @param dispositionId UUID dyspozycji
     * @param tenantId      UUID tenanta
     * @throws ResourceNotFoundException   gdy dyspozycja nie istnieje (HTTP 404)
     * @throws CrossTenantAccessException gdy dyspozycja nie należy do tej kolejki (HTTP 403)
     */
    void deleteFromQueue(UUID queueId, UUID dispositionId, UUID tenantId);
}
