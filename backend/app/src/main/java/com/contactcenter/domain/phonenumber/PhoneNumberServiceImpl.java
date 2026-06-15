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
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Implementacja {@link PhoneNumberService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PhoneNumberServiceImpl implements PhoneNumberService {

    /** Wzorzec E.164: + po którym następuje cyfra 1-9 i 6-14 cyfr (łącznie 8-15 cyfr po +). */
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    private final PhoneNumberRepository phoneNumberRepository;
    private final PhoneRoutingRuleRepository phoneRoutingRuleRepository;

    // =========================================================================
    // Tworzenie
    // =========================================================================

    @Override
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

    @Override
    @Transactional(readOnly = true)
    public List<PhoneNumberResponse> listPhoneNumbers(UUID tenantId) {
        return phoneNumberRepository.findAllByTenantId(tenantId).stream()
                .map(PhoneNumberResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PhoneNumberResponse getPhoneNumber(UUID tenantId, UUID phoneNumberId) {
        PhoneNumber phoneNumber = phoneNumberRepository.findById(phoneNumberId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Numer telefonu nie istnieje: " + phoneNumberId));
        return PhoneNumberResponse.from(phoneNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PhoneNumber> findActiveNumber(UUID tenantId, String number) {
        return phoneNumberRepository.findByNumberAndTenantId(number, tenantId)
                .filter(PhoneNumber::isActive);
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

    @Override
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

    @Override
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
