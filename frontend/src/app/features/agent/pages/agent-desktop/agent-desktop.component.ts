import { LowerCasePipe } from '@angular/common';
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
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { HttpClient } from '@angular/common/http';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { catchError, of } from 'rxjs';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { AuthService } from '../../../../core/services/auth.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { ContactTabStore } from '../../services/contact-tab.store';
import { ContactDraftStore } from '../../services/contact-draft.store';
import { NotificationService } from '../../../../core/services/notification.service';
import { SoftphoneService } from '../../services/softphone.service';
import { AgentHotkeysService } from '../../services/agent-hotkeys.service';
import { AGENT_SHORTCUTS } from '../../models/agent-shortcuts.model';
import { isEditableElement } from '../../../../shared/utils/focus.util';
import { ConfirmDialogComponent } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';
import { ContactService } from '../../services/contact.service';
import { TabMarker, resolveTabMarker, sessionMatchesTab } from '../../models/contact-tab-marker';
import { CallLifecycleService } from '../../services/call-lifecycle.service';
import { DispositionSaveService } from '../../services/disposition-save.service';
import { CallState } from '../../models/call-session.model';
import { AcwBannerComponent } from '../../components/acw-banner/acw-banner.component';
import { SoftphoneComponent } from '../../components/softphone/softphone.component';
import { CustomerPanelComponent } from '../../components/customer-panel/customer-panel.component';
import { DispositionPanelComponent } from '../../components/disposition-panel/disposition-panel.component';
import { EmailContactComponent } from './email-contact/email-contact.component';
import { SocialContactComponent } from './social-contact/social-contact.component';
import { ContactHeaderComponent } from '../../components/contact-header/contact-header.component';
import { NoContactsComponent } from '../../components/no-contacts/no-contacts.component';
import { WsBannerComponent } from '../../components/ws-banner/ws-banner.component';
import { QueuePanelComponent } from '../../components/queue-panel/queue-panel.component';
import { ManualCampaignPanelComponent } from '../../components/manual-campaign-panel/manual-campaign-panel.component';
import { AgentCalendarComponent } from './agent-calendar/agent-calendar.component';
import { AddBreakModalComponent } from '../../components/add-break-modal/add-break-modal.component';
import { ContactDetailModalComponent } from '../../../../shared/components/contact-detail-modal/contact-detail-modal.component';
import { PluginPanelHostComponent } from '../../../../shared/components/plugin-panel-host/plugin-panel-host.component';
import { PluginAdminService } from '../../../plugins/services/plugin-admin.service';
import { ManualActionDef, TenantPluginInstallationDto } from '../../../plugins/models/plugin.model';
import { ContactTab, acwDraftKey } from '../../models/contact-tab.model';

/**
 * What the close button / Delete does for a tab:
 * - HANGUP: live call (active / held / transferring) -> confirm, hang up, the lifecycle moves
 *   the tab to ACW (the disposition is closed by saving, never by removing the tab)
 * - REJECT: ringing call -> confirm, reject
 * - RETURN: EMAIL/SOCIAL/CHAT -> release back to the queue (confirm only with an unsent draft)
 * - STALE: PHONE tab without a live call -> just remove it
 * - null: not closable (ACW)
 */
type CloseKind = 'HANGUP' | 'REJECT' | 'RETURN' | 'STALE';

/** Pending "close tab" confirmation. */
interface PendingTabClose {
  tab: ContactTab;
  kind: Exclude<CloseKind, 'STALE'>;
}

/** Manual action bound to its owning installation – flattened for toolbar rendering. */
interface ToolbarManualAction {
  installationId: string;
  action: ManualActionDef;
}

@Component({
  selector: 'app-agent-desktop',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LowerCasePipe,
    TranslocoModule,
    SoftphoneComponent,
    CustomerPanelComponent,
    DispositionPanelComponent,
    EmailContactComponent,
    SocialContactComponent,
    ManualCampaignPanelComponent,
    QueuePanelComponent,
    ContactHeaderComponent,
    NoContactsComponent,
    WsBannerComponent,
    AgentCalendarComponent,
    AddBreakModalComponent,
    ContactDetailModalComponent,
    PluginPanelHostComponent,
    ConfirmDialogComponent,
    AcwBannerComponent,
  ],
  templateUrl: './agent-desktop.component.html',
  styleUrl: './agent-desktop.component.scss',
})
export class AgentDesktopComponent implements OnInit {
  @ViewChild(AgentCalendarComponent) private calendarRef?: AgentCalendarComponent;
  private readonly softphoneRef = viewChild(SoftphoneComponent);
  private readonly tabButtons = viewChildren<ElementRef<HTMLButtonElement>>('tabBtn');
  private readonly mainRegion = viewChild<ElementRef<HTMLElement>>('mainRegion');

  private readonly ws = inject(WebSocketService);
  protected readonly statusService = inject(AgentStatusService);
  protected readonly tabStore = inject(ContactTabStore);
  private readonly drafts = inject(ContactDraftStore);
  private readonly contactService = inject(ContactService);
  private readonly calls = inject(CallLifecycleService);
  private readonly dispositionSave = inject(DispositionSaveService);

  /** PHONE note typed during the call; pre-fills the disposition form. */
  protected phoneNoteOf(tab: ContactTab): string {
    return this.drafts.phoneNote(acwDraftKey(tab));
  }

  protected draftKeyOf(tab: ContactTab): string {
    return acwDraftKey(tab);
  }
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly softphoneService = inject(SoftphoneService);
  private readonly transloco = inject(TranslocoService);
  protected readonly authService = inject(AuthService);
  private readonly pluginAdminService = inject(PluginAdminService);
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  protected readonly hotkeys = inject(AgentHotkeysService);
  protected readonly shortcuts = AGENT_SHORTCUTS;

  protected readonly connectionState = this.ws.connectionState;
  protected readonly isOffline = computed(
    () => this.connectionState() === 'DISCONNECTED' || this.connectionState() === 'ERROR',
  );
  protected readonly isReconnecting = computed(() => this.connectionState() === 'CONNECTING');

  protected readonly tabs = this.tabStore.tabs;
  protected readonly activeTab = this.tabStore.activeTab;

  /**
   * One state marker per tab (FE-133). Hold / in-call come from the softphone session (the single
   * source of truth for the call state), the draft from ContactDraftStore, ACW from the tab.
   */
  protected readonly tabMarkers = computed(() => {
    const session = this.softphoneService.session();
    const markers = new Map<string, TabMarker | null>();
    for (const tab of this.tabs()) {
      markers.set(
        tab.id,
        resolveTabMarker(tab, session, this.drafts.hasUnsentDraft(tab.contactId)),
      );
    }
    return markers;
  });

  /** Active language as a signal: template labels built with translate() re-render on change. */
  private readonly activeLang = toSignal(this.transloco.langChanges$, {
    initialValue: this.transloco.getActiveLang(),
  });

  /** `aria-owns` of the tablist: the tabs are owned by id, the close buttons are not tab content. */
  protected readonly tabDomIds = computed(() =>
    this.tabs()
      .map((t) => 'tab-' + t.id)
      .join(' '),
  );

  /**
   * Polite live-region text: a contact that arrived without taking the active tab, or why a tab
   * cannot be closed. Cleared first, then set after render, so a repeated text is spoken again.
   */
  protected readonly politeAnnouncement = signal('');

  /** Tab being closed: a confirmation dialog is open for it. */
  protected readonly pendingClose = signal<PendingTabClose | null>(null);

  /** Tabs whose contact is being returned to the queue (API call in flight). */
  protected readonly returningTabIds = signal<ReadonlySet<string>>(new Set());

  /** Element that had focus when the confirmation opened (or the trigger when it had none). */
  private closeFocusTarget: HTMLElement | null = null;
  private destroyed = false;
  /** Tabs already on the desktop at creation are not "new" (the store outlives the component). */
  private knownTabIds = new Set(this.tabStore.tabs().map((t) => t.id));

  constructor() {
    this.destroyRef.onDestroy(() => (this.destroyed = true));
    // The ACW tab is closed by DispositionSaveService (it outlives this page); focus lost with
    // the form goes to the neighbour tab, exactly like after any other tab removal.
    this.dispositionSave.closed$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((e) => {
      const neighbour = this.tabs().find((t) => t.id === e.neighbourTabId) ?? null;
      this.focusAfterRemoval(neighbour);
    });
  }

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
  protected readonly calendarTabActive = this.tabStore.calendarOpen;

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

  /**
   * A contact that arrives while another tab is active does not take over (FE-133): announce it
   * politely instead. PHONE tabs are excluded: ringing has its own assertive announcement.
   * Focus is never moved here.
   */
  private readonly newContactEffect = effect(() => {
    const tabs = this.tabs();
    untracked(() => {
      const fresh = tabs.filter((t) => !this.knownTabIds.has(t.id));
      this.knownTabIds = new Set(tabs.map((t) => t.id));
      const announced = fresh.filter((t) => t.type !== 'PHONE' && !t.isActive);
      if (announced.length === 0) return;
      this.announce(
        announced
          .map((t) =>
            this.transloco.translate('agent.desktop.newContactAnnouncement', {
              who: t.customerName,
              type: this.getTabTypeLabel(t),
            }),
          )
          .join('. '),
      );
    });
  });

  /**
   * A pending close confirmation must not outlive its premise: when the tab vanishes, enters ACW,
   * or the call state it was asked about changes, the dialog is dismissed.
   */
  private readonly pendingGuardEffect = effect(() => {
    const pending = this.pendingClose();
    if (!pending) return;
    const tab = this.tabs().find((t) => t.id === pending.tab.id);
    const state = this.callStateOf(tab);
    if (!tab || !this.isCloseStillValid(pending.kind, tab, state)) {
      untracked(() => this.dismissPending());
    }
  });

  private announce(text: string): void {
    this.politeAnnouncement.set('');
    afterNextRender(() => this.politeAnnouncement.set(text), { injector: this.injector });
  }

  ngOnInit(): void {
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

  /** WAI-ARIA tabs: roving tabindex, arrows wrap, Home/End jump; selection follows focus. */
  protected onTabKeydown(event: KeyboardEvent, current: ContactTab): void {
    // Only react to keys pressed on the tab itself (never inside an editor of the panel).
    if (event.target !== event.currentTarget) return;
    if (event.key === 'Delete' && !event.ctrlKey && !event.altKey && !event.metaKey) {
      event.preventDefault();
      // A held key must never cascade through the neighbouring tabs.
      if (!event.repeat) this.requestClose(current, event.currentTarget as HTMLElement);
      return;
    }
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
    // Focus moves synchronously: a key pressed right after this one (e.g. Delete) must reach the
    // tab that is now selected, not the previous one.
    this.tabButtons()
      .find((b) => b.nativeElement.id === 'tab-' + tab.id)
      ?.nativeElement.focus();
  }

  /** Roving tabindex: the active tab, or the first one when none is active, is the tab stop. */
  protected isTabStop(tab: ContactTab): boolean {
    const stopId = this.activeTab()?.id ?? this.tabs()[0]?.id;
    return stopId === tab.id;
  }

  protected setActiveTab(tab: ContactTab): void {
    // Activating the ACW tab on purpose (click / Enter / Space on it) is an explicit request to
    // fill in the disposition: the form takes focus. Arrow-key browsing never gets here.
    if (tab.type === 'PHONE' && tab.status === 'WRAPPING' && !tab.isActive) {
      this.tabStore.requestAcwFocus(tab.id);
    }
    this.tabStore.setActiveTab(tab.id);
  }

  /** Marker of a tab (null = none). */
  protected markerOf(tab: ContactTab): TabMarker | null {
    return this.tabMarkers().get(tab.id) ?? null;
  }

  protected markerLabel(marker: TabMarker): string {
    this.activeLang();
    return this.transloco.translate('agent.desktop.tabMarker.' + marker);
  }

  /** Accessible name of a tab: full contact name, channel and (when present) its state. */
  protected tabAriaLabel(tab: ContactTab): string {
    this.activeLang();
    const marker = this.markerOf(tab);
    const parts = [tab.customerName, this.getTabTypeLabel(tab)];
    if (marker) parts.push(this.transloco.translate('agent.desktop.tabMarkerSr.' + marker));
    return parts.join(', ');
  }

  /** Softphone call state of this PHONE tab, null when it has no call. */
  private callStateOf(tab: ContactTab | undefined): CallState | null {
    if (!tab) return null;
    const session = this.softphoneService.session();
    return sessionMatchesTab(session, tab) ? session!.state : null;
  }

  private closeKindOf(tab: ContactTab): CloseKind | null {
    if (tab.type !== 'PHONE') return 'RETURN';
    if (tab.status === 'WRAPPING') return null;
    switch (this.callStateOf(tab)) {
      case 'RINGING':
        return 'REJECT';
      case 'ACTIVE':
      case 'ON_HOLD':
      case 'TRANSFERRING':
        return 'HANGUP';
      default:
        return 'STALE';
    }
  }

  private isCloseStillValid(
    kind: PendingTabClose['kind'],
    tab: ContactTab,
    state: CallState | null,
  ): boolean {
    switch (kind) {
      case 'RETURN':
        return !this.returningTabIds().has(tab.id);
      case 'REJECT':
        return tab.status !== 'WRAPPING' && state === 'RINGING';
      case 'HANGUP':
        return (
          tab.status !== 'WRAPPING' &&
          (state === 'ACTIVE' || state === 'ON_HOLD' || state === 'TRANSFERRING')
        );
    }
  }

  /** A WRAPPING (ACW) call tab is closed by saving the disposition, never by the close button. */
  protected isCloseDisabled(tab: ContactTab): boolean {
    return (
      (tab.type === 'PHONE' && tab.status === 'WRAPPING') || this.returningTabIds().has(tab.id)
    );
  }

  protected isReturning(tab: ContactTab | null): boolean {
    return tab !== null && this.returningTabIds().has(tab.id);
  }

  protected closeLabel(tab: ContactTab): string {
    this.activeLang();
    let key: string;
    switch (this.closeKindOf(tab)) {
      case null:
        key = 'closeWrapping';
        break;
      case 'REJECT':
        key = 'closeReject';
        break;
      case 'HANGUP':
        key = 'closePhone';
        break;
      case 'STALE':
        key = 'closeStale';
        break;
      default:
        key = 'closeAsync';
    }
    return this.transloco.translate('agent.desktop.tabClose.' + key, { name: tab.customerName });
  }

  protected closeDisabledReason(tab: ContactTab): string | null {
    this.activeLang();
    if (this.returningTabIds().has(tab.id)) {
      return this.transloco.translate('agent.desktop.tabClose.busy');
    }
    if (tab.type === 'PHONE' && tab.status === 'WRAPPING') {
      return this.transloco.translate('agent.desktop.tabClose.wrapping');
    }
    return null;
  }

  protected closeConfirmMessage(pending: PendingTabClose): string {
    this.activeLang();
    const key = { HANGUP: 'confirmHangup', REJECT: 'confirmReject', RETURN: 'confirmReturn' }[
      pending.kind
    ];
    return this.transloco.translate('agent.desktop.tabClose.' + key, {
      name: pending.tab.customerName,
    });
  }

  protected closeConfirmLabel(pending: PendingTabClose): string {
    this.activeLang();
    const key = {
      HANGUP: 'confirmHangupAction',
      REJECT: 'confirmRejectAction',
      RETURN: 'confirmReturnAction',
    }[pending.kind];
    return this.transloco.translate('agent.desktop.tabClose.' + key);
  }

  /**
   * Single entry point of the close button and the Delete key (`trigger` = the element the agent
   * used; it gets focus back when the confirmation is cancelled).
   * - PHONE live call: confirm, hang up; the tab then goes to ACW instead of disappearing.
   * - PHONE ringing: confirm, reject. PHONE without a call: the stale tab is just removed.
   * - EMAIL/SOCIAL/CHAT: release to the queue; confirm only when an unsent draft exists.
   * - PHONE in ACW: nothing; the reason is announced politely.
   */
  protected requestClose(tab: ContactTab, trigger?: HTMLElement): void {
    if (this.pendingClose() || this.returningTabIds().has(tab.id)) return;

    const kind = this.closeKindOf(tab);
    if (kind === null) {
      this.announce(this.transloco.translate('agent.desktop.tabClose.wrapping'));
      return;
    }
    if (kind === 'STALE') {
      this.removeTabKeepingFocus(tab);
    } else if (kind === 'RETURN' && !this.drafts.hasUnsentDraft(tab.contactId)) {
      this.returnToQueue(tab);
    } else {
      // Dialog opening moves focus away: remember where it was (a mouse click on the close
      // button does not focus it in Safari / Firefox on macOS, so fall back to the trigger).
      const active = document.activeElement as HTMLElement | null;
      this.closeFocusTarget = active && active !== document.body ? active : (trigger ?? null);
      this.pendingClose.set({ tab, kind });
    }
  }

  protected cancelClose(): void {
    this.pendingClose.set(null);
    this.restoreFocusIfLost();
  }

  private dismissPending(): void {
    this.pendingClose.set(null);
    this.restoreFocusIfLost();
  }

  /** Gives focus back to the remembered element, but only when focus is currently lost. */
  private restoreFocusIfLost(): void {
    const target = this.closeFocusTarget;
    this.closeFocusTarget = null;
    if (!target) return;
    afterNextRender(
      () => {
        const active = document.activeElement;
        if ((!active || active === document.body) && target.isConnected) target.focus();
      },
      { injector: this.injector },
    );
  }

  protected confirmClose(): void {
    const pending = this.pendingClose();
    this.pendingClose.set(null);
    this.closeFocusTarget = null;
    if (!pending) return;
    // Re-validate: the call may have ended, been answered or entered ACW while the dialog was open.
    const tab = this.tabs().find((t) => t.id === pending.tab.id);
    if (!tab || !this.isCloseStillValid(pending.kind, tab, this.callStateOf(tab))) return;

    if (pending.kind === 'HANGUP') {
      // CallLifecycleService reacts to ENDED: the tab goes to WRAPPING and shows the disposition.
      this.softphoneService.hangupCall();
    } else if (pending.kind === 'REJECT') {
      const neighbour = this.neighbourOf(tab);
      this.calls.rejectRingingCall();
      this.focusAfterRemoval(neighbour);
    } else {
      this.returnToQueue(tab);
    }
  }

  /**
   * Returns an EMAIL/SOCIAL/CHAT contact via `POST /contacts/{id}/release`. The tab (and its
   * draft) is removed only after the API succeeded. 404/409 mean the contact is no longer this
   * agent's (reassigned, closed): retrying can never succeed, so the tab is removed with an
   * explanation instead of becoming a zombie. Any other error keeps tab and draft.
   * The request is deliberately not tied to the component lifetime: leaving the desktop must not
   * leave a half-returned contact.
   */
  private returnToQueue(tab: ContactTab): void {
    this.returningTabIds.update((ids) => new Set(ids).add(tab.id));
    this.tabStore.beginRelease(tab.contactId);
    this.contactService.releaseContact(tab.contactId).subscribe({
      next: () => {
        this.finishReturning(tab.id);
        const reassigned = this.tabStore.endRelease(tab.contactId);
        this.removeTabKeepingFocus(tab);
        if (reassigned) {
          // The backend handed the same contact back before the response arrived: re-open it
          // (inactive, announced as a new contact) instead of leaving it assigned without a tab.
          this.tabStore.openFromContactAssigned(reassigned);
        } else {
          this.notifications.success(
            this.transloco.translate('agent.desktop.tabClose.returned', {
              name: tab.customerName,
            }),
          );
        }
      },
      error: (err: unknown) => {
        this.finishReturning(tab.id);
        const reassigned = this.tabStore.endRelease(tab.contactId);
        const status =
          err && typeof err === 'object' && 'status' in err
            ? (err as { status: number }).status
            : null;
        if ((status === 404 || status === 409) && !reassigned) {
          this.removeTabKeepingFocus(tab);
          this.notifications.info(
            this.transloco.translate('agent.desktop.tabClose.notYours', { name: tab.customerName }),
          );
        } else if (!reassigned) {
          this.notifications.error(
            this.transloco.translate('agent.desktop.tabClose.returnError', {
              name: tab.customerName,
            }),
          );
        }
        // reassigned + error: the contact is the agent's again, the tab simply stays.
      },
    });
  }

  private finishReturning(tabId: string): void {
    this.returningTabIds.update((ids) => {
      const next = new Set(ids);
      next.delete(tabId);
      return next;
    });
  }

  /** Tab that takes over after `tab` is removed (the next one, else the previous). */
  private neighbourOf(tab: ContactTab): ContactTab | null {
    const tabs = this.tabs();
    const index = tabs.findIndex((t) => t.id === tab.id);
    return tabs[index + 1] ?? tabs[index - 1] ?? null;
  }

  /** Closes through the store (the single draft cleanup point), then repairs lost focus. */
  private removeTabKeepingFocus(tab: ContactTab): void {
    const neighbour = this.neighbourOf(tab);
    this.tabStore.closeTab(tab.id);
    this.focusAfterRemoval(neighbour);
  }

  /**
   * Moves focus to the neighbouring tab (or the desktop region) ONLY when it was lost with the
   * removed tab (it falls back to <body>). An agent who already moved on, e.g. typing in another
   * tab's editor while the API call was in flight, keeps focus where it is.
   */
  private focusAfterRemoval(neighbour: ContactTab | null): void {
    if (this.destroyed) return; // left the desktop while the request was in flight
    afterNextRender(
      () => {
        const active = document.activeElement;
        if (active && active !== document.body) return;
        const target = neighbour
          ? this.tabButtons().find((b) => b.nativeElement.id === 'tab-' + neighbour.id)
              ?.nativeElement
          : this.mainRegion()?.nativeElement;
        (target ?? this.mainRegion()?.nativeElement)?.focus();
      },
      { injector: this.injector },
    );
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
    this.activeLang();
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
  protected readonly trackByInstallationId = (
    _i: number,
    installation: TenantPluginInstallationDto,
  ) => installation.id;
  protected readonly trackByToolbarAction = (_i: number, item: ToolbarManualAction) =>
    `${item.installationId}:${item.action.actionId}`;

  protected onEmailReplySent(tab: ContactTab, sent: boolean): void {
    this.tabStore.closeTab(tab.id);
    if (sent) {
      this.notifications.success(this.transloco.translate('agent.desktop.replySent'));
    }
  }
}
