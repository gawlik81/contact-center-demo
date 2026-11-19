package com.contactcenter.domain.ivr;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementacja {@link IvrPendingStateStore} w pamieci - WYLACZNIE dla testow jednostkowych (BE-177);
 * produkcyjna jest {@link RedisIvrPendingStateStore}. Semantyka jak w Redis: klucz zawiera tenanta (odczyt z innym
 * tenantem nie trafia w stan i go nie niszczy), odczyt jednorazowy, brak tenanta = brak wyniku.
 */
class InMemoryIvrPendingStateStore implements IvrPendingStateStore {

    private record Key(String type, UUID tenantId, String callId) { }

    private final Map<Key, Object> state = new ConcurrentHashMap<>();

    @Override
    public void saveConference(String callId, UUID tenantId, UUID contactId, UUID queueId, String prompt) {
        Objects.requireNonNull(tenantId);
        state.put(new Key("conference", tenantId, callId), new PendingConference(tenantId, contactId, queueId, prompt));
    }

    @Override
    public void discardConference(String callId, UUID tenantId) {
        state.remove(new Key("conference", tenantId, callId));
    }

    @Override
    public Optional<PendingConference> takeConference(String callId, UUID tenantId) {
        return take("conference", callId, tenantId, PendingConference.class);
    }

    @Override
    public void saveHangupPrompt(String callId, UUID tenantId, String prompt) {
        state.put(new Key("hangup", tenantId, callId), prompt);
    }

    @Override
    public Optional<String> takeHangupPrompt(String callId, UUID tenantId) {
        return take("hangup", callId, tenantId, String.class);
    }

    @Override
    public void saveAudioPrompt(String callId, UUID tenantId, String prompt) {
        state.put(new Key("audio", tenantId, callId), prompt);
    }

    @Override
    public Optional<String> takeAudioPrompt(String callId, UUID tenantId) {
        return take("audio", callId, tenantId, String.class);
    }

    private <T> Optional<T> take(String type, String callId, UUID tenantId, Class<T> clazz) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.remove(new Key(type, tenantId, callId))).map(clazz::cast);
    }

    boolean isEmpty() {
        return state.isEmpty();
    }
}
