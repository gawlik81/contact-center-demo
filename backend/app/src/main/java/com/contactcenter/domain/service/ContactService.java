package com.contactcenter.domain.service;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.contact.dto.ContactFilterParams;
import com.contactcenter.api.contact.dto.ContactRecordingUrlResponse;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.CreateContactRequest;
import com.contactcenter.api.contact.dto.DispositionRequest;
import com.contactcenter.api.contact.dto.EmailPreviewResponse;
import com.contactcenter.api.contact.dto.UpdateContactRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.EmailMessageRepository;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający historią kontaktów.
 *
 * <p>Implementuje BE-027: Contact API – zapis i odczyt historii kontaktów.
 *
 * <p>Uprawnienia:
 * <ul>
 *   <li>AGENT – może tworzyć kontakty, aktualizować własne (własny agentId),
 *       ustawiać disposition na własnych kontaktach.</li>
 *   <li>SUPERVISOR/ADMIN – pełen CRUD, mogą filtrować po dowolnym agencie.</li>
 * </ul>
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Każdy odczyt i zapis filtruje po tenantId z {@link TenantContext}</li>
 *   <li>Cross-tenant guard przez {@code assertSameTenant()} w repozytorium</li>
 *   <li>AGENT może aktualizować tylko kontakty przypisane do siebie</li>
 * </ul>
 *
 * <p>Tabela {@code contact} jest partycjonowana – zapis przez natywny INSERT,
 * aktualizacja przez natywny UPDATE (trigger oblicza duration_seconds).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int RECORDING_URL_TTL_MINUTES = 15;

    private final ContactRepository contactRepository;
    private final RecordingService recordingService;
    private final EmailMessageRepository emailMessageRepository;
    private final AppUserRepository appUserRepository;
    private final ContactEventService contactEventService;

    // =========================================================================
    // Tworzenie kontaktu
    // =========================================================================

    /**
     * Tworzy nowy kontakt.
     *
     * <p>Kontakt jest tworzony z statusem QUEUED. Agent może zainicjować kontakt
     * (np. przy odbieraniu połączenia przychodzącego).
     *
     * @param request  dane nowego kontaktu
     * @param tenantId UUID tenanta z TenantContext
     * @return DTO nowo utworzonego kontaktu
     */
    @Transactional
    @Audited(action = "CONTACT_CREATED", entityType = "CONTACT")
    public ContactResponse createContact(CreateContactRequest request, UUID tenantId) {
        Instant now = Instant.now();
        Instant startedAt = request.startedAt() != null ? request.startedAt() : now;

        Contact contact = Contact.builder()
                .contactId(UUID.randomUUID())
                .tenantId(tenantId)
                .customerId(request.customerId())
                .agentId(request.agentId())
                .queueId(request.queueId())
                .campaignId(request.campaignId())
                .channel(request.channel())
                .direction(request.direction())
                .status("QUEUED")
                .remoteAddress(request.remoteAddress())
                .queuedAt(now)
                .startedAt(startedAt)
                .channelMetadata(request.channelMetadata() != null
                        ? new HashMap<>(request.channelMetadata()) : new HashMap<>())
                .createdAt(now)
                .callbackId(request.callbackId())
                .build();

        Contact saved = contactRepository.insert(contact);

        log.info("[ContactService] Kontakt utworzony: contactId={}, tenant={}, channel={}, direction={}",
                saved.getContactId(), tenantId, saved.getChannel(), saved.getDirection());

        return ContactResponse.from(saved);
    }

    // =========================================================================
    // Odczyt kontaktu
    // =========================================================================

    /**
     * Pobiera szczegóły kontaktu.
     *
     * <p>AGENT może pobierać tylko kontakty, w których jest przypisanym agentem.
     * SUPERVISOR/ADMIN mają dostęp do wszystkich kontaktów tenanta.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param userId    UUID zalogowanego użytkownika (weryfikacja dla AGENT)
     * @param isAgent   true gdy zalogowany użytkownik jest AGENT
     * @return DTO kontaktu
     * @throws EntityNotFoundException   HTTP 404 gdy kontakt nie istnieje lub inny tenant
     * @throws InvalidOperationException HTTP 409 gdy AGENT próbuje pobrać cudzy kontakt
     */
    @Transactional(readOnly = true)
    public ContactResponse getContact(UUID contactId, UUID tenantId, UUID userId, boolean isAgent) {
        Contact contact = findContactOrThrow(contactId, tenantId);

        // AGENT may only view their own contacts.
        // Allow access when agentId is null – inbound Twilio calls have no agent assigned yet
        // at the time the contact record is created (webhook fires before the agent answers).
        if (isAgent && contact.getAgentId() != null && !userId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Agent może przeglądać tylko kontakty przypisane do siebie: " + contactId);
        }

        return ContactResponse.from(contact);
    }

    /**
     * Wewnętrzna metoda odczytu – używana po UPDATE w tej samej transakcji.
     * Nie stosuje dodatkowej kontroli AGENT – wywołujący jest odpowiedzialny
     * za wcześniejszą weryfikację uprawnień.
     */
    private ContactResponse getContactInternal(UUID contactId, UUID tenantId) {
        Contact contact = findContactOrThrow(contactId, tenantId);
        return ContactResponse.from(contact);
    }

    // =========================================================================
    // Lista kontaktów z paginacją i filtrami
    // =========================================================================

    /**
     * Pobiera paginowaną listę kontaktów z opcjonalnymi filtrami.
     *
     * <p>Agenci mogą widzieć tylko swoje kontakty (filtr agentId jest narzucony).
     * Supervisorzy i admini mogą filtrować po dowolnym agencie lub widzieć wszystkie.
     *
     * @param params   parametry filtrowania i paginacji
     * @param tenantId UUID tenanta
     * @param userId   UUID zalogowanego użytkownika (do wymuszania filtra dla AGENT)
     * @param isAgent  true gdy zalogowany użytkownik jest AGENT
     * @return paginowana lista kontaktów
     */
    @Transactional(readOnly = true)
    public PagedResponse<ContactResponse> listContacts(ContactFilterParams params,
                                                       UUID tenantId,
                                                       UUID userId,
                                                       boolean isAgent) {
        int effectiveSize = Math.min(Math.max(params.size(), 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(params.page(), 0);

        // AGENT może widzieć tylko swoje kontakty
        UUID effectiveAgentId = isAgent ? userId : params.agentId();

        log.debug("[ContactService] Lista kontaktów: tenant={}, agentId={}, customerId={}, status={}, " +
                  "channel={}, queueId={}, campaignId={}, remoteAddress={}, durationMin={}, durationMax={}, " +
                  "page={}, size={}, isAgent={}",
                  tenantId, effectiveAgentId, params.customerId(), params.status(),
                  params.channel(), params.queueId(), params.campaignId(), params.remoteAddress(),
                  params.durationMin(), params.durationMax(), effectivePage, effectiveSize, isAgent);

        List<Contact> contacts = contactRepository.findContacts(
                tenantId, effectiveAgentId, params.customerId(),
                params.status(), params.channel(),
                params.dateFrom(), params.dateTo(),
                params.queueId(), params.campaignId(), params.remoteAddress(),
                params.durationMin(), params.durationMax(),
                effectivePage, effectiveSize
        );

        long totalElements = contactRepository.countContacts(
                tenantId, effectiveAgentId, params.customerId(),
                params.status(), params.channel(),
                params.dateFrom(), params.dateTo(),
                params.queueId(), params.campaignId(), params.remoteAddress(),
                params.durationMin(), params.durationMax()
        );

        int totalPages = (int) Math.ceil((double) totalElements / effectiveSize);

        List<ContactResponse> content = contacts.stream()
                .map(ContactResponse::from)
                .toList();

        return new PagedResponse<>(
                content,
                effectivePage,
                effectiveSize,
                totalElements,
                totalPages,
                effectivePage == 0,
                effectivePage >= totalPages - 1 || totalPages == 0
        );
    }

    // =========================================================================
    // Aktualizacja kontaktu
    // =========================================================================

    /**
     * Aktualizuje kontakt (PATCH semantics).
     *
     * <p>AGENT może aktualizować tylko kontakty, w których jest przypisanym agentem.
     * Pola null są ignorowane.
     *
     * @param contactId UUID kontaktu
     * @param request   dane do aktualizacji
     * @param tenantId  UUID tenanta
     * @param userId    UUID zalogowanego użytkownika
     * @param isAgent   true gdy zalogowany użytkownik jest AGENT
     * @return DTO zaktualizowanego kontaktu
     * @throws EntityNotFoundException  HTTP 404 gdy kontakt nie istnieje
     * @throws InvalidOperationException HTTP 409 gdy AGENT próbuje zaktualizować cudzego kontaktu
     */
    @Transactional
    @Audited(action = "CONTACT_UPDATED", entityType = "CONTACT")
    public ContactResponse updateContact(UUID contactId, UpdateContactRequest request,
                                         UUID tenantId, UUID userId, boolean isAgent) {
        Contact contact = findContactOrThrow(contactId, tenantId);

        // AGENT may only modify their own contacts.
        // Allow when agentId is null – inbound Twilio calls have no agent assigned at creation time.
        if (isAgent && contact.getAgentId() != null && !userId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Agent może aktualizować tylko kontakty przypisane do siebie: " + contactId);
        }

        // PATCH semantics – null = bez zmiany
        if (request.agentId() != null) {
            contact.setAgentId(request.agentId());
        }
        if (request.status() != null) {
            contact.setStatus(request.status());
        }
        if ("COMPLETED".equals(request.status()) || "ABANDONED".equals(request.status())) {
            contactEventService.closeAgent(contactId, tenantId);
            contactEventService.closeQueue(contactId, tenantId);
            contactEventService.closeHold(contactId, tenantId);
        }
        if (request.assignedAt() != null) {
            contact.setAssignedAt(request.assignedAt());
        }
        if (request.endedAt() != null) {
            contact.setEndedAt(request.endedAt());
        }
        if (request.remoteAddress() != null) {
            contact.setRemoteAddress(request.remoteAddress());
        }
        if (request.channelMetadata() != null) {
            contact.setChannelMetadata(new HashMap<>(request.channelMetadata()));
        }

        int updated = contactRepository.update(contact);
        if (updated == 0) {
            throw new EntityNotFoundException("Nie udało się zaktualizować kontaktu: " + contactId);
        }

        log.info("[ContactService] Kontakt zaktualizowany: contactId={}, tenant={}, status={}",
                contactId, tenantId, contact.getStatus());

        // Odczyt po UPDATE – trigger mógł zmienić duration_seconds i updated_at.
        // Używamy getContactInternal bo uprawnienia zostały już zweryfikowane wyżej.
        return getContactInternal(contactId, tenantId);
    }

    // =========================================================================
    // Ustawianie disposition code
    // =========================================================================

    /**
     * Ustawia kod dyspozycji kontaktu po jego zakończeniu.
     *
     * <p>AGENT może ustawiać disposition tylko na własnych kontaktach.
     * Kontakt musi być w statusie COMPLETED, ABANDONED lub TRANSFERRED.
     *
     * @param contactId UUID kontaktu
     * @param request   żądanie z disposition code
     * @param tenantId  UUID tenanta
     * @param userId    UUID zalogowanego użytkownika
     * @param isAgent   true gdy zalogowany użytkownik jest AGENT
     * @return DTO zaktualizowanego kontaktu
     * @throws EntityNotFoundException   HTTP 404 gdy kontakt nie istnieje
     * @throws InvalidOperationException HTTP 409 gdy AGENT próbuje ustawić disposition na cudzym kontakcie
     *                                   lub kontakt nie jest w odpowiednim statusie
     */
    @Transactional
    @Audited(action = "CONTACT_DISPOSITION_SET", entityType = "CONTACT")
    public ContactResponse setDisposition(UUID contactId, DispositionRequest request,
                                          UUID tenantId, UUID userId, boolean isAgent) {
        Contact contact = findContactOrThrow(contactId, tenantId);

        // AGENT may only set disposition on their own contacts.
        // Allow when agentId is null – inbound Twilio calls have no agent assigned at creation time.
        // The agent_id is populated later when the agent answers (POST /api/telephony/calls/{callId}/answer).
        if (isAgent && contact.getAgentId() != null && !userId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Agent może ustawiać disposition tylko na własnych kontaktach: " + contactId);
        }

        // Walidacja statusu – disposition ma sens tylko po zakończeniu kontaktu
        if ("QUEUED".equals(contact.getStatus()) || "ACTIVE".equals(contact.getStatus())) {
            throw new InvalidOperationException(
                    "Nie można ustawić disposition dla aktywnego kontaktu (status: " + contact.getStatus() + ")");
        }

        contact.setDispositionCode(request.dispositionCode());
        contact.setNotes(request.notes());

        int updated = contactRepository.update(contact);
        if (updated == 0) {
            throw new EntityNotFoundException("Nie udało się ustawić disposition dla kontaktu: " + contactId);
        }

        log.info("[ContactService] Disposition ustawiony: contactId={}, tenant={}, code={}",
                contactId, tenantId, request.dispositionCode());

        return getContactInternal(contactId, tenantId);
    }

    // =========================================================================
    // Przypisanie agenta (odbieranie połączenia)
    // =========================================================================

    /**
     * Assigns an agent to a contact that was created without one.
     *
     * <p>Called when an agent answers an inbound Twilio call. At webhook time the contact
     * record is created with {@code agent_id = null}; the agent becomes known only when
     * they explicitly answer. This method persists that assignment and transitions the
     * contact status to ACTIVE.
     *
     * @param contactId  UUID of the contact
     * @param tenantId   UUID of the tenant
     * @param agentId    UUID of the agent who answered
     * @return DTO of the updated contact
     * @throws EntityNotFoundException   HTTP 404 when the contact does not exist
     * @throws InvalidOperationException HTTP 409 when the contact is already assigned to a different agent
     */
    @Transactional
    @Audited(action = "CONTACT_AGENT_ASSIGNED", entityType = "CONTACT")
    public ContactResponse assignAgent(UUID contactId, UUID tenantId, UUID agentId) {
        Contact contact = findContactOrThrow(contactId, tenantId);

        // If already assigned to a different agent – reject (do not silently overwrite ownership)
        if (contact.getAgentId() != null && !agentId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Kontakt jest już przypisany do innego agenta: " + contactId);
        }

        Instant assignedAt = Instant.now();
        int updated = contactRepository.assignAgent(contactId, tenantId, agentId, assignedAt);
        if (updated == 0) {
            throw new EntityNotFoundException(
                    "Nie udało się przypisać agenta do kontaktu (zły status lub brak rekordu): " + contactId);
        }

        contactEventService.closeQueue(contactId, tenantId);
        String agentName = appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)
            .map(u -> u.getFirstName() + " " + u.getLastName())
            .orElse("");
        contactEventService.openAgent(contactId, tenantId, agentId, agentName);

        log.info("[ContactService] Agent przypisany do kontaktu: contactId={}, agentId={}, tenant={}",
                contactId, agentId, tenantId);

        return getContactInternal(contactId, tenantId);
    }

    // =========================================================================
    // Przyjęcie kontaktu przez agenta (EMAIL / CHAT – potwierdzenie odebrania)
    // =========================================================================

    /**
     * Przełącza kontakt ze statusu ASSIGNED na ACTIVE.
     *
     * <p>Wywoływany przez agenta bezpośrednio po otwarciu zakładki z kontaktem
     * asynchronicznym (EMAIL, CHAT). Informuje {@link ContactAssignmentMonitor},
     * że kontakt został odebrany i nie wymaga ponownego wysyłania CONTACT_ASSIGNED.
     *
     * <p>Operacja jest idempotentna – jeśli kontakt jest już ACTIVE, zwraca go bez
     * błędu (agent mógł wywołać endpoint dwukrotnie po refreshu strony).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param agentId   UUID agenta (z TenantContext) – weryfikacja własności
     * @return zaktualizowany DTO kontaktu
     * @throws EntityNotFoundException   HTTP 404 gdy kontakt nie istnieje
     * @throws InvalidOperationException HTTP 409 gdy kontakt należy do innego agenta
     *                                   lub jest w niedozwolonym statusie (COMPLETED, ABANDONED)
     */
    @Transactional
    @Audited(action = "CONTACT_ACCEPTED", entityType = "CONTACT")
    public ContactResponse acceptContact(UUID contactId, UUID tenantId, UUID agentId) {
        Contact contact = findContactOrThrow(contactId, tenantId);

        // Idempotentność – kontakt już aktywny (np. drugi request po refreshu)
        if ("ACTIVE".equals(contact.getStatus())) {
            return getContactInternal(contactId, tenantId);
        }

        // Tylko kontakt ASSIGNED może być akceptowany
        if (!"ASSIGNED".equals(contact.getStatus())) {
            throw new InvalidOperationException(
                    "Kontakt nie jest w statusie ASSIGNED (aktualny status: " +
                    contact.getStatus() + "): " + contactId);
        }

        // Agent musi być właścicielem przypisania
        if (contact.getAgentId() != null && !agentId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Kontakt jest przypisany do innego agenta: " + contactId);
        }

        Instant now = Instant.now();
        contact.setStatus("ACTIVE");
        contact.setUpdatedAt(now);
        contactRepository.update(contact);

        log.info("[ContactService] Kontakt zaakceptowany przez agenta: contactId={}, agentId={}, tenant={}",
                contactId, agentId, tenantId);

        return getContactInternal(contactId, tenantId);
    }

    // =========================================================================
    // Porzucenie kontaktu przez agenta (EMAIL / CHAT – anulowanie bez odpowiedzi)
    // =========================================================================

    /**
     * Porzuca kontakt EMAIL/CHAT przez agenta (bez wysyłania odpowiedzi).
     *
     * <p>Wywoływany gdy agent klika "Anuluj" w zakładce emaila. Ustawia status
     * kontaktu na ABANDONED i zamyka zakładkę po stronie frontendu.
     *
     * <p>Operacja jest idempotentna – jeśli kontakt jest już ABANDONED lub COMPLETED,
     * zwraca go bez błędu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param agentId   UUID agenta (z TenantContext) – weryfikacja własności
     * @return zaktualizowany DTO kontaktu
     * @throws EntityNotFoundException   HTTP 404 gdy kontakt nie istnieje
     * @throws InvalidOperationException HTTP 409 gdy kontakt należy do innego agenta
     */
    @Transactional
    @Audited(action = "CONTACT_ABANDONED", entityType = "CONTACT")
    public ContactResponse abandonContact(UUID contactId, UUID tenantId, UUID agentId) {
        Contact contact = findContactOrThrow(contactId, tenantId);

        // Idempotentność – kontakt już w stanie końcowym
        if ("ABANDONED".equals(contact.getStatus()) || "COMPLETED".equals(contact.getStatus())) {
            return getContactInternal(contactId, tenantId);
        }

        // Weryfikacja własności (tylko dla kontaktów z przypisanym agentem)
        if (contact.getAgentId() != null && !agentId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Kontakt jest przypisany do innego agenta: " + contactId);
        }

        Instant now = Instant.now();
        contact.setStatus("ABANDONED");
        contact.setEndedAt(now);
        contact.setUpdatedAt(now);
        contactRepository.update(contact);

        contactEventService.closeAgent(contactId, tenantId);
        contactEventService.closeQueue(contactId, tenantId);

        log.info("[ContactService] Kontakt porzucony przez agenta: contactId={}, agentId={}, tenant={}",
                contactId, agentId, tenantId);

        return getContactInternal(contactId, tenantId);
    }

    // =========================================================================
    // Historia kontaktów klienta
    // =========================================================================

    /**
     * Pobiera historię kontaktów dla konkretnego klienta.
     *
     * <p>Używane przez panel profilu klienta (FE-019). Dostępne dla AGENT,
     * SUPERVISOR i ADMIN.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @param page       numer strony (0-based)
     * @param size       rozmiar strony (max 100)
     * @return paginowana historia kontaktów klienta
     */
    @Transactional(readOnly = true)
    public PagedResponse<ContactResponse> getCustomerHistory(UUID customerId, UUID tenantId,
                                                              int page, int size) {
        int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 0);

        log.debug("[ContactService] Historia klienta: customerId={}, tenant={}, page={}, size={}",
                customerId, tenantId, effectivePage, effectiveSize);

        List<Contact> contacts = contactRepository.findByCustomerId(
                customerId, tenantId, effectivePage, effectiveSize);

        long totalElements = contactRepository.countByCustomerId(customerId, tenantId);
        int totalPages = (int) Math.ceil((double) totalElements / effectiveSize);

        List<ContactResponse> content = contacts.stream()
                .map(ContactResponse::from)
                .toList();

        return new PagedResponse<>(
                content,
                effectivePage,
                effectiveSize,
                totalElements,
                totalPages,
                effectivePage == 0,
                effectivePage >= totalPages - 1 || totalPages == 0
        );
    }

    // =========================================================================
    // Nagranie kontaktu – presigned URL (BE-037)
    // =========================================================================

    /**
     * Generuje presigned URL do nagrania kontaktu (TTL 15 minut).
     *
     * <p>Weryfikuje przynależność kontaktu do tenanta zalogowanego użytkownika.
     * AGENT może pobierać URL nagrania tylko dla kontaktów przypisanych do siebie.
     * SUPERVISOR i ADMIN mają dostęp do wszystkich nagrań tenanta.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta z TenantContext
     * @param userId    UUID zalogowanego użytkownika (weryfikacja dla AGENT)
     * @param isAgent   true gdy zalogowany użytkownik jest AGENT
     * @return DTO z presigned URL, datą wygaśnięcia, nazwą pliku i czasem trwania
     * @throws EntityNotFoundException   HTTP 404 gdy kontakt nie istnieje lub inny tenant
     * @throws ResponseStatusException   HTTP 404 gdy kontakt nie ma nagrania;
     *                                   HTTP 503 gdy MinIO/S3 jest niedostępny
     * @throws InvalidOperationException HTTP 409 gdy AGENT próbuje pobrać nagranie cudzego kontaktu
     */
    @Transactional(readOnly = true)
    @Audited(action = "RECORDING_URL_REQUESTED", entityType = "CONTACT")
    public ContactRecordingUrlResponse getRecordingUrl(UUID contactId, UUID tenantId,
                                                        UUID userId, boolean isAgent) {
        Contact contact = findContactOrThrow(contactId, tenantId);

        // AGENT może pobierać nagrania tylko własnych kontaktów.
        // Wyjątek: brak agentId (kontakt inbound Twilio przed odebraniem) – dostęp dozwolony.
        if (isAgent && contact.getAgentId() != null && !userId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Agent może pobierać nagrania tylko własnych kontaktów: " + contactId);
        }

        // Sprawdź czy kontakt ma nagranie
        if (contact.getRecordingUrl() == null || contact.getRecordingUrl().isBlank()) {
            log.debug("[ContactService] Brak nagrania dla contactId={}, tenant={}", contactId, tenantId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Brak nagrania dla tego kontaktu: " + contactId);
        }

        String s3Key = contact.getRecordingUrl();

        // Generuj presigned URL przez RecordingService (TTL 15 minut, znamy już s3Key z kontaktu)
        Duration ttl = Duration.ofMinutes(RECORDING_URL_TTL_MINUTES);
        String presignedUrl;
        try {
            presignedUrl = recordingService.generatePresignedUrlForKey(s3Key, ttl);
        } catch (RecordingService.RecordingException e) {
            log.error("[ContactService] Błąd generowania presigned URL: contactId={}, s3Key={}, error={}",
                    contactId, s3Key, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Usługa nagrań jest chwilowo niedostępna. Spróbuj ponownie za chwilę.");
        }

        Instant expiresAt = Instant.now().plus(ttl);

        // Wykryj kanał i ustaw odpowiedni contentType
        // s3Key zawiera już właściwe rozszerzenie (.eml lub .mp3)
        String contentType = "EMAIL".equals(contact.getChannel()) ? "message/rfc822" : "audio/mpeg";

        log.info("[ContactService] Wygenerowano presigned URL do nagrania: contactId={}, tenant={}, " +
                 "ttlMinutes={}, durationSeconds={}, contentType={}",
                contactId, tenantId, RECORDING_URL_TTL_MINUTES, contact.getDurationSeconds(), contentType);

        return new ContactRecordingUrlResponse(
                presignedUrl,
                expiresAt,
                s3Key,
                contact.getDurationSeconds(),
                contentType
        );
    }

    // =========================================================================
    // Podgląd treści wiadomości email dla kontaktu
    // =========================================================================

    /**
     * Pobiera podgląd wiadomości email powiązanej z kontaktem.
     *
     * <p>Kontakt musi być kanałem EMAIL. Pobiera pierwszą wiadomość posortowaną
     * malejąco po {@code received_at} z tabeli {@code email_message}.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta z TenantContext
     * @return DTO z nagłówkami i treścią wiadomości
     * @throws ResponseStatusException HTTP 400 gdy kontakt nie jest kanałem EMAIL;
     *                                 HTTP 404 gdy kontakt nie istnieje lub brak wiadomości email
     */
    @Transactional(readOnly = true)
    public EmailPreviewResponse getEmailPreview(UUID contactId, UUID tenantId) {
        Contact contact = findContactOrThrow(contactId, tenantId);

        if (!"EMAIL".equals(contact.getChannel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Kontakt nie jest kanałem EMAIL");
        }

        // Próba 1: szukaj po contact_id (działa dla INBOUND – EmailMessage ma contact_id ustawione)
        Page<EmailMessage> page = emailMessageRepository.findByContactId(
                contactId, tenantId, Pageable.ofSize(1));

        EmailMessage message = page.getContent().stream().findFirst().orElse(null);

        // Próba 2: dla OUTBOUND – EmailMessage.contact_id nie jest aktualizowane;
        // zamiast tego kontakt ma w channelMetadata klucz "emailMessageId" z UUID wiadomości.
        if (message == null) {
            Object emailMsgIdObj = contact.getChannelMetadata() != null
                    ? contact.getChannelMetadata().get("emailMessageId")
                    : null;
            if (emailMsgIdObj instanceof String emailMsgIdStr && !emailMsgIdStr.isBlank()) {
                try {
                    UUID emailMsgId = UUID.fromString(emailMsgIdStr);
                    message = emailMessageRepository.findById(emailMsgId).orElse(null);
                } catch (IllegalArgumentException e) {
                    log.warn("[ContactService] Nieprawidłowy UUID w channelMetadata.emailMessageId: " +
                            "contactId={}, wartość={}", contactId, emailMsgIdObj);
                }
            }
        }

        if (message == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Brak wiadomości email dla kontaktu: " + contactId);
        }

        log.debug("[ContactService] Podgląd email: contactId={}, messageId={}, direction={}",
                contactId, message.getId(), message.getDirection());

        return new EmailPreviewResponse(
                message.getFromAddress(),
                message.getToAddress(),
                message.getCcAddress(),
                message.getSubject(),
                message.getBodyHtml(),
                message.getBodyText(),
                message.getReceivedAt(),
                message.getDirection()
        );
    }

    // =========================================================================
    // Cleanup – terminacja błędnych/przeterminowanych kontaktów w kolejce
    // =========================================================================

    /**
     * Kończy kontakty w statusie QUEUED, które są uznawane za błędne.
     *
     * <p>Kontakt jest uznawany za błędny jeśli:
     * <ul>
     *   <li>Timeout: {@code queued_at} starsze niż 30 minut od chwili wywołania –
     *       klient prawdopodobnie rozłączył się lub połączenie padło.</li>
     *   <li>Błąd telefonii: {@code channel_metadata.callStatus} to
     *       {@code 'failed'}, {@code 'busy'}, {@code 'no-answer'} lub {@code 'canceled'}.</li>
     *   <li>Flaga błędu: {@code channel_metadata.error} = {@code true}.</li>
     * </ul>
     *
     * <p>Wywoływana przy zmianie statusu agenta na AVAILABLE – zapewnia, że agent
     * nie dostanie do obsługi kontaktów, które i tak są martwe.
     *
     * @param tenantId UUID tenanta (z TenantContext)
     */
    @Transactional
    public void terminateStaleQueuedContacts(UUID tenantId) {
        Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);
        List<Contact> staleContacts = contactRepository.findStaleQueuedContacts(tenantId, threshold);

        if (staleContacts.isEmpty()) {
            log.debug("[ContactService] Brak przeterminowanych kontaktów do zakończenia: tenant={}", tenantId);
            return;
        }

        Instant now = Instant.now();
        int terminated = 0;
        for (Contact contact : staleContacts) {
            // Przeterminowane kontakty QUEUED to klienci którzy się rozłączyli bez obsługi –
            // właściwy status to ABANDONED, a nie ERROR.
            contact.setStatus("ABANDONED");
            contact.setEndedAt(now);
            int updated = contactRepository.update(contact);
            if (updated > 0) {
                terminated++;
            } else {
                log.warn("[ContactService] Nie udało się zaktualizować kontaktu ABANDONED: contactId={}, tenant={}",
                        contact.getContactId(), tenantId);
            }
        }

        log.info("[ContactService] Zakończono {} przeterminowanych kontaktów ze statusem ABANDONED: tenant={}",
                terminated, tenantId);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Contact findContactOrThrow(UUID contactId, UUID tenantId) {
        return contactRepository.findById(contactId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kontakt nie istnieje lub nie należy do tego tenanta: " + contactId));
    }
}
