package com.contactcenter.api.contact;

import com.contactcenter.api.GlobalExceptionHandler;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.domain.campaign.ScheduledCallbackService;
import com.contactcenter.domain.contact.AiSummaryService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.disposition.CustomDispositionService;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-160: {@code POST /api/contacts/{id}/release} over real HTTP semantics (MockMvc): the real
 * {@link GlobalExceptionHandler} maps 404/409 and a real {@code @PreAuthorize} proxy yields 403.
 * Same infrastructure as {@code GdprControllerTest} (BE129-02).
 */
@WebMvcTest(controllers = ContactController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ContactController /release - MockMvc: 200/404/409/403 (BE-160)")
class ContactControllerReleaseMvcTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    @Import({ContactController.class, GlobalExceptionHandler.class})
    static class MinimalBootConfig {
    }

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String URL = "/api/contacts/" + CONTACT_ID + "/release";

    @Autowired
    private MockMvc mockMvc;

    @MockBean private ContactService contactService;
    @MockBean private ScheduledCallbackService scheduledCallbackService;
    @MockBean private AiSummaryService aiSummaryService;
    @MockBean private CustomDispositionService customDispositionService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("200: AGENT, delegacja z tenantId/agentId z TenantContext")
    void ok() throws Exception {
        ContactResponse response = mock(ContactResponse.class);
        when(contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID)).thenReturn(response);

        mockMvc.perform(post(URL)).andExpect(status().isOk());

        verify(contactService).releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);
    }

    @Test
    @WithMockUser(roles = {"SUPERVISOR"})
    @DisplayName("200: SUPERVISOR dozwolony")
    void supervisorAllowed() throws Exception {
        when(contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID)).thenReturn(mock(ContactResponse.class));

        mockMvc.perform(post(URL)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("404: EntityNotFoundException -> HTTP 404")
    void notFound() throws Exception {
        when(contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .thenThrow(new EntityNotFoundException("Kontakt nie istnieje: " + CONTACT_ID));

        mockMvc.perform(post(URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(containsString(CONTACT_ID.toString())));
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("404: niepoprawny UUID w sciezce, serwis nietkniety")
    void invalidUuid() throws Exception {
        mockMvc.perform(post("/api/contacts/mock-1/release")).andExpect(status().isNotFound());

        verifyNoInteractions(contactService);
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("409: InvalidOperationException (PHONE / stan koncowy / cudzy kontakt) -> HTTP 409")
    void conflict() throws Exception {
        when(contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .thenThrow(new InvalidOperationException("Kontakt jest przypisany do innego agenta"));

        mockMvc.perform(post(URL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("403: rola spoza AGENT/SUPERVISOR/ADMIN, serwis nietkniety")
    void forbiddenForOtherRole() throws Exception {
        mockMvc.perform(post(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(contactService);
    }
}
