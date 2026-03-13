package com.contactcenter.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Konfiguracja OpenAPI 3.0 – dokumentacja REST API.
 *
 * <p>Swagger UI dostępny pod: {@code /swagger-ui.html}
 * Spec JSON: {@code /api-docs}
 *
 * <p>Autentykacja: JWT Bearer token w nagłówku Authorization.
 * Schemat bezpieczeństwa: Bearer Auth (JWT).
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Value("${spring.application.name:contact-center-backend}")
    private String applicationName;

    @Bean
    public OpenAPI contactCenterOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(apiServers())
                .components(securityComponents())
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    private Info apiInfo() {
        return new Info()
                .title("Contact Center SaaS API")
                .version("1.0.0")
                .description("""
                        REST API dla wielotenantowej platformy Contact Center.

                        **Autentykacja:** JWT Bearer token (RS256, TTL 15 min).
                        Endpoint logowania: `POST /api/auth/login`

                        **Multi-tenancy:** tenant_id jest automatycznie wyciągany z JWT claims.
                        Nie trzeba przekazywać tenant_id w URL – jest egzekwowany po stronie serwera.

                        **Role:**
                        - `SYSTEM_ADMIN` – zarządzanie platformą i tenantami
                        - `SUPERVISOR` – zarządzanie agentami i kampaniami w ramach tenanta
                        - `AGENT` – obsługa kontaktów klientów
                        """)
                .contact(new Contact()
                        .name("Contact Center SaaS Team")
                        .email("backend@contactcenter.io"))
                .license(new License()
                        .name("Proprietary")
                        .url("https://contactcenter.io/license"));
    }

    private List<Server> apiServers() {
        return List.of(
                new Server().url("/").description("Bieżące środowisko"),
                new Server().url("http://localhost:8080").description("Lokalne środowisko DEV"),
                new Server().url("https://api.contactcenter.io").description("Produkcja")
        );
    }

    private Components securityComponents() {
        return new Components()
                .addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .name(SECURITY_SCHEME_NAME)
                                .description(
                                        "JWT token uzyskany przez POST /api/auth/login. " +
                                        "Wstaw token w formacie: Bearer {token}"
                                ));
    }
}
