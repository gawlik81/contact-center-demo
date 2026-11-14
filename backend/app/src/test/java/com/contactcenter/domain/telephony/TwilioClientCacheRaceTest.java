package com.contactcenter.domain.telephony;

import com.contactcenter.domain.tenant.TenantTwilioConfigDecrypted;
import com.contactcenter.domain.tenant.TenantTwilioConfigService;
import com.contactcenter.domain.tenant.TwilioConfigChangedEvent;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.twilio.http.TwilioRestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-179 (S1): an invalidation that arrives while the client is being loaded (config read BEFORE the commit of the
 * change) must not be overwritten by a late {@code put} of the old client.
 */
@DisplayName("TwilioRestClient cache: invalidate w trakcie ładowania nie zostawia starego klienta (BE-179)")
class TwilioClientCacheRaceTest {

    @Test
    @DisplayName("resolveRestClient w toku (stare poświadczenia) + invalidate => wpis nie zostaje w cache")
    void invalidateDuringLoad_doesNotLeaveStaleClient() throws Exception {
        UUID tenant = UUID.randomUUID();
        TwilioProperties props = new TwilioProperties();
        props.setEnabled(true);
        props.setAccountSid("ACtest12345678901234567890123456");
        props.setAuthToken("testAuthToken12345678901234567890");
        com.contactcenter.domain.tenant.TenantService tenantService = mock(com.contactcenter.domain.tenant.TenantService.class);
        when(tenantService.getActiveTenants()).thenReturn(List.of());

        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TenantTwilioConfigService cfgService = mock(TenantTwilioConfigService.class);
        when(cfgService.getDecryptedConfig(any())).thenAnswer(inv -> {
            loading.countDown();
            release.await(10, TimeUnit.SECONDS); // "old" config read, the change commits meanwhile
            return Optional.of(new TenantTwilioConfigDecrypted("AC" + "0".repeat(32), "old-token",
                    null, null, null, null, null));
        });

        TwilioTelephonyAdapter adapter = new TwilioTelephonyAdapter(props, mock(TelephonyEventPublisher.class),
                mock(com.contactcenter.domain.customer.CustomerService.class), tenantService,
                mock(RedisTemplate.class), mock(StringRedisTemplate.class),
                mock(com.contactcenter.domain.recording.TwilioRecordingDownloadService.class), cfgService,
                mock(com.contactcenter.domain.contact.ContactEventService.class),
                mock(com.contactcenter.domain.queue.QueueService.class), mock(RabbitTemplate.class),
                mock(com.contactcenter.domain.customer.CliLookupService.class));
        adapter.init();

        CompletableFuture<Object> resolving = CompletableFuture.supplyAsync(
                () -> ReflectionTestUtils.invokeMethod(adapter, "resolveRestClient", tenant));
        assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> invalidating = CompletableFuture.runAsync(
                () -> adapter.onTwilioConfigChanged(new TwilioConfigChangedEvent(tenant)));
        release.countDown();
        resolving.get(10, TimeUnit.SECONDS);
        invalidating.get(10, TimeUnit.SECONDS);

        @SuppressWarnings("unchecked")
        Cache<UUID, TwilioRestClient> cache =
                (Cache<UUID, TwilioRestClient>) ReflectionTestUtils.getField(adapter, "clientCache");
        assertThat(cache.getIfPresent(tenant)).as("the invalidation wins over the in-flight load").isNull();
    }
}
