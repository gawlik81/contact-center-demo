package com.contactcenter.infrastructure;

import com.contactcenter.domain.audit.AuditLogEvent;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.infrastructure.aspect.AuditAspect;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.aspectj.lang.ProceedingJoinPoint;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link AuditAspect}.
 *
 * <p>Weryfikuje logikę aspektu bez uruchamiania kontekstu Spring –
 * aspekt jest testowany bezpośrednio przez mockowanie {@link ProceedingJoinPoint}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuditAspect – przechwytywanie zdarzeń audytowych")
class AuditAspectTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ENTITY_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private AuditLogService auditLogService;

    private AuditAspect auditAspect;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // dla Java time
        auditAspect = new AuditAspect(auditLogService, objectMapper);

        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Adnotacja @Audited – CREATE
    // =========================================================================

    @Nested
    @DisplayName("auditMethod() – CREATE (bez old_value)")
    class AuditCreate {

        @Test
        @DisplayName("powinien opublikować zdarzenie audytowe po wywołaniu metody")
        void shouldPublishAuditEventAfterMethodInvocation() throws Throwable {
            // given
            SimpleResult result = new SimpleResult(ENTITY_ID, "Test Tenant");
            ProceedingJoinPoint pjp = mockJoinPoint(new Object[]{}, result);
            Audited audited = mockAudited("TENANT_CREATED", "TENANT", false, "", -1);

            // when
            Object returnedResult = auditAspect.auditMethod(pjp, audited);

            // then
            assertThat(returnedResult).isSameAs(result);

            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());

            AuditLogEvent event = captor.getValue();
            assertThat(event.action()).isEqualTo("TENANT_CREATED");
            assertThat(event.entityType()).isEqualTo("TENANT");
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
            assertThat(event.userId()).isEqualTo(USER_ID);
            assertThat(event.oldValue()).isNull();
            assertThat(event.newValue()).isNotNull();
        }

        @Test
        @DisplayName("powinien wyodrębnić entityId z jawnie wskazanego indeksu parametru")
        void shouldExtractEntityIdFromExplicitParamIndex() throws Throwable {
            // given
            SimpleResult result = new SimpleResult(ENTITY_ID, "Test");
            ProceedingJoinPoint pjp = mockJoinPoint(new Object[]{ENTITY_ID, "extraParam"}, result);
            Audited audited = mockAudited("TENANT_UPDATED", "TENANT", false, "", 0);

            // when
            auditAspect.auditMethod(pjp, audited);

            // then
            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            assertThat(captor.getValue().entityId()).isEqualTo(ENTITY_ID);
        }

        @Test
        @DisplayName("powinien wyodrębnić entityId z pierwszego UUID w parametrach gdy brak jawnego indeksu")
        void shouldExtractEntityIdFromFirstUuidWhenNoExplicitIndex() throws Throwable {
            // given – UUID jest pierwszym parametrem, aspekt scan'uje args
            SimpleResult result = new SimpleResult(ENTITY_ID, "Test");
            ProceedingJoinPoint pjp = mockJoinPoint(new Object[]{ENTITY_ID}, result);
            Audited audited = mockAudited("TENANT_CREATED", "TENANT", false, "", -1);

            // when
            auditAspect.auditMethod(pjp, audited);

            // then
            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            assertThat(captor.getValue().entityId()).isEqualTo(ENTITY_ID);
        }

        @Test
        @DisplayName("powinien propagować wyjątek oryginalnej metody bez publikowania zdarzenia")
        void shouldPropagateOriginalMethodExceptionWithoutPublishingEvent() throws Throwable {
            // given
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            when(pjp.proceed()).thenThrow(new RuntimeException("Błąd biznesowy"));
            Audited audited = mockAudited("TENANT_CREATED", "TENANT", false, "", -1);

            // when / then
            assertThatThrownBy(() -> auditAspect.auditMethod(pjp, audited))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Błąd biznesowy");

            // Zdarzenie nie powinno być opublikowane
            verifyNoInteractions(auditLogService);
        }
    }

    // =========================================================================
    // Adnotacja @Audited – bez TenantContext (operacja globalna)
    // =========================================================================

    @Nested
    @DisplayName("auditMethod() – brak TenantContext (operacja globalna)")
    class AuditWithoutContext {

        @Test
        @DisplayName("powinien opublikować zdarzenie z null tenantId gdy kontekst nie jest ustawiony")
        void shouldPublishEventWithNullTenantIdWhenContextNotSet() throws Throwable {
            // given – wyczyść kontekst symulując operację globalną
            TenantContext.clear();

            SimpleResult result = new SimpleResult(ENTITY_ID, "Test");
            ProceedingJoinPoint pjp = mockJoinPoint(new Object[]{}, result);
            Audited audited = mockAudited("TENANT_CREATED", "TENANT", false, "", -1);

            // when
            auditAspect.auditMethod(pjp, audited);

            // then
            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            assertThat(captor.getValue().tenantId()).isNull();
            assertThat(captor.getValue().userId()).isNull();
        }
    }

    // =========================================================================
    // Wykluczanie pól wrażliwych
    // =========================================================================

    @Nested
    @DisplayName("Wykluczanie pól wrażliwych z newValue")
    class SensitiveFieldsExclusion {

        @Test
        @DisplayName("powinien wykluczyć passwordHash i mfaSecret z serializowanego newValue")
        void shouldExcludePasswordHashFromNewValue() throws Throwable {
            // given – wynik zawiera wrażliwe pola
            SensitiveResult result = new SensitiveResult(ENTITY_ID, "user@test.com", "$2a$12$hash", "totp-secret");
            ProceedingJoinPoint pjp = mockJoinPoint(new Object[]{}, result);
            Audited audited = mockAudited("USER_CREATED", "USER", false, "", -1);

            // when
            auditAspect.auditMethod(pjp, audited);

            // then
            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());

            String newValue = captor.getValue().newValue();
            assertThat(newValue).isNotNull();
            assertThat(newValue).doesNotContain("passwordHash");
            assertThat(newValue).doesNotContain("$2a$12$hash");
            assertThat(newValue).doesNotContain("mfaSecret");
            assertThat(newValue).doesNotContain("totp-secret");
            // Bezpieczne pola powinny być obecne
            assertThat(newValue).contains("user@test.com");
        }
    }

    // =========================================================================
    // Maskowanie PII (BE-142) – AuditPiiKeys, wyłącznie CUSTOMER/CONTACT
    // =========================================================================

    @Nested
    @DisplayName("Maskowanie PII (BE-142) – AuditPiiKeys, wyłącznie CUSTOMER/CONTACT")
    class PiiMasking {

        @Test
        @DisplayName("CUSTOMER: maskuje firstName/lastName/externalId/phone/email/customFields w newValue, customerId/source nietknięte")
        void customerEntityType_masksPiiFields_inNewValue() throws Throwable {
            CustomerLikeResult result = new CustomerLikeResult(
                    ENTITY_ID, "Jan", "Kowalski", "ERP-1",
                    java.util.List.of("+48500100200"), java.util.List.of("jan@example.com"),
                    java.util.Map.of("vip", true), "MANUAL");
            ProceedingJoinPoint pjp = mockJoinPoint(new Object[]{}, result);
            Audited audited = mockAudited("CUSTOMER_CREATED", "CUSTOMER", false, "", -1);

            auditAspect.auditMethod(pjp, audited);

            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            String newValue = captor.getValue().newValue();

            assertThat(newValue).contains("\"firstName\":\"[MASKED]\"");
            assertThat(newValue).contains("\"lastName\":\"[MASKED]\"");
            assertThat(newValue).contains("\"externalId\":\"[MASKED]\"");
            assertThat(newValue).contains("\"phone\":\"[MASKED]\"");
            assertThat(newValue).contains("\"email\":\"[MASKED]\"");
            assertThat(newValue).contains("\"customFields\":\"[MASKED]\"");
            // pola NIE-PII zostają nietknięte
            assertThat(newValue).contains("\"source\":\"MANUAL\"");
            assertThat(newValue).contains(ENTITY_ID.toString());
        }

        @Test
        @DisplayName("CONTACT: maskuje remoteAddress/channelMetadata/notes/recordingUrl w newValue, status/dispositionCode nietknięte")
        void contactEntityType_masksPiiFields_inNewValue() throws Throwable {
            ContactLikeResult result = new ContactLikeResult(
                    ENTITY_ID, "+48500100200", java.util.Map.of("sip_call_id", "abc"),
                    "notatka agenta", "s3://bucket/rec.mp3", "COMPLETED", "SALE");
            ProceedingJoinPoint pjp = mockJoinPoint(new Object[]{}, result);
            Audited audited = mockAudited("CONTACT_DISPOSITION_SET", "CONTACT", false, "", -1);

            auditAspect.auditMethod(pjp, audited);

            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            String newValue = captor.getValue().newValue();

            assertThat(newValue).contains("\"remoteAddress\":\"[MASKED]\"");
            assertThat(newValue).contains("\"channelMetadata\":\"[MASKED]\"");
            assertThat(newValue).contains("\"notes\":\"[MASKED]\"");
            assertThat(newValue).contains("\"recordingUrl\":\"[MASKED]\"");
            // pola operacyjne NIE-PII zostają nietknięte
            assertThat(newValue).contains("\"status\":\"COMPLETED\"");
            assertThat(newValue).contains("\"dispositionCode\":\"SALE\"");
        }

        @Test
        @DisplayName("inna encja (TENANT): pole firstName NIE jest maskowane – maskowanie warunkowe wyłącznie CUSTOMER/CONTACT")
        void nonMaskedEntityType_doesNotMaskEvenSameFieldName() throws Throwable {
            TenantLikeResult result = new TenantLikeResult(ENTITY_ID, "Jan", "Nazwa Tenanta");
            ProceedingJoinPoint pjp = mockJoinPoint(new Object[]{}, result);
            Audited audited = mockAudited("TENANT_CREATED", "TENANT", false, "", -1);

            auditAspect.auditMethod(pjp, audited);

            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            String newValue = captor.getValue().newValue();

            assertThat(newValue).contains("\"firstName\":\"Jan\"");
            assertThat(newValue).doesNotContain("[MASKED]");
        }

        @Test
        @DisplayName("RECORDING_URL_REQUESTED (entityType=CONTACT): presignedUrl NIE jest maskowany – klucz spoza AuditPiiKeys (decyzja BE-142)")
        void recordingUrlRequested_presignedUrlNotInPiiKeys_staysUnmasked() throws Throwable {
            RecordingUrlLikeResult result = new RecordingUrlLikeResult(
                    ENTITY_ID, "http://minio:9000/rec.mp3?X-Amz-Signature=abc", 42);
            ProceedingJoinPoint pjp = mockJoinPoint(new Object[]{}, result);
            Audited audited = mockAudited("RECORDING_URL_REQUESTED", "CONTACT", false, "", -1);

            auditAspect.auditMethod(pjp, audited);

            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            String newValue = captor.getValue().newValue();

            assertThat(newValue).contains("X-Amz-Signature");
            assertThat(newValue).doesNotContain("[MASKED]");
        }

        @Test
        @DisplayName("oldValue (captureOldValue=true, ścieżka refleksyjna dla CONTACT – nie jest w ENTITY_CLASS_MAP): maskowanie stosowane również do oldValue")
        void captureOldValue_maskingAppliesToOldValueToo() throws Throwable {
            ContactLikeResult oldEntity = new ContactLikeResult(
                    ENTITY_ID, "+48500100200", java.util.Map.of("sip_call_id", "xyz"),
                    null, null, "IN_PROGRESS", null);
            FetchTarget target = new FetchTarget(oldEntity);

            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            when(pjp.getArgs()).thenReturn(new Object[]{ENTITY_ID});
            when(pjp.getTarget()).thenReturn(target);
            ContactLikeResult newResult = new ContactLikeResult(
                    ENTITY_ID, "+48500100200", java.util.Map.of("sip_call_id", "xyz"),
                    "agent notatka", null, "COMPLETED", "SALE");
            when(pjp.proceed()).thenReturn(newResult);

            Audited audited = mockAudited("CONTACT_DISPOSITION_SET", "CONTACT", true, "fetchOldContact", -1);

            auditAspect.auditMethod(pjp, audited);

            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            AuditLogEvent event = captor.getValue();

            assertThat(event.oldValue()).contains("\"remoteAddress\":\"[MASKED]\"");
            assertThat(event.oldValue()).contains("\"status\":\"IN_PROGRESS\"");
            assertThat(event.newValue()).contains("\"notes\":\"[MASKED]\"");
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private ProceedingJoinPoint mockJoinPoint(Object[] args, Object returnValue) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(returnValue);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.getTarget()).thenReturn(new Object()); // dummy target (używane tylko gdy captureOldValue=true)
        return pjp;
    }

    private Audited mockAudited(String action, String entityType,
                                 boolean captureOldValue, String fetchMethod, int entityIdParamIndex) {
        Audited audited = mock(Audited.class);
        when(audited.action()).thenReturn(action);
        when(audited.entityType()).thenReturn(entityType);
        when(audited.captureOldValue()).thenReturn(captureOldValue);
        when(audited.fetchOldValueMethod()).thenReturn(fetchMethod);
        when(audited.entityIdParamIndex()).thenReturn(entityIdParamIndex);
        return audited;
    }

    // =========================================================================
    // Klasy pomocnicze (dane testowe)
    // =========================================================================

    /** Prosty wynik metody z metodą id() (jak record DTO). */
    record SimpleResult(UUID id, String name) {}

    /** Wynik z polami wrażliwymi (klasa z getterami, nie record – żeby Jackson serializował). */
    static class SensitiveResult {
        private final UUID id;
        private final String email;
        private final String passwordHash;
        private final String mfaSecret;

        SensitiveResult(UUID id, String email, String passwordHash, String mfaSecret) {
            this.id = id;
            this.email = email;
            this.passwordHash = passwordHash;
            this.mfaSecret = mfaSecret;
        }

        public UUID getId() { return id; }
        public String getEmail() { return email; }
        public String getPasswordHash() { return passwordHash; }
        public String getMfaSecret() { return mfaSecret; }
    }

    /** Wynik z polami PII klienta (jak {@code CustomerResponse}) – dla testów maskowania BE-142. */
    record CustomerLikeResult(UUID customerId, String firstName, String lastName, String externalId,
                               java.util.List<String> phone, java.util.List<String> email,
                               java.util.Map<String, Object> customFields, String source) {}

    /** Wynik z polami PII kontaktu (jak {@code ContactResponse}) – dla testów maskowania BE-142. */
    record ContactLikeResult(UUID contactId, String remoteAddress, java.util.Map<String, Object> channelMetadata,
                              String notes, String recordingUrl, String status, String dispositionCode) {}

    /** Wynik z polem {@code presignedUrl} (jak dla akcji RECORDING_URL_REQUESTED) – spoza {@code AuditPiiKeys}. */
    record RecordingUrlLikeResult(UUID contactId, String presignedUrl, Integer durationSeconds) {}

    /** Wynik z polem {@code firstName} na encji INNEJ niż CUSTOMER/CONTACT – dowód warunkowości maskowania. */
    record TenantLikeResult(UUID id, String firstName, String name) {}

    /**
     * Cel wywołania refleksyjnego {@code fetchOldValueMethod} – symuluje serwis z getterem starego
     * stanu. MUSI być {@code public} (razem z metodą) – {@code AuditAspect} wywołuje ją reflection
     * z INNEGO pakietu ({@code infrastructure.aspect}), a JVM odmawia dostępu do publicznej metody
     * na klasie o niepublicznej (domyślnej) widoczności przy wywołaniu spoza pakietu.
     */
    public static class FetchTarget {
        private final Object toReturn;
        FetchTarget(Object toReturn) { this.toReturn = toReturn; }
        public Object fetchOldContact(UUID id) { return toReturn; }
    }
}
