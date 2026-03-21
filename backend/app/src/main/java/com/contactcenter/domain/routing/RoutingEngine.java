package com.contactcenter.domain.routing;

import java.util.Optional;

/**
 * Interfejs silnika routingu kontaktów do agentów.
 *
 * <p>Implementacja {@link DefaultRoutingEngine} obsługuje strategie:
 * SKILL_BASED, ROUND_ROBIN, FIRST_AVAILABLE oraz sticky agent.
 *
 * <p>Kontrakt: metoda jest bezstanowa z perspektywy wywołującego.
 * Stan rotacji ROUND_ROBIN przechowywany jest w Redis (klucz {@code routing:rr:{queueId}}).
 */
public interface RoutingEngine {

    /**
     * Znajduje najlepszego dostępnego agenta dla danego żądania routingu.
     *
     * @param request parametry routingu – tenant, kolejka, strategia, skills
     * @return Optional z {@link RoutingResult} lub empty gdy brak dostępnych agentów
     */
    Optional<RoutingResult> findBestAgent(RoutingRequest request);
}
