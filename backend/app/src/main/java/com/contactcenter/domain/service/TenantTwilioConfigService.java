package com.contactcenter.domain.service;

import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigRequest;
import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigResponse;
import com.contactcenter.api.supervisor.twilio.dto.TwilioConnectionTestResult;
import com.contactcenter.domain.event.TwilioConfigChangedEvent;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.TenantTwilioConfig;
import com.contactcenter.domain.repository.TenantTwilioConfigRepository;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.Account;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantTwilioConfigService {

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
        config.setAuthToken(request.authToken());
        config.setApiKeySid(request.apiKeySid());
        config.setApiKeySecret(request.apiKeySecret());
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
    public Optional<TenantTwilioConfigResponse> getConfig(UUID tenantId) {
        return configRepository.findByTenantId(tenantId)
                .map(TenantTwilioConfigResponse::from);
    }

    // =========================================================================
    // Odczyt odszyfrowany (INTERNAL – tylko dla adapterów, nie dla REST)
    // =========================================================================

    @Transactional(readOnly = true)
    public Optional<TenantTwilioConfigDecrypted> getDecryptedConfig(UUID tenantId) {
        return configRepository.findByTenantId(tenantId)
                .map(TenantTwilioConfigDecrypted::from);
    }

    // =========================================================================
    // Usuwanie
    // =========================================================================

    @Transactional
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
