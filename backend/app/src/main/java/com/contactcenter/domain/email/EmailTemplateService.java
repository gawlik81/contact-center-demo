package com.contactcenter.domain.email;

import com.contactcenter.api.email.dto.CreateEmailTemplateRequest;
import com.contactcenter.api.email.dto.UpdateEmailTemplateRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
public interface EmailTemplateService {

    /**
     * Lista aktywnych szablonów dla bieżącego tenanta (paginacja).
     *
     * @param pageable parametry paginacji
     * @return strona szablonów
     */
    Page<EmailTemplate> list(Pageable pageable);

    /**
     * Pobiera szablon po ID.
     *
     * @param id UUID szablonu
     * @return encja szablonu
     * @throws ResourceNotFoundException gdy szablon nie istnieje lub należy do innego tenanta
     */
    EmailTemplate getById(UUID id);

    /**
     * Tworzy nowy szablon email.
     *
     * <p>Waliduje unikalność nazwy w ramach tenanta przed zapisem.
     *
     * @param dto dane nowego szablonu
     * @return utworzony szablon
     * @throws ConflictException gdy szablon o tej nazwie już istnieje
     */
    EmailTemplate create(CreateEmailTemplateRequest dto);

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
    EmailTemplate update(UUID id, UpdateEmailTemplateRequest dto);

    /**
     * Soft delete szablonu – ustawia {@code is_active = false} (schemat V010).
     *
     * <p>Nigdy nie usuwa fizycznie wiersza z bazy danych.
     *
     * @param id UUID szablonu
     * @throws ResourceNotFoundException gdy szablon nie istnieje
     */
    void delete(UUID id);

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
    RenderedEmailTemplate render(UUID templateId, Map<String, Object> variables);
}
