package com.contactcenter.api.email;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.email.dto.*;
import com.contactcenter.domain.model.EmailTemplate;
import com.contactcenter.domain.email.EmailTemplateService;
import com.contactcenter.domain.email.EmailTemplateService.RenderedEmailTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API dla szablonów odpowiedzi email (BE-016).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET    /api/email-templates              – lista szablonów (paginacja)</li>
 *   <li>GET    /api/email-templates/{id}         – szczegóły szablonu</li>
 *   <li>POST   /api/email-templates              – tworzenie szablonu</li>
 *   <li>PATCH  /api/email-templates/{id}         – częściowa aktualizacja</li>
 *   <li>DELETE /api/email-templates/{id}         – soft delete</li>
 *   <li>POST   /api/email-templates/{id}/preview – podgląd wyrenderowanego szablonu</li>
 * </ul>
 *
 * <p>Dostęp do odczytu (lista, szczegóły, podgląd): AGENT, SUPERVISOR, ADMIN.
 * Zarządzanie szablonami (tworzenie, edycja, usuwanie): SUPERVISOR, ADMIN.
 */
@Slf4j
@RestController
@RequestMapping("/api/email-templates")
@RequiredArgsConstructor
@Tag(name = "Email Templates", description = "Zarządzanie szablonami odpowiedzi email")
public class EmailTemplateController {

    private final EmailTemplateService emailTemplateService;

    // =========================================================================
    // Lista
    // =========================================================================

    /**
     * Paginowana lista aktywnych szablonów email dla bieżącego tenanta.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Lista szablonów email",
               description = "Zwraca paginowaną listę aktywnych szablonów email dla bieżącego tenanta")
    public ResponseEntity<PagedResponse<EmailTemplateResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<EmailTemplate> templatesPage = emailTemplateService.list(pageable);

        Page<EmailTemplateResponse> mapped = templatesPage.map(EmailTemplateResponse::from);
        return ResponseEntity.ok(PagedResponse.from(mapped));
    }

    // =========================================================================
    // Szczegóły
    // =========================================================================

    /**
     * Szczegóły szablonu email.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Szczegóły szablonu email")
    public ResponseEntity<EmailTemplateResponse> getById(@PathVariable UUID id) {
        EmailTemplate template = emailTemplateService.getById(id);
        return ResponseEntity.ok(EmailTemplateResponse.from(template));
    }

    // =========================================================================
    // Tworzenie
    // =========================================================================

    /**
     * Tworzenie nowego szablonu email.
     *
     * <p>Zwraca HTTP 201 Created z URL zasobu w nagłówku Location.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Utwórz szablon email",
               description = "Tworzy nowy szablon email; zwraca 409 gdy nazwa jest już zajęta")
    public ResponseEntity<EmailTemplateResponse> create(
            @Valid @RequestBody CreateEmailTemplateRequest request) {

        EmailTemplate created = emailTemplateService.create(request);

        log.info("[EmailTemplateController] Utworzono szablon: id={}", created.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EmailTemplateResponse.from(created));
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

    /**
     * Częściowa aktualizacja szablonu email (PATCH semantics).
     *
     * <p>Pola null w ciele żądania są ignorowane.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Aktualizuj szablon email",
               description = "Częściowa aktualizacja szablonu; pola null są ignorowane")
    public ResponseEntity<EmailTemplateResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEmailTemplateRequest request) {

        EmailTemplate updated = emailTemplateService.update(id, request);

        log.info("[EmailTemplateController] Zaktualizowano szablon: id={}", id);

        return ResponseEntity.ok(EmailTemplateResponse.from(updated));
    }

    // =========================================================================
    // Usunięcie
    // =========================================================================

    /**
     * Soft delete szablonu email.
     *
     * <p>Zwraca HTTP 204 No Content po pomyślnym usunięciu.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Usuń szablon email",
               description = "Soft delete szablonu; wiersz zostaje w bazie z is_deleted=true")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        emailTemplateService.delete(id);

        log.info("[EmailTemplateController] Usunięto szablon: id={}", id);

        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Podgląd wyrenderowanego szablonu
    // =========================================================================

    /**
     * Podgląd szablonu email po podstawieniu zmiennych Mustache.
     *
     * <p>Zwraca wyrenderowany temat i treść HTML. Nie wysyła wiadomości.
     * Zwraca HTTP 422 gdy brakuje wymaganych zmiennych.
     */
    @PostMapping("/{id}/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Podgląd wyrenderowanego szablonu",
               description = "Renderuje szablon z podanymi zmiennymi; zwraca 422 gdy brakuje wymaganych zmiennych")
    public ResponseEntity<PreviewResponse> preview(
            @PathVariable UUID id,
            @RequestBody PreviewRequest request) {

        RenderedEmailTemplate rendered = emailTemplateService.render(id, request.variables());

        return ResponseEntity.ok(new PreviewResponse(rendered.subject(), rendered.bodyHtml()));
    }
}
