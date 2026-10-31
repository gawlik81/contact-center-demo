# Memory Index

- [Backend controller test convention](reference_backend_controller_test_convention.md) — api.* controllers tested by direct invocation, no MockMvc/security chain, @PreAuthorize verified by code review only
- [HTTP adapter test pattern](reference_http_adapter_test_pattern.md) — real local com.sun.net.httpserver.HttpServer instead of mocking HttpClient; needs injectable base URL
- [Social module (WhatsApp) test coverage](project_social_module_coverage.md) — what's covered vs. still-untested in domain/api/infrastructure.social as of 2026-08-29
