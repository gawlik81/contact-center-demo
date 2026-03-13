package com.contactcenter;

import com.contactcenter.app.ContactCenterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test integracyjny weryfikujący kryteria akceptacji BE-001:
 * - Aplikacja startuje bez błędów
 * - /actuator/health zwraca status UP
 *
 * <p>Test używa in-memory konfiguracji (H2/mock datasource) – bez prawdziwej bazy.
 * Testy z prawdziwą bazą (Testcontainers) będą w osobnych klasach IT.
 *
 * <p>TODO: Rozszerzyć o Testcontainers (PostgreSQL + Redis + RabbitMQ) gdy
 * środowisko CI będzie gotowe.
 */
@SpringBootTest(
        classes = ContactCenterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // Wyłączamy Flyway w testach jednostkowych (brak prawdziwej bazy)
        "spring.flyway.enabled=false",
        // Wyłączamy JPA schema validation w testach
        "spring.jpa.hibernate.ddl-auto=none",
        // Wyłączamy auto-konfigurację JPA/DataSource (brak bazy w unit testach)
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        // Wyłączamy health checks dla zewnętrznych serwisów
        "management.health.redis.enabled=false",
        "management.health.rabbit.enabled=false",
        "management.health.db.enabled=false",
        "management.endpoint.health.show-details=always"
})
class ContactCenterApplicationIT {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Kryterium akceptacji BE-001:
     * /actuator/health zwraca status UP.
     */
    @Test
    void actuatorHealth_shouldReturnUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * Weryfikuje, że Swagger UI spec jest dostępny.
     */
    @Test
    void openApiSpec_shouldBeAccessible() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk());
    }
}
