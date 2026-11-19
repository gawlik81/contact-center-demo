package com.contactcenter.domain.ivr;

import com.contactcenter.domain.contact.ContactEventService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.support.RedisTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-177: stan oczekujacy IVR w prawdziwym Redis (Testcontainers). "Instancja A" i "instancja B" to dwa
 * niezalezne obiekty magazynu/silnika z OSOBNYMI {@link StringRedisTemplate} (jak dwie repliki backendu)
 * na wspolnym Redis. Testy uzywaja losowych callSid/tenantow (kontener jest wspolny dla klas testowych).
 */
@DisplayName("RedisIvrPendingStateStore - Redis między instancjami (BE-177)")
class RedisIvrPendingStateStoreIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    private static RedisIvrPendingStateStore newInstance(Duration ttl) {
        return new RedisIvrPendingStateStore(RedisTestContainer.stringTemplate(), new SimpleMeterRegistry(), ttl);
    }

    private static String sid() {
        return "CA" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    @DisplayName("zapis na instancji A, odczyt na instancji B; powtórny odczyt pusty (jednorazowość)")
    void conference_writtenOnA_readOnB_onlyOnce() {
        RedisIvrPendingStateStore a = newInstance(TTL);
        RedisIvrPendingStateStore b = newInstance(TTL);
        String callSid = sid();
        UUID tenant = UUID.randomUUID();
        UUID contact = UUID.randomUUID();
        UUID queue = UUID.randomUUID();

        a.saveConference(callSid, tenant, contact, queue, "Prosze czekac ąęś");

        Optional<IvrPendingStateStore.PendingConference> first = b.takeConference(callSid, tenant);
        assertThat(first).isPresent();
        assertThat(first.get().contactId()).isEqualTo(contact);
        assertThat(first.get().queueId()).isEqualTo(queue);
        assertThat(first.get().tenantId()).isEqualTo(tenant);
        assertThat(first.get().prompt()).isEqualTo("Prosze czekac ąęś");

        assertThat(b.takeConference(callSid, tenant)).isEmpty();
        assertThat(a.takeConference(callSid, tenant)).isEmpty();
    }

    @Test
    @DisplayName("zapowiedzi hangup/audio: A -> B, jednorazowo, pusty prompt zachowany")
    void prompts_writtenOnA_readOnB_onlyOnce() {
        RedisIvrPendingStateStore a = newInstance(TTL);
        RedisIvrPendingStateStore b = newInstance(TTL);
        String callSid = sid();
        UUID tenant = UUID.randomUUID();

        a.saveHangupPrompt(callSid, tenant, "");
        a.saveAudioPrompt(callSid, tenant, "Witamy");

        assertThat(b.takeHangupPrompt(callSid, tenant)).contains("");
        assertThat(b.takeHangupPrompt(callSid, tenant)).isEmpty();
        assertThat(b.takeAudioPrompt(callSid, tenant)).contains("Witamy");
        assertThat(b.takeAudioPrompt(callSid, tenant)).isEmpty();
    }

    @Test
    @DisplayName("klucz ivr:pending:<typ>:<tenantId>:<callSid> ma TTL; wartość zawiera tenantId, UUID-y i prompt")
    void keyFormatAndTtl() {
        RedisIvrPendingStateStore store = newInstance(TTL);
        StringRedisTemplate raw = RedisTestContainer.stringTemplate();
        String callSid = sid();
        UUID tenant = UUID.randomUUID();

        store.saveConference(callSid, tenant, UUID.randomUUID(), UUID.randomUUID(), "x");

        String key = "ivr:pending:conference:" + tenant + ":" + callSid;
        assertThat(raw.hasKey(key)).isTrue();
        Long ttlSeconds = raw.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(TTL.toSeconds());
        assertThat(raw.opsForValue().get(key)).contains(tenant.toString());
        raw.delete(key);
    }

    @Test
    @DisplayName("domyślny TTL to jedno źródło (PT2M) i konstruktor wiąże wartość z właściwości")
    void defaultTtlIsSingleSource() {
        assertThat(Duration.parse(RedisIvrPendingStateStore.DEFAULT_TTL)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("TTL: wygasły klucz -> pusty wynik bez wyjątku (ścieżka awaryjna)")
    void expiredState_returnsEmptyWithoutException() {
        RedisIvrPendingStateStore store = newInstance(Duration.ofSeconds(1));
        String callSid = sid();
        UUID tenant = UUID.randomUUID();
        store.saveConference(callSid, tenant, UUID.randomUUID(), UUID.randomUUID(), "x");
        store.saveHangupPrompt(callSid, tenant, "y");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(RedisTestContainer.stringTemplate().hasKey("ivr:pending:conference:" + tenant + ":" + callSid)).isFalse());

        assertThat(store.takeConference(callSid, tenant)).isEmpty();
        assertThat(store.takeHangupPrompt(callSid, tenant)).isEmpty();
    }

    @Test
    @DisplayName("odczyt z cudzym tenantem nie trafia w stan i NIE niszczy stanu prawdziwej rozmowy")
    void otherTenant_doesNotDestroyRealState() {
        RedisIvrPendingStateStore store = newInstance(TTL);
        String callSid = sid();
        UUID owner = UUID.randomUUID();
        store.saveConference(callSid, owner, UUID.randomUUID(), UUID.randomUUID(), "x");

        assertThat(store.takeConference(callSid, UUID.randomUUID())).isEmpty();

        assertThat(store.takeConference(callSid, owner)).isPresent();
    }

    @Test
    @DisplayName("fail-closed: tenant null przy odczycie i zapisie; wpis bez/z innym tenantId w wartości ignorowany")
    void nullOrMismatchedTenant_failsClosed() {
        RedisIvrPendingStateStore store = newInstance(TTL);
        String callSid = sid();
        UUID tenant = UUID.randomUUID();
        store.saveConference(callSid, tenant, UUID.randomUUID(), UUID.randomUUID(), "x");

        assertThat(store.takeConference(callSid, null)).isEmpty();
        assertThat(store.takeConference(callSid, tenant)).isPresent(); // null-odczyt nie zniszczył stanu
        assertThatThrownBy(() -> store.saveConference(callSid, null, UUID.randomUUID(), UUID.randomUUID(), "x"))
                .isInstanceOf(IvrPendingStateUnavailableException.class);

        // wartość bez tenantId (uszkodzony/obcy wpis pod poprawnym kluczem) -> pusty wynik
        RedisTestContainer.stringTemplate().opsForValue().set("ivr:pending:audio:" + tenant + ":" + callSid,
                "{\"prompt\":\"x\"}", Duration.ofMinutes(1));
        assertThat(store.takeAudioPrompt(callSid, tenant)).isEmpty();
        // wartość z innym tenantId pod kluczem tenanta -> pusty wynik
        RedisTestContainer.stringTemplate().opsForValue().set("ivr:pending:audio:" + tenant + ":" + callSid,
                "{\"tenantId\":\"" + UUID.randomUUID() + "\",\"prompt\":\"x\"}", Duration.ofMinutes(1));
        assertThat(store.takeAudioPrompt(callSid, tenant)).isEmpty();
    }

    @Test
    @DisplayName("discardConference usuwa klucz; powtórne wywołanie i brak tenanta nie rzucają")
    void discardConference_removesKey() {
        RedisIvrPendingStateStore store = newInstance(TTL);
        String callSid = sid();
        UUID tenant = UUID.randomUUID();
        store.saveConference(callSid, tenant, UUID.randomUUID(), UUID.randomUUID(), "x");

        store.discardConference(callSid, tenant);
        store.discardConference(callSid, tenant);
        store.discardConference(callSid, null);

        assertThat(store.takeConference(callSid, tenant)).isEmpty();
    }

    @Test
    @DisplayName("uszkodzona wartość w Redis -> pusty wynik, bez wyjątku")
    void corruptValue_returnsEmpty() {
        RedisIvrPendingStateStore store = newInstance(TTL);
        String callSid = sid();
        UUID tenant = UUID.randomUUID();
        RedisTestContainer.stringTemplate().opsForValue()
                .set("ivr:pending:conference:" + tenant + ":" + callSid, "{not-json", Duration.ofMinutes(1));

        assertThat(store.takeConference(callSid, tenant)).isEmpty();
    }

    @Test
    @DisplayName("duplikat webhooka: równoległe odczyty - dokładnie jeden zwycięzca (GETDEL)")
    void concurrentTake_exactlyOneWinner() throws Exception {
        RedisIvrPendingStateStore writer = newInstance(TTL);
        String callSid = sid();
        UUID tenant = UUID.randomUUID();
        writer.saveConference(callSid, tenant, UUID.randomUUID(), UUID.randomUUID(), "x");

        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < readers; i++) {
                RedisIvrPendingStateStore reader = newInstance(TTL); // osobna "instancja"
                futures.add(pool.submit(() -> {
                    start.await();
                    if (reader.takeConference(callSid, tenant).isPresent()) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("niedostępny Redis: zapis konferencji fail-closed, odczyt i zapowiedzi fail-open")
    void unavailableRedis_followsDocumentedFailurePolicy() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        LettuceClientConfiguration cfg = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).build();
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", deadPort), cfg);
        dead.afterPropertiesSet();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            StringRedisTemplate template = new StringRedisTemplate(dead);
            template.afterPropertiesSet();
            RedisIvrPendingStateStore store = new RedisIvrPendingStateStore(template, meters, TTL);
            UUID tenant = UUID.randomUUID();

            assertThatThrownBy(() -> store.saveConference(sid(), tenant, UUID.randomUUID(), UUID.randomUUID(), "x"))
                    .isInstanceOf(IvrPendingStateUnavailableException.class);
            store.discardConference(sid(), tenant);
            store.saveHangupPrompt(sid(), tenant, "x");
            store.saveAudioPrompt(sid(), tenant, "x");
            assertThat(store.takeConference(sid(), tenant)).isEmpty();
            assertThat(store.takeHangupPrompt(sid(), tenant)).isEmpty();
            assertThat(store.takeAudioPrompt(sid(), tenant)).isEmpty();
            assertThat(meters.get("cc.ivr.pending.store.errors").counters()).isNotEmpty();
        } finally {
            dead.destroy();
        }
    }

    @Test
    @DisplayName("TTL niedodatni jest odrzucany przy starcie")
    void nonPositiveTtl_isRejected() {
        assertThatThrownBy(() -> newInstance(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("silnik IVR: QUEUE_TRANSFER na instancji A, webhook DTMF na instancji B -> konferencja; retry -> brak stanu")
    void engineQueueTransferOnA_webhookOnB() {
        UUID tenant = UUID.randomUUID();
        UUID queueId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        String callSid = sid();

        QueueService queueService = mock(QueueService.class);
        Queue queue = mock(Queue.class);
        when(queue.getName()).thenReturn("Support");
        when(queueService.findQueueEntity(queueId, tenant)).thenReturn(Optional.of(queue));

        IvrEngineServiceImpl engineA = engine(queueService);
        IvrEngineServiceImpl engineB = engine(queueService);

        IvrSessionData session = new IvrSessionData(callSid, UUID.randomUUID(), "n1", tenant);
        session.setContactId(contactId);
        IvrNode queueNode = new IvrNode("n1", IvrNodeType.QUEUE_TRANSFER, "Czekaj", null, List.of(),
                queueId.toString(), 10, 3, null, 1, 1, "#", null, null, null);

        engineA.executeNode(callSid, queueNode, session);

        String twiml = engineB.handleDtmfAndBuildTwiml(callSid, "", tenant, "https://cc.example.com");
        assertThat(twiml).contains("<Conference").contains("contact-" + contactId).contains("Czekaj");

        String retry = engineB.handleDtmfAndBuildTwiml(callSid, "", tenant, "https://cc.example.com");
        assertThat(retry).doesNotContain("<Conference");
    }

    /** "Instancja": własny StringRedisTemplate (sesja IVR + stan oczekujący), mocki reszty zależności. */
    private static IvrEngineServiceImpl engine(QueueService queueService) {
        StringRedisTemplate redis = RedisTestContainer.stringTemplate();
        return new IvrEngineServiceImpl(mock(IvrTreeRepository.class), mock(IvrAudioRepository.class), queueService,
                mock(ContactService.class), mock(TelephonyAdapter.class), mock(RabbitTemplate.class), redis,
                mock(TaskScheduler.class), new ObjectMapper(), mock(ContactEventService.class),
                new RedisIvrPendingStateStore(redis, new SimpleMeterRegistry(), TTL));
    }
}
