package com.contactcenter.domain.plugin.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bean executora dedykowanego wyłącznie wywołaniom {@code PluginEntryPoint} przez
 * {@code ExtensionPointPublisher} (ARCHITECTURE.md §11.5/§11.7, EPIC-28, BE-102).
 *
 * <p><strong>Odrębność od innych pool-i (kryterium akceptacji BE-102):</strong> ten bean NIE
 * współdzieli wątków z Tomcat request threads, ani z {@code applicationTaskExecutor}
 * ({@code infrastructure.config.AsyncConfig}, używanym przez audit log/CSV importy/schedulery),
 * ani z {@code lifecycleExecutor} lokalnym dla {@code PluginRuntimeManagerImpl} (BE-101, tylko
 * {@code onActivate}/{@code onDeactivate}). Plugin wolny lub zawieszony w
 * {@code onPreContactConnect}/{@code onManualAction} nie może więc zagłodzić wątków potrzebnych
 * resztce aplikacji — odrębny pool jest warunkiem izolacji, nie tylko optymalizacją.
 *
 * <p>Wymiarowanie poola konfigurowalne przez {@link PluginInvocationProperties}
 * ({@code plugin.invocation.executor-*}) — domyślnie core=8/max=32/queue=200, większe niż
 * {@code applicationTaskExecutor} (core=4/max=16), bo każda blokująca inwokacja
 * ({@code PRE_CONTACT_CONNECT}/{@code MANUAL_ACTION}) zajmuje wątek na czas timeoutu (do 2s/5s)
 * pod normalnym ruchem agentów, a fire-and-forget inwokacje (BE-104) dodatkowo zajmują wątki na
 * dłużej (do 30s).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
class PluginInvocationExecutorConfig {

    private final PluginInvocationProperties properties;

    @Bean(name = "pluginInvocationExecutor", destroyMethod = "shutdown")
    ExecutorService pluginInvocationExecutor() {
        AtomicLong threadCounter = new AtomicLong(0);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "plugin-invocation-" + threadCounter.incrementAndGet());
            // Daemon: nie blokuje zamknięcia JVM jeśli plugin zawiesił wątek na zawsze
            // (ARCHITECTURE.md §11.7 "Timeout = no kill" — wątek może nigdy się nie zakończyć).
            thread.setDaemon(true);
            return thread;
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                properties.getExecutorCorePoolSize(),
                properties.getExecutorMaxPoolSize(),
                60L, java.util.concurrent.TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getExecutorQueueCapacity()),
                threadFactory,
                // Rejection policy: jeśli pool+queue są pełne, wywołujący wątek (request thread)
                // sam wykonuje invocation — degradacja kontrolowana zamiast utraty wywołania;
                // ExtensionPointPublisher i tak otacza całość timeoutem na Future.get(...).
                new ThreadPoolExecutor.CallerRunsPolicy());

        log.info("[PluginInvocationExecutor] ThreadPoolExecutor skonfigurowany: core={}, max={}, "
                        + "queueCapacity={}",
                properties.getExecutorCorePoolSize(), properties.getExecutorMaxPoolSize(),
                properties.getExecutorQueueCapacity());

        return executor;
    }
}
