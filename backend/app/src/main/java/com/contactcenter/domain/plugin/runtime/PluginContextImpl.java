package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.pluginsdk.DbEgressClient;
import com.contactcenter.pluginsdk.HttpEgressClient;
import com.contactcenter.pluginsdk.PluginConfig;
import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginLogger;
import com.contactcenter.pluginsdk.model.ContactView;
import com.contactcenter.pluginsdk.model.CustomerView;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementacja {@link PluginContext} — <strong>jedyny</strong> obiekt przekazywany do
 * pluginu (ARCHITECTURE.md §11.3, §11.6).
 *
 * <p><strong>Reguła krytyczna bezpieczeństwa (RT-10, kryterium akceptacji BE-101):</strong>
 * {@code tenantId} jest przyjmowany przez konstruktor, ale ten konstruktor jest wywoływany
 * <strong>wyłącznie</strong> przez {@link PluginRuntimeManagerImpl} (i w przyszłości przez
 * {@code ExtensionPointPublisher}/BE-102) z wartością {@code TenantContext.getTenantId()}
 * odczytaną na wątku WYKONUJĄCYM operację — <strong>nigdy</strong> z wartości pochodzącej od
 * samego pluginu. Plugin nie ma żadnego API do wpłynięcia na to, jaki {@code tenantId} zostanie
 * użyty: nie istnieje metoda {@code setTenantId}, nie ma settera, pole jest {@code final}.
 * Każda metoda tej klasy, która dotyka danych, woła normalne tenant-aware serwisy
 * ({@link CustomerService}, {@link ContactService}) z tym jednym, zamrożonym przy konstrukcji
 * {@code tenantId} — identycznie jak zwykłe wywołanie żądania HTTP tego tenanta.
 *
 * <p>Instancja jest tworzona <strong>per wywołanie</strong> (per {@code onActivate}, i w
 * przyszłości per invocation hooka BE-102) — nie jest cache'owana ani współdzielona między
 * wywołaniami, więc nie ma ryzyka, że stary {@code tenantId} "przecieknie" do kolejnego
 * wywołania na innym wątku.
 */
@Slf4j
final class PluginContextImpl implements PluginContext {

    private final UUID tenantId;
    private final String pluginKey;
    private final CustomerService customerService;
    private final ContactService contactService;
    private final HttpEgressClient httpEgressClient;
    private final DbEgressClient dbEgressClient;
    private final PluginLogger logger;
    private final PluginConfig config;

    /**
     * @param tenantId            tenant dla którego wykonywana jest ta inwokacja — MUSI pochodzić
     *                            z {@code TenantContext.getTenantId()} wywołującego wątku, nigdy
     *                            z parametru kontrolowanego przez plugin
     * @param pluginKey           identyfikator pluginu (do logowania/namespacingu custom_fields)
     * @param grantedPermissions  uprawnienia zatwierdzone przy instalacji (do allow-listy egress)
     * @param installationConfigJson surowa konfiguracja tenanta dla tej instalacji (JSON)
     * @param customerService    serwis domenowy klientów (tenant-aware)
     * @param contactService     serwis domenowy kontaktów (tenant-aware)
     */
    PluginContextImpl(
            UUID tenantId,
            String pluginKey,
            List<String> grantedPermissions,
            String installationConfigJson,
            CustomerService customerService,
            ContactService contactService) {
        this.tenantId = tenantId;
        this.pluginKey = pluginKey;
        this.customerService = customerService;
        this.contactService = contactService;
        this.httpEgressClient = new PluginHttpEgressClientImpl(grantedPermissions, tenantId, pluginKey);
        this.logger = new PluginLoggerImpl(tenantId, pluginKey);
        this.config = new PluginConfigImpl(installationConfigJson);
        // Dane połączenia DB (jdbcUrl/dbUsername/dbPassword) pochodzą z tej samej
        // installation_config co reszta konfiguracji tenanta (już odszyfrowanej w `config`) —
        // czytane stąd, żeby nie duplikować parsowania JSON-a (PluginConfigImpl).
        this.dbEgressClient = new PluginDbEgressClientImpl(
                grantedPermissions,
                tenantId,
                pluginKey,
                this.config.get("jdbcUrl").orElse(null),
                this.config.get("dbUsername").orElse(null),
                this.config.get("dbPassword").orElse(null));
    }

    @Override
    public CustomerView getCustomer(UUID customerId) {
        Customer customer = customerService.findById(customerId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Klient nie istnieje lub nie należy do tenanta: " + customerId));
        return toCustomerView(customer);
    }

    @Override
    public void updateCustomerFields(UUID customerId, Map<String, Object> customFields) {
        Customer customer = customerService.findById(customerId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Klient nie istnieje lub nie należy do tenanta: " + customerId));

        // Zapis wyłącznie do custom_fields.plugins.<pluginKey> — nigdy flat merge, nigdy
        // do istniejącej typowanej kolumny (reguła anti-overloaded-column, CLAUDE.md;
        // kontrakt PluginContext#updateCustomerFields, plugin-sdk BE-097).
        Map<String, Object> existingCustomFields = customer.getCustomFields() != null
                ? new HashMap<>(customer.getCustomFields())
                : new HashMap<>();

        @SuppressWarnings("unchecked")
        Map<String, Object> pluginsNamespace = (Map<String, Object>) existingCustomFields
                .computeIfAbsent("plugins", k -> new HashMap<String, Object>());

        @SuppressWarnings("unchecked")
        Map<String, Object> ownNamespace = (Map<String, Object>) pluginsNamespace
                .computeIfAbsent(pluginKey, k -> new HashMap<String, Object>());

        if (customFields != null) {
            ownNamespace.putAll(customFields);
        }

        customerService.updateCustomFields(customerId, tenantId, existingCustomFields);

        log.debug("[PluginContext] updateCustomerFields: tenant={}, plugin={}, customerId={}, keys={}",
                tenantId, pluginKey, customerId,
                customFields != null ? customFields.keySet() : Map.of().keySet());
    }

    @Override
    public ContactView getContact(UUID contactId) {
        Contact contact = contactService.findContactEntity(contactId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Kontakt nie istnieje lub nie należy do tenanta: " + contactId));
        return toContactView(contact);
    }

    @Override
    public void appendContactNote(UUID contactId, String note) {
        Contact contact = contactService.findContactEntity(contactId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Kontakt nie istnieje lub nie należy do tenanta: " + contactId));

        String existingNotes = contact.getNotes();
        String attributedNote = "[Plugin: " + pluginKey + "] " + (note != null ? note : "");
        String updatedNotes = (existingNotes == null || existingNotes.isBlank())
                ? attributedNote
                : existingNotes + System.lineSeparator() + attributedNote;

        contactService.updateNotes(contactId, tenantId, updatedNotes);
    }

    @Override
    public HttpEgressClient httpClient() {
        return httpEgressClient;
    }

    @Override
    public DbEgressClient dbClient() {
        return dbEgressClient;
    }

    @Override
    public PluginLogger logger() {
        return logger;
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    // =========================================================================
    // Mapowanie encja -> DTO SDK (record immutable, nigdy encja JPA — kontrakt SDK)
    // =========================================================================

    private static CustomerView toCustomerView(Customer customer) {
        return new CustomerView(
                customer.getCustomerId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail() != null ? List.copyOf(customer.getEmail()) : List.of(),
                customer.getPhone() != null ? List.copyOf(customer.getPhone()) : List.of(),
                customer.getCustomFields() != null ? Map.copyOf(customer.getCustomFields()) : Map.of(),
                customer.getCreatedAt());
    }

    private static ContactView toContactView(Contact contact) {
        return new ContactView(
                contact.getContactId(),
                contact.getCustomerId(),
                contact.getChannel(),
                contact.getDirection(),
                contact.getStatus(),
                contact.getAgentId(),
                contact.getQueueId(),
                contact.getStartedAt(),
                contact.getEndedAt());
    }
}
