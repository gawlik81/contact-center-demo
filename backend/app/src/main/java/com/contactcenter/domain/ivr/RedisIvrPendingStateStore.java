package com.contactcenter.domain.ivr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-owa implementacja {@link IvrPendingStateStore} (BE-177) - wariant współdzielony między instancjami
 * (nie "sticky routing"): zapis na instancji A, odczyt+usunięcie na instancji B.
 *
 * <p>Klucze: {@code ivr:pending:<typ>:<tenantId>:<callSid>}, typ = {@code conference} | {@code hangup} |
 * {@code audio}. Tenant w KLUCZU = izolacja bez niszczenia cudzego stanu: webhook z obcym {@code tenantId}
 * (parametr query URL-a Twilio) po prostu nie trafia w klucz prawdziwej rozmowy (brak DoS przez {@code GETDEL}).
 * Wartość to JSON z {@code tenantId} (dodatkowa weryfikacja; niezgodny/brakujący = fail-closed) i polami typu.
 * <b>Dane:</b> UUID-y oraz tekst zapowiedzi IVR już po podstawieniu zmiennych sesji, więc ZAWIERA to, co zebrał
 * {@code COLLECT_DTMF}, jeśli drzewo osadza te zmienne w zapowiedzi (np. numer klienta) - traktować jak dane
 * osobowe (jawny JSON w Redis, bez szyfrowania). Dlatego TTL jest krótki: {@code ivr.pending-state.ttl},
 * domyślnie {@value #DEFAULT_TTL} (stan jest konsumowany przy najbliższym webhooku, czyli po sekundach; TTL tylko
 * zabezpiecza przed wyciekiem kluczy porzuconej rozmowy). Konferencja to JEDEN klucz (contact+queue+prompt), więc jej
 * odczyt jest atomowy jednym {@code GETDEL} (bez stanu częściowego).
 *
 * <p><b>Polityka awarii Redis</b> (R1 z BE-177), uzasadnienie:
 * <ul>
 *   <li>Odczyt = fail-open: błąd Redis -&gt; log ERROR + metryka + pusty wynik, czyli dotychczasowa ścieżka
 *       "brak stanu" (TwiML zakończenia rozmowy). Rzucenie wyjątku dałoby 500 do Twilio i ten sam efekt
 *       (rozmowa kończona) bez kontroli nad komunikatem.</li>
 *   <li>Zapis konferencji = fail-closed ({@link IvrPendingStateUnavailableException}): silnik woła zapis PRZED
 *       publikacją kontaktu do kolejki, więc błąd uruchamia istniejący fallback do domyślnej kolejki i kontakt nie
 *       jest publikowany dwa razy. Uwaga: przy martwym Redis klient i tak nie trafi do konferencji (fallback
 *       również nie ma gdzie zapisać stanu) - dostaje TwiML zakończenia; to ograniczenie awarii Redis, a nie
 *       tej klasy.</li>
 *   <li>Zapis zapowiedzi kosmetycznych (hangup/audio) = fail-open: brak zapowiedzi nie psuje przepływu.</li>
 * </ul>
 */
@Slf4j
@Component
class RedisIvrPendingStateStore implements IvrPendingStateStore {

    static final String KEY_PREFIX = "ivr:pending:";
    static final String TYPE_CONFERENCE = "conference";
    static final String TYPE_HANGUP = "hangup";
    static final String TYPE_AUDIO = "audio";
    static final String PROP_TTL = "ivr.pending-state.ttl";
    /** Jedyne źródło domyślnego TTL (nie powielać w application.yml). */
    static final String DEFAULT_TTL = "PT2M";

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final MeterRegistry meterRegistry;

    RedisIvrPendingStateStore(StringRedisTemplate redis,
                              MeterRegistry meterRegistry,
                              @Value("${" + PROP_TTL + ":" + DEFAULT_TTL + "}") Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(PROP_TTL + " musi byc dodatnie, otrzymano: " + ttl);
        }
        this.redis = redis;
        this.meterRegistry = meterRegistry;
        this.ttl = ttl;
    }

    static String key(String type, UUID tenantId, String callId) {
        return KEY_PREFIX + type + ":" + tenantId + ":" + callId;
    }

    // ---- konferencja -------------------------------------------------------

    @Override
    public void saveConference(String callId, UUID tenantId, UUID contactId, UUID queueId, String prompt) {
        ConferenceValue value = new ConferenceValue(tenantId, contactId, queueId, prompt == null ? "" : prompt);
        try {
            if (tenantId == null) {
                throw new IllegalArgumentException("tenantId jest wymagany");
            }
            redis.opsForValue().set(key(TYPE_CONFERENCE, tenantId, callId), JSON.writeValueAsString(value), ttl);
        } catch (Exception e) {
            countError("save", TYPE_CONFERENCE);
            log.error("[IVR] Nie zapisano stanu konferencji w Redis (fail-closed): callId={}, tenantId={}, error={}",
                    callId, tenantId, e.toString());
            throw new IvrPendingStateUnavailableException("Nie zapisano stanu konferencji IVR: callId=" + callId, e);
        }
    }

    @Override
    public void discardConference(String callId, UUID tenantId) {
        if (tenantId == null) {
            return;
        }
        try {
            redis.delete(key(TYPE_CONFERENCE, tenantId, callId));
        } catch (Exception e) {
            countError("discard", TYPE_CONFERENCE);
            log.warn("[IVR] Nie usunieto stanu konferencji (TTL go wyczysci): callId={}, error={}", callId, e.toString());
        }
    }

    @Override
    public Optional<PendingConference> takeConference(String callId, UUID tenantId) {
        return take(TYPE_CONFERENCE, callId, tenantId, ConferenceValue.class)
                .filter(v -> v.contactId() != null)
                .map(v -> new PendingConference(v.tenantId(), v.contactId(), v.queueId(),
                        v.prompt() == null ? "" : v.prompt()));
    }

    // ---- zapowiedzi --------------------------------------------------------

    @Override
    public void saveHangupPrompt(String callId, UUID tenantId, String prompt) {
        savePrompt(TYPE_HANGUP, callId, tenantId, prompt);
    }

    @Override
    public Optional<String> takeHangupPrompt(String callId, UUID tenantId) {
        return take(TYPE_HANGUP, callId, tenantId, PromptValue.class).map(v -> v.prompt() == null ? "" : v.prompt());
    }

    @Override
    public void saveAudioPrompt(String callId, UUID tenantId, String prompt) {
        savePrompt(TYPE_AUDIO, callId, tenantId, prompt);
    }

    @Override
    public Optional<String> takeAudioPrompt(String callId, UUID tenantId) {
        return take(TYPE_AUDIO, callId, tenantId, PromptValue.class).map(v -> v.prompt() == null ? "" : v.prompt());
    }

    private void savePrompt(String type, String callId, UUID tenantId, String prompt) {
        try {
            if (tenantId == null) {
                throw new IllegalArgumentException("tenantId jest wymagany");
            }
            redis.opsForValue().set(key(type, tenantId, callId),
                    JSON.writeValueAsString(new PromptValue(tenantId, prompt == null ? "" : prompt)), ttl);
        } catch (Exception e) {
            countError("save", type);
            log.error("[IVR] Nie zapisano zapowiedzi w Redis (fail-open, zapowiedz pominieta): type={}, callId={}, error={}",
                    type, callId, e.toString());
        }
    }

    // ---- wspólny odczyt ----------------------------------------------------

    /** Atomowy GETDEL + walidacja tenanta; nigdy nie rzuca (fail-open). */
    private <T extends TenantScoped> Optional<T> take(String type, String callId, UUID tenantId, Class<T> clazz) {
        if (tenantId == null) {
            // fail-closed: wołający bez tenanta nie dostaje (ani nie niszczy) niczyjego stanu
            countError("tenant_mismatch", type);
            log.warn("[IVR] Odczyt stanu oczekujacego bez tenanta - ignoruje: type={}, callId={}", type, callId);
            return Optional.empty();
        }
        String raw;
        try {
            raw = redis.opsForValue().getAndDelete(key(type, tenantId, callId));
        } catch (Exception e) {
            countError("take", type);
            log.error("[IVR] Redis niedostepny przy odczycie stanu (fail-open, sciezka 'brak stanu'): type={}, callId={}, error={}",
                    type, callId, e.toString());
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.empty();
        }
        try {
            T value = JSON.readValue(raw, clazz);
            if (!tenantId.equals(value.tenantId())) {
                // klucz zawiera tenanta, wiec to wpis uszkodzony/obcy (np. brak tenantId w wartosci) - fail-closed
                countError("tenant_mismatch", type);
                log.warn("[IVR] Stan oczekujacy ma niezgodny/brakujacy tenantId - ignoruje: type={}, callId={}", type, callId);
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (Exception e) {
            countError("corrupt", type);
            log.error("[IVR] Uszkodzony stan oczekujacy w Redis - ignoruje: type={}, callId={}, error={}",
                    type, callId, e.toString());
            return Optional.empty();
        }
    }

    private void countError(String op, String type) {
        meterRegistry.counter("cc.ivr.pending.store.errors", "op", op, "type", type).increment();
    }

    private interface TenantScoped {
        UUID tenantId();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConferenceValue(UUID tenantId, UUID contactId, UUID queueId, String prompt) implements TenantScoped { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptValue(UUID tenantId, String prompt) implements TenantScoped { }
}
