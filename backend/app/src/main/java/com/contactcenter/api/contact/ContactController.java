package com.contactcenter.api.contact;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.contact.dto.ContactFilterParams;
import com.contactcenter.api.contact.dto.ContactRecordingUrlResponse;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.CreateContactRequest;
import com.contactcenter.api.contact.dto.DispositionRequest;
import com.contactcenter.api.contact.dto.UpdateContactRequest;
import com.contactcenter.api.dialer.dto.CreateInboundCallbackRequest;
import com.contactcenter.api.dialer.dto.ScheduledCallbackResponse;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.service.ContactService;
import com.contactcenter.security.TenantContext;
import org.springframework.security.access.AccessDeniedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.contactcenter.api.contact.dto.EmailPreviewResponse;
import com.contactcenter.api.contact.dto.RelatedItem;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kontroler REST zarządzający historią kontaktów.
 *
 * <p>Implementuje BE-027: Contact API – zapis i odczyt historii kontaktów.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET    /api/contacts                         – lista kontaktów (paginacja + filtry)</li>
 *   <li>GET    /api/contacts/{id}                    – szczegóły kontaktu</li>
 *   <li>POST   /api/contacts                         – tworzenie kontaktu</li>
 *   <li>PATCH  /api/contacts/{id}                    – aktualizacja kontaktu</li>
 *   <li>PATCH  /api/contacts/{id}/disposition        – ustawienie disposition code</li>
 *   <li>GET    /api/contacts/customer/{customerId}   – historia klienta (FE-019)</li>
 * </ul>
 *
 * <p>Uprawnienia:
 * <ul>
 *   <li>AGENT – tworzy kontakty, aktualizuje własne, ustawia disposition na własnych</li>
 *   <li>SUPERVISOR/ADMIN – pełen CRUD, filtrowanie po dowolnym agencie</li>
 * </ul>
 *
 * <p>TenantId i userId pobierane z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Contact History",
     description = "Zarządzanie historią kontaktów – tworzenie, aktualizacja, paginacja, historia klienta")
public class ContactController {

    private final ContactService contactService;
    private final ContactRepository contactRepository;
    private final ScheduledCallbackRepository scheduledCallbackRepository;

    /**
     * Sprawdza czy zalogowany użytkownik ma rolę AGENT.
     *
     * <p>Wyciągnięte do metody pomocniczej zamiast powielać blok inline –
     * jeden punkt zmiany gdy format roli w TenantContext ulegnie zmianie.
     * Obsługuje oba warianty: "ROLE_AGENT" (Spring Security prefix) i "AGENT" (raw).
     */
    private boolean currentUserIsAgent() {
        String role = TenantContext.getUserRole();
        return "ROLE_AGENT".equals(role) || "AGENT".equals(role);
    }

    /**
     * Parsuje identyfikator kontaktu z path variable.
     *
     * <p>Akceptuje dwa formaty:
     * <ul>
     *   <li>UUID – standardowy identyfikator kontaktu w produkcji.</li>
     *   <li>Dowolny string niebędący UUID (np. {@code mock-1}) – używany w środowisku dev
     *       gdy frontend otrzymuje callId z MockTelephonyAdapter jako contactId przez WebSocket.
     *       W takim przypadku rzuca {@link jakarta.persistence.EntityNotFoundException},
     *       który jest mapowany na HTTP 422 przez {@link GlobalExceptionHandler}.</li>
     * </ul>
     *
     * @param id surowy identyfikator z path variable
     * @return sparsowany UUID
     * @throws jakarta.persistence.EntityNotFoundException gdy id nie jest poprawnym UUID
     *         (np. mock-1 w środowisku dev) – zwrócone jako HTTP 422 z czytelnym komunikatem
     */
    private UUID parseContactId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            log.warn("[ContactController] Niepoprawny format contact ID: '{}' – " +
                     "oczekiwano UUID. W trybie dev contactId może być callId (np. mock-1) " +
                     "zamiast UUID z tabeli contact.", id);
            throw new jakarta.persistence.EntityNotFoundException(
                    "Kontakt nie istnieje: podany identyfikator '" + id + "' nie jest poprawnym UUID. " +
                    "W środowisku dev kontakt musi zostać najpierw utworzony przez POST /api/contacts.");
        }
    }

    // =========================================================================
    // Lista kontaktów z paginacją i filtrami
    // =========================================================================

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Lista kontaktów",
        description = "Zwraca paginowaną listę kontaktów z opcjonalnymi filtrami. " +
                      "AGENT widzi tylko własne kontakty (filtr agentId wymuszony). " +
                      "SUPERVISOR/ADMIN mogą filtrować po dowolnym agencie lub widzieć wszystkie.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista kontaktów"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji parametrów"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<PagedResponse<ContactResponse>> listContacts(
            @Parameter(description = "Filtr po ID agenta (null = wszystkie; wymuszony dla AGENT)")
            @RequestParam(required = false) UUID agentId,

            @Parameter(description = "Filtr po ID klienta")
            @RequestParam(required = false) UUID customerId,

            @Parameter(description = "Filtr po statusie: QUEUED, ACTIVE, ON_HOLD, COMPLETED, ABANDONED, TRANSFERRED")
            @Pattern(regexp = "QUEUED|ACTIVE|ON_HOLD|COMPLETED|ABANDONED|TRANSFERRED",
                     message = "status musi być jednym z: QUEUED, ACTIVE, ON_HOLD, COMPLETED, ABANDONED, TRANSFERRED")
            @RequestParam(required = false) String status,

            @Parameter(description = "Filtr po kanale: PHONE, EMAIL, CHAT, SOCIAL")
            @Pattern(regexp = "PHONE|EMAIL|CHAT|SOCIAL",
                     message = "channel musi być jednym z: PHONE, EMAIL, CHAT, SOCIAL")
            @RequestParam(required = false) String channel,

            @Parameter(description = "Filtr od daty started_at (format YYYY-MM-DD, np. 2026-04-01)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,

            @Parameter(description = "Filtr do daty started_at (format YYYY-MM-DD, włącznie)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,

            @Parameter(description = "Filtr po ID kolejki (UUID)")
            @Size(max = 36, message = "queueId nie może przekraczać 36 znaków")
            @RequestParam(required = false) String queueId,

            @Parameter(description = "Filtr po ID kampanii (UUID)")
            @Size(max = 36, message = "campaignId nie może przekraczać 36 znaków")
            @RequestParam(required = false) String campaignId,

            @Parameter(description = "Filtr po numerze telefonu (częściowe dopasowanie, case-insensitive)")
            @RequestParam(required = false) String remoteAddress,

            @Parameter(description = "Minimalna długość rozmowy w sekundach (włącznie)")
            @Min(value = 0, message = "durationMin nie może być ujemny")
            @RequestParam(required = false) Integer durationMin,

            @Parameter(description = "Maksymalna długość rozmowy w sekundach (włącznie)")
            @Min(value = 0, message = "durationMax nie może być ujemny")
            @RequestParam(required = false) Integer durationMax,

            @Parameter(description = "Numer strony (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Rozmiar strony (max 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        boolean isAgent = currentUserIsAgent();

        ContactFilterParams params = new ContactFilterParams(
                agentId, customerId, status, channel, dateFrom, dateTo,
                queueId, campaignId, remoteAddress, durationMin, durationMax,
                page, size);

        log.debug("[ContactController] Lista kontaktów: tenant={}, isAgent={}", tenantId, isAgent);

        PagedResponse<ContactResponse> response = contactService.listContacts(params, tenantId, userId, isAgent);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Szczegóły kontaktu
    // =========================================================================

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Szczegóły kontaktu",
        description = "Zwraca pełne dane kontaktu. Kontakt musi należeć do tenanta zalogowanego użytkownika.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Dane kontaktu"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kontakt nie istnieje")
        }
    )
    public ResponseEntity<ContactResponse> getContact(
            @Parameter(description = "UUID kontaktu", required = true)
            @PathVariable String id
    ) {
        UUID contactId = parseContactId(id);
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        boolean isAgent = currentUserIsAgent();
        log.debug("[ContactController] Szczegóły kontaktu: contactId={}, tenant={}", contactId, tenantId);
        ContactResponse response = contactService.getContact(contactId, tenantId, userId, isAgent);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Tworzenie kontaktu
    // =========================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Utwórz kontakt",
        description = "Tworzy nowy kontakt (np. przy odbieraniu połączenia przez agenta). " +
                      "Kontakt inicjowany ze statusem QUEUED.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Kontakt utworzony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<ContactResponse> createContact(
            @Valid @RequestBody CreateContactRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[ContactController] Tworzenie kontaktu: tenant={}, channel={}", tenantId, request.channel());

        ContactResponse response = contactService.createContact(request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.contactId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Aktualizacja kontaktu
    // =========================================================================

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Aktualizuj kontakt",
        description = "Aktualizuje kontakt (PATCH semantics). Pola null są ignorowane. " +
                      "AGENT może aktualizować tylko kontakty, w których jest przypisanym agentem. " +
                      "SUPERVISOR/ADMIN mogą aktualizować dowolny kontakt.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Kontakt zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kontakt nie istnieje"),
            @ApiResponse(responseCode = "409", description = "AGENT próbuje zaktualizować cudzy kontakt")
        }
    )
    public ResponseEntity<ContactResponse> updateContact(
            @Parameter(description = "UUID kontaktu", required = true)
            @PathVariable String id,
            @Valid @RequestBody UpdateContactRequest request
    ) {
        UUID contactId = parseContactId(id);
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        boolean isAgent = currentUserIsAgent();

        log.debug("[ContactController] Aktualizacja kontaktu: contactId={}, tenant={}", contactId, tenantId);

        ContactResponse response = contactService.updateContact(contactId, request, tenantId, userId, isAgent);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Ustawianie disposition code
    // =========================================================================

    @PatchMapping("/{id}/disposition")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Ustaw disposition code",
        description = "Ustawia kod wyniku kontaktu po jego zakończeniu (wrap-up). " +
                      "Kontakt musi być w statusie ON_HOLD, COMPLETED lub ABANDONED. " +
                      "AGENT może ustawiać disposition tylko na własnych kontaktach.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Disposition ustawiony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kontakt nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Kontakt jest aktywny lub cudzy (dla AGENT)")
        }
    )
    public ResponseEntity<ContactResponse> setDisposition(
            @Parameter(description = "UUID kontaktu", required = true)
            @PathVariable String id,
            @Valid @RequestBody DispositionRequest request
    ) {
        UUID contactId = parseContactId(id);
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        boolean isAgent = currentUserIsAgent();

        log.debug("[ContactController] Disposition: contactId={}, tenant={}, code={}",
                contactId, tenantId, request.dispositionCode());

        ContactResponse response = contactService.setDisposition(contactId, request, tenantId, userId, isAgent);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Historia kontaktów klienta
    // =========================================================================

    @GetMapping("/customer/{customerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Historia kontaktów klienta",
        description = "Zwraca paginowaną historię wszystkich kontaktów klienta. " +
                      "Używane przez panel profilu klienta (FE-019). " +
                      "Posortowane od najnowszych. Dostępne dla wszystkich ról.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Historia kontaktów klienta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<PagedResponse<ContactResponse>> getCustomerHistory(
            @Parameter(description = "UUID klienta", required = true)
            @PathVariable UUID customerId,

            @Parameter(description = "Numer strony (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Rozmiar strony (max 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[ContactController] Historia klienta: customerId={}, tenant={}", customerId, tenantId);

        PagedResponse<ContactResponse> response = contactService.getCustomerHistory(
                customerId, tenantId, page, size);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Presigned URL do nagrania kontaktu (BE-037)
    // =========================================================================

    @GetMapping("/{id}/recording")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Pobierz presigned URL nagrania kontaktu",
        description = "Generuje jednorazowy presigned URL do pobrania/odtworzenia nagrania kontaktu. " +
                      "URL jest ważny przez 15 minut od wygenerowania. " +
                      "AGENT może pobierać nagrania tylko własnych kontaktów. " +
                      "SUPERVISOR i ADMIN mają dostęp do nagrań wszystkich kontaktów tenanta.",
        parameters = {
            @Parameter(
                name = "id",
                description = "UUID kontaktu",
                required = true,
                example = "22222222-2222-2222-2222-222222222222"
            )
        },
        responses = {
            @ApiResponse(responseCode = "200", description = "Presigned URL wygenerowany pomyślnie"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404",
                         description = "Kontakt nie istnieje lub nie posiada nagrania"),
            @ApiResponse(responseCode = "409",
                         description = "AGENT próbuje pobrać nagranie cudzego kontaktu"),
            @ApiResponse(responseCode = "503",
                         description = "Usługa MinIO/S3 jest niedostępna")
        }
    )
    public ResponseEntity<ContactRecordingUrlResponse> getRecordingUrl(
            @Parameter(description = "UUID kontaktu", required = true)
            @PathVariable String id
    ) {
        UUID contactId = parseContactId(id);
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        boolean isAgent = currentUserIsAgent();

        log.debug("[ContactController] Żądanie nagrania: contactId={}, tenant={}, isAgent={}",
                contactId, tenantId, isAgent);

        ContactRecordingUrlResponse response = contactService.getRecordingUrl(
                contactId, tenantId, userId, isAgent);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Podgląd treści wiadomości email kontaktu
    // =========================================================================

    @GetMapping("/{id}/email-preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Podgląd treści wiadomości email",
        description = "Zwraca nagłówki i treść (HTML/text) pierwszej wiadomości email powiązanej z kontaktem. " +
                      "Kontakt musi być kanałem EMAIL, w przeciwnym razie zwracany jest HTTP 400. " +
                      "Dostępny dla wszystkich ról (ADMIN, SUPERVISOR, AGENT).",
        parameters = {
            @Parameter(
                name = "id",
                description = "UUID kontaktu",
                required = true,
                example = "33333333-3333-3333-3333-333333333333"
            )
        },
        responses = {
            @ApiResponse(responseCode = "200", description = "Podgląd wiadomości email"),
            @ApiResponse(responseCode = "400", description = "Kontakt nie jest kanałem EMAIL"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kontakt nie istnieje lub brak wiadomości email")
        }
    )
    public ResponseEntity<EmailPreviewResponse> getEmailPreview(
            @Parameter(description = "UUID kontaktu", required = true)
            @PathVariable String id
    ) {
        UUID contactId = parseContactId(id);
        UUID tenantId = TenantContext.getTenantId();

        log.debug("[ContactController] Podgląd email: contactId={}, tenant={}", contactId, tenantId);

        EmailPreviewResponse response = contactService.getEmailPreview(contactId, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Oddzwonienie z rozmowy przychodzącej (BE-040)
    // =========================================================================

    @PostMapping("/{contactId}/callback")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Zaplanuj oddzwonienie z rozmowy",
        description = "Tworzy zaplanowane oddzwonienie powiązane z kontaktem. " +
                      "sourceType ustawiany automatycznie na podstawie kierunku rozmowy: " +
                      "INBOUND_CALLBACK (rozmowa przychodząca) lub OUTBOUND_CALLBACK (wychodząca). " +
                      "Dla roli AGENT dozwolone tylko dla własnej rozmowy.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Callback zaplanowany"),
            @ApiResponse(responseCode = "403", description = "AGENT – cudza rozmowa"),
            @ApiResponse(responseCode = "404", description = "Kontakt nie istnieje"),
            @ApiResponse(responseCode = "422", description = "scheduledAt w przeszłości")
        }
    )
    public ResponseEntity<ScheduledCallbackResponse> createInboundCallback(
            @Parameter(description = "UUID kontaktu") @PathVariable UUID contactId,
            @Valid @RequestBody CreateInboundCallbackRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID jwtAgentId = TenantContext.getUserId();
        String role = TenantContext.getUserRole();

        Contact contact = contactRepository.findById(contactId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Kontakt nie istnieje: " + contactId));

        if ("AGENT".equals(role) && contact.getAgentId() != null
                && !contact.getAgentId().equals(jwtAgentId)) {
            throw new AccessDeniedException("Agent może planować oddzwonienie tylko z własnej rozmowy");
        }

        UUID effectiveAgentId = "AGENT".equals(role) ? jwtAgentId
                : (request.agentId() != null ? request.agentId() : jwtAgentId);

        String sourceType = "OUTBOUND".equalsIgnoreCase(contact.getDirection())
                ? "OUTBOUND_CALLBACK"
                : "INBOUND_CALLBACK";

        ScheduledCallback callback = ScheduledCallback.builder()
                .tenantId(tenantId)
                .agentId(effectiveAgentId)
                .phone(request.phone())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .scheduledAt(request.scheduledAt())
                .notes(request.notes())
                .sourceType(sourceType)
                .originContactId(contactId)
                .status("PENDING")
                .build();

        ScheduledCallback saved = scheduledCallbackRepository.save(callback);

        log.info("[ContactController] Callback zaplanowany: callbackId={}, contactId={}, sourceType={}, tenant={}",
                saved.getCallbackId(), contactId, sourceType, tenantId);

        URI location = URI.create("/api/dialer/callbacks/" + saved.getCallbackId());
        return ResponseEntity.created(location).body(ScheduledCallbackResponse.from(saved));
    }

    // =========================================================================
    // Powiązane kontakty przez callback
    // =========================================================================

    /**
     * Zwraca listę kontaktów powiązanych z danym kontaktem przez callback.
     *
     * <p>Powiązania są dwukierunkowe:
     * <ul>
     *   <li><strong>PARENT</strong> – ten kontakt ma ustawiony {@code callback_id};
     *       szukamy kontaktu źródłowego, z którego callback powstał
     *       (callback.origin_contact_id → kontakt PARENT).</li>
     *   <li><strong>CHILD</strong> – ten kontakt jest kontaktem źródłowym jakiegoś callbacku
     *       (callback.origin_contact_id = contactId); zwracamy kontakty realizacji
     *       tych callbacków (contact.callback_id = callback.callbackId).</li>
     * </ul>
     *
     * <p>Uprawnienia: ADMIN/SUPERVISOR/AGENT – AGENT dostaje 200 z pustą listą
     * jeśli kontakt nie należy do niego (poglądowy widok pomocniczy).
     *
     * @param id UUID kontaktu (path variable)
     * @return lista powiązanych kontaktów z typem powiązania (PARENT/CHILD)
     */
    @GetMapping("/{id}/related")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Powiązane kontakty i callbacki",
        description = "Zwraca listę elementów powiązanych z danym kontaktem przez scheduled_callback. " +
                      "Każdy element ma itemType (CONTACT lub CALLBACK) i relationType (PARENT lub CHILD). " +
                      "PARENT = kontakt źródłowy (ten kontakt powstał z callbacku zaplanowanego przez inny kontakt). " +
                      "CHILD = callback zaplanowany z tego kontaktu oraz kontakty jego realizacji.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista powiązanych elementów (może być pusta)"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kontakt nie istnieje")
        }
    )
    public ResponseEntity<List<RelatedItem>> getRelatedContacts(
            @PathVariable String id) {

        UUID tenantId = TenantContext.getTenantId();
        UUID contactId = parseContactId(id);

        Contact contact = contactRepository.findById(contactId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Kontakt nie istnieje: " + contactId));

        List<RelatedItem> related = new ArrayList<>();

        // --- Kierunek A: ten kontakt ma callback_id (jest "dzieckiem" realizacji callbacku) ---
        // Szukamy callbacku i przez niego kontaktu źródłowego (origin_contact_id)
        if (contact.getCallbackId() != null) {
            scheduledCallbackRepository.findById(contact.getCallbackId(), tenantId)
                    .ifPresent(callback -> {
                        if (callback.getOriginContactId() != null) {
                            contactRepository.findById(callback.getOriginContactId(), tenantId)
                                    .ifPresent(parent -> related.add(RelatedItem.ofContact(
                                            parent.getContactId(),
                                            parent.getStartedAt(),
                                            parent.getChannel(),
                                            parent.getDirection(),
                                            parent.getStatus(),
                                            parent.getRemoteAddress(),
                                            "PARENT"
                                    )));
                        }
                    });
        }

        // --- Kierunek B: ten kontakt jest origin_contact_id jakiegoś callbacku ---
        // Pobieramy wszystkie callbacki zaplanowane z tego kontaktu.
        // Dla każdego callbacku: dodajemy sam callback jako CHILD (typ CALLBACK),
        // a następnie szukamy kontaktów realizacji (contact.callback_id = callback.callbackId)
        // i dodajemy je jako CHILD (typ CONTACT).
        List<ScheduledCallback> originCallbacks =
                scheduledCallbackRepository.findByOriginContactId(contactId, tenantId);

        for (ScheduledCallback callback : originCallbacks) {
            // Dodaj sam callback jako element CHILD
            related.add(RelatedItem.ofCallback(
                    callback.getCallbackId(),
                    callback.getScheduledAt(),
                    callback.getStatus(),
                    callback.getPhone(),
                    callback.getFirstName(),
                    callback.getLastName(),
                    "CHILD"
            ));

            // Dodaj kontakty realizacji tego callbacku (jeśli dialer już go obsłużył)
            List<Contact> childContacts =
                    contactRepository.findByCallbackId(callback.getCallbackId(), tenantId);
            for (Contact child : childContacts) {
                related.add(RelatedItem.ofContact(
                        child.getContactId(),
                        child.getStartedAt(),
                        child.getChannel(),
                        child.getDirection(),
                        child.getStatus(),
                        child.getRemoteAddress(),
                        "CHILD"
                ));
            }
        }

        // --- Kierunek C: EMAIL OUTBOUND z inboundContactId → szukamy kontaktu macierzystego (PARENT) ---
        // Kontakt OUTBOUND EMAIL zawiera w channelMetadata klucz "inboundContactId" wskazujący
        // na kontakt INBOUND, w odpowiedzi na który wysłano wiadomość.
        if ("EMAIL".equals(contact.getChannel()) && "OUTBOUND".equals(contact.getDirection())) {
            Object inboundIdObj = contact.getChannelMetadata().get("inboundContactId");
            if (inboundIdObj instanceof String inboundIdStr && !inboundIdStr.isBlank()) {
                try {
                    UUID inboundContactId = UUID.fromString(inboundIdStr);
                    contactRepository.findById(inboundContactId, tenantId)
                            .ifPresent(parent -> related.add(RelatedItem.ofContact(
                                    parent.getContactId(),
                                    parent.getStartedAt(),
                                    parent.getChannel(),
                                    parent.getDirection(),
                                    parent.getStatus(),
                                    parent.getRemoteAddress(),
                                    "PARENT"
                            )));
                } catch (IllegalArgumentException e) {
                    log.warn("[ContactController] Nieprawidłowy UUID w channelMetadata.inboundContactId: " +
                            "contactId={}, wartość={}", contactId, inboundIdObj);
                }
            }
        }

        // --- Kierunek D: EMAIL INBOUND → szukamy odpowiedzi OUTBOUND (CHILD) ---
        // Kontakty OUTBOUND EMAIL mają w channelMetadata klucz "inboundContactId" = this.contactId.
        // Pobieramy wszystkie takie kontakty jako elementy CHILD.
        if ("EMAIL".equals(contact.getChannel()) && "INBOUND".equals(contact.getDirection())) {
            List<Contact> outboundReplies = contactRepository.findByChannelMetadataValue(
                    "inboundContactId", contactId.toString(), tenantId);
            for (Contact reply : outboundReplies) {
                related.add(RelatedItem.ofContact(
                        reply.getContactId(),
                        reply.getStartedAt(),
                        reply.getChannel(),
                        reply.getDirection(),
                        reply.getStatus(),
                        reply.getRemoteAddress(),
                        "CHILD"
                ));
            }
        }

        log.debug("[ContactController] getRelatedContacts: contactId={}, powiązanych={}, tenant={}",
                contactId, related.size(), tenantId);

        return ResponseEntity.ok(related);
    }
}
