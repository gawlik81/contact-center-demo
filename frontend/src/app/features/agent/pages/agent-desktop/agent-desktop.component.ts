import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  ViewChild,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
  viewChildren,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpClient } from '@angular/common/http';
import { DatePipe, LowerCasePipe } from '@angular/common';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { catchError, interval, of } from 'rxjs';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { AuthService } from '../../../../core/services/auth.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { ContactTabStore } from '../../services/contact-tab.store';
import { NotificationService } from '../../../../core/services/notification.service';
import { SoftphoneService } from '../../services/softphone.service';
import { AgentHotkeysService } from '../../services/agent-hotkeys.service';
import { AGENT_SHORTCUTS } from '../../models/agent-shortcuts.model';
import { isEditableElement } from '../../../../shared/utils/focus.util';
import { QueueStateService } from '../../services/queue-state.service';
import { SoftphoneComponent } from '../../components/softphone/softphone.component';
import { CustomerPanelComponent } from '../../components/customer-panel/customer-panel.component';
import { DispositionPanelComponent } from '../../components/disposition-panel/disposition-panel.component';
import { EmailContactComponent } from './email-contact/email-contact.component';
import { SocialContactComponent } from './social-contact/social-contact.component';
import { ManualCampaignPanelComponent } from '../../components/manual-campaign-panel/manual-campaign-panel.component';
import { AgentCalendarComponent } from './agent-calendar/agent-calendar.component';
import { AddBreakModalComponent } from '../../components/add-break-modal/add-break-modal.component';
import { StatusElapsedComponent } from '../../components/status-elapsed/status-elapsed.component';
import { ContactDetailModalComponent } from '../../../../shared/components/contact-detail-modal/contact-detail-modal.component';
import { PluginPanelHostComponent } from '../../../../shared/components/plugin-panel-host/plugin-panel-host.component';
import { PluginAdminService } from '../../../plugins/services/plugin-admin.service';
import { ManualActionDef, TenantPluginInstallationDto } from '../../../plugins/models/plugin.model';
import {
  AgentStatus,
  ALL_AGENT_STATUSES,
  AGENT_STATUS_CONFIG,
} from '../../models/agent-status.model';
import { ContactTab } from '../../models/contact-tab.model';
import { QueueItem } from '../../models/queue-item.model';

/** Manual action bound to its owning installation – flattened for toolbar rendering. */
interface ToolbarManualAction {
  installationId: string;
  action: ManualActionDef;
}

@Component({
  selector: 'app-agent-desktop',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    LowerCasePipe,
    TranslocoModule,
    SoftphoneComponent,
    CustomerPanelComponent,
    DispositionPanelComponent,
    EmailContactComponent,
    SocialContactComponent,
    ManualCampaignPanelComponent,
    AgentCalendarComponent,
    AddBreakModalComponent,
    StatusElapsedComponent,
    ContactDetailModalComponent,
    PluginPanelHostComponent,
  ],
  templateUrl: './agent-desktop.component.html',
  styleUrl: './agent-desktop.component.scss',
})
export class AgentDesktopComponent implements OnInit {
  @ViewChild(AgentCalendarComponent) private calendarRef?: AgentCalendarComponent;
  private readonly softphoneRef = viewChild(SoftphoneComponent);
  private readonly statusTrigger = viewChild<ElementRef<HTMLButtonElement>>('statusTrigger');
  private readonly statusItems = viewChildren<ElementRef<HTMLButtonElement>>('statusItem');
  private readonly tabButtons = viewChildren<ElementRef<HTMLButtonElement>>('tabBtn');

  private readonly ws = inject(WebSocketService);
  protected readonly statusService = inject(AgentStatusService);
  protected readonly tabStore = inject(ContactTabStore);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly softphoneService = inject(SoftphoneService);
  private readonly transloco = inject(TranslocoService);
  private readonly queueStateService = inject(QueueStateService);
  protected readonly authService = inject(AuthService);
  private readonly pluginAdminService = inject(PluginAdminService);
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  protected readonly hotkeys = inject(AgentHotkeysService);
  protected readonly shortcuts = AGENT_SHORTCUTS;

  protected readonly statusConfig = AGENT_STATUS_CONFIG;
  protected readonly allStatuses = ALL_AGENT_STATUSES;

  protected readonly queueItems = this.queueStateService.queueItems;
  protected readonly statusMenuOpen = signal(false);
  protected readonly now = signal(Date.now());

  protected readonly connectionState = this.ws.connectionState;
  protected readonly isOffline = computed(
    () => this.connectionState() === 'DISCONNECTED' || this.connectionState() === 'ERROR',
  );
  protected readonly isReconnecting = computed(() => this.connectionState() === 'CONNECTING');

  protected readonly currentStatusConfig = computed(
    () => this.statusConfig[this.statusService.currentStatus()],
  );

  protected readonly tabs = this.tabStore.tabs;
  protected readonly activeTab = this.tabStore.activeTab;

  /**
   * CLI (phone number) of the active contact tab.
   * Used to drive the CustomerPanelComponent for PHONE contacts.
   */
  protected readonly activeCli = computed<string>(() => {
    const tab = this.activeTab();
    if (!tab || tab.type !== 'PHONE') return '';
    return tab.customerIdentifier;
  });

  /**
   * Email address of the active contact tab.
   * Used to drive the CustomerPanelComponent for EMAIL contacts.
   */
  protected readonly activeEmail = computed<string>(() => {
    const tab = this.activeTab();
    if (!tab || tab.type !== 'EMAIL') return '';
    return tab.customerIdentifier;
  });

  /** Active tab in ACW/WRAPPING state – drives DispositionPanelComponent visibility */
  protected readonly wrappingTab = this.tabStore.wrappingTab;

  /**
   * Text of the assertive live region announcing a ringing call. Set once when the session
   * enters RINGING and cleared when it leaves, so screen readers hear each ringing event once.
   */
  protected readonly ringingAnnouncement = signal('');

  /** Calendar tab visibility */
  protected readonly calendarTabActive = signal(false);

  /** Add-break modal visibility */
  protected readonly addBreakOpen = signal(false);

  /** Contact ID to display in contact-detail-modal (opened from CustomerPanel history). */
  protected readonly selectedContactDetailId = signal<string | null>(null);

  /** Right panel (customer profile) collapse state */
  protected readonly customerPanelCollapsed = signal(false);

  /**
   * Plugin installations visible to this agent (EPIC-28, FE-100).
   * Loaded once on init via `GET /api/agent/plugins` (PluginAgentController) — backend already
   * filters to `enabled=true`; `healthStatus !== 'DISABLED_BY_ADMIN'` is re-checked here
   * defensively (acceptance criteria requires both conditions, and the contract may change).
   * Empty array (the common case — most tenants have zero plugins) means zero layout impact.
   */
  protected readonly pluginInstallations = signal<TenantPluginInstallationDto[]>([]);

  private readonly activePluginInstallations = computed(() =>
    this.pluginInstallations().filter((i) => i.enabled && i.healthStatus !== 'DISABLED_BY_ADMIN'),
  );

  /**
   * Installations declaring at least one AGENT_DESKTOP_SIDE_PANEL uiPanel.
   * Visible only while the agent is handling a contact (active tab with contactId)
   * or during ACW/disposition (wrapping tab) — hidden when agent is idle.
   */
  protected readonly sidePanelInstallations = computed(() => {
    const hasActiveContact = this.activeTab()?.contactId != null;
    const hasWrappingTab = this.wrappingTab() != null;
    if (!hasActiveContact && !hasWrappingTab) return [];
    return this.activePluginInstallations().filter((i) =>
      i.uiPanels.some((p) => p.mountPoint === 'AGENT_DESKTOP_SIDE_PANEL'),
    );
  });

  /** Currently selected plugin tab id in the side panel (null until first installation loads). */
  protected readonly activePluginPanelId = signal<string | null>(null);

  /** Flattened list of toolbar manual actions across all active installations. */
  protected readonly toolbarManualActions = computed<ToolbarManualAction[]>(() =>
    this.activePluginInstallations().flatMap((installation) =>
      installation.manualActions
        .filter((action) => action.mountPoint === 'AGENT_DESKTOP_TOOLBAR')
        .map((action) => ({ installationId: installation.id, action })),
    ),
  );

  /** installationId:actionId currently in flight – disables the button and avoids double-submits. */
  protected readonly pendingManualActionKey = signal<string | null>(null);

  protected readonly currentTenantId = this.authService.currentTenantId;

  /**
   * Identifies one ringing event: the contactId while RINGING, null otherwise. Keyed on it (not on
   * the state) so a second call ringing right after the first re-fires the effect below, while
   * duration ticks and name enrichment do not.
   */
  private readonly ringingCallId = computed(() => {
    const session = this.softphoneService.session();
    return session?.state === 'RINGING' ? session.contactId : null;
  });

  /**
   * The ringing moment must never be hidden: when a call starts ringing, leave the calendar
   * view, activate the call's PHONE tab, announce it to assistive tech and move focus to
   * "Odbierz" (unless the agent is typing, or a modal dialog has the page inert).
   * The announcement uses the best-known label at that moment and is not updated afterwards, so
   * the screen reader never repeats it when the customer name is enriched.
   */
  private readonly ringingEffect = effect(() => {
    const callId = this.ringingCallId();
    untracked(() => {
      if (callId === null) {
        this.ringingAnnouncement.set('');
        return;
      }
      this.calendarTabActive.set(false);

      const session = this.softphoneService.session();
      const phoneTabs = this.tabStore.tabs().filter((t) => t.type === 'PHONE');
      const phoneTab = phoneTabs.find((t) => t.contactId === callId) ?? phoneTabs[0];
      if (phoneTab) this.tabStore.setActiveTab(phoneTab.id);

      const who = session?.customerName || session?.customerPhone || '';
      let announcement = who
        ? this.transloco.translate('agent.desktop.ringingAnnouncement', { who })
        : this.transloco.translate('agent.desktop.ringingAnnouncementUnknown');
      if (session?.queueName) {
        announcement += `. ${this.transloco.translate('agent.desktop.ringingAnnouncementQueue', { queue: session.queueName })}`;
      }
      // Clear first so an identical text for a consecutive call is announced again.
      this.ringingAnnouncement.set('');
      afterNextRender(
        () => {
          if (this.ringingCallId() === callId) this.ringingAnnouncement.set(announcement);
        },
        { injector: this.injector },
      );

      afterNextRender(
        () => {
          const modalOpen = document.querySelector('dialog[open]') !== null;
          if (!modalOpen && !isEditableElement(document.activeElement)) {
            this.softphoneRef()?.focusAnswerButton();
          }
        },
        { injector: this.injector },
      );
    });
  });

  ngOnInit(): void {
    this.statusService.initStatus();

    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.now.set(Date.now()));

    // EPIC-28 / FE-100: load agent-visible plugin installations once. Failure is non-fatal —
    // the desktop must keep working even if the plugin subsystem is unavailable, so we swallow
    // the error here (no toast) and simply keep the panel/toolbar empty (zero layout impact).
    this.pluginAdminService
      .listAgentInstallations()
      .pipe(
        catchError(() => {
          console.warn('[AgentDesktop] Nie udało się wczytać instalacji pluginów (EPIC-28).');
          return of([] as TenantPluginInstallationDto[]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((installations) => {
        this.pluginInstallations.set(installations);
        const firstSidePanel = installations.find((i) =>
          i.uiPanels.some((p) => p.mountPoint === 'AGENT_DESKTOP_SIDE_PANEL'),
        );
        if (firstSidePanel) {
          this.activePluginPanelId.set(firstSidePanel.id);
        }
      });
  }

  protected openCalendarTab(): void {
    this.calendarTabActive.set(true);
  }

  protected closeCalendarTab(): void {
    this.calendarTabActive.set(false);
  }

  protected openAddBreak(): void {
    this.addBreakOpen.set(true);
  }

  protected onBreakSavedFromHeader(): void {
    this.addBreakOpen.set(false);
    this.calendarRef?.reload();
  }

  protected changeStatus(status: AgentStatus): void {
    this.closeStatusMenu(true);
    this.statusService.changeStatus(status).pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }

  protected toggleStatusMenu(): void {
    if (this.statusMenuOpen()) {
      this.closeStatusMenu(true);
    } else {
      this.openStatusMenu('current');
    }
  }

  /** Closes the status menu; `returnFocus` puts focus back on the trigger (keyboard paths). */
  protected closeStatusMenu(returnFocus = false): void {
    this.statusMenuOpen.set(false);
    if (returnFocus) this.statusTrigger()?.nativeElement.focus();
  }

  /** Index the status menu should focus once rendered; nudged by repeated arrow presses. */
  private pendingStatusFocus: number | null = null;

  private openStatusMenu(target: 'current' | 'first' | 'last'): void {
    const count = this.allStatuses.length;
    const current = this.allStatuses.indexOf(this.statusService.currentStatus());
    this.pendingStatusFocus =
      target === 'first' ? 0 : target === 'last' ? count - 1 : Math.max(current, 0);
    this.statusMenuOpen.set(true);
    afterNextRender(
      () => {
        const index = this.pendingStatusFocus;
        this.pendingStatusFocus = null;
        if (index !== null) this.statusItems()[index]?.nativeElement.focus();
      },
      { injector: this.injector },
    );
  }

  /** WAI-ARIA menu button: Enter/Space/ArrowDown open on the first (or current) item, ArrowUp on the last. */
  protected onStatusTriggerKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const count = this.allStatuses.length;
    if (this.statusMenuOpen() && this.pendingStatusFocus !== null) {
      // A second arrow press arrived before the menu rendered and took focus: move the target on.
      const step = event.key === 'ArrowDown' ? 1 : -1;
      this.pendingStatusFocus = (this.pendingStatusFocus + step + count) % count;
      return;
    }
    this.openStatusMenu(event.key === 'ArrowDown' ? 'first' : 'last');
  }

  protected onStatusMenuKeydown(event: KeyboardEvent): void {
    const items = this.statusItems().map((i) => i.nativeElement);
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    let next: number | null = null;
    switch (event.key) {
      case 'ArrowDown':
        next = (index + 1) % items.length;
        break;
      case 'ArrowUp':
        next = (index - 1 + items.length) % items.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = items.length - 1;
        break;
      case 'Escape':
        event.preventDefault();
        this.closeStatusMenu(true);
        return;
      case 'Tab':
        this.closeStatusMenu();
        return;
    }
    if (next !== null) {
      event.preventDefault();
      items[next]?.focus();
    }
  }

  /** WAI-ARIA tabs: roving tabindex, arrows wrap, Home/End jump; selection follows focus. */
  protected onTabKeydown(event: KeyboardEvent, current: ContactTab): void {
    // The close button is nested in the tab; only react to keys pressed on the tab itself.
    if (event.target !== event.currentTarget) return;
    const tabs = this.tabs();
    const index = tabs.findIndex((t) => t.id === current.id);
    let next: number | null = null;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    if (next === null) return;

    event.preventDefault();
    const tab = tabs[next];
    this.tabStore.setActiveTab(tab.id);
    afterNextRender(
      () =>
        this.tabButtons()
          .find((b) => b.nativeElement.id === 'tab-' + tab.id)
          ?.nativeElement.focus(),
      { injector: this.injector },
    );
  }

  /** Roving tabindex: the active tab, or the first one when none is active, is the tab stop. */
  protected isTabStop(tab: ContactTab): boolean {
    const stopId = this.activeTab()?.id ?? this.tabs()[0]?.id;
    return stopId === tab.id;
  }

  protected setActiveTab(tab: ContactTab): void {
    this.tabStore.setActiveTab(tab.id);
  }

  protected closeTab(event: MouseEvent, tab: ContactTab): void {
    event.stopPropagation();
    if (tab.type === 'PHONE') {
      this.softphoneService.hangupCall();
    }
    this.tabStore.closeTab(tab.id);
  }

  protected getWaitingTime(waitingSince: Date): string {
    const diffMs = this.now() - new Date(waitingSince).getTime();
    const minutes = Math.floor(diffMs / 60_000);
    const seconds = Math.floor((diffMs % 60_000) / 1_000);
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }

  protected getTabTypeIcon(tab: ContactTab): string {
    switch (tab.type) {
      case 'PHONE':
        return 'M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z';
      case 'CHAT':
        return 'M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 12H6v-2h12v2zm0-3H6V9h12v2zm0-3H6V6h12v2z';
      case 'EMAIL':
        return 'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z';
      case 'SOCIAL':
        return 'M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z';
    }
  }

  protected getTabTypeLabel(tab: ContactTab): string {
    switch (tab.type) {
      case 'PHONE':
        return this.transloco.translate('agent.desktop.tabPhone');
      case 'CHAT':
        return this.transloco.translate('agent.desktop.tabChat');
      case 'EMAIL':
        return this.transloco.translate('agent.desktop.tabEmail');
      case 'SOCIAL':
        return this.transloco.translate('agent.desktop.tabSocial');
    }
  }

  protected getQueueTypeIcon(item: QueueItem): string {
    switch (item.type) {
      case 'PHONE':
        return 'M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z';
      case 'CHAT':
        return 'M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 12H6v-2h12v2zm0-3H6V9h12v2zm0-3H6V6h12v2z';
      case 'EMAIL':
        return 'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z';
      case 'SOCIAL':
        return 'M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z';
    }
  }

  protected openContactDetail(contactId: string): void {
    this.selectedContactDetailId.set(contactId);
  }

  /**
   * Contact/customer UUID of the active tab, passed down to `cc-plugin-panel-host` as the
   * plugin context (EPIC-28). `originalContactId` is preferred for attended-transfer
   * consultation tabs, matching the convention already used for the disposition panel above.
   */
  protected readonly activePluginContactId = computed<string | null>(() => {
    const tab = this.activeTab();
    if (!tab) return null;
    return tab.originalContactId ?? tab.contactId;
  });

  protected readonly activePluginCustomerId = computed<string | null>(
    () => this.activeTab()?.customerId ?? null,
  );

  protected setActivePluginPanel(installationId: string): void {
    this.activePluginPanelId.set(installationId);
  }

  /**
   * Invokes a toolbar manual action (EPIC-28, FE-100). Same REST contract as
   * `cc-plugin-panel-host`'s INVOKE_MANUAL_ACTION (`POST /api/agent/plugins/{id}/manual-action/{actionId}`),
   * but result goes straight to a toast instead of a postMessage reply to an iframe — duplicating
   * this one HttpClient.post call is simpler than introducing a shared service for two call sites.
   */
  protected invokeToolbarManualAction(item: ToolbarManualAction): void {
    const key = `${item.installationId}:${item.action.actionId}`;
    if (this.pendingManualActionKey() === key) return;

    this.pendingManualActionKey.set(key);
    this.http
      .post<{ success: boolean; message?: string; error?: string }>(
        `/api/agent/plugins/${item.installationId}/manual-action/${item.action.actionId}`,
        {
          contactId: this.activePluginContactId(),
          customerId: this.activePluginCustomerId(),
          payload: {},
        },
      )
      .pipe(
        catchError((err: unknown) => {
          const status =
            err && typeof err === 'object' && 'status' in err
              ? (err as { status: number }).status
              : null;
          const message =
            status === 504
              ? this.transloco.translate('agent.desktop.pluginActionTimeout')
              : this.transloco.translate('agent.desktop.pluginActionError');
          this.notifications.error(message);
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        this.pendingManualActionKey.set(null);
        if (result === null) return;
        if (result.success) {
          this.notifications.success(
            result.message ?? this.transloco.translate('agent.desktop.pluginActionSuccess'),
          );
        } else {
          this.notifications.error(
            result.error ?? this.transloco.translate('agent.desktop.pluginActionError'),
          );
        }
      });
  }

  protected readonly trackByTabId = (_i: number, tab: ContactTab) => tab.id;
  protected readonly trackByQueueId = (_i: number, item: QueueItem) => item.id;
  protected readonly trackByInstallationId = (
    _i: number,
    installation: TenantPluginInstallationDto,
  ) => installation.id;
  protected readonly trackByToolbarAction = (_i: number, item: ToolbarManualAction) =>
    `${item.installationId}:${item.action.actionId}`;

  /**
   * Called when the DispositionPanelComponent emits `saved`.
   * Closes the WRAPPING tab – the agent is now AVAILABLE (status changed by DispositionPanel).
   */
  protected onDispositionSaved(): void {
    const tab = this.tabStore.wrappingTab();
    if (tab) {
      this.tabStore.closeTab(tab.id);
    }
  }

  protected onEmailReplySent(tab: ContactTab, sent: boolean): void {
    this.tabStore.closeTab(tab.id);
    if (sent) {
      this.notifications.success(this.transloco.translate('agent.desktop.replySent'));
    }
  }
}
