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
     *   <li>Akcesor wskazany przez {@link #entityIdResultAccessor()} na wyniku metody (gdy ustawiony)</li>
     *   <li>Pierwszy parametr typu UUID</li>
     *   <li>Pole {@code id} / {@code getId()} na obiekcie zwróconym przez metodę</li>
     * </ol>
     */
    int entityIdParamIndex() default -1;

    /**
     * Nazwa bezargumentowej metody-akcesora na WYNIKU metody, zwracającej UUID encji (entity_id).
     *
     * <p>Przeznaczona dla operacji CREATE, gdzie ID encji jest generowane WEWNĄTRZ metody
     * (nieznane w momencie wywołania – {@link #entityIdParamIndex()} nie ma zastosowania),
     * a wynik jest rekordem Javy z własną nazwą akcesora (np. {@code customerId()},
     * {@code contactId()}, {@code queueId()}), nie konwencjonalnym {@code id()} / {@code getId()}.
     *
     * <p><strong>Uwaga (BE-146):</strong> gdy ten atrybut jest ustawiony (niepusty), aspekt
     * wywołuje wskazany akcesor na wyniku i NIE skanuje parametrów wywołania w poszukiwaniu
     * UUID (próba "pierwszy parametr typu UUID" jest pomijana). Jest to świadome – bez tego
     * jawna deklaracja "ID jest w wyniku" mogłaby zostać przesłonięta przez przypadkowy UUID
     * w parametrach (np. {@code tenantId} przekazany jako jedyny UUID do metody CREATE, który
     * semantycznie NIE jest {@code entity_id} – zob. bug opisany w BE-146).
     *
     * <p>Przykład: {@code @Audited(action = "CUSTOMER_CREATED", entityType = "CUSTOMER",
     * entityIdResultAccessor = "customerId")} dla {@code createCustomer(CreateCustomerRequest,
     * UUID tenantId)} zwracającej {@code CustomerResponse(UUID customerId, ...)}.
     *
     * <p>Domyślna wartość {@code ""} oznacza "nie ustawiony" – aspekt zachowuje dotychczasowe
     * zachowanie (skan parametrów, potem {@code id()}/{@code getId()} na wyniku).
     */
    String entityIdResultAccessor() default "";

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
