---
name: CrossTenantAspect fałszywy alarm dla publicznych serwisów domenowych
description: Pointcut domainServiceMethods() obejmuje wszystkie @Service w com.contactcenter.domain.*. Serwisy wywoływane przez publiczne endpointy (AuthService.login, AuthService.refreshToken) nie mają TenantContext — CrossTenantAspect loguje ERROR nawet gdy jest to poprawne zachowanie.
type: project
---

`CrossTenantAspect.domainServiceMethods()` pointcut: `within(@Service *) && within(com.contactcenter.domain..*) && !within(com.contactcenter.domain.websocket.*)`. `AuthService` jest w `com.contactcenter.domain.service` — podpada pod pointcut. Endpoint `/api/auth/login` jest publiczny, `TenantFilter` go pomija, `TenantContext` pusty → ERROR w logach.

**Why:** Odkryto 2026-03-27. Fałszywy alarm pojawia się za każdym razem gdy agent/supervisor się loguje. Brak wpływu funkcjonalnego, ale zanieczyszcza logi ERROR — utrudnia wykrycie prawdziwych błędów konfiguracji.

**How to apply:** Przy analizie logów: ignoruj `CrossTenantAspect ERROR` dla metod `AuthService.login`, `AuthService.refreshToken`, `PublicTenantService.*`. Przy naprawie: dodaj `!within(com.contactcenter.domain.service.AuthService)` do pointcutu lub stwórz adnotację `@SkipTenantContextCheck`.
