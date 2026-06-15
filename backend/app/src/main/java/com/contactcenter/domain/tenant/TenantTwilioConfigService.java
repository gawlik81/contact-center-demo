package com.contactcenter.domain.tenant;

import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigRequest;
import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigResponse;
import com.contactcenter.api.supervisor.twilio.dto.TwilioConnectionTestResult;
import com.contactcenter.api.supervisor.twilio.dto.TwilioPhoneNumberListResponse;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający per-tenant konfiguracją Twilio.
 */
public interface TenantTwilioConfigService {

    /**
     * Zapisuje (upsert) konfigurację Twilio dla tenanta.
     *
     * <p>Sekrety (authToken, apiKeySecret) są nadpisywane tylko gdy podano niepustą
     * wartość – puste/null oznacza "zachowaj istniejący" (frontend wysyła puste dla
     * zamaskowanych pól).
     *
     * @param tenantId UUID tenanta
     * @param request  dane konfiguracji Twilio
     * @return DTO z zapisaną konfiguracją (zamaskowaną)
     * @throws IllegalArgumentException gdy format accountSid lub phoneNumber jest nieprawidłowy
     */
    TenantTwilioConfigResponse saveConfig(UUID tenantId, TenantTwilioConfigRequest request);

    /**
     * Zwraca zamaskowaną konfigurację Twilio tenanta (do prezentacji w REST API).
     *
     * @param tenantId UUID tenanta
     * @return konfiguracja (zamaskowana) lub {@link Optional#empty()} gdy nie istnieje
     */
    Optional<TenantTwilioConfigResponse> getConfig(UUID tenantId);

    /**
     * Zwraca odszyfrowaną konfigurację Twilio tenanta.
     *
     * <p>INTERNAL – wyłącznie dla adapterów telephony, nigdy nie zwracać przez REST.
     *
     * @param tenantId UUID tenanta
     * @return odszyfrowana konfiguracja lub {@link Optional#empty()} gdy nie istnieje
     */
    Optional<TenantTwilioConfigDecrypted> getDecryptedConfig(UUID tenantId);

    /**
     * Usuwa konfigurację Twilio tenanta.
     *
     * @param tenantId UUID tenanta
     * @throws ResourceNotFoundException gdy konfiguracja nie istnieje
     */
    void deleteConfig(UUID tenantId);

    /**
     * Testuje połączenie z Twilio API używając zapisanej konfiguracji tenanta.
     *
     * @param tenantId UUID tenanta
     * @return wynik testu połączenia
     */
    TwilioConnectionTestResult testConnection(UUID tenantId);

    /**
     * Zwraca listę aktywnych numerów telefonu z konta Twilio tenanta.
     *
     * @param tenantId UUID tenanta
     * @return lista numerów telefonu
     * @throws ResourceNotFoundException gdy konfiguracja Twilio nie istnieje dla tenanta
     */
    TwilioPhoneNumberListResponse listActivePhoneNumbers(UUID tenantId);
}
