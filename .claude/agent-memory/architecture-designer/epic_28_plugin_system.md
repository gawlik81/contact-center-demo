---
name: epic_28_plugin_system
description: EPIC-28 per-tenant runtime plugin/extension system architecture — ADR-09..13, RT-09..14, added to ARCHITECTURE.md section 11
metadata:
  type: project
---

## What this is
EPIC-28: a runtime, JAR-based, per-tenant plugin system for the Contact Center SaaS platform,
designed 2026-06-20 and added as a new §11 in ARCHITECTURE.md (after Appendix C, ~line 1313
onward). Continues ADR numbering from ADR-09 (last existing was ADR-08) and RT numbering from
RT-09 (last existing was RT-08).

**Why this is separate from ADR-08:** ADR-08 ("Modular Social Media Plugin Architecture",
ARCHITECTURE.md ~line 1167) is an unrelated, pre-existing, compile-time plugin pattern for
social-media channel adapters (Spring component scanning, no runtime upload, no per-tenant
JAR). Do not conflate the two in future work — EPIC-28 is the only one that is tenant-admin
JAR-upload + runtime ClassLoader.

## Pre-agreed decisions (do not renegotiate without explicit user request)
1. **Execution isolation:** in-process, one dedicated `ClassLoader` per `(tenant_id,
   plugin_key)` pair, same JVM as Spring Boot — NOT a separate process/container. Accepted
   residual risk (RT-10): JDK has no hard sandbox since `SecurityManager` deprecation;
   mitigated via narrow parent classloader (only `plugin-sdk` interfaces exposed), static
   bytecode scan (ASM) at upload, no direct access to ApplicationContext/JPA/beans — only a
   `PluginContext` facade.
2. **UI integration:** hybrid — (a) data hooks via existing REST endpoints called by existing
   Angular components, AND (b) plugin-provided UI panel in a cross-origin sandboxed
   `<iframe sandbox="allow-scripts allow-forms">` (explicitly no `allow-same-origin`),
   communicating only via a `postMessage`-based `PluginUiSdk` — never same-origin web
   component, since plugin code is third-party/untrusted.
3. **Extension points (v1 scope):** `PRE_CONTACT_CONNECT` (blocking, 2s timeout, never blocks
   call connect on failure), `POST_CONTACT_END` (async/fire-and-forget via RabbitMQ
   `cc.queue.plugin-invocation`), `CUSTOMER_SYNC` (async), `DISPOSITION_SET` (async),
   `MANUAL_ACTION` (blocking, agent/supervisor-triggered button, 5s timeout).
4. **Hook mechanism:** fixed, versioned, backend-defined extension-point enum dispatched by
   `ExtensionPointPublisher` — explicitly NOT a generic AOP/interceptor chain. Mirrors the
   `TelephonyAdapter` (ADR-05) philosophy of a stable interface decoupled from internals.

## Key data model (all new tables, §11.9 of ARCHITECTURE.md)
- `PLUGIN`, `PLUGIN_VERSION` — global catalog, NOT tenant-scoped, no RLS (immutable once
  VALIDATED, same "never edit applied migration" philosophy as Flyway).
- `TENANT_PLUGIN_INSTALLATION`, `TENANT_PLUGIN_EXTENSION_BINDING`, `PLUGIN_INVOCATION_LOG` —
  tenant-scoped, RLS enabled, following the project's `app.current_tenant_id` pattern.
- Plugin custom data written into `customer.custom_fields.plugins.<pluginKey>` namespaced
  JSONB path — never a flat merge, never an existing typed column (CLAUDE.md anti-overloaded-
  column rule applied explicitly here, also recorded as RT-14).

## SDK shape
New Maven module `backend/plugin-sdk` — interfaces + immutable DTOs only, no Spring/JPA
dependency, so it can't be used to reach host internals via classpath. Entry point
`PluginEntryPoint` (onActivate/onDeactivate/onPreContactConnect/onPostContactEnd/
onCustomerSync/onDispositionSet/onManualAction). Only object passed to plugin: `PluginContext`
facade (read-only DTOs for customer/contact, `appendContactNote`, allow-listed
`HttpEgressClient` checked against manifest's `http:egress:<host>` permissions, tenant-scoped
`PluginLogger`/`PluginConfig`).

## Open questions raised (OQ-28-1..3)
- OQ-28-1: is JAR signing mandatory before production rollout, or is static-scan + manual
  review (ADR-11) sufficient for initial vetted-partner-only scope? Unresolved — flagged for
  security/compliance before first non-pilot tenant onboarding.
- OQ-28-2: cross-tenant plugin marketplace/catalog in scope for a later epic?
- OQ-28-3: concurrency ceiling for `PluginInvocationExecutor` — needs real load-test data.

## How to apply
When asked to implement EPIC-28 tickets (backend `plugin-sdk` module, `PluginRuntimeManager`,
upload/validation pipeline, Angular `cc-plugin-panel-host`), refer to ARCHITECTURE.md §11 as
the source of truth — it has the full manifest JSON shape, table DDL sketches, sequence
diagram (mermaid, §11.13), and the ADR-09..13 rationale. Don't re-derive the isolation model
from scratch; it was deliberately negotiated with the user as in-process/ClassLoader, not
process/container.
