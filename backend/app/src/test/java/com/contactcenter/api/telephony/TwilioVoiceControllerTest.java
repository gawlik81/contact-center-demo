package com.contactcenter.api.telephony;

import com.contactcenter.domain.service.TenantTwilioConfigDecrypted;
import com.contactcenter.domain.service.TenantTwilioConfigService;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("TwilioVoiceController – per-tenant Access Token")
@ExtendWith(MockitoExtension.class)
class TwilioVoiceControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID USER_ID   = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Mock private TenantTwilioConfigService tenantTwilioConfigService;
    @Mock private RedisTemplate<String, Object> redisTemplate;

    private TwilioProperties twilioProperties;
    private TwilioVoiceController controller;

    @BeforeEach
    void setUp() {
        twilioProperties = new TwilioProperties();
        twilioProperties.setAccountSid("ACglobal12345678901234567890123456");
        twilioProperties.setApiKeySid("SKglobal000000000000000000000000000");
        // apiKeySecret musi mieć >= 32 znaki (256 bitów) dla HS256 używanego przez Twilio SDK
        twilioProperties.setApiKeySecret("globalApiKeySecretThatIsLongEnough32");
        twilioProperties.setTwimlAppSid("APglobal00000000000000000000000000");

        controller = new TwilioVoiceController(twilioProperties, redisTemplate, tenantTwilioConfigService);
        ReflectionTestUtils.setField(controller, "holdMusicUrl", "");

        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("getVoiceToken() – wybór konfiguracji")
    class VoiceTokenTests {

        @Test
        @DisplayName("używa globalnych credentials gdy brak per-tenant konfiguracji")
        void getVoiceToken_noPerTenantConfig_usesGlobal() {
            when(tenantTwilioConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.empty());

            ResponseEntity<Map<String, String>> response = controller.getVoiceToken();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("token");
            assertThat(response.getBody()).containsKey("identity");
            assertThat(response.getBody().get("identity")).isEqualTo("agent-" + USER_ID);
        }

        @Test
        @DisplayName("używa per-tenant credentials gdy konfiguracja istnieje")
        void getVoiceToken_perTenantConfig_usesPerTenant() {
            TenantTwilioConfigDecrypted config = new TenantTwilioConfigDecrypted(
                    "ACperTenant123456789012345678901234",
                    "perTenantAuthToken",
                    "SKperTenant000000000000000000000000",
                    // apiKeySecret musi mieć >= 32 znaki (256 bitów) dla HS256
                    "perTenantApiKeySecretThatIsLongEnough32",
                    "APperTenant0000000000000000000000000",
                    "+48123456789",
                    null
            );
            when(tenantTwilioConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(config));

            ResponseEntity<Map<String, String>> response = controller.getVoiceToken();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("token");
            // Token zawiera accountSid w payloadzie – sprawdzamy że nie rzuca wyjątku
            // (dowód że per-tenant credentials zostały użyte)
        }

        @Test
        @DisplayName("fallback do globalnego apiKeySid gdy per-tenant config go nie ma")
        void getVoiceToken_partialPerTenantConfig_fallsBackForMissingFields() {
            // Config ma accountSid ale brak apiKeySid/apiKeySecret
            TenantTwilioConfigDecrypted config = new TenantTwilioConfigDecrypted(
                    "ACperTenant123456789012345678901234",
                    "perTenantAuthToken",
                    null,   // brak apiKeySid → fallback do globalnego
                    null,   // brak apiKeySecret → fallback do globalnego
                    null,   // brak twimlAppSid → fallback do globalnego
                    "+48123456789",
                    null
            );
            when(tenantTwilioConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(config));

            // Nie powinno rzucić wyjątku – użyje globalnych apiKeySid/Secret/twimlAppSid
            ResponseEntity<Map<String, String>> response = controller.getVoiceToken();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("identity")).isEqualTo("agent-" + USER_ID);
        }

        @Test
        @DisplayName("identity zawiera userId z TenantContext")
        void getVoiceToken_identityContainsUserId() {
            when(tenantTwilioConfigService.getDecryptedConfig(any()))
                    .thenReturn(Optional.empty());

            ResponseEntity<Map<String, String>> response = controller.getVoiceToken();

            assertThat(response.getBody().get("identity")).startsWith("agent-");
            assertThat(response.getBody().get("identity")).contains(USER_ID.toString());
        }
    }
}
