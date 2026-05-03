package com.contactcenter.domain;

import com.contactcenter.api.user.dto.UserPreferencesDto;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.AppUser.UserRole;
import com.contactcenter.domain.model.AppUser.UserStatus;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link UserPreferencesService}.
 *
 * <p>Weryfikuje: odczyt preferencji, aktualizację języka, zachowanie przy braku użytkownika.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserPreferencesService – odczyt i aktualizacja preferencji użytkownika")
class UserPreferencesServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private AppUserRepository appUserRepository;

    @InjectMocks
    private UserPreferencesService userPreferencesService;

    private AppUser buildUser(String preferredLanguage) {
        return AppUser.builder()
                .id(USER_ID)
                .tenantId(TENANT_ID)
                .email("agent@test.com")
                .passwordHash("$2a$12$hash")
                .role(UserRole.AGENT)
                .status(UserStatus.AVAILABLE)
                .active(true)
                .deleted(false)
                .mfaEnabled(false)
                .passwordResetRequired(false)
                .skills(new ArrayList<>())
                .preferredLanguage(preferredLanguage)
                .version(0L)
                .build();
    }

    // =========================================================================
    // getPreferences
    // =========================================================================

    @Nested
    @DisplayName("getPreferences()")
    class GetPreferences {

        @Test
        @DisplayName("zwraca DTO gdy użytkownik istnieje")
        void returnsDto_whenUserExists() {
            // given
            AppUser user = buildUser("en");
            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));

            // when
            UserPreferencesDto result = userPreferencesService.getPreferences(USER_ID, TENANT_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.preferredLanguage()).isEqualTo("en");
            verify(appUserRepository, times(1))
                    .findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID);
        }

        @Test
        @DisplayName("zwraca domyślny język 'pl' gdy użytkownik ma pl")
        void returnsDefaultPolish_whenUserHasPolish() {
            // given
            AppUser user = buildUser("pl");
            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));

            // when
            UserPreferencesDto result = userPreferencesService.getPreferences(USER_ID, TENANT_ID);

            // then
            assertThat(result.preferredLanguage()).isEqualTo("pl");
        }

        @Test
        @DisplayName("rzuca ResourceNotFoundException gdy użytkownik nie istnieje")
        void throwsResourceNotFoundException_whenUserNotFound() {
            // given
            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> userPreferencesService.getPreferences(USER_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(USER_ID.toString());
            verify(appUserRepository, never()).save(any());
        }
    }

    // =========================================================================
    // updatePreferences
    // =========================================================================

    @Nested
    @DisplayName("updatePreferences()")
    class UpdatePreferences {

        @Test
        @DisplayName("aktualizuje język i zwraca nowe DTO")
        void updatesLanguage_andReturnsDto() {
            // given
            AppUser user = buildUser("pl");
            UserPreferencesDto dto = new UserPreferencesDto("de");

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));
            when(appUserRepository.save(any(AppUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            UserPreferencesDto result = userPreferencesService.updatePreferences(USER_ID, TENANT_ID, dto);

            // then
            assertThat(result.preferredLanguage()).isEqualTo("de");
            verify(appUserRepository, times(1)).save(user);
            assertThat(user.getPreferredLanguage()).isEqualTo("de");
        }

        @Test
        @DisplayName("aktualizuje język na 'en'")
        void updatesLanguage_toEnglish() {
            // given
            AppUser user = buildUser("pl");
            UserPreferencesDto dto = new UserPreferencesDto("en");

            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.of(user));
            when(appUserRepository.save(any(AppUser.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            UserPreferencesDto result = userPreferencesService.updatePreferences(USER_ID, TENANT_ID, dto);

            // then
            assertThat(result.preferredLanguage()).isEqualTo("en");
        }

        @Test
        @DisplayName("rzuca ResourceNotFoundException gdy użytkownik nie istnieje")
        void throwsResourceNotFoundException_whenUserNotFound() {
            // given
            UserPreferencesDto dto = new UserPreferencesDto("en");
            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(USER_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> userPreferencesService.updatePreferences(USER_ID, TENANT_ID, dto))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(USER_ID.toString());
            verify(appUserRepository, never()).save(any());
        }

        @Test
        @DisplayName("walidacja @Pattern odrzuca niepoprawny kod języka")
        void validationPattern_rejectsInvalidLanguageCode() {
            // Weryfikacja, że DTO z niepoprawnym kodem nie przejdzie walidacji Bean Validation.
            // @Pattern na UserPreferencesDto.preferredLanguage wyklucza kody spoza {pl, en, de}.
            // W kontrolerze @Valid wymusza tę walidację – test dokumentuje oczekiwany pattern.
            UserPreferencesDto invalidDto = new UserPreferencesDto("fr");

            // Walidacja przez Jakarta Bean Validation – sprawdź constraint
            jakarta.validation.Validator validator = jakarta.validation.Validation
                    .buildDefaultValidatorFactory()
                    .getValidator();
            var violations = validator.validate(invalidDto);

            assertThat(violations).isNotEmpty();
            assertThat(violations.iterator().next().getMessage())
                    .isEqualTo("Unsupported language code");
        }

        @Test
        @DisplayName("walidacja @Pattern przepuszcza poprawne kody języka")
        void validationPattern_acceptsValidLanguageCodes() {
            jakarta.validation.Validator validator = jakarta.validation.Validation
                    .buildDefaultValidatorFactory()
                    .getValidator();

            for (String lang : new String[]{"pl", "en", "de"}) {
                var violations = validator.validate(new UserPreferencesDto(lang));
                assertThat(violations).as("Kod języka '%s' powinien być poprawny", lang).isEmpty();
            }
        }
    }
}
