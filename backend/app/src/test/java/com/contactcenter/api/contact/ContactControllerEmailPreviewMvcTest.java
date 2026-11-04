package com.contactcenter.api.contact;

import com.contactcenter.api.GlobalExceptionHandler;
import com.contactcenter.api.contact.dto.ContactRecordingUrlResponse;
import com.contactcenter.api.contact.dto.EmailPreviewResponse;
import com.contactcenter.domain.campaign.ScheduledCallbackService;
import com.contactcenter.domain.contact.AiSummaryService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.disposition.CustomDispositionService;
import com.contactcenter.domain.exception.InvalidOperationException;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/contacts/{id}/email-preview} (contract: whole conversation) and
 * {@code GET /api/contacts/{id}/email-messages/{messageId}/eml} over MockMvc: JSON field names, role gate
 * and error mapping (same infrastructure as {@code ContactControllerReleaseMvcTest}).
 */
@WebMvcTest(controllers = ContactController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ContactController /email-preview i /email-messages/{id}/eml - MockMvc")
class ContactControllerEmailPreviewMvcTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    @Import({ContactController.class, GlobalExceptionHandler.class})
    static class MinimalBootConfig {
    }

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MESSAGE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String PREVIEW_URL = "/api/contacts/" + CONTACT_ID + "/email-preview";
    private static final String EML_URL = "/api/contacts/" + CONTACT_ID + "/email-messages/" + MESSAGE_ID + "/eml";

    @Autowired
    private MockMvc mockMvc;

    @MockBean private ContactService contactService;
    @MockBean private ScheduledCallbackService scheduledCallbackService;
    @MockBean private AiSummaryService aiSummaryService;
    @MockBean private CustomDispositionService customDispositionService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUserRole("AGENT");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("email-preview 200: kontrakt JSON {messages[...], totalMessages, truncated}; AGENT => isAgent=true z userId")
    void previewContract() throws Exception {
        Instant at = Instant.parse("2026-04-17T10:30:00Z");
        EmailPreviewResponse.MessageDto dto = new EmailPreviewResponse.MessageDto(MESSAGE_ID, "a@a.pl", "b@b.pl",
                null, "Temat", "<p>x</p>", "x", null, at, "OUTBOUND",
                List.of(new EmailPreviewResponse.AttachmentDto("f.pdf", "application/pdf", 12L, "k")), true);
        when(contactService.getEmailPreview(CONTACT_ID, TENANT_ID, USER_ID, true))
                .thenReturn(new EmailPreviewResponse(List.of(dto), 1, false));

        mockMvc.perform(get(PREVIEW_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMessages").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.messages[0].messageId").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.messages[0].from").value("a@a.pl"))
                .andExpect(jsonPath("$.messages[0].to").value("b@b.pl"))
                .andExpect(jsonPath("$.messages[0].cc").doesNotExist())
                .andExpect(jsonPath("$.messages[0].subject").value("Temat"))
                .andExpect(jsonPath("$.messages[0].bodyHtml").value("<p>x</p>"))
                .andExpect(jsonPath("$.messages[0].bodyText").value("x"))
                .andExpect(jsonPath("$.messages[0].receivedAt").doesNotExist())
                .andExpect(jsonPath("$.messages[0].messageAt").value("2026-04-17T10:30:00Z"))
                .andExpect(jsonPath("$.messages[0].direction").value("OUTBOUND"))
                .andExpect(jsonPath("$.messages[0].attachments[0].filename").value("f.pdf"))
                .andExpect(jsonPath("$.messages[0].attachments[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.messages[0].attachments[0].sizeBytes").value(12))
                .andExpect(jsonPath("$.messages[0].attachments[0].s3Key").value("k"))
                .andExpect(jsonPath("$.messages[0].emlAvailable").value(true));
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    @DisplayName("email-preview: SUPERVISOR => isAgent=false")
    void previewSupervisorIsNotAgent() throws Exception {
        TenantContext.setUserRole("SUPERVISOR");
        when(contactService.getEmailPreview(CONTACT_ID, TENANT_ID, USER_ID, false))
                .thenReturn(new EmailPreviewResponse(List.of(), 0, false));

        mockMvc.perform(get(PREVIEW_URL)).andExpect(status().isOk());

        verify(contactService).getEmailPreview(CONTACT_ID, TENANT_ID, USER_ID, false);
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("email-preview: cudzy kontakt agenta -> 409, kanal nie-EMAIL -> 400")
    void previewErrorMapping() throws Exception {
        doThrow(new InvalidOperationException("cudzy kontakt"))
                .when(contactService).getEmailPreview(CONTACT_ID, TENANT_ID, USER_ID, true);
        mockMvc.perform(get(PREVIEW_URL)).andExpect(status().isConflict());

        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kontakt nie jest kanalem EMAIL"))
                .when(contactService).getEmailPreview(CONTACT_ID, TENANT_ID, USER_ID, true);
        mockMvc.perform(get(PREVIEW_URL)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Kontakt nie jest kanalem EMAIL"));
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("eml 200: ContactRecordingUrlResponse z contentType message/rfc822 i durationSeconds null")
    void emlOk() throws Exception {
        when(contactService.getEmailMessageEmlUrl(CONTACT_ID, MESSAGE_ID, TENANT_ID, USER_ID, true))
                .thenReturn(new ContactRecordingUrlResponse("https://s3/signed", Instant.parse("2026-04-17T10:45:00Z"),
                        "t/k.eml", null, "message/rfc822"));

        mockMvc.perform(get(EML_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presignedUrl").value("https://s3/signed"))
                .andExpect(jsonPath("$.contentType").value("message/rfc822"))
                .andExpect(jsonPath("$.durationSeconds").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("eml: mapowanie 404 (nie ta wiadomosc), 409 (cudzy agent), 400 (nie-EMAIL), 503 (S3)")
    void emlErrorMapping() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "brak"))
                .when(contactService).getEmailMessageEmlUrl(CONTACT_ID, MESSAGE_ID, TENANT_ID, USER_ID, true);
        mockMvc.perform(get(EML_URL)).andExpect(status().isNotFound());

        doThrow(new InvalidOperationException("cudzy"))
                .when(contactService).getEmailMessageEmlUrl(CONTACT_ID, MESSAGE_ID, TENANT_ID, USER_ID, true);
        mockMvc.perform(get(EML_URL)).andExpect(status().isConflict());

        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "nie EMAIL"))
                .when(contactService).getEmailMessageEmlUrl(CONTACT_ID, MESSAGE_ID, TENANT_ID, USER_ID, true);
        mockMvc.perform(get(EML_URL)).andExpect(status().isBadRequest());

        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "s3"))
                .when(contactService).getEmailMessageEmlUrl(CONTACT_ID, MESSAGE_ID, TENANT_ID, USER_ID, true);
        mockMvc.perform(get(EML_URL)).andExpect(status().isServiceUnavailable());
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("eml: AGENT cudzego kontaktu -> 409 (serwis dostaje isAgent=true i userId z TenantContext)")
    void emlAgentForeignContactIs409() throws Exception {
        doThrow(new InvalidOperationException("Agent moze pobierac EML tylko wlasnych kontaktow"))
                .when(contactService).getEmailMessageEmlUrl(CONTACT_ID, MESSAGE_ID, TENANT_ID, USER_ID, true);

        mockMvc.perform(get(EML_URL)).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));

        verify(contactService).getEmailMessageEmlUrl(CONTACT_ID, MESSAGE_ID, TENANT_ID, USER_ID, true);
    }

    @Test
    @WithMockUser(roles = "AGENT")
    @DisplayName("eml: niepoprawny UUID wiadomosci -> 404, serwis nietkniety")
    void emlInvalidMessageUuid() throws Exception {
        mockMvc.perform(get("/api/contacts/" + CONTACT_ID + "/email-messages/not-a-uuid/eml"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(contactService);
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("403: rola spoza ADMIN/SUPERVISOR/AGENT (oba endpointy), serwis nietkniety")
    void forbiddenForOtherRole() throws Exception {
        mockMvc.perform(get(PREVIEW_URL)).andExpect(status().isForbidden());
        mockMvc.perform(get(EML_URL)).andExpect(status().isForbidden());

        verifyNoInteractions(contactService);
    }
}
