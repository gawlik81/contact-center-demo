package com.contactcenter.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

/**
 * Konfiguracja executora dla @Async – operacje asynchroniczne.
 *
 * <p>Używany przez:
 * <ul>
 *   <li>AuditLogService – asynchroniczny zapis przez RabbitMQ (nie blokuje głównego flow)</li>
 *   <li>CSV import jobs – przetwarzanie dużych plików w tle</li>
 *   <li>Schedulery (odświeżanie tokenów social media, retencja nagrań)</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    /**
     * Główny executor dla @Async w aplikacji.
     *
     * <p>Wymiarowanie thread pool:
     * <ul>
     *   <li>Core: 4 (wątki zawsze aktywne)</li>
     *   <li>Max: 16 (skalowanie przy skokach obciążenia)</li>
     *   <li>Queue capacity: 500 (bufor zadań oczekujących)</li>
     * </ul>
     */
    @Bean(name = "applicationTaskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("cc-async-");
        // Przy zamknięciu aplikacji – poczekaj na zakończenie aktywnych zadań
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("[Async] ThreadPoolTaskExecutor skonfigurowany: core={}, max={}, queueCapacity={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 500);
        return executor;
    }

    /**
     * TaskScheduler dla IVR timeoutów DTMF oraz innych zaplanowanych zadań.
     *
     * <p>Używany przez {@link com.contactcenter.domain.ivr.IvrEngineService}
     * do planowania timeoutów oczekiwania na wejście DTMF.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("cc-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();

        log.info("[Scheduler] ThreadPoolTaskScheduler skonfigurowany: poolSize=4");
        return scheduler;
    }

    /**
     * Handler wyjątków z @Async – loguje niekrytyczne błędy asynchroniczne.
     */
    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                log.error("[Async] Błąd w metodzie asynchronicznej: {}.{}() – {}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        throwable.getMessage(),
                        throwable);
    }
}
