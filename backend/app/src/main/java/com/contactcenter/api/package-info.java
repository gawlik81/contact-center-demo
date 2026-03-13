/**
 * Warstwa API – kontrolery REST i WebSocket.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Kontrolery Spring MVC (@RestController) eksponujące endpointy REST</li>
 *   <li>Obiekty DTO (request/response) – walidowane adnotacjami javax.validation</li>
 *   <li>Mapowanie encji domenowych na DTO (przez MapStruct)</li>
 *   <li>Obsługa błędów (@ExceptionHandler, GlobalExceptionHandler)</li>
 *   <li>Dokumentacja OpenAPI 3.0 (adnotacje springdoc)</li>
 * </ul>
 *
 * <p>Zasada: warstwa api/ NIE zawiera logiki biznesowej.
 * Wszystkie operacje delegowane do serwisów w warstwie domain/.
 */
package com.contactcenter.api;
