---
name: revoked-token-401-entrypoint
description: Unieważniony (poprawnie podpisany) JWT dawał 403 zamiast 401; fix = authenticationEntryPoint w SecurityConfig + UnauthorizedResponseWriter
metadata:
  type: project
---

Bez `exceptionHandling().authenticationEntryPoint` Spring Security odpowiada 403 (Http403ForbiddenEntryPoint) dla żądań bez SecurityContext, a JwtAuthFilter przy unieważnieniu (blacklista, revoked-since, tenant INACTIVE, user not found) przepuszcza żądanie bez kontekstu. TenantFilter przepuszcza token z dobrym podpisem, więc 401 dawały tylko brak tokenu i zły podpis.

Fix: `UnauthorizedResponseWriter` (package-private, wspólny ProblemDetail 401 dla TenantFilter i entry pointa), `SecurityConfig.securityFilterChain(HttpSecurity, ObjectMapper)`. accessDeniedHandler bez zmian (403 dla uwierzytelnionych). Test: `security/RevokedTokenReturns401MvcTest` (prawdziwy łańcuch jak w AgentSelfControllerTest).

**Why:** FE (FE-173) odświeża/wylogowuje tylko na 401.
**How to apply:** nowy test z `@Import(SecurityConfig)` ma mieć ObjectMapper (auto-config `@EnableAutoConfiguration` wystarcza). /ws, /ws-native i publiczne ścieżki są permitAll, entry point ich nie dotyczy.
