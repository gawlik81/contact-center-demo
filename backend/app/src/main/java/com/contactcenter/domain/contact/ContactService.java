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
}
