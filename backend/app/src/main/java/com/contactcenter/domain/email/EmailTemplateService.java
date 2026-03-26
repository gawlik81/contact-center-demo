package com.contactcenter.domain.email;

import com.contactcenter.domain.model.EmailTemplate;
import com.contactcenter.domain.repository.EmailTemplateRepository;
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

/**
 * Serwis domenowy zarządzający szablonami email.
 *
 * <p>Operacje:
 * <ul>
 *   <li>CRUD szablonów per tenant (list, getById, create, update, delete)</li>
 *   <li>Renderowanie szablonu Mustache z podstawieniem zmiennych</li>
 * </ul>
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Każda operacja zapisu wywołuje {@code assertSameTenant} przez repozytorium</li>
 *   <li>Odczyt filtrowany przez RLS PostgreSQL ({@code setTenantContextInDb})</li>
 *   <li>Operacje audytowane przez {@code @Audited}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final EmailTemplateRepository emailTemplateRepository;
    private final MustacheTemplateEngine templateEngine;

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Lista aktywnych szablonów dla bieżącego tenanta (paginacja).
     *
     * @param pageable parametry paginacji
     * @return strona szablonów
     */
    @Transactional(readOnly = true)
    public Page<EmailTemplate> list(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        return emailTemplateRepository.findAllByTenantIdAndIsActiveTrue(tenantId, pageable);
    }

    /**
     * Pobiera szablon po ID.
     *
     * @param id UUID szablonu
     * @return encja szablonu
     * @throws ResourceNotFoundException gdy szablon nie istnieje lub należy do innego tenanta
     */
    @Transactional(readOnly = true)
    public EmailTemplate getById(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        return emailTemplateRepository.findByIdAndTenantIdAndIsActiveTrue(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Szablon email nie istnieje: " + id));
    }

    // =========================================================================
    // Tworzenie
    // =========================================================================

    /**
     * Tworzy nowy szablon email.
     *
     * <p>Waliduje unikalność nazwy w ramach tenanta przed zapisem.
     *
     * @param dto dane nowego szablonu
     * @return utworzony szablon
     * @throws ConflictException gdy szablon o tej nazwie już istnieje
     */
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

    /**
     * Częściowa aktualizacja szablonu (PATCH semantics).
     *
     * <p>Pola null w DTO są ignorowane – nie powodują zmiany wartości.
     * Jeśli zmieniana jest nazwa, waliduje jej unikalność (pomijając bieżący rekord).
     *
     * @param id  UUID szablonu
     * @param dto żądanie aktualizacji
     * @return zaktualizowany szablon
     * @throws ResourceNotFoundException gdy szablon nie istnieje
     * @throws ConflictException         gdy nowa nazwa jest już zajęta
     */
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

    /**
     * Soft delete szablonu – ustawia {@code is_active = false} (schemat V010).
     *
     * <p>Nigdy nie usuwa fizycznie wiersza z bazy danych.
     *
     * @param id UUID szablonu
     * @throws ResourceNotFoundException gdy szablon nie istnieje
     */
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

    /**
     * Renderuje szablon Mustache podstawiając dostarczone zmienne.
     *
     * <p>Waliduje czy wszystkie zmienne zadeklarowane w {@code template.variables}
     * zostały dostarczone w mapie kontekstu. Jeśli nie – rzuca
     * {@link TemplateRenderException} z listą brakujących pól.
     *
     * @param templateId UUID szablonu
     * @param variables  mapa zmiennych do podstawienia
     * @return wyrenderowany temat i treść HTML
     * @throws ResourceNotFoundException gdy szablon nie istnieje
     * @throws TemplateRenderException   gdy brakuje wymaganych zmiennych
     */
    @Transactional(readOnly = true)
    public RenderedEmailTemplate render(UUID templateId, Map<String, Object> variables) {
        EmailTemplate template = getById(templateId);

        // Walidacja: czy wszystkie zadeklarowane zmienne szablonu są dostarczone
        List<String> declaredVariables = template.getVariables();
        if (declaredVariables != null && !declaredVariables.isEmpty()) {
            List<String> missing = declaredVariables.stream()
                    .filter(varName -> !variables.containsKey(varName))
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

    // =========================================================================
    // Rekord wyniku renderowania
    // =========================================================================

    /**
     * Wynik renderowania szablonu Mustache.
     *
     * @param subject  wyrenderowany temat wiadomości
     * @param bodyHtml wyrenderowana treść HTML
     */
    public record RenderedEmailTemplate(String subject, String bodyHtml) {}
}
