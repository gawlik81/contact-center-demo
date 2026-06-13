package com.contactcenter.api.telephony;

import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.service.ContactService;
import com.contactcenter.domain.service.IncomingCallRoutingService;
import com.contactcenter.domain.service.IvrEngineService;
import com.contactcenter.domain.service.TwilioRecordingDownloadService;
import com.contactcenter.domain.telephony.TwilioTelephonyAdapter;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link TwilioWebhookController#handleConferenceStatusCallback}.
 *
 * <p>Kluczowy scenariusz: race condition między handlerem hangup (→ COMPLETED)
 * a callbackiem conference-end (→ ABANDONED). Naprawka polega na:
 * <ol>
 *   <li>Zmianie warunków: tylko QUEUED/ASSIGNED mogą przejść do ABANDONED
 *       (ACTIVE i ON_HOLD są celowo wykluczone).</li>
 *   <li>Użyciu atomowego conditional UPDATE ({@code updateContactStatusIfNotTerminal})
 *       zamiast bezwarunkowego UPDATE.</li>
 * </ol>
 *
 * <p>Weryfikacja podpisu Twilio jest wyłączona przez
 * {@code signatureValidationEnabled=false} w TwilioProperties – testy skupiają się
 * na logice biznesowej, nie na HMAC.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TwilioWebhookController – handleConferenceStatusCallback (race condition fix)")
class TwilioWebhookControllerConferenceTest {

    private static final UUID TENANT_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // FriendlyName konferencji w formacie wymaganym przez kontroler
    private static final String FRIENDLY_NAME = "contact-" + CONTACT_ID;

    @Mock private TwilioTelephonyAdapter twilioAdapter;
    @Mock private IvrEngineService ivrEngineService;
    @Mock private IncomingCallRoutingService incomingCallRoutingService;
    @Mock private ContactService contactService;
    @Mock private CustomerService customerService;
    @Mock private ContactRepository contactRepository;
    @Mock private TwilioRecordingDownloadService recordingDownloadService;

    private TwilioProperties twilioProperties;
    private TwilioWebhookController controller;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        twilioProperties = new TwilioProperties();
        // Wyłącz weryfikację podpisu – testy unit nie mają prawdziwego żądania Twilio
        twilioProperties.setSignatureValidationEnabled(false);

        controller = new TwilioWebhookController(
            twilioAdapter,
            ivrEngineService,
            incomingCallRoutingService,
            contactService,
            customerService,
            contactRepository,
            recordingDownloadService,
            twilioProperties
        );
        ReflectionTestUtils.setField(controller, "appBaseUrl", "http://localhost:8080");

        httpRequest = mock(HttpServletRequest.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Scenariusze: kontakt QUEUED / ASSIGNED → powinien otrzymać ABANDONED
    // =========================================================================

    @Nested
    @DisplayName("Status QUEUED lub ASSIGNED – powinien ustawić ABANDONED")
    class ShouldSetAbandoned {

        @Test
        @DisplayName("kontakt QUEUED + conference-end → wywołuje updateContactStatusIfNotTerminal z ABANDONED")
        void queued_conferenceEnd_setsAbandoned() {
            Contact contact = contactWithStatus("QUEUED");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                .thenReturn(Optional.of(contact));

            ResponseEntity<Void> response = callConferenceEndCallback();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository).updateContactStatusIfNotTerminal(
                eq(CONTACT_ID), eq(TENANT_ID), eq("ABANDONED"), any(Instant.class));
        }

        @Test
        @DisplayName("kontakt ASSIGNED + conference-end → wywołuje updateContactStatusIfNotTerminal z ABANDONED")
        void assigned_conferenceEnd_setsAbandoned() {
            Contact contact = contactWithStatus("ASSIGNED");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                .thenReturn(Optional.of(contact));

            ResponseEntity<Void> response = callConferenceEndCallback();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository).updateContactStatusIfNotTerminal(
                eq(CONTACT_ID), eq(TENANT_ID), eq("ABANDONED"), any(Instant.class));
        }
    }

    // =========================================================================
    // Scenariusze core regresji: ACTIVE / ON_HOLD → NIE powinny dostać ABANDONED
    // =========================================================================

    @Nested
    @DisplayName("Status ACTIVE lub ON_HOLD – NIE powinien ustawiać ABANDONED (core regresja)")
    class ShouldNotSetAbandonedForActiveContacts {

        /**
         * Kluczowy test regresji dla race condition.
         *
         * <p>Przed naprawką: exec-8 (conference-end) nadpisywał COMPLETED → ABANDONED
         * gdy agent rozłączył się przed dotarciem webhooka.
         * Po naprawce: ACTIVE jest wykluczone z warunków → żadna metoda update nie jest wywoływana.
         */
        @Test
        @DisplayName("kontakt ACTIVE + conference-end → NIE wywołuje żadnego update statusu")
        void active_conferenceEnd_doesNotSetAbandoned() {
            Contact contact = contactWithStatus("ACTIVE");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                .thenReturn(Optional.of(contact));

            ResponseEntity<Void> response = callConferenceEndCallback();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            // updateContactStatusIfNotTerminal NIE może być wywołana dla ACTIVE
            verify(contactRepository, never()).updateContactStatusIfNotTerminal(
                any(), any(), any(), any());
            // Stara metoda bezwarunkowa też nie może być wywołana
            verify(contactRepository, never()).updateContactStatusOnTelephonyEvent(
                any(), any(), any(), any());
        }

        @Test
        @DisplayName("kontakt ON_HOLD + conference-end → NIE wywołuje żadnego update statusu")
        void onHold_conferenceEnd_doesNotSetAbandoned() {
            Contact contact = contactWithStatus("ON_HOLD");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                .thenReturn(Optional.of(contact));

            ResponseEntity<Void> response = callConferenceEndCallback();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository, never()).updateContactStatusIfNotTerminal(
                any(), any(), any(), any());
            verify(contactRepository, never()).updateContactStatusOnTelephonyEvent(
                any(), any(), any(), any());
        }
    }

    // =========================================================================
    // Scenariusze: statusy terminalne → idempotentność
    // =========================================================================

    @Nested
    @DisplayName("Statusy terminalne – idempotentność (brak wywołania update)")
    class TerminalStatusesAreIdempotent {

        @ParameterizedTest(name = "status={0} → brak update")
        @ValueSource(strings = {"COMPLETED", "ABANDONED", "NOT_REACHED", "ERROR", "TRANSFERRED"})
        @DisplayName("status terminalny + conference-end → brak wywołania update")
        void terminalStatus_conferenceEnd_noUpdate(String terminalStatus) {
            Contact contact = contactWithStatus(terminalStatus);
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                .thenReturn(Optional.of(contact));

            ResponseEntity<Void> response = callConferenceEndCallback();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            // Statusy terminalne są obsługiwane przez sprawdzenie w findById().ifPresentOrElse –
            // updateContactStatusIfNotTerminal nie może być wywołana dla żadnego z nich
            verify(contactRepository, never()).updateContactStatusIfNotTerminal(
                any(), any(), any(), any());
            verify(contactRepository, never()).updateContactStatusOnTelephonyEvent(
                any(), any(), any(), any());
        }
    }

    // =========================================================================
    // Scenariusze edge case: brakujące dane wejściowe
    // =========================================================================

    @Nested
    @DisplayName("Edge cases – brakujące lub błędne parametry wejściowe")
    class EdgeCases {

        @Test
        @DisplayName("brak FriendlyName → 204 bez update")
        void noFriendlyName_returnsNoContent_withoutUpdate() {
            ResponseEntity<Void> response = controller.handleConferenceStatusCallback(
                httpRequest, null, "completed", null, TENANT_ID.toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository, never()).findById(any(), any());
        }

        @Test
        @DisplayName("FriendlyName bez prefiksu 'contact-' → 204 bez update")
        void wrongFriendlyNamePrefix_returnsNoContent_withoutUpdate() {
            ResponseEntity<Void> response = controller.handleConferenceStatusCallback(
                httpRequest, "conference-abc123", "completed", null, TENANT_ID.toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository, never()).findById(any(), any());
        }

        @Test
        @DisplayName("FriendlyName z nieprawidłowym UUID po 'contact-' → 204 bez update")
        void invalidUuidInFriendlyName_returnsNoContent_withoutUpdate() {
            ResponseEntity<Void> response = controller.handleConferenceStatusCallback(
                httpRequest, "contact-not-a-uuid", "completed", null, TENANT_ID.toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository, never()).findById(any(), any());
        }

        @Test
        @DisplayName("brak tenantId → 204 bez update")
        void noTenantId_returnsNoContent_withoutUpdate() {
            ResponseEntity<Void> response = controller.handleConferenceStatusCallback(
                httpRequest, FRIENDLY_NAME, "completed", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository, never()).findById(any(), any());
        }

        @Test
        @DisplayName("event inny niż conference-end (np. 'participant-join') → 204 bez przetwarzania")
        void nonEndEvent_returnsNoContent_withoutUpdate() {
            ResponseEntity<Void> response = controller.handleConferenceStatusCallback(
                httpRequest, FRIENDLY_NAME, "in-progress", "participant-join", TENANT_ID.toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository, never()).findById(any(), any());
        }

        @Test
        @DisplayName("kontakt nie istnieje w bazie → 204 bez update")
        void contactNotFound_returnsNoContent_withoutUpdate() {
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                .thenReturn(Optional.empty());

            ResponseEntity<Void> response = callConferenceEndCallback();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository, never()).updateContactStatusIfNotTerminal(
                any(), any(), any(), any());
        }
    }

    // =========================================================================
    // Scenariusz: wywołanie z StatusCallbackEvent=conference-end (alternatywna forma)
    // =========================================================================

    @Nested
    @DisplayName("Alternatywna forma zakończenia konferencji: StatusCallbackEvent")
    class AlternativeConferenceEndForm {

        @Test
        @DisplayName("StatusCallbackEvent=conference-end z kontaktem QUEUED → ustawia ABANDONED")
        void statusCallbackEventConferenceEnd_queued_setsAbandoned() {
            Contact contact = contactWithStatus("QUEUED");
            when(contactRepository.findById(CONTACT_ID, TENANT_ID))
                .thenReturn(Optional.of(contact));

            // ConferenceStatus != "completed", ale StatusCallbackEvent = "conference-end"
            ResponseEntity<Void> response = controller.handleConferenceStatusCallback(
                httpRequest, FRIENDLY_NAME, "in-progress", "conference-end", TENANT_ID.toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(contactRepository).updateContactStatusIfNotTerminal(
                eq(CONTACT_ID), eq(TENANT_ID), eq("ABANDONED"), any(Instant.class));
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Wywołuje handleConferenceStatusCallback z parametrami standardowego zakończenia konferencji
     * (ConferenceStatus=completed) dla globalnych TENANT_ID i FRIENDLY_NAME.
     */
    private ResponseEntity<Void> callConferenceEndCallback() {
        return controller.handleConferenceStatusCallback(
            httpRequest,
            FRIENDLY_NAME,
            "completed",
            null,
            TENANT_ID.toString()
        );
    }

    /**
     * Tworzy obiekt Contact z podanym statusem i stałymi CONTACT_ID / TENANT_ID.
     */
    private Contact contactWithStatus(String status) {
        Contact contact = new Contact();
        contact.setContactId(CONTACT_ID);
        contact.setTenantId(TENANT_ID);
        contact.setStatus(status);
        return contact;
    }
}
