package com.contactcenter.api;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.customer.CustomerController;
import com.contactcenter.api.customer.dto.CreateCustomerRequest;
import com.contactcenter.api.customer.dto.CustomerResponse;
import com.contactcenter.api.customer.dto.UpdateCustomerRequest;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link CustomerController}.
 *
 * <p>Weryfikuje routing, mapowanie żądań i odpowiedzi, obsługę błędów
 * i poprawność kodów HTTP.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerController – REST API klientów")
class CustomerControllerTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private CustomerService customerService;

    @InjectMocks
    private CustomerController customerController;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserRole("SUPERVISOR");
        // ServletUriComponentsBuilder.fromCurrentRequest() wymaga aktywnego HttpServletRequest
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.setRequestURI("/api/customers");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    // =========================================================================
    // POST /api/customers
    // =========================================================================

    @Test
    @DisplayName("createCustomer – zwraca HTTP 201 Created z Location header")
    void createCustomer_returns201WithLocation() {
        // given
        CreateCustomerRequest request = new CreateCustomerRequest(
                "Jan", "Kowalski", null,
                List.of("+48501234567"),
                List.of("jan@example.com"),
                null, null, "MANUAL"
        );
        CustomerResponse response = buildCustomerResponse(CUSTOMER_ID);
        when(customerService.createCustomer(request, TENANT_ID)).thenReturn(response);

        // when
        ResponseEntity<CustomerResponse> result = customerController.createCustomer(request);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().customerId()).isEqualTo(CUSTOMER_ID);

        verify(customerService).createCustomer(request, TENANT_ID);
    }

    @Test
    @DisplayName("createCustomer – z externalId – zwraca HTTP 201 z externalId w odpowiedzi")
    void createCustomer_withExternalId_returns201WithExternalId() {
        // given
        CreateCustomerRequest request = new CreateCustomerRequest(
                "Jan", "Kowalski", "CRM-99999",
                List.of("+48501234567"),
                List.of("jan@example.com"),
                null, null, "MANUAL"
        );
        CustomerResponse response = buildCustomerResponse(CUSTOMER_ID, "CRM-99999");
        when(customerService.createCustomer(request, TENANT_ID)).thenReturn(response);

        // when
        ResponseEntity<CustomerResponse> result = customerController.createCustomer(request);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().externalId()).isEqualTo("CRM-99999");
    }

    @Test
    @DisplayName("createCustomer – duplikat externalId – propaguje ConflictException mapowany na HTTP 409")
    void createCustomer_duplicateExternalId_propagatesConflictMappedTo409() {
        // given
        CreateCustomerRequest request = new CreateCustomerRequest(
                "Jan", "Kowalski", "CRM-12345",
                List.of("+48501234567"),
                List.of("jan@example.com"),
                null, null, "MANUAL"
        );
        ConflictException conflict = new ConflictException(
                "Klient z identyfikatorem zewnętrznym 'CRM-12345' już istnieje");
        when(customerService.createCustomer(request, TENANT_ID)).thenThrow(conflict);

        // when / then – kontroler propaguje wyjątek (mapowanie na HTTP dzieje się w GlobalExceptionHandler)
        assertThatThrownBy(() -> customerController.createCustomer(request))
                .isInstanceOf(ConflictException.class);

        // GlobalExceptionHandler mapuje ConflictException na HTTP 409 Conflict
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
        ResponseEntity<?> handled = exceptionHandler.handleConflictException(conflict, mock(WebRequest.class));
        assertThat(handled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // =========================================================================
    // GET /api/customers
    // =========================================================================

    @Test
    @DisplayName("listOrSearchCustomers – wykonuje fuzzy search gdy podano parametr q")
    void listOrSearchCustomers_executesFuzzySearchWhenQPresent() {
        // given
        List<CustomerResponse> searchResults = List.of(buildCustomerResponse(CUSTOMER_ID));
        when(customerService.searchCustomers("kowalsk", TENANT_ID, 20)).thenReturn(searchResults);

        // when
        ResponseEntity<?> result = customerController.listOrSearchCustomers("kowalsk", 0, 20);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        PagedResponse<CustomerResponse> body = (PagedResponse<CustomerResponse>) result.getBody();
        assertThat(body).isNotNull();
        assertThat(body.content()).hasSize(1);

        verify(customerService).searchCustomers("kowalsk", TENANT_ID, 20);
        verify(customerService, never()).listCustomers(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("listOrSearchCustomers – przycina spacje z parametru q")
    void listOrSearchCustomers_trimsFuzzyQueryParam() {
        // given
        when(customerService.searchCustomers("kowalsk", TENANT_ID, 20)).thenReturn(List.of());

        // when
        customerController.listOrSearchCustomers("  kowalsk  ", 0, 20);

        // then
        verify(customerService).searchCustomers("kowalsk", TENANT_ID, 20);
    }

    @Test
    @DisplayName("listOrSearchCustomers – zwraca paginowaną listę gdy brak parametru q")
    void listOrSearchCustomers_returnsPaginatedListWhenNoQuery() {
        // given
        PagedResponse<CustomerResponse> pagedResult =
                new PagedResponse<>(List.of(), 0, 20, 0L, 0, true, true);

        when(customerService.listCustomers(TENANT_ID, 0, 20)).thenReturn(pagedResult);

        // when
        ResponseEntity<?> result = customerController.listOrSearchCustomers(null, 0, 20);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(customerService).listCustomers(TENANT_ID, 0, 20);
        verify(customerService, never()).searchCustomers(any(), any(), any());
    }

    @Test
    @DisplayName("listOrSearchCustomers – traktuje pusty string q jak brak parametru")
    void listOrSearchCustomers_treatsBlankQAsNoQuery() {
        // given
        PagedResponse<CustomerResponse> pagedResult =
                new PagedResponse<>(List.of(), 0, 20, 0L, 0, true, true);
        when(customerService.listCustomers(TENANT_ID, 0, 20)).thenReturn(pagedResult);

        // when
        customerController.listOrSearchCustomers("   ", 0, 20);

        // then
        verify(customerService).listCustomers(TENANT_ID, 0, 20);
        verify(customerService, never()).searchCustomers(any(), any(), any());
    }

    // =========================================================================
    // GET /api/customers/{id}
    // =========================================================================

    @Test
    @DisplayName("getCustomer – zwraca HTTP 200 z danymi klienta")
    void getCustomer_returns200WithCustomerData() {
        // given
        CustomerResponse response = buildCustomerResponse(CUSTOMER_ID);
        when(customerService.getCustomer(CUSTOMER_ID, TENANT_ID)).thenReturn(response);

        // when
        ResponseEntity<CustomerResponse> result = customerController.getCustomer(CUSTOMER_ID);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().customerId()).isEqualTo(CUSTOMER_ID);
        verify(customerService).getCustomer(CUSTOMER_ID, TENANT_ID);
    }

    @Test
    @DisplayName("getCustomer – propaguje EntityNotFoundException gdy klient nie istnieje")
    void getCustomer_propagatesEntityNotFoundExceptionWhenNotFound() {
        // given
        when(customerService.getCustomer(CUSTOMER_ID, TENANT_ID))
                .thenThrow(new EntityNotFoundException("Klient nie istnieje: " + CUSTOMER_ID));

        // when / then
        assertThatThrownBy(() -> customerController.getCustomer(CUSTOMER_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // =========================================================================
    // PATCH /api/customers/{id}
    // =========================================================================

    @Test
    @DisplayName("updateCustomer – zwraca HTTP 200 z zaktualizowanymi danymi")
    void updateCustomer_returns200WithUpdatedData() {
        // given
        UpdateCustomerRequest request = new UpdateCustomerRequest(
                "Nowe imię", null, null, null, null, null, null
        );
        CustomerResponse response = buildCustomerResponse(CUSTOMER_ID);
        when(customerService.updateCustomer(CUSTOMER_ID, request, TENANT_ID)).thenReturn(response);

        // when
        ResponseEntity<CustomerResponse> result = customerController.updateCustomer(CUSTOMER_ID, request);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        verify(customerService).updateCustomer(CUSTOMER_ID, request, TENANT_ID);
    }

    @Test
    @DisplayName("updateCustomer – z externalId – zwraca HTTP 200 z externalId w odpowiedzi")
    void updateCustomer_withExternalId_returns200WithExternalId() {
        // given
        UpdateCustomerRequest request = new UpdateCustomerRequest(
                null, null, "CRM-88888", null, null, null, null
        );
        CustomerResponse response = buildCustomerResponse(CUSTOMER_ID, "CRM-88888");
        when(customerService.updateCustomer(CUSTOMER_ID, request, TENANT_ID)).thenReturn(response);

        // when
        ResponseEntity<CustomerResponse> result = customerController.updateCustomer(CUSTOMER_ID, request);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().externalId()).isEqualTo("CRM-88888");
    }

    @Test
    @DisplayName("updateCustomer – duplikat externalId – propaguje ConflictException mapowany na HTTP 409")
    void updateCustomer_duplicateExternalId_propagatesConflictMappedTo409() {
        // given
        UpdateCustomerRequest request = new UpdateCustomerRequest(
                null, null, "CRM-TAKEN", null, null, null, null
        );
        ConflictException conflict = new ConflictException(
                "Klient z identyfikatorem zewnętrznym 'CRM-TAKEN' już istnieje");
        when(customerService.updateCustomer(CUSTOMER_ID, request, TENANT_ID)).thenThrow(conflict);

        // when / then
        assertThatThrownBy(() -> customerController.updateCustomer(CUSTOMER_ID, request))
                .isInstanceOf(ConflictException.class);

        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
        ResponseEntity<?> handled = exceptionHandler.handleConflictException(conflict, mock(WebRequest.class));
        assertThat(handled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // =========================================================================
    // DELETE /api/customers/{id}
    // =========================================================================

    @Test
    @DisplayName("anonymizeCustomer – zwraca HTTP 204 No Content po anonimizacji")
    void anonymizeCustomer_returns204WhenSuccessful() {
        // given
        doNothing().when(customerService).anonymizeCustomer(CUSTOMER_ID, TENANT_ID);

        // when
        ResponseEntity<Void> result = customerController.anonymizeCustomer(CUSTOMER_ID);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(customerService).anonymizeCustomer(CUSTOMER_ID, TENANT_ID);
    }

    @Test
    @DisplayName("anonymizeCustomer – propaguje EntityNotFoundException gdy klient nie istnieje")
    void anonymizeCustomer_propagatesEntityNotFoundExceptionWhenNotFound() {
        // given
        doThrow(new EntityNotFoundException("Klient nie istnieje: " + CUSTOMER_ID))
                .when(customerService).anonymizeCustomer(CUSTOMER_ID, TENANT_ID);

        // when / then
        assertThatThrownBy(() -> customerController.anonymizeCustomer(CUSTOMER_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private CustomerResponse buildCustomerResponse(UUID customerId) {
        return buildCustomerResponse(customerId, null);
    }

    private CustomerResponse buildCustomerResponse(UUID customerId, String externalId) {
        return new CustomerResponse(
                customerId,
                TENANT_ID,
                "Jan",
                "Kowalski",
                externalId,
                List.of("+48501234567"),
                List.of("jan@example.com"),
                new HashMap<>(),
                new HashMap<>(),
                "MANUAL",
                false,
                Instant.now(),
                null,
                null
        );
    }
}
