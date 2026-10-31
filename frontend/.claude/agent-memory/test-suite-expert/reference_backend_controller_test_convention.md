---
name: reference_backend_controller_test_convention
description: How REST controllers in api.* are unit-tested in this backend repo (no MockMvc/security chain), and where the precedent is documented
metadata:
  type: reference
---

Backend controllers under `com.contactcenter.api.*` are unit-tested by **direct method
invocation** on a manually constructed controller instance (mocked dependencies via
`@ExtendWith(MockitoExtension.class)`), NOT via `MockMvc`/`@WebMvcTest` with the Spring Security
filter chain active. `TenantContext.setTenantId(...)` is set directly in `@BeforeEach` (it's a
real static `InheritableThreadLocal`, no need for `mockStatic` in most cases — `RetentionControllerTest`
uses `mockStatic(TenantContext.class)` but simpler tests like `TenantTwilioConfigControllerTest`/
`SocialMessageServiceTest` just call the real setter).

Consequence: `@PreAuthorize("hasRole('ADMIN')")` is verified **declaratively by code review only**,
not by a 403-without-role unit test — there is no active Spring Security filter chain in these
tests to produce a real 403. This is an explicit, documented project decision, not an oversight.

Where this is written down: Javadoc of `RetentionControllerTest`
(`backend/app/src/test/java/com/contactcenter/api/retention/RetentionControllerTest.java`,
BE-118) explains it in detail and notes even the repo's few existing `@WebMvcTest`s
(`CustomerImportControllerTest`, `CampaignImportControllerTest`, `PluginAssetControllerTest`)
explicitly set `addFilters = false`.

Also from that same precedent: `@Valid` (Bean Validation on request DTOs) is inert when calling
the controller method directly (no Spring MVC argument resolution) — test it separately via
`jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator().validate(dto)`,
see `RetentionControllerTest.BeanValidation` nested class or `SocialOAuthControllerTest.BeanValidation`.

**How to apply:** when asked to test a new/existing `api.*` controller in this repo, follow this
same pattern (direct invocation + real `TenantContext` + separate `Validator` for `@Valid`) instead
of introducing `MockMvc`/`@WebMvcTest` for just one controller — that would fork the testing
convention for no real gain. If a full security-filter-chain test is genuinely wanted, it should be
scoped as a cross-cutting task covering all `api.*` controllers at once, not bolted onto a single
controller's test file.
