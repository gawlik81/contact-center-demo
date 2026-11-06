package com.contactcenter.api.social;

import com.contactcenter.api.social.dto.SendSocialMessageRequest;
import com.contactcenter.domain.exception.BusinessValidationException;
import com.contactcenter.domain.social.SocialMessageService;
import com.contactcenter.security.TenantContext;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/** BE-200: kontrakt statusow {@code SocialContactController#sendMessage}. */
@ExtendWith(MockitoExtension.class)
@DisplayName("SocialContactController.sendMessage - mapowanie wyjatkow (BE-200)")
class SocialContactControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private SocialMessageService socialMessageService;

    @InjectMocks
    private SocialContactController controller;

    private final SendSocialMessageRequest request = new SendSocialMessageRequest("tresc", List.of());

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("BusinessValidationException z serwisu -> 400 (status niezmieniony dla FE)")
    void businessValidation_returns400() {
        doThrow(new BusinessValidationException("Kontakt nie jest kanalem social media"))
                .when(socialMessageService).sendMessage(eq(CONTACT), eq(TENANT), any(), any());

        ResponseEntity<Void> response = controller.sendMessage(CONTACT, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("IllegalStateException (brak integracji) -> 400")
    void illegalState_returns400() {
        doThrow(new IllegalStateException("brak integracji"))
                .when(socialMessageService).sendMessage(eq(CONTACT), eq(TENANT), any(), any());

        assertThat(controller.sendMessage(CONTACT, request).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("IllegalArgumentException z biblioteki NIE jest polykany -> propaguje do globalnego handlera (500)")
    void libraryIllegalArgument_propagates() {
        doThrow(new IllegalArgumentException("No enum constant com.contactcenter.X"))
                .when(socialMessageService).sendMessage(eq(CONTACT), eq(TENANT), any(), any());

        assertThatThrownBy(() -> controller.sendMessage(CONTACT, request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
