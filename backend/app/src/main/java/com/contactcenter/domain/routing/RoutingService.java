package com.contactcenter.domain.routing;

import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy orkiestrujący routing kontaktów do agentów.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Pobieranie konfiguracji kolejki z bazy danych</li>
 *   <li>Budowanie {@link RoutingRequest} i delegowanie do {@link RoutingEngine}</li>
 *   <li>Aktualizacja statusu kontaktu po przydzieleniu</li>
 *   <li>Publikacja eventów domenowych ({@code contact.assigned})</li>
 *   <li>Nasłuchiwanie eventów {@code contact.queued} i pierwotne próby routingu</li>
 *   <li>Nasłuchiwanie eventów {@code agent.status.changed} i retry routingu dla oczekujących kontaktów</li>
 * </ul>
 *
 * <p>Metoda {@link #routeContact} jest synchroniczna – wywołanie z {@code @RabbitListener}
 * pozwala Spring AMQP poprawnie obsłużyć wyjątki (NACK / DLQ) przy błędzie routingu.
 *
 * <p>Retry dla oczekujących kontaktów (brak agentów) jest wyzwalany przez event
 * {@code agent.status.changed} zamiast ponownej publikacji {@code contact.queued},
 * co eliminuje ryzyko nieskończonej pętli.
 */
public interface RoutingService {

    /**
     * Routuje kontakt do najlepszego dostępnego agenta.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Pobierz kolejkę z bazy danych</li>
     *   <li>Pobierz kontakt z bazy danych</li>
     *   <li>Wyznacz zbiór uprawnionych agentów: gdy {@code all_agents=FALSE} – jeden SELECT UNION
     *       ({@code queue_agent} + grupy); gdy {@code all_agents=TRUE} – null (brak filtru)</li>
     *   <li>Zbuduj {@link RoutingRequest} z konfiguracji kolejki + {@code eligibleAgentIds}</li>
     *   <li>Wywołaj {@link RoutingEngine#findBestAgent(RoutingRequest)}</li>
     *   <li>Jeśli znaleziono agenta: zaktualizuj kontakt (status ACTIVE, agent_id) i opublikuj
     *       event {@code contact.assigned}</li>
     *   <li>Jeśli nie znaleziono: zwróć empty – kontakt pozostaje w statusie QUEUED w DB.
     *       Retry nastąpi gdy agent zmieni status na AVAILABLE (patrz {@code onAgentStatusChanged}).</li>
     * </ol>
     *
     * <p>Metoda jest synchroniczna – wywołanie z {@code @RabbitListener} na {@code contact.queued}
     * lub {@code agent.status.changed} pozwala Spring AMQP poprawnie obsłużyć NACK przy wyjątku.
     *
     * @param contactId UUID kontaktu do routowania
     * @param queueId   UUID kolejki docelowej
     * @param tenantId  UUID tenanta
     * @return Optional z UUID agenta lub empty gdy nie znaleziono
     */
    Optional<UUID> routeContact(UUID contactId, UUID queueId, UUID tenantId);
}
