package com.contactcenter.infrastructure.aspect;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
     * Wszystkie publiczne metody serwisów domenowych w pakiecie domain,
     * z wyjątkiem pakietu domain.websocket.
     *
     * <p>Klasy w domain.websocket (np. WebSocketEventBroadcaster) celowo nie korzystają
     * z TenantContext – działają w wątkach RabbitMQ listener, które nie przechodzą
     * przez TenantFilter. Włączenie ich do pointcutu generuje fałszywe ERROR logi.
     */
    @Pointcut("within(@org.springframework.stereotype.Service *) && " +
              "within(com.contactcenter.domain..*) && " +
              "!within(com.contactcenter.domain.websocket.*)")
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
     * <p>Sprawdzenie jest aktywne <strong>tylko dla żądań HTTP</strong> – wątki
     * asynchroniczne (RabbitMQ listenery, {@code @Scheduled}, {@code @Async}) działają
     * poza HTTP pipeline i celowo nie mają TenantContext. Obecność aktywnego
     * {@code RequestAttributes} jest wyznacznikiem wątku HTTP.
     *
     * <p>Gdy TenantContext nie jest ustawiony dla żądania HTTP, to błąd konfiguracji
     * filtrów (TenantFilter powinien był zablokować żądanie wcześniej). Loguje ERROR.
     *
     * @param joinPoint punkt złączenia
     */
    @Before("domainServiceMethods()")
    public void verifyTenantContext(JoinPoint joinPoint) {
        if (!TenantContext.isSet()) {
            String className  = joinPoint.getTarget().getClass().getSimpleName();
            String methodName = joinPoint.getSignature().getName();

            if (isAuthenticationBootstrapMethod(className, methodName)) {
                log.trace("[CrossTenant][AuthBootstrap] TenantContext nie ustawiony dla {}.{} "
                        + "(oczekiwane – wywołanie z JwtAuthFilter/UserDetailsServiceImpl "
                        + "przed TenantFilter)", className, methodName);
                return;
            }

            // Sprawdź czy wywołanie pochodzi z wątku HTTP czy asynchronicznego.
            // RequestContextHolder ma aktywne atrybuty tylko dla żądań HTTP.
            boolean isHttpRequestThread = RequestContextHolder.getRequestAttributes() != null;

            if (isHttpRequestThread) {
                // Sprawdź URI – webhooks Twilio są publiczne i celowo nie mają TenantContext.
                // Logowanie ERROR dla tych ścieżek byłoby fałszywym alarmem.
                String requestUri = resolveRequestUri();
                if (requestUri != null && isExpectedPublicPath(requestUri)) {
                    log.trace("[CrossTenant][Public] TenantContext nie ustawiony dla publicznego "
                            + "endpointu {}.{} (URI={}; oczekiwane – TenantFilter pomija tę ścieżkę)",
                            className, methodName, requestUri);
                } else {
                    // Brak TenantContext w wątku HTTP = błąd konfiguracji (TenantFilter pominięty)
                    log.error("[CrossTenant][Config] TenantContext NIE JEST ustawiony dla metody {}.{} " +
                            "w wątku HTTP. Sprawdź czy TenantFilter jest poprawnie zarejestrowany " +
                            "w SecurityConfig.",
                            className, methodName);
                }
            } else {
                // Wątek async (RabbitMQ, @Scheduled) – brak TenantContext jest oczekiwany.
                // Serwisy domenowe wywołane z tych wątków powinny przyjmować tenantId jako
                // jawny parametr (setTenantContextInDb(uuid) zamiast setTenantContextInDb()).
                log.trace("[CrossTenant][Async] TenantContext nie ustawiony w wątku async dla {}.{} " +
                        "(oczekiwane – wątek: {})",
                        className, methodName, Thread.currentThread().getName());
            }

            // Nie rzucamy wyjątku – pozwalamy na propagację ISE z TenantContext.getTenantId()
            // gdy metoda domeny spróbuje go użyć bez jawnego parametru tenantId.
        }
    }

    /**
     * Metody serwisów domenowych wywoływane przez JwtAuthFilter / UserDetailsServiceImpl
     * jako część bootstrapu autentykacji – PRZED TenantFilter, więc TenantContext
     * jest celowo nieustawiony niezależnie od URI żądania.
     */
    private boolean isAuthenticationBootstrapMethod(String className, String methodName) {
        return "UserServiceImpl".equals(className)
            && "findAuthenticatableUser".equals(methodName);
    }

    /**
     * Publiczne ścieżki, dla których brak TenantContext jest oczekiwany.
     * Musi być spójne z {@code TenantFilter.PUBLIC_PATH_PREFIXES}.
     */
    private boolean isExpectedPublicPath(String uri) {
        // Auth endpoints: TenantContext nie jest ustawiony celowo (login/refresh nie wymagają JWT)
        return uri.startsWith("/api/auth/login")
            || uri.startsWith("/api/auth/refresh")
            || uri.startsWith("/api/auth/mfa/")
            || uri.startsWith("/api/telephony/webhook")
            || uri.startsWith("/api/telephony/hold-music")
            || uri.startsWith("/api/public/")
            || uri.startsWith("/api/oauth/")
            || uri.startsWith("/api/webhooks/")
            || uri.startsWith("/api/logs");
    }

    /**
     * Bezpiecznie pobiera URI aktualnego żądania HTTP.
     *
     * @return URI żądania lub null gdy nie można odczytać (np. wątek async)
     */
    private String resolveRequestUri() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attrs.getRequest();
            return request.getRequestURI();
        } catch (Exception e) {
            return null;
        }
    }
}
