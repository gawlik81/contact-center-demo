package com.contactcenter.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Punkt wejściowy aplikacji Contact Center SaaS.
 *
 * <p>Architektura: Modularny monolit (Faza 1). Docelowa migracja do mikroserwisów
 * zachowana poprzez ścisłą izolację modułów: api/, domain/, infrastructure/, security/.
 *
 * <p>Profile Spring: {@code dev} – lokalna baza PostgreSQL, {@code prod} – konfiguracja z ENV.
 *
 * <p>Uwaga: {@code @EnableJpaRepositories} i {@code @EntityScan} są wymagane, ponieważ
 * projekt używa jednocześnie Spring Data JPA i Spring Data Redis. Obecność obu modułów
 * powoduje wejście Spring Data w tryb "strict", w którym automatyczne skanowanie repozytoriów
 * i encji jest wyłączone. Jawne wskazanie pakietów rozwiązuje niejednoznaczność.
 *
 * @see com.contactcenter.infrastructure.config
 */
@SpringBootApplication(
        scanBasePackages = "com.contactcenter"
)
@EnableJpaRepositories(basePackages = "com.contactcenter.domain.repository")
@EntityScan(basePackages = "com.contactcenter.domain.model")
@ConfigurationPropertiesScan("com.contactcenter")
@EnableAsync
@EnableScheduling
public class ContactCenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContactCenterApplication.class, args);
    }
}
