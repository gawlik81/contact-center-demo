package com.contactcenter.domain.tenant;

import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigRequest;
import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigResponse;
import com.contactcenter.api.supervisor.twilio.dto.TwilioConnectionTestResult;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("TenantTwilioConfigService – logika biznesowa")
@ExtendWith(MockitoExtension.class)
class TenantTwilioConfigServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String VALID_ACCOUNT_SID = "AC" + "aabbccddeeff00112233445566778899";
    private static final String VALID_AUTH_TOKEN = "auth-token-secret-value";
    private static final String VALID_PHONE = "+48221234567";

    @Mock
    private TenantTwilioConfigRepository configRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TenantTwilioConfigServiceImpl service;

    // =========================================================================
    // saveConfig()
    // =========================================================================

    @Nested
    @DisplayName("saveConfig() – upsert z walidacją")
    class SaveConfigTests {

        @Test
        @DisplayName("tworzy nowy rekord gdy nie istnieje")
        void saveConfig_noExisting_createsNew() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantTwilioConfigRequest request = new TenantTwilioConfigRequest(
                    VALID_ACCOUNT_SID, VALID_AUTH_TOKEN, null, null, null, VALID_PHONE, null);

            TenantTwilioConfigResponse response = service.saveConfig(TENANT_ID, request);

            assertThat(response.accountSid()).isEqualTo(VALID_ACCOUNT_SID);
            assertThat(response.authToken()).contains("●●●●");
            verify(configRepository).save(any(TenantTwilioConfig.class));
        }

        @Test
        @DisplayName("aktualizuje istniejący rekord (upsert)")
        void saveConfig_existing_updates() {
            TenantTwilioConfig existing = TenantTwilioConfig.builder()
                    .configId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .accountSid("ACold")
                    .authToken("old-token")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(existing));
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TenantTwilioConfigRequest request = new TenantTwilioConfigRequest(
                    VALID_ACCOUNT_SID, VALID_AUTH_TOKEN, null, null, null, null, null);

            service.saveConfig(TENANT_ID, request);

            assertThat(existing.getAccountSid()).isEqualTo(VALID_ACCOUNT_SID);
        }

        @Test
        @DisplayName("publikuje TwilioConfigChangedEvent po zapisie")
        void saveConfig_publishesChangedEvent() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.saveConfig(TENANT_ID, new TenantTwilioConfigRequest(
                    VALID_ACCOUNT_SID, VALID_AUTH_TOKEN, null, null, null, null, null));

            ArgumentCaptor<TwilioConfigChangedEvent> captor =
                    ArgumentCaptor.forClass(TwilioConfigChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("rzuca IllegalArgumentException dla niepoprawnego accountSid")
        void saveConfig_invalidAccountSid_throwsIllegalArgumentException() {
            TenantTwilioConfigRequest request = new TenantTwilioConfigRequest(
                    "INVALID-SID", VALID_AUTH_TOKEN, null, null, null, null, null);

            assertThatThrownBy(() -> service.saveConfig(TENANT_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Account SID");
        }

        @Test
        @DisplayName("rzuca IllegalArgumentException dla niepoprawnego phoneNumber")
        void saveConfig_invalidPhoneNumber_throwsIllegalArgumentException() {
            TenantTwilioConfigRequest request = new TenantTwilioConfigRequest(
                    VALID_ACCOUNT_SID, VALID_AUTH_TOKEN, null, null, null, "not-e164", null);

            assertThatThrownBy(() -> service.saveConfig(TENANT_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("E.164");
        }

        @Test
        @DisplayName("akceptuje null phoneNumber (pole opcjonalne)")
        void saveConfig_nullPhoneNumber_accepted() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatNoException().isThrownBy(() ->
                    service.saveConfig(TENANT_ID, new TenantTwilioConfigRequest(
                            VALID_ACCOUNT_SID, VALID_AUTH_TOKEN, null, null, null, null, null)));
        }
    }

    // =========================================================================
    // getConfig() – masking
    // =========================================================================

    @Nested
    @DisplayName("getConfig() – masking wrażliwych pól")
    class GetConfigTests {

        @Test
        @DisplayName("zwraca empty gdy brak konfiguracji")
        void getConfig_noConfig_returnsEmpty() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThat(service.getConfig(TENANT_ID)).isEmpty();
        }

        @Test
        @DisplayName("maskuje authToken w response")
        void getConfig_masksAuthToken() {
            TenantTwilioConfig config = TenantTwilioConfig.builder()
                    .configId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .accountSid(VALID_ACCOUNT_SID)
                    .authToken("super-secret-token-a3f2")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(config));

            TenantTwilioConfigResponse response = service.getConfig(TENANT_ID).orElseThrow();

            assertThat(response.authToken()).doesNotContain("super-secret-token");
            assertThat(response.authToken()).endsWith("a3f2");
            assertThat(response.authToken()).contains("●");
        }

        @Test
        @DisplayName("maskuje apiKeySecret w response")
        void getConfig_masksApiKeySecret() {
            TenantTwilioConfig config = TenantTwilioConfig.builder()
                    .configId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .accountSid(VALID_ACCOUNT_SID)
                    .authToken("token")
                    .apiKeySecret("my-secret-key-xyz9")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(config));

            TenantTwilioConfigResponse response = service.getConfig(TENANT_ID).orElseThrow();

            assertThat(response.apiKeySecret()).endsWith("xyz9");
            assertThat(response.apiKeySecret()).contains("●");
        }
    }

    // =========================================================================
    // getDecryptedConfig()
    // =========================================================================

    @Nested
    @DisplayName("getDecryptedConfig() – plaintext dla adapterów")
    class GetDecryptedConfigTests {

        @Test
        @DisplayName("zwraca plaintext authToken (nie maskowany)")
        void getDecryptedConfig_returnsPlaintext() {
            TenantTwilioConfig config = TenantTwilioConfig.builder()
                    .configId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .accountSid(VALID_ACCOUNT_SID)
                    .authToken("plaintext-secret")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(config));

            TenantTwilioConfigDecrypted decrypted = service.getDecryptedConfig(TENANT_ID).orElseThrow();

            assertThat(decrypted.authToken()).isEqualTo("plaintext-secret");
            assertThat(decrypted.accountSid()).isEqualTo(VALID_ACCOUNT_SID);
        }
    }

    // =========================================================================
    // deleteConfig()
    // =========================================================================

    @Nested
    @DisplayName("deleteConfig() – usuwanie z eventem")
    class DeleteConfigTests {

        @Test
        @DisplayName("usuwa rekord i publikuje event")
        void deleteConfig_deletesAndPublishesEvent() {
            TenantTwilioConfig config = TenantTwilioConfig.builder()
                    .configId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .accountSid(VALID_ACCOUNT_SID)
                    .authToken("token")
                    .build();
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(config));

            service.deleteConfig(TENANT_ID);

            verify(configRepository).delete(config);
            verify(eventPublisher).publishEvent(any(TwilioConfigChangedEvent.class));
        }

        @Test
        @DisplayName("rzuca ResourceNotFoundException gdy brak konfiguracji")
        void deleteConfig_noConfig_throwsResourceNotFoundException() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteConfig(TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =========================================================================
    // testConnection() – bez prawdziwego Twilio (mockujemy tylko brak konfiguracji)
    // =========================================================================

    @Nested
    @DisplayName("testConnection() – obsługa braku konfiguracji")
    class TestConnectionTests {

        @Test
        @DisplayName("zwraca success=false gdy brak konfiguracji (nie rzuca wyjątku)")
        void testConnection_noConfig_returnsFalse() {
            when(configRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            TwilioConnectionTestResult result = service.testConnection(TENANT_ID);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isNotBlank();
            assertThat(result.testedAt()).isNotNull();
        }
    }
}
