package com.contactcenter.api;

import com.contactcenter.api.campaign.CampaignImportController;
import com.contactcenter.domain.campaign.CampaignImportService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test MockMvc dla {@link CampaignImportController} (BE-023 CSV + rozszerzenie o import JSON) —
 * regresja wiązania parametrów POST /api/campaigns/{id}/contacts/import[/json].
 *
 * <p>Wzorowany 1:1 na {@code CustomerImportControllerTest}: w odróżnieniu od pozostałych testów
 * kontrolerów w tym pakiecie, które wywołują metody kontrolera bezpośrednio, ten test celowo
 * wysyła prawdziwe żądanie HTTP przez {@link MockMvc}, żeby zweryfikować RZECZYWISTE wiązanie
 * {@code @RequestParam}/{@code @RequestPart}/{@code @PathVariable} przez Spring — wywołanie metody
 * Javy bezpośrednio pomija cały ten mechanizm i nie wykryłoby regresji nazw parametrów.
 *
 * <p>{@code @AutoConfigureMockMvc(addFilters = false)} wyłącza łańcuch filtrów Spring Security
 * (JwtAuthFilter/TenantFilter) — nie są przedmiotem tego testu; autoryzacja {@code @PreAuthorize}
 * jest weryfikowana deklaratywnie przez code review (ten sam wzorzec co
 * {@code CustomerImportControllerTest}). W odróżnieniu od {@code CustomerImportController},
 * {@link CampaignImportController} odczytuje {@code TenantContext.getTenantId()} bezpośrednio
 * (do logowania) — ustawiany ręcznie w {@code @BeforeEach}, bo filtr {@code TenantFilter} jest
 * wyłączony.
 *
 * <p>Kontekst bootstrapowany przez lokalną {@link MinimalBootConfig} (nie
 * {@code ContactCenterApplication}) — z tych samych powodów co w {@code CustomerImportControllerTest}
 * ({@code ContactCenterApplication} leży w innym pakiecie i wymaga realnego
 * {@code EntityManagerFactory}).
 */
@WebMvcTest(controllers = CampaignImportController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CampaignImportController – wiązanie parametrów POST /api/campaigns/{id}/contacts/import[/json]")
class CampaignImportControllerTest {

    /**
     * Minimalna klasa rozruchowa – bez {@code @EnableJpaRepositories}/{@code @EntityScan}.
     *
     * <p>Jawny {@code @Import(CampaignImportController.class)} jest wymagany z tych samych
     * powodów co w {@code CustomerImportControllerTest} – {@code @EnableAutoConfiguration} sam
     * w sobie nie skanuje żadnego pakietu w poszukiwaniu kontrolerów.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(CampaignImportController.class)
    static class MinimalBootConfig {
    }

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CAMPAIGN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CampaignImportService campaignImportService;

    @BeforeEach
    void setUp() {
        // TenantFilter jest wyłączony (addFilters = false) – kontroler czyta TenantContext
        // bezpośrednio (do logowania), więc trzeba go ustawić ręcznie.
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "contacts.csv", "text/csv", content.getBytes());
    }

    private MockMultipartFile jsonFile(String content) {
        return new MockMultipartFile("file", "contacts.json", "application/json", content.getBytes());
    }

    // =========================================================================
    // POST /{id}/contacts/import – CSV
    // =========================================================================

    @Test
    @DisplayName("CSV – separator/quoteChar/skipDuplicates/columnMapping docierają do serwisu z tymi wartościami")
    void importContacts_bindsAllParams_toServiceCall() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(campaignImportService.initiateImport(any(), any(), any(Boolean.class), any(), any(), any()))
                .thenReturn(jobId);

        MockMultipartFile file = csvFile("phone;first_name\n+48501234567;Jan\n");

        mockMvc.perform(multipart("/api/campaigns/{id}/contacts/import", CAMPAIGN_ID)
                        .file(file)
                        .param("skipDuplicates", "false")
                        .param("columnSeparator", ";")
                        .param("quoteChar", "'")
                        .param("columnMapping", "{\"phone\":0,\"first_name\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));

        verify(campaignImportService).initiateImport(
                eq(CAMPAIGN_ID), any(), eq(false), eq(";"), eq("'"), eq("{\"phone\":0,\"first_name\":1}"));
    }

    @Test
    @DisplayName("CSV – bez parametrów zapytania – używa wartości domyślnych (skipDuplicates=true, przecinek, cudzysłów)")
    void importContacts_noQueryParams_usesDefaults() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(campaignImportService.initiateImport(any(), any(), any(Boolean.class), any(), any(), any()))
                .thenReturn(jobId);

        MockMultipartFile file = csvFile("phone,first_name\n+48501234567,Jan\n");

        mockMvc.perform(multipart("/api/campaigns/{id}/contacts/import", CAMPAIGN_ID).file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));

        verify(campaignImportService).initiateImport(
                eq(CAMPAIGN_ID), any(), eq(true), eq(","), eq("\""), isNull());
    }

    // =========================================================================
    // POST /{id}/contacts/import/json
    // =========================================================================

    @Test
    @DisplayName("JSON – skipDuplicates=false dociera do serwisu z tą wartością")
    void importContactsJson_bindsSkipDuplicatesParam_toServiceCall() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(campaignImportService.initiateJsonImport(any(), any(), any(Boolean.class)))
                .thenReturn(jobId);

        MockMultipartFile file = jsonFile("[{\"phone\":\"+48501234567\",\"firstName\":\"Jan\"}]");

        mockMvc.perform(multipart("/api/campaigns/{id}/contacts/import/json", CAMPAIGN_ID)
                        .file(file)
                        .param("skipDuplicates", "false"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));

        verify(campaignImportService).initiateJsonImport(eq(CAMPAIGN_ID), any(), eq(false));
    }

    @Test
    @DisplayName("JSON – bez parametru skipDuplicates – używa wartości domyślnej true")
    void importContactsJson_noQueryParam_usesDefault() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(campaignImportService.initiateJsonImport(any(), any(), any(Boolean.class)))
                .thenReturn(jobId);

        MockMultipartFile file = jsonFile("[{\"phone\":\"+48501234567\",\"firstName\":\"Jan\"}]");

        mockMvc.perform(multipart("/api/campaigns/{id}/contacts/import/json", CAMPAIGN_ID).file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));

        verify(campaignImportService).initiateJsonImport(eq(CAMPAIGN_ID), any(), eq(true));
    }
}
