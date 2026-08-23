package com.contactcenter.domain.retention;

import com.contactcenter.domain.audit.AuditLogEvent;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.contact.ContactEventService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.retention.dto.PurgeResultDto;
import com.contactcenter.domain.retention.dto.RetentionSummaryDto;
import com.contactcenter.domain.social.SocialMessageService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link RetentionPurgeServiceImpl} (EPIC-29, BE-113).
 *
 * <p><strong>Wzorzec self-invocation w testach:</strong> w {@code @BeforeEach} pole {@code self}
 * jest domyślnie ustawiane na tę samą instancję serwisu (wzorzec identyczny do
 * {@code ProgressiveDialerServiceTest}) — poza kontenerem Spring nie istnieje prawdziwy proxy AOP,
 * więc wywołanie {@code self.purgeAsync(...)} jest zwykłym synchronicznym wywołaniem metody, co
 * pozwala testować pełny przepływ {@code purge()} + {@code purgeAsync()} deterministycznie w
 * jednym wątku, bez oczekiwania na wątki w tle. Test „purgeId zwrócony natychmiast” (patrz
 * {@link ReturnsImmediately}) celowo podmienia {@code self} na mock, żeby udowodnić, że
 * {@link RetentionPurgeServiceImpl#purge} zwraca się PRZED wykonaniem faktycznej pracy usuwania
 * (która żyje wyłącznie w {@code purgeAsync}).
 *
 * <p><strong>Ograniczenie testów jednostkowych względem AC „TenantContext w wątku roboczym”:</strong>
 * ponieważ w testach jednostkowych {@code self.purgeAsync(...)} wykonuje się na TYM SAMYM wątku co
 * wywołujący (brak prawdziwego {@code @Async} bez kontenera Spring), nie da się tu zaobserwować
 * przełączenia wątku fizycznie. Testy weryfikują natomiast kontrakt {@code snapshot/restore/clear}:
 * że {@code purgeAsync} działa poprawnie niezależnie od stanu {@code TenantContext} wątku
 * wywołującego w momencie startu (w tym gdy jest on całkowicie pusty — symulacja przyszłego
 * triggera AUTO bez kontekstu HTTP) i że kontekst jest czyszczony po zakończeniu.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RetentionPurgeService – silnik usuwania Poziom 1 (BE-113)")
class RetentionPurgeServiceImplTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private RetentionPolicyService retentionPolicyService;

    @Mock
    private RetentionPurgeLogRepository purgeLogRepository;

    @Mock
    private ContactService contactService;

    @Mock
    private ContactEventService contactEventService;

    @Mock
    private EmailMessageService emailMessageService;

    @Mock
    private SocialMessageService socialMessageService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private TenantRetentionPendingSummaryRepository summaryRepository;

    @Mock
    private CampaignArchiveRetentionRepository campaignArchiveRetentionRepository;

    @InjectMocks
    private RetentionPurgeServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "batchSize", 100);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static List<UUID> uuids(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    // =========================================================================
    // Batch pojedynczy
    // =========================================================================

    @Nested
    @DisplayName("Batch pojedynczy – mniej wierszy niż batchSize")
    class SingleBatch {

        @Test
        @DisplayName("CONTACT_INTERACTIONS: jeden batch < batchSize kończy pętlę po jednym wywołaniu")
        void contactInteractions_singleBatch_completesAfterOneIteration() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            List<UUID> batch = uuids(30);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), eq(100))).thenReturn(batch);
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), eq(100))).thenReturn(0);

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            verify(contactService, org.mockito.Mockito.times(1))
                    .purgeContactsOlderThan(eq(TENANT_A), any(), eq(100));
            verify(contactEventService, org.mockito.Mockito.times(1))
                    .purgeOlderThan(eq(TENANT_A), any(), eq(100));
            verify(purgeLogRepository).markCompleted(any(), eq(TENANT_A), eq(30L));
        }
    }

    // =========================================================================
    // Wiele batchy
    // =========================================================================

    @Nested
    @DisplayName("Wiele batchy – pętla kontynuuje aż do wyczerpania")
    class MultipleBatches {

        @Test
        @DisplayName("CONTACT_INTERACTIONS: 2 pełne batche + 1 niepełny -> suma poprawna")
        void contactInteractions_multipleBatches_sumsCorrectly() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), eq(100)))
                    .thenReturn(uuids(100))
                    .thenReturn(uuids(100))
                    .thenReturn(uuids(40));
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), eq(100)))
                    .thenReturn(0);

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            verify(contactService, org.mockito.Mockito.times(3))
                    .purgeContactsOlderThan(eq(TENANT_A), any(), eq(100));
            // 100 + 100 + 40 (contact) + 0 (event) = 240
            verify(purgeLogRepository).markCompleted(any(), eq(TENANT_A), eq(240L));
        }

        @Test
        @DisplayName("TRANSCRIPTS: contact_transcription i contact_ai_summary batchowane niezależnie")
        void transcripts_multipleBatches_sumsCorrectly() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.TRANSCRIPTS))
                    .thenReturn(3);
            when(contactService.purgeTranscriptionsOlderThan(eq(TENANT_A), any(), eq(100)))
                    .thenReturn(100)
                    .thenReturn(25);
            when(contactService.purgeAiSummariesOlderThan(eq(TENANT_A), any(), eq(100)))
                    .thenReturn(10);

            service.purge(TENANT_A, RetentionDataCategory.TRANSCRIPTS, PurgeTriggerType.MANUAL, USER_ID);

            verify(contactService, org.mockito.Mockito.times(2))
                    .purgeTranscriptionsOlderThan(eq(TENANT_A), any(), eq(100));
            verify(contactService, org.mockito.Mockito.times(1))
                    .purgeAiSummariesOlderThan(eq(TENANT_A), any(), eq(100));
            // 100 + 25 (transkrypcje) + 10 (ai summary) = 135
            verify(purgeLogRepository).markCompleted(any(), eq(TENANT_A), eq(135L));
        }
    }

    // =========================================================================
    // Izolacja cross-tenant
    // =========================================================================

    @Nested
    @DisplayName("Izolacja cross-tenant")
    class CrossTenantIsolation {

        @Test
        @DisplayName("purge dla tenanta A nigdy nie przekazuje tenantId=B do żadnej zależności")
        void purgeForTenantA_neverTouchesTenantB() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            List<UUID> batch = uuids(5);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(batch);
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(0);

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            // Żadna zależność nie została wywołana z TENANT_B
            verify(contactService, never()).purgeContactsOlderThan(eq(TENANT_B), any(), anyInt());
            verify(contactEventService, never()).purgeOlderThan(eq(TENANT_B), any(), anyInt());
            verify(emailMessageService, never()).detachContactReferences(eq(TENANT_B), any());
            verify(socialMessageService, never()).detachContactReferences(eq(TENANT_B), any());
            verify(purgeLogRepository, never()).insertRunning(any(), eq(TENANT_B), any(), any(), any(), any());
            verify(purgeLogRepository, never()).markCompleted(any(), eq(TENANT_B), anyLong());

            // Wszystkie wywołania faktycznie użyły TENANT_A
            verify(emailMessageService).detachContactReferences(eq(TENANT_A), eq(batch));
            verify(socialMessageService).detachContactReferences(eq(TENANT_A), eq(batch));
        }
    }

    // =========================================================================
    // Błąd w trakcie
    // =========================================================================

    @Nested
    @DisplayName("Błąd w trakcie batcha")
    class FailureDuringBatch {

        @Test
        @DisplayName("wyjątek w trakcie -> status FAILED, error_message zapisany, markCompleted nigdy nie wywołane")
        void exceptionDuringBatch_marksFailed_neverCompleted() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), anyInt()))
                    .thenThrow(new RuntimeException("DB connection lost"));

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            verify(purgeLogRepository).markFailed(any(), eq(TENANT_A), eq("DB connection lost"), eq(0L));
            verify(purgeLogRepository, never()).markCompleted(any(), any(), anyLong());
        }

        @Test
        @DisplayName("wyjątek w trakcie -> wpis audytowy FAILED opublikowany")
        void exceptionDuringBatch_publishesFailedAuditEvent() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.TRANSCRIPTS))
                    .thenReturn(3);
            when(contactService.purgeTranscriptionsOlderThan(eq(TENANT_A), any(), anyInt()))
                    .thenThrow(new IllegalStateException("boom"));

            UUID purgeId = service.purge(TENANT_A, RetentionDataCategory.TRANSCRIPTS, PurgeTriggerType.MANUAL, USER_ID);

            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            AuditLogEvent event = captor.getValue();
            assertThat(event.action()).isEqualTo("RETENTION_PURGE_FAILED");
            assertThat(event.entityType()).isEqualTo("RETENTION_PURGE");
            assertThat(event.entityId()).isEqualTo(purgeId);
            assertThat(event.tenantId()).isEqualTo(TENANT_A);
            assertThat(event.userId()).isEqualTo(USER_ID);
            assertThat(event.newValue()).contains("FAILED").contains("boom");
        }

        @Test
        @DisplayName("job nie pozostaje zawieszony w RUNNING – RUNNING zapisany zanim wystąpił błąd, potem FAILED")
        void jobDoesNotStayRunningForever() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), anyInt()))
                    .thenThrow(new RuntimeException("timeout"));

            UUID purgeId = service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            InOrder inOrder = inOrder(purgeLogRepository);
            inOrder.verify(purgeLogRepository).insertRunning(eq(purgeId), eq(TENANT_A),
                    eq(RetentionDataCategory.CONTACT_INTERACTIONS), eq(PurgeTriggerType.MANUAL), eq(USER_ID), any());
            inOrder.verify(purgeLogRepository).markFailed(eq(purgeId), eq(TENANT_A), any(), eq(0L));
        }
    }

    // =========================================================================
    // Cutoff dokładnie na granicy
    // =========================================================================

    @Nested
    @DisplayName("Cutoff dokładnie na granicy retencji")
    class CutoffBoundary {

        @Test
        @DisplayName("cutoffDate = LocalDate.now(UTC).minusMonths(retentionMonths), przekazywany spójnie dalej")
        void cutoff_computedPreciselyFromRetentionMonths() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.TRANSCRIPTS))
                    .thenReturn(3);
            when(contactService.purgeTranscriptionsOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(0);
            when(contactService.purgeAiSummariesOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(0);

            LocalDate expectedCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(3);
            Instant expectedCutoffInstant = expectedCutoffDate.atStartOfDay(ZoneOffset.UTC).toInstant();

            service.purge(TENANT_A, RetentionDataCategory.TRANSCRIPTS, PurgeTriggerType.MANUAL, USER_ID);

            ArgumentCaptor<LocalDate> cutoffDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(purgeLogRepository).insertRunning(any(), eq(TENANT_A), eq(RetentionDataCategory.TRANSCRIPTS),
                    eq(PurgeTriggerType.MANUAL), eq(USER_ID), cutoffDateCaptor.capture());
            assertThat(cutoffDateCaptor.getValue()).isEqualTo(expectedCutoffDate);

            ArgumentCaptor<Instant> cutoffInstantCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(contactService).purgeTranscriptionsOlderThan(eq(TENANT_A), cutoffInstantCaptor.capture(), anyInt());
            assertThat(cutoffInstantCaptor.getValue()).isEqualTo(expectedCutoffInstant);
        }
    }

    // =========================================================================
    // CONTACT_INTERACTIONS czyści FK email/social
    // =========================================================================

    @Nested
    @DisplayName("CONTACT_INTERACTIONS – odcięcie FK email/social")
    class ContactInteractionsDetachesForeignKeys {

        @Test
        @DisplayName("po każdym niepustym batchu contact wywoływane jest detachContactReferences dla email i social")
        void detachesEmailAndSocialReferences_perBatch() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            List<UUID> firstBatch = uuids(100);
            List<UUID> secondBatch = uuids(15);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), eq(100)))
                    .thenReturn(firstBatch)
                    .thenReturn(secondBatch);
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), eq(100))).thenReturn(0);

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            verify(emailMessageService).detachContactReferences(TENANT_A, firstBatch);
            verify(emailMessageService).detachContactReferences(TENANT_A, secondBatch);
            verify(socialMessageService).detachContactReferences(TENANT_A, firstBatch);
            verify(socialMessageService).detachContactReferences(TENANT_A, secondBatch);
        }

        @Test
        @DisplayName("batch pusty -> detachContactReferences NIE jest wywoływane (no-op)")
        void emptyBatch_doesNotCallDetach() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(List.of());
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(0);

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            verifyNoInteractions(emailMessageService);
            verifyNoInteractions(socialMessageService);
            verify(purgeLogRepository).markCompleted(any(), eq(TENANT_A), eq(0L));
        }
    }

    // =========================================================================
    // TRANSCRIPTS usuwa z 2 tabel
    // =========================================================================

    @Nested
    @DisplayName("TRANSCRIPTS – usuwanie z dwóch tabel")
    class TranscriptsDeletesFromTwoTables {

        @Test
        @DisplayName("wywołuje zarówno purgeTranscriptionsOlderThan jak i purgeAiSummariesOlderThan, nie dotyka contact/contact_event")
        void purgesBothTranscriptionAndAiSummary_neverTouchesContact() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.TRANSCRIPTS))
                    .thenReturn(3);
            when(contactService.purgeTranscriptionsOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(5);
            when(contactService.purgeAiSummariesOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(2);

            service.purge(TENANT_A, RetentionDataCategory.TRANSCRIPTS, PurgeTriggerType.MANUAL, USER_ID);

            verify(contactService).purgeTranscriptionsOlderThan(eq(TENANT_A), any(), anyInt());
            verify(contactService).purgeAiSummariesOlderThan(eq(TENANT_A), any(), anyInt());
            verify(contactService, never()).purgeContactsOlderThan(any(), any(), anyInt());
            verify(contactEventService, never()).purgeOlderThan(any(), any(), anyInt());
            verify(purgeLogRepository).markCompleted(any(), eq(TENANT_A), eq(7L));
        }
    }

    // =========================================================================
    // purgeId zwrócony natychmiast
    // =========================================================================

    @Nested
    @DisplayName("purgeId zwrócony natychmiast")
    class ReturnsImmediately {

        @Test
        @DisplayName("purge() zapisuje RUNNING i dyspatchuje do self.purgeAsync PRZED wykonaniem faktycznej pracy")
        void purge_returnsPurgeId_beforeActualWorkExecutes() {
            RetentionPurgeService mockSelf = mock(RetentionPurgeService.class, RETURNS_DEFAULTS);
            ReflectionTestUtils.setField(service, "self", mockSelf);

            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);

            UUID purgeId = service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            assertThat(purgeId).isNotNull();

            InOrder inOrder = inOrder(purgeLogRepository, mockSelf);
            inOrder.verify(purgeLogRepository).insertRunning(eq(purgeId), eq(TENANT_A),
                    eq(RetentionDataCategory.CONTACT_INTERACTIONS), eq(PurgeTriggerType.MANUAL), eq(USER_ID), any());
            inOrder.verify(mockSelf).purgeAsync(eq(purgeId), eq(TENANT_A),
                    eq(RetentionDataCategory.CONTACT_INTERACTIONS), any(), eq(USER_ID), any());

            // self jest zamockowany (no-op) – żadna faktyczna praca usuwania się nie odbyła
            // w ramach wywołania purge(), co dowodzi że cały ciężar leży w purgeAsync, nie w purge()
            verifyNoInteractions(contactService, contactEventService, emailMessageService, socialMessageService,
                    campaignArchiveRetentionRepository);
            verify(purgeLogRepository, never()).markCompleted(any(), any(), anyLong());
        }
    }

    // =========================================================================
    // Kategorie poza zakresem
    // =========================================================================

    @Nested
    @DisplayName("Kategorie poza zakresem (RECORDINGS)")
    class UnsupportedCategories {

        @ParameterizedTest(name = "kategoria {0} rzuca UnsupportedOperationException")
        @EnumSource(value = RetentionDataCategory.class, names = {"RECORDINGS"})
        @DisplayName("RECORDINGS odrzucana przed zapisem RUNNING")
        void unsupportedCategory_throwsBeforeAnyPersistence(RetentionDataCategory category) {
            assertThatThrownBy(() -> service.purge(TENANT_A, category, PurgeTriggerType.MANUAL, USER_ID))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining(category.name());

            verifyNoInteractions(purgeLogRepository, retentionPolicyService);
        }
    }

    // =========================================================================
    // CAMPAIGN_DATA – delegacja do purge_campaign_contact_archive (BE-119)
    // =========================================================================

    @Nested
    @DisplayName("CAMPAIGN_DATA – delegacja do purge_campaign_contact_archive (BE-119)")
    class CampaignDataPurge {

        @Test
        @DisplayName("wynik purgeEligible zapisywany jako rowsDeleted w markCompleted, inne zależności nietknięte")
        void purgeCampaignData_delegatesToRepositoryAndRecordsResult() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA))
                    .thenReturn(60);
            when(campaignArchiveRetentionRepository.purgeEligible(eq(TENANT_A), any())).thenReturn(17L);

            service.purge(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA, PurgeTriggerType.MANUAL, USER_ID);

            verify(campaignArchiveRetentionRepository).purgeEligible(eq(TENANT_A), any());
            verify(purgeLogRepository).markCompleted(any(), eq(TENANT_A), eq(17L));
            verifyNoInteractions(contactService, contactEventService, emailMessageService, socialMessageService);
        }

        @Test
        @DisplayName("cutoff przekazywany do purgeEligible to ten sam Instant co dla pozostałych kategorii – bez konwersji lat")
        void cutoff_passedDirectlyAsInstant_noYearConversion() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA))
                    .thenReturn(60);
            when(campaignArchiveRetentionRepository.purgeEligible(eq(TENANT_A), any())).thenReturn(0L);

            LocalDate expectedCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(60);
            Instant expectedCutoffInstant = expectedCutoffDate.atStartOfDay(ZoneOffset.UTC).toInstant();

            service.purge(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA, PurgeTriggerType.MANUAL, USER_ID);

            ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(campaignArchiveRetentionRepository).purgeEligible(eq(TENANT_A), cutoffCaptor.capture());
            assertThat(cutoffCaptor.getValue()).isEqualTo(expectedCutoffInstant);
        }

        @Test
        @DisplayName("izolacja cross-tenant: purge dla TENANT_A nigdy nie wywołuje repozytorium z TENANT_B")
        void purgeForTenantA_neverTouchesTenantB() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA))
                    .thenReturn(60);
            when(campaignArchiveRetentionRepository.purgeEligible(eq(TENANT_A), any())).thenReturn(3L);

            service.purge(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA, PurgeTriggerType.MANUAL, USER_ID);

            verify(campaignArchiveRetentionRepository, never()).purgeEligible(eq(TENANT_B), any());
            verify(campaignArchiveRetentionRepository).purgeEligible(eq(TENANT_A), any());
            verify(purgeLogRepository, never()).markCompleted(any(), eq(TENANT_B), anyLong());
        }

        @Test
        @DisplayName("wyjątek z repozytorium -> status FAILED, markCompleted nigdy nie wywołane")
        void repositoryThrows_marksFailed() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA))
                    .thenReturn(60);
            when(campaignArchiveRetentionRepository.purgeEligible(eq(TENANT_A), any()))
                    .thenThrow(new RuntimeException("DB connection lost"));

            service.purge(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA, PurgeTriggerType.MANUAL, USER_ID);

            verify(purgeLogRepository).markFailed(any(), eq(TENANT_A), eq("DB connection lost"), eq(0L));
            verify(purgeLogRepository, never()).markCompleted(any(), any(), anyLong());
        }
    }

    // =========================================================================
    // TenantContext – snapshot/restore/clear na granicy wątku roboczego
    // =========================================================================

    @Nested
    @DisplayName("TenantContext – lifecycle snapshot/restore/clear")
    class TenantContextLifecycle {

        @Test
        @DisplayName("purgeAsync działa poprawnie nawet gdy wątek wywołujący nie miał ustawionego TenantContext (AUTO trigger)")
        void purgeAsync_worksWithoutCallerTenantContext_simulatingAutoTrigger() {
            TenantContext.clear();
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(List.of());
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(0);

            // AUTO trigger – brak triggeredByUserId, brak ustawionego TenantContext (żadnego HTTP requestu)
            UUID purgeId = service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.AUTO, null);

            assertThat(purgeId).isNotNull();
            verify(purgeLogRepository).markCompleted(any(), eq(TENANT_A), eq(0L));
        }

        @Test
        @DisplayName("TenantContext jest wyczyszczony po zakończeniu purgeAsync (finally)")
        void tenantContext_isClearedAfterCompletion() {
            TenantContext.setTenantId(TENANT_A);
            TenantContext.setUserId(USER_ID);
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(List.of());
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(0);

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("TenantContext jest wyczyszczony nawet gdy purgeAsync zakończył się błędem")
        void tenantContext_isClearedEvenAfterFailure() {
            TenantContext.setTenantId(TENANT_A);
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), anyInt()))
                    .thenThrow(new RuntimeException("fail"));

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            assertThat(TenantContext.isSet()).isFalse();
        }
    }

    // =========================================================================
    // Sukces – status COMPLETED, completed_at, wpis audytowy
    // =========================================================================

    @Nested
    @DisplayName("Sukces – COMPLETED + audit log")
    class SuccessCompletesWithAudit {

        @Test
        @DisplayName("sukces publikuje wpis audytowy RETENTION_PURGE_COMPLETED z rowsDeleted")
        void success_publishesCompletedAuditEvent() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(uuids(3));
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), anyInt())).thenReturn(0);

            UUID purgeId = service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            AuditLogEvent event = captor.getValue();
            assertThat(event.action()).isEqualTo("RETENTION_PURGE_COMPLETED");
            assertThat(event.entityType()).isEqualTo("RETENTION_PURGE");
            assertThat(event.entityId()).isEqualTo(purgeId);
            assertThat(event.newValue()).contains("COMPLETED").contains("3");
        }
    }

    // =========================================================================
    // batchSize konfigurowalny + ochrona przed nieprawidłową wartością
    // =========================================================================

    @Nested
    @DisplayName("batchSize – konfigurowalność i ochrona przed 0/ujemną wartością")
    class BatchSizeConfiguration {

        @Test
        @DisplayName("niestandardowy batchSize jest przekazywany do zapytań repozytorium")
        void customBatchSize_isPropagatedToQueries() {
            ReflectionTestUtils.setField(service, "batchSize", 25);
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), eq(25))).thenReturn(List.of());
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), eq(25))).thenReturn(0);

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            verify(contactService).purgeContactsOlderThan(eq(TENANT_A), any(), eq(25));
        }

        @Test
        @DisplayName("batchSize=0 (błędna konfiguracja) -> fallback do DEFAULT_BATCH_SIZE=100, brak nieskończonej pętli")
        void zeroBatchSize_fallsBackToDefault() {
            ReflectionTestUtils.setField(service, "batchSize", 0);
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);
            when(contactService.purgeContactsOlderThan(eq(TENANT_A), any(), eq(100))).thenReturn(List.of());
            when(contactEventService.purgeOlderThan(eq(TENANT_A), any(), eq(100))).thenReturn(0);

            service.purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, PurgeTriggerType.MANUAL, USER_ID);

            verify(contactService).purgeContactsOlderThan(eq(TENANT_A), any(), eq(100));
        }
    }

    // =========================================================================
    // getPurgeStatus (BE-118)
    // =========================================================================

    @Nested
    @DisplayName("getPurgeStatus (BE-118)")
    class GetPurgeStatus {

        @Test
        @DisplayName("purgeId istnieje dla tenanta -> zwraca PurgeResultDto zmapowane z encji")
        void found_returnsMappedDto() {
            UUID purgeId = UUID.randomUUID();
            RetentionPurgeLog log = RetentionPurgeLog.builder()
                    .purgeId(purgeId)
                    .tenantId(TENANT_A)
                    .dataCategory(RetentionDataCategory.TRANSCRIPTS)
                    .triggerType(PurgeTriggerType.MANUAL)
                    .triggeredBy(USER_ID)
                    .cutoffDate(LocalDate.of(2026, 1, 1))
                    .rowsDeleted(42L)
                    .status(RetentionPurgeLog.STATUS_COMPLETED)
                    .startedAt(Instant.now())
                    .completedAt(Instant.now())
                    .build();
            when(purgeLogRepository.findById(purgeId, TENANT_A)).thenReturn(Optional.of(log));

            PurgeResultDto result = service.getPurgeStatus(TENANT_A, purgeId);

            assertThat(result.purgeId()).isEqualTo(purgeId);
            assertThat(result.tenantId()).isEqualTo(TENANT_A);
            assertThat(result.status()).isEqualTo(RetentionPurgeLog.STATUS_COMPLETED);
            assertThat(result.rowsDeleted()).isEqualTo(42L);
        }

        @Test
        @DisplayName("purgeId nie istnieje lub należy do innego tenanta -> ResourceNotFoundException (404)")
        void notFoundOrOtherTenant_throwsResourceNotFoundException() {
            UUID purgeId = UUID.randomUUID();
            when(purgeLogRepository.findById(purgeId, TENANT_A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPurgeStatus(TENANT_A, purgeId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("purgeId innego tenanta (TENANT_B) -> repozytorium filtruje po tenant_id, zwraca 404 dla TENANT_A")
        void purgeIdOfOtherTenant_isInvisibleToRequestingTenant() {
            UUID purgeIdOwnedByTenantB = UUID.randomUUID();
            // findById(purgeId, tenantId) filtruje po OBU kolumnach w SQL — dla TENANT_A zwraca empty,
            // mimo że wiersz istnieje (należy do TENANT_B).
            when(purgeLogRepository.findById(purgeIdOwnedByTenantB, TENANT_A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPurgeStatus(TENANT_A, purgeIdOwnedByTenantB))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =========================================================================
    // getPurgeHistory (BE-118)
    // =========================================================================

    @Nested
    @DisplayName("getPurgeHistory (BE-118)")
    class GetPurgeHistory {

        @Test
        @DisplayName("deleguje do repozytorium i mapuje stronę encji na stronę PurgeResultDto")
        void delegatesToRepositoryAndMapsPage() {
            RetentionPurgeLog log = RetentionPurgeLog.builder()
                    .purgeId(UUID.randomUUID())
                    .tenantId(TENANT_A)
                    .dataCategory(RetentionDataCategory.CONTACT_INTERACTIONS)
                    .triggerType(PurgeTriggerType.AUTO)
                    .cutoffDate(LocalDate.of(2026, 1, 1))
                    .rowsDeleted(10L)
                    .status(RetentionPurgeLog.STATUS_COMPLETED)
                    .startedAt(Instant.now())
                    .completedAt(Instant.now())
                    .build();
            Pageable pageable = PageRequest.of(0, 20);
            Page<RetentionPurgeLog> entityPage = new PageImpl<>(List.of(log), pageable, 1);
            when(purgeLogRepository.findAllByTenantId(TENANT_A, pageable)).thenReturn(entityPage);

            Page<PurgeResultDto> result = service.getPurgeHistory(TENANT_A, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).purgeId()).isEqualTo(log.getPurgeId());
        }

        @Test
        @DisplayName("brak historii -> strona pusta, nie rzuca")
        void emptyHistory_returnsEmptyPage() {
            Pageable pageable = PageRequest.of(0, 20);
            when(purgeLogRepository.findAllByTenantId(TENANT_A, pageable))
                    .thenReturn(new PageImpl<>(List.of(), pageable, 0));

            Page<PurgeResultDto> result = service.getPurgeHistory(TENANT_A, pageable);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    // =========================================================================
    // getPendingSummary (BE-118)
    // =========================================================================

    @Nested
    @DisplayName("getPendingSummary (BE-118)")
    class GetPendingSummary {

        @Test
        @DisplayName("cache pusty -> zwraca 4 wpisy, wszystkie computed=false")
        void emptyCache_returnsFourEntriesAllNotComputed() {
            when(summaryRepository.findAllByTenantId(TENANT_A)).thenReturn(List.of());

            List<RetentionSummaryDto> result = service.getPendingSummary(TENANT_A);

            assertThat(result).hasSize(RetentionDataCategory.values().length);
            assertThat(result).allSatisfy(dto -> {
                assertThat(dto.computed()).isFalse();
                assertThat(dto.eligibleRowCount()).isZero();
                assertThat(dto.oldestEligiblePeriod()).isNull();
                assertThat(dto.newestEligiblePeriod()).isNull();
                assertThat(dto.computedAt()).isNull();
            });
        }

        @Test
        @DisplayName("cache z 1 kategorią (typowo RECORDINGS brakuje) -> 4 wpisy, 1 computed=true + 3 computed=false")
        void partialCache_mixesComputedAndNotComputed() {
            Instant computedAt = Instant.now();
            TenantRetentionPendingSummaryRepository.PendingSummaryRow row =
                    new TenantRetentionPendingSummaryRepository.PendingSummaryRow(
                            RetentionDataCategory.CONTACT_INTERACTIONS, 123L,
                            LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 1), computedAt);
            when(summaryRepository.findAllByTenantId(TENANT_A)).thenReturn(List.of(row));

            List<RetentionSummaryDto> result = service.getPendingSummary(TENANT_A);

            assertThat(result).hasSize(4);

            RetentionSummaryDto contactInteractions = result.stream()
                    .filter(dto -> dto.dataCategory() == RetentionDataCategory.CONTACT_INTERACTIONS)
                    .findFirst().orElseThrow();
            assertThat(contactInteractions.computed()).isTrue();
            assertThat(contactInteractions.eligibleRowCount()).isEqualTo(123L);
            assertThat(contactInteractions.oldestEligiblePeriod()).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(contactInteractions.newestEligiblePeriod()).isEqualTo(LocalDate.of(2020, 6, 1));
            assertThat(contactInteractions.computedAt()).isEqualTo(computedAt);

            RetentionSummaryDto recordings = result.stream()
                    .filter(dto -> dto.dataCategory() == RetentionDataCategory.RECORDINGS)
                    .findFirst().orElseThrow();
            assertThat(recordings.computed()).isFalse();
            assertThat(recordings.eligibleRowCount()).isZero();
        }

        @Test
        @DisplayName("cache z eligibleRowCount=0 ale computed -> odróżnialne od 'jeszcze nie policzone' (computed=true)")
        void computedZero_isDistinguishableFromNotComputed() {
            Instant computedAt = Instant.now();
            TenantRetentionPendingSummaryRepository.PendingSummaryRow row =
                    new TenantRetentionPendingSummaryRepository.PendingSummaryRow(
                            RetentionDataCategory.TRANSCRIPTS, 0L, null, null, computedAt);
            when(summaryRepository.findAllByTenantId(TENANT_A)).thenReturn(List.of(row));

            List<RetentionSummaryDto> result = service.getPendingSummary(TENANT_A);

            RetentionSummaryDto transcripts = result.stream()
                    .filter(dto -> dto.dataCategory() == RetentionDataCategory.TRANSCRIPTS)
                    .findFirst().orElseThrow();
            assertThat(transcripts.computed()).isTrue();
            assertThat(transcripts.eligibleRowCount()).isZero();
            assertThat(transcripts.computedAt()).isEqualTo(computedAt);
        }
    }
}
