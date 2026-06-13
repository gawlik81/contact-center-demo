package com.contactcenter.api.supervisor.twilio;

import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigRequest;
import com.contactcenter.api.supervisor.twilio.dto.TenantTwilioConfigResponse;
import com.contactcenter.api.supervisor.twilio.dto.TwilioConnectionTestResult;
import com.contactcenter.domain.tenant.TenantTwilioConfigService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TenantTwilioConfigController – REST API konfiguracji Twilio")
@ExtendWith(MockitoExtension.class)
class TenantTwilioConfigControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String VALID_SID = "AC" + "aabbccddeeff00112233445566778899";

    @Mock
    private TenantTwilioConfigService configService;

    @InjectMocks
    private TenantTwilioConfigController controller;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // GET /api/supervisor/twilio-config
    // =========================================================================

    @Nested
    @DisplayName("GET /api/supervisor/twilio-config")
    class GetConfigTests {

        @Test
        @DisplayName("zwraca 200 z konfigurację gdy istnieje")
        void getConfig_exists_returns200() {
            TenantTwilioConfigResponse response = buildResponse();
            when(configService.getConfig(TENANT_ID)).thenReturn(Optional.of(response));

            ResponseEntity<TenantTwilioConfigResponse> result = controller.getConfig();

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isEqualTo(response);
        }

        @Test
        @DisplayName("zwraca 204 gdy brak konfiguracji")
        void getConfig_notFound_returns204() {
            when(configService.getConfig(TENANT_ID)).thenReturn(Optional.empty());

            ResponseEntity<TenantTwilioConfigResponse> result = controller.getConfig();

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(result.getBody()).isNull();
        }
    }

    // =========================================================================
    // PUT /api/supervisor/twilio-config
    // =========================================================================

    @Nested
    @DisplayName("PUT /api/supervisor/twilio-config")
    class SaveConfigTests {

        @Test
        @DisplayName("zwraca 200 z zapisaną konfiguracją")
        void saveConfig_valid_returns200() {
            TenantTwilioConfigRequest request = new TenantTwilioConfigRequest(
                    VALID_SID, "token", null, null, null, "+48221234567", null);
            TenantTwilioConfigResponse response = buildResponse();
            when(configService.saveConfig(eq(TENANT_ID), any())).thenReturn(response);

            ResponseEntity<TenantTwilioConfigResponse> result = controller.saveConfig(request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isEqualTo(response);
            verify(configService).saveConfig(TENANT_ID, request);
        }
    }

    // =========================================================================
    // DELETE /api/supervisor/twilio-config
    // =========================================================================

    @Nested
    @DisplayName("DELETE /api/supervisor/twilio-config")
    class DeleteConfigTests {

        @Test
        @DisplayName("zwraca 204 po usunięciu")
        void deleteConfig_returns204() {
            ResponseEntity<Void> result = controller.deleteConfig();

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(configService).deleteConfig(TENANT_ID);
        }
    }

    // =========================================================================
    // POST /api/supervisor/twilio-config/test
    // =========================================================================

    @Nested
    @DisplayName("POST /api/supervisor/twilio-config/test")
    class TestConnectionTests {

        @Test
        @DisplayName("zwraca 200 z wynikiem testu (success=true)")
        void testConnection_success_returns200() {
            TwilioConnectionTestResult testResult = new TwilioConnectionTestResult(
                    true, "Połączenie nawiązane", Instant.now());
            when(configService.testConnection(TENANT_ID)).thenReturn(testResult);

            ResponseEntity<TwilioConnectionTestResult> result = controller.testConnection();

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().success()).isTrue();
        }

        @Test
        @DisplayName("zwraca 200 z wynikiem testu (success=false) – nie rzuca wyjątku")
        void testConnection_failure_returns200WithFalse() {
            TwilioConnectionTestResult testResult = new TwilioConnectionTestResult(
                    false, "Błąd autoryzacji Twilio", Instant.now());
            when(configService.testConnection(TENANT_ID)).thenReturn(testResult);

            ResponseEntity<TwilioConnectionTestResult> result = controller.testConnection();

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().success()).isFalse();
            assertThat(result.getBody().message()).isNotBlank();
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private TenantTwilioConfigResponse buildResponse() {
        return new TenantTwilioConfigResponse(
                UUID.randomUUID(), TENANT_ID, VALID_SID,
                "●●●●●●●●...abcd", null, null,
                null, "+48221234567", null,
                true, Instant.now(), null
        );
    }
}
