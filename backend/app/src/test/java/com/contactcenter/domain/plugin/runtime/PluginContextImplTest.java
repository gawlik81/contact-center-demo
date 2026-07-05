package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.pluginsdk.model.ContactView;
import com.contactcenter.pluginsdk.model.CustomerView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy {@link PluginContextImpl} — scoping per tenant (ARCHITECTURE.md §11.3, kryterium
 * akceptacji BE-101 #3: {@code getCustomer(id)} zwraca dane tylko dla tenanta z którym ta
 * instancja {@code PluginContextImpl} została skonstruowana, niezależnie od tego, jaki
 * {@code tenantId} "próbowałby" przekazać kod pluginu — co jest niemożliwe, bo
 * {@code PluginContext} (plugin-sdk) nie ma parametru tenantId w żadnej metodzie).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginContextImpl – tenantId zamrożony przy konstrukcji, plugin nie ma sposobu go zmienić")
class PluginContextImplTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PLUGIN_KEY = "acme-crm-sync";

    @Mock private CustomerService customerService;
    @Mock private ContactService contactService;

    @Nested
    @DisplayName("getCustomer")
    class GetCustomerTests {

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: woła CustomerService.findById z tenantId zamrożonym w konstruktorze")
        void usesTenantIdFromConstructor() {
            UUID customerId = UUID.randomUUID();
            Customer customer = customerEntity(customerId, TENANT_A);
            when(customerService.findById(customerId, TENANT_A)).thenReturn(Optional.of(customer));

            PluginContextImpl context = newContext(TENANT_A);

            CustomerView view = context.getCustomer(customerId);

            assertThat(view.customerId()).isEqualTo(customerId);
            verify(customerService).findById(customerId, TENANT_A);
            // Nigdy nie wołane z tenantId innym niż ten z konstruktora — nie ma jak, bo
            // PluginContext#getCustomer nie przyjmuje tenantId jako parametru (plugin-sdk).
            verify(customerService, never()).findById(eq(customerId), eq(TENANT_B));
        }

        @Test
        @DisplayName("Klient istniejący tylko dla innego tenanta jest niewidoczny — ResourceNotFoundException")
        void customerOfOtherTenantIsNotVisible() {
            UUID customerId = UUID.randomUUID();
            // Symulujemy: klient istnieje, ale tylko gdy zapytanie dotyczy TENANT_B — kontekst
            // zbudowany dla TENANT_A nigdy nie może go zobaczyć, bo woła findById(id, TENANT_A).
            when(customerService.findById(customerId, TENANT_A)).thenReturn(Optional.empty());

            PluginContextImpl context = newContext(TENANT_A);

            assertThatThrownBy(() -> context.getCustomer(customerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Dwie instancje PluginContextImpl (różne tenanty) na ten sam customerId zwracają niezależne wywołania")
        void twoContextsForDifferentTenantsQueryIndependently() {
            UUID customerId = UUID.randomUUID();
            when(customerService.findById(customerId, TENANT_A))
                    .thenReturn(Optional.of(customerEntity(customerId, TENANT_A)));
            when(customerService.findById(customerId, TENANT_B))
                    .thenReturn(Optional.of(customerEntity(customerId, TENANT_B)));

            PluginContextImpl contextA = newContext(TENANT_A);
            PluginContextImpl contextB = newContext(TENANT_B);

            contextA.getCustomer(customerId);
            contextB.getCustomer(customerId);

            verify(customerService).findById(customerId, TENANT_A);
            verify(customerService).findById(customerId, TENANT_B);
        }
    }

    @Nested
    @DisplayName("updateCustomerFields")
    class UpdateCustomerFieldsTests {

        @Test
        @DisplayName("Zapis tylko do namespace custom_fields.plugins.<pluginKey>, nigdy flat merge")
        void writesOnlyToNamespacedPluginBag() {
            UUID customerId = UUID.randomUUID();
            Customer customer = customerEntity(customerId, TENANT_A);
            customer.setCustomFields(new HashMap<>(Map.of("accountNumber", "123")));
            when(customerService.findById(customerId, TENANT_A)).thenReturn(Optional.of(customer));

            PluginContextImpl context = newContext(TENANT_A);
            context.updateCustomerFields(customerId, Map.of("externalId", "ext-42"));

            @SuppressWarnings("unchecked")
            var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(customerService).updateCustomFields(eq(customerId), eq(TENANT_A), captor.capture());

            Map<String, Object> savedFields = captor.getValue();
            assertThat(savedFields).containsKey("accountNumber"); // pole platformy zachowane
            assertThat(savedFields).containsKey("plugins");

            @SuppressWarnings("unchecked")
            Map<String, Object> plugins = (Map<String, Object>) savedFields.get("plugins");
            @SuppressWarnings("unchecked")
            Map<String, Object> ownNamespace = (Map<String, Object>) plugins.get(PLUGIN_KEY);
            assertThat(ownNamespace).containsEntry("externalId", "ext-42");
        }
    }

    @Nested
    @DisplayName("getContact")
    class GetContactTests {

        @Test
        @DisplayName("woła ContactService.findContactEntity z tenantId zamrożonym w konstruktorze")
        void usesTenantIdFromConstructor() {
            UUID contactId = UUID.randomUUID();
            Contact contact = contactEntity(contactId, TENANT_A);
            when(contactService.findContactEntity(contactId, TENANT_A)).thenReturn(Optional.of(contact));

            PluginContextImpl context = newContext(TENANT_A);
            ContactView view = context.getContact(contactId);

            assertThat(view.contactId()).isEqualTo(contactId);
            verify(contactService).findContactEntity(contactId, TENANT_A);
        }

        @Test
        @DisplayName("Kontakt nieistniejący dla tego tenanta → ResourceNotFoundException")
        void contactNotFoundForTenant() {
            UUID contactId = UUID.randomUUID();
            when(contactService.findContactEntity(contactId, TENANT_A)).thenReturn(Optional.empty());

            PluginContextImpl context = newContext(TENANT_A);

            assertThatThrownBy(() -> context.getContact(contactId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("appendContactNote")
    class AppendContactNoteTests {

        @Test
        @DisplayName("Notatka jest atrybuowana do pluginu i dopisana do istniejących notatek")
        void appendsAttributedNote() {
            UUID contactId = UUID.randomUUID();
            Contact contact = contactEntity(contactId, TENANT_A);
            contact.setNotes("Istniejąca notatka agenta");
            when(contactService.findContactEntity(contactId, TENANT_A)).thenReturn(Optional.of(contact));

            PluginContextImpl context = newContext(TENANT_A);
            context.appendContactNote(contactId, "CRM sync zakończony");

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(contactService).updateNotes(eq(contactId), eq(TENANT_A), captor.capture());

            assertThat(captor.getValue())
                    .contains("Istniejąca notatka agenta")
                    .contains("[Plugin: " + PLUGIN_KEY + "]")
                    .contains("CRM sync zakończony");
        }
    }

    @Nested
    @DisplayName("httpClient / config")
    class FacadeTests {

        @Test
        @DisplayName("httpClient() egress poza allow-listą rzuca SecurityException")
        void httpClientRejectsHostOutsideAllowList() {
            PluginContextImpl context = new PluginContextImpl(
                    TENANT_A, PLUGIN_KEY, List.of("http:egress:api.acme-crm.example"), null,
                    customerService, contactService);

            assertThatThrownBy(() -> context.httpClient().get("https://evil.example/x", Map.of()))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("config() odczytuje wartości z installationConfig JSON")
        void configReadsFromInstallationConfigJson() {
            PluginContextImpl context = new PluginContextImpl(
                    TENANT_A, PLUGIN_KEY, List.of(), "{\"apiKey\":\"secret-123\"}",
                    customerService, contactService);

            assertThat(context.config().get("apiKey")).contains("secret-123");
            assertThat(context.config().getOrDefault("missing", "default")).isEqualTo("default");
        }

        @Test
        @DisplayName("dbClient() egress poza allow-listą rzuca SecurityException")
        void dbClientRejectsHostOutsideAllowList() {
            String installationConfigJson = "{\"jdbcUrl\":\"jdbc:postgresql://evil.example:5432/db\","
                    + "\"dbUsername\":\"u\",\"dbPassword\":\"p\"}";
            PluginContextImpl context = new PluginContextImpl(
                    TENANT_A, PLUGIN_KEY, List.of("db:egress:db.acme-crm.example:5432"),
                    installationConfigJson, customerService, contactService);

            assertThatThrownBy(() -> context.dbClient().executeUpdate("INSERT INTO x VALUES (?)", List.of(1)))
                    .isInstanceOf(SecurityException.class);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PluginContextImpl newContext(UUID tenantId) {
        return new PluginContextImpl(tenantId, PLUGIN_KEY, List.of(), null, customerService, contactService);
    }

    private static Customer customerEntity(UUID customerId, UUID tenantId) {
        return Customer.builder()
                .customerId(customerId)
                .tenantId(tenantId)
                .firstName("Jan")
                .lastName("Kowalski")
                .phone(List.of("+48501234567"))
                .email(List.of("jan@example.com"))
                .customFields(new HashMap<>())
                .createdAt(Instant.now())
                .build();
    }

    private static Contact contactEntity(UUID contactId, UUID tenantId) {
        return Contact.builder()
                .contactId(contactId)
                .tenantId(tenantId)
                .channel("PHONE")
                .direction("INBOUND")
                .status("ACTIVE")
                .startedAt(Instant.now())
                .build();
    }
}
