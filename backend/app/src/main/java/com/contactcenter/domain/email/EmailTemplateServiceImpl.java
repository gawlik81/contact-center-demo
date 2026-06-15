package com.contactcenter.domain.email;

import com.contactcenter.api.email.dto.CreateEmailTemplateRequest;
import com.contactcenter.api.email.dto.UpdateEmailTemplateRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class EmailTemplateServiceImpl implements EmailTemplateService {

    private final EmailTemplateRepository emailTemplateRepository;
    private final MustacheTemplateEngine templateEngine;

    // =========================================================================
    // Odczyt
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public Page<EmailTemplate> list(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        return emailTemplateRepository.findAllByTenantIdAndIsActiveTrue(tenantId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public EmailTemplate getById(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        return emailTemplateRepository.findByIdAndTenantIdAndIsActiveTrue(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Szablon email nie istnieje: " + id));
    }

    // =========================================================================
    // Tworzenie
    // =========================================================================

    @Override
    @Transactional
    @Audited(action = "EMAIL_TEMPLATE_CREATED", entityType = "EMAIL_TEMPLATE")
    public EmailTemplate create(CreateEmailTemplateRequest dto) {
        UUID tenantId = TenantContext.getTenantId();

        // Walidacja unikalności nazwy
        if (emailTemplateRepository.existsByNameAndTenantIdAndIsActiveTrue(dto.name(), tenantId)) {
            throw new ConflictException("Szablon email o nazwie '" + dto.name() + "' już istnieje");
        }

        EmailTemplate template = EmailTemplate.builder()
                .tenantId(tenantId)
                .name(dto.name())
                .subjectTemplate(dto.subjectTemplate())
                .bodyHtml(dto.bodyHtml())
                .variables(dto.variables())
                .build();

        EmailTemplate saved = emailTemplateRepository.save(template);

        log.info("[EmailTemplateService] Utworzono szablon: id={}, name='{}', tenant={}",
                saved.getId(), saved.getName(), tenantId);

        return saved;
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

    @Override
    @Transactional
    @Audited(action = "EMAIL_TEMPLATE_UPDATED", entityType = "EMAIL_TEMPLATE",
             captureOldValue = true, fetchOldValueMethod = "getById")
    public EmailTemplate update(UUID id, UpdateEmailTemplateRequest dto) {
        EmailTemplate template = getById(id);
        UUID tenantId = template.getTenantId();

        // Walidacja unikalności nowej nazwy (gdy zmieniana)
        if (dto.name() != null && !dto.name().equals(template.getName())) {
            if (emailTemplateRepository.existsByNameAndTenantIdAndIsActiveTrueAndIdNot(
                    dto.name(), tenantId, id)) {
                throw new ConflictException("Szablon email o nazwie '" + dto.name() + "' już istnieje");
            }
            template.setName(dto.name());
        }

        if (dto.subjectTemplate() != null) {
            template.setSubjectTemplate(dto.subjectTemplate());
        }
        if (dto.bodyHtml() != null) {
            template.setBodyHtml(dto.bodyHtml());
        }
        if (dto.variables() != null) {
            template.setVariables(dto.variables());
        }

        EmailTemplate updated = emailTemplateRepository.save(template);

        log.info("[EmailTemplateService] Zaktualizowano szablon: id={}, tenant={}", id, tenantId);

        return updated;
    }

    // =========================================================================
    // Usunięcie (soft delete)
    // =========================================================================

    @Override
    @Transactional
    @Audited(action = "EMAIL_TEMPLATE_DELETED", entityType = "EMAIL_TEMPLATE",
             captureOldValue = true, fetchOldValueMethod = "getById")
    public void delete(UUID id) {
        EmailTemplate template = getById(id);

        template.setActive(false);
        emailTemplateRepository.save(template);

        log.info("[EmailTemplateService] Usunięto (soft delete) szablon: id={}, tenant={}",
                id, template.getTenantId());
    }

    // =========================================================================
    // Renderowanie
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public RenderedEmailTemplate render(UUID templateId, Map<String, Object> variables) {
        EmailTemplate template = getById(templateId);

        // Walidacja: czy wszystkie zadeklarowane zmienne szablonu są dostarczone
        // Predefiniowane zmienne (PredefinedTemplateVariable) są auto-uzupełniane – nie liczymy ich jako brakujące
        List<String> declaredVariables = template.getVariables();
        if (declaredVariables != null && !declaredVariables.isEmpty()) {
            List<String> missing = declaredVariables.stream()
                    .filter(varName -> !variables.containsKey(varName)
                            && !PredefinedTemplateVariable.BY_KEY.containsKey(varName))
                    .toList();

            if (!missing.isEmpty()) {
                log.warn("[EmailTemplateService] Brakujące zmienne szablonu: templateId={}, missing={}",
                        templateId, missing);
                throw new TemplateRenderException(missing);
            }
        }

        // Renderuj temat i treść
        String renderedSubject = templateEngine.render(template.getSubjectTemplate(), variables);
        String renderedBodyHtml = templateEngine.render(template.getBodyHtml(), variables);

        log.debug("[EmailTemplateService] Wyrenderowano szablon: id={}, subject='{}'",
                templateId, renderedSubject);

        return new RenderedEmailTemplate(renderedSubject, renderedBodyHtml);
    }
}
