package com.contactcenter.domain;

import com.contactcenter.api.customer.dto.CreateCustomerRequest;
import com.contactcenter.api.customer.dto.CustomerResponse;
import com.contactcenter.api.customer.dto.UpdateCustomerRequest;
import com.contactcenter.domain.model.Customer;
import com.contactcenter.domain.repository.CustomerRepository;
import com.contactcenter.domain.service.CustomerService;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link CustomerService}.
 *
 * <p>Weryfikuje logikę biznesową: tworzenie, odczyt, aktualizacja,
 * fuzzy search, anonimizację RODO i auto-tworzenie z UNKNOWN_CALLER.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CustomerService – CRUD klientów i fuzzy search")
class CustomerServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private CustomerRepository customerRepository;
    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserRole("SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Tworzenie klienta
    // =========================================================================

    @Test
    @DisplayName("createCustomer – zapisuje encję i publikuje event RabbitMQ")
    void createCustomer_savesAndPublishesEvent() {
        // given
        CreateCustomerRequest request = new CreateCustomerRequest(
                "Jan", "Kowalski",
                List.of("+48501234567"),
                List.of("jan@example.com"),
                null, null, "MANUAL"
        );

        Customer saved = buildCustomer(CUSTOMER_ID);
        when(customerRepository.save(any(Customer.class))).thenReturn(saved);

        // when
        CustomerResponse response = customerService.createCustomer(request, TENANT_ID);

        // then
        assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(response.firstName()).isEqualTo("Jan");

        verify(customerRepository).save(argThat(c ->
                c.getTenantId().equals(TENANT_ID)
                && "Jan".equals(c.getFirstName())
                && "Kowalski".equals(c.getLastName())
                && "MANUAL".equals(c.getSource())
        ));

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_EVENTS),
                eq("customer.created"),
                any(Map.class)
        );
    }

    @Test
    @DisplayName("createCustomer – domyślnie source=MANUAL gdy nie podano")
    void createCustomer_defaultsToManualSource() {
        // given
        CreateCustomerRequest request = new CreateCustomerRequest(
                "Anna", "Nowak", null, null, null, null, null
        );

        Customer saved = Customer.builder()
                .customerId(CUSTOMER_ID)
                .tenantId(TENANT_ID)
                .firstName("Anna")
                .lastName("Nowak")
                .phone(new ArrayList<>())
                .email(new ArrayList<>())
                .customFields(new HashMap<>())
                .gdprConsent(new HashMap<>())
                .source("MANUAL")
                .deleted(false)
                .createdAt(Instant.now())
                .build();
        when(customerRepository.save(any(Customer.class))).thenReturn(saved);

        // when
        CustomerResponse response = customerService.createCustomer(request, TENANT_ID);

        // then
        verify(customerRepository).save(argThat(c -> "MANUAL".equals(c.getSource())));
    }

    @Test
    @DisplayName("createCustomer – błąd RabbitMQ nie blokuje operacji")
    void createCustomer_rabbitFailureDoesNotBlockOperation() {
        // given
        CreateCustomerRequest request = new CreateCustomerRequest(
                "Jan", null, null, null, null, null, null
        );
        Customer saved = buildCustomer(CUSTOMER_ID);
        when(customerRepository.save(any(Customer.class))).thenReturn(saved);
        doThrow(new RuntimeException("RabbitMQ connection failed"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // when / then – nie rzuca wyjątku
        assertThatNoException().isThrownBy(() ->
                customerService.createCustomer(request, TENANT_ID));
    }

    // =========================================================================
    // Odczyt klienta
    // =========================================================================

    @Test
    @DisplayName("getCustomer – zwraca DTO gdy klient istnieje")
    void getCustomer_returnsResponseWhenFound() {
        // given
        Customer customer = buildCustomer(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID, TENANT_ID)).thenReturn(Optional.of(customer));

        // when
        CustomerResponse response = customerService.getCustomer(CUSTOMER_ID, TENANT_ID);

        // then
        assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(response.tenantId()).isEqualTo(TENANT_ID);
        verify(customerRepository).findById(CUSTOMER_ID, TENANT_ID);
    }

    @Test
    @DisplayName("getCustomer – rzuca EntityNotFoundException gdy klient nie istnieje")
    void getCustomer_throwsWhenNotFound() {
        // given
        when(customerRepository.findById(CUSTOMER_ID, TENANT_ID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> customerService.getCustomer(CUSTOMER_ID, TENANT_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(CUSTOMER_ID.toString());
    }

    // =========================================================================
    // Fuzzy search
    // =========================================================================

    @Test
    @DisplayName("searchCustomers – wywołuje repozytorium i mapuje wyniki")
    void searchCustomers_delegatesToRepository() {
        // given
        List<Customer> found = List.of(buildCustomer(CUSTOMER_ID));
        when(customerRepository.searchByQuery(TENANT_ID, "kowalsk", 20)).thenReturn(found);

        // when
        List<CustomerResponse> results = customerService.searchCustomers("kowalsk", TENANT_ID, 20);

        // then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).customerId()).isEqualTo(CUSTOMER_ID);
        verify(customerRepository).searchByQuery(TENANT_ID, "kowalsk", 20);
    }

    @Test
    @DisplayName("searchCustomers – limit domyślnie 20 gdy null")
    void searchCustomers_usesDefaultLimitWhenNull() {
        // given
        when(customerRepository.searchByQuery(eq(TENANT_ID), eq("test"), eq(20)))
                .thenReturn(List.of());

        // when
        customerService.searchCustomers("test", TENANT_ID, null);

        // then
        verify(customerRepository).searchByQuery(TENANT_ID, "test", 20);
    }

    @Test
    @DisplayName("searchCustomers – ogranicza limit do max 100")
    void searchCustomers_capsLimitAtHundred() {
        // given
        when(customerRepository.searchByQuery(eq(TENANT_ID), eq("test"), eq(100)))
                .thenReturn(List.of());

        // when
        customerService.searchCustomers("test", TENANT_ID, 999);

        // then
        verify(customerRepository).searchByQuery(TENANT_ID, "test", 100);
    }

    // =========================================================================
    // Lista klientów
    // =========================================================================

    @Test
    @DisplayName("listCustomers – zwraca mapę z metadanymi paginacji")
    void listCustomers_returnsPaginationMetadata() {
        // given
        List<Customer> page = List.of(buildCustomer(CUSTOMER_ID));
        when(customerRepository.findByTenantIdPaged(TENANT_ID, 0, 20)).thenReturn(page);
        when(customerRepository.countByTenantId(TENANT_ID)).thenReturn(45L);

        // when
        Map<String, Object> result = customerService.listCustomers(TENANT_ID, 0, 20);

        // then
        assertThat(result.get("page")).isEqualTo(0);
        assertThat(result.get("size")).isEqualTo(20);
        assertThat(result.get("totalElements")).isEqualTo(45L);
        assertThat(result.get("totalPages")).isEqualTo(3);
        assertThat(result.get("first")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        List<CustomerResponse> content = (List<CustomerResponse>) result.get("content");
        assertThat(content).hasSize(1);
    }

    // =========================================================================
    // Aktualizacja klienta
    // =========================================================================

    @Test
    @DisplayName("updateCustomer – aktualizuje tylko podane pola (PATCH semantics)")
    void updateCustomer_updatesOnlyProvidedFields() {
        // given
        Customer existing = buildCustomer(CUSTOMER_ID);
        existing.setFirstName("Stare imię");
        existing.setLastName("Stare nazwisko");
        when(customerRepository.findById(CUSTOMER_ID, TENANT_ID)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateCustomerRequest request = new UpdateCustomerRequest(
                "Nowe imię", null, null, null, null, null
        );

        // when
        CustomerResponse response = customerService.updateCustomer(CUSTOMER_ID, request, TENANT_ID);

        // then
        assertThat(response.firstName()).isEqualTo("Nowe imię");
        assertThat(response.lastName()).isEqualTo("Stare nazwisko"); // nie zmieniono
        verify(customerRepository).save(argThat(c ->
                "Nowe imię".equals(c.getFirstName())
                && "Stare nazwisko".equals(c.getLastName())
        ));
    }

    @Test
    @DisplayName("updateCustomer – rzuca EntityNotFoundException gdy klient nie istnieje")
    void updateCustomer_throwsWhenNotFound() {
        // given
        when(customerRepository.findById(CUSTOMER_ID, TENANT_ID)).thenReturn(Optional.empty());
        UpdateCustomerRequest request = new UpdateCustomerRequest("Jan", null, null, null, null, null);

        // when / then
        assertThatThrownBy(() -> customerService.updateCustomer(CUSTOMER_ID, request, TENANT_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("updateCustomer – pusta lista phone=[] czyści tablicę")
    void updateCustomer_emptyListClearsPhones() {
        // given
        Customer existing = buildCustomer(CUSTOMER_ID);
        existing.setPhone(List.of("+48501234567"));
        when(customerRepository.findById(CUSTOMER_ID, TENANT_ID)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateCustomerRequest request = new UpdateCustomerRequest(null, null, List.of(), null, null, null);

        // when
        CustomerResponse response = customerService.updateCustomer(CUSTOMER_ID, request, TENANT_ID);

        // then
        assertThat(response.phone()).isEmpty();
    }

    // =========================================================================
    // Anonimizacja RODO
    // =========================================================================

    @Test
    @DisplayName("anonymizeCustomer – wywołuje repozytorium i nie rzuca wyjątku")
    void anonymizeCustomer_callsRepositoryWhenRecordExists() {
        // given
        when(customerRepository.anonymize(CUSTOMER_ID, TENANT_ID)).thenReturn(1);

        // when / then – nie rzuca wyjątku
        assertThatNoException().isThrownBy(() ->
                customerService.anonymizeCustomer(CUSTOMER_ID, TENANT_ID));

        verify(customerRepository).anonymize(CUSTOMER_ID, TENANT_ID);
    }

    @Test
    @DisplayName("anonymizeCustomer – rzuca EntityNotFoundException gdy rekord nie istnieje")
    void anonymizeCustomer_throwsWhenNotFound() {
        // given
        when(customerRepository.anonymize(CUSTOMER_ID, TENANT_ID)).thenReturn(0);

        // when / then
        assertThatThrownBy(() -> customerService.anonymizeCustomer(CUSTOMER_ID, TENANT_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(CUSTOMER_ID.toString());
    }

    // =========================================================================
    // Auto-tworzenie z UNKNOWN_CALLER
    // =========================================================================

    @Test
    @DisplayName("handleUnknownCaller – tworzy nowy profil gdy klient nie istnieje")
    void handleUnknownCaller_createsNewProfileWhenNotFound() {
        // given
        String phone = "+48501999888";
        Customer created = Customer.builder()
                .customerId(CUSTOMER_ID)
                .tenantId(TENANT_ID)
                .phone(List.of(phone))
                .email(new ArrayList<>())
                .customFields(new HashMap<>())
                .gdprConsent(new HashMap<>())
                .source("AUTO")
                .deleted(false)
                .createdAt(Instant.now())
                .build();

        when(customerRepository.findByPhoneNumber(phone, TENANT_ID)).thenReturn(Optional.empty());
        when(customerRepository.createFromUnknownCaller(TENANT_ID, phone)).thenReturn(created);

        // when
        CustomerResponse response = customerService.handleUnknownCaller(phone, TENANT_ID);

        // then
        assertThat(response.source()).isEqualTo("AUTO");
        assertThat(response.phone()).contains(phone);

        verify(customerRepository).createFromUnknownCaller(TENANT_ID, phone);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_EVENTS),
                eq("customer.created"),
                any(Map.class)
        );
    }

    @Test
    @DisplayName("handleUnknownCaller – zwraca istniejący profil gdy numer już zarejestrowany")
    void handleUnknownCaller_returnsExistingProfileWhenFound() {
        // given
        String phone = "+48501999888";
        Customer existing = buildCustomer(CUSTOMER_ID);
        existing.setPhone(List.of(phone));

        when(customerRepository.findByPhoneNumber(phone, TENANT_ID)).thenReturn(Optional.of(existing));

        // when
        CustomerResponse response = customerService.handleUnknownCaller(phone, TENANT_ID);

        // then
        assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
        verify(customerRepository, never()).createFromUnknownCaller(any(), any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("handleUnknownCallerEvent – loguje warning i nie rzuca wyjątku gdy brakuje pól")
    void handleUnknownCallerEvent_handlesInvalidEventGracefully() {
        // given
        Map<String, String> invalidEvent = Map.of("someKey", "someValue");

        // when / then – nie rzuca wyjątku
        assertThatNoException().isThrownBy(() ->
                customerService.handleUnknownCallerEvent(invalidEvent));

        verify(customerRepository, never()).createFromUnknownCaller(any(), any());
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Customer buildCustomer(UUID customerId) {
        return Customer.builder()
                .customerId(customerId)
                .tenantId(TENANT_ID)
                .firstName("Jan")
                .lastName("Kowalski")
                .phone(new ArrayList<>(List.of("+48501234567")))
                .email(new ArrayList<>(List.of("jan@example.com")))
                .customFields(new HashMap<>())
                .gdprConsent(new HashMap<>())
                .source("MANUAL")
                .deleted(false)
                .createdAt(Instant.now())
                .build();
    }
}
