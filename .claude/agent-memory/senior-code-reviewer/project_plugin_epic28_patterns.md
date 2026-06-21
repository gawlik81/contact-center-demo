---
name: EPIC-28 plugin system (per-tenant ClassLoader isolation) patterns and known issues
description: domain.plugin / domain.plugin.runtime packages — in-process ClassLoader isolation for tenant plugins (RT-10, ADR-09). Critical TCCL leak found in BE-101 review (2026-06-20), still open as of that date — re-check status before reviewing BE-102+.
type: project
---

EPIC-28 "Per-Tenant Plugin (Extension) System" — ARCHITECTURE.md §11 (11.1-11.11), risk RT-10
is the single largest architectural risk in this epic (accepted in-process isolation trade-off,
ADR-09). Ticket sequence: BE-097 (plugin-sdk module) → BE-098 (validation/ASM scan) → BE-099
(upload/storage) → BE-100 (PluginRegistrationService, DB-only) → BE-101 (PluginRuntimeManager /
ClassLoader isolation — most critical) → BE-102 (ExtensionPointPublisher, circuit breaker,
timeouts) → BE-103+ (telephony integration).

**Why this file exists separately from [[project_backend_patterns]]:** this subsystem has a
fundamentally different threat model (untrusted third-party bytecode executing in-process) than
the rest of the backend, and BE-102/BE-103/etc. will need this context repeatedly. Don't merge
into the general backend patterns file — keep it scoped.

**How to apply:** Before reviewing any new BE-10x ticket in this epic, re-grep the actual files
listed below to confirm whether each finding is still open — this file is a snapshot from the
2026-06-20 BE-101 review, not a live state.

## Critical finding from BE-101 review (2026-06-20) — verify fix status before trusting isolation claims

**Thread-Context ClassLoader (TCCL) is never reset at the plugin invocation boundary.**
`PluginRuntimeManagerImpl.lifecycleExecutor` (`Executors.newCachedThreadPool` with a custom
`ThreadFactory`) creates worker threads that, by default JVM behavior, inherit the TCCL of the
thread that created them (verified empirically — new threads get the creator's TCCL, which in
the Spring Boot app is the application classloader with the full classpath). `entryPoint.onActivate(context)`
is invoked on this thread **without** `Thread.currentThread().setContextClassLoader(pluginClassLoader)`
beforehand. Any plugin code calling `Thread.currentThread().getContextClassLoader()` (common in
third-party libraries via `ServiceLoader.load(X.class)`, JAXB, some JSON/XML libs) gets the full
application classloader and can do `Class.forName("com.contactcenter.domain...", true, tccl)` —
bypassing `PlatformApiClassLoader`'s package filter entirely. This is NOT covered by the existing
tests (`PlatformApiClassLoaderTest`/`PluginClassLoaderTest` only test explicit-classloader
`Class.forName` calls, never the TCCL path) and NOT blocked by `PluginBytecodeScanner`'s ASM
blacklist (`Thread#getContextClassLoader`/`setContextClassLoader`, `ServiceLoader#load` are
absent from `BLOCKED_METHOD_CALLS`/`BLOCKED_OWNER_PREFIXES`).

**Required fix (two layers, both needed):**
1. Snapshot/set/restore TCCL around every plugin entrypoint invocation (`onActivate`,
   `onDeactivate`, and future BE-102 extension-point calls) — same shape as the mandatory
   `TenantContext.snapshot()/restore()/clear()` pattern (CLAUDE.md/§11.8), but for
   `Thread.setContextClassLoader`.
2. Add `Thread#getContextClassLoader`, `Thread#setContextClassLoader`,
   `java/util/ServiceLoader#load` to `PluginBytecodeScanner`'s blacklist.

**When reviewing BE-102 (`PluginInvocationExecutor`):** this is the next place a thread pool is
created for plugin code — check whether BE-102 fixed this for its own executor, and whether
BE-101's `PluginRuntimeManagerImpl.lifecycleExecutor` was also retrofitted. If BE-102 introduces
its own executor without fixing this, the same Critical finding applies there.

## Other BE-101 findings (severity, status as of 2026-06-20 review)

- **High — temp JAR file leak.** `PluginRuntimeManagerImpl.downloadJarToLocalCache` (`Files.createTempFile`)
  is never cleaned up, not in `load()`, not in `unload()` (`closeQuietly` only closes the
  `URLClassLoader`, doesn't delete the backing file). Unbounded disk growth on repeated
  enable/disable/rollback. Self-reported by the implementing agent as a known limitation;
  confirmed real by this review. Fix needs either storing `Path` in `PluginInstanceHandle` for
  cleanup in `unload()`, or a shared cache keyed by `pluginVersionId` with refcounting.
- **Medium — `PluginLoggerImpl` violates the SDK's own Javadoc contract.** `plugin-sdk`'s
  `PluginLogger` interface explicitly promises plugin logs are "never mixed into the platform's
  own application logs" — the impl calls plain SLF4J `log.info/warn/error`, mixing them in.
  Self-documented as temporary until BE-102 adds `PluginInvocationLogService`. No message length
  limit or CRLF sanitization — log-forging/log-flood vector from untrusted plugin input until
  BE-102 lands.
- **Low — new `HttpClient` per `PluginContextImpl` construction** in `PluginHttpEgressClientImpl`,
  not shared/cached. Will matter more once BE-102 calls extension points per-contact at volume;
  circuit breaker state (per `(tenant_id, plugin_key, host)`, per SDK Javadoc) implies long-lived
  state that doesn't fit a per-invocation object — flag for BE-102 design.

## Positive patterns confirmed (carry forward, don't re-litigate unless code changes)

- `PlatformApiClassLoader.loadClass` correctly **delegates** to `super.loadClass()` for the
  allowed prefix rather than `defineClass`-redefining — avoids loader-constraint /
  `ClassCastException` identity mismatch. This was a deliberate fix from a first version that
  used `defineClass`; the delegation approach is correct and well-documented.
- `PluginClassLoader` gets one instance per `(tenant_id, plugin_key)` — verified by reference
  identity tests, not just behavior tests (`isNotSameAs` on two loaders for the same JAR/different
  tenants).
- `PluginContextImpl.tenantId` is genuinely structurally frozen — `final` field, set only via
  constructor called by `PluginRuntimeManagerImpl`, and the SDK's `PluginContext` interface has
  no method taking a `tenantId` parameter at all (not just "by convention" — by interface shape).
  Every data-touching method threads through tenant-aware services down to repository-level
  explicit tenant checks (e.g. `CustomerRepository.findById` double-checks
  `customer.getTenantId().equals(tenantId)` even on top of RLS).
- `updateCustomerFields` correctly namespaces writes to `custom_fields.plugins.<pluginKey>`,
  verified by test, never a flat merge — consistent with CLAUDE.md's anti-overloaded-column rule
  and RT-14.
- `unload()` GC-eligibility for the `PluginClassLoader` is verified by an actual `WeakReference` +
  `System.gc()` test with a bounded (20-round) retry loop, not a flaky infinite retry — includes a
  notably careful comment about Mockito's invocation history holding a strong reference that
  would otherwise produce a false leak signal in the test itself.
- ASM static bytecode scan (`PluginBytecodeScanner`, BE-098) blocks `setAccessible`,
  `ProcessBuilder`, `java.nio.file.*`, `sun.misc.*`, and `ClassLoader` subclassing — good first
  layer, but see the TCCL/ServiceLoader gap above, which is the same mechanism and should be
  extended, not replaced.

## Where to look for related future tickets

- BE-102 (`ExtensionPointPublisher`, `PluginInvocationExecutor`, circuit breaker) — not yet
  implemented as of 2026-06-20. Will introduce a second/replacement executor for blocking
  extension-point calls (`PRE_CONTACT_CONNECT` 2s, `MANUAL_ACTION` 5s) — re-check TCCL fix applies
  there too.
- Migration for `tenant_plugin_installation`: `backend/src/main/resources/db/migration/V075__create_tenant_plugin_installation.sql`
  (DB-043) — has RLS, out of scope for BE-101 review, not re-audited here.
