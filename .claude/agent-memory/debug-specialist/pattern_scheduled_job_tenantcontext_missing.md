---
name: pattern_scheduled_job_tenantcontext_missing
description: Audyt wszystkich @Scheduled jobów pod kątem brakującego TenantContext na wątku schedulera (assertSameTenant/setTenantContextInDb()/currentTenantId() rzucają ISE) — EPIC-29 retention/recording jobs dotknięte, reszta bezpieczna dzięki 3 znanym wzorcom
type: project
---

## Kontekst

Audyt (2026-08-13, branch `contact-arch`) wszystkich metod `@Scheduled` w
`backend/app/src/main/java/` pod kątem wzorca: wątek schedulera Springa nie ma
`TenantContext` (ThreadLocal, ustawiany tylko przez `TenantFilter` na wątkach HTTP), więc
każde wywołanie `TenantAwareRepository.assertSameTenant(...)`, bezargumentowego
`setTenantContextInDb()` lub `currentTenantId()` na tym wątku rzuca `IllegalStateException`.
Te wyjątki są zwykle łapane przez per-tenant/per-category `try/catch` wewnątrz joba, więc job
"kończy się sukcesem" w logach mimo że nic nie zapisał.

## POTWIERDZONE ŻYWE BUGI (ten sam root cause)

1. **`RetentionEvaluationJob.runEvaluationJob()`** (`domain/retention/`, cron 01:00 UTC) —
   `TenantRetentionPendingSummaryRepository.upsert()` woła `assertSameTenant(tenantId)` →
   ISE dla KAŻDEGO tenanta/kategorii, KAŻDY przebieg. Tabela
   `tenant_retention_pending_summary` zawsze pusta. Zweryfikowane live na kontenerze.
2. **`RecordingRetentionJob.runRetentionJob()`** (`domain/recording/`, cron 02:00 UTC) —
   `ContactRepository.clearRecordingUrl(contactId, tenantId)` woła
   `assertSameTenant(tenantId, contactId)` → ISE. S3 file JEST usuwany (ta wywołanie idzie
   pierwsze i nie dotyka TenantContext), ale `contact.recording_url` NIGDY nie jest czyszczone
   w DB → dangling reference, ten sam kontakt wraca każdej nocy, próba ponownego usunięcia z S3
   (zwykle no-op), nieskończona pętla "duchów" w logach ERROR. Gorszy niż #1 bo aktywnie kasuje
   dane bez spójności referencji.
3. **Dormant (auto_purge_enabled=false dla wszystkich tenantów dziś), ale ten sam bug**:
   `RetentionEvaluationJob.maybeTriggerAutoPurge()` → `RetentionPurgeServiceImpl.purge()` →
   `RetentionPurgeLogRepository.insertRunning()` (`assertSameTenant`) rzuca ISE SYNCHRONICZNIE,
   zanim `purgeAsync` w ogóle wystartuje. Nawet gdyby to naprawić osobno: `purge()` woła
   `TenantContext.snapshot()` na (pustym) wątku schedulera → snapshot z `tenantId=null` →
   `purgeAsync()` (`@Async`) woła `TenantContext.restore(snapshot)`, ale `restore()` pomija pola
   null → tenantId dalej nieustawiony na wątku roboczym → kolejny ISE przy pierwszym
   `TenantAwareRepository` write (`ContactRepository.deleteBatchOlderThan`,
   `CampaignArchiveRetentionRepository.purgeEligible`, itd.). To DRUGI, niezależny wariant tego
   samego problemu, o warstwę głębiej.

## POTWIERDZONE BEZPIECZNE (przeczytane, zweryfikowane)

- `PartitionMaintenanceJob`, `PartitionReclaimJob` — repozytoria (`PartitionMaintenanceRepository`,
  `PartitionScannerImpl`) celowo NIE rozszerzają `TenantAwareRepository` (operacje cross-tenant
  po zamierzeniu — DDL partycji, MIN/MAX retencji po wszystkich tenantach).
- `EtlSyncServiceImpl` (4 metody) — raw `JdbcTemplate`, brak `TenantAwareRepository`, jawne filtry
  `tenant_id` w SQL, brak zależności od RLS.
- `SupervisorMetricsService.broadcastMetrics()`, `WaitTimeEstimationServiceImpl.broadcastWaitTimeUpdates()`
  — repozytoria wywoływane WYŁĄCZNIE przez warianty z jawnym `tenantId` jako parametrem
  (`setTenantContextInDb(tenantId)` lub metody bez RLS w ogóle, np.
  `ContactRepository.getAvgHandleTimeSeconds`/`countWaitingByQueueId`/`getAvgCurrentWaitSeconds` —
  jawnie udokumentowane "Nie wywołuje setTenantContextInDb()").

## ISTNIEJĄCE POPRAWNE WZORCE W REPO (do kopiowania przy naprawie)

Trzy niezależne, już działające miejsca ustawiają `TenantContext` per-tenant NA TYM SAMYM
wątku schedulera (synchronicznie, przed wejściem w logikę biznesową/repozytoria), zamiast polegać
na propagacji async:

1. **Najprostszy — `SocialIntegrationServiceImpl.refreshToken()`** (`domain/social/`):
   ```java
   TenantContext.setTenantId(tenantId);
   try { ... praca z repozytoriami ... }
   finally { TenantContext.clear(); }
   ```
2. **Snapshot ręczny — `EmailPollingServiceImpl.pollAllTenants()`** (`domain/email/`):
   buduje `new TenantContext.Snapshot(tenant.getId(), null, tenant.getName(), "SYSTEM")`
   RĘCZNIE (NIE przez `TenantContext.snapshot()`, bo to przechwyciłoby pusty kontekst wątku
   schedulera), potem `TenantContext.restore(snapshot)` / `finally { TenantContext.clear(); }`.
3. Ten sam `setTenantId()/clear()` wzorzec powtórzony niezależnie w: `ScheduledCallbackExecutor`,
   `ProgressiveDialerServiceImpl.pollAvailableAgents`, `DialerCallbackHandlerImpl.checkRingTimeouts`,
   `RoutingServiceImpl.pollAvailableAgents` (tu przez ręcznie zbudowany `Snapshot` + `restore`),
   `CampaignWindowActivator`, `ContactAssignmentMonitor`, `AgentBreakActivator` — wszystkie
   pre-istniejące (spoza EPIC-29), wszystkie poprawne.

**Rekomendowany fix dla EPIC-29**: dodać `TenantContext.setTenantId(tenantId)` /
`finally { TenantContext.clear(); }` wokół ciała pętli per-tenant w
`RetentionEvaluationJob.persistAndMaybeAutoPurge()` i `evaluateCampaignData()` (osobne pętle,
osobny fix w obu) oraz w `RecordingRetentionJob.processRetentionForTenant()`. Ustawienie kontekstu
w `RetentionEvaluationJob` PRZED wywołaniem `maybeTriggerAutoPurge()` naprawia transytywnie też
dormant auto-purge chain (bo `TenantContext.snapshot()` wewnątrz `RetentionPurgeServiceImpl.purge()`
przechwyci wtedy poprawny tenantId) — jeden punkt naprawy, dwa efekty.

## Powiązane wzorce

Zobacz też [[pattern_twilio_public_endpoint_tenantcontext]] (ISE z `assertSameTenant` na wątku
publicznego endpointu) i [[pattern_crosstenant_aspect_false_alarm]] (`CrossTenantAspect` loguje
tylko TRACE dla wątków async/scheduled — nie jest źródłem prawdziwego alarmu, prawdziwy stack
trace ISE widać dopiero w `log.error` per-tenant catch bloku samego joba).
