---
name: Tenant management UI (FE-006)
description: TenantListComponent, TenantFormComponent, TenantDeactivateModal, TenantService – structure, patterns and key decisions
type: project
---

Feature is under `frontend/src/app/features/tenants/` with lazy-loaded TENANT_ROUTES registered in `admin.routes.ts` under `tenants` child path.

**Files created:**
- `tenant.model.ts` – Tenant, TenantConfig, TenantStatus, CreateTenantRequest, PagedResponse<T>, NameAvailabilityResponse, TenantListParams
- `tenant.service.ts` – getTenants(), createTenant(), deactivateTenant(), checkNameAvailability()
- `tenant-list/` – paginated table, filter form (debounce 300ms), status badges, skeleton loading, empty state, deactivate button (ACTIVE only)
- `tenant-form/` – reactive form with async name uniqueness validator (debounce 500ms via `timer(500)`)
- `tenant-deactivate-modal/` – native `<dialog>` element opened with `showModal()` in `ngAfterViewInit`
- `tenants.routes.ts` – TENANT_ROUTES ('' → TenantListComponent, 'new' → TenantFormComponent, both lazy-loaded)

**Key patterns used:**
- Signals for loading/pagination state; `computed()` only for pure signal derivations
- Getter methods (not `computed()`) for ReactiveForm validation helpers in templates – Angular CD re-evaluates getters on each check cycle; `computed()` does NOT track FormControl state
- `takeUntilDestroyed(this.destroyRef)` for all Observable subscriptions
- `ChangeDetectionStrategy.OnPush` on all components
- `viewChild<ElementRef<HTMLDialogElement>>('dialogEl')` + `ngAfterViewInit` to call `showModal()` (not `ngOnInit`)
- `DatePipe` imported as standalone in TenantListComponent (no NgModules)
- `debounceTime(300)` on filterForm.valueChanges resets page to 0 before reloading

**Admin route integration:**
- `admin.routes.ts` `tenants` child changed from `loadComponent` (stub) to `loadChildren: () => import('../tenants/tenants.routes').then(m => m.TENANT_ROUTES)`
- Sidenav already had 'Tenants' entry pointing to `/admin/tenants` – no change needed

**Why:** FE-006 task specification, BE-006 API already available.
**How to apply:** Reference this pattern for future feature modules. Always use getter methods (not `computed()`) for template-facing validation state derived from FormControls.
