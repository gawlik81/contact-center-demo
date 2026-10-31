package com.contactcenter.api.contact;

import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BE-160: {@code POST /api/contacts/{id}/release} - delegation, identity from TenantContext, errors, security. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContactController.releaseContact (BE-160)")
class ContactControllerReleaseTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private ContactService contactService;

    @InjectMocks
    private ContactController controller;

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
    @DisplayName("200 + ContactResponse z serwisu; tenant i agent z TenantContext")
    void returns200() {
        ContactResponse response = mock(ContactResponse.class);
        when(contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID)).thenReturn(response);

        ResponseEntity<ContactResponse> result = controller.releaseContact(CONTACT_ID.toString());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(response);
        verify(contactService).releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);
    }

    @Test
    @DisplayName("409 z serwisu propagowane (InvalidOperationException)")
    void propagatesConflict() {
        when(contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .thenThrow(new InvalidOperationException("nope"));

        assertThatThrownBy(() -> controller.releaseContact(CONTACT_ID.toString()))
                .isInstanceOf(InvalidOperationException.class);
    }

    @Test
    @DisplayName("404 z serwisu propagowane (EntityNotFoundException)")
    void propagatesNotFound() {
        when(contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .thenThrow(new EntityNotFoundException("nie ma"));

        assertThatThrownBy(() -> controller.releaseContact(CONTACT_ID.toString()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("niepoprawny UUID -> EntityNotFoundException (404), serwis nietkniety")
    void invalidUuidIs404() {
        assertThatThrownBy(() -> controller.releaseContact("mock-1"))
                .isInstanceOf(EntityNotFoundException.class);
        org.mockito.Mockito.verifyNoInteractions(contactService);
    }

    @Test
    @DisplayName("@PreAuthorize: AGENT, SUPERVISOR, ADMIN")
    void securedForAgentSupervisorAdmin() throws Exception {
        Method method = ContactController.class.getMethod("releaseContact", String.class);
        PreAuthorize pre = method.getAnnotation(PreAuthorize.class);
        assertThat(pre).isNotNull();
        assertThat(pre.value()).isEqualTo("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')");
    }
}
