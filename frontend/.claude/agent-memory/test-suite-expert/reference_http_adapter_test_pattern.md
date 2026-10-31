---
name: reference_http_adapter_test_pattern
description: How this backend repo unit-tests java.net.http.HttpClient-based adapters/clients (real local JDK HttpServer, not mocked HttpClient)
metadata:
  type: reference
---

Several backend classes talk to external HTTP APIs via plain `java.net.http.HttpClient`
(no WireMock/MockWebServer dependency in the repo): `TwilioRecordingDownloadServiceImpl`,
`WhatsAppAdapter` (`infrastructure.social`), `SocialIntegrationServiceImpl` (revoke/refresh-token
Graph API calls), `AiSummaryClient`, `VoicebotClientImpl`, `TwilioTelephonyAdapter`.

Established test convention for these (see `TwilioRecordingDownloadServiceTest`,
`backend/app/src/test/java/com/contactcenter/domain/recording/`): spin up a real
`com.sun.net.httpserver.HttpServer` on `localhost:0` (JDK built-in, zero extra deps) in
`@BeforeEach`, register a context handler that inspects/answers the request, point the class under
test's base URL at `"http://localhost:" + port`, then assert on the real captured request
(method/path/headers/body) rather than mocking `HttpClient.send(...)`. `httpServer.stop(0)` in
`@AfterEach` is safe to call even if `start()` was never invoked (used deliberately in some error-path
tests to simulate "server exists but not accepting").

**Prerequisite this often needs:** the target class's base URL / host must be an injectable field
(constructor param, ideally `@Value` with the real prod URL as default) rather than a
`private static final String` constant baked into the class. `WhatsAppAdapter.GRAPH_API_BASE` was
refactored this way on 2026-08-29 (test-suite-expert session) specifically to enable this pattern —
minimal change, default value unchanged, see git history around that date. `SocialIntegrationServiceImpl`'s
own Graph API calls (`revokeTokenAtProvider`, `exchangeForLongLivedToken`) and `AiSummaryClient`/
`VoicebotClientImpl` still hardcode their `HttpClient`/base URL inline — if asked to add HTTP-level
tests for those, evaluate the same minimal refactor first (that's the pattern this repo prefers over
introducing a mocking library).

**How to apply:** default to this pattern for any new HTTP-adapter test in this repo. Only reach
for mocking `HttpClient` directly (via constructor-injected `HttpClient` + Mockito) if the base URL
truly cannot be made configurable without disproportionate change.
