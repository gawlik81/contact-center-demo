package com.contactcenter.domain.ivr;

import com.contactcenter.api.ivr.dto.CreateIvrRequest;
import com.contactcenter.api.ivr.dto.IvrResponse;
import com.contactcenter.api.ivr.dto.UpdateIvrRequest;
import com.contactcenter.domain.exception.ConflictException;
import jakarta.persistence.EntityNotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający drzewami IVR (CRUD).
 *
 * <p>Dostępny dla ról SUPERVISOR i ADMIN. Zapewnia:
 * <ul>
 *   <li>Tworzenie i aktualizację drzew IVR</li>
 *   <li>Aktywację i deaktywację drzew IVR (wiele drzew może być aktywnych jednocześnie)</li>
 *   <li>Usunięcie drzewa IVR</li>
 * </ul>
 */
public interface IvrService {

    /**
     * Zwraca listę wszystkich drzew IVR dla tenanta.
     *
     * @param tenantId UUID tenanta
     * @return lista DTO drzew IVR
     */
    List<IvrResponse> listIvrTrees(UUID tenantId);

    /**
     * Pobiera drzewo IVR po identyfikatorze.
     *
     * @param ivrId    UUID drzewa IVR
     * @param tenantId UUID tenanta
     * @return DTO drzewa IVR
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     */
    IvrResponse getIvrTree(UUID ivrId, UUID tenantId);

    /**
     * Tworzy nowe drzewo IVR.
     *
     * @param request  dane nowego drzewa IVR
     * @param tenantId UUID tenanta
     * @param userId   UUID użytkownika tworzącego
     * @return DTO nowo utworzonego drzewa IVR
     */
    IvrResponse createIvrTree(CreateIvrRequest request, UUID tenantId, UUID userId);

    /**
     * Aktualizuje drzewo IVR (PATCH semantics – pola null ignorowane).
     *
     * @param ivrId    UUID drzewa IVR
     * @param request  dane do aktualizacji
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego drzewa IVR
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     */
    IvrResponse updateIvrTree(UUID ivrId, UpdateIvrRequest request, UUID tenantId);

    /**
     * Usuwa drzewo IVR (fizyczne usunięcie – tabela nie ma is_deleted).
     *
     * @param ivrId    UUID drzewa IVR
     * @param tenantId UUID tenanta
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     */
    void deleteIvrTree(UUID ivrId, UUID tenantId);

    /**
     * Aktywuje drzewo IVR.
     *
     * <p>Wiele drzew IVR może być aktywnych jednocześnie per tenant –
     * które drzewo obsługuje ruch przychodzący decydują reguły {@code phone_routing_rule}.
     *
     * @param ivrId    UUID drzewa IVR do aktywacji
     * @param tenantId UUID tenanta
     * @return DTO aktywowanego drzewa IVR
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     */
    IvrResponse activateIvrTree(UUID ivrId, UUID tenantId);

    /**
     * Deaktywuje drzewo IVR.
     *
     * <p>Blokuje deaktywację gdy drzewo jest przypisane do co najmniej jednej reguły routingu.
     * Przed deaktywacją należy usunąć drzewo ze wszystkich reguł {@code phone_routing_rule}.
     *
     * @param ivrId    UUID drzewa IVR do deaktywacji
     * @param tenantId UUID tenanta
     * @return DTO deaktywowanego drzewa IVR
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     * @throws ConflictException       gdy drzewo jest przypisane do reguły routingu (HTTP 409)
     */
    IvrResponse deactivateIvrTree(UUID ivrId, UUID tenantId);

    /**
     * Sprawdza czy drzewo IVR o podanym identyfikatorze istnieje i jest aktywne.
     *
     * <p>Używane przez {@code domain.phonenumber.PhoneRoutingRuleService} do walidacji
     * {@code ivrTreeId} przy tworzeniu/aktualizacji reguły routingu – reguła może wskazywać
     * tylko na aktywne drzewo IVR.
     *
     * @param tenantId UUID tenanta
     * @param ivrId    UUID drzewa IVR
     * @return {@code true} gdy drzewo istnieje i ma {@code is_active = true}
     */
    boolean existsActiveIvrTree(UUID tenantId, UUID ivrId);
}
