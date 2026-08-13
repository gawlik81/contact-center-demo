package com.contactcenter.domain.recording;

import com.contactcenter.domain.contact.ContactRecordingEntry;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.retention.RetentionDataCategory;
import com.contactcenter.domain.retention.RetentionPolicyService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link RecordingRetentionJob} (EPIC-29, BE-116).
 *
 * <p><strong>Kluczowa zmiana BE-116 pod testem:</strong> granica czasowa retencji jest liczona
 * INDYWIDUALNIE dla każdego tenanta wewnątrz pętli (na podstawie
 * {@link RetentionPolicyService#getRetentionDays}), a nie jednym globalnym cutoffem
 * ({@code S3Properties.retentionDays}) wyliczonym raz PRZED pętlą po tenantach, jak przed BE-116.
 * Test regresyjny AC BE-116: dwaj tenanci z różnymi wartościami {@code RECORDINGS.retentionMonths}
 * — job usuwa nagrania KAŻDEGO zgodnie z JEGO WŁASNĄ retencją, nie globalną.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RecordingRetentionJob – retencja nagrań per tenant (BE-116)")
class RecordingRetentionJobTest {

    /** Tenant z krótką retencją: 1 miesiąc -> 30 dni. */
    private static final UUID TENANT_SHORT_RETENTION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    /** Tenant z długą retencją: 12 miesięcy -> 360 dni. */
    private static final UUID TENANT_LONG_RETENTION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CONTACT_SHORT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONTACT_LONG  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private RecordingService recordingService;

    @Mock
    private ContactService contactService;

    @Mock
    private RetentionPolicyService retentionPolicyService;

    @InjectMocks
    private RecordingRetentionJob job;

    @BeforeEach
    void setUp() {
        when(contactService.findExpiredRecordings(any(), any(), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        // Wątek testowy jest reużywany między metodami testowymi w tej klasie (analogicznie do
        // wątku puli schedulera w produkcji) – TenantContext MUSI zostać wyczyszczony dla izolacji,
        // szczególnie istotne odkąd job realnie ustawia TenantContext (regresja BE-116).
        TenantContext.clear();
    }

    // =========================================================================
    // Regresja BE-116 – per-tenant retencja (kryterium akceptacji)
    // =========================================================================

    @Nested
    @DisplayName("runRetentionJob() – dwaj tenanci z różną retencją RECORDINGS")
    class PerTenantRetention {

        @Test
        @DisplayName("wyznacza ODDZIELNY cutoff dla każdego tenanta wg JEGO WŁASNEJ polityki, nie globalnej wartości")
        void shouldUseIndependentCutoffPerTenant() {
            // given – tenant A: 1 miesiąc (30 dni), tenant B: 12 miesięcy (360 dni)
            when(contactService.findTenantsWithRecordings())
                    .thenReturn(List.of(TENANT_SHORT_RETENTION, TENANT_LONG_RETENTION));
            when(retentionPolicyService.getRetentionDays(TENANT_SHORT_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenReturn(30);
            when(retentionPolicyService.getRetentionDays(TENANT_LONG_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenReturn(360);

            // when
            job.runRetentionJob();

            // then – cutoff przekazany do findExpiredRecordings różni się per tenant o ~330 dni
            ArgumentCaptor<Instant> shortCutoffCaptor = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> longCutoffCaptor = ArgumentCaptor.forClass(Instant.class);

            verify(contactService).findExpiredRecordings(
                    eq(TENANT_SHORT_RETENTION), shortCutoffCaptor.capture(), eq(100));
            verify(contactService).findExpiredRecordings(
                    eq(TENANT_LONG_RETENTION), longCutoffCaptor.capture(), eq(100));

            Instant expectedShortCutoff = Instant.now().minus(30, ChronoUnit.DAYS);
            Instant expectedLongCutoff = Instant.now().minus(360, ChronoUnit.DAYS);

            assertThat(shortCutoffCaptor.getValue()).isCloseTo(expectedShortCutoff, within(5, ChronoUnit.SECONDS));
            assertThat(longCutoffCaptor.getValue()).isCloseTo(expectedLongCutoff, within(5, ChronoUnit.SECONDS));

            // Różnica między cutoffami odpowiada różnicy retencji (330 dni = 360 - 30), nie jest to
            // ta sama wartość. Tolerancja +/-1 dzień: oba cutoffy pochodzą z osobnych wywołań
            // Instant.now() (w pętli po tenantach) wykonanych kilka milisekund od siebie, co przy
            // truncacji ChronoUnit.DAYS.between (liczba PEŁNYCH dni) może przesunąć wynik o 1 dzień.
            long diffDays = ChronoUnit.DAYS.between(longCutoffCaptor.getValue(), shortCutoffCaptor.getValue());
            assertThat(diffDays).isBetween(329L, 330L);
        }

        @Test
        @DisplayName("usuwa nagrania tenanta A wg JEGO retencji, nie wpływając na przetwarzanie tenanta B")
        void shouldDeleteRecordingsIndependentlyPerTenant() {
            when(contactService.findTenantsWithRecordings())
                    .thenReturn(List.of(TENANT_SHORT_RETENTION, TENANT_LONG_RETENTION));
            when(retentionPolicyService.getRetentionDays(TENANT_SHORT_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenReturn(30);
            when(retentionPolicyService.getRetentionDays(TENANT_LONG_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenReturn(360);

            ContactRecordingEntry expiredForShort = new ContactRecordingEntry(CONTACT_SHORT, "recordings/short.mp3");
            ContactRecordingEntry expiredForLong = new ContactRecordingEntry(CONTACT_LONG, "recordings/long.mp3");

            when(contactService.findExpiredRecordings(eq(TENANT_SHORT_RETENTION), any(), anyInt()))
                    .thenReturn(List.of(expiredForShort));
            when(contactService.findExpiredRecordings(eq(TENANT_LONG_RETENTION), any(), anyInt()))
                    .thenReturn(List.of(expiredForLong));

            job.runRetentionJob();

            verify(recordingService).deleteFromS3("recordings/short.mp3");
            verify(contactService).clearRecordingUrl(CONTACT_SHORT, TENANT_SHORT_RETENTION);

            verify(recordingService).deleteFromS3("recordings/long.mp3");
            verify(contactService).clearRecordingUrl(CONTACT_LONG, TENANT_LONG_RETENTION);
        }
    }

    // =========================================================================
    // Odporność na błędy – brak polityki dla jednego tenanta nie przerywa jobu
    // =========================================================================

    @Nested
    @DisplayName("runRetentionJob() – odporność na błędy per tenant")
    class ErrorResilience {

        @Test
        @DisplayName("brak skonfigurowanej polityki RECORDINGS dla jednego tenanta nie przerywa przetwarzania pozostałych")
        void shouldSkipTenantWithMissingPolicyWithoutFailingWholeJob() {
            UUID tenantWithoutPolicy = UUID.fromString("33333333-3333-3333-3333-333333333333");

            when(contactService.findTenantsWithRecordings())
                    .thenReturn(List.of(tenantWithoutPolicy, TENANT_LONG_RETENTION));
            when(retentionPolicyService.getRetentionDays(tenantWithoutPolicy, RetentionDataCategory.RECORDINGS))
                    .thenThrow(new ResourceNotFoundException("Brak polityki retencji"));
            when(retentionPolicyService.getRetentionDays(TENANT_LONG_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenReturn(360);

            ContactRecordingEntry expiredForLong = new ContactRecordingEntry(CONTACT_LONG, "recordings/long.mp3");
            when(contactService.findExpiredRecordings(eq(TENANT_LONG_RETENTION), any(), anyInt()))
                    .thenReturn(List.of(expiredForLong));

            job.runRetentionJob();

            // tenant bez polityki – żadnej próby wyszukania nagrań (błąd rzucony wcześniej, wewnątrz try/catch)
            verify(contactService, never()).findExpiredRecordings(eq(tenantWithoutPolicy), any(), anyInt());
            // tenant z poprawną polityką – przetworzony normalnie mimo błędu poprzedniego tenanta
            verify(recordingService).deleteFromS3("recordings/long.mp3");
            verify(contactService).clearRecordingUrl(CONTACT_LONG, TENANT_LONG_RETENTION);
        }
    }

    // =========================================================================
    // Regresja: TenantContext w wątku schedulera (bug wykryty przy ręcznym uruchomieniu joba)
    // =========================================================================

    /**
     * {@code contactService} jest tu mockiem, więc te testy NIE weryfikują, że
     * {@code TenantAwareRepository#assertSameTenant} rzeczywiście przechodzi (to pokrywa
     * {@code ContactRepositoryClearRecordingUrlTest}, który używa prawdziwego repozytorium).
     * Weryfikują za to własną odpowiedzialność JOBA: że {@link TenantContext} (klasa statyczna,
     * NIGDY nie mockowana w tych testach) jest faktycznie ustawiany na poprawny {@code tenantId}
     * PRZED każdym wywołaniem serwisu wymagającego kontekstu, i czyszczony po każdej iteracji —
     * niezależnie od tego, czy przetwarzanie tenanta się powiodło, czy rzuciło wyjątek.
     */
    @Nested
    @DisplayName("TenantContext – wątek schedulera (regresja BE-116)")
    class TenantContextRegression {

        @Test
        @DisplayName("wątek testowy startuje bez kontekstu (dokładnie jak wątek @Scheduled)")
        void threadStartsWithoutContext() {
            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("TenantContext jest ustawiony na tenantId W MOMENCIE wywołania clearRecordingUrl")
        void contextIsSetToTenantId_whenClearingRecordingUrl() {
            when(contactService.findTenantsWithRecordings()).thenReturn(List.of(TENANT_SHORT_RETENTION));
            when(retentionPolicyService.getRetentionDays(TENANT_SHORT_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenReturn(30);
            ContactRecordingEntry expired = new ContactRecordingEntry(CONTACT_SHORT, "recordings/short.mp3");
            when(contactService.findExpiredRecordings(eq(TENANT_SHORT_RETENTION), any(), anyInt()))
                    .thenReturn(List.of(expired));

            List<UUID> observedContextDuringClear = new ArrayList<>();
            doAnswer(invocation -> {
                observedContextDuringClear.add(TenantContext.getTenantIdOrNull());
                return null;
            }).when(contactService).clearRecordingUrl(eq(CONTACT_SHORT), eq(TENANT_SHORT_RETENTION));

            job.runRetentionJob();

            assertThat(observedContextDuringClear).containsExactly(TENANT_SHORT_RETENTION);
        }

        @Test
        @DisplayName("kontekst NIE wycieka między tenantami – każdy widzi WYŁĄCZNIE własny tenantId, nigdy poprzedniego")
        void contextIsIsolatedBetweenTenants() {
            when(contactService.findTenantsWithRecordings())
                    .thenReturn(List.of(TENANT_SHORT_RETENTION, TENANT_LONG_RETENTION));
            when(retentionPolicyService.getRetentionDays(TENANT_SHORT_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenReturn(30);
            when(retentionPolicyService.getRetentionDays(TENANT_LONG_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenReturn(360);

            ContactRecordingEntry expiredForShort = new ContactRecordingEntry(CONTACT_SHORT, "recordings/short.mp3");
            ContactRecordingEntry expiredForLong = new ContactRecordingEntry(CONTACT_LONG, "recordings/long.mp3");
            when(contactService.findExpiredRecordings(eq(TENANT_SHORT_RETENTION), any(), anyInt()))
                    .thenReturn(List.of(expiredForShort));
            when(contactService.findExpiredRecordings(eq(TENANT_LONG_RETENTION), any(), anyInt()))
                    .thenReturn(List.of(expiredForLong));

            List<UUID> observedForShort = new ArrayList<>();
            List<UUID> observedForLong = new ArrayList<>();
            doAnswer(invocation -> {
                observedForShort.add(TenantContext.getTenantIdOrNull());
                return null;
            }).when(contactService).clearRecordingUrl(eq(CONTACT_SHORT), eq(TENANT_SHORT_RETENTION));
            doAnswer(invocation -> {
                observedForLong.add(TenantContext.getTenantIdOrNull());
                return null;
            }).when(contactService).clearRecordingUrl(eq(CONTACT_LONG), eq(TENANT_LONG_RETENTION));

            job.runRetentionJob();

            assertThat(observedForShort).containsExactly(TENANT_SHORT_RETENTION);
            assertThat(observedForLong).containsExactly(TENANT_LONG_RETENTION);
        }

        @Test
        @DisplayName("kontekst jest wyczyszczony PO zakończeniu jobu – brak wycieku do kolejnego przebiegu/schedulera")
        void contextIsClearedAfterJobCompletes() {
            when(contactService.findTenantsWithRecordings()).thenReturn(List.of(TENANT_LONG_RETENTION));
            when(retentionPolicyService.getRetentionDays(TENANT_LONG_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenReturn(360);

            job.runRetentionJob();

            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("kontekst jest wyczyszczony nawet gdy przetwarzanie tenanta rzuca wyjątek (finally)")
        void contextIsClearedEvenWhenTenantProcessingThrows() {
            when(contactService.findTenantsWithRecordings()).thenReturn(List.of(TENANT_SHORT_RETENTION));
            when(retentionPolicyService.getRetentionDays(TENANT_SHORT_RETENTION, RetentionDataCategory.RECORDINGS))
                    .thenThrow(new RuntimeException("boom - simulated failure mid-processing"));

            job.runRetentionJob();

            assertThat(TenantContext.isSet()).isFalse();
        }
    }
}
