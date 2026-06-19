package com.contactcenter.domain.email;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link TemplateVariableResolverImpl}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Rozwiązanie zmiennych klienta wprost przez {@code customerId} (priorytet, bez {@code Contact})</li>
 *   <li>Rozwiązanie zmiennych klienta przez {@code contactId} (zachowanie historyczne)</li>
 *   <li>Priorytet {@code customerId} nad {@code contactId}, gdy podano oba</li>
 *   <li>Puste wartości gdy brak kontekstu / klient nieznaleziony</li>
 *   <li>Zmienne agenta niezależnie od ścieżki klienta</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateVariableResolverImpl – rozwiązywanie zmiennych predefiniowanych")
class TemplateVariableResolverImplTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AGENT_ID    = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private ContactService contactService;

    @Mock
    private CustomerService customerService;

    @Mock
    private UserService userService;

    private TemplateVariableResolverImpl resolver;

    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        resolver = new TemplateVariableResolverImpl(contactService, customerService, userService);
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    private Customer customer(String firstName, String lastName) {
        return Customer.builder()
                .customerId(CUSTOMER_ID)
                .tenantId(TENANT_ID)
                .firstName(firstName)
                .lastName(lastName)
                .email(List.of("jan.kowalski@example.com"))
                .phone(List.of("+48501234567"))
                .build();
    }

    @Nested
    @DisplayName("resolveForContext(contactId, customerId, agentId)")
    class ResolveWithCustomerIdTests {

        @Test
        @DisplayName("gdy podano customerId – pobiera klienta wprost, bez Contact")
        void shouldResolveCustomerVarsDirectlyByCustomerId() {
            when(customerService.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer("Jan", "Kowalski")));

            Map<String, Object> result = resolver.resolveForContext(null, CUSTOMER_ID, null);

            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_FIRST_NAME.getKey())).isEqualTo("Jan");
            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_LAST_NAME.getKey())).isEqualTo("Kowalski");
            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_FULL_NAME.getKey())).isEqualTo("Jan Kowalski");
            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_EMAIL.getKey()))
                    .isEqualTo("jan.kowalski@example.com");
            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_PHONE.getKey())).isEqualTo("+48501234567");

            verifyNoInteractions(contactService);
        }

        @Test
        @DisplayName("customerId ma priorytet nad contactId, gdy podano oba")
        void shouldPreferCustomerIdOverContactIdWhenBothProvided() {
            when(customerService.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer("Anna", "Nowak")));

            Map<String, Object> result = resolver.resolveForContext(CONTACT_ID, CUSTOMER_ID, null);

            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_FIRST_NAME.getKey())).isEqualTo("Anna");
            verifyNoInteractions(contactService);
        }

        @Test
        @DisplayName("gdy customerId nieznajdywalny w bazie – puste wartości klienta")
        void shouldReturnEmptyCustomerVarsWhenCustomerNotFound() {
            when(customerService.findById(CUSTOMER_ID, TENANT_ID)).thenReturn(Optional.empty());

            Map<String, Object> result = resolver.resolveForContext(null, CUSTOMER_ID, null);

            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_FIRST_NAME.getKey())).isEqualTo("");
            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_EMAIL.getKey())).isEqualTo("");
        }

        @Test
        @DisplayName("gdy customerId == null – fallback do ścieżki przez contactId (zachowanie historyczne)")
        void shouldFallBackToContactPathWhenCustomerIdNull() {
            Contact contact = Contact.builder()
                    .contactId(CONTACT_ID)
                    .tenantId(TENANT_ID)
                    .customerId(CUSTOMER_ID)
                    .build();
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(customerService.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer("Piotr", "Wisniewski")));

            Map<String, Object> result = resolver.resolveForContext(CONTACT_ID, null, null);

            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_FIRST_NAME.getKey())).isEqualTo("Piotr");
        }

        @Test
        @DisplayName("gdy oba identyfikatory null – puste wartości klienta, brak interakcji z serwisami")
        void shouldReturnEmptyCustomerVarsWhenBothIdsNull() {
            Map<String, Object> result = resolver.resolveForContext(null, null, null);

            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_FIRST_NAME.getKey())).isEqualTo("");
            verifyNoInteractions(contactService);
            verifyNoInteractions(customerService);
        }

        @Test
        @DisplayName("rozwiązuje zmienne agenta niezależnie od ścieżki klienta")
        void shouldResolveAgentVarsAlongsideCustomerId() {
            when(customerService.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer("Jan", "Kowalski")));
            AppUser agent = AppUser.builder()
                    .id(AGENT_ID)
                    .tenantId(TENANT_ID)
                    .firstName("Agata")
                    .lastName("Agentowska")
                    .email("agata@example.com")
                    .build();
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(agent));

            Map<String, Object> result = resolver.resolveForContext(null, CUSTOMER_ID, AGENT_ID);

            assertThat(result.get(PredefinedTemplateVariable.AGENT_FIRST_NAME.getKey())).isEqualTo("Agata");
            assertThat(result.get(PredefinedTemplateVariable.AGENT_EMAIL.getKey())).isEqualTo("agata@example.com");
        }
    }

    @Nested
    @DisplayName("resolveForContext(contactId, agentId) – metoda 2-argumentowa (zachowanie historyczne)")
    class LegacyTwoArgOverloadTests {

        @Test
        @DisplayName("delegowanie do nowej metody z customerId=null")
        void shouldDelegateToNewOverloadWithNullCustomerId() {
            Contact contact = Contact.builder()
                    .contactId(CONTACT_ID)
                    .tenantId(TENANT_ID)
                    .customerId(CUSTOMER_ID)
                    .build();
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(customerService.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer("Jan", "Kowalski")));

            Map<String, Object> result = resolver.resolveForContext(CONTACT_ID, null);

            assertThat(result.get(PredefinedTemplateVariable.CUSTOMER_FIRST_NAME.getKey())).isEqualTo("Jan");
        }
    }
}
