package com.contactcenter.domain.retention;

import com.contactcenter.api.GlobalExceptionHandler;
import com.contactcenter.api.retention.CampaignArchiveSettingController;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-151: kontroler + PRAWDZIWY {@link CampaignArchiveSettingServiceImpl} (tylko repozytorium i audyt
 * zamockowane) — dowodzi, że walidacja zakresu endpointu {@code /preview} naprawdę odrzuca wartości
 * ({@code @PreAuthorize} pokrywa {@code CampaignArchiveSettingControllerTest}). Test w pakiecie
 * serwisu, bo implementacja jest package-private.
 */
@DisplayName("CampaignArchiveSettingController + prawdziwa walidacja serwisu (BE-151)")
class CampaignArchiveSettingControllerValidationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PREVIEW_URL = "/api/tenants/" + TENANT + "/retention/campaign-archive/preview";

    private TenantCampaignArchiveSettingRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        repository = mock(TenantCampaignArchiveSettingRepository.class);
        CampaignArchiveSettingServiceImpl service =
                new CampaignArchiveSettingServiceImpl(repository, mock(AuditLogService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(new CampaignArchiveSettingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"29", "731", "0", "-1"})
    @DisplayName("preview: wartosc poza [30,730] -> 422 z komunikatem zakresu, repozytorium nietkniete")
    void outOfRange_rejectedByRealValidation(String days) throws Exception {
        mockMvc.perform(get(PREVIEW_URL).param("archiveAfterDays", days))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("30-730")));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("preview: brak parametru -> 422 (serwis odrzuca null), repozytorium nietkniete")
    void missingParam_rejectedByRealValidation() throws Exception {
        mockMvc.perform(get(PREVIEW_URL))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("wymagany")));
        verifyNoInteractions(repository);
    }
}
