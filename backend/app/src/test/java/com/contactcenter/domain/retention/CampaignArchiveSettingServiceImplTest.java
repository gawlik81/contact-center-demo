package com.contactcenter.domain.retention;

import com.contactcenter.domain.audit.AuditLogEvent;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.retention.dto.CampaignArchivePreviewDto;
import com.contactcenter.domain.retention.dto.CampaignArchiveSettingDto;
import com.contactcenter.domain.retention.dto.UpdateCampaignArchiveSettingRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** BE-151: testy jednostkowe serwisu, audytu i walidatora requestu. */
@ExtendWith(MockitoExtension.class)
@DisplayName("CampaignArchiveSettingServiceImpl (BE-151)")
class CampaignArchiveSettingServiceImplTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private TenantCampaignArchiveSettingRepository repository;
    @Mock
    private AuditLogService auditLogService;

    private CampaignArchiveSettingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CampaignArchiveSettingServiceImpl(repository, auditLogService);
    }

    private static TenantCampaignArchiveSettingRepository.UpsertOutcome outcome(Integer previous, int days,
                                                                                  boolean written) {
        return new TenantCampaignArchiveSettingRepository.UpsertOutcome(row(days), previous, written);
    }

    private static TenantCampaignArchiveSetting row(int days) {
        return TenantCampaignArchiveSetting.builder().tenantId(TENANT).archiveAfterDays(days)
                .updatedBy(USER).createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    @Test
    @DisplayName("get: brak wiersza -> 180, isDefault=true, zakres 30-730")
    void get_defaultWhenNoRow() {
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.empty());

        CampaignArchiveSettingDto dto = service.get(TENANT);

        assertThat(dto.archiveAfterDays()).isEqualTo(180);
        assertThat(dto.isDefault()).isTrue();
        assertThat(dto.defaultDays()).isEqualTo(180);
        assertThat(dto.minDays()).isEqualTo(30);
        assertThat(dto.maxDays()).isEqualTo(730);
    }

    @Test
    @DisplayName("get: jawna wartosc -> isDefault=false")
    void get_explicit() {
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.of(row(45)));

        CampaignArchiveSettingDto dto = service.get(TENANT);

        assertThat(dto.archiveAfterDays()).isEqualTo(45);
        assertThat(dto.isDefault()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 730})
    @DisplayName("update: wartosci graniczne zapisane")
    void update_boundaries(int days) {
        when(repository.upsertReturningPrevious(TENANT, days, USER)).thenReturn(outcome(null, days, true));

        assertThat(service.update(TENANT, days, USER).archiveAfterDays()).isEqualTo(days);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 29, 731, 100000})
    @DisplayName("update/preview: null i poza zakresem -> IllegalArgumentException, zadnego dostepu do repo")
    void rejectsInvalid(Integer days) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.update(TENANT, days, USER));
        assertThatIllegalArgumentException().isThrownBy(() -> service.preview(TENANT, days));
        verifyNoInteractions(repository, auditLogService);
    }

    @Test
    @DisplayName("audyt: skrocenie 180 -> 30 publikuje wpis (kto, tenant, stara/nowa, shortened=true)")
    void audit_shortening() {
        when(repository.upsertReturningPrevious(TENANT, 30, USER)).thenReturn(outcome(null, 30, true));

        service.update(TENANT, 30, USER);

        ArgumentCaptor<AuditLogEvent> cap = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(auditLogService).publishAuditEvent(cap.capture());
        AuditLogEvent e = cap.getValue();
        assertThat(e.tenantId()).isEqualTo(TENANT);
        assertThat(e.userId()).isEqualTo(USER);
        assertThat(e.action()).isEqualTo("CAMPAIGN_ARCHIVE_PERIOD_CHANGED");
        assertThat(e.entityType()).isEqualTo("TENANT_CAMPAIGN_ARCHIVE_SETTING");
        assertThat(e.entityId()).isEqualTo(TENANT);
        assertThat(e.oldValue()).contains("\"archiveAfterDays\":180").contains("\"isDefault\":true");
        assertThat(e.newValue()).contains("\"archiveAfterDays\":30").contains("\"shortened\":true");
    }

    @Test
    @DisplayName("audyt: wydluzenie 60 -> 365 publikuje wpis z shortened=false")
    void audit_lengthening() {
        when(repository.upsertReturningPrevious(TENANT, 365, USER)).thenReturn(outcome(60, 365, true));

        service.update(TENANT, 365, USER);

        ArgumentCaptor<AuditLogEvent> cap = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(auditLogService).publishAuditEvent(cap.capture());
        assertThat(cap.getValue().oldValue()).contains("\"archiveAfterDays\":60").contains("\"isDefault\":false");
        assertThat(cap.getValue().newValue()).contains("\"archiveAfterDays\":365").contains("\"shortened\":false");
    }

    @Test
    @DisplayName("PUT z ta sama wartoscia: brak zapisu i brak audytu (bez spamu)")
    void update_sameValue_noWriteNoAudit() {
        when(repository.upsertReturningPrevious(TENANT, 90, USER)).thenReturn(outcome(90, 90, false));

        CampaignArchiveSettingDto dto = service.update(TENANT, 90, USER);

        assertThat(dto.archiveAfterDays()).isEqualTo(90);
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("pierwszy jawny zapis 180 (= domyslne): wiersz powstaje, audytu brak (efektywnie bez zmiany)")
    void update_pinDefault_writesRowWithoutAudit() {
        when(repository.upsertReturningPrevious(TENANT, 180, USER)).thenReturn(outcome(null, 180, true));

        CampaignArchiveSettingDto dto = service.update(TENANT, 180, USER);

        assertThat(dto.isDefault()).isFalse();
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("audyt best-effort: SYNCHRONICZNY wyjatek publishAuditEvent (np. TaskRejectedException) nie psuje PUT")
    void audit_synchronousPublishException_doesNotFailPut() {
        // Uwaga: publishAuditEvent jest @Async i awarie brokera (AmqpException) polyka sam, wiec ten test
        // NIE modeluje awarii RabbitMQ - tylko to, co faktycznie moze dotrzec do wywolujacego
        // (odrzucenie zadania przez przepelniony executor).
        when(repository.upsertReturningPrevious(TENANT, 30, USER)).thenReturn(outcome(null, 30, true));
        doThrow(new TaskRejectedException("executor saturated")).when(auditLogService).publishAuditEvent(any());

        assertThat(service.update(TENANT, 30, USER).archiveAfterDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("preview: roznica wzgledem okresu biezacego, skrocenie wykryte")
    void preview_diff() {
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.empty());
        when(repository.countArchivable(TENANT, 30))
                .thenReturn(new TenantCampaignArchiveSettingRepository.ArchivableCounts(5, 500));
        when(repository.countArchivable(TENANT, 180))
                .thenReturn(new TenantCampaignArchiveSettingRepository.ArchivableCounts(1, 100));

        CampaignArchivePreviewDto p = service.preview(TENANT, 30);

        assertThat(p.currentDays()).isEqualTo(180);
        assertThat(p.newDays()).isEqualTo(30);
        assertThat(p.campaignsToArchive()).isEqualTo(5);
        assertThat(p.contactsToArchive()).isEqualTo(500);
        assertThat(p.additionalCampaigns()).isEqualTo(4);
        assertThat(p.additionalContacts()).isEqualTo(400);
        assertThat(p.shortensPeriod()).isTrue();
    }

    @Test
    @DisplayName("preview: wydluzenie daje ujemna roznice, shortensPeriod=false; ten sam okres liczony raz")
    void preview_lengthening_and_same() {
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.of(row(60)));
        when(repository.countArchivable(TENANT, 365))
                .thenReturn(new TenantCampaignArchiveSettingRepository.ArchivableCounts(0, 0));
        when(repository.countArchivable(TENANT, 60))
                .thenReturn(new TenantCampaignArchiveSettingRepository.ArchivableCounts(2, 20));

        CampaignArchivePreviewDto p = service.preview(TENANT, 365);
        assertThat(p.additionalCampaigns()).isEqualTo(-2);
        assertThat(p.additionalContacts()).isEqualTo(-20);
        assertThat(p.shortensPeriod()).isFalse();

        CampaignArchivePreviewDto same = service.preview(TENANT, 60);
        assertThat(same.additionalContacts()).isZero();
        verify(repository, org.mockito.Mockito.times(2)).countArchivable(TENANT, 60);
    }

    // ---- Walidator Bean Validation requestu ----

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(ints = {30, 180, 730})
    @DisplayName("walidator: 30/180/730 poprawne")
    void validator_accepts(int days) {
        assertThat(validator.validate(new UpdateCampaignArchiveSettingRequest(days))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-5, 0, 29, 731})
    @DisplayName("walidator: null, 29, 731 odrzucone")
    void validator_rejects(Integer days) {
        assertThat(validator.validate(new UpdateCampaignArchiveSettingRequest(days))).hasSize(1);
    }
}
