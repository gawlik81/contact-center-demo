package com.contactcenter.support;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Minimalny kontekst Springa z PRAWDZIWYM Hibernate/{@code EntityManager}, {@code JpaTransactionManager}
 * i proxy {@code @Transactional} — do testów integracyjnych beanów domenowych na prawdziwej bazie
 * ({@link PostgresTestDatabase}) bez uruchamiania całej aplikacji (Redis, RabbitMQ, JWT, Twilio…).
 *
 * <p>Odwzorowuje to, co w aplikacji robi Spring Boot: {@code @PersistenceContext} wstrzykiwany do
 * repozytoriów ({@code PersistenceAnnotationBeanPostProcessor}), proxy CGLIB z
 * {@code @EnableTransactionManagement(proxyTargetClass = true)}, globalny timeout zapytań JPA
 * 5 s ({@code application.yml}) oraz {@code ddl-auto: none} (schemat = Flyway).
 *
 * <p>Repozytoria i serwisy są package-private, więc testy siedzą w ich pakiecie i podają klasy
 * ({@code X.class}) do {@link #create}.
 */
public final class JpaTestContext {

    private JpaTestContext() {
    }

    /** Konfiguracja włączająca proxy {@code @Transactional} (jak {@code TransactionAutoConfiguration}). */
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionInfrastructure {
    }

    /**
     * Buduje i odświeża kontekst.
     *
     * @param dataSource   źródło danych (pula z {@link PostgresTestDatabase})
     * @param entityTypes  encje JPA potrzebne beanom (np. {@code EmailMessage.class})
     * @param beanClasses  klasy beanów do zarejestrowania (repozytoria, serwisy); zależności
     *                     nieobjęte kontekstem dorejestruj przez {@code ctx.registerBean} w
     *                     {@code prepare}
     * @param prepare      hook wywoływany PRZED {@code refresh()} — rejestracja mocków/singletonów
     * @return odświeżony kontekst (zamknij w {@code @AfterAll})
     */
    public static AnnotationConfigApplicationContext create(
            DataSource dataSource,
            Class<?>[] entityTypes,
            Class<?>[] beanClasses,
            Consumer<AnnotationConfigApplicationContext> prepare) {

        LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(dataSource);
        emfBean.setManagedTypes(PersistenceManagedTypes.of(
                Arrays.stream(entityTypes).map(Class::getName).toArray(String[]::new)));
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emfBean.setPersistenceUnitName("test-pu");
        emfBean.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "none",
                "jakarta.persistence.query.timeout", 5000));
        emfBean.afterPropertiesSet();
        EntityManagerFactory emf = emfBean.getObject();

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getBeanFactory().registerSingleton("dataSource", dataSource);
        ctx.getBeanFactory().registerSingleton("entityManagerFactory", emf);
        ctx.getBeanFactory().registerSingleton("transactionManager", new JpaTransactionManager(emf));
        ctx.register(TransactionInfrastructure.class);
        ctx.register(beanClasses);
        if (prepare != null) {
            prepare.accept(ctx);
        }
        ctx.refresh();
        return ctx;
    }

    /** Zamyka kontekst wraz z {@code EntityManagerFactory} (rejestrowanym jako singleton — nie jest zamykany przez kontekst). */
    public static void close(AnnotationConfigApplicationContext ctx) {
        if (ctx == null) {
            return;
        }
        EntityManagerFactory emf = ctx.getBean(EntityManagerFactory.class);
        ctx.close();
        if (emf.isOpen()) {
            emf.close();
        }
    }
}
