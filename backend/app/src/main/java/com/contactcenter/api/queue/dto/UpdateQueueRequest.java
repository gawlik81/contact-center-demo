package com.contactcenter.api.queue.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO żądania aktualizacji kolejki (PATCH semantics).
 *
 * <p>Używane przez {@code PATCH /api/queues/{id}}.
 * Wszystkie pola są opcjonalne – wartość {@code null} oznacza brak zmiany.
 *
 * @param name                          nowa nazwa kolejki (null = bez zmiany)
 * @param routingStrategy               nowa strategia routingu (null = bez zmiany)
 * @param requiredSkills                nowa lista wymaganych umiejętności (null = bez zmiany)
 * @param stickyAgentTimeoutSeconds     nowy timeout sticky agenta (null = bez zmiany)
 * @param maxConcurrentContactsPerAgent nowy limit jednoczesnych kontaktów (null = bez zmiany)
 * @param waitConfig                    nowa konfiguracja oczekiwania (null = bez zmiany)
 * @param active                        nowy status aktywności (null = bez zmiany)
 */
public record UpdateQueueRequest(

        // @NotBlank ignoruje null (PATCH semantics: null = bez zmiany); odrzuca "" gdy pole podane
        @Size(min = 1, max = 255, message = "Nazwa kolejki musi mieć od 1 do 255 znaków")
        String name,

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
