package com.contactcenter.domain.contact;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.contact.dto.ContactEventResponse;
import com.contactcenter.api.contact.dto.ContactFilterParams;
import com.contactcenter.api.contact.dto.ContactRecordingUrlResponse;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.CreateContactRequest;
import com.contactcenter.api.contact.dto.DispositionRequest;
import com.contactcenter.api.contact.dto.EmailPreviewResponse;
import com.contactcenter.api.contact.dto.UpdateContactRequest;
import com.contactcenter.api.telephony.dto.TransferCallRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.telephony.TransferRequest;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
public interface ContactService {

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
    ContactResponse createContact(CreateContactRequest request, UUID tenantId);

    /**
     * Tworzy nowy kontakt, z opcjonalnym wejściem do IVR.
     *
     * <p>Metoda wewnętrzna używana przez {@code TwilioWebhookController} dla połączeń voice
     * kierowanych do drzewa IVR. Nie jest częścią publicznego API {@code POST /api/contacts}.
     *
     * <ul>
     *   <li>{@code ivrEntry=false} (domyślnie) – kontakt tworzony ze statusem {@code QUEUED}
     *       i {@code queuedAt=now}, jak dotychczas (chat/email/social, voice z routingiem
     *       bezpośrednio do kolejki bez IVR, lub kontakty inicjowane przez agenta).</li>
     *   <li>{@code ivrEntry=true} – kontakt tworzony ze statusem {@code IVR} i {@code queuedAt=null}.
     *       {@code queuedAt} zostanie ustawione dopiero przy faktycznym transferze do kolejki
     *       agentów (zob. {@code IvrEngineService.executeQueueTransfer()}), aby KPI
     *       "Śr. czas oczekiwania" nie liczyło czasu spędzonego w IVR.</li>
     * </ul>
     *
     * @param request  dane nowego kontaktu
     * @param tenantId UUID tenanta z TenantContext
     * @param ivrEntry true gdy połączenie voice jest kierowane do drzewa IVR
     * @return DTO nowo utworzonego kontaktu
     */
    ContactResponse createContact(CreateContactRequest request, UUID tenantId, boolean ivrEntry);

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
    ContactResponse getContact(UUID contactId, UUID tenantId, UUID userId, boolean isAgent);

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
    PagedResponse<ContactResponse> listContacts(ContactFilterParams params,
                                                 UUID tenantId,
                                                 UUID userId,
                                                 boolean isAgent);

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
    ContactResponse updateContact(UUID contactId, UpdateContactRequest request,
                                   UUID tenantId, UUID userId, boolean isAgent);

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
    ContactResponse setDisposition(UUID contactId, DispositionRequest request,
                                    UUID tenantId, UUID userId, boolean isAgent);

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
    ContactResponse assignAgent(UUID contactId, UUID tenantId, UUID agentId);

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
    ContactResponse acceptContact(UUID contactId, UUID tenantId, UUID agentId);

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
    ContactResponse abandonContact(UUID contactId, UUID tenantId, UUID agentId);

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
    PagedResponse<ContactResponse> getCustomerHistory(UUID customerId, UUID tenantId,
                                                       int page, int size);

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
    ContactRecordingUrlResponse getRecordingUrl(UUID contactId, UUID tenantId,
                                                 UUID userId, boolean isAgent);

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
    EmailPreviewResponse getEmailPreview(UUID contactId, UUID tenantId);

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
    List<ContactEventResponse> getContactEvents(UUID contactId, UUID tenantId, UUID userId, boolean isAgent);

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
    CallSession initiateTransfer(String callId, TransferCallRequest req, UUID tenantId, UUID userId);

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
    void bridgeCalls(String callId, String secondCallId, UUID tenantId, UUID userId);

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
    void terminateStaleQueuedContacts(UUID tenantId);

    // =========================================================================
    // Encapsulation pass (pkt 9 wzorca refaktoru domenowego) – metody delegujące
    // do ContactRepository / ContactAiSummaryRepository / ContactTranscriptionRepository
    // dla konsumentów z innych pakietów.
    // =========================================================================

    /**
     * Pobiera encję kontaktu po ID z zabezpieczeniem cross-tenant.
     *
     * <p>Odpowiednik {@code ContactRepository.findById(UUID, UUID)} – w odróżnieniu
     * od {@link #getContact} zwraca encję domenową (lub {@code Optional.empty()}),
     * nie DTO, i nie weryfikuje uprawnień AGENT.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return Optional z encją kontaktu lub empty gdy nie istnieje / inny tenant
     */
    Optional<Contact> findContactEntity(UUID contactId, UUID tenantId);

    /**
     * Pobiera listę encji kontaktów dla danego klienta (bez paginacji DTO).
     *
     * <p>Odpowiednik {@code ContactRepository.findByCustomerId(UUID, UUID, int, int)}
     * – w odróżnieniu od {@link #getCustomerHistory} zwraca encje domenowe, nie DTO.
     * Używane przez {@code GdprService} do anonimizacji/usuwania danych klienta.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @param page       numer strony (0-based)
     * @param size       rozmiar strony
     * @return lista encji kontaktów klienta posortowana od najnowszych
     */
    List<Contact> findContactsByCustomerId(UUID customerId, UUID tenantId, int page, int size);

    /**
     * Zwraca Twilio Call SID przechowywany w {@code channel_metadata->>'sip_call_id'}
     * dla danego contactId.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return Optional z Twilio CallSid lub empty gdy kontakt nie istnieje lub brak sip_call_id
     */
    Optional<String> findCallSidByContactId(UUID contactId, UUID tenantId);

    /**
     * Zwraca contactId dla kontaktu z danym Twilio Call SID w {@code channel_metadata->>'sip_call_id'}.
     *
     * @param callSid  Twilio Call SID (CA...)
     * @param tenantId UUID tenanta
     * @return Optional z UUID contactId lub empty gdy brak kontaktu dla tego callSid
     */
    Optional<UUID> findContactIdByCallSid(String callSid, UUID tenantId);

    /**
     * Zapisuje Twilio Conference SID do {@code channel_metadata->>'conference_sid'} kontaktu
     * identyfikowanego przez {@code sip_call_id} (Call SID).
     *
     * @param callSid       Twilio Call SID (CA...) – identyfikuje rekord contact przez sip_call_id
     * @param conferenceSid Twilio Conference SID (CF...) – zapisywany do channel_metadata
     * @param tenantId      UUID tenanta
     */
    void updateConferenceSidInMetadata(String callSid, String conferenceSid, UUID tenantId);

    /**
     * Uzupełnia {@code channel_metadata->>'sip_call_id'} dla kontaktu wychodzącego (OUTBOUND),
     * który został utworzony przed inicjacją połączenia Twilio i nie ma jeszcze callSid.
     *
     * @param contactId UUID rekordu contact (z sesji połączenia)
     * @param callSid   Twilio Call SID (CA...) do zapisania jako sip_call_id
     * @param tenantId  UUID tenanta
     */
    void backfillCallSidInMetadata(UUID contactId, String callSid, UUID tenantId);

    /**
     * Tworzy nowy kontakt przez natywny INSERT (encja domenowa, bez przejścia przez DTO/walidację API).
     *
     * <p>Odpowiednik {@code ContactRepository.insert(Contact)} – używane przez adaptery telefonii
     * i social/email, które same budują encję {@link Contact} przed zapisem.
     *
     * @param contact encja kontaktu do zapisu (contactId musi być ustawiony)
     * @return przekazana encja (trigger oblicza duration_seconds przy UPDATE)
     */
    Contact insertContact(Contact contact);

    /**
     * Aktualizuje istniejącą encję kontaktu przez natywny UPDATE.
     *
     * <p>Odpowiednik {@code ContactRepository.update(Contact)} – używane przez konsumentów,
     * którzy modyfikują encję {@link Contact} otrzymaną z {@link #findContactEntity} i zapisują ją
     * bez przechodzenia przez pełny cykl {@code updateContact}/DTO.
     *
     * @param contact encja kontaktu z wypełnionym contactId i startedAt
     * @return liczba zaktualizowanych wierszy (0 = kontakt nie istnieje)
     */
    int updateContactEntity(Contact contact);

    /**
     * Ustawia {@code campaign_contact_record_id} na kontakcie wychodzącym dialera.
     *
     * @param contactId UUID kontaktu
     * @param recordId  UUID rekordu campaign_contact
     * @param tenantId  UUID tenanta (cross-tenant safety)
     * @return liczba zaktualizowanych wierszy
     */
    int updateCampaignContactRecordId(UUID contactId, UUID recordId, UUID tenantId);

    /**
     * Zwraca listę kontaktów powiązanych z danym rekordem listy kampanii.
     *
     * @param campaignContactRecordId UUID rekordu campaign_contact
     * @param tenantId                UUID tenanta
     * @return lista kontaktów posortowana od najnowszych
     */
    List<Contact> findByCampaignContactRecordId(UUID campaignContactRecordId, UUID tenantId);

    /**
     * Zwraca listę kontaktów powiązanych z danym callbackiem.
     *
     * @param callbackId UUID callbacku (scheduled_callback.callback_id)
     * @param tenantId   UUID tenanta
     * @return lista kontaktów z ustawionym callback_id równym podanemu callbackId
     */
    List<Contact> findByCallbackId(UUID callbackId, UUID tenantId);

    /**
     * Zwraca listę kontaktów powstałych z transferu kolejkowego danego kontaktu.
     *
     * @param transferredFromContactId UUID kontaktu źródłowego (contact.contact_id)
     * @param tenantId                 UUID tenanta
     * @return lista kontaktów z ustawionym transferred_from_contact_id równym podanemu UUID
     */
    List<Contact> findByTransferredFromContactId(UUID transferredFromContactId, UUID tenantId);

    /**
     * Pobiera kontakty ze statusem QUEUED dla danego tenanta (oczekujące na przydzielenie agenta).
     *
     * @param tenantId UUID tenanta
     * @return lista oczekujących kontaktów posortowana od najstarszych (queuedAt ASC)
     */
    List<Contact> findQueuedContacts(UUID tenantId);

    /**
     * Aktualizuje status kontaktu po zdarzeniu telefonicznym (hangup → COMPLETED).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (zabezpieczenie cross-tenant)
     * @param newStatus nowy status kontaktu (np. "COMPLETED", "ABANDONED")
     * @param endedAt   czas zakończenia (może być null)
     */
    void updateContactStatusOnTelephonyEvent(UUID contactId, UUID tenantId, String newStatus, Instant endedAt);

    /**
     * Atomicznie ustawia status kontaktu tylko jeśli nie jest już w stanie terminalnym.
     *
     * @return true jeśli status został zmieniony
     */
    boolean updateContactStatusIfNotTerminal(UUID contactId, UUID tenantId, String newStatus, Instant endedAt);

    /**
     * Identyczna logika co {@link #updateContactStatusOnTelephonyEvent}, ale z propagacją
     * {@code REQUIRES_NEW} – zawiesza zewnętrzną transakcję i commituje natychmiast.
     */
    void updateContactStatusRequiresNew(UUID contactId, UUID tenantId, String newStatus, Instant endedAt);

    /**
     * Aktualizuje {@code queue_id} kontaktu będącego w statusie QUEUED lub IVR.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (cross-tenant safety)
     * @param queueId   UUID docelowej kolejki wyznaczonej przez węzeł IVR
     * @return liczba zaktualizowanych wierszy (0 = kontakt nie istnieje, ma inny tenant lub inny status)
     */
    int updateQueueId(UUID contactId, UUID tenantId, UUID queueId);

    /**
     * Aktualizuje URL nagrania dla podanego kontaktu.
     *
     * @param contactId    UUID kontaktu
     * @param tenantId     UUID tenanta (do weryfikacji RLS i assertSameTenant)
     * @param recordingUrl ścieżka S3 w formacie {@code /{tenantId}/{year}/{month}/{contactId}.mp3}
     */
    void updateRecordingUrl(UUID contactId, UUID tenantId, String recordingUrl);

    /**
     * Pobiera ścieżkę S3 nagrania dla danego kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (wymagany dla RLS)
     * @return Optional z ścieżką S3 lub empty jeśli nagranie nie istnieje
     */
    Optional<String> findRecordingUrl(UUID contactId, UUID tenantId);

    /**
     * Usuwa URL nagrania (ustawia NULL) dla podanego kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    void clearRecordingUrl(UUID contactId, UUID tenantId);

    /**
     * Pobiera listę kontaktów z nagraniami, których data zakończenia jest starsza niż podana data.
     *
     * @param tenantId        UUID tenanta
     * @param cutoffTimestamp timestamp graniczny (ended_at < cutoffTimestamp)
     * @param limit           maksymalna liczba rekordów do przetworzenia w jednej iteracji
     * @return lista par (contactId, recordingUrl) do usunięcia
     */
    List<ContactRecordingEntry> findExpiredRecordings(UUID tenantId, Instant cutoffTimestamp, int limit);

    /**
     * Pobiera listę kluczy S3 nagrań dla wszystkich kontaktów danego klienta.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @return lista kluczy S3 (recording_url) – może być pusta gdy brak nagrań
     */
    List<String> findRecordingUrlsByCustomer(UUID customerId, UUID tenantId);

    /**
     * Pobiera wszystkie tenantId, które posiadają kontakty z nagraniami.
     *
     * <p><strong>WYŁĄCZNIE DLA SCHEDULED JOB ({@code RecordingRetentionJob}) – POMIJA RLS.</strong>
     *
     * @return lista unikalnych tenant_id posiadających nagrania
     * @throws IllegalStateException gdy wywoływana z aktywnym TenantContext (błędne użycie)
     */
    List<UUID> findTenantsWithRecordings();

    /**
     * Pobiera paginowane, zagregowane statystyki agentów za dany zakres dat.
     *
     * @param tenantId UUID tenanta
     * @param agentId  filtr po agencie (null = wszystkie)
     * @param queueId  filtr po kolejce (null = wszystkie)
     * @param channel  filtr po kanale (null = wszystkie)
     * @param dateFrom data początku zakresu (włącznie)
     * @param dateTo   data końca zakresu (wyłącznie)
     * @param offset   offset paginacji
     * @param size     rozmiar strony
     * @return lista wierszy agregacji jako Object[]
     */
    List<Object[]> findAgentReportRows(UUID tenantId, UUID agentId, UUID queueId, String channel,
                                        java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
                                        int offset, int size);

    /**
     * Zlicza łączną liczbę wierszy raportu agentów – do metadanych paginacji.
     *
     * @param tenantId UUID tenanta
     * @param agentId  filtr po agencie (null = wszystkie)
     * @param queueId  filtr po kolejce (null = wszystkie)
     * @param channel  filtr po kanale (null = wszystkie)
     * @param dateFrom data początku zakresu
     * @param dateTo   data końca zakresu
     * @return łączna liczba wierszy grupowania (agent + dzień + kanał)
     */
    long countAgentReportRows(UUID tenantId, UUID agentId, UUID queueId, String channel,
                               java.time.LocalDate dateFrom, java.time.LocalDate dateTo);

    /**
     * Zwraca listę kontaktów, w których {@code channel_metadata->>'key' = value}.
     *
     * @param key      klucz JSON w channel_metadata (np. {@code "inboundContactId"})
     * @param value    oczekiwana wartość tekstowa
     * @param tenantId UUID tenanta (cross-tenant safety)
     * @return lista kontaktów spełniających kryterium (posortowana od najnowszych)
     */
    List<Contact> findByChannelMetadataValue(String key, String value, UUID tenantId);

    /**
     * Oblicza średni czas obsługi kontaktu (handle time) w sekundach dla danej kolejki
     * na podstawie zakończonych kontaktów z ostatnich 7 dni.
     *
     * @param tenantId UUID tenanta
     * @param queueId  UUID kolejki
     * @return średni czas obsługi w sekundach (domyślnie 300 jeśli brak danych)
     */
    double getAvgHandleTimeSeconds(UUID tenantId, UUID queueId);

    /**
     * Zlicza kontakty w statusie QUEUED dla danej kolejki tenanta.
     *
     * @param tenantId UUID tenanta
     * @param queueId  UUID kolejki
     * @return liczba kontaktów ze statusem QUEUED
     */
    int countWaitingByQueueId(UUID tenantId, UUID queueId);

    /**
     * Oblicza średni czas oczekiwania (w sekundach) kontaktów AKTUALNIE czekających
     * w kolejkach tenanta (status QUEUED), na podstawie {@code NOW() - queued_at}.
     *
     * @param tenantId UUID tenanta
     * @return średni czas oczekiwania w sekundach (0.0 jeśli brak kontaktów QUEUED)
     */
    double getAvgCurrentWaitSeconds(UUID tenantId);

    /**
     * Szuka aktywnego kontaktu social media dla danego nadawcy.
     *
     * @param tenantId         UUID tenanta
     * @param senderExternalId ID nadawcy na platformie (np. Facebook user ID)
     * @param channel          kanał (np. "SOCIAL_FACEBOOK")
     * @return Optional z aktywnym kontaktem lub empty gdy brak
     */
    Optional<Contact> findActiveSocialContact(UUID tenantId, String senderExternalId, String channel);

    /**
     * Zwraca kontakt aktualnie przypisany (status ASSIGNED) do danego agenta.
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     * @return Optional z kontaktem ASSIGNED lub empty gdy brak
     */
    Optional<Contact> findAssignedContactForAgent(UUID agentId, UUID tenantId);

    /**
     * Zwraca listę oczekujących kontaktów (status QUEUED) z detalami potrzebnymi
     * do wyświetlenia w sidebarze agenta.
     *
     * @param tenantId UUID tenanta
     * @return lista projekcji {@link QueuedContactView} posortowana wg queued_at ASC
     */
    List<QueuedContactView> findQueuedContactsForAgentView(UUID tenantId);

    /**
     * Pobiera zagregowane KPI agenta za podany zakres dat (dzień UTC).
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     * @param dayStart początek dnia UTC (włącznie)
     * @param dayEnd   koniec dnia UTC (wyłącznie)
     * @return tablica Object[] z czterema wartościami liczbowymi
     */
    Object[] findAgentKpiToday(UUID agentId, UUID tenantId, Instant dayStart, Instant dayEnd);

    /**
     * Zapisuje transkrypt nagrania (Whisper) do pola {@code notes} kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (cross-tenant safety)
     * @param notes     treść transkryptu do zapisania
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy kontakt nie istnieje
     */
    void updateNotes(UUID contactId, UUID tenantId, String notes);

    /**
     * Zwraca treść najnowszej transkrypcji rozmowy dla wskazanego kontaktu.
     *
     * <p>Odpowiednik {@code ContactTranscriptionRepository.findContentByContactId}.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (cross-tenant safety)
     * @return Optional z treścią transkrypcji lub empty() gdy brak rekordu
     */
    Optional<String> findTranscriptionContent(UUID contactId, UUID tenantId);

    /**
     * Zapisuje nowy rekord transkrypcji dla kontaktu.
     *
     * <p>Odpowiednik {@code ContactTranscriptionRepository.save}.
     *
     * @param contactId UUID kontaktu (musi istnieć w tabeli {@code contact})
     * @param tenantId  UUID tenanta (cross-tenant safety)
     * @param content   pełna transkrypcja rozmowy
     * @param language  wykryty język ISO 639-1 (np. "pl", "en") – może być null
     */
    void saveTranscription(UUID contactId, UUID tenantId, String content, String language);

    /**
     * Zapisuje nowy rekord podsumowania AI dla kontaktu.
     *
     * <p>Odpowiednik {@code ContactAiSummaryRepository.save}.
     *
     * @param aiSummary encja podsumowania z uzupełnionymi wszystkimi polami
     */
    void saveAiSummary(ContactAiSummary aiSummary);

    // =========================================================================
    // BE-113: Retencja – usuwanie batchowane (EPIC-29)
    // =========================================================================

    /**
     * Usuwa batch kontaktów tenanta starszych niż {@code cutoff} (retencja EPIC-29, BE-113 –
     * kategoria CONTACT_INTERACTIONS).
     *
     * <p>Bezpieczne dla tabel partycjonowanych współdzielonych przez wielu tenantów: identyfikuje
     * wiersze do usunięcia przez pełny klucz główny {@code (contact_id, started_at)}, nie przez
     * fizyczny {@code ctid} (patrz uzasadnienie w {@code ContactRepository#deleteBatchOlderThan}).
     *
     * @param tenantId  UUID tenanta
     * @param cutoff    granica czasowa – usuwane są kontakty z {@code started_at < cutoff}
     * @param batchSize maksymalna liczba wierszy usuwanych w jednym wywołaniu
     * @return lista UUID usuniętych kontaktów (do dalszego czyszczenia FK w email_message/social_message)
     *         – pusta gdy brak kwalifikujących się wierszy
     */
    List<UUID> purgeContactsOlderThan(UUID tenantId, Instant cutoff, int batchSize);

    /**
     * Usuwa batch transkrypcji tenanta starszych niż {@code cutoff} (retencja EPIC-29, BE-113 –
     * kategoria TRANSCRIPTS).
     *
     * @param tenantId  UUID tenanta
     * @param cutoff    granica czasowa – usuwane są transkrypcje z {@code created_at < cutoff}
     * @param batchSize maksymalna liczba wierszy usuwanych w jednym wywołaniu
     * @return liczba usuniętych wierszy (0 = brak kwalifikujących się wierszy)
     */
    int purgeTranscriptionsOlderThan(UUID tenantId, Instant cutoff, int batchSize);

    /**
     * Usuwa batch podsumowań AI tenanta starszych niż {@code cutoff} (retencja EPIC-29, BE-113 –
     * kategoria TRANSCRIPTS).
     *
     * <p>UWAGA: kolumna partycjonowania jest {@code generated_at}, nie {@code created_at}
     * (V087/DB-051, BE-117) — {@code cutoff} jest porównywany z {@code generated_at}.
     *
     * @param tenantId  UUID tenanta
     * @param cutoff    granica czasowa – usuwane są podsumowania z {@code generated_at < cutoff}
     * @param batchSize maksymalna liczba wierszy usuwanych w jednym wywołaniu
     * @return liczba usuniętych wierszy (0 = brak kwalifikujących się wierszy)
     */
    int purgeAiSummariesOlderThan(UUID tenantId, Instant cutoff, int batchSize);

    // =========================================================================
    // AdminMetrics: agregacje per tenant dla SUPER_ADMIN
    // =========================================================================

    /**
     * Zlicza aktywne/w toku kontakty tenanta (status QUEUED, ACTIVE lub ON_HOLD).
     *
     * @param tenantId UUID tenanta
     * @return liczba aktywnych/w toku kontaktów tenanta
     */
    long countActiveContactsByTenantId(UUID tenantId);

    /**
     * Agreguje statystyki kontaktów zakończonych tenanta dla wskazanego dnia.
     *
     * @param tenantId UUID tenanta
     * @param date     dzień, dla którego liczona jest agregacja
     * @return {@code Object[]} {contactsCount, sumHandleTimeSeconds, sumWaitTimeSeconds, fcrCount}
     */
    Object[] getDailyContactAggregate(UUID tenantId, java.time.LocalDate date);

    /**
     * Agreguje liczbę kontaktów zakończonych tenanta w podanym zakresie dat (oba krańce
     * włącznie), pogrupowaną po kanale.
     *
     * <p>Ta sama definicja "kontaktu" co {@link #getDailyContactAggregate} – dla zakresu
     * zawężonego do jednego dnia ({@code fromDate == toDate}) suma zwróconych liczników zgadza
     * się z {@code contactsCount} z {@link #getDailyContactAggregate} dla tego samego tenanta/dnia.
     *
     * @param tenantId UUID tenanta
     * @param fromDate pierwszy dzień zakresu (włącznie)
     * @param toDate   ostatni dzień zakresu (włącznie)
     * @return lista wierszy {@code [channel(String), count(Number)]} – tylko kanały z
     *         co najmniej jednym kontaktem w zakresie
     */
    List<Object[]> getContactCountsByChannelInRange(
            UUID tenantId, java.time.LocalDate fromDate, java.time.LocalDate toDate);
}
