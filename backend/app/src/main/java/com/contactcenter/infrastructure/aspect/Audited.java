package com.contactcenter.infrastructure.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adnotacja oznaczająca metodę serwisową jako wymagającą zapisu do dziennika audytu.
 *
 * <p>Gdy metoda serwisowa jest oznaczona {@code @Audited}, aspekt {@link AuditAspect}
 * przechwytuje wywołanie i:
 * <ol>
 *   <li>Przed wywołaniem – serializuje stan encji jako {@code old_value} (dla UPDATE/DELETE).</li>
 *   <li>Po wywołaniu – serializuje stan encji jako {@code new_value} (dla CREATE/UPDATE).</li>
 *   <li>Publikuje zdarzenie audytowe do RabbitMQ (exchange {@code cc.audit}).</li>
 * </ol>
 *
 * <p>Pola wrażliwe ({@code password}, {@code passwordHash}, {@code mfaSecret}, {@code token},
 * {@code refreshToken}) są automatycznie wykluczane z serializacji.
 *
 * <h3>Przykład użycia:</h3>
 * <pre>{@code
 * @Audited(action = "TENANT_CREATED", entityType = "TENANT")
 * public TenantResponse createTenant(CreateTenantRequest request) { ... }
 *
 * @Audited(action = "USER_UPDATED", entityType = "USER", captureOldValue = true)
 * public AppUser updateUser(UUID userId, UpdateUserRequest request) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /**
     * Kod akcji audytowej w formacie SNAKE_UPPER_CASE.
     * Przykłady: TENANT_CREATED, USER_DEACTIVATED, CAMPAIGN_STARTED.
     */
    String action();

    /**
     * Typ encji domenowej, której dotyczy operacja.
     * Przykłady: TENANT, USER, CAMPAIGN, CUSTOMER.
     */
    String entityType();

    /**
     * Indeks parametru metody zawierającego UUID encji (entity_id).
     * Wartość -1 oznacza brak parametru UUID (ID będzie pobrane z wyniku metody).
     *
     * <p>Aspekt próbuje znaleźć UUID w kolejności:
     * <ol>
     *   <li>Parametr pod indeksem {@code entityIdParamIndex} (gdy >= 0)</li>
     *   <li>Pierwszy parametr typu UUID</li>
     *   <li>Pole {@code id} na obiekcie zwróconym przez metodę</li>
     * </ol>
     */
    int entityIdParamIndex() default -1;

    /**
     * Czy przechwytywać stan encji przed wywołaniem (old_value).
     *
     * <p>Ustaw {@code true} dla operacji UPDATE i DELETE.
     * Aspekt wywoła metodę gettera podaną w {@link #fetchOldValueMethod()}
     * z pierwszym parametrem UUID z wywołania metody.
     */
    boolean captureOldValue() default false;

    /**
     * Nazwa metody w tym samym serwisie zwracającej aktualny stan encji
     * (używana do pobrania old_value przed aktualizacją).
     *
     * <p>Metoda musi przyjmować jeden parametr UUID (entity_id) i zwracać obiekt.
     * Używana tylko gdy {@link #captureOldValue()} == true.
     *
     * <p>Przykład: gdy serwis ma metodę {@code getTenant(UUID id)},
     * ustaw {@code fetchOldValueMethod = "getTenant"}.
     */
    String fetchOldValueMethod() default "";
}
