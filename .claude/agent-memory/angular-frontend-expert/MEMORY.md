# Agent Memory Index

## Feedback memories

- [RouterLinkActive.isActive zawodne przy OnPush](./feedback_routerlinkactive_onpush.md) – używaj Router events + toSignal zamiast rla.isActive jako parametru
  metody

## Project memories

- [Angular testing patterns](./project_testing_patterns.md) – ng test via Angular builder, vi.fn() mocks, no fakeAsync (zoneless), environment path depth
- [Angular workspace setup (FE-001)](./project_fe001_workspace.md) – Angular 21 in `frontend/`, Vitest, standalone components, proxy config,
  ESLint+Prettier+Husky setup
- [Routing, guards and auth infrastructure (FE-002)](./project_fe002_routing.md) – lazy loading routes, AuthGuard, RoleGuard, AuthService, TokenService, HTTP
  interceptor with silent refresh
- [HTTP interceptors and notification infrastructure (FE-003)](./project_fe003_interceptors.md) – ErrorHandlerInterceptor, NotificationService (signals),
  ToastContainerComponent (custom CSS, no Angular Material)
- [Auth UI – Login and Change Password (FE-004)](./project_fe004_auth_ui.md) – LoginComponent with MFA step, ChangePasswordComponent, AuthService extensions,
  routing under /auth
- [App Shell infrastructure (FE-005)](./project_fe005_shell.md) – AppShellComponent, TopNavbar, Sidenav (role-aware nav), Breadcrumbs, BreadcrumbService,
  responsive breakpoints, WCAG skip-link
- [Tenant management UI (FE-006)](./project_fe006_tenants.md) – TenantListComponent (paged table, filters, badges, skeleton), TenantFormComponent (async
  validator), TenantDeactivateModal (native dialog), TenantService, TENANT_ROUTES
- [Admin Dashboard metrics RT (FE-007)](./project_fe007_admin_dashboard.md) – AdminDashboardComponent (KPI cards, tenant table, alert banner, skeleton),
  AdminMetricsService (30s polling via timer+switchMap+shareReplay), CSS progress bars for agent utilization
- [Agent management UI (FE-008)](./project_fe008_agent_management.md) – UserListComponent (table, status/skill filters, skeleton), UserFormComponent (native
  dialog, skills chip multi-select, password validator), UserDeleteModal (409→warning toast), UserResetPasswordModal, UserService
- [CR-FRONTEND code review fixes](./project_cr_frontend.md) – 20 issues fixed: XSS (token in-memory), TokenRefreshService, dynamic tenants, pagination, stub
  routes, host bindings for dialogs, shareReplay refCount:true, computed() for filteredSkills
- [Admin cross-tenant user management (FE-009 / FE-010)](./project_fe009_admin_users.md) – Full CRUD admin users (edit/delete/force-reset), TenantEditModal,
  AdminUpdateUserRequest DTO, updateTenant + checkNameAvailabilityForUpdate in TenantService
- [Agent Desktop layout and status panel (FE-009)](./project_fe009_agent_desktop.md) – WebSocketService (native WS, no STOMP), AgentStatusService,
  ContactTabStore (tab limits), AgentDesktopComponent (header/sidebar/tabs), WS reconnect banner
- [Softphone WebRTC component (FE-010)](./project_fe010_softphone.md) – SoftphoneService (signal state machine), SoftphoneComponent (5 states, transfer panel
  blind/attended), ContactTabStore.updateTabStatus, AgentDesktop integration
- [Customer panel during contact (FE-011)](./project_fe011_customer_panel.md) – CustomerPanelComponent (4 stany, pure CSS skeleton), CustomerLookupService (
  Map-cache 5min, 404→null), CustomerProfile model, integracja z AgentDesktop (activeCli computed, aside 280px)
- [Disposition panel after contact (FE-017)](./project_fe017_disposition_panel.md) – DispositionPanelComponent (modal ACW, timer, dropdown, textarea),
  ContactService (PATCH /api/contacts/{id}/disposition), ContactTabStore.wrappingTab + markAsWrapping, effect() w AgentDesktop monitoruje
  SoftphoneService.session().state===ENDED
- [Customer detail view and contact history (FE-019)](./project_fe019_customer_detail.md) – CustomerDetailComponent (4 stany, skeleton, error/not-found),
  CustomerService.getCustomerContacts, paginowana historia kontaktów, badge CSS per kanał/status
- [Supervisor RT Dashboard (FE-021)](./project_fe021_supervisor_dashboard.md) – KPI cards, agent table z break-time tracking, queue CSS bar chart,
  SupervisorMetricsService (WS SUPERVISOR_METRICS), fullscreen API
- [Campaign management (FE-015)](./project_campaigns_fe015.md) – CampaignListComponent (polling 10s), CampaignFormComponent (native dialog, cross-field
  validators, day selector signals), CampaignService, campaign.model.ts
- [Customer import from CSV (FE-020)](./project_fe020_customer_import.md) – CustomerImportComponent (full-page wizard), CustomerImportStatus model,
  CustomerService import methods, route customers/import
- [Customer import columnMapping fix](./project_fe020_customer_import_columnmapping_fix.md) – multi-column phone/email (bug nadpisywania), named custom_fields,
  opcje zgód RODO w mapowaniu, nowy format columnMapping (customer-refactor, 2026-07-05)
- [IVR editor drag & drop (FE-014)](./project_fe014_ivr_editor.md) – IvrListComponent, IvrEditorComponent (HTML5 DnD + SVG Bezier), IvrService (localStorage
  positions), walidacja, mock audio upload
- [FE-022 Reports module](./project_fe022_reports.md) – historical reports for SUPERVISOR/ADMIN; files created, conventions, decisions (2026-03-22)
- [Email contact component (FE-012)](./project_fe012_email_contact.md) – EmailContactComponent (split-panel thread+reply), EmailThreadMessageComponent (iframe srcdoc), EmailService, contenteditable rich text editor, no Angular Material
- [Email attachment support (FE-012 ext)](./project_fe012_email_attachments.md) – upload (POST /api/email/attachments/upload), download presigned URL, pending list signals, sr-only class convention confirmed
- [Twilio settings panel per tenant (FE-025)](./project_fe025_twilio_settings.md) – TwilioSettingsComponent, TwilioConfigService, badge per-tenant vs fallback, E.164 walidacja, route settings/twilio
- [Manual dialer call button (FE-027)](./project_fe027_manual_dialer_button.md) – DialerService (POST /api/dialer/manual/call), przycisk "Zadzwoń" w CampaignContactsComponent, callingRecordId signal, optymistyczna aktualizacja
- [Contact detail modal + audio player (FE-028/030)](./project_fe028_contact_detail_modal.md) – ContactDetailModalComponent (native dialog, 3 sekcje, lazy recording), AudioPlayerComponent (HTML5 Audio, własny UI), CustomerDetailComponent integracja (selectedContactId signal, klikalne wiersze)
- [Contacts report page (FE-029)](./project_fe029_contacts_report.md) – ContactsReportComponent (/supervisor/reports/contacts), 7 filtrów, tabela 9 kolumn z badgami, paginacja 25/str, eksport CSV client-side, ContactService.getContacts(), submenu Raporty w sidenavie
- [Phone numbers and routing rules (FE-026)](./project_fe026_phone_numbers.md) – PhoneNumbersComponent, RoutingRulesComponent, RoutingRuleFormComponent, PhoneNumberService, route settings/phone-numbers zastąpiło settings/twilio
- [Social Media integrations panel (FE-023)](./project_fe023_social_integrations.md) – OAuth redirect flow, 3 karty platform, dialog rozłączenia, OauthCallbackComponent, route supervisor/settings/integrations
- [Email templates management (FE-031)](./project_fe031_email_templates.md) – EmailTemplatesComponent (CRUD, 3 native dialogs, preview iframe), EmailService extensions, route settings/email-templates, sidenav Konfiguracja
- [Social contact component (FE-013)](./project_fe013_social_contact.md) – SocialContactComponent (czat FB/IG/WA), WS SOCIAL_MESSAGE_RECEIVED, infinite scroll, read-only mode, ContactType rozszerzony o SOCIAL
- [Agent customer search tab (FE-040)](./project_fe040_agent_customers_tab.md) – AgentCustomersTabComponent (/agent/customers), debounce Subject+300ms, CustomerSummary model, CustomerSearchService, drawer ze szczegółami, @Output scheduleCallback dla FE-041
- [Agent calendar component (FE-043)](./project_fe043_agent_calendar.md) – AgentCalendarComponent (tyg/dzień, 3 typy zdarzeń, FAB), calendarTabActive signal w AgentDesktop, placeholder dla FE-045
- [Transloco i18n setup (FE-049)](./project_fe049_transloco.md) – @jsverse/transloco v8, własny HttpLoader (brak gotowego), public/i18n/ dla pl/en/de, provideTransloco w app.config
- [LanguageService and language model (FE-050)](./project_fe050_language_service.md) – LanguageService (priority chain backend→localStorage→navigator→'pl'), APP_INITIALIZER, Signal mock pattern dla isAuthenticated w testach
- [Language switcher component (FE-051)](./project_fe051_language_switcher.md) – LanguageSwitcherComponent (custom dropdown, HostListener outside-click, signal isOpen), osadzony w TopNavbar
- [Twilio phone number select (FE-068)](./project_fe068_twilio_phone_select.md) – TwilioPhoneNumberSelectComponent (ControlValueAccessor, 5 stanów, allowNull), TwilioConfigService.getPhoneNumbers(), integracja w TwilioConfig + CampaignForm
- [Nowy design system oklch + ThemeService](./project_new_design_system.md) – tokeny oklch light/dark/auto, ThemeService (signal+effect), KPI strip w navbar, przebudowa wizualna shell+agent desktop
- [AdHocEmailModal WYSIWYG fix](./project_fe_adhoc_email_wysiwyg.md) – bodyHtml textarea→contenteditable, wzorowane na EmailContactComponent, gotcha z label-has-associated-control
- [Plugins settings page (FE-098)](./project_fe098_plugins_page.md) – PluginsPageComponent, dryf BE/FE w PluginVersionDto.permissions (zweryfikuj zawsze w *.java), ConfirmDialogComponent wzorzec @if+signal, brak narzędzia do weryfikacji w przeglądarce w tym sandboxie
- [Plugin panel host iframe (FE-099)](./project_fe099_plugin_panel_host.md) – cc-plugin-panel-host, plugin-ui-sdk-message.model.ts, RT-11 origin+source validation, contactId/customerId/tenantId jako @Input od rodzica (nie self-fetch), blokuje FE-100
- [Agent desktop plugin mount (FE-100, EPIC-28 zamknięty)](./project_fe100_agent_desktop_mount.md) – skąd brane tenantId/contactId/customerId w AgentDesktopComponent, własny mechanizm tabs (brak shared), zero-regresji w HTML potwierdzone, lint/build czyste
- [Customer externalId field (customer-refactor branch)](./project_customer_external_id.md) – externalId w CustomerResponse/CustomerProfile/CustomerSummary + UI; CR fix 2026-07-05: `??` zamiast `||` przy czyszczeniu, 409 detail message, CustomerSummary jednak rozszerzony
- [CustomerEditComponent customFields edit (2026-07-05)](./project_customer_edit_customfields.md) – pierwszy w repo wzorzec FormArray<FormGroup> typowany (`fb.array<CustomFieldFormGroup>`), naprawa przedistniejącego braku edycji pól dodatkowych
