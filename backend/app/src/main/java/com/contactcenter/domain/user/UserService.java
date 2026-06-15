package com.contactcenter.domain.user;

import com.contactcenter.api.user.dto.CreateUserRequest;
import com.contactcenter.api.user.dto.UpdateStatusRequest;
import com.contactcenter.api.user.dto.UpdateUserRequest;
import com.contactcenter.api.user.dto.UserResponse;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.infrastructure.aspect.Audited;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający użytkownikami i agentami.
 *
 * <p>Implementuje BE-008: CRUD użytkowników z obsługą skills (JSONB),
 * soft delete, zmiana statusu agenta z propagacją przez RabbitMQ i Redis.
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Każdy odczyt i zapis filtruje po tenantId z {@link com.contactcenter.security.TenantContext}</li>
 *   <li>Hasła hashowane BCrypt(12) przed zapisem – nigdy nie przechowujemy plain text</li>
 *   <li>Pola wrażliwe (passwordHash, mfaSecret) nigdy nie trafiają do DTO</li>
 *   <li>Operacje modyfikujące logują zdarzenia audytowe przez {@link Audited}</li>
 * </ul>
 */
public interface UserService {

    /**
     * Tworzy nowego użytkownika w tenancie.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Sprawdza limit agentów (gdy role=AGENT) przez {@code TenantResourceLimitService}</li>
     *   <li>Sprawdza unikalność email w tenancie</li>
     *   <li>Hashuje hasło BCrypt(12)</li>
     *   <li>Zapisuje encję z domyślnym statusem ACTIVE</li>
     * </ol>
     *
     * @param request  dane nowego użytkownika
     * @param tenantId UUID tenanta z TenantContext
     * @return DTO nowo utworzonego użytkownika
     * @throws com.contactcenter.domain.exception.ResourceLimitExceededException HTTP 422 gdy limit agentów przekroczony
     * @throws IllegalArgumentException HTTP 422 gdy email już zajęty w tenancie
     */
    UserResponse createUser(CreateUserRequest request, UUID tenantId);

    /**
     * Lista użytkowników tenanta z paginacją i opcjonalnym filtrowaniem.
     *
     * @param tenantId UUID tenanta
     * @param status   opcjonalny filtr statusu (null = brak filtru)
     * @param skill    opcjonalny filtr skill (null = brak filtru)
     * @param role     opcjonalny filtr roli (null = brak filtru)
     * @param search   opcjonalna fraza wyszukiwania w imieniu, nazwisku, emailu (null = brak filtru)
     * @param pageable parametry stronicowania
     * @return strona DTO użytkowników
     */
    Page<UserResponse> listUsers(UUID tenantId, String status, String skill, String role, String search, Pageable pageable);

    /**
     * Pobiera użytkownika po ID w ramach tenanta.
     *
     * @param userId   UUID użytkownika
     * @param tenantId UUID tenanta
     * @return DTO użytkownika
     * @throws EntityNotFoundException HTTP 422 gdy użytkownik nie istnieje
     */
    UserResponse getUser(UUID userId, UUID tenantId);

    /**
     * Aktualizuje dane użytkownika (PATCH semantics).
     *
     * <p>Pola null w żądaniu są ignorowane – wartości pozostają bez zmian.
     * Nie pozwala na zmianę email, roli ani hasła (osobne endpointy).
     *
     * @param userId   UUID użytkownika
     * @param request  dane do aktualizacji (null = bez zmiany)
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego użytkownika
     * @throws EntityNotFoundException HTTP 422 gdy użytkownik nie istnieje
     */
    UserResponse updateUser(UUID userId, UpdateUserRequest request, UUID tenantId);

    /**
     * Soft delete użytkownika (is_deleted=true, is_active=false).
     *
     * <p>Reguły:
     * <ul>
     *   <li>Nie można usunąć siebie samego</li>
     *   <li>Nie można usunąć agenta z aktywnymi kontaktami (HTTP 409)</li>
     * </ul>
     *
     * @param userId          UUID użytkownika do usunięcia
     * @param tenantId        UUID tenanta
     * @param requestingUserId UUID użytkownika wykonującego żądanie
     * @throws ConflictException       HTTP 409 gdy agent ma aktywne kontakty
     * @throws EntityNotFoundException HTTP 422 gdy użytkownik nie istnieje
     * @throws IllegalArgumentException HTTP 422 gdy próbuje usunąć siebie
     */
    void deleteUser(UUID userId, UUID tenantId, UUID requestingUserId);

    /**
     * Lista unikalnych skills wszystkich agentów w tenancie.
     *
     * @param tenantId UUID tenanta
     * @return posortowana lista unikalnych skill tagów
     */
    List<String> listSkills(UUID tenantId);

    /**
     * Zmienia status agenta i propaguje event przez RabbitMQ i Redis.
     *
     * <p>Dozwolone statusy: AVAILABLE, BUSY, BREAK, AFTER_CONTACT.
     * Statusy ACTIVE/INACTIVE zarządzane przez CRUD (nie przez ten endpoint).
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Walidacja dozwolonego statusu</li>
     *   <li>Zapis nowego statusu w bazie</li>
     *   <li>Zapis statusu w Redis (klucz session:agent:{userId}, TTL 8h)</li>
     *   <li>Publikacja eventu na RabbitMQ (cc.events, routing key: agent.status.changed)</li>
     * </ol>
     *
     * @param userId   UUID agenta
     * @param request  nowy status
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego użytkownika
     * @throws IllegalArgumentException HTTP 422 gdy status jest niedozwolony (ACTIVE/INACTIVE)
     * @throws EntityNotFoundException  HTTP 422 gdy użytkownik nie istnieje
     */
    UserResponse updateStatus(UUID userId, UpdateStatusRequest request, UUID tenantId);

    /**
     * Ustawia status agenta na AVAILABLE po anulowaniu konsultacji (attended transfer bez bridge).
     *
     * <p>Wywoływana przez TwilioTelephonyAdapter gdy Agent1 rozłączy nogę konsultacyjną
     * zanim wywoła bridge. Agent2 (targetAgentId) wraca do AVAILABLE bez przechodzenia
     * przez AFTER_CONTACT.
     *
     * <p>Operacja jest best-effort: błędy są logowane, ale nie blokują publikacji eventu
     * CALL_CONSULT_CANCELLED przez adapter.
     *
     * @param agentId  UUID Agent2
     * @param tenantId UUID tenanta
     */
    void setAgentAvailableAfterConsultCancelled(UUID agentId, UUID tenantId);

    // =========================================================================
    // Encapsulation pass: dostęp do AppUserRepository wyłącznie przez UserService
    // =========================================================================

    /**
     * Znajdź aktywnego, nieusuniętego użytkownika po email i tenantId – do uwierzytelniania.
     *
     * <p>Używane przez {@code UserDetailsServiceImpl} podczas logowania. Pomija
     * użytkowników z {@code is_active = false}.
     *
     * <p><strong>Uwaga:</strong> ta metoda jest wywoływana z {@code JwtAuthFilter} /
     * {@code UserDetailsServiceImpl} jako część bootstrapu autentykacji – PRZED
     * {@code TenantFilter}. W tym momencie {@code TenantContext} jest celowo
     * NIEUSTAWIONY (kolejność filtrów: JwtAuthFilter → TenantFilter →
     * UsernamePasswordAuthenticationFilter). Metoda musi działać wyłącznie na
     * podstawie jawnie przekazanego {@code tenantId} (z claimów JWT), bez odwołań
     * do {@code TenantContext.getTenantId()}. {@code CrossTenantAspect} ma dla niej
     * jawny wyjątek (patrz {@code isAuthenticationBootstrapMethod}) – przyszłe
     * refaktory nie powinny przenosić tej metody bez zachowania tej własności.
     *
     * @param tenantId UUID tenanta
     * @param email    adres e-mail użytkownika
     * @return Optional z użytkownikiem lub empty jeśli nie istnieje/nieaktywny
     */
    Optional<AppUser> findAuthenticatableUser(UUID tenantId, String email);

    /**
     * Dezaktywuje wszystkich aktywnych użytkowników tenanta jednym bulk UPDATE.
     *
     * <p>Używane przy dezaktywacji tenanta ({@code TenantService#deactivateTenant}).
     *
     * @param tenantId UUID tenanta
     * @return liczba dezaktywowanych użytkowników
     */
    int deactivateAllUsersByTenantId(UUID tenantId);

    /**
     * Znajdź agenta po ID i tenantId (nie usuniętego).
     *
     * <p>Bezpieczny odczyt per tenant – uniemożliwia cross-tenant lookup.
     * Używane do walidacji agentów (np. przy przypisywaniu do grup, kolejek,
     * transferach, rozwiązywaniu nazw agentów).
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     * @return Optional z agentem lub empty jeśli nie istnieje/inny tenant/usunięty
     */
    Optional<AppUser> findAgentByIdAndTenantId(UUID agentId, UUID tenantId);

    /**
     * Zlicza łączną liczbę agentów dla tenanta (is_deleted=false, role=AGENT).
     *
     * @param tenantId UUID tenanta
     * @return liczba agentów z rolą AGENT i is_deleted=false
     */
    long countAgentsByTenantId(UUID tenantId);

    /**
     * Pobiera wielu agentów po liście UUID w obrębie tenanta.
     *
     * <p>Używane do batch-lookupowania nazw agentów – unika problemu N+1 zapytań.
     * Soft-deleted użytkownicy są pomijani.
     *
     * @param agentIds zbiór UUID agentów do pobrania
     * @param tenantId UUID tenanta (izolacja cross-tenant)
     * @return lista agentów należących do tenanta
     */
    List<AppUser> findAgentsByIdsAndTenantId(java.util.Collection<UUID> agentIds, UUID tenantId);

    /**
     * Lista agentów tenanta z opcjonalnym filtrowaniem po statusie, skillu, roli i frazie wyszukiwania.
     *
     * @param tenantId UUID tenanta
     * @param status   opcjonalny status lub null
     * @param skill    opcjonalny skill lub null
     * @param role     opcjonalna rola lub null
     * @param search   opcjonalna fraza wyszukiwania lub null
     * @param pageable parametry stronicowania
     * @return strona agentów spełniających kryteria
     */
    Page<AppUser> findAgentsByTenantIdWithFilters(UUID tenantId, String status, String skill, String role,
                                                   String search, Pageable pageable);

    /**
     * Zwraca agentów dostępnych jako kandydaci do przyjęcia transferu połączenia.
     *
     * @param tenantId      UUID tenanta
     * @param excludeUserId UUID agenta wykluczanego z wyników (zalogowany agent)
     * @return lista kandydatów do transferu
     */
    List<AppUser> findTransferCandidates(UUID tenantId, UUID excludeUserId);

    /**
     * Zwraca wszystkich nieusunietych agentów ze statusem AVAILABLE, cross-tenant.
     *
     * <p>Używana przez {@code ProgressiveDialerService} i {@code RoutingService}
     * do cyklicznego pollingu agentów dostępnych do obsługi.
     *
     * @return lista agentów ze statusem AVAILABLE, is_deleted=false
     */
    List<AppUser> findAvailableAgents();

    /**
     * Zwraca aktywne tenanty, w których istnieje aktywny, nieusunięty użytkownik z danym emailem.
     *
     * <p>Używane przez publiczny endpoint email-first login flow.
     *
     * @param email adres e-mail użytkownika (case-insensitive)
     * @return lista wierszy [tenant_id, name] aktywnych tenantów z dopasowaniem
     */
    List<Object[]> findActiveTenantsByUserEmail(String email);

    /**
     * Zwraca liczbę aktywnych kontaktów dla listy agentów w jednym zapytaniu SQL (batch).
     *
     * <p>Używane przez routing engine do wyboru najmniej obciążonego agenta.
     *
     * @param tenantId UUID tenanta (izolacja cross-tenant)
     * @param agentIds lista UUID agentów do sprawdzenia
     * @return lista par [agent_id, active_contacts_count] jako Object[]
     */
    List<Object[]> countActiveContactsByAgentIds(UUID tenantId, List<UUID> agentIds);

    /**
     * Pobiera wielu użytkowników po liście UUID, bez filtra tenantId.
     *
     * <p>Używane wyłącznie w kontekstach, gdzie tenant został już zweryfikowany
     * inną ścieżką (np. dane z kolejki/callbacku należące do bieżącego tenanta).
     *
     * @param userIds zbiór UUID użytkowników do pobrania
     * @return lista znalezionych użytkowników
     */
    List<AppUser> findUsersByIds(java.util.Collection<UUID> userIds);

    /**
     * Pobiera użytkownika po ID, bez filtra tenantId.
     *
     * <p>Używane wyłącznie w kontekstach, gdzie tenant został już zweryfikowany
     * inną ścieżką.
     *
     * @param userId UUID użytkownika
     * @return Optional z użytkownikiem lub empty jeśli nie istnieje
     */
    Optional<AppUser> findUserById(UUID userId);
}
