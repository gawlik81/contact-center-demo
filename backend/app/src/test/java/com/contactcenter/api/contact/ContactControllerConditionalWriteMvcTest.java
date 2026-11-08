package com.contactcenter.api.contact;

import com.contactcenter.api.GlobalExceptionHandler;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.DispositionRequest;
import com.contactcenter.api.contact.dto.UpdateContactRequest;
import com.contactcenter.domain.campaign.ScheduledCallbackService;
import com.contactcenter.domain.contact.AiSummaryService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.disposition.CustomDispositionService;
import com.contactcenter.domain.exception.ConflictException;
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
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-209: HTTP contract of the conditional writes. A request that lost the compare-and-set (service throws
 * {@link ConflictException}) must surface as 409 and a vanished contact as 404 through the real
 * {@link GlobalExceptionHandler} - for PATCH /{id}, PATCH /{id}/disposition and POST /{id}/abandon.
 * Same infrastructure as {@link ContactControllerReleaseMvcTest}.
 */
@WebMvcTest(controllers = ContactController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ContactController zapisy warunkowe - MockMvc: 200/404/409 (BE-209)")
class ContactControllerConditionalWriteMvcTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    @Import({ContactController.class, GlobalExceptionHandler.class})
    static class MinimalBootConfig {
    }

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BASE = "/api/contacts/" + CONTACT_ID;

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

    private void stubUpdate(Throwable t) {
        when(contactService.updateContact(eq(CONTACT_ID), any(UpdateContactRequest.class), eq(TENANT_ID),
                eq(AGENT_ID), anyBoolean())).thenThrow(t);
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("PATCH /{id}: przegrany CAS -> 409, kontakt zniknal -> 404")
    void patchLostCasIs409AndVanishedIs404() throws Exception {
        stubUpdate(new ConflictException("Kontakt został zmieniony równolegle, spróbuj ponownie: " + CONTACT_ID));
        mockMvc.perform(patch(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(containsString(CONTACT_ID.toString())));

        stubUpdate(new EntityNotFoundException("Nie udało się zaktualizować kontaktu: " + CONTACT_ID));
        mockMvc.perform(patch(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("PATCH /{id}/disposition: przegrany CAS -> 409, kontakt zniknal -> 404")
    void dispositionLostCasIs409AndVanishedIs404() throws Exception {
        when(contactService.setDisposition(eq(CONTACT_ID), any(DispositionRequest.class), eq(TENANT_ID),
                eq(AGENT_ID), anyBoolean()))
                .thenThrow(new ConflictException("Kontakt został zmieniony równolegle: " + CONTACT_ID))
                .thenThrow(new EntityNotFoundException("Nie udało się ustawić disposition: " + CONTACT_ID));
        String body = "{\"dispositionCode\":\"SALE\"}";

        mockMvc.perform(patch(BASE + "/disposition").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        mockMvc.perform(patch(BASE + "/disposition").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("POST /{id}/abandon: przegrany CAS (zmiana wlasciciela) -> 409, kontakt zniknal -> 404, sukces -> 200")
    void abandonLostCasIs409VanishedIs404AndOkIs200() throws Exception {
        when(contactService.abandonContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .thenThrow(new ConflictException("Kontakt został zmieniony równolegle: " + CONTACT_ID))
                .thenThrow(new EntityNotFoundException("Kontakt nie istnieje: " + CONTACT_ID))
                .thenReturn(org.mockito.Mockito.mock(ContactResponse.class));

        mockMvc.perform(post(BASE + "/abandon")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        mockMvc.perform(post(BASE + "/abandon")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(post(BASE + "/abandon")).andExpect(status().isOk());
    }
}
