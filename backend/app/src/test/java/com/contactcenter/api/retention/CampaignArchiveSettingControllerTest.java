package com.contactcenter.api.retention;

import com.contactcenter.api.GlobalExceptionHandler;
import com.contactcenter.domain.retention.CampaignArchiveSettingService;
import com.contactcenter.domain.retention.dto.CampaignArchivePreviewDto;
import com.contactcenter.domain.retention.dto.CampaignArchiveSettingDto;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-151: MockMvc dla {@link CampaignArchiveSettingController} — realny {@code @PreAuthorize}
 * (wzorzec {@code GdprControllerTest}: {@code @EnableMethodSecurity}, filtry wyłączone),
 * {@link GlobalExceptionHandler}, izolacja tenantów przez prawdziwy {@link TenantContext}.
 */
@WebMvcTest(controllers = CampaignArchiveSettingController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CampaignArchiveSettingController - MockMvc (BE-151)")
class CampaignArchiveSettingControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    @Import({CampaignArchiveSettingController.class, GlobalExceptionHandler.class})
    static class MinimalBootConfig {
    }

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final String URL_A = "/api/tenants/" + TENANT_A + "/retention/campaign-archive";
    private static final String URL_B = "/api/tenants/" + TENANT_B + "/retention/campaign-archive";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CampaignArchiveSettingService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_A);
        TenantContext.setUserId(USER);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("403 - rola inna niz ADMIN")
    class NonAdmin {

        @Test
        @WithMockUser(roles = "SUPERVISOR")
        @DisplayName("GET/PUT/preview z rola SUPERVISOR -> 403, serwis nietkniety")
        void rejectsNonAdmin() throws Exception {
            mockMvc.perform(get(URL_A)).andExpect(status().isForbidden());
            mockMvc.perform(put(URL_A).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"archiveAfterDays\":90}")).andExpect(status().isForbidden());
            mockMvc.perform(get(URL_A + "/preview").param("archiveAfterDays", "90"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
        }

        @Test
        @WithMockUser(roles = "AGENT")
        @DisplayName("rola AGENT -> 403")
        void rejectsAgent() throws Exception {
            mockMvc.perform(get(URL_A)).andExpect(status().isForbidden());
            verifyNoInteractions(service);
        }
    }

    @Nested
    @DisplayName("izolacja tenantow (ADMIN tenanta A)")
    class TenantIsolation {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("GET/PUT/preview cudzego tenanta B -> 403, serwis nietkniety")
        void crossTenantForbidden() throws Exception {
            mockMvc.perform(get(URL_B)).andExpect(status().isForbidden());
            mockMvc.perform(put(URL_B).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"archiveAfterDays\":90}")).andExpect(status().isForbidden());
            mockMvc.perform(get(URL_B + "/preview").param("archiveAfterDays", "90"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
        }
    }

    @Nested
    @DisplayName("GET")
    class Get {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("zwraca okres efektywny i metadane zakresu (kontrakt dla FE-113)")
        void returnsDefaults() throws Exception {
            when(service.get(TENANT_A)).thenReturn(CampaignArchiveSettingDto.defaults());

            mockMvc.perform(get(URL_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.archiveAfterDays").value(180))
                    .andExpect(jsonPath("$.isDefault").value(true))
                    .andExpect(jsonPath("$.defaultDays").value(180))
                    .andExpect(jsonPath("$.minDays").value(30))
                    .andExpect(jsonPath("$.maxDays").value(730));
        }
    }

    @Nested
    @DisplayName("PUT")
    class Put {

        @ParameterizedTest
        @ValueSource(ints = {30, 180, 730})
        @WithMockUser(roles = "ADMIN")
        @DisplayName("granice i wartosc domyslna zapisane; updatedBy z kontekstu, nie z body")
        void validValues(int days) throws Exception {
            when(service.update(eq(TENANT_A), eq(days), eq(USER)))
                    .thenReturn(CampaignArchiveSettingDto.explicit(days, USER, java.time.Instant.now()));

            mockMvc.perform(put(URL_A).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"archiveAfterDays\":" + days + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.archiveAfterDays").value(days))
                    .andExpect(jsonPath("$.isDefault").value(false));
            verify(service).update(TENANT_A, days, USER);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"archiveAfterDays\":29}",
                "{\"archiveAfterDays\":731}",
                "{\"archiveAfterDays\":null}",
                "{}",
                "{\"archiveAfterDays\":\"abc\"}",
                "{\"archiveAfterDays\":12.5}",
                "{\"archiveAfterDays\":30.7}",
                "{\"archiveAfterDays\":\"90\"}",
                "not-json"})
        @WithMockUser(roles = "ADMIN")
        @DisplayName("29 / 731 / null / brak / tekst / ulamek (takze 30.7) / liczba jako tekst / nie-JSON -> 422")
        void invalidValues(String body) throws Exception {
            mockMvc.perform(put(URL_A).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity());
            verifyNoInteractions(service);
        }
    }

    @Nested
    @DisplayName("GET /preview")
    class Preview {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("deleguje do serwisu i zwraca liczby")
        void returnsCounts() throws Exception {
            when(service.preview(TENANT_A, 30))
                    .thenReturn(new CampaignArchivePreviewDto(180, 30, 4, 1200, 0, 0, 4, 1200, true));

            mockMvc.perform(get(URL_A + "/preview").param("archiveAfterDays", "30"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.campaignsToArchive").value(4))
                    .andExpect(jsonPath("$.contactsToArchive").value(1200))
                    .andExpect(jsonPath("$.currentDays").value(180))
                    .andExpect(jsonPath("$.newDays").value(30))
                    .andExpect(jsonPath("$.additionalContacts").value(1200))
                    .andExpect(jsonPath("$.shortensPeriod").value(true));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("tekst w parametrze -> 422 (serwis nietkniety)")
        void textParam() throws Exception {
            mockMvc.perform(get(URL_A + "/preview").param("archiveAfterDays", "abc"))
                    .andExpect(status().isUnprocessableEntity());
            verifyNoInteractions(service);
        }

        @ParameterizedTest
        @ValueSource(ints = {29, 731})
        @WithMockUser(roles = "ADMIN")
        @DisplayName("IllegalArgumentException z serwisu -> 422 (TYLKO mapowanie wyjatku; "
                + "prawdziwa walidacja zakresu: CampaignArchiveSettingControllerValidationTest)")
        void serviceIllegalArgument_mappedTo422(int days) throws Exception {
            when(service.preview(eq(TENANT_A), eq(days))).thenThrow(new IllegalArgumentException("zakres"));
            mockMvc.perform(get(URL_A + "/preview").param("archiveAfterDays", String.valueOf(days)))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("brak parametru: kontroler przekazuje null do serwisu; 422 z mapowania wyjatku "
                + "(prawdziwa walidacja: CampaignArchiveSettingControllerValidationTest)")
        void missingParam_passesNullToService() throws Exception {
            when(service.preview(eq(TENANT_A), any())).thenThrow(new IllegalArgumentException("wymagany"));
            mockMvc.perform(get(URL_A + "/preview")).andExpect(status().isUnprocessableEntity());
            verify(service).preview(TENANT_A, null);
        }
    }
}
