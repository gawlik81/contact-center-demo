package com.contactcenter.domain.service;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.contact.dto.ContactFilterParams;
import com.contactcenter.api.contact.dto.ContactRecordingUrlResponse;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.CreateContactRequest;
import com.contactcenter.api.contact.dto.DispositionRequest;
import com.contactcenter.api.contact.dto.EmailPreviewResponse;
import com.contactcenter.api.contact.dto.ContactEventResponse;
import com.contactcenter.api.contact.dto.UpdateContactRequest;
import com.contactcenter.api.telephony.dto.TransferCallRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.EmailMessageRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.telephony.TelephonyEventPublisher;
import com.contactcenter.domain.telephony.TransferRequest;
import com.contactcenter.domain.telephony.TransferTargetType;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final QueueRepository queueRepository;
    private final CampaignRepository campaignRepository;
    private final ContactEventService contactEventService;
    private final TelephonyAdapter telephonyAdapter;
    private final TelephonyEventPublisher eventPublisher;

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

        String agentName = resolveAgentName(contact.getAgentId(), tenantId);
        String dispositionLabel = resolveDispositionLabel(
                contact.getCampaignId(), contact.getDispositionCode(), tenantId);
        return ContactResponse.from(contact, agentName, dispositionLabel);
    }

    /**
     * Wewnętrzna metoda odczytu – używana po UPDATE w tej samej transakcji.
     * Nie stosuje dodatkowej kontroli AGENT – wywołujący jest odpowiedzialny
     * za wcześniejszą weryfikację uprawnień.
     */
    private ContactResponse getContactInternal(UUID contactId, UUID tenantId) {
        Contact contact = findContactOrThrow(contactId, tenantId);
        String agentName = resolveAgentName(contact.getAgentId(), tenantId);
        String dispositionLabel = resolveDispositionLabel(
                contact.getCampaignId(), contact.getDispositionCode(), tenantId);
        return ContactResponse.from(contact, agentName, dispositionLabel);
    }

    /**
     * Zwraca wyświetlaną nazwę agenta na podstawie jego UUID.
     *
     * <p>Format: {@code firstName + ' ' + lastName} gdy oba pola są niepuste;
     * w przeciwnym razie zwraca email agenta.
     * Zwraca {@code null} gdy agentId jest null lub użytkownik nie istnieje.
     *
     * @param agentId  UUID agenta (może być null)
     * @param tenantId UUID tenanta – wymagany do cross-tenant guard
     * @return nazwa agenta lub null
     */
    private String resolveAgentName(UUID agentId, UUID tenantId) {
        if (agentId == null) {
            return null;
        }
        return appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)
                .map(this::formatAgentName)
                .orElse(null);
    }

    /**
     * Batch lookup nazw agentów dla listy kontaktów – unika problemu N+1 zapytań.
     *
     * <p>Pobiera jednym zapytaniem wszystkich unikalnych agentów z listy kontaktów.
     * Kontakty bez agenta (agentId = null) są pomijane – w mapie wynikowej ich klucz
     * nie istnieje, więc {@code map.get(null)} zwróci null.
     *
     * @param contacts lista kontaktów do zmapowania
     * @param tenantId UUID tenanta – wymagany do cross-tenant guard
     * @return mapa agentId → wyświetlana nazwa agenta
     */
    private Map<UUID, String> resolveAgentNamesForContacts(List<Contact> contacts, UUID tenantId) {
        List<UUID> agentIds = contacts.stream()
                .map(Contact::getAgentId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        if (agentIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, String> result = new HashMap<>();
        appUserRepository.findAllByIdInAndTenantId(agentIds, tenantId)
                .forEach(user -> result.put(user.getId(), formatAgentName(user)));
        return result;
    }

    /**
     * Rozwiązuje czytelną etykietę dyspozycji dla pojedynczego kontaktu.
     *
     * <p>Pobiera kampanię z bazy i przeszukuje jej listę {@code dispositionCodes}
     * (JSONB: {@code [{"code": "SALE", "label": "Sprzedaż"}, ...]}) w celu znalezienia
     * etykiety pasującej do podanego kodu.
     *
     * @param campaignId      UUID kampanii (może być null – kontakt bez kampanii)
     * @param dispositionCode surowy kod dyspozycji (może być null – brak dyspozycji)
     * @param tenantId        UUID tenanta – wymagany do izolacji danych
     * @return czytelna etykieta dyspozycji lub null gdy nie można jej wyznaczyć
     */
    private String resolveDispositionLabel(UUID campaignId, String dispositionCode, UUID tenantId) {
        if (campaignId == null || dispositionCode == null) {
            return null;
        }
        return campaignRepository.findById(campaignId, tenantId)
                .map(campaign -> findLabelInDispositionCodes(campaign.getDispositionCodes(), dispositionCode))
                .orElse(null);
    }

    /**
     * Batch lookup etykiet dyspozycji dla listy kontaktów – unika problemu N+1 zapytań.
     *
     * <p>Zbiera unikalne {@code campaignId} ze wszystkich kontaktów, pobiera kampanie
     * jednym zapytaniem, a następnie buduje mapę {@code campaignId → Map<code, label>}
     * umożliwiającą O(1) lookup dla każdego kontaktu.
     *
     * @param contacts lista kontaktów
     * @param tenantId UUID tenanta
     * @return mapa {@code campaignId → (code → label)} dla wszystkich kampanii z listy
     */
    private Map<UUID, Map<String, String>> resolveDispositionLabelsForContacts(
            List<Contact> contacts, UUID tenantId) {

        List<UUID> campaignIds = contacts.stream()
                .map(Contact::getCampaignId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (campaignIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Map<String, String>> result = new HashMap<>();
        campaignRepository.findAllByIds(campaignIds, tenantId).forEach(campaign -> {
            Map<String, String> codeToLabel = new HashMap<>();
            if (campaign.getDispositionCodes() != null) {
                for (Map<String, Object> entry : campaign.getDispositionCodes()) {
                    Object code = entry.get("code");
                    Object label = entry.get("label");
                    if (code instanceof String codeStr && label instanceof String labelStr) {
                        codeToLabel.put(codeStr, labelStr);
                    }
                }
            }
            result.put(campaign.getCampaignId(), codeToLabel);
        });

        return result;
    }

    /**
     * Przeszukuje listę definicji dyspozycji kampanii i zwraca etykietę dla podanego kodu.
     *
     * @param dispositionCodes lista map {@code {"code": ..., "label": ...}}
     * @param dispositionCode  kod do wyszukania
     * @return etykieta lub null gdy kod nie istnieje w liście
     */
    private String findLabelInDispositionCodes(
            List<Map<String, Object>> dispositionCodes, String dispositionCode) {
        if (dispositionCodes == null) {
            return null;
        }
        for (Map<String, Object> entry : dispositionCodes) {
            if (dispositionCode.equals(entry.get("code"))) {
                Object label = entry.get("label");
                return label instanceof String s ? s : null;
            }
        }
        return null;
    }

    /**
     * Formatuje wyświetlaną nazwę agenta.
     *
     * <p>Zwraca {@code firstName + ' ' + lastName} gdy oba pola są niepuste,
     * w przeciwnym razie email użytkownika.
     *
     * @param user encja użytkownika
     * @return wyświetlana nazwa
     */
    private String formatAgentName(AppUser user) {
        String firstName = user.getFirstName();
        String lastName = user.getLastName();
        if (firstName != null && !firstName.isBlank()
                && lastName != null && !lastName.isBlank()) {
            return firstName + " " + lastName;
        }
        return user.getEmail();
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

        Map<UUID, String> agentNames = resolveAgentNamesForContacts(contacts, tenantId);
        Map<UUID, Map<String, String>> dispositionLabels = resolveDispositionLabelsForContacts(contacts, tenantId);
        List<ContactResponse> content = contacts.stream()
                .map(c -> {
                    String label = c.getCampaignId() != null && c.getDispositionCode() != null
                            ? dispositionLabels.getOrDefault(c.getCampaignId(), Map.of())
                                              .get(c.getDispositionCode())
                            : null;
                    return ContactResponse.from(c, agentNames.get(c.getAgentId()), label);
                })
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
        // Exception: TRANSFERRED contacts relax the ownership check (BUG #4 fix).
        // Attended transfer scenario: after bridge(), the original contact has agentId=Agent1 and
        // status=TRANSFERRED. Agent2 receives a new contact via CALL_BRIDGE_COMPLETE WebSocket event,
        // but until that event is processed by the frontend, Agent2 may attempt disposition on the
        // TRANSFERRED contact. Only TRANSFERRED relaxes ownership; other statuses still enforce it.
        boolean ownershipRelaxed = "TRANSFERRED".equals(contact.getStatus());
        if (isAgent && contact.getAgentId() != null && !userId.equals(contact.getAgentId())
                && !ownershipRelaxed) {
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

        Map<UUID, String> agentNames = resolveAgentNamesForContacts(contacts, tenantId);
        Map<UUID, Map<String, String>> dispositionLabels = resolveDispositionLabelsForContacts(contacts, tenantId);
        List<ContactResponse> content = contacts.stream()
                .map(c -> {
                    String label = c.getCampaignId() != null && c.getDispositionCode() != null
                            ? dispositionLabels.getOrDefault(c.getCampaignId(), Map.of())
                                              .get(c.getDispositionCode())
                            : null;
                    return ContactResponse.from(c, agentNames.get(c.getAgentId()), label);
                })
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
    // Historia etapów kontaktu (BE-073)
    // =========================================================================

    /**
     * Pobiera historię etapów kontaktu posortowaną chronologicznie.
     *
     * <p>AGENT może przeglądać historię tylko własnych kontaktów.
     * SUPERVISOR/ADMIN mają dostęp do historii wszystkich kontaktów tenanta.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param userId    UUID zalogowanego użytkownika (weryfikacja dla AGENT)
     * @param isAgent   true gdy zalogowany użytkownik jest AGENT
     * @return lista zdarzeń posortowana chronologicznie (może być pusta)
     * @throws EntityNotFoundException   HTTP 404 gdy kontakt nie istnieje lub inny tenant
     * @throws InvalidOperationException HTTP 409 gdy AGENT próbuje pobrać historię cudzego kontaktu
     */
    @Transactional(readOnly = true)
    public List<ContactEventResponse> getContactEvents(
            UUID contactId, UUID tenantId, UUID userId, boolean isAgent) {

        Contact contact = findContactOrThrow(contactId, tenantId);

        if (isAgent && contact.getAgentId() != null && !userId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Agent może przeglądać tylko historię własnych kontaktów: " + contactId);
        }

        return contactEventService.getHistory(contactId, tenantId)
                .stream()
                .map(ContactEventResponse::from)
                .toList();
    }

    // =========================================================================
    // Transfer połączenia (BE-077)
    // =========================================================================

    /**
     * Inicjuje transfer aktywnego połączenia telefonicznego.
     *
     * <p>Kolejność kroków:
     * <ol>
     *   <li>Walidacja domenowego {@link TransferRequest} (kombinacja targetType + transferType).</li>
     *   <li>Weryfikacja istnienia kontaktu i przynależności do tenanta (HTTP 404 / 403).</li>
     *   <li>Weryfikacja własności – agent musi obsługiwać ten kontakt (HTTP 403).</li>
     *   <li>Weryfikacja stanu kontaktu – musi być ACTIVE (HTTP 409).</li>
     *   <li>Wywołanie {@link TelephonyAdapter#initiateTransfer(String, TransferRequest)}.</li>
     *   <li>Zapis zdarzenia TRANSFER do historii kontaktu (nie przerywa przepływu przy błędzie).</li>
     * </ol>
     *
     * <p>Metoda NIE aktualizuje statusu kontaktu w DB — adapter może to zrobić asynchronicznie
     * po potwierdzeniu przez provider telefonii (np. webhook Twilio).
     *
     * @param callId   identyfikator sesji telefonicznej (Twilio SID lub mock UUID)
     * @param req      żądanie transferu z typem celu i odpowiednimi polami
     * @param tenantId UUID tenanta z TenantContext
     * @param userId   UUID zalogowanego agenta z TenantContext
     * @return nowa sesja połączenia zwrócona przez adapter
     * @throws IllegalArgumentException  HTTP 422 gdy kombinacja targetType+transferType jest niedozwolona
     *                                   lub brakuje wymaganego pola (np. phoneNumber dla PHONE)
     * @throws EntityNotFoundException   HTTP 404 gdy kontakt powiązany z callId nie istnieje lub inny tenant
     * @throws CrossTenantAccessException HTTP 403 gdy kontakt należy do innego tenanta
     * @throws ConflictException         HTTP 409 gdy kontakt nie jest w statusie ACTIVE
     * @throws InvalidOperationException HTTP 409 gdy agent nie jest właścicielem kontaktu
     */
    /**
     * Inicjuje transfer – faza walidacji (read-only transakcja, brak I/O zewnętrznego).
     *
     * <p>Walidacja domenowa + weryfikacja stanu kontaktu. Zwraca kontakt gdy warunki
     * są spełnione – lub rzuca wyjątek domenowy.
     */
    @Transactional(readOnly = true)
    protected Contact validateTransferPreconditions(String callId, TransferCallRequest req,
                                                     UUID tenantId, UUID userId) {
        UUID contactId = resolveContactIdFromCallId(callId, tenantId);
        if (contactId == null) {
            throw new EntityNotFoundException(
                    "Kontakt powiązany z callId nie istnieje lub nie należy do tego tenanta: " + callId);
        }

        Contact contact = contactRepository.findById(contactId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kontakt nie istnieje lub nie należy do tego tenanta: " + contactId));

        if (!tenantId.equals(contact.getTenantId())) {
            throw new CrossTenantAccessException(contactId, tenantId, contact.getTenantId());
        }

        if (contact.getAgentId() == null || !userId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Agent nie jest właścicielem kontaktu (transfer niedozwolony): " + contactId);
        }

        if (!"ACTIVE".equals(contact.getStatus())) {
            throw new ConflictException(
                    "Transfer możliwy tylko dla kontaktu w statusie ACTIVE " +
                    "(aktualny status: " + contact.getStatus() + "): " + contactId);
        }

        return contact;
    }

    /**
     * Zapisuje zdarzenie TRANSFER w ramach własnej transakcji (odseparowane od I/O adaptera).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void persistTransferEvent(UUID contactId, UUID tenantId, TransferCallRequest req) {
        recordTransferEvent(contactId, tenantId, req);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CallSession initiateTransfer(String callId, TransferCallRequest req,
                                        UUID tenantId, UUID userId) {

        // 1. Walidacja domenowego obiektu (kombinacje targetType + transferType)
        TransferRequest domainReq = new TransferRequest(
                req.transferType(), req.targetType(),
                req.phoneNumber(), req.agentId(), req.queueId());
        domainReq.validate();

        // 2-5. Walidacja kontaktu i uprawnień (read-only transakcja)
        Contact contact = validateTransferPreconditions(callId, req, tenantId, userId);
        UUID contactId = contact.getContactId();

        // BE-083: Guard – transfer OUTBOUND → QUEUE jest niedozwolony
        // Dla kampanii wychodzących nie ma kolejki do przekierowania; dostępny jest tylko
        // transfer do agenta lub na numer telefonu.
        if ("OUTBOUND".equals(contact.getDirection())
                && TransferTargetType.QUEUE == domainReq.targetType()) {
            throw new InvalidOperationException(
                    "Transfer do kolejki jest niedozwolony dla połączeń wychodzących (outbound). " +
                    "Dla kampanii wychodzących dostępny jest wyłącznie transfer do agenta lub na numer telefonu.");
        }

        // 6. Wywołaj adapter (poza transakcją – zewnętrzne I/O)
        log.info("[ContactService] Inicjowanie transferu: callId={}, targetType={}, transferType={}, " +
                 "contactId={}, agentId={}, tenant={}",
                callId, req.targetType(), req.transferType(), contactId, userId, tenantId);

        CallSession resultSession = telephonyAdapter.initiateTransfer(callId, domainReq);

        // 7. Aktualizuj historię w zależności od typu transferu
        if (req.transferType() != TelephonyAdapter.TransferType.ATTENDED) {
            // Blind transfer: agent kończy obsługę tutaj
            contactEventService.closeAgent(contactId, tenantId);
            // 8. Zapisz zdarzenie TRANSFER w osobnej transakcji
            persistTransferEvent(contactId, tenantId, req);
        } else {
            // Attended transfer: zamknij bieżący etap AGENT (Agent1) i otwórz CONSULTING.
            // Po bridge lub anulowaniu konsultacji etap AGENT zostanie ponownie otwarty / zamknięty.
            contactEventService.closeAgent(contactId, tenantId);
            String consultTarget = req.agentId() != null ? req.agentId().toString()
                                 : (req.phoneNumber() != null ? req.phoneNumber() : "");
            Map<String, Object> consultMeta = new HashMap<>();
            if (req.agentId() != null) {
                consultMeta.put("target_type", "AGENT");
                appUserRepository.findByIdAndTenantIdAndDeletedFalse(req.agentId(), tenantId)
                        .ifPresent(u -> consultMeta.put("target_agent_name",
                                u.getFirstName() + " " + u.getLastName()));
            } else if (req.phoneNumber() != null) {
                consultMeta.put("target_type", "PHONE");
            }
            contactEventService.openConsulting(contactId, tenantId, consultTarget, consultMeta);
        }

        log.info("[ContactService] Transfer zainicjowany: callId={}, contactId={}, " +
                 "targetType={}, newSessionCallId={}",
                callId, contactId, req.targetType(), resultSession.getCallId());

        return resultSession;
    }

    /**
     * Próbuje rozwiązać contactId na podstawie callId.
     *
     * <p>Logika rozwiązywania:
     * <ol>
     *   <li>Jeśli callId jest poprawnym UUID – traktuje go jako contactId bezpośrednio.</li>
     *   <li>W przeciwnym razie (np. Twilio CA...) – szuka kontaktu przez
     *       {@code channel_metadata->>'sip_call_id'}.</li>
     * </ol>
     *
     * @param callId   identyfikator sesji (UUID lub Twilio SID)
     * @param tenantId UUID tenanta
     * @return UUID kontaktu lub null gdy nie znaleziono
     */
    private UUID resolveContactIdFromCallId(String callId, UUID tenantId) {
        try {
            return UUID.fromString(callId);
        } catch (IllegalArgumentException ignored) {
            // callId nie jest UUID – szukamy przez sip_call_id w channel_metadata
        }
        return contactRepository.findContactIdByCallSid(callId, tenantId).orElse(null);
    }

    /**
     * Rejestruje zdarzenie TRANSFER w historii kontaktu.
     *
     * <p>Metadane zdarzenia zawierają transfer_type, target_type i identyfikator celu.
     * Błąd zapisu jest logowany i ignorowany – nie przerywa głównego przepływu.
     */
    private void recordTransferEvent(UUID contactId, UUID tenantId, TransferCallRequest req) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put(TelephonyAdapter.META_TRANSFER_TYPE, req.transferType().name());
            meta.put(TelephonyAdapter.META_TARGET_TYPE,   req.targetType().name());

            switch (req.targetType()) {
                case PHONE ->
                        meta.put("target", req.phoneNumber());
                case AGENT -> {
                    meta.put(TelephonyAdapter.META_TARGET_AGENT_ID,
                            req.agentId() != null ? req.agentId().toString() : null);
                    if (req.agentId() != null) {
                        appUserRepository.findByIdAndTenantIdAndDeletedFalse(req.agentId(), tenantId)
                                .ifPresent(u -> meta.put("target_agent_name",
                                        u.getFirstName() + " " + u.getLastName()));
                    }
                }
                case QUEUE -> {
                    meta.put(TelephonyAdapter.META_TARGET_QUEUE_ID,
                            req.queueId() != null ? req.queueId().toString() : null);
                    if (req.queueId() != null) {
                        queueRepository.findByIdAndTenantId(req.queueId(), tenantId)
                                .ifPresent(q -> meta.put("target_queue_name", q.getName()));
                    }
                }
            }

            contactEventService.recordTransfer(
                    contactId, tenantId,
                    resolveTransferTarget(req),
                    req.transferType().name(),
                    null,  // targetAgentName – opcjonalne, nie pobieramy dla uproszczenia
                    meta   // przekazujemy zbudowane metadane
            );
        } catch (Exception e) {
            log.warn("[ContactService] Błąd zapisu zdarzenia TRANSFER: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }

    /** Zwraca czytelny identyfikator celu transferu do przechowania w historii. */
    private String resolveTransferTarget(TransferCallRequest req) {
        return switch (req.targetType()) {
            case PHONE -> req.phoneNumber();
            case AGENT -> req.agentId() != null ? req.agentId().toString() : "unknown";
            case QUEUE -> req.queueId() != null ? req.queueId().toString() : "unknown";
        };
    }

    // =========================================================================
    // Bridge calls – finalizacja attended transfer (BE-078)
    // =========================================================================

    /**
     * Finalizuje attended transfer łącząc dwie nogi połączenia (bridge).
     *
     * <p>Wywoływana po tym jak agent skonsultował się z drugą stroną i potwierdza przekazanie.
     * {@code callId} to oryginalne połączenie z klientem (status ON_HOLD lub ACTIVE),
     * {@code secondCallId} to druga noga transferu (konsultacyjna) do docelowego agenta/numeru.
     *
     * <p>Kroki:
     * <ol>
     *   <li>Znajdź kontakt powiązany z {@code callId} – zweryfikuj tenant i własność agenta.</li>
     *   <li>Sprawdź że stan sesji jest kompatybilny z bridge (adapter rzuci {@link TelephonyAdapter.TelephonyException}).</li>
     *   <li>Wywołaj {@link TelephonyAdapter#bridgeCalls(String, String)}.</li>
     *   <li>Zamknij otwarte etapy CONSULTING i ON_HOLD w historii kontaktu.</li>
     *   <li>Zapisz zdarzenie TRANSFER (bridge finalizuje attended transfer).</li>
     * </ol>
     *
     * @param callId       identyfikator pierwotnej sesji (UUID kontaktu lub Twilio SID)
     * @param secondCallId identyfikator drugiej nogi transferu
     * @param tenantId     UUID tenanta z TenantContext
     * @param userId       UUID zalogowanego agenta z TenantContext
     * @throws EntityNotFoundException    HTTP 404 gdy kontakt powiązany z callId nie istnieje
     * @throws CrossTenantAccessException HTTP 403 gdy kontakt należy do innego tenanta
     * @throws InvalidOperationException  HTTP 409 gdy agent nie jest właścicielem kontaktu
     * @throws TelephonyAdapter.TelephonyException HTTP 409 gdy sesja nie jest w stanie kompatybilnym z bridge
     */
    @Transactional
    public void bridgeCalls(String callId, String secondCallId, UUID tenantId, UUID userId) {

        // 1. Znajdź kontakt – najpierw próbujemy callId jako contactId (UUID),
        //    dla Twilio CA... szukamy przez channel_metadata->>'sip_call_id'.
        UUID contactId = resolveContactIdFromCallId(callId, tenantId);
        if (contactId == null) {
            throw new EntityNotFoundException(
                    "Kontakt powiązany z callId nie istnieje lub nie należy do tego tenanta: " + callId);
        }

        Contact contact = contactRepository.findById(contactId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kontakt nie istnieje lub nie należy do tego tenanta: " + contactId));

        // 2. Guard cross-tenant
        if (!tenantId.equals(contact.getTenantId())) {
            throw new CrossTenantAccessException(contactId, tenantId, contact.getTenantId());
        }

        // 3. Weryfikacja własności – tylko właściciel kontaktu może finalizować bridge
        if (contact.getAgentId() == null || !userId.equals(contact.getAgentId())) {
            throw new InvalidOperationException(
                    "Agent nie jest właścicielem kontaktu (bridge niedozwolony): " + contactId);
        }

        // 3b. Weryfikacja cross-tenant dla drugiej nogi – pobierz sesję do późniejszego użycia
        CallSession secondSession = telephonyAdapter.getSession(secondCallId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Sesja drugiej nogi nie istnieje: " + secondCallId));
        if (!tenantId.equals(secondSession.getTenantId())) {
            throw new CrossTenantAccessException(contactId, tenantId, secondSession.getTenantId());
        }

        log.info("[ContactService] Inicjowanie bridge: callId={}, secondCallId={}, " +
                 "contactId={}, agentId={}, tenant={}",
                callId, secondCallId, contactId, userId, tenantId);

        // 4. Wygeneruj newContactId PRZED wywołaniem adaptera – adapter zapisze go w sesji Redis
        UUID agent2Id = secondSession.getAgentId();

        // Utwórz nowy kontakt dla Agent2 PRZED bridgeCalls, żeby adapter mógł użyć newContactId
        // przy tworzeniu indeksu Redis (contact-session-index:{newContactId}).
        UUID newContactId = null;
        if (agent2Id != null) {
            Contact newContact = createTransferContact(contact, agent2Id, secondCallId, tenantId, UUID.randomUUID());
            newContactId = newContact.getContactId();
        }

        // 5. Wywołaj adapter – redirect klienta + Agent2 do nowej konferencji, aktualizacja sesji Redis
        //    Adapter sam aktualizuje sesję Redis drugiej nogi (contactId, conferenceName, customerCallSid)
        UUID finalNewContactId = newContactId != null ? newContactId : UUID.randomUUID();
        telephonyAdapter.bridgeCalls(callId, secondCallId, finalNewContactId);

        // 6. Zamknij etapy CONSULTING, AGENT i ON_HOLD na oryginalnym kontakcie
        contactEventService.closeConsulting(contactId, tenantId);
        contactEventService.closeAgent(contactId, tenantId);
        contactEventService.closeHold(contactId, tenantId);

        // 7. Zapisz zdarzenie TRANSFER na oryginalnym kontakcie
        Map<String, Object> transferMeta = new HashMap<>();
        transferMeta.put("target_type", "AGENT");
        if (agent2Id != null) {
            transferMeta.put("target_agent_id", agent2Id.toString());
            appUserRepository.findByIdAndTenantIdAndDeletedFalse(agent2Id, tenantId)
                    .ifPresent(u -> transferMeta.put("target_agent_name",
                            u.getFirstName() + " " + u.getLastName()));
        }
        contactEventService.recordTransfer(
                contactId, tenantId,
                agent2Id != null ? agent2Id.toString() : secondCallId,
                TelephonyAdapter.TransferType.ATTENDED.name(),
                null,
                transferMeta
        );

        // 8. Otwórz etap AGENT na nowym kontakcie i powiadom Agent2
        if (agent2Id != null) {
            // Otwórz etap AGENT na nowym kontakcie (sesja Redis już zaktualizowana przez bridgeCalls)
            contactEventService.openAgent(finalNewContactId, tenantId, agent2Id, null);

            // Powiadom Agent2 przez WS aby zaktualizował swoje session.contactId
            eventPublisher.publishBridgeComplete(
                    secondCallId, finalNewContactId, tenantId, agent2Id,
                    secondSession.getFrom(), secondSession.getTo());
        }

        log.info("[ContactService] Bridge wykonany: callId={}, secondCallId={}, contactId={}",
                callId, secondCallId, contactId);
    }

    /**
     * Tworzy nowy kontakt dla agenta docelowego po attended transfer (bridge zakończony).
     *
     * <p>Kopiuje podstawowe dane z oryginalnego kontaktu i powiązuje z SID drugiej nogi.
     *
     * @param original      oryginalny kontakt (klienta)
     * @param agentId       UUID Agent2
     * @param secondCallSid SID drugiej nogi (Twilio CA_... lub mock)
     * @param tenantId      UUID tenanta
     * @return nowo utworzony kontakt Agent2
     */
    private Contact createTransferContact(Contact original, UUID agentId,
                                           String secondCallSid, UUID tenantId, UUID newContactId) {
        Instant now = Instant.now();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sip_call_id", secondCallSid);

        Contact newContact = Contact.builder()
                .contactId(newContactId)
                .tenantId(tenantId)
                .channel(original.getChannel())
                .direction(original.getDirection())
                .remoteAddress(original.getRemoteAddress())
                .status("ACTIVE")
                .agentId(agentId)
                .customerId(original.getCustomerId())
                .queueId(original.getQueueId())
                .createdAt(now)
                .startedAt(now)
                .assignedAt(now)
                .queuedAt(now)
                .channelMetadata(metadata)
                .transferredFromContactId(original.getContactId())
                .build();

        contactRepository.insert(newContact);

        log.info("[ContactService] Nowy kontakt dla Agent2 po attended transfer: " +
                 "newContactId={}, agentId={}, secondCallSid={}", newContactId, agentId, secondCallSid);
        return newContact;
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
