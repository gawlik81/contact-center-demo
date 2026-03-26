package com.contactcenter.domain.email;

import com.contactcenter.domain.model.EmailTemplate;
import com.contactcenter.domain.repository.EmailTemplateRepository;
import com.contactcenter.api.email.dto.CreateEmailTemplateRequest;
import com.contactcenter.api.email.dto.UpdateEmailTemplateRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Testy jednostkowe dla {@link EmailTemplateService}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Tworzenie szablonu (sukces i duplikat 409)</li>
 *   <li>Aktualizację szablonu (partial update, duplikat nazwy)</li>
 *   <li>Soft delete szablonu</li>
 *   <li>Pobieranie szablonu (success i not found)</li>
 *   <li>Renderowanie szablonu z walidacją brakujących zmiennych</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailTemplateService – zarządzanie szablonami email")
class EmailTemplateServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEMPLATE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private EmailTemplateRepository emailTemplateRepository;

    @Mock
    private MustacheTemplateEngine templateEngine;

    private EmailTemplateService service;

    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        service = new EmailTemplateService(emailTemplateRepository, templateEngine);

        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    // =========================================================================
    // Tworzenie szablonu
    // =========================================================================

    @Nested
    @DisplayName("create() – tworzenie szablonu")
    class Create {

        @Test
        @DisplayName("Powinien utworzyć szablon gdy nazwa jest unikalna")
        void shouldCreateTemplate_whenNameIsUnique() {
            // given
            CreateEmailTemplateRequest request = new CreateEmailTemplateRequest(
                    "Szablon powitalny",
                    "Witaj {{customerName}}!",
                    "<p>Witaj {{customerName}}, jak możemy pomóc?</p>",
                    List.of("customerName")
            );

            when(emailTemplateRepository.existsByNameAndTenantIdAndIsActiveTrue("Szablon powitalny", TENANT_ID))
                    .thenReturn(false);

            EmailTemplate savedTemplate = EmailTemplate.builder()
                    .id(TEMPLATE_ID)
                    .tenantId(TENANT_ID)
                    .name("Szablon powitalny")
                    .subjectTemplate("Witaj {{customerName}}!")
                    .bodyHtml("<p>Witaj {{customerName}}, jak możemy pomóc?</p>")
                    .variables(List.of("customerName"))
                    .build();

            when(emailTemplateRepository.save(any(EmailTemplate.class))).thenReturn(savedTemplate);

            // when
            EmailTemplate result = service.create(request);

            // then
            assertThat(result.getId()).isEqualTo(TEMPLATE_ID);
            assertThat(result.getName()).isEqualTo("Szablon powitalny");
            assertThat(result.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(result.getVariables()).containsExactly("customerName");

            // Weryfikacja że save był wywołany z właściwymi danymi
            ArgumentCaptor<EmailTemplate> captor = ArgumentCaptor.forClass(EmailTemplate.class);
            verify(emailTemplateRepository).save(captor.capture());
            EmailTemplate captured = captor.getValue();
            assertThat(captured.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(captured.getName()).isEqualTo("Szablon powitalny");
            assertThat(captured.isActive()).isTrue();
        }

        @Test
        @DisplayName("Powinien rzucić ConflictException gdy nazwa jest już zajęta")
        void shouldThrowConflict_whenNameIsDuplicate() {
            // given
            CreateEmailTemplateRequest request = new CreateEmailTemplateRequest(
                    "Istniejący szablon",
                    "Temat",
                    "<p>Treść</p>",
                    List.of()
            );

            when(emailTemplateRepository.existsByNameAndTenantIdAndIsActiveTrue("Istniejący szablon", TENANT_ID))
                    .thenReturn(true);

            // when / then
            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Istniejący szablon");

            verify(emailTemplateRepository, never()).save(any());
        }
    }

    // =========================================================================
    // Pobieranie szablonu
    // =========================================================================

    @Nested
    @DisplayName("getById() – pobieranie szablonu")
    class GetById {

        @Test
        @DisplayName("Powinien zwrócić szablon gdy istnieje")
        void shouldReturnTemplate_whenExists() {
            // given
            EmailTemplate template = buildTemplate();
            when(emailTemplateRepository.findByIdAndTenantIdAndIsActiveTrue(TEMPLATE_ID, TENANT_ID))
                    .thenReturn(Optional.of(template));

            // when
            EmailTemplate result = service.getById(TEMPLATE_ID);

            // then
            assertThat(result.getId()).isEqualTo(TEMPLATE_ID);
            assertThat(result.getTenantId()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("Powinien rzucić ResourceNotFoundException gdy szablon nie istnieje")
        void shouldThrowNotFound_whenTemplateNotFound() {
            // given
            when(emailTemplateRepository.findByIdAndTenantIdAndIsActiveTrue(TEMPLATE_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> service.getById(TEMPLATE_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(TEMPLATE_ID.toString());
        }
    }

    // =========================================================================
    // Soft delete
    // =========================================================================

    @Nested
    @DisplayName("delete() – soft delete szablonu")
    class Delete {

        @Test
        @DisplayName("Powinien ustawić is_deleted=true (soft delete)")
        void shouldSetIsDeletedTrue() {
            // given
            EmailTemplate template = buildTemplate();
            assertThat(template.isActive()).isTrue(); // buildTemplate() zwraca aktywny szablon

            when(emailTemplateRepository.findByIdAndTenantIdAndIsActiveTrue(TEMPLATE_ID, TENANT_ID))
                    .thenReturn(Optional.of(template));
            when(emailTemplateRepository.save(any(EmailTemplate.class))).thenReturn(template);

            // when
            service.delete(TEMPLATE_ID);

            // then
            ArgumentCaptor<EmailTemplate> captor = ArgumentCaptor.forClass(EmailTemplate.class);
            verify(emailTemplateRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isFalse(); // po soft delete: isActive=false
        }

        @Test
        @DisplayName("Powinien rzucić ResourceNotFoundException gdy szablon nie istnieje")
        void shouldThrowNotFound_whenDeleting_notExisting() {
            // given
            when(emailTemplateRepository.findByIdAndTenantIdAndIsActiveTrue(TEMPLATE_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> service.delete(TEMPLATE_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(emailTemplateRepository, never()).save(any());
        }
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

    @Nested
    @DisplayName("update() – częściowa aktualizacja szablonu")
    class Update {

        @Test
        @DisplayName("Powinien zaktualizować podane pola, ignorując null")
        void shouldUpdateOnlyProvidedFields() {
            // given
            EmailTemplate template = buildTemplate();

            when(emailTemplateRepository.findByIdAndTenantIdAndIsActiveTrue(TEMPLATE_ID, TENANT_ID))
                    .thenReturn(Optional.of(template));
            when(emailTemplateRepository.save(any(EmailTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateEmailTemplateRequest request = new UpdateEmailTemplateRequest(
                    null, // name – bez zmiany
                    "Nowy temat {{customerName}}",
                    null, // bodyHtml – bez zmiany
                    null  // variables – bez zmiany
            );

            // when
            EmailTemplate updated = service.update(TEMPLATE_ID, request);

            // then
            assertThat(updated.getSubjectTemplate()).isEqualTo("Nowy temat {{customerName}}");
            assertThat(updated.getName()).isEqualTo("Szablon powitalny"); // bez zmiany
        }

        @Test
        @DisplayName("Powinien rzucić ConflictException gdy nowa nazwa jest zajęta przez inny szablon")
        void shouldThrowConflict_whenNewNameIsTakenByOtherTemplate() {
            // given
            EmailTemplate template = buildTemplate();

            when(emailTemplateRepository.findByIdAndTenantIdAndIsActiveTrue(TEMPLATE_ID, TENANT_ID))
                    .thenReturn(Optional.of(template));
            when(emailTemplateRepository.existsByNameAndTenantIdAndIsActiveTrueAndIdNot(
                    "Inna nazwa", TENANT_ID, TEMPLATE_ID))
                    .thenReturn(true);

            UpdateEmailTemplateRequest request = new UpdateEmailTemplateRequest(
                    "Inna nazwa", null, null, null);

            // when / then
            assertThatThrownBy(() -> service.update(TEMPLATE_ID, request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Inna nazwa");

            verify(emailTemplateRepository, never()).save(any());
        }
    }

    // =========================================================================
    // Renderowanie
    // =========================================================================

    @Nested
    @DisplayName("render() – renderowanie szablonu Mustache")
    class Render {

        @Test
        @DisplayName("Powinien wyrenderować szablon gdy wszystkie zmienne są dostarczone")
        void shouldRender_whenAllVariablesProvided() {
            // given
            EmailTemplate template = buildTemplate(); // variables = ["customerName"]

            doReturn(Optional.of(template)).when(emailTemplateRepository)
                    .findByIdAndTenantIdAndIsActiveTrue(TEMPLATE_ID, TENANT_ID);
            when(templateEngine.render(eq("Witaj {{customerName}}!"), anyMap()))
                    .thenReturn("Witaj Jan!");
            when(templateEngine.render(eq("<p>Witaj {{customerName}}, jak możemy pomóc?</p>"), anyMap()))
                    .thenReturn("<p>Witaj Jan, jak możemy pomóc?</p>");

            Map<String, Object> variables = Map.of("customerName", "Jan");

            // when
            EmailTemplateService.RenderedEmailTemplate result = service.render(TEMPLATE_ID, variables);

            // then
            assertThat(result.subject()).isEqualTo("Witaj Jan!");
            assertThat(result.bodyHtml()).isEqualTo("<p>Witaj Jan, jak możemy pomóc?</p>");
        }

        @Test
        @DisplayName("Powinien rzucić TemplateRenderException gdy brakuje wymaganej zmiennej")
        void shouldThrowTemplateRenderException_whenVariableMissing() {
            // given
            EmailTemplate template = buildTemplate(); // variables = ["customerName"]

            when(emailTemplateRepository.findByIdAndTenantIdAndIsActiveTrue(TEMPLATE_ID, TENANT_ID))
                    .thenReturn(Optional.of(template));

            Map<String, Object> variables = Map.of(); // brak "customerName"

            // when / then
            assertThatThrownBy(() -> service.render(TEMPLATE_ID, variables))
                    .isInstanceOf(TemplateRenderException.class)
                    .satisfies(ex -> {
                        TemplateRenderException tre = (TemplateRenderException) ex;
                        assertThat(tre.getMissingVariables()).containsExactly("customerName");
                    });

            verify(templateEngine, never()).render(anyString(), anyMap());
        }

        @Test
        @DisplayName("Powinien wyrenderować gdy szablon nie ma zadeklarowanych zmiennych")
        void shouldRender_whenTemplateHasNoVariables() {
            // given
            EmailTemplate template = EmailTemplate.builder()
                    .id(TEMPLATE_ID)
                    .tenantId(TENANT_ID)
                    .name("Szablon bez zmiennych")
                    .subjectTemplate("Stały temat")
                    .bodyHtml("<p>Stała treść</p>")
                    .variables(List.of()) // brak wymaganych zmiennych
                    .build();

            doReturn(Optional.of(template)).when(emailTemplateRepository)
                    .findByIdAndTenantIdAndIsActiveTrue(TEMPLATE_ID, TENANT_ID);
            when(templateEngine.render(eq("Stały temat"), anyMap())).thenReturn("Stały temat");
            when(templateEngine.render(eq("<p>Stała treść</p>"), anyMap())).thenReturn("<p>Stała treść</p>");

            // when
            EmailTemplateService.RenderedEmailTemplate result = service.render(TEMPLATE_ID, Map.of());

            // then
            assertThat(result.subject()).isEqualTo("Stały temat");
            assertThat(result.bodyHtml()).isEqualTo("<p>Stała treść</p>");
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private EmailTemplate buildTemplate() {
        return EmailTemplate.builder()
                .id(TEMPLATE_ID)
                .tenantId(TENANT_ID)
                .name("Szablon powitalny")
                .subjectTemplate("Witaj {{customerName}}!")
                .bodyHtml("<p>Witaj {{customerName}}, jak możemy pomóc?</p>")
                .variables(List.of("customerName"))
                .createdAt(Instant.now())
                .build();
    }
}
