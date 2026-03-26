package com.contactcenter.api.queue;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO żądania tworzenia kolejki.
 *
 * <p>Używane przez {@code POST /api/queues}.
 *
 * @param name                          nazwa kolejki (unikalna w tenancie)
 * @param routingStrategy               strategia routingu: ROUND_ROBIN, FIRST_AVAILABLE, SKILL_BASED
 * @param requiredSkills                lista wymaganych umiejętności agenta (opcjonalna)
 * @param stickyAgentTimeoutSeconds     czas oczekiwania na sticky agenta w sekundach (>=0, domyślnie 60)
 * @param maxConcurrentContactsPerAgent max jednoczesnych kontaktów per agent (>=1, domyślnie 1)
 * @param waitConfig                    konfiguracja komunikatów oczekiwania (JSON string, opcjonalne)
 * @param active                        czy kolejka jest aktywna (domyślnie true)
 */
public record CreateQueueRequest(

        @NotBlank(message = "Nazwa kolejki jest wymagana")
        @Size(max = 255, message = "Nazwa kolejki nie może przekraczać 255 znaków")
        String name,

        @NotNull(message = "Strategia routingu jest wymagana")
        @Pattern(
                regexp = "ROUND_ROBIN|FIRST_AVAILABLE|SKILL_BASED",
                message = "routingStrategy musi być jednym z: ROUND_ROBIN, FIRST_AVAILABLE, SKILL_BASED"
        )
        String routingStrategy,

        List<String> requiredSkills,

        @Email(message = "emailAddress musi być poprawnym adresem email")
        @Size(max = 255, message = "emailAddress nie może przekraczać 255 znaków")
        String emailAddress,

        @Min(value = 0, message = "stickyAgentTimeoutSeconds musi być >= 0")
        Integer stickyAgentTimeoutSeconds,

        @Min(value = 1, message = "maxConcurrentContactsPerAgent musi być >= 1")
        Integer maxConcurrentContactsPerAgent,

        String waitConfig,

        Boolean active
) {}
