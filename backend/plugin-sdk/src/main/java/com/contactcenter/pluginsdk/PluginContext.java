package com.contactcenter.pluginsdk;

import com.contactcenter.pluginsdk.model.ContactView;
import com.contactcenter.pluginsdk.model.CustomerView;
import java.util.Map;
import java.util.UUID;

/**
 * The <b>only</b> object through which a plugin reaches the platform.
 *
 * <p>Implemented by {@code backend/app}, instantiated per-invocation with the calling tenant's
 * ID already baked in — a plugin never supplies or controls which tenant's data a call
 * resolves against. Every method that touches data is backed by the platform's normal
 * tenant-aware repositories (see {@code TenantAwareRepository} in the host application), so
 * the same multi-tenancy guarantees that apply to ordinary requests apply here.
 *
 * <p>A plugin can never obtain a reference to the Spring {@code ApplicationContext}, a JPA
 * repository, or any other application bean through this facade — the object graph reachable
 * from {@code PluginContext} contains only the interfaces and immutable DTOs declared in this
 * SDK module.
 */
public interface PluginContext {

    /**
     * Fetches a read-only snapshot of a customer, scoped to the calling tenant.
     *
     * @param customerId identifier of the customer to fetch
     * @return an immutable view of the customer
     * @throws RuntimeException if no customer with this ID exists for the calling tenant
     *                          (the exact exception type is host-implementation-defined; a
     *                          plugin should treat any thrown exception as "customer not
     *                          accessible" and fail gracefully)
     */
    CustomerView getCustomer(UUID customerId);

    /**
     * Writes plugin-owned data into the customer record.
     *
     * <p>This is the <b>only</b> way a plugin may persist data about a customer. The host
     * implementation writes exclusively into the {@code customer.custom_fields} JSONB bag,
     * namespaced under the plugin's own key (e.g. {@code custom_fields.plugins.<pluginKey>})
     * — never into a core, typed platform column. A plugin cannot overwrite another plugin's
     * data or any platform-owned field through this method.
     *
     * @param customerId   identifier of the customer to update
     * @param customFields key/value pairs to merge into the plugin's own namespace under this
     *                      customer's {@code custom_fields}; values must be JSON-serializable
     */
    void updateCustomerFields(UUID customerId, Map<String, Object> customFields);

    /**
     * Fetches a read-only snapshot of a contact (a single interaction record), scoped to the
     * calling tenant.
     *
     * @param contactId identifier of the contact to fetch
     * @return an immutable view of the contact
     * @throws RuntimeException if no contact with this ID exists for the calling tenant
     */
    ContactView getContact(UUID contactId);

    /**
     * Appends a free-text note to a contact's history.
     *
     * @param contactId identifier of the contact to annotate
     * @param note       note text to append; the host is responsible for attributing the note
     *                   to the plugin (e.g. prefixing with the plugin's display name) so
     *                   agents/supervisors can distinguish plugin-authored notes from
     *                   agent-authored ones
     */
    void appendContactNote(UUID contactId, String note);

    /**
     * Returns the restricted outbound HTTP client available to this plugin instance.
     *
     * @return an {@link HttpEgressClient} that enforces this installation's declared egress
     *         allow-list
     */
    HttpEgressClient httpClient();

    /**
     * Returns the logger available to this plugin instance.
     *
     * @return a {@link PluginLogger} whose output is captured into the platform's plugin
     *         invocation diagnostics, not the application's own logs
     */
    PluginLogger logger();

    /**
     * Returns read-only access to this installation's tenant-supplied configuration.
     *
     * @return a {@link PluginConfig} scoped to the calling tenant's installation
     */
    PluginConfig config();
}
