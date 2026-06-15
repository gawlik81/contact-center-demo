package com.contactcenter.domain.tenant;

import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigRequest;
import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigResponse;
import com.contactcenter.api.supervisor.twilio.dto.TwilioConnectionTestResult;
import com.contactcenter.api.supervisor.twilio.dto.TwilioPhoneNumberDto;
import com.contactcenter.api.supervisor.twilio.dto.TwilioPhoneNumberListResponse;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.exception.TwilioApiException;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.Account;
import com.twilio.rest.api.v2010.account.IncomingPhoneNumber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
class TenantTwilioConfigServiceImpl implements TenantTwilioConfigService {

    private static final Pattern ACCOUNT_SID_PATTERN =
            Pattern.compile("^AC[0-9a-fA-F]{32}$");
    private static final Pattern E164_PATTERN =
            Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final TenantTwilioConfigRepository configRepository;
    private final ApplicationEventPublisher eventPublisher;

    // =========================================================================
    // Zapis (upsert)
    // =========================================================================

    @Transactional
    @Override
    public TenantTwilioConfigResponse saveConfig(UUID tenantId, TenantTwilioConfigRequest request) {
        validateAccountSid(request.accountSid());
        if (request.phoneNumber() != null && !request.phoneNumber().isBlank()) {
            validatePhoneNumber(request.phoneNumber());
        }

        TenantTwilioConfig config = configRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantTwilioConfig.builder()
                        .tenantId(tenantId)
                        .build());

        config.setAccountSid(request.accountSid());
        // Secrets: only overwrite when a new non-empty value is explicitly provided.
        // Empty/null means "keep existing" — the frontend sends empty for masked fields.
        if (StringUtils.hasText(request.authToken())) {
            config.setAuthToken(request.authToken());
        }
        config.setApiKeySid(request.apiKeySid()); // not masked – frontend always sends current value
        if (StringUtils.hasText(request.apiKeySecret())) {
            config.setApiKeySecret(request.apiKeySecret());
        }
        config.setTwimlAppSid(request.twimlAppSid());
        config.setPhoneNumber(request.phoneNumber());
        config.setStatusCallbackUrl(request.statusCallbackUrl());
        config.setActive(true);

        TenantTwilioConfig saved = configRepository.save(config);
        log.info("[TenantTwilioConfigService] Zapisano konfigurację Twilio: tenant={}", tenantId);

        eventPublisher.publishEvent(new TwilioConfigChangedEvent(tenantId));

        return TenantTwilioConfigResponse.from(saved);
    }

    // =========================================================================
    // Odczyt (z maskingiem)
    // =========================================================================

    @Transactional(readOnly = true)
    @Override
    public Optional<TenantTwilioConfigResponse> getConfig(UUID tenantId) {
        return configRepository.findByTenantId(tenantId)
                .map(TenantTwilioConfigResponse::from);
    }

    // =========================================================================
    // Odczyt odszyfrowany (INTERNAL – tylko dla adapterów, nie dla REST)
    // =========================================================================

    @Transactional(readOnly = true)
    @Override
    public Optional<TenantTwilioConfigDecrypted> getDecryptedConfig(UUID tenantId) {
        return configRepository.findByTenantId(tenantId)
                .map(TenantTwilioConfigDecrypted::from);
    }

    // =========================================================================
    // Usuwanie
    // =========================================================================

    @Transactional
    @Override
    public void deleteConfig(UUID tenantId) {
        TenantTwilioConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Konfiguracja Twilio nie istnieje dla tenanta: " + tenantId));

        configRepository.delete(config);
        log.info("[TenantTwilioConfigService] Usunięto konfigurację Twilio: tenant={}", tenantId);

        eventPublisher.publishEvent(new TwilioConfigChangedEvent(tenantId));
    }

    // =========================================================================
    // Test połączenia z Twilio API
    // =========================================================================

    @Transactional(readOnly = true)
    @Override
    public TwilioConnectionTestResult testConnection(UUID tenantId) {
        Optional<TenantTwilioConfigDecrypted> configOpt = getDecryptedConfig(tenantId);
        if (configOpt.isEmpty()) {
            return new TwilioConnectionTestResult(false,
                    "Brak konfiguracji Twilio dla tego tenanta", Instant.now());
        }

        TenantTwilioConfigDecrypted config = configOpt.get();
        try {
            TwilioRestClient client = new TwilioRestClient.Builder(
                    config.accountSid(), config.authToken()).build();
            Account.fetcher().fetch(client);
            log.info("[TenantTwilioConfigService] Test połączenia Twilio: SUCCESS, tenant={}", tenantId);
            return new TwilioConnectionTestResult(true, "Połączenie nawiązane pomyślnie", Instant.now());
        } catch (Exception e) {
            log.warn("[TenantTwilioConfigService] Test połączenia Twilio: FAILED, tenant={}, error={}",
                    tenantId, e.getMessage());
            return new TwilioConnectionTestResult(false,
                    "Błąd połączenia z Twilio: " + e.getMessage(), Instant.now());
        }
    }

    // =========================================================================
    // Lista numerów telefonu z konta Twilio
    // =========================================================================

    @Transactional(readOnly = true)
    @Override
    public TwilioPhoneNumberListResponse listActivePhoneNumbers(UUID tenantId) {
        TenantTwilioConfigDecrypted config = getDecryptedConfig(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Brak konfiguracji Twilio dla tenanta: " + tenantId));

        TwilioRestClient client = buildRestClient(config);

        try {
            List<TwilioPhoneNumberDto> result = new ArrayList<>();
            for (IncomingPhoneNumber number : IncomingPhoneNumber.reader().read(client)) {
                result.add(new TwilioPhoneNumberDto(
                        number.getSid(),
                        number.getPhoneNumber() != null ? number.getPhoneNumber().toString() : null,
                        number.getFriendlyName()
                ));
            }
            log.debug("[TenantTwilioConfigService] listActivePhoneNumbers: tenant={}, count={}",
                    tenantId, result.size());
            return new TwilioPhoneNumberListResponse(result);
        } catch (ApiException e) {
            log.warn("[TenantTwilioConfigService] Błąd Twilio API przy listowaniu numerów: tenant={}, code={}, msg={}",
                    tenantId, e.getCode(), e.getMessage());
            throw new TwilioApiException(
                    "Twilio API zwróciło błąd podczas pobierania numerów telefonu: " + e.getMessage(), e);
        }
    }

    private TwilioRestClient buildRestClient(TenantTwilioConfigDecrypted config) {
        if (StringUtils.hasText(config.apiKeySid()) && StringUtils.hasText(config.apiKeySecret())
                && StringUtils.hasText(config.accountSid())) {
            return new TwilioRestClient.Builder(config.apiKeySid(), config.apiKeySecret())
                    .accountSid(config.accountSid())
                    .build();
        }
        return new TwilioRestClient.Builder(config.accountSid(), config.authToken()).build();
    }

    // =========================================================================
    // Walidacja prywatna
    // =========================================================================

    private void validateAccountSid(String accountSid) {
        if (accountSid == null || !ACCOUNT_SID_PATTERN.matcher(accountSid).matches()) {
            throw new IllegalArgumentException(
                    "Nieprawidłowy format Account SID '" + accountSid
                    + "'. Wymagany format: AC + 32 znaki hex (np. ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx)");
        }
    }

    private void validatePhoneNumber(String phoneNumber) {
        if (!E164_PATTERN.matcher(phoneNumber).matches()) {
            throw new IllegalArgumentException(
                    "Nieprawidłowy format numeru telefonu '" + phoneNumber
                    + "'. Wymagany format E.164, np. +48221234567");
        }
    }
}
