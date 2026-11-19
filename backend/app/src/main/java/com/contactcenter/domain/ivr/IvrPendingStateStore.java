package com.contactcenter.domain.ivr;

import java.util.Optional;
import java.util.UUID;

/**
 * Magazyn krótkotrwałego stanu IVR przekazywanego między kolejnymi webhookami Twilio tej samej rozmowy (BE-177).
 *
 * <p>Kolejny webhook (action URL) tej samej rozmowy może trafić na INNĄ instancję backendu niż ta, która zapisała
 * stan - dlatego produkcyjna implementacja ({@link RedisIvrPendingStateStore}) trzyma go w Redis, a nie w pamięci JVM.
 * Wszystkie odczyty są JEDNORAZOWE ({@code take} = atomowe pobranie + usunięcie, {@code GETDEL}); drugi odczyt
 * (np. retry webhooka Twilio) zwraca {@link Optional#empty()}.
 *
 * <p>Semantyka błędów (niedostępność magazynu):
 * <ul>
 *   <li>{@code take*} nigdy nie rzuca - przy awarii zwraca pusty wynik (ścieżka awaryjna "brak stanu", fail-open).</li>
 *   <li>{@link #saveConference} rzuca {@link IvrPendingStateUnavailableException} (fail-closed) - wywołujący
 *       musi to zrobić PRZED efektami ubocznymi (publikacja kontaktu do kolejki).</li>
 *   <li>{@code save*Prompt} (zapowiedzi kosmetyczne) nigdy nie rzucają - w razie awarii zapowiedź jest pomijana.</li>
 * </ul>
 */
interface IvrPendingStateStore {

    /**
     * Stan konferencji klienta oczekującego w kolejce (po QUEUE_TRANSFER).
     *
     * @param tenantId  tenant rozmowy (walidowany przy odczycie)
     * @param contactId kontakt przekazany do konferencji Twilio
     * @param queueId   kolejka, do której przypisano kontakt
     * @param prompt    zapowiedź dla klienta (może być pusta, nigdy null)
     */
    record PendingConference(UUID tenantId, UUID contactId, UUID queueId, String prompt) { }

    /** Zapisuje stan konferencji; rzuca {@link IvrPendingStateUnavailableException} gdy magazyn niedostępny. */
    void saveConference(String callId, UUID tenantId, UUID contactId, UUID queueId, String prompt);

    /**
     * Usuwa stan konferencji (best-effort, nigdy nie rzuca) - wołane po błędzie PO udanym {@link #saveConference},
     * żeby nie został stan wskazujący kolejkę, do której kontakt ostatecznie nie trafił.
     */
    void discardConference(String callId, UUID tenantId);

    /**
     * Atomowo pobiera i usuwa stan konferencji; pusty gdy brak/wygasł/awaria magazynu/brak tenanta. Klucz zawiera
     * tenanta, więc odczyt z cudzym tenantem nie trafia w klucz i NIE niszczy stanu prawdziwej rozmowy.
     */
    Optional<PendingConference> takeConference(String callId, UUID tenantId);

    /** Zapisuje zapowiedź przed rozłączeniem (best-effort). */
    void saveHangupPrompt(String callId, UUID tenantId, String prompt);

    /** Atomowo pobiera i usuwa zapowiedź rozłączenia. */
    Optional<String> takeHangupPrompt(String callId, UUID tenantId);

    /** Zapisuje zapowiedź PLAY_AUDIO doklejaną do TwiML następnego węzła (best-effort). */
    void saveAudioPrompt(String callId, UUID tenantId, String prompt);

    /** Atomowo pobiera i usuwa zapowiedź PLAY_AUDIO. */
    Optional<String> takeAudioPrompt(String callId, UUID tenantId);
}
