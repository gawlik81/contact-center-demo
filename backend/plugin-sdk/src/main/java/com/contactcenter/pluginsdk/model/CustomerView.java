package com.contactcenter.pluginsdk.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable, read-only snapshot of a customer record, scoped to the tenant making the
 * invocation.
 *
 * <p>This is a plain {@code record} — never a JPA entity. A plugin can never obtain a
 * Hibernate-managed object, trigger lazy-loading, or hold a reference into the host's
 * persistence context. The host backend (implementation of {@code PluginContext}) is
 * responsible for mapping the real {@code customer} entity into this view before it is
 * handed to a plugin.
 *
 * @param customerId  unique identifier of the customer
 * @param firstName   customer's first name, or {@code null} if not recorded
 * @param lastName    customer's last name, or {@code null} if not recorded
 * @param emails      known e-mail addresses for this customer, never {@code null} (may be empty)
 * @param phoneNumbers known phone numbers in E.164 format, never {@code null} (may be empty)
 * @param customFields read-only copy of the {@code customer.custom_fields} JSONB bag; this is
 *                     the same namespace a plugin writes into via
 *                     {@code PluginContext#updateCustomerFields}, so a plugin can read back
 *                     its own previously-stored data under {@code plugins.<pluginKey>}
 * @param createdAt   timestamp the customer record was created
 */
public record CustomerView(
        UUID customerId,
        String firstName,
        String lastName,
        java.util.List<String> emails,
        java.util.List<String> phoneNumbers,
        Map<String, Object> customFields,
        Instant createdAt) {
}
