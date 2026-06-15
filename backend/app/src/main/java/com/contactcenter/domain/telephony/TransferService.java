package com.contactcenter.domain.telephony;

import com.contactcenter.api.telephony.dto.TransferAgentResponse;
import com.contactcenter.api.telephony.dto.TransferQueueResponse;

import java.util.List;
import java.util.UUID;

/**
 * Serwis zwracający listę agentów dostępnych do transferu połączenia.
 *
 * <p>Logika filtrowania:
 * <ul>
 *   <li>Tylko agenci tego samego tenanta</li>
 *   <li>Wykluczone statusy: OFFLINE (agenci niezalogowani)</li>
 *   <li>Wykluczony zalogowany agent (nie możemy transferować do samego siebie)</li>
 *   <li>Tylko rola AGENT (brak ADMIN/SUPERVISOR, którzy nie obsługują połączeń)</li>
 * </ul>
 *
 * <p>Sortowanie: AVAILABLE najpierw, następnie pozostałe statusy,
 * w ramach statusu – alfabetycznie po lastName.
 *
 * <p>Nazwy kolejek są pobierane jednym zapytaniem zbiorczym (brak N+1).
 */
public interface TransferService {

    /**
     * Zwraca posortowaną listę agentów dostępnych do przyjęcia transferu.
     *
     * @param tenantId      UUID tenanta zalogowanego agenta
     * @param excludeUserId UUID zalogowanego agenta (wykluczony z wyników)
     * @return lista agentów posortowana według statusu i nazwiska
     */
    List<TransferAgentResponse> getAvailableAgents(UUID tenantId, UUID excludeUserId);

    /**
     * Zwraca listę aktywnych kolejek tenanta wzbogaconą o statystyki snapshot.
     *
     * <p>Logika:
     * <ol>
     *   <li>Pobiera wszystkie aktywne kolejki tenanta jednym zapytaniem</li>
     *   <li>Pobiera liczbę oczekujących kontaktów (status QUEUED) per kolejka – jedno zapytanie GROUP BY</li>
     *   <li>Pobiera liczbę dostępnych agentów (status AVAILABLE) per kolejka – jedno zapytanie GROUP BY (trzy źródła przypisania analogicznie do BE-075)</li>
     *   <li>Sortuje alfabetycznie po name i mapuje na DTO</li>
     * </ol>
     *
     * <p>Brak N+1: łącznie 3 zapytania niezależnie od liczby kolejek.
     *
     * @param tenantId UUID tenanta
     * @return posortowana alfabetycznie lista kolejek z metrykami
     */
    List<TransferQueueResponse> getAvailableQueues(UUID tenantId);
}
