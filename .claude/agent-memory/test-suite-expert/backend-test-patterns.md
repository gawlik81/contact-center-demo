---
name: Backend test patterns for contact-center-demo
description: Recurring Mockito/JUnit5 patterns that work well in this project
type: project
---

## Multi-tenancy test pattern

```java
@BeforeEach void setUp() { TenantContext.setTenantId(TENANT_ID); }
@AfterEach void tearDown() { TenantContext.clear(); }
```

CrossTenantAccessException test: set TENANT_A in context, call assertSameTenant(TENANT_B, resource) → expect exception with requestingTenantId=A, resourceTenantId=B.

## TenantFilter test pattern

Use `MockHttpServletRequest` + `MockHttpServletResponse` from `spring-test`.
Verify TenantContext is populated INSIDE `doAnswer` on `filterChain.doFilter()`.
Verify TenantContext is NULL after `tenantFilter.doFilter()` returns (finally block check).

## Lenient mock setup

Services with many collaborators use `@MockitoSettings(strictness = Strictness.LENIENT)` to allow shared `@BeforeEach` mocks without "unnecessary stubbing" errors in tests that don't trigger all paths.

## JwtService test

Uses in-memory RSA 2048-bit key pair (generated in `@BeforeAll`). No PEM files needed.
`jwtService.initWithPrivateKey(privateKey)` and `jwtParser.initWithPublicKey(publicKey, issuer)`.

## RabbitMQ publish verification

```java
verify(rabbitTemplate).convertAndSend(
    eq(RabbitMQConfig.EXCHANGE_EVENTS),
    eq("contact.assigned"),
    any(ContactAssignedEvent.class)
);
```

## Uncovered areas (as of 2026-04-24)

- `CampaignService` — no unit tests
- `AdminUserService` — no unit tests  
- `EmailSendService` — no unit tests
- `IvrService` — no unit tests
- Frontend: `AuthService`, `AgentStatusService`, `ContactService`, all feature components except EmailContactComponent and CustomerListComponent
