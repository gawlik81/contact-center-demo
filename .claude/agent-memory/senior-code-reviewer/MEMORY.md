# Agent Memory Index

## Project

- [Frontend Angular patterns and known issues](./project_frontend_patterns.md) — Angular 21 frontend: critical bugs (localStorage token, module-level interceptor state, hardcoded tenant IDs), major issues (pagination, stub routes, DOM listener bypass), and positive patterns (OnPush, lazy loading, native dialog, trackBy). First full review 2026-03-17.
- [Backend Java/Spring Boot patterns and known issues](./project_backend_patterns.md) — Spring Boot 3.3.5 / Java 21 backend: critical bugs (N+1 in deactivateTenant, wrong blacklist TTL, mfaVerified bypass on refresh, admin metrics always 0), security issues (TOTP replay, LaissezFaire Redis deserializer), architecture violations (repositories not extending TenantAwareRepository, missing clearAutomatically), and positive patterns (filter chain, SHA-256 blacklist, TenantContext lifecycle). First full review 2026-03-17.
