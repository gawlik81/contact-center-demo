package com.contactcenter.api.customer;

import com.contactcenter.api.GlobalExceptionHandler;
import com.contactcenter.api.customer.dto.AnonymizePreviewResponse;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.gdpr.GdprService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test MockMvc dla {@link GdprController} (BE129-02, code review BE-129 2026-09-24).
 *
 * <p><strong>Dlaczego MockMvc, a nie goły wywołanie metody kontrolera</strong> (jak większość
 * innych testów kontrolerów w tym repo, np. {@code RetentionControllerTest},
 * {@code EmailAttachmentControllerTest} — te pliki wspominają "MockMvc" WYŁĄCZNIE w Javadoc
 * tłumaczącym, że go NIE używają): {@code @PreAuthorize} jest przechwytywane przez AOP
 * ({@code MethodSecurityInterceptor}) — bezpośrednie wywołanie metody Javy na obiekcie kontrolera
 * (nie na proxy Springa) całkowicie je omija, więc nigdy nie udowodni 403. Podobnie mapowanie
 * {@link ConflictException} na HTTP 409 dzieje się w {@link GlobalExceptionHandler}
 * ({@code @RestControllerAdvice}) — wyłącznie DispatcherServlet/{@code ExceptionHandlerExceptionResolver}
 * je stosuje. Oba mechanizmy wymagają rzeczywistego żądania HTTP przez {@link MockMvc}.
 *
 * <p><strong>Infrastruktura:</strong> {@code @WebMvcTest} + lokalny {@code MinimalBootConfig}
 * (ten sam wzorzec co {@code CustomerImportControllerTest}/{@code CampaignImportControllerTest} —
 * {@code ContactCenterApplication} wymaga realnego {@code EntityManagerFactory}, niepotrzebnego
 * tutaj) z jawnym {@code @Import} kontrolera POD TESTEM oraz {@link GlobalExceptionHandler}
 * (leży w pakiecie nadrzędnym {@code api}, więc skanowanie pakietu od {@code MinimalBootConfig}
 * — {@code api.customer} — by go nie znalazło). {@code @AutoConfigureMockMvc(addFilters = false)}
 * wyłącza łańcuch filtrów serwletowych ({@code JwtAuthFilter}/{@code TenantFilter} — niepotrzebne
 * ciężkie zależności jak {@code JwtService}), ale {@code @EnableMethodSecurity} (na
 * {@code MinimalBootConfig}, niezależne od łańcucha filtrów — to osobny mechanizm AOP oparty
 * wyłącznie o {@code SecurityContextHolder}) POZOSTAJE aktywne, więc {@code @PreAuthorize} jest
 * naprawdę egzekwowane. {@link WithMockUser} (spring-security-test) ustawia
 * {@code SecurityContextHolder} przed każdym testem, zastępując rolę, którą normalnie ustawiłby
 * wyłączony {@code JwtAuthFilter}.
 */
@WebMvcTest(controllers = GdprController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("GdprController – MockMvc: @PreAuthorize + mapowanie wyjątków (BE129-02)")
class GdprControllerTest {

    /**
     * Minimalna klasa rozruchowa (bez {@code @EnableJpaRepositories}/{@code @EntityScan}) z
     * jawnym importem kontrolera pod testem i {@link GlobalExceptionHandler} — {@code
     * @EnableAutoConfiguration} sam w sobie nie skanuje żadnego pakietu w poszukiwaniu beanów.
     * {@code @EnableMethodSecurity} czyni {@code @PreAuthorize} realnie egzekwowanym mimo
     * wyłączonego łańcucha filtrów servletowych (patrz Javadoc klasy).
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    @Import({GdprController.class, GlobalExceptionHandler.class})
    static class MinimalBootConfig {
    }

    private static final UUID CUSTOMER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final String EXPORT_URL = "/api/customers/" + CUSTOMER_ID + "/gdpr/export";
    private static final String ANONYMIZE_URL = "/api/customers/" + CUSTOMER_ID + "/gdpr/anonymize";
    private static final String PREVIEW_URL = "/api/customers/" + CUSTOMER_ID + "/gdpr/anonymize/preview";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GdprService gdprService;

    // =========================================================================
    // 403 – @PreAuthorize blokuje rolę AGENT dla WSZYSTKICH TRZECH endpointów
    // =========================================================================

    @Nested
    @DisplayName("@PreAuthorize – rola AGENT odrzucona (403) dla wszystkich trzech endpointów")
    class PreAuthorizeRejectsAgent {

        @Test
        @WithMockUser(roles = "AGENT")
        @DisplayName("POST .../export z rolą AGENT -> 403, serwis NIE wywołany")
        void export_agentRole_returns403_withoutCallingService() throws Exception {
            mockMvc.perform(post(EXPORT_URL))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            verifyNoInteractions(gdprService);
        }

        @Test
        @WithMockUser(roles = "AGENT")
        @DisplayName("POST .../anonymize z rolą AGENT -> 403, serwis NIE wywołany")
        void anonymize_agentRole_returns403_withoutCallingService() throws Exception {
            mockMvc.perform(post(ANONYMIZE_URL))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            verifyNoInteractions(gdprService);
        }

        @Test
        @WithMockUser(roles = "AGENT")
        @DisplayName("GET .../anonymize/preview z rolą AGENT -> 403, serwis NIE wywołany")
        void preview_agentRole_returns403_withoutCallingService() throws Exception {
            mockMvc.perform(get(PREVIEW_URL))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            verifyNoInteractions(gdprService);
        }
    }

    // =========================================================================
    // POST .../export
    // =========================================================================

    @Nested
    @DisplayName("POST .../export")
    class Export {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("happy path (ADMIN): 200, application/zip, deleguje do GdprService.exportCustomerData")
        void happyPath_returns200WithZip() throws Exception {
            byte[] zipBytes = {0x50, 0x4B, 0x03, 0x04};
            when(gdprService.exportCustomerData(CUSTOMER_ID)).thenReturn(zipBytes);

            mockMvc.perform(post(EXPORT_URL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
                    .andExpect(header().string("Content-Disposition", containsString(CUSTOMER_ID.toString())))
                    .andExpect(content().bytes(zipBytes));

            verify(gdprService).exportCustomerData(CUSTOMER_ID);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("404: EntityNotFoundException z serwisu -> HTTP 404 RFC 7807")
        void notFound_mapsTo404() throws Exception {
            when(gdprService.exportCustomerData(CUSTOMER_ID))
                    .thenThrow(new EntityNotFoundException("Klient nie istnieje: " + CUSTOMER_ID));

            mockMvc.perform(post(EXPORT_URL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.detail").value(containsString(CUSTOMER_ID.toString())));
        }
    }

    // =========================================================================
    // POST .../anonymize
    // =========================================================================

    @Nested
    @DisplayName("POST .../anonymize")
    class Anonymize {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("happy path (ADMIN): 204, deleguje do GdprService.anonymizeCustomer")
        void happyPath_admin_returns204() throws Exception {
            doNothing().when(gdprService).anonymizeCustomer(CUSTOMER_ID);

            mockMvc.perform(post(ANONYMIZE_URL))
                    .andExpect(status().isNoContent());

            verify(gdprService).anonymizeCustomer(CUSTOMER_ID);
        }

        @Test
        @WithMockUser(roles = "SUPERVISOR")
        @DisplayName("happy path (SUPERVISOR): hasAnyRole dopuszcza też SUPERVISOR, nie tylko ADMIN -> 204")
        void happyPath_supervisor_returns204() throws Exception {
            doNothing().when(gdprService).anonymizeCustomer(CUSTOMER_ID);

            mockMvc.perform(post(ANONYMIZE_URL))
                    .andExpect(status().isNoContent());

            verify(gdprService).anonymizeCustomer(CUSTOMER_ID);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("404: EntityNotFoundException z serwisu -> HTTP 404 RFC 7807")
        void notFound_mapsTo404() throws Exception {
            doThrow(new EntityNotFoundException("Klient nie istnieje: " + CUSTOMER_ID))
                    .when(gdprService).anonymizeCustomer(CUSTOMER_ID);

            mockMvc.perform(post(ANONYMIZE_URL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("409: ConflictException (rekord w toku) -> HTTP 409 RFC 7807 z customerId w treści")
        void recordInProgress_mapsTo409WithProblemDetail() throws Exception {
            doThrow(new ConflictException(
                    "Klient ma powiązany rekord w trakcie realizacji połączenia (kampania w statusie "
                    + "DIALING lub oddzwonienie w statusie PROCESSING) — anonimizacja odrzucona. "
                    + "Poczekaj na zakończenie połączenia i spróbuj ponownie: customerId=" + CUSTOMER_ID))
                    .when(gdprService).anonymizeCustomer(CUSTOMER_ID);

            mockMvc.perform(post(ANONYMIZE_URL))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.type").value(containsString("conflict")))
                    .andExpect(jsonPath("$.title").value(containsString("Konflikt")))
                    .andExpect(jsonPath("$.detail").value(containsString(CUSTOMER_ID.toString())))
                    .andExpect(jsonPath("$.detail").value(containsString("DIALING")));

            verify(gdprService, times(1)).anonymizeCustomer(CUSTOMER_ID);
        }
    }

    // =========================================================================
    // GET .../anonymize/preview
    // =========================================================================

    @Nested
    @DisplayName("GET .../anonymize/preview")
    class Preview {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("happy path (ADMIN): 200 z licznikami podglądu, deleguje do GdprService.previewAnonymizeCustomer")
        void happyPath_returns200WithCounts() throws Exception {
            AnonymizePreviewResponse response = new AnonymizePreviewResponse(
                    true, Map.of("contact", 5, "scheduled_callback", 1), 3, 2, 4);
            when(gdprService.previewAnonymizeCustomer(CUSTOMER_ID)).thenReturn(response);

            mockMvc.perform(get(PREVIEW_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dryRun").value(true))
                    .andExpect(jsonPath("$.counts.contact").value(5))
                    .andExpect(jsonPath("$.matchedByLink").value(3))
                    .andExpect(jsonPath("$.matchedByIdentifier").value(2))
                    .andExpect(jsonPath("$.s3ObjectsToDelete").value(4));

            verify(gdprService).previewAnonymizeCustomer(CUSTOMER_ID);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("404: EntityNotFoundException z serwisu -> HTTP 404 RFC 7807")
        void notFound_mapsTo404() throws Exception {
            when(gdprService.previewAnonymizeCustomer(CUSTOMER_ID))
                    .thenThrow(new EntityNotFoundException("Klient nie istnieje: " + CUSTOMER_ID));

            mockMvc.perform(get(PREVIEW_URL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }
}
