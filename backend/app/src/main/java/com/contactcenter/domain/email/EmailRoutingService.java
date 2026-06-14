package com.contactcenter.domain.email;

import com.contactcenter.domain.model.EmailMessage;
import com.contactcenter.domain.model.EmailRoutingRule;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.repository.EmailMessageRepository;
import com.contactcenter.domain.repository.EmailRoutingRuleRepository;
import com.contactcenter.domain.queue.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis routingu przychodzących wiadomości email do kolejek obsługi.
 *
 * <p>Reguły routingu są przechowywane w bazie danych (tabela {@code email_routing_rule}).
 * Ewaluowane po priorytecie rosnąco – pierwsza pasująca reguła wygrywa.
 *
 * <p>Obsługiwane warunki reguł (pole {@code conditions} JSONB):
 * <ul>
 *   <li>{@code "field": "subject", "operator": "CONTAINS", "value": "..."}</li>
 *   <li>{@code "field": "from_address", "operator": "CONTAINS", "value": "..."}</li>
 *   <li>{@code "field": "to_address", "operator": "EQUALS", "value": "..."}</li>
 *   <li>{@code "field": "subject", "operator": "REGEX", "value": "..."}</li>
 * </ul>
 *
 * <p>Fallback: kolejka domyślna z {@code tenant.config["email_default_queue_id"]}.
 * Gdy brak kolejki domyślnej – wiadomość pozostaje bez kolejki (log WARNING).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailRoutingService {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final EmailMessageRepository emailMessageRepository;
    private final EmailEventPublisher emailEventPublisher;
    private final EmailRoutingRuleRepository routingRuleRepository;
    private final QueueService queueService;

    // =========================================================================
    // Główna metoda routingu
    // =========================================================================

    /**
     * Wyznacza kolejkę docelową dla wiadomości i publikuje event {@code email.queued}.
     *
     * <p>Wywołuj po zapisaniu wiadomości INBOUND w DB i po {@code publishReceived()}.
     *
     * @param message            wiadomość do zroutowania
     * @param tenantConfig       mapa {@code tenant.config} z konfiguracją routingu
     */
    @Transactional
    public void route(EmailMessage message, Map<String, Object> tenantConfig) {
        UUID tenantId = message.getTenantId();

        log.debug("[EmailRouting] Routuję wiadomość: messageId={}, tenant={}, from={}",
                message.getId(), tenantId, message.getFromAddress());

        Optional<UUID> queueId = findMatchingQueue(message, tenantId, tenantConfig);

        if (queueId.isPresent()) {
            log.info("[EmailRouting] Wiadomość {} przypisana do kolejki {}", message.getId(), queueId.get());
            emailEventPublisher.publishQueued(message, queueId.get());
        } else {
            log.warn("[EmailRouting] Brak pasującej kolejki dla wiadomości {} " +
                     "(sprawdzono: reguły routingu → adres email kolejki → email_default_queue_id). " +
                     "Skonfiguruj email_default_queue_id w tenant.config lub przypisz email_address do kolejki.",
                     message.getId());
        }
    }

    // =========================================================================
    // Ewaluacja reguł
    // =========================================================================

    /**
     * Wyszukuje pasującą kolejkę przez ewaluację reguł per priorytet.
     *
     * @param message      wiadomość do dopasowania
     * @param tenantId     UUID tenanta
     * @param tenantConfig konfiguracja tenanta (fallback na kolejkę domyślną)
     * @return Optional z UUID kolejki lub empty gdy brak dopasowania
     */
    private Optional<UUID> findMatchingQueue(EmailMessage message, UUID tenantId,
                                              Map<String, Object> tenantConfig) {
        // 1. Pobierz aktywne reguły routingu posortowane po priorytecie
        List<EmailRoutingRule> rules = routingRuleRepository.findActiveByTenantId(tenantId);

        for (EmailRoutingRule rule : rules) {
            if (matchesRule(message, rule)) {
                log.debug("[EmailRouting] Dopasowano regułę: ruleId={}, ruleName={}, queueId={}",
                        rule.getRuleId(), rule.getName(), rule.getQueueId());
                return Optional.ofNullable(rule.getQueueId());
            }
        }

        // 2. Fallback: kolejka przypisana do adresu email (to_address)
        if (message.getToAddress() != null && !message.getToAddress().isBlank()) {
            String primaryToAddress = extractEmailAddress(message.getToAddress().split(",")[0]);
            Optional<UUID> queueByEmail = findQueueByEmailAddress(primaryToAddress, tenantId);
            if (queueByEmail.isPresent()) {
                log.info("[EmailRouting] Wiadomość {} przypisana do kolejki {} przez adres email {}",
                        message.getId(), queueByEmail.get(), primaryToAddress);
                return queueByEmail;
            }
        }

        // 3. Ostatni fallback: kolejka domyślna z tenant.config["email_default_queue_id"]
        Object defaultQueueIdObj = tenantConfig != null
                ? tenantConfig.get("email_default_queue_id")
                : null;

        if (defaultQueueIdObj != null) {
            try {
                UUID defaultQueueId = UUID.fromString(String.valueOf(defaultQueueIdObj));
                log.debug("[EmailRouting] Używam kolejki domyślnej: {}", defaultQueueId);
                return Optional.of(defaultQueueId);
            } catch (IllegalArgumentException e) {
                log.warn("[EmailRouting] Nieprawidłowy email_default_queue_id w tenant.config: {}",
                        defaultQueueIdObj);
            }
        }

        return Optional.empty();
    }

    /**
     * Ekstrahuje czysty adres email z wartości nagłówka, obsługując format RFC 5322.
     *
     * <p>Przykłady wejścia:
     * <ul>
     *   <li>{@code "support@mycompany.com"} → {@code "support@mycompany.com"}</li>
     *   <li>{@code "Support Team <support@mycompany.com>"} → {@code "support@mycompany.com"}</li>
     * </ul>
     *
     * @param address surowa wartość adresu (pojedynczy wpis, nie lista)
     * @return czysty adres email małymi literami lub null gdy wejście null
     */
    private String extractEmailAddress(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        // Format: "Display Name <email@domain.com>"
        int start = trimmed.lastIndexOf('<');
        int end   = trimmed.lastIndexOf('>');
        if (start >= 0 && end > start) {
            return trimmed.substring(start + 1, end).trim().toLowerCase();
        }
        return trimmed.toLowerCase();
    }

    /**
     * Wyszukuje aktywną kolejkę przypisaną do podanego adresu email.
     *
     * <p>Porównanie case-insensitive – delegowane do bazy danych.
     *
     * @param emailAddress adres email (to_address wiadomości przychodzacej)
     * @param tenantId     UUID tenanta
     * @return Optional z UUID kolejki lub empty gdy brak dopasowania
     */
    private Optional<UUID> findQueueByEmailAddress(String emailAddress, UUID tenantId) {
        return queueService.findQueueByEmailAddress(emailAddress, tenantId)
                .map(Queue::getQueueId);
    }

    /**
     * Sprawdza czy wiadomość spełnia warunki reguły routingu.
     *
     * <p>Wszystkie warunki ({@code AND}) muszą być spełnione.
     *
     * @param message wiadomość do sprawdzenia
     * @param rule    reguła routingu
     * @return true jeśli wszystkie warunki spełnione
     */
    @SuppressWarnings("unchecked")
    boolean matchesRule(EmailMessage message, EmailRoutingRule rule) {
        if (rule.getConditions() == null || rule.getConditions().isBlank()
                || "[]".equals(rule.getConditions())) {
            // Brak warunków = reguła catch-all
            return true;
        }

        List<Map<String, String>> conditions;
        try {
            conditions = MAPPER.readValue(rule.getConditions(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            log.warn("[EmailRouting] Błąd parsowania warunków reguły {}: {}", rule.getRuleId(), e.getMessage());
            return false;
        }

        for (Map<String, String> condition : conditions) {
            if (!evaluateCondition(message, condition)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ewaluuje pojedynczy warunek reguły routingu.
     *
     * @param message   wiadomość
     * @param condition mapa z kluczami: field, operator, value
     * @return true jeśli warunek spełniony
     */
    private boolean evaluateCondition(EmailMessage message, Map<String, String> condition) {
        String field    = condition.get("field");
        String operator = condition.get("operator");
        String value    = condition.get("value");

        if (field == null || operator == null || value == null) {
            log.warn("[EmailRouting] Niekompletny warunek reguły: {}", condition);
            return false;
        }

        String fieldValue = getFieldValue(message, field);
        if (fieldValue == null) {
            return false;
        }

        return switch (operator.toUpperCase()) {
            case "CONTAINS"       -> fieldValue.toLowerCase().contains(value.toLowerCase());
            case "EQUALS"         -> fieldValue.equalsIgnoreCase(value);
            case "STARTS_WITH"    -> fieldValue.toLowerCase().startsWith(value.toLowerCase());
            case "ENDS_WITH"      -> fieldValue.toLowerCase().endsWith(value.toLowerCase());
            case "REGEX"          -> fieldValue.matches(value);
            default -> {
                log.warn("[EmailRouting] Nieznany operator: {}", operator);
                yield false;
            }
        };
    }

    /**
     * Pobiera wartość pola wiadomości na podstawie nazwy pola.
     *
     * @param message wiadomość
     * @param field   nazwa pola (subject, from_address, to_address)
     * @return wartość pola lub null
     */
    private String getFieldValue(EmailMessage message, String field) {
        return switch (field.toLowerCase()) {
            case "subject"      -> message.getSubject();
            case "from_address", "from" -> message.getFromAddress();
            case "to_address", "to"   -> message.getToAddress();
            default -> {
                log.debug("[EmailRouting] Nieznane pole warunku: {}", field);
                yield null;
            }
        };
    }
}
