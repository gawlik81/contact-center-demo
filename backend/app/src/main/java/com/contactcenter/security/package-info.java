/**
 * Warstwa bezpieczeństwa – Spring Security, JWT, multi-tenancy.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Konfiguracja Spring Security (SecurityFilterChain)</li>
 *   <li>Filtr JWT (JwtAuthFilter) – ekstrakcja userId, tenantId, roles</li>
 *   <li>TenantFilter – ładowanie TenantContext z JWT claims</li>
 *   <li>TenantContext (ThreadLocal) – izolacja multi-tenant</li>
 *   <li>JwtTokenProvider – generowanie i walidacja JWT (RS256)</li>
 *   <li>MFA – generowanie i weryfikacja TOTP (RFC 6238)</li>
 *   <li>Blacklista tokenów w Redis (przy logout/revoke)</li>
 * </ul>
 */
package com.contactcenter.security;
