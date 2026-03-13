/**
 * Warstwa infrastrukturalna – integracje zewnętrzne i konfiguracja.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Konfiguracja Spring Boot (DataSource, Flyway, Redis, RabbitMQ, OpenAPI)</li>
 *   <li>Implementacje adapterów zewnętrznych (telephony, email, social media)</li>
 *   <li>Klienci HTTP do zewnętrznych API</li>
 *   <li>Konfiguracja S3 (nagrania rozmów)</li>
 *   <li>Schedulery (retencja nagrań, odświeżanie tokenów)</li>
 * </ul>
 *
 * <p>Podpakiety:
 * <ul>
 *   <li>{@code config/} – klasy @Configuration</li>
 *   <li>{@code persistence/} – JPA convertery, custom repozytoria</li>
 *   <li>{@code messaging/} – RabbitMQ producers/consumers</li>
 *   <li>{@code cache/} – Redis helpers</li>
 *   <li>{@code telephony/} – adapter VoIP</li>
 *   <li>{@code email/} – adapter IMAP/SMTP</li>
 *   <li>{@code social/} – adaptery social media</li>
 * </ul>
 */
package com.contactcenter.infrastructure;
