package com.contactcenter.domain.phonenumber;

import com.contactcenter.api.phonenumber.dto.CreatePhoneNumberRequest;
import com.contactcenter.api.phonenumber.dto.PhoneNumberResponse;
import com.contactcenter.api.phonenumber.dto.UpdatePhoneNumberRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający numerami telefonów tenanta.
 *
 * <p>Implementuje logikę biznesową BE-033:
 * <ul>
 *   <li>Walidacja formatu E.164 przed zapisem</li>
 *   <li>Detekcja duplikatów w obrębie tenanta (HTTP 409)</li>
 *   <li>Soft delete z blokadą gdy istnieją aktywne reguły routingu (HTTP 409)</li>
 * </ul>
 */
public interface PhoneNumberService {

    /**
     * Tworzy nowy numer telefonu dla tenanta.
     *
     * @param tenantId UUID tenanta
     * @param request  dane nowego numeru
     * @return DTO nowo utworzonego numeru
     * @throws IllegalArgumentException gdy numer nie jest w formacie E.164 (HTTP 422)
     * @throws ConflictException        gdy numer już istnieje w obrębie tenanta (HTTP 409)
     */
    PhoneNumberResponse createPhoneNumber(UUID tenantId, CreatePhoneNumberRequest request);

    /**
     * Zwraca listę wszystkich aktywnych (nie usuniętych) numerów tenanta.
     *
     * @param tenantId UUID tenanta
     * @return lista numerów posortowanych po {@code created_at} ASC
     */
    List<PhoneNumberResponse> listPhoneNumbers(UUID tenantId);

    /**
     * Pobiera szczegóły numeru telefonu po ID.
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru
     * @return DTO numeru
     * @throws ResourceNotFoundException gdy numer nie istnieje lub jest usunięty (HTTP 404)
     */
    PhoneNumberResponse getPhoneNumber(UUID tenantId, UUID phoneNumberId);

    /**
     * Częściowa aktualizacja numeru telefonu (PATCH).
     *
     * <p>Aktualizuje tylko pola {@code displayName} i {@code isActive}.
     * Numer E.164 jest niezmienny po utworzeniu.
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru
     * @param request       dane do aktualizacji (null = brak zmiany)
     * @return DTO zaktualizowanego numeru
     * @throws ResourceNotFoundException gdy numer nie istnieje (HTTP 404)
     */
    PhoneNumberResponse updatePhoneNumber(UUID tenantId, UUID phoneNumberId, UpdatePhoneNumberRequest request);

    /**
     * Soft delete numeru telefonu.
     *
     * <p>Ustawia {@code is_deleted = true}. Przed usunięciem sprawdza czy istnieją
     * aktywne reguły routingu – jeśli tak, rzuca {@link ConflictException} (HTTP 409).
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru
     * @throws ResourceNotFoundException gdy numer nie istnieje (HTTP 404)
     * @throws ConflictException         gdy istnieją aktywne reguły routingu (HTTP 409)
     */
    void deletePhoneNumber(UUID tenantId, UUID phoneNumberId);

    /**
     * Znajduje aktywny (nie usunięty i {@code is_active = true}) numer telefonu po wartości numeru.
     *
     * <p>Używane przez silnik routingu połączeń przychodzących ({@code IncomingCallRoutingService})
     * do wyszukania konfiguracji numeru na podstawie parametru {@code To} z Twilio.
     *
     * @param tenantId UUID tenanta
     * @param number   numer E.164 (np. "+48221234567")
     * @return Optional z encją gdy numer istnieje, nie jest usunięty i jest aktywny; w przeciwnym razie empty
     */
    Optional<PhoneNumber> findActiveNumber(UUID tenantId, String number);
}
