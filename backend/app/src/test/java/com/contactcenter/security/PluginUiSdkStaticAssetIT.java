package com.contactcenter.security;

import com.contactcenter.api.telemetry.FrontendLogController;
import com.contactcenter.domain.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test weryfikujący, że {@code /plugin-ui-sdk.js} (EPIC-28, uzupełnienie kontraktu FE-099
 * nieprzewidziane w BE-107) jest serwowany jako plik statyczny PUBLICZNIE, bez wymaganego JWT.
 *
 * <p><strong>Wybór {@code @WebMvcTest} (nie {@code @SpringBootTest}):</strong> projekt nie ma
 * obecnie żadnego działającego testu {@code @SpringBootTest} z pełnym kontekstem aplikacji
 * uruchamianego w izolacji bez zewnętrznej infrastruktury (zweryfikowano: nawet
 * {@code ContactCenterApplicationIT} nie startuje samodzielnie bez PostgreSQL/Redis/RabbitMQ
 * dostępnych na hoście — {@code RedisConfig} to manualna {@code @Configuration} bez warunku
 * profilowego, więc wymaga {@code RedisConnectionFactory} niezależnie od
 * {@code spring.autoconfigure.exclude}; CI w {@code .github/workflows/ci.yml} odpala
 * {@code mvn verify} bez żadnych serwisów). Ten test używa więc {@code @WebMvcTest} +
 * {@code @Import} PRAWDZIWYCH {@link SecurityConfig}/{@link PublicPathsConfig} (to one są
 * przedmiotem testu — CLAUDE.md, reguła "nowy publiczny endpoint — dwa miejsca"), z resztą
 * łańcucha filtrów ({@link JwtAuthFilter}, {@link TenantFilter}, {@link UserDetailsServiceImpl})
 * zmockowaną na poziomie ich zależności (parsera JWT, blacklisty Redis, serwisu użytkowników) —
 * żaden z tych mocków nie jest wywoływany w tym teście, bo żądanie jest BEZ nagłówka
 * {@code Authorization} (patrz {@link JwtAuthFilter#doFilterInternal}, ścieżka "brak tokenu —
 * przepuszczamy, Spring Security oceni autoryzację"), więc to faktycznie
 * {@code SecurityConfig.authorizeHttpRequests().permitAll()} decyduje o wyniku testu.
 *
 * <p>{@code @WebMvcTest(controllers = FrontendLogController.class)} wskazuje kontroler bez
 * żadnych zależności (jedyny taki w projekcie) tylko po to, by ograniczyć component-scan do
 * minimum — sam {@code FrontendLogController} nie jest przedmiotem tego testu i nie jest
 * wywoływany.
 *
 * <p>Kontekst bootstrapowany przez lokalną {@link MinimalBootConfig} (nie
 * {@code ContactCenterApplication}) — {@code ContactCenterApplication} ma
 * {@code @EnableJpaRepositories}, które wymaga realnego {@code EntityManagerFactory}
 * niezależnie od zakresu {@code @WebMvcTest}/{@code @Import}.
 */
@WebMvcTest(controllers = FrontendLogController.class)
@Import({SecurityConfig.class, PasswordEncoderConfig.class, JwtAuthFilter.class, TenantFilter.class,
        UserDetailsServiceImpl.class})
@DisplayName("GET /plugin-ui-sdk.js – plik statyczny PluginUiSdk, publiczny bez JWT (EPIC-28)")
class PluginUiSdkStaticAssetIT {

    /** Minimalna klasa rozruchowa – bez {@code @EnableJpaRepositories}/{@code @EntityScan}. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MinimalBootConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtParser jwtParser;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @MockBean
    private UserService userService;

    @Test
    @DisplayName("zwraca 200 i treść JS bez nagłówka Authorization")
    void pluginUiSdkJs_isServedPublicly_withoutAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/plugin-ui-sdk.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.startsWith("text/javascript"),
                                org.hamcrest.Matchers.startsWith("application/javascript"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("window.PluginUiSdk")));
    }
}
