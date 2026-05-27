package com.contactcenter.domain.disposition;

import com.contactcenter.domain.disposition.dto.AvailableDispositionDto;
import com.contactcenter.domain.disposition.dto.CreateCustomDispositionRequest;
import com.contactcenter.domain.disposition.dto.CustomDispositionDto;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link CustomDispositionService} (BE-092).
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Logikę resolucji dyspozycji (priorytet kampania → kolejka → system)</li>
 *   <li>CRUD dyspozycji per kampania i per kolejka z walidacją duplikatów</li>
 *   <li>Gwarancję niepustej listy w każdym scenariuszu resolucji</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomDispositionService – logika resolucji i CRUD dyspozycji")
class CustomDispositionServiceTest {

    private static final UUID TENANT_ID    = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CAMPAIGN_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID QUEUE_ID     = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DISP_ID      = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private CustomDispositionRepository customDispositionRepository;

    @InjectMocks
    private CustomDispositionService customDispositionService;

    // =========================================================================
    // resolveForContact – logika priorytetu
    // =========================================================================

    @Nested
    @DisplayName("resolveForContact()")
    class ResolveForContact {

        @Test
        @DisplayName("kampania ma dyspozycje → zwróć dyspozycje kampanii (najwyższy priorytet)")
        void resolveForContact_withCampaignDispositions_returnsCampaignDispositions() {
            // given
            CustomDisposition d = buildDisposition(DISP_ID, TENANT_ID, CAMPAIGN_ID, null,
                    "SALE", "Sprzedaż", "positive", 1);

            when(customDispositionRepository.existsByCampaignId(CAMPAIGN_ID, TENANT_ID)).thenReturn(true);
            when(customDispositionRepository.findByCampaignId(CAMPAIGN_ID, TENANT_ID)).thenReturn(List.of(d));

            // when
            List<AvailableDispositionDto> result =
                    customDispositionService.resolveForContact(CAMPAIGN_ID, QUEUE_ID, TENANT_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).dispositionCode()).isEqualTo("SALE");
            assertThat(result.get(0).tone()).isEqualTo("positive");

            // kolejka nie powinna być sprawdzana gdy kampania ma dyspozycje
            verify(customDispositionRepository, never()).existsByQueueId(any(), any());
            verify(customDispositionRepository, never()).findByQueueId(any(), any());
        }

        @Test
        @DisplayName("kampania bez dyspozycji, kolejka ma dyspozycje → zwróć dyspozycje kolejki")
        void resolveForContact_withQueueDispositions_returnsQueueDispositions() {
            // given
            CustomDisposition d = buildDisposition(DISP_ID, TENANT_ID, null, QUEUE_ID,
                    "NO_INTEREST", "Brak zainteresowania", "negative", 1);

            when(customDispositionRepository.existsByCampaignId(CAMPAIGN_ID, TENANT_ID)).thenReturn(false);
            when(customDispositionRepository.existsByQueueId(QUEUE_ID, TENANT_ID)).thenReturn(true);
            when(customDispositionRepository.findByQueueId(QUEUE_ID, TENANT_ID)).thenReturn(List.of(d));

            // when
            List<AvailableDispositionDto> result =
                    customDispositionService.resolveForContact(CAMPAIGN_ID, QUEUE_ID, TENANT_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).dispositionCode()).isEqualTo("NO_INTEREST");
            assertThat(result.get(0).tone()).isEqualTo("negative");

            verify(customDispositionRepository, never()).findByCampaignId(any(), any());
        }

        @Test
        @DisplayName("brak kampanii i kolejki z dyspozycjami → zwróć systemowe domyślne")
        void resolveForContact_noCampaignNoQueue_returnsSystemDefaults() {
            // given
            when(customDispositionRepository.existsByCampaignId(CAMPAIGN_ID, TENANT_ID)).thenReturn(false);
            when(customDispositionRepository.existsByQueueId(QUEUE_ID, TENANT_ID)).thenReturn(false);

            // when
            List<AvailableDispositionDto> result =
                    customDispositionService.resolveForContact(CAMPAIGN_ID, QUEUE_ID, TENANT_ID);

            // then
            assertThat(result).isEqualTo(CustomDispositionService.SYSTEM_DEFAULTS);
            assertThat(result).hasSize(6);
            assertThat(result.get(0).dispositionCode()).isEqualTo("SALE");
        }

        @Test
        @DisplayName("campaignId=null, kolejka ma dyspozycje → zwróć dyspozycje kolejki")
        void resolveForContact_campaignIdNull_queueHasDispositions_returnsQueueDispositions() {
            // given
            CustomDisposition d = buildDisposition(DISP_ID, TENANT_ID, null, QUEUE_ID,
                    "CALLBACK", "Oddzwonienie", "warning", 1);

            when(customDispositionRepository.existsByQueueId(QUEUE_ID, TENANT_ID)).thenReturn(true);
            when(customDispositionRepository.findByQueueId(QUEUE_ID, TENANT_ID)).thenReturn(List.of(d));

            // when
            List<AvailableDispositionDto> result =
                    customDispositionService.resolveForContact(null, QUEUE_ID, TENANT_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).dispositionCode()).isEqualTo("CALLBACK");

            // campaign existsByCampaignId nie powinien być wywołany gdy campaignId=null
            verify(customDispositionRepository, never()).existsByCampaignId(any(), any());
        }

        @Test
        @DisplayName("nigdy nie zwraca pustej listy — gwarancja niepustości")
        void resolveForContact_neverReturnsEmptyList() {
            // given — oba pola null, brak konfiguracji → system defaults
            // when
            List<AvailableDispositionDto> result =
                    customDispositionService.resolveForContact(null, null, TENANT_ID);

            // then
            assertThat(result).isNotEmpty();
            assertThat(result).hasSizeGreaterThanOrEqualTo(1);
        }
    }

    // =========================================================================
    // createForCampaign – walidacja duplikatu kodu
    // =========================================================================

    @Nested
    @DisplayName("createForCampaign()")
    class CreateForCampaign {

        @Test
        @DisplayName("duplikat kodu dla kampanii → rzuca ConflictException (HTTP 409)")
        void createForCampaign_duplicateCode_throwsConflictException() {
            // given
            CreateCustomDispositionRequest req = new CreateCustomDispositionRequest(
                    "SALE", "Sprzedaż", "positive", 1);

            when(customDispositionRepository.existsCodeForCampaign(CAMPAIGN_ID, "SALE", TENANT_ID))
                    .thenReturn(true);

            // when / then
            assertThatThrownBy(() ->
                    customDispositionService.createForCampaign(CAMPAIGN_ID, req, TENANT_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Disposition code already exists for this campaign");

            verify(customDispositionRepository, never()).insert(any());
        }

        @Test
        @DisplayName("sukces → tworzy dyspozycję przypisaną do kampanii")
        void createForCampaign_success_returnsDto() {
            // given
            CreateCustomDispositionRequest req = new CreateCustomDispositionRequest(
                    "SALE", "Sprzedaż", "positive", 1);

            CustomDisposition saved = buildDisposition(DISP_ID, TENANT_ID, CAMPAIGN_ID, null,
                    "SALE", "Sprzedaż", "positive", 1);

            when(customDispositionRepository.existsCodeForCampaign(CAMPAIGN_ID, "SALE", TENANT_ID))
                    .thenReturn(false);
            when(customDispositionRepository.insert(any(CustomDisposition.class))).thenReturn(saved);

            // when
            CustomDispositionDto result = customDispositionService.createForCampaign(CAMPAIGN_ID, req, TENANT_ID);

            // then
            assertThat(result.id()).isEqualTo(DISP_ID);
            assertThat(result.dispositionCode()).isEqualTo("SALE");
            assertThat(result.isActive()).isTrue();
            verify(customDispositionRepository).insert(any(CustomDisposition.class));
        }
    }

    // =========================================================================
    // createForQueue – walidacja duplikatu kodu
    // =========================================================================

    @Nested
    @DisplayName("createForQueue()")
    class CreateForQueue {

        @Test
        @DisplayName("duplikat kodu dla kolejki → rzuca ConflictException (HTTP 409)")
        void createForQueue_duplicateCode_throwsConflictException() {
            // given
            CreateCustomDispositionRequest req = new CreateCustomDispositionRequest(
                    "NO_INTEREST", "Brak zainteresowania", "negative", 2);

            when(customDispositionRepository.existsCodeForQueue(QUEUE_ID, "NO_INTEREST", TENANT_ID))
                    .thenReturn(true);

            // when / then
            assertThatThrownBy(() ->
                    customDispositionService.createForQueue(QUEUE_ID, req, TENANT_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Disposition code already exists for this queue");

            verify(customDispositionRepository, never()).insert(any());
        }
    }

    // =========================================================================
    // update – aktualizacja dyspozycji
    // =========================================================================

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("dyspozycja nie istnieje → rzuca ResourceNotFoundException (HTTP 404)")
        void update_dispositionNotFound_throwsResourceNotFoundException() {
            // given
            when(customDispositionRepository.findByIdAndTenantId(DISP_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            com.contactcenter.domain.disposition.dto.UpdateCustomDispositionRequest req =
                    new com.contactcenter.domain.disposition.dto.UpdateCustomDispositionRequest(
                            "Nowa etykieta", "neutral", 2, true);

            // when / then
            assertThatThrownBy(() ->
                    customDispositionService.update(DISP_ID, req, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(DISP_ID.toString());

            verify(customDispositionRepository, never()).update(any());
        }

        @Test
        @DisplayName("sukces → aktualizuje pola i zwraca DTO")
        void update_success_returnsUpdatedDto() {
            // given
            CustomDisposition existing = buildDisposition(DISP_ID, TENANT_ID, CAMPAIGN_ID, null,
                    "SALE", "Sprzedaż", "positive", 1);
            CustomDisposition updated = buildDisposition(DISP_ID, TENANT_ID, CAMPAIGN_ID, null,
                    "SALE", "Nowa etykieta", "neutral", 2);
            updated.setActive(false);

            com.contactcenter.domain.disposition.dto.UpdateCustomDispositionRequest req =
                    new com.contactcenter.domain.disposition.dto.UpdateCustomDispositionRequest(
                            "Nowa etykieta", "neutral", 2, false);

            when(customDispositionRepository.findByIdAndTenantId(DISP_ID, TENANT_ID))
                    .thenReturn(Optional.of(existing));
            when(customDispositionRepository.update(any(CustomDisposition.class))).thenReturn(updated);

            // when
            CustomDispositionDto result = customDispositionService.update(DISP_ID, req, TENANT_ID);

            // then
            assertThat(result.label()).isEqualTo("Nowa etykieta");
            assertThat(result.tone()).isEqualTo("neutral");
            assertThat(result.ordinal()).isEqualTo(2);
            assertThat(result.isActive()).isFalse();
        }
    }

    // =========================================================================
    // delete – usuwanie dyspozycji
    // =========================================================================

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("dyspozycja nie istnieje → rzuca ResourceNotFoundException (HTTP 404)")
        void delete_dispositionNotFound_throwsResourceNotFoundException() {
            // given
            when(customDispositionRepository.delete(DISP_ID, TENANT_ID)).thenReturn(0);

            // when / then
            assertThatThrownBy(() ->
                    customDispositionService.delete(DISP_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(DISP_ID.toString());
        }

        @Test
        @DisplayName("sukces → usuwa dyspozycję bez wyjątku")
        void delete_success_doesNotThrow() {
            // given
            when(customDispositionRepository.delete(DISP_ID, TENANT_ID)).thenReturn(1);

            // when / then — nie rzuca
            customDispositionService.delete(DISP_ID, TENANT_ID);

            verify(customDispositionRepository).delete(DISP_ID, TENANT_ID);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private CustomDisposition buildDisposition(UUID id, UUID tenantId, UUID campaignId, UUID queueId,
                                               String code, String label, String tone, int ordinal) {
        CustomDisposition d = new CustomDisposition();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setCampaignId(campaignId);
        d.setQueueId(queueId);
        d.setDispositionCode(code);
        d.setLabel(label);
        d.setTone(tone);
        d.setOrdinal(ordinal);
        d.setActive(true);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        return d;
    }
}
