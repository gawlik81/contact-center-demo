# Agent Memory Index

## Feedback memories
- [RouterLinkActive.isActive zawodne przy OnPush](./feedback_routerlinkactive_onpush.md) – używaj Router events + toSignal zamiast rla.isActive jako parametru metody

## Project memories
- [Angular workspace setup (FE-001)](./project_fe001_workspace.md) – Angular 21 in `frontend/`, Vitest, standalone components, proxy config, ESLint+Prettier+Husky setup
- [Routing, guards and auth infrastructure (FE-002)](./project_fe002_routing.md) – lazy loading routes, AuthGuard, RoleGuard, AuthService, TokenService, HTTP interceptor with silent refresh
- [HTTP interceptors and notification infrastructure (FE-003)](./project_fe003_interceptors.md) – ErrorHandlerInterceptor, NotificationService (signals), ToastContainerComponent (custom CSS, no Angular Material)
- [Auth UI – Login and Change Password (FE-004)](./project_fe004_auth_ui.md) – LoginComponent with MFA step, ChangePasswordComponent, AuthService extensions, routing under /auth
- [App Shell infrastructure (FE-005)](./project_fe005_shell.md) – AppShellComponent, TopNavbar, Sidenav (role-aware nav), Breadcrumbs, BreadcrumbService, responsive breakpoints, WCAG skip-link
- [Tenant management UI (FE-006)](./project_fe006_tenants.md) – TenantListComponent (paged table, filters, badges, skeleton), TenantFormComponent (async validator), TenantDeactivateModal (native dialog), TenantService, TENANT_ROUTES
- [Admin Dashboard metrics RT (FE-007)](./project_fe007_admin_dashboard.md) – AdminDashboardComponent (KPI cards, tenant table, alert banner, skeleton), AdminMetricsService (30s polling via timer+switchMap+shareReplay), CSS progress bars for agent utilization
- [Agent management UI (FE-008)](./project_fe008_agent_management.md) – UserListComponent (table, status/skill filters, skeleton), UserFormComponent (native dialog, skills chip multi-select, password validator), UserDeleteModal (409→warning toast), UserResetPasswordModal, UserService
- [CR-FRONTEND code review fixes](./project_cr_frontend.md) – 20 issues fixed: XSS (token in-memory), TokenRefreshService, dynamic tenants, pagination, stub routes, host bindings for dialogs, shareReplay refCount:true, computed() for filteredSkills
- [Admin cross-tenant user management (FE-009 / FE-010)](./project_fe009_admin_users.md) – Full CRUD admin users (edit/delete/force-reset), TenantEditModal, AdminUpdateUserRequest DTO, updateTenant + checkNameAvailabilityForUpdate in TenantService
- [Agent Desktop layout and status panel (FE-009)](./project_fe009_agent_desktop.md) – WebSocketService (native WS, no STOMP), AgentStatusService, ContactTabStore (tab limits), AgentDesktopComponent (header/sidebar/tabs), WS reconnect banner
