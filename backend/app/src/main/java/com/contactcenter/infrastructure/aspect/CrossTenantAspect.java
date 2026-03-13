package com.contactcenter.infrastructure.aspect;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Aspekt AOP monitorujący i logujący próby dostępu cross-tenant.
 *
 * <p><strong>Odpowiedzialności:</strong>
 * <ol>
 *   <li><strong>Logowanie prób cross-tenant</strong> – gdy {@link CrossTenantAccessException}
 *       jest rzucony przez repozytorium lub serwis domenowy, aspekt loguje WARNING
 *       z pełnym kontekstem (tenant, zasób, metoda).</li>
 *   <li><strong>Weryfikacja kontekstu</strong> – sprawdza, czy TenantContext jest ustawiony
 *       przed wywołaniem metod warstwy domenowej (wczesne wykrycie błędów konfiguracji).</li>
 * </ol>
 *
 * <p><strong>Pointcuty:</strong>
 * <ul>
 *   <li>{@code domainServiceMethods()} – wszystkie publiczne metody serwisów domenowych
 *       ({@code @Service} w pakiecie {@code com.contactcenter.domain})</li>
 *   <li>{@code repositoryMethods()} – wszystkie publiczne metody repozytoriów
 *       ({@code @Repository} w pakiecie {@code com.contactcenter.domain})</li>
 * </ul>
 *
 * <p>Aspekt NIE blokuje wykonania (nie rzuca wyjątków samodzielnie) – jedynie loguje.
 * Blokowanie dostępu jest odpowiedzialnością {@link com.contactcenter.domain.repository.TenantAwareRepository}.
 */
@Slf4j
@Aspect
@Component
public class CrossTenantAspect {

    // =========================================================================
    // Pointcuty
    // =========================================================================

    /**
     * Wszystkie publiczne metody serwisów domenowych w pakiecie domain.
     */
    @Pointcut("within(@org.springframework.stereotype.Service *) && " +
              "within(com.contactcenter.domain..*)")
    public void domainServiceMethods() {}

    /**
     * Wszystkie publiczne metody repozytoriów w pakiecie domain.
     */
    @Pointcut("within(@org.springframework.stereotype.Repository *) && " +
              "within(com.contactcenter.domain..*)")
    public void repositoryMethods() {}

    /**
     * Publiczne metody serwisów i repozytoriów domenowych.
     */
    @Pointcut("domainServiceMethods() || repositoryMethods()")
    public void domainLayerMethods() {}

    // =========================================================================
    // Advice
    // =========================================================================

    /**
     * Loguje WARNING gdy wykryto próbę dostępu cross-tenant.
     *
     * <p>Wywoływany po rzuceniu {@link CrossTenantAccessException} przez
     * dowolną metodę warstwy domenowej. Wyjątek jest dalej propagowany.
     *
     * @param joinPoint punkt złączenia – dostarcza informacje o metodzie
     * @param ex        rzucony wyjątek cross-tenant
     */
    @AfterThrowing(
            pointcut = "domainLayerMethods()",
            throwing = "ex"
    )
    public void logCrossTenantAttempt(JoinPoint joinPoint, CrossTenantAccessException ex) {
        UUID requestingTenant = ex.getRequestingTenantId();
        UUID resourceId = ex.getResourceId();

        String className  = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();

        log.warn("[CrossTenant][Security] PRÓBA DOSTĘPU CROSS-TENANT: " +
                        "metoda={}.{}, zasób={}, tenant_żądający={}",
                className, methodName, resourceId, requestingTenant);

        // Dodatkowy alarm dla security audit – poziom WARN jest monitorowany przez SIEM
        if (log.isWarnEnabled()) {
            log.warn("[CrossTenant][AUDIT] Potencjalne naruszenie izolacji tenantów: " +
                    "tenant={} próbował uzyskać dostęp do zasobu={} (szczegóły: {})",
                    requestingTenant, resourceId, ex.getMessage());
        }
    }

    /**
     * Weryfikuje, że TenantContext jest ustawiony przed wywołaniem metod serwisowych.
     *
     * <p>Gdy TenantContext nie jest ustawiony dla chronionego endpointu, to błąd
     * konfiguracji filtrów (TenantFilter powinien był zablokować żądanie wcześniej).
     * Loguje ERROR i pozwala propagować ISE.
     *
     * @param joinPoint punkt złączenia
     */
    @Before("domainServiceMethods()")
    public void verifyTenantContext(JoinPoint joinPoint) {
        if (!TenantContext.isSet()) {
            String className  = joinPoint.getTarget().getClass().getSimpleName();
            String methodName = joinPoint.getSignature().getName();

            // To jest błąd konfiguracji – powinno nigdy nie wystąpić gdy TenantFilter jest poprawny
            log.error("[CrossTenant][Config] TenantContext NIE JEST ustawiony dla metody {}.{}. " +
                    "Sprawdź czy TenantFilter jest poprawnie zarejestrowany w SecurityConfig.",
                    className, methodName);

            // Nie rzucamy wyjątku tutaj – pozwalamy na propagację ISE z TenantContext.getTenantId()
            // gdy metoda domeny spróbuje go użyć. Dzięki temu nie zakłócamy flow publicznych endpointów
            // obsługiwanych przez metody serwisowe (np. webhook validation).
        }
    }
}
