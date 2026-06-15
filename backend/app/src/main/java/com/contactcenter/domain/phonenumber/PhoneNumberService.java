package com.contactcenter.domain.phonenumber;

import com.contactcenter.api.phonenumber.dto.CreatePhoneNumberRequest;
import com.contactcenter.api.phonenumber.dto.PhoneNumberResponse;
import com.contactcenter.api.phonenumber.dto.UpdatePhoneNumberRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

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
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneNumberService {

    /** Wzorzec E.164: + po którym następuje cyfra 1-9 i 6-14 cyfr (łącznie 8-15 cyfr po +). */
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    private final PhoneNumberRepository phoneNumberRepository;
    private final PhoneRoutingRuleRepository phoneRoutingRuleRepository;

    // =========================================================================
    // Tworzenie
    // =========================================================================

    /**
     * Tworzy nowy numer telefonu dla tenanta.
     *
     * @param tenantId UUID tenanta
     * @param request  dane nowego numeru
     * @return DTO nowo utworzonego numeru
     * @throws IllegalArgumentException gdy numer nie jest w formacie E.164 (HTTP 422)
     * @throws ConflictException        gdy numer już istnieje w obrębie tenanta (HTTP 409)
     */
    @Transactional
    public PhoneNumberResponse createPhoneNumber(UUID tenantId, CreatePhoneNumberRequest request) {
        validateE164Format(request.number());

        if (phoneNumberRepository.existsByTenantIdAndNumber(tenantId, request.number())) {
            log.warn("[PhoneNumberService] Duplikat numeru: number='{}', tenant={}",
                    request.number(), tenantId);
            throw new ConflictException(
                    "Numer telefonu '" + request.number() + "' już istnieje w tym tenantcie");
        }

        PhoneNumber phoneNumber = PhoneNumber.builder()
                .tenantId(tenantId)
                .number(request.number())
                .displayName(request.displayName())
                .active(request.isActive() != null ? request.isActive() : true)
                .deleted(false)
                .build();

        PhoneNumber saved = phoneNumberRepository.save(phoneNumber);
        log.info("[PhoneNumberService] Utworzono numer: phoneNumberId={}, number='{}', tenant={}",
                saved.getPhoneNumberId(), saved.getNumber(), tenantId);

        return PhoneNumberResponse.from(saved);
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Zwraca listę wszystkich aktywnych (nie usuniętych) numerów tenanta.
     *
     * @param tenantId UUID tenanta
     * @return lista numerów posortowanych po {@code created_at} ASC
     */
    @Transactional(readOnly = true)
    public List<PhoneNumberResponse> listPhoneNumbers(UUID tenantId) {
        return phoneNumberRepository.findAllByTenantId(tenantId).stream()
                .map(PhoneNumberResponse::from)
                .toList();
    }

    /**
     * Pobiera szczegóły numeru telefonu po ID.
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru
     * @return DTO numeru
     * @throws ResourceNotFoundException gdy numer nie istnieje lub jest usunięty (HTTP 404)
     */
    @Transactional(readOnly = true)
    public PhoneNumberResponse getPhoneNumber(UUID tenantId, UUID phoneNumberId) {
        PhoneNumber phoneNumber = phoneNumberRepository.findById(phoneNumberId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Numer telefonu nie istnieje: " + phoneNumberId));
        return PhoneNumberResponse.from(phoneNumber);
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

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
    @Transactional
    public PhoneNumberResponse updatePhoneNumber(UUID tenantId, UUID phoneNumberId,
                                                  UpdatePhoneNumberRequest request) {
        PhoneNumber phoneNumber = phoneNumberRepository.findById(phoneNumberId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Numer telefonu nie istnieje: " + phoneNumberId));

        if (request.displayName() != null) {
            phoneNumber.setDisplayName(request.displayName());
        }
        if (request.isActive() != null) {
            phoneNumber.setActive(request.isActive());
        }

        PhoneNumber updated = phoneNumberRepository.save(phoneNumber);
        log.info("[PhoneNumberService] Zaktualizowano numer: phoneNumberId={}, tenant={}",
                phoneNumberId, tenantId);

        return PhoneNumberResponse.from(updated);
    }

    // =========================================================================
    // Usuwanie
    // =========================================================================

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
    @Transactional
    public void deletePhoneNumber(UUID tenantId, UUID phoneNumberId) {
        PhoneNumber phoneNumber = phoneNumberRepository.findById(phoneNumberId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Numer telefonu nie istnieje: " + phoneNumberId));

        if (phoneRoutingRuleRepository.existsActiveRulesByPhoneNumberId(phoneNumberId, tenantId)) {
            log.warn("[PhoneNumberService] Próba usunięcia numeru z aktywnymi regułami: phoneNumberId={}, tenant={}",
                    phoneNumberId, tenantId);
            throw new ConflictException(
                    "Nie można usunąć numeru telefonu '" + phoneNumber.getNumber()
                    + "' – istnieją aktywne reguły routingu. Dezaktywuj lub usuń reguły przed usunięciem numeru.");
        }

        phoneNumber.setDeleted(true);
        phoneNumberRepository.save(phoneNumber);
        log.info("[PhoneNumberService] Usunięto numer (soft delete): phoneNumberId={}, number='{}', tenant={}",
                phoneNumberId, phoneNumber.getNumber(), tenantId);
    }

    // =========================================================================
    // Walidacja prywatna
    // =========================================================================

    /**
     * Waliduje format E.164.
     *
     * @param number numer do walidacji
     * @throws IllegalArgumentException gdy numer nie pasuje do wzorca E.164 (HTTP 422)
     */
    private void validateE164Format(String number) {
        if (number == null || !E164_PATTERN.matcher(number).matches()) {
            throw new IllegalArgumentException(
                    "Nieprawidłowy format numeru telefonu '" + number
                    + "'. Wymagany format E.164, np. +48221234567");
        }
    }
}
