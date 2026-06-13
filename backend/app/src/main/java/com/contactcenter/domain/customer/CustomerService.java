package com.contactcenter.domain.customer;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.customer.dto.CreateCustomerRequest;
import com.contactcenter.api.customer.dto.CustomerLookupResponse;
import com.contactcenter.api.customer.dto.CustomerResponse;
import com.contactcenter.api.customer.dto.UpdateCustomerRequest;
import jakarta.persistence.EntityNotFoundException;

import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający profilami klientów końcowych.
 *
 * <p>Implementuje BE-025: Customer CRUD API z fuzzy search, paginacją,
 * anonimizacją RODO i auto-tworzeniem profilu z nieznanego połączenia.
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Każdy odczyt i zapis filtruje po tenantId z TenantContext lub parametru</li>
 *   <li>Cross-tenant guard przez {@code assertSameTenant()} w repozytorium</li>
 *   <li>Operacje modyfikujące logują zdarzenia audytowe przez {@code @Audited}</li>
 * </ul>
 *
 * <p>Fuzzy search używa PostgreSQL funkcji {@code search_customers()} z trigram
 * indeksem GIN – czas odpowiedzi < 1s (NFR-P03).
 */
public interface CustomerService {

    /**
     * Tworzy nowy profil klienta w tenancie.
     *
     * <p>Po zapisie publikuje event {@code customer.created} do RabbitMQ.
     *
     * @param request  dane nowego klienta
     * @param tenantId UUID tenanta z TenantContext
     * @return DTO nowo utworzonego klienta
     */
    CustomerResponse createCustomer(CreateCustomerRequest request, UUID tenantId);

    /**
     * Wyszukuje klientów przez fuzzy search (trigram similarity).
     *
     * <p>Wywołuje PostgreSQL funkcję {@code search_customers()} z indeksem GIN.
     * Wyniki posortowane malejąco wg similarity score.
     *
     * @param query    fraza wyszukiwania (imię/nazwisko, telefon, email)
     * @param tenantId UUID tenanta
     * @param limit    maksymalna liczba wyników (domyślnie 20)
     * @return lista dopasowanych klientów jako DTO
     */
    java.util.List<CustomerResponse> searchCustomers(String query, UUID tenantId, Integer limit);

    /**
     * Lista klientów tenanta z paginacją offset-based.
     *
     * @param tenantId UUID tenanta
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony (max 100)
     * @return strona klientów z metadanymi paginacji
     */
    PagedResponse<CustomerResponse> listCustomers(UUID tenantId, int page, int size);

    /**
     * Pobiera klienta po ID z zabezpieczeniem cross-tenant.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @return DTO klienta
     * @throws EntityNotFoundException HTTP 404 gdy klient nie istnieje lub inny tenant
     */
    CustomerResponse getCustomer(UUID customerId, UUID tenantId);

    /**
     * Aktualizuje dane klienta (PATCH semantics).
     *
     * <p>Pola null w żądaniu są ignorowane – wartości pozostają bez zmian.
     * Przekazanie pustej listy phone=[] lub email=[] wyczyści tablicę.
     *
     * @param customerId UUID klienta
     * @param request    dane do aktualizacji (null = bez zmiany)
     * @param tenantId   UUID tenanta
     * @return DTO zaktualizowanego klienta
     * @throws EntityNotFoundException HTTP 404 gdy klient nie istnieje
     */
    CustomerResponse updateCustomer(UUID customerId, UpdateCustomerRequest request, UUID tenantId);

    /**
     * Anonimizuje dane osobowe klienta zgodnie z RODO Art. 17.
     *
     * <p>Operacja jest nieodwracalna. Rekord pozostaje w bazie (historia kontaktów
     * jest zachowana dla rozliczeń), ale wszystkie PII są zastępowane przez 'ANONYMIZED'.
     *
     * <p>Anonimizuje:
     * <ul>
     *   <li>first_name → 'ANONYMIZED'</li>
     *   <li>last_name → 'ANONYMIZED'</li>
     *   <li>phone → []</li>
     *   <li>email → []</li>
     *   <li>is_deleted → true</li>
     * </ul>
     *
     * @param customerId UUID klienta do anonimizacji
     * @param tenantId   UUID tenanta
     * @throws EntityNotFoundException HTTP 404 gdy klient nie istnieje lub już anonimizowany
     */
    void anonymizeCustomer(UUID customerId, UUID tenantId);

    /**
     * Wyszukuje klienta po numerze telefonu bez auto-tworzenia profilu.
     *
     * <p>Używane przez agenta do identyfikacji klienta przy odbieraniu połączenia.
     * W odróżnieniu od {@link #handleUnknownCaller} nie tworzy profilu gdy klient
     * nie zostanie znaleziony – zamiast tego zwraca pusty Optional.
     *
     * @param phone    numer telefonu do wyszukania
     * @param tenantId UUID tenanta
     * @return Optional z DTO klienta lub empty gdy nie znaleziono
     */
    Optional<CustomerLookupResponse> lookupByPhone(String phone, UUID tenantId);

    /**
     * Wyszukuje klienta po adresie email bez auto-tworzenia profilu.
     *
     * <p>Używane przez agenta do identyfikacji klienta przy obsłudze wiadomości email.
     * Analogicznie do {@link #lookupByPhone} – zwraca pusty Optional gdy klient nie istnieje.
     *
     * @param email    adres email do wyszukania
     * @param tenantId UUID tenanta
     * @return Optional z DTO klienta lub empty gdy nie znaleziono
     */
    Optional<CustomerLookupResponse> lookupByEmail(String email, UUID tenantId);

    /**
     * Tworzy lub odnajduje profil klienta dla nieznanego dzwoniącego.
     *
     * <p>Idempotentna operacja – jeśli klient z tym numerem już istnieje, zwraca go.
     * Jeśli nie istnieje, tworzy nowy profil z source='AUTO'.
     *
     * @param phone    numer telefonu dzwoniącego
     * @param tenantId UUID tenanta
     * @return profil klienta (nowy lub istniejący)
     */
    CustomerResponse handleUnknownCaller(String phone, UUID tenantId);
}
