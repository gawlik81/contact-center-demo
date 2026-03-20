package com.contactcenter.domain.service;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.contact.dto.ContactFilterParams;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.CreateContactRequest;
import com.contactcenter.api.contact.dto.DispositionRequest;
import com.contactcenter.api.contact.dto.UpdateContactRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    private final ContactRepository contactRepository;

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
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return DTO kontaktu
     * @throws EntityNotFoundException HTTP 404 gdy kontakt nie istnieje lub inny tenant
     */
    @Transactional(readOnly = true)
    public ContactResponse getContact(UUID contactId, UUID tenantId) {
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
                  "channel={}, page={}, size={}, isAgent={}",
                  tenantId, effectiveAgentId, params.customerId(), params.status(),
                  params.channel(), effectivePage, effectiveSize, isAgent);

        List<Contact> contacts = contactRepository.findContacts(
                tenantId, effectiveAgentId, params.customerId(),
                params.status(), params.channel(),
                params.dateFrom(), params.dateTo(),
                effectivePage, effectiveSize
        );

        long totalElements = contactRepository.countContacts(
                tenantId, effectiveAgentId, params.customerId(),
                params.status(), params.channel(),
                params.dateFrom(), params.dateTo()
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

        // AGENT może modyfikować tylko własne kontakty
        if (isAgent && !userId.equals(contact.getAgentId())) {
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

        // Odczyt po UPDATE – trigger mógł zmienić duration_seconds i updated_at
        return getContact(contactId, tenantId);
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

        // AGENT może ustawiać disposition tylko na własnych kontaktach
        if (isAgent && !userId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Agent może ustawiać disposition tylko na własnych kontaktach: " + contactId);
        }

        // Walidacja statusu – disposition ma sens tylko po zakończeniu kontaktu
        if ("QUEUED".equals(contact.getStatus()) || "ACTIVE".equals(contact.getStatus())) {
            throw new InvalidOperationException(
                    "Nie można ustawić disposition dla aktywnego kontaktu (status: " + contact.getStatus() + ")");
        }

        contact.setDispositionCode(request.dispositionCode());

        int updated = contactRepository.update(contact);
        if (updated == 0) {
            throw new EntityNotFoundException("Nie udało się ustawić disposition dla kontaktu: " + contactId);
        }

        log.info("[ContactService] Disposition ustawiony: contactId={}, tenant={}, code={}",
                contactId, tenantId, request.dispositionCode());

        return getContact(contactId, tenantId);
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
    // Metody pomocnicze
    // =========================================================================

    private Contact findContactOrThrow(UUID contactId, UUID tenantId) {
        return contactRepository.findById(contactId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kontakt nie istnieje lub nie należy do tego tenanta: " + contactId));
    }
}
