import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, LowerCasePipe } from '@angular/common';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { filter, interval } from 'rxjs';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { ContactTabStore } from '../../services/contact-tab.store';
import { NotificationService } from '../../../../core/services/notification.service';
import { SoftphoneService } from '../../services/softphone.service';
import { IncomingCallAlertService } from '../../services/incoming-call-alert.service';
import { QueueStateService } from '../../services/queue-state.service';
import { SoftphoneComponent } from '../../components/softphone/softphone.component';
import { CustomerPanelComponent } from '../../components/customer-panel/customer-panel.component';
import { DispositionPanelComponent } from '../../components/disposition-panel/disposition-panel.component';
import { EmailContactComponent } from './email-contact/email-contact.component';
import { SocialContactComponent } from './social-contact/social-contact.component';
import { ManualCampaignPanelComponent } from '../../components/manual-campaign-panel/manual-campaign-panel.component';
import { AgentCalendarComponent } from './agent-calendar/agent-calendar.component';
import { AddBreakModalComponent } from '../../components/add-break-modal/add-break-modal.component';
import { ContactDetailModalComponent } from '../../../../shared/components/contact-detail-modal/contact-detail-modal.component';
import {
  AgentStatus,
  ALL_AGENT_STATUSES,
  AGENT_STATUS_CONFIG,
} from '../../models/agent-status.model';
import { ContactTab } from '../../models/contact-tab.model';
import { QueueItem } from '../../models/queue-item.model';
import { WsEvent, ContactAssignedPayload } from '../../models/ws-event.model';

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
    ContactDetailModalComponent,
  ],
  templateUrl: './agent-desktop.component.html',
  styleUrl: './agent-desktop.component.scss',
})
export class AgentDesktopComponent implements OnInit {
  private readonly ws = inject(WebSocketService);
  protected readonly statusService = inject(AgentStatusService);
  protected readonly tabStore = inject(ContactTabStore);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly softphoneService = inject(SoftphoneService);
  private readonly incomingCallAlert = inject(IncomingCallAlertService);
  private readonly transloco = inject(TranslocoService);
  private readonly queueStateService = inject(QueueStateService);

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

  protected readonly tabLimitMessage = signal<string | null>(null);

  /** Calendar tab visibility */
  protected readonly calendarTabActive = signal(false);

  /** Add-break modal visibility */
  protected readonly addBreakOpen = signal(false);

  /** Contact ID to display in contact-detail-modal (opened from CustomerPanel history). */
  protected readonly selectedContactDetailId = signal<string | null>(null);

  /**
   * Derived signal that emits only the session state string (or null).
   * Changes value only when the actual call state transitions (RINGING → ACTIVE → ENDED etc.),
   * NOT when the duration counter increments every second. This prevents effects below
   * from firing spuriously on every timer tick.
   */
  private readonly sessionState = computed(() => this.softphoneService.session()?.state ?? null);

  /**
   * Monitors SoftphoneService session state.
   * When a call transitions to ENDED, puts the active PHONE tab into WRAPPING state
   * so the disposition panel appears automatically, and sets agent status to AFTER_CONTACT.
   * Uses sessionState (not session()) to avoid re-triggering on every duration tick.
   * tabStore.tabs() is read inside untracked() to prevent tab closure from re-triggering.
   */
  private readonly softphoneEndedEffect = effect(() => {
    const state = this.sessionState();
    if (state === 'ENDED') {
      untracked(() => {
        // Status change always happens immediately
        this.statusService
          .changeStatus('AFTER_CONTACT')
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe();

        // Wrapping is deferred if callback modal is open — SoftphoneComponent
        // will call markAsWrapping() itself when the modal is closed.
        if (!this.softphoneService.callbackModalOpen()) {
          const phoneTab = this.tabStore
            .tabs()
            .find((t) => t.type === 'PHONE' && t.status !== 'WRAPPING');
          if (phoneTab) {
            this.tabStore.markAsWrapping(phoneTab.id);
          }
        }
      });
    }
  });

  /**
   * Dismisses the incoming call banner when the agent accepts the call
   * and the session transitions to ACTIVE state.
   */
  private readonly incomingCallDismissEffect = effect(() => {
    const state = this.sessionState();
    if (state === 'ACTIVE') {
      untracked(() => this.incomingCallAlert.dismissAlert());
    }
  });

  /**
   * When a call transitions to ACTIVE (agent answers), sets the agent status to BUSY
   * so the backend dialer skips this agent during poll for available agents.
   */
  private readonly softphoneActiveEffect = effect(() => {
    const state = this.sessionState();
    if (state === 'ACTIVE') {
      untracked(() => {
        this.statusService
          .changeStatus('BUSY')
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe();
      });
    }
  });

  ngOnInit(): void {
    this.statusService.initStatus();

    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.now.set(Date.now()));

    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CONTACT_ASSIGNED'),
      )
      .subscribe((e) => {
        const payload = e.payload as ContactAssignedPayload;
        if (payload.type === 'PHONE') return; // handled by IncomingCallAlertService
        const reason = this.tabStore.openFromContactAssigned(payload);
        if (reason !== null) {
          this.showLimitMessage(reason);
        } else {
          this.notifications.info(payload.customerName);
        }
      });

    // Gdy klient rozłączy połączenie wychodzące (lub przychodzące) po stronie Twilio,
    // backend wysyła CALL_HANGUP przez WebSocket. Przekazujemy to do softphoneService
    // aby softphone przeszedł w stan ENDED i uruchomił panel dyspozycji (ACW).
    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CALL_HANGUP'),
      )
      .subscribe(() => {
        const wasRinging = this.softphoneService.session()?.state === 'RINGING';
        this.softphoneService.remoteHangup();
        if (wasRinging) {
          // remoteHangup() sets session to null immediately for RINGING state (no ACW needed),
          // so softphoneEndedEffect never fires — close the PHONE tab manually here.
          const phoneTab = this.tabStore.tabs().find((t) => t.type === 'PHONE');
          if (phoneTab) {
            this.tabStore.closeTab(phoneTab.id);
          }
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

  protected changeStatus(status: AgentStatus): void {
    this.statusMenuOpen.set(false);
    this.statusService.changeStatus(status).pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }

  protected toggleStatusMenu(): void {
    this.statusMenuOpen.update((v) => !v);
  }

  protected closeStatusMenu(): void {
    this.statusMenuOpen.set(false);
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

  protected readonly trackByTabId = (_i: number, tab: ContactTab) => tab.id;
  protected readonly trackByQueueId = (_i: number, item: QueueItem) => item.id;

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

  private showLimitMessage(reason: 'MAX_PHONE' | 'MAX_ASYNC' | 'MAX_TOTAL'): void {
    const messages: Record<string, string> = {
      MAX_PHONE: this.transloco.translate('agent.desktop.tabLimitPhone'),
      MAX_ASYNC: this.transloco.translate('agent.desktop.tabLimitAsync'),
      MAX_TOTAL: this.transloco.translate('agent.desktop.tabLimitTotal'),
    };
    this.tabLimitMessage.set(
      messages[reason] ?? this.transloco.translate('agent.desktop.tabLimit'),
    );
    setTimeout(() => this.tabLimitMessage.set(null), 5_000);
  }
}
