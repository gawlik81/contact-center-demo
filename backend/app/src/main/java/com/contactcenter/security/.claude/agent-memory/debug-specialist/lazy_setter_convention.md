---
name: lazy_setter_convention
description: Project convention for breaking Spring circular bean dependencies via @Lazy @Autowired setter injection
metadata:
  type: project
---

This codebase's established (pre-existing, documented in code comments) convention
for breaking circular bean dependencies is **setter injection with `@Lazy @Autowired`**,
NOT `spring.main.allow-circular-references=true` (explicitly forbidden — that's a
workaround, not a fix, per task instructions and code comments).

Pattern:
```java
private SomeService someService; // non-final, NOT in @RequiredArgsConstructor

@Autowired
@Lazy
public void setSomeService(SomeService someService) {
    this.someService = someService;
}
```

Existing examples before the [[circular_bean_dependencies_refactor]] work:
- `ContactServiceImpl.setUserService(UserService)` — breaks UserService ↔ ContactService.
- `TwilioTelephonyAdapter.setUserService(UserService)` — breaks
  TwilioTelephonyAdapter → UserService → ContactService → TelephonyAdapter cycle.
- `TenantServiceImpl.setAdminMetricsService(AdminMetricsService)` — breaks
  TenantService → AdminMetricsService → TenantRepository ← TenantService cycle.

**How to apply:** When introducing a new cross-domain dependency that could create a
cycle (especially anything touching `ContactService`, `UserService`, or
`TenantService` — these are hub services with many dependents), check whether the
target class is itself (transitively) depended upon by the service you're injecting
into. If so, use the `@Lazy` setter pattern on the LESS central class. Remember to
update Mockito unit tests: `@InjectMocks` does NOT populate non-final fields via
setters — call `instance.setXxx(mock)` explicitly in `@BeforeEach`, and fix any
direct `new XxxImpl(...)` constructor calls in tests (param count changes).
