package com.contactcenter.infrastructure.aspect;

import com.contactcenter.domain.model.AuditLogEvent;
import com.contactcenter.domain.service.AuditLogService;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aspekt AOP rejestrujący operacje CRUD na encjach wrażliwych w dzienniku audytu.
 *
 * <p>Przechwytuje wszystkie metody oznaczone adnotacją {@link Audited} i dla każdego
 * wywołania:
 * <ol>
 *   <li>Opcjonalnie pobiera stan encji przed zmianą ({@code old_value}) gdy
 *       {@link Audited#captureOldValue()} == true.</li>
 *   <li>Wywołuje oryginalną metodę.</li>
 *   <li>Serializuje wynik jako {@code new_value} (dla CREATE/UPDATE).</li>
 *   <li>Publikuje {@link AuditLogEvent} do RabbitMQ przez {@link AuditLogService}.</li>
 * </ol>
 *
 * <p><strong>Wykluczanie pól wrażliwych:</strong> Pola {@code password}, {@code passwordHash},
 * {@code mfaSecret}, {@code token}, {@code refreshToken} są usuwane z serializacji JSON.
 *
 * <p><strong>Bezpieczeństwo błędów:</strong> Błędy w logice audytu (serializacja, publikacja)
 * nie przerywają operacji biznesowej – są logowane i pomijane.
 *
 * <p><strong>Optymalizacja captureOldValue:</strong> Zamiast wywoływać getter serwisu przez
 * refleksję (co przechodzi przez Spring AOP proxy i otwiera nową readOnly transakcję), aspekt
 * używa {@code EntityManager.find()} bezpośrednio. Gdy encja jest już załadowana w L1 cache
 * bieżącej transakcji JPA – nie wykonuje dodatkowego zapytania do bazy danych.
 * Mapowanie {@code entityType} (string) → klasa JPA odbywa się przez {@link #ENTITY_CLASS_MAP}.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    /** Pola wrażliwe wykluczane z serializacji w old_value / new_value. */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "passwordHash", "mfaSecret", "token", "refreshToken",
            "password_hash", "mfa_secret", "refresh_token"
    );

    /**
     * Mapowanie entityType (string z @Audited) → klasa JPA encji.
     * Używane przez captureOldValue do wyszukania encji przez EntityManager.find()
     * zamiast wywoływania gettera serwisu przez proxy (podwójny DB read).
     */
    private static final Map<String, Class<?>> ENTITY_CLASS_MAP;
    static {
        Map<String, Class<?>> map = new java.util.HashMap<>();
        map.put("USER",     com.contactcenter.domain.user.AppUser.class);
        map.put("TENANT",   com.contactcenter.domain.tenant.Tenant.class);
        map.put("CUSTOMER", com.contactcenter.domain.customer.Customer.class);
        ENTITY_CLASS_MAP = java.util.Collections.unmodifiableMap(map);
    }

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /** EntityManager do odczytu encji przez L1 cache (bez proxy serwisu). */
    @PersistenceContext
    private EntityManager em;

    // =========================================================================
    // Advice
    // =========================================================================

    /**
     * Przechwytuje wywołania metod oznaczonych {@code @Audited}.
     *
     * <p>Aspekt działa jako {@code @Around}, co pozwala przechwycić stan przed
     * i po wywołaniu oryginalnej metody.
     *
     * @param pjp     punkt złączenia – dostarcza argumenty i możliwość wywołania metody
     * @param audited adnotacja z metadanymi zdarzenia audytowego
     * @return wynik oryginalnej metody
     * @throws Throwable gdy oryginalna metoda rzuci wyjątek (propagujemy bez zmian)
     */
    @Around("@annotation(audited)")
    public Object auditMethod(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        // 1. Pobierz kontekst tenanta (może być null dla operacji globalnych)
        UUID tenantId   = TenantContext.getTenantIdOrNull();
        UUID userId     = TenantContext.getUserIdOrNull();

        // 2. Pobierz old_value przed wywołaniem (opcjonalnie – dla UPDATE/DELETE)
        String oldValue = null;
        if (audited.captureOldValue()) {
            oldValue = captureOldValue(pjp, audited);
        }

        // 3. Wywołaj oryginalną metodę (błąd propagujemy bez zmian)
        Object result = pjp.proceed();

        // 4. Zbuduj i opublikuj zdarzenie audytowe (błędy nie przerywają flow)
        try {
            String newValue  = serializeToJson(result);
            UUID   entityId  = extractEntityId(pjp, audited, result);

            AuditLogEvent event = new AuditLogEvent(
                    tenantId,
                    userId,
                    audited.action(),
                    audited.entityType(),
                    entityId,
                    oldValue,
                    newValue,
                    null,  // ip_address – opcjonalne, brak w tym kontekście
                    null,  // user_agent – opcjonalne, brak w tym kontekście
                    Instant.now()
            );

            auditLogService.publishAuditEvent(event);

        } catch (Exception e) {
            // Błąd audytu nie może przerywać operacji biznesowej
            log.error("[AuditAspect] Błąd podczas budowania zdarzenia audytowego: " +
                            "action={}, entityType={}, error={}",
                    audited.action(), audited.entityType(), e.getMessage(), e);
        }

        return result;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Pobiera stan encji przed operacją używając {@code EntityManager.find()}.
     *
     * <p>Poprzednia implementacja wywoływała getter serwisu przez refleksję + Spring AOP proxy,
     * co zawsze otwierało nową transakcję {@code readOnly=true} i wykonywało dodatkowe
     * zapytanie SELECT (niezależnie od tego, czy encja była już w L1 cache). To powodowało
     * 2× DB read dla każdej operacji UPDATE/DELETE z {@code captureOldValue=true}.
     *
     * <p>Nowe podejście: {@code em.find(entityClass, entityId)} działa bezpośrednio
     * na EntityManager bieżącej transakcji. Gdy encja jest już załadowana w sesji JPA
     * (np. przez wcześniejsze {@code findById} w tej samej transakcji serwisu) –
     * Hibernate zwraca ją z L1 cache bez zapytania do bazy. Gdy nie ma w cache –
     * wykonuje jeden SELECT, bez overhead proxy.
     *
     * <p>Mapowanie {@code entityType} → klasa JPA odbywa się przez {@link #ENTITY_CLASS_MAP}.
     * Dla nieznanych typów encji – fallback: null (brak old_value w audycie).
     *
     * @param pjp     punkt złączenia
     * @param audited adnotacja z konfiguracją
     * @return JSON string reprezentujący stan encji, lub null gdy nie udało się pobrać
     */
    private String captureOldValue(ProceedingJoinPoint pjp, Audited audited) {
        try {
            // Znajdź UUID (entity_id) w parametrach
            UUID entityId = findFirstUuidParam(pjp.getArgs(), audited.entityIdParamIndex());
            if (entityId == null) {
                log.debug("[AuditAspect] Nie znaleziono UUID w parametrach dla old_value – pomijam");
                return null;
            }

            // Wyznacz klasę JPA na podstawie entityType
            Class<?> entityClass = ENTITY_CLASS_MAP.get(audited.entityType());
            if (entityClass == null) {
                // Nieznany typ – fallback na getter serwisu przez refleksję (stara ścieżka)
                String fetchMethod = audited.fetchOldValueMethod();
                if (fetchMethod == null || fetchMethod.isBlank()) {
                    log.debug("[AuditAspect] captureOldValue=true, entityType={} nieznany i brak fetchOldValueMethod – pomijam",
                            audited.entityType());
                    return null;
                }
                Object target = pjp.getTarget();
                Method getter = target.getClass().getMethod(fetchMethod, UUID.class);
                Object oldEntity = getter.invoke(target, entityId);
                return serializeToJson(oldEntity);
            }

            // Szybka ścieżka: em.find() trafia w L1 cache jeśli encja jest już załadowana
            // w bieżącej transakcji serwisu (np. przez findById wykonany przez serwis).
            // Gdy nie ma w cache – wykona jeden SELECT bez overhead proxy.
            Object oldEntity = em.find(entityClass, entityId);
            return serializeToJson(oldEntity);

        } catch (Exception e) {
            log.warn("[AuditAspect] Nie udało się pobrać old_value: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Serializuje obiekt do JSON string, usuwając pola wrażliwe.
     *
     * @param obj obiekt do serializacji (może być null)
     * @return JSON string lub null gdy obj jest null
     */
    private String serializeToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            // Konwertuj przez ObjectNode żeby móc usunąć wrażliwe pola
            ObjectNode node = objectMapper.convertValue(obj, ObjectNode.class);
            SENSITIVE_FIELDS.forEach(node::remove);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("[AuditAspect] Nie udało się serializować obiektu {} do JSON: {}",
                    obj.getClass().getSimpleName(), e.getMessage());
            // Fallback: zwróć podstawowe info zamiast null
            return "{\"type\":\"" + obj.getClass().getSimpleName() + "\",\"error\":\"serialization_failed\"}";
        }
    }

    /**
     * Wyodrębnia UUID encji z parametrów wywołania lub wyniku metody.
     *
     * <p>Kolejność prób:
     * <ol>
     *   <li>Parametr pod indeksem {@link Audited#entityIdParamIndex()} (gdy >= 0)</li>
     *   <li>Pierwszy parametr typu UUID</li>
     *   <li>Pole {@code id} na obiekcie wynikowym przez refleksję</li>
     * </ol>
     *
     * @param pjp     punkt złączenia z argumentami wywołania
     * @param audited adnotacja z konfiguracją
     * @param result  wynik wywołania metody
     * @return UUID encji lub null gdy nie znaleziono
     */
    private UUID extractEntityId(ProceedingJoinPoint pjp, Audited audited, Object result) {
        Object[] args = pjp.getArgs();

        // Próba 1: jawnie wskazany indeks parametru
        int idx = audited.entityIdParamIndex();
        if (idx >= 0 && idx < args.length && args[idx] instanceof UUID uuid) {
            return uuid;
        }

        // Próba 2: pierwszy parametr UUID
        UUID fromParam = findFirstUuidParam(args, -1);
        if (fromParam != null) {
            return fromParam;
        }

        // Próba 3: pole "id" na wyniku metody
        if (result != null) {
            try {
                Method idGetter = result.getClass().getMethod("id");
                Object idValue = idGetter.invoke(result);
                if (idValue instanceof UUID uuid) {
                    return uuid;
                }
            } catch (NoSuchMethodException ignored) {
                // Próbuj przez pole "getId"
            } catch (Exception e) {
                log.trace("[AuditAspect] Nie udało się pobrać id() z wyniku: {}", e.getMessage());
            }

            try {
                Method idGetter = result.getClass().getMethod("getId");
                Object idValue = idGetter.invoke(result);
                if (idValue instanceof UUID uuid) {
                    return uuid;
                }
            } catch (Exception e) {
                log.trace("[AuditAspect] Nie udało się pobrać getId() z wyniku: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * Zwraca pierwszy parametr UUID z tablicy argumentów.
     *
     * @param args  argumenty wywołania metody
     * @param index jeśli >= 0, próbuje pobrać element pod tym indeksem; w przeciwnym razie
     *              przeszukuje od początku
     * @return pierwszy znaleziony UUID lub null
     */
    private UUID findFirstUuidParam(Object[] args, int index) {
        if (args == null || args.length == 0) {
            return null;
        }
        if (index >= 0 && index < args.length) {
            return args[index] instanceof UUID uuid ? uuid : null;
        }
        for (Object arg : args) {
            if (arg instanceof UUID uuid) {
                return uuid;
            }
        }
        return null;
    }
}
