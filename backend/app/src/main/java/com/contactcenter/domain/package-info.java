/**
 * Warstwa domenowa – logika biznesowa i encje.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Encje JPA (@Entity) – mapowanie do tabel PostgreSQL</li>
 *   <li>Repozytoria Spring Data JPA (rozszerzające TenantAwareRepository)</li>
 *   <li>Serwisy domenowe (@Service) – logika biznesowa</li>
 *   <li>Zdarzenia domenowe (DomainEvent) publikowane przez RabbitMQ</li>
 *   <li>Wyjątki domenowe (BusinessException, ResourceNotFoundException itd.)</li>
 * </ul>
 *
 * <p>Zasada: warstwa domain/ jest niezależna od frameworka HTTP.
 * Encje domenowe są tu centrum systemu – bez zależności od warstwy api/.
 *
 * <p>Podpakiety:
 * <ul>
 *   <li>{@code tenant/} – zarządzanie tenantami (EPIC-01)</li>
 *   <li>{@code user/} – użytkownicy i role (EPIC-02)</li>
 *   <li>{@code contact/} – historia kontaktów</li>
 *   <li>{@code customer/} – baza klientów (EPIC-09)</li>
 *   <li>{@code campaign/} – kampanie outbound (EPIC-08)</li>
 *   <li>{@code queue/} – kolejki i routing (EPIC-07)</li>
 *   <li>{@code audit/} – audit log (przekrojowe)</li>
 * </ul>
 */
package com.contactcenter.domain;
