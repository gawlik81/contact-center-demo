package com.contactcenter.domain.disposition;

import com.contactcenter.domain.disposition.dto.ApplySetResponse;
import com.contactcenter.domain.disposition.dto.CreateDispositionSetRequest;
import com.contactcenter.domain.disposition.dto.DispositionSetDto;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link DispositionSetService} (BE-095).
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>CRUD zestawów dyspozycji z walidacją nazwy (duplikat → ConflictException)</li>
 *   <li>Aplikowanie zestawu do kampanii i kolejki (kopiowanie elementów)</li>
 *   <li>Obsługę duplikatów kodów przy aplikowaniu (skipped count)</li>
 *   <li>Rzucanie ResourceNotFoundException dla pustego/nieistniejącego zestawu</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DispositionSetService – CRUD zestawów i aplikowanie")
class DispositionSetServiceTest {

    private static final UUID TENANT_ID    = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SET_ID       = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CAMPAIGN_ID  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID QUEUE_ID     = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID ITEM_ID_1    = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID ITEM_ID_2    = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Mock
    private DispositionSetRepository setRepo;

    @Mock
    private DispositionSetItemRepository itemRepo;

    @Mock
    private CustomDispositionRepository customDispositionRepository;

    @InjectMocks
    private DispositionSetService service;

    // =========================================================================
    // createSet
    // =========================================================================

    @Nested
    @DisplayName("createSet")
    class CreateSet {

        @Test
        @DisplayName("duplikat nazwy → rzuca ConflictException")
        void createSet_duplicateName_throwsConflict() {
            when(setRepo.existsByNameAndTenantId("TestSet", TENANT_ID)).thenReturn(true);

            assertThatThrownBy(() ->
                    service.createSet(new CreateDispositionSetRequest("TestSet", null), TENANT_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("TestSet");

            verify(setRepo, never()).insert(any());
        }

        @Test
        @DisplayName("unikalna nazwa → tworzy zestaw i zwraca DTO")
        void createSet_success_returnsDto() {
            DispositionSet saved = buildSet(SET_ID, "TestSet", "Opis zestawu");

            when(setRepo.existsByNameAndTenantId("TestSet", TENANT_ID)).thenReturn(false);
            when(setRepo.insert(any(DispositionSet.class))).thenReturn(saved);

            DispositionSetDto dto = service.createSet(
                    new CreateDispositionSetRequest("TestSet", "Opis zestawu"), TENANT_ID);

            assertThat(dto.id()).isEqualTo(SET_ID);
            assertThat(dto.name()).isEqualTo("TestSet");
            assertThat(dto.description()).isEqualTo("Opis zestawu");
            assertThat(dto.itemCount()).isZero();
            verify(setRepo).insert(any(DispositionSet.class));
        }
    }

    // =========================================================================
    // applyToCampaign
    // =========================================================================

    @Nested
    @DisplayName("applyToCampaign")
    class ApplyToCampaign {

        @Test
        @DisplayName("pusty zestaw → rzuca ResourceNotFoundException")
        void applyToCampaign_emptySet_throwsNotFoundException() {
            when(itemRepo.findBySetId(SET_ID, TENANT_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.applyToCampaign(SET_ID, CAMPAIGN_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(SET_ID.toString());

            verify(customDispositionRepository, never()).insert(any());
        }

        @Test
        @DisplayName("zestaw z 2 elementami → kopiuje wszystkie i zwraca copied=2, skipped=0")
        void applyToCampaign_success_copiesAllItems() {
            List<DispositionSetItem> items = List.of(
                    buildItem(ITEM_ID_1, SET_ID, "SALE", "Sprzedaż", "positive", 1),
                    buildItem(ITEM_ID_2, SET_ID, "NO_INTEREST", "Brak zainteresowania", "negative", 2));

            when(itemRepo.findBySetId(SET_ID, TENANT_ID)).thenReturn(items);
            when(customDispositionRepository.insert(any(CustomDisposition.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ApplySetResponse response = service.applyToCampaign(SET_ID, CAMPAIGN_ID, TENANT_ID);

            assertThat(response.copied()).isEqualTo(2);
            assertThat(response.skipped()).isZero();
            assertThat(response.message()).contains("Skopiowano 2");
            verify(customDispositionRepository, times(2)).insert(any(CustomDisposition.class));
        }

        @Test
        @DisplayName("duplikat kodu przy insercie → pomija element i liczy w skipped")
        void applyToCampaign_duplicateCode_skipsAndCounts() {
            List<DispositionSetItem> items = List.of(
                    buildItem(ITEM_ID_1, SET_ID, "SALE", "Sprzedaż", "positive", 1),
                    buildItem(ITEM_ID_2, SET_ID, "CALLBACK", "Oddzwonienie", "warning", 2));

            when(itemRepo.findBySetId(SET_ID, TENANT_ID)).thenReturn(items);
            // Pierwszy insert OK, drugi rzuca wyjątek (duplikat UNIQUE constraint)
            when(customDispositionRepository.insert(any(CustomDisposition.class)))
                    .thenReturn(buildCustomDisposition("SALE", CAMPAIGN_ID))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

            ApplySetResponse response = service.applyToCampaign(SET_ID, CAMPAIGN_ID, TENANT_ID);

            assertThat(response.copied()).isEqualTo(1);
            assertThat(response.skipped()).isEqualTo(1);
            assertThat(response.message()).contains("pominiętych");
        }
    }

    // =========================================================================
    // applyToQueue
    // =========================================================================

    @Nested
    @DisplayName("applyToQueue")
    class ApplyToQueue {

        @Test
        @DisplayName("zestaw z 2 elementami → kopiuje wszystkie do kolejki i zwraca copied=2, skipped=0")
        void applyToQueue_success_copiesAllItems() {
            List<DispositionSetItem> items = List.of(
                    buildItem(ITEM_ID_1, SET_ID, "SALE", "Sprzedaż", "positive", 1),
                    buildItem(ITEM_ID_2, SET_ID, "NO_INTEREST", "Brak zainteresowania", "negative", 2));

            when(itemRepo.findBySetId(SET_ID, TENANT_ID)).thenReturn(items);
            when(customDispositionRepository.insert(any(CustomDisposition.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ApplySetResponse response = service.applyToQueue(SET_ID, QUEUE_ID, TENANT_ID);

            assertThat(response.copied()).isEqualTo(2);
            assertThat(response.skipped()).isZero();
            assertThat(response.message()).contains("Skopiowano 2");
            // Weryfikacja że queueId jest ustawione (nie campaignId)
            verify(customDispositionRepository, times(2)).insert(
                    argThat(cd -> QUEUE_ID.equals(cd.getQueueId()) && cd.getCampaignId() == null));
        }
    }

    // =========================================================================
    // Builders pomocnicze
    // =========================================================================

    private DispositionSet buildSet(UUID id, String name, String description) {
        DispositionSet s = new DispositionSet();
        s.setId(id);
        s.setTenantId(TENANT_ID);
        s.setName(name);
        s.setDescription(description);
        s.setCreatedAt(Instant.now());
        return s;
    }

    private DispositionSetItem buildItem(UUID id, UUID setId, String code, String label, String tone, int ordinal) {
        DispositionSetItem item = new DispositionSetItem();
        item.setId(id);
        item.setSetId(setId);
        item.setTenantId(TENANT_ID);
        item.setDispositionCode(code);
        item.setLabel(label);
        item.setTone(tone);
        item.setOrdinal(ordinal);
        return item;
    }

    private CustomDisposition buildCustomDisposition(String code, UUID campaignId) {
        CustomDisposition cd = new CustomDisposition();
        cd.setId(UUID.randomUUID());
        cd.setTenantId(TENANT_ID);
        cd.setCampaignId(campaignId);
        cd.setDispositionCode(code);
        cd.setLabel("Label");
        cd.setTone("positive");
        cd.setOrdinal(1);
        cd.setActive(true);
        return cd;
    }

    // Matcher pomocniczy dla weryfikacji właściwości CustomDisposition
    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.Mockito.argThat(matcher);
    }
}
