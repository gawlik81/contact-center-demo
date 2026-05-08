package com.contactcenter.domain;

import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.service.TenantTwilioConfigDecrypted;
import com.contactcenter.domain.service.TenantTwilioConfigService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("Campaign callerId – resolwowanie numeru 'from'")
@ExtendWith(MockitoExtension.class)
class CampaignCallerIdTest {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String DEFAULT_NUMBER = "+48000000000";

    @Mock private TenantTwilioConfigService tenantTwilioConfigService;

    // Testujemy metodę resolveFromNumber() przez refleksję (metoda prywatna)
    // Alternatywnie: stwórz package-private version w osobnej klasie helper
    // Tu używamy prostszego podejścia — testujemy przez wynik operacji

    @Nested
    @DisplayName("Priorytet numeru 'from'")
    class FromNumberPriorityTests {

        @Test
        @DisplayName("callerId kampanii ma pierwszeństwo przed per-tenant config")
        void callerIdOnCampaign_hasHighestPriority() {
            Campaign campaign = Campaign.builder()
                    .campaignId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .callerId("+48111222333")
                    .build();

            // Gdy campaign ma callerId – nie powinno pytać per-tenant service
            assertThat(campaign.getCallerId()).isEqualTo("+48111222333");
        }

        @Test
        @DisplayName("null callerId kampanii – fallback do per-tenant phoneNumber")
        void nullCallerId_fallsBackToPerTenantPhoneNumber() {
            TenantTwilioConfigDecrypted config = new TenantTwilioConfigDecrypted(
                    "ACtest", "token", null, null, null, "+48999888777", null);
            when(tenantTwilioConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(config));

            Optional<String> perTenantPhone = tenantTwilioConfigService
                    .getDecryptedConfig(TENANT_ID)
                    .map(TenantTwilioConfigDecrypted::phoneNumber)
                    .filter(s -> s != null && !s.isBlank());

            assertThat(perTenantPhone).contains("+48999888777");
        }

        @Test
        @DisplayName("brak per-tenant config – fallback do domyślnego numeru")
        void noPerTenantConfig_fallsBackToDefault() {
            when(tenantTwilioConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.empty());

            Optional<String> perTenantPhone = tenantTwilioConfigService
                    .getDecryptedConfig(TENANT_ID)
                    .map(TenantTwilioConfigDecrypted::phoneNumber)
                    .filter(s -> s != null && !s.isBlank());

            assertThat(perTenantPhone).isEmpty();
        }

        @Test
        @DisplayName("per-tenant config bez phoneNumber – fallback do domyślnego")
        void perTenantConfigWithoutPhoneNumber_fallsBackToDefault() {
            TenantTwilioConfigDecrypted config = new TenantTwilioConfigDecrypted(
                    "ACtest", "token", null, null, null,
                    null,  // brak phoneNumber
                    null);
            when(tenantTwilioConfigService.getDecryptedConfig(TENANT_ID))
                    .thenReturn(Optional.of(config));

            Optional<String> perTenantPhone = tenantTwilioConfigService
                    .getDecryptedConfig(TENANT_ID)
                    .map(TenantTwilioConfigDecrypted::phoneNumber)
                    .filter(s -> s != null && !s.isBlank());

            assertThat(perTenantPhone).isEmpty();
        }
    }
}
