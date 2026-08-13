package com.contactcenter.api.tenant.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Testy jednostkowe zachowania (de)serializacji Jackson dla {@link TenantResourceLimitsDto}
 * (EPIC-29, BE-116).
 *
 * <p><strong>Kontekst decyzji (BE-116):</strong> pole {@code recording_retention_days} zostało
 * usunięte z tego DTO — jedynym źródłem prawdy dla retencji nagrań jest teraz
 * {@code tenant_retention_policy} (kategoria {@code RECORDINGS}). Kryterium akceptacji BE-116
 * wymagało zweryfikowania (nie zgadywania), jak Jackson w TYM projekcie zachowa się, gdy klient
 * nadal wyśle to (usunięte) pole w żądaniu {@code POST /api/tenants} lub
 * {@code PATCH /api/tenants/{id}} (endpoint {@code /{id}}, NIE {@code /{id}/config} — pole
 * {@code recordingRetentionDays} nigdy nie było częścią {@code TenantTwilioConfigRequest}
 * obsługiwanego przez {@code /config}).
 *
 * <p><strong>Zweryfikowany wynik:</strong> w tym projekcie NIE MA globalnej konfiguracji
 * {@code spring.jackson.deserialization.fail-on-unknown-properties=false} ani dedykowanego
 * {@code ObjectMapper} beana dla warstwy REST (sprawdzono {@code application.yml} i
 * {@code infrastructure.config} — jedyny customowy {@code ObjectMapper} to ten w
 * {@code RedisConfig}, niezwiązany z (de)serializacją żądań HTTP). Domyślny Jackson
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES=true}) rzuciłby więc {@code UnrecognizedPropertyException}
 * dla nieznanego pola JSON — a ponieważ {@code GlobalExceptionHandler} NIE MA dedykowanego
 * {@code @ExceptionHandler(HttpMessageNotReadableException.class)}, wyjątek trafiłby do
 * generycznego {@code @ExceptionHandler(Exception.class)} → HTTP 500 (dokładnie tego zabrania
 * kryterium akceptacji BE-116: "nie powoduje 500"). Dlatego {@link TenantResourceLimitsDto} ma
 * jawnie dodane {@code @JsonIgnoreProperties(ignoreUnknown = true)} — DECYZJA: pole jest CICHO
 * IGNOROWANE (żądanie kończy się sukcesem, tak jakby pola nie było w body), nie HTTP 400. Wybór
 * "ignorowane" zamiast "400" ułatwia zgodność wsteczną klientom (np. starszemu frontendowi
 * przed FE-109), którzy mogą jeszcze wysyłać to pole podczas okresu przejściowego.
 */
@DisplayName("TenantResourceLimitsDto – Jackson (de)serializacja pola recording_retention_days (BE-116)")
class TenantResourceLimitsDtoTest {

    /**
     * Zwykły {@code new ObjectMapper()} (bez żadnej customowej konfiguracji) reprezentuje
     * dokładnie te ustawienia unknown-properties, jakich Spring Boot używa domyślnie w tym
     * projekcie (brak override {@code spring.jackson.deserialization.fail-on-unknown-properties}
     * — patrz javadoc klasy). {@code @JsonIgnoreProperties} na DTO działa niezależnie od
     * konfiguracji globalnej {@code ObjectMapper}, więc ten test jest miarodajny bez potrzeby
     * uruchamiania pełnego kontekstu Spring MVC.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("ignoruje pole recording_retention_days w body (usunięte, backward-compat) zamiast rzucać wyjątek -> nie 500")
    void shouldIgnoreUnknownRecordingRetentionDaysFieldInsteadOfThrowing() {
        String jsonWithRemovedField = """
                {
                    "max_agents": 100,
                    "max_queues": 50,
                    "max_campaigns": 20,
                    "recording_retention_days": 180,
                    "timezone": "Europe/Warsaw"
                }
                """;

        assertThatCode(() -> objectMapper.readValue(jsonWithRemovedField, TenantResourceLimitsDto.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("deserializuje poprawnie pozostałe pola mimo obecności nieznanego pola w body")
    void shouldStillDeserializeKnownFieldsCorrectly() throws Exception {
        String jsonWithRemovedField = """
                {
                    "max_agents": 42,
                    "max_queues": 7,
                    "max_campaigns": 3,
                    "recording_retention_days": 180,
                    "timezone": "Europe/London"
                }
                """;

        TenantResourceLimitsDto dto = objectMapper.readValue(jsonWithRemovedField, TenantResourceLimitsDto.class);

        assertThat(dto.maxAgents()).isEqualTo(42);
        assertThat(dto.maxQueues()).isEqualTo(7);
        assertThat(dto.maxCampaigns()).isEqualTo(3);
        assertThat(dto.timezone()).isEqualTo("Europe/London");
    }

    @Test
    @DisplayName("deserializuje poprawnie body BEZ pola recording_retention_days (nowy kontrakt)")
    void shouldDeserializeWithoutRemovedFieldAtAll() throws Exception {
        String jsonWithoutRemovedField = """
                {
                    "max_agents": 10,
                    "max_queues": 5,
                    "max_campaigns": 2,
                    "timezone": "Europe/Warsaw"
                }
                """;

        TenantResourceLimitsDto dto = objectMapper.readValue(jsonWithoutRemovedField, TenantResourceLimitsDto.class);

        assertThat(dto.maxAgents()).isEqualTo(10);
        assertThat(dto.timezone()).isEqualTo("Europe/Warsaw");
    }

    @Test
    @DisplayName("defaults() nie zawiera już recordingRetentionDays (usunięte pole, BE-116)")
    void shouldNotExposeRecordingRetentionDaysInDefaults() {
        TenantResourceLimitsDto defaults = TenantResourceLimitsDto.defaults();

        assertThat(defaults.maxAgents()).isEqualTo(100);
        assertThat(defaults.maxQueues()).isEqualTo(50);
        assertThat(defaults.maxCampaigns()).isEqualTo(20);
        assertThat(defaults.timezone()).isEqualTo("Europe/Warsaw");
    }
}
