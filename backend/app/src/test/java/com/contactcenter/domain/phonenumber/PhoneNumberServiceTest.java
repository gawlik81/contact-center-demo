package com.contactcenter.domain.phonenumber;

import com.contactcenter.api.phonenumber.dto.CreatePhoneNumberRequest;
import com.contactcenter.api.phonenumber.dto.PhoneNumberResponse;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link PhoneNumberService}.
 *
 * <p>Weryfikuje logikę biznesową BE-033:
 * <ul>
 *   <li>tworzenie poprawnego numeru E.164</li>
 *   <li>walidacja E.164 – rzuca wyjątek dla niepoprawnego formatu</li>
 *   <li>duplikat numeru w tenantcie – rzuca ConflictException (HTTP 409)</li>
 *   <li>soft delete z aktywnymi regułami routingu – rzuca ConflictException (HTTP 409)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PhoneNumberService – CRUD numerów telefonu")
class PhoneNumberServiceTest {

    private static final UUID TENANT_ID       = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PHONE_NUMBER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String VALID_NUMBER  = "+48221234567";

    @Mock private PhoneNumberRepository phoneNumberRepository;
    @Mock private PhoneRoutingRuleRepository phoneRoutingRuleRepository;

    @InjectMocks
    private PhoneNumberServiceImpl phoneNumberService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserRole("SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Tworzenie numeru
    // =========================================================================

    @Nested
    @DisplayName("createPhoneNumber")
    class CreatePhoneNumber {

        @Test
        @DisplayName("poprawny numer E.164 – zapisuje i zwraca PhoneNumberResponse")
        void createPhoneNumber_validE164_savesAndReturnsResponse() {
            // given
            CreatePhoneNumberRequest request = new CreatePhoneNumberRequest(
                    VALID_NUMBER, "Linia główna", true);

            PhoneNumber saved = buildPhoneNumber(PHONE_NUMBER_ID, VALID_NUMBER);

            when(phoneNumberRepository.existsByTenantIdAndNumber(TENANT_ID, VALID_NUMBER))
                    .thenReturn(false);
            when(phoneNumberRepository.save(any(PhoneNumber.class))).thenReturn(saved);

            // when
            PhoneNumberResponse response = phoneNumberService.createPhoneNumber(TENANT_ID, request);

            // then
            assertThat(response).isNotNull();
            assertThat(response.phoneNumberId()).isEqualTo(PHONE_NUMBER_ID);
            assertThat(response.number()).isEqualTo(VALID_NUMBER);
            assertThat(response.displayName()).isEqualTo("Linia główna");
            assertThat(response.isActive()).isTrue();

            verify(phoneNumberRepository).save(any(PhoneNumber.class));
        }

        @Test
        @DisplayName("numer bez displayName i isActive – domyślnie isActive=true")
        void createPhoneNumber_nullOptionals_defaultsToActive() {
            // given
            CreatePhoneNumberRequest request = new CreatePhoneNumberRequest(
                    VALID_NUMBER, null, null);

            PhoneNumber saved = buildPhoneNumber(PHONE_NUMBER_ID, VALID_NUMBER);

            when(phoneNumberRepository.existsByTenantIdAndNumber(TENANT_ID, VALID_NUMBER))
                    .thenReturn(false);
            when(phoneNumberRepository.save(any(PhoneNumber.class))).thenReturn(saved);

            // when
            PhoneNumberResponse response = phoneNumberService.createPhoneNumber(TENANT_ID, request);

            // then
            assertThat(response).isNotNull();
            verify(phoneNumberRepository).save(argThat(pn -> pn.isActive()));
        }
    }

    // =========================================================================
    // Walidacja E.164
    // =========================================================================

    @Nested
    @DisplayName("walidacja E.164")
    class E164Validation {

        @Test
        @DisplayName("numer bez + – rzuca IllegalArgumentException")
        void createPhoneNumber_missingPlus_throwsException() {
            // given
            CreatePhoneNumberRequest request = new CreatePhoneNumberRequest(
                    "48221234567", "Test", true);

            // when / then
            assertThatThrownBy(() -> phoneNumberService.createPhoneNumber(TENANT_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("E.164");

            verifyNoInteractions(phoneNumberRepository);
        }

        @Test
        @DisplayName("numer za krótki – rzuca IllegalArgumentException")
        void createPhoneNumber_tooShort_throwsException() {
            // given – tylko 5 cyfr po +1 = za krótkie (min 7)
            CreatePhoneNumberRequest request = new CreatePhoneNumberRequest(
                    "+112345", "Test", true);

            // when / then
            assertThatThrownBy(() -> phoneNumberService.createPhoneNumber(TENANT_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("E.164");
        }

        @Test
        @DisplayName("numer null – rzuca IllegalArgumentException")
        void createPhoneNumber_null_throwsException() {
            // given
            CreatePhoneNumberRequest request = new CreatePhoneNumberRequest(
                    null, "Test", true);

            // when / then
            assertThatThrownBy(() -> phoneNumberService.createPhoneNumber(TENANT_ID, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("numer z 0 na drugiej pozycji – rzuca IllegalArgumentException")
        void createPhoneNumber_leadingZeroAfterPlus_throwsException() {
            // E.164: po + następuje cyfra 1-9 (nie 0)
            CreatePhoneNumberRequest request = new CreatePhoneNumberRequest(
                    "+0123456789", "Test", true);

            assertThatThrownBy(() -> phoneNumberService.createPhoneNumber(TENANT_ID, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // Duplikat – HTTP 409
    // =========================================================================

    @Nested
    @DisplayName("duplikaty")
    class Duplicates {

        @Test
        @DisplayName("numer już istnieje w tenantcie – rzuca ConflictException")
        void createPhoneNumber_duplicate_throwsConflictException() {
            // given
            CreatePhoneNumberRequest request = new CreatePhoneNumberRequest(
                    VALID_NUMBER, "Duplikat", true);

            when(phoneNumberRepository.existsByTenantIdAndNumber(TENANT_ID, VALID_NUMBER))
                    .thenReturn(true);

            // when / then
            assertThatThrownBy(() -> phoneNumberService.createPhoneNumber(TENANT_ID, request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(VALID_NUMBER);

            verify(phoneNumberRepository, never()).save(any());
        }
    }

    // =========================================================================
    // Soft delete z aktywnymi regułami – HTTP 409
    // =========================================================================

    @Nested
    @DisplayName("deletePhoneNumber")
    class DeletePhoneNumber {

        @Test
        @DisplayName("brak aktywnych reguł – ustawia is_deleted=true i zapisuje")
        void deletePhoneNumber_noActiveRules_softDeletes() {
            // given
            PhoneNumber phoneNumber = buildPhoneNumber(PHONE_NUMBER_ID, VALID_NUMBER);

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(phoneNumber));
            when(phoneRoutingRuleRepository.existsActiveRulesByPhoneNumberId(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(false);
            when(phoneNumberRepository.save(any(PhoneNumber.class))).thenReturn(phoneNumber);

            // when
            phoneNumberService.deletePhoneNumber(TENANT_ID, PHONE_NUMBER_ID);

            // then
            verify(phoneNumberRepository).save(argThat(PhoneNumber::isDeleted));
        }

        @Test
        @DisplayName("istnieją aktywne reguły routingu – rzuca ConflictException")
        void deletePhoneNumber_activeRoutingRules_throwsConflictException() {
            // given
            PhoneNumber phoneNumber = buildPhoneNumber(PHONE_NUMBER_ID, VALID_NUMBER);

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(phoneNumber));
            when(phoneRoutingRuleRepository.existsActiveRulesByPhoneNumberId(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(true);

            // when / then
            assertThatThrownBy(() -> phoneNumberService.deletePhoneNumber(TENANT_ID, PHONE_NUMBER_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("aktywne reguły routingu");

            verify(phoneNumberRepository, never()).save(any());
        }

        @Test
        @DisplayName("numer nie istnieje – rzuca ResourceNotFoundException")
        void deletePhoneNumber_notFound_throwsResourceNotFoundException() {
            // given
            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> phoneNumberService.deletePhoneNumber(TENANT_ID, PHONE_NUMBER_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(PHONE_NUMBER_ID.toString());
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private PhoneNumber buildPhoneNumber(UUID id, String number) {
        return PhoneNumber.builder()
                .phoneNumberId(id)
                .tenantId(TENANT_ID)
                .number(number)
                .displayName("Linia główna")
                .active(true)
                .deleted(false)
                .createdAt(Instant.now())
                .build();
    }
}
