package com.contactcenter.domain.service;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.customer.dto.CreateCustomerRequest;
import com.contactcenter.api.customer.dto.CustomerLookupResponse;
import com.contactcenter.api.customer.dto.CustomerResponse;
import com.contactcenter.api.customer.dto.UpdateCustomerRequest;
import com.contactcenter.domain.model.Customer;
import com.contactcenter.domain.repository.CustomerRepository;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>Operacje modyfikujące logują zdarzenia audytowe przez {@link Audited}</li>
 * </ul>
 *
 * <p>Fuzzy search używa PostgreSQL funkcji {@code search_customers()} z trigram
 * indeksem GIN – czas odpowiedzi < 1s (NFR-P03).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private static final int DEFAULT_SEARCH_LIMIT = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final CustomerRepository customerRepository;
    private final RabbitTemplate rabbitTemplate;

    // =========================================================================
    // Tworzenie klienta
    // =========================================================================

    /**
     * Tworzy nowy profil klienta w tenancie.
     *
     * <p>Po zapisie publikuje event {@code customer.created} do RabbitMQ.
     *
     * @param request  dane nowego klienta
     * @param tenantId UUID tenanta z TenantContext
     * @return DTO nowo utworzonego klienta
     */
    @Transactional
    @Audited(action = "CUSTOMER_CREATED", entityType = "CUSTOMER")
    public CustomerResponse createCustomer(CreateCustomerRequest request, UUID tenantId) {
        Customer customer = Customer.builder()
                .tenantId(tenantId)
                .firstName(request.firstName())
                .lastName(request.lastName())
                .phone(request.phone() != null ? new ArrayList<>(request.phone()) : new ArrayList<>())
                .email(request.email() != null ? new ArrayList<>(request.email()) : new ArrayList<>())
                .customFields(request.customFields() != null
                        ? new HashMap<>(request.customFields()) : new HashMap<>())
                .gdprConsent(request.gdprConsent() != null
                        ? new HashMap<>(request.gdprConsent()) : defaultGdprConsent())
                .source(request.source() != null ? request.source() : "MANUAL")
                .deleted(false)
                .build();

        Customer saved = customerRepository.save(customer);

        log.info("[CustomerService] Utworzono klienta: customerId={}, tenantId={}, source={}",
                saved.getCustomerId(), tenantId, saved.getSource());

        publishCustomerCreatedEvent(saved);

        return CustomerResponse.from(saved);
    }

    // =========================================================================
    // Wyszukiwanie (fuzzy search)
    // =========================================================================

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
    @Transactional(readOnly = true)
    public List<CustomerResponse> searchCustomers(String query, UUID tenantId, Integer limit) {
        int effectiveLimit = (limit == null || limit <= 0)
                ? DEFAULT_SEARCH_LIMIT
                : Math.min(limit, MAX_PAGE_SIZE);

        log.debug("[CustomerService] Fuzzy search: query='{}', tenant={}, limit={}", query, tenantId, effectiveLimit);

        List<Customer> results = customerRepository.searchByQuery(tenantId, query, effectiveLimit);

        return results.stream()
                .map(CustomerResponse::from)
                .toList();
    }

    // =========================================================================
    // Lista klientów z paginacją
    // =========================================================================

    /**
     * Lista klientów tenanta z paginacją offset-based.
     *
     * @param tenantId UUID tenanta
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony (max 100)
     * @return mapa z kluczami: content (lista), page, size, totalElements, totalPages
     */
    @Transactional(readOnly = true)
    public PagedResponse<CustomerResponse> listCustomers(UUID tenantId, int page, int size) {
        int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 0);

        List<Customer> customers = customerRepository.findByTenantIdPaged(tenantId, effectivePage, effectiveSize);
        long totalElements = customerRepository.countByTenantId(tenantId);
        int totalPages = (int) Math.ceil((double) totalElements / effectiveSize);

        List<CustomerResponse> content = customers.stream()
                .map(CustomerResponse::from)
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
    // Odczyt klienta
    // =========================================================================

    /**
     * Pobiera klienta po ID z zabezpieczeniem cross-tenant.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @return DTO klienta
     * @throws EntityNotFoundException HTTP 404 gdy klient nie istnieje lub inny tenant
     */
    @Transactional(readOnly = true)
    public CustomerResponse getCustomer(UUID customerId, UUID tenantId) {
        Customer customer = findCustomerOrThrow(customerId, tenantId);
        return CustomerResponse.from(customer);
    }

    // =========================================================================
    // Aktualizacja klienta
    // =========================================================================

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
    @Transactional
    @Audited(action = "CUSTOMER_UPDATED", entityType = "CUSTOMER", captureOldValue = true,
             fetchOldValueMethod = "getCustomer", entityIdParamIndex = 0)
    public CustomerResponse updateCustomer(UUID customerId, UpdateCustomerRequest request, UUID tenantId) {
        Customer customer = findCustomerOrThrow(customerId, tenantId);

        if (request.firstName() != null) {
            customer.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            customer.setLastName(request.lastName());
        }
        if (request.phone() != null) {
            customer.setPhone(new ArrayList<>(request.phone()));
        }
        if (request.email() != null) {
            customer.setEmail(new ArrayList<>(request.email()));
        }
        if (request.customFields() != null) {
            customer.setCustomFields(new HashMap<>(request.customFields()));
        }
        if (request.gdprConsent() != null) {
            customer.setGdprConsent(new HashMap<>(request.gdprConsent()));
        }

        Customer saved = customerRepository.save(customer);
        log.info("[CustomerService] Zaktualizowano klienta: customerId={}, tenantId={}", customerId, tenantId);

        return CustomerResponse.from(saved);
    }

    // =========================================================================
    // Anonimizacja RODO (DELETE)
    // =========================================================================

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
    @Transactional
    @Audited(action = "CUSTOMER_ANONYMIZED", entityType = "CUSTOMER", entityIdParamIndex = 0)
    public void anonymizeCustomer(UUID customerId, UUID tenantId) {
        int updated = customerRepository.anonymize(customerId, tenantId);

        if (updated == 0) {
            throw new EntityNotFoundException(
                    "Klient nie istnieje lub jest już zanonimizowany: " + customerId);
        }

        log.info("[CustomerService] Klient zanonimizowany (RODO): customerId={}, tenantId={}",
                customerId, tenantId);
    }

    // =========================================================================
    // Wyszukiwanie klienta po numerze telefonu (lookup)
    // =========================================================================

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
    @Transactional(readOnly = true)
    public Optional<CustomerLookupResponse> lookupByPhone(String phone, UUID tenantId) {
        log.debug("[CustomerService] Lookup po numerze telefonu: phone={}, tenant={}", phone, tenantId);
        return customerRepository.findByPhoneNumber(phone, tenantId)
                .map(customer -> {
                    List<CustomerLookupResponse.ContactSummaryDto> contacts =
                            fetchRecentContactsForLookup(customer.getCustomerId(), tenantId);
                    return new CustomerLookupResponse(
                            customer.getCustomerId(),
                            customer.getFirstName(),
                            customer.getLastName(),
                            customer.getPhone() != null ? customer.getPhone() : List.of(),
                            customer.getEmail() != null ? customer.getEmail() : List.of(),
                            contacts
                    );
                });
    }

    private List<CustomerLookupResponse.ContactSummaryDto> fetchRecentContactsForLookup(
            UUID customerId, UUID tenantId) {
        try {
            List<Object[]> rows = customerRepository.findLastContactsForCustomer(customerId, tenantId, 5);
            List<CustomerLookupResponse.ContactSummaryDto> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                UUID contactId = row[0] instanceof UUID u ? u : (row[0] != null ? UUID.fromString(row[0].toString()) : null);
                String channel = row[1] != null ? row[1].toString() : null;
                String status = row[2] != null ? row[2].toString() : null;
                String date = null;
                if (row[3] instanceof java.sql.Timestamp ts) {
                    date = ts.toInstant().toString();
                } else if (row[3] instanceof Instant inst) {
                    date = inst.toString();
                } else if (row[3] instanceof java.time.OffsetDateTime odt) {
                    date = odt.toInstant().toString();
                }
                result.add(new CustomerLookupResponse.ContactSummaryDto(contactId, channel, status, date, null));
            }
            return result;
        } catch (Exception e) {
            log.warn("[CustomerService] Błąd pobierania ostatnich kontaktów dla customerId={}: {}",
                    customerId, e.getMessage());
            return List.of();
        }
    }

    // =========================================================================
    // Wyszukiwanie klienta po adresie email (lookup)
    // =========================================================================

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
    @Transactional(readOnly = true)
    public Optional<CustomerLookupResponse> lookupByEmail(String email, UUID tenantId) {
        // Defensywne parsowanie RFC 2822 "Display Name <email@domain>" — na wypadek gdyby
        // klient API przekazał surowy nagłówek From: zamiast czystego adresu.
        String cleanEmail = extractEmailAddress(email);
        log.debug("[CustomerService] Lookup po adresie email: email={}, tenant={}", cleanEmail, tenantId);
        return customerRepository.findByEmail(cleanEmail, tenantId)
                .map(customer -> {
                    List<CustomerLookupResponse.ContactSummaryDto> contacts =
                            fetchRecentContactsForLookup(customer.getCustomerId(), tenantId);
                    return new CustomerLookupResponse(
                            customer.getCustomerId(),
                            customer.getFirstName(),
                            customer.getLastName(),
                            customer.getPhone() != null ? customer.getPhone() : List.of(),
                            customer.getEmail() != null ? customer.getEmail() : List.of(),
                            contacts
                    );
                });
    }

    // =========================================================================
    // Auto-tworzenie z UNKNOWN_CALLER (RabbitMQ event)
    // =========================================================================

    /**
     * Obsługuje event przychodzącego połączenia od nieznanego numeru.
     *
     * <p>Jeśli klient z danym numerem już istnieje w tenancie, zwraca istniejący profil.
     * W przeciwnym razie tworzy nowy profil z source='AUTO'.
     *
     * <p>Nasłuchuje na exchange {@code cc.events}, routing key {@code call.unknown_caller}.
     * Wiadomość musi zawierać pola: tenantId, phone.
     *
     * @param event mapa z danymi eventu (tenantId, phone)
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_UNKNOWN_CALLER)
    public void handleUnknownCallerEvent(Map<String, String> event) {
        try {
            String tenantIdStr = event.get("tenantId");
            String phone = event.get("phone");

            if (tenantIdStr == null || phone == null) {
                log.warn("[CustomerService] Event call.unknown_caller bez tenantId lub phone: {}", event);
                return;
            }

            UUID tenantId = UUID.fromString(tenantIdStr);
            handleUnknownCaller(phone, tenantId);

        } catch (Exception e) {
            log.error("[CustomerService] Błąd przetwarzania eventu call.unknown_caller: error={}",
                    e.getMessage(), e);
        }
    }

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
    @Transactional
    public CustomerResponse handleUnknownCaller(String phone, UUID tenantId) {
        // Sprawdź czy klient z tym numerem już istnieje
        return customerRepository.findByPhoneNumber(phone, tenantId)
                .map(existing -> {
                    log.debug("[CustomerService] Znaleziono istniejącego klienta dla phone={}, tenant={}",
                            phone, tenantId);
                    return CustomerResponse.from(existing);
                })
                .orElseGet(() -> {
                    log.info("[CustomerService] Auto-tworzenie profilu dla nieznanego numeru: phone={}, tenant={}",
                            phone, tenantId);
                    Customer created = customerRepository.createFromUnknownCaller(tenantId, phone);
                    publishCustomerCreatedEvent(created);
                    return CustomerResponse.from(created);
                });
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Customer findCustomerOrThrow(UUID customerId, UUID tenantId) {
        return customerRepository.findById(customerId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Klient nie istnieje lub nie należy do tego tenanta: " + customerId));
    }

    private Map<String, Object> defaultGdprConsent() {
        Map<String, Object> consent = new HashMap<>();
        consent.put("consent_given", false);
        return consent;
    }

    private void publishCustomerCreatedEvent(Customer customer) {
        try {
            Map<String, Object> event = Map.of(
                    "customerId", customer.getCustomerId().toString(),
                    "tenantId", customer.getTenantId().toString(),
                    "source", customer.getSource()
            );
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_EVENTS,
                    "customer.created",
                    event
            );
            log.debug("[CustomerService] Event customer.created opublikowany: customerId={}",
                    customer.getCustomerId());
        } catch (Exception e) {
            // Failure RabbitMQ nie blokuje operacji HTTP – logujemy error
            log.error("[CustomerService] Błąd publikacji eventu customer.created: customerId={}, error={}",
                    customer.getCustomerId(), e.getMessage());
        }
    }

    /**
     * Wyodrębnia czysty adres email z formatu RFC 2822 "Display Name &lt;email@domain&gt;".
     * Jeśli format nie pasuje, zwraca oryginalny ciąg po przycięciu białych znaków.
     */
    private String extractEmailAddress(String raw) {
        if (raw == null || raw.isBlank()) return "";
        int lt = raw.lastIndexOf('<');
        int gt = raw.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            return raw.substring(lt + 1, gt).trim();
        }
        return raw.trim();
    }
}
