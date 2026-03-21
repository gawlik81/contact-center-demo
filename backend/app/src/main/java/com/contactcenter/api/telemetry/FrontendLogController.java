package com.contactcenter.api.telemetry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint przyjmowania logów z aplikacji frontendowej.
 *
 * <p>Endpoint jest publiczny (nie wymaga JWT) – frontend może logować błędy
 * przed zalogowaniem użytkownika (np. błędy ładowania, błędy auth flow).
 *
 * <p>Logi są wypisywane przez SLF4J/Logback na odpowiednim poziomie.
 * Nie są zapisywane do bazy danych.
 *
 * <p>Przyjmuje max {@value #MAX_ENTRIES} wpisów w jednym żądaniu – nadmiarowe
 * wpisy są ignorowane (bez błędu – zawsze zwraca 204).
 */
@Slf4j(topic = "fe.contact-center")
@Validated
@RestController
@RequestMapping("/api/logs")
@Tag(name = "Logs", description = "Frontend log collector – publiczny endpoint do zbierania logów z przeglądarki")
public class FrontendLogController {

    private static final int MAX_ENTRIES = 20;

    // MDC klucze używane przy logowaniu wpisów z frontendu
    private static final String MDC_FE_TENANT = "feTenantId";
    private static final String MDC_FE_USER   = "feUserId";

    @PostMapping
    @Operation(
            summary = "Wyślij logi z frontendu",
            description = "Przyjmuje tablicę logów z aplikacji Angular. " +
                    "Endpoint publiczny – nie wymaga JWT. Zawsze zwraca 204 No Content."
    )
    public ResponseEntity<Void> collectLogs(
            @RequestBody @Valid List<@Valid FrontendLogRequest> entries
    ) {
        if (entries == null || entries.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        // Ogranicz do MAX_ENTRIES – nie ujawniaj błędu, po prostu ignoruj resztę
        List<FrontendLogRequest> batch = entries.subList(0, Math.min(MAX_ENTRIES, entries.size()));

        for (FrontendLogRequest entry : batch) {
            logEntry(entry);
        }

        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Loguje pojedynczy wpis na odpowiednim poziomie SLF4J.
     *
     * <p>Format: {@code [FRONTEND] [tenantId] [userId] [logger] message}
     * Stack trace (jeśli present) dołączany jako osobna linia.
     */
    private void logEntry(FrontendLogRequest entry) {
        // Ustawiamy MDC z danymi z frontendu – tylko do wzbogacenia logu, nie do autoryzacji
        if (StringUtils.hasText(entry.tenantId())) {
            MDC.put(MDC_FE_TENANT, sanitize(entry.tenantId(), 36));
        }
        if (StringUtils.hasText(entry.userId())) {
            MDC.put(MDC_FE_USER, sanitize(entry.userId(), 36));
        }

        try {
            String tenantPart = StringUtils.hasText(entry.tenantId())
                    ? "[" + sanitize(entry.tenantId(), 36) + "]"
                    : "[-]";
            String userPart = StringUtils.hasText(entry.userId())
                    ? "[" + sanitize(entry.userId(), 36) + "]"
                    : "[-]";
            String loggerPart = "[" + entry.logger() + "]";

            String formattedMessage = "[FRONTEND] " + tenantPart + " " + userPart + " "
                    + loggerPart + " " + entry.message();

            // Dołącz stack trace do komunikatu jeśli present
            String fullMessage = StringUtils.hasText(entry.stackTrace())
                    ? formattedMessage + "\n" + entry.stackTrace()
                    : formattedMessage;

            switch (entry.level().toUpperCase()) {
                case "ERROR" -> log.error(fullMessage);
                case "WARN"  -> log.warn(fullMessage);
                case "DEBUG" -> log.debug(fullMessage);
                default      -> log.info(fullMessage);  // INFO i nieznane poziomy
            }
        } finally {
            MDC.remove(MDC_FE_TENANT);
            MDC.remove(MDC_FE_USER);
        }
    }

    /**
     * Przycina string do maksymalnej długości.
     * Chroni przed nadmiarowo długimi wartościami, które mogłyby trafić do MDC.
     */
    private String sanitize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
