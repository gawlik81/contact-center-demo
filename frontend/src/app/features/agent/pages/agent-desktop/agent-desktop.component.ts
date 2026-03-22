import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, LowerCasePipe } from '@angular/common';
import { filter } from 'rxjs';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { ContactTabStore } from '../../services/contact-tab.store';
import { NotificationService } from '../../../../core/services/notification.service';
import { SoftphoneService } from '../../services/softphone.service';
import { CustomerLookupService } from '../../services/customer-lookup.service';
import { SoftphoneComponent } from '../../components/softphone/softphone.component';
import { CustomerPanelComponent } from '../../components/customer-panel/customer-panel.component';
import { DispositionPanelComponent } from '../../components/disposition-panel/disposition-panel.component';
import {
  AgentStatus,
  ALL_AGENT_STATUSES,
  AGENT_STATUS_CONFIG,
} from '../../models/agent-status.model';
import { ContactTab } from '../../models/contact-tab.model';
import { QueueItem } from '../../models/queue-item.model';
import {
  WsEvent,
  CallIncomingPayload,
  ContactAssignedPayload,
  QueueUpdatePayload,
} from '../../models/ws-event.model';

@Component({
  selector: 'app-agent-desktop',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    LowerCasePipe,
    SoftphoneComponent,
    CustomerPanelComponent,
    DispositionPanelComponent,
  ],
  templateUrl: './agent-desktop.component.html',
  styleUrl: './agent-desktop.component.scss',
})
export class AgentDesktopComponent implements OnInit, OnDestroy {
  private readonly ws = inject(WebSocketService);
  protected readonly statusService = inject(AgentStatusService);
  protected readonly tabStore = inject(ContactTabStore);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly softphoneService = inject(SoftphoneService);
  private readonly lookupService = inject(CustomerLookupService);

  protected readonly statusConfig = AGENT_STATUS_CONFIG;
  protected readonly allStatuses = ALL_AGENT_STATUSES;

  protected readonly queueItems = signal<QueueItem[]>([]);
  protected readonly statusMenuOpen = signal(false);

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
   * Used to drive the CustomerPanelComponent.
   * Only PHONE tabs carry a meaningful CLI – for CHAT/EMAIL we pass the customerIdentifier,
   * which may be an email address; the lookup service will return null gracefully.
   */
  protected readonly activeCli = computed<string>(() => {
    const tab = this.activeTab();
    if (!tab) return '';
    return tab.type === 'PHONE' ? tab.customerIdentifier : '';
  });

  /** Active tab in ACW/WRAPPING state – drives DispositionPanelComponent visibility */
  protected readonly wrappingTab = this.tabStore.wrappingTab;

  protected readonly tabLimitMessage = signal<string | null>(null);

  /**
   * Monitors SoftphoneService session state.
   * When a call transitions to ENDED, puts the active PHONE tab into WRAPPING state
   * so the disposition panel appears automatically.
   */
  private readonly softphoneEndedEffect = effect(() => {
    const session = this.softphoneService.session();
    if (session?.state === 'ENDED') {
      const phoneTab = this.tabStore
        .tabs()
        .find((t) => t.type === 'PHONE' && t.status !== 'WRAPPING');
      if (phoneTab) {
        this.tabStore.markAsWrapping(phoneTab.id);
      }
    }
  });

  ngOnInit(): void {
    this.ws.connect();
    this.statusService.initStatus();

    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CALL_INCOMING'),
      )
      .subscribe((e) => {
        const payload = e.payload as CallIncomingPayload;
        this.lookupService.evict(payload.customerPhone);
        const reason = this.tabStore.openFromCallIncoming(payload);
        if (reason !== null) {
          this.showLimitMessage(reason);
        } else {
          this.softphoneService.incomingCall(payload);
          this.notifications.info(
            `Przychodzace polaczenie od ${payload.customerName} (${payload.customerPhone})`,
          );
        }
      });

    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CONTACT_ASSIGNED'),
      )
      .subscribe((e) => {
        const payload = e.payload as ContactAssignedPayload;
        const reason = this.tabStore.openFromContactAssigned(payload);
        if (reason !== null) {
          this.showLimitMessage(reason);
        } else {
          this.notifications.info(`Nowy kontakt przydzielony: ${payload.customerName}`);
        }
      });

    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'QUEUE_UPDATE'),
      )
      .subscribe((e) => {
        const payload = e.payload as QueueUpdatePayload;
        this.queueItems.set(payload.items ?? []);
      });
  }

  ngOnDestroy(): void {
    this.ws.disconnect();
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
    const now = new Date();
    const diffMs = now.getTime() - new Date(waitingSince).getTime();
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
    }
  }

  protected getTabTypeLabel(tab: ContactTab): string {
    switch (tab.type) {
      case 'PHONE':
        return 'Telefon';
      case 'CHAT':
        return 'Chat';
      case 'EMAIL':
        return 'Email';
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
    }
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

  private showLimitMessage(reason: 'MAX_PHONE' | 'MAX_ASYNC' | 'MAX_TOTAL'): void {
    const messages: Record<string, string> = {
      MAX_PHONE: 'Możesz obsługiwać tylko 1 połączenie telefoniczne jednocześnie.',
      MAX_ASYNC: 'Osiągnąłeś limit 3 kontaktów chat/email jednocześnie.',
      MAX_TOTAL: 'Osiągnąłeś maksymalny limit 4 aktywnych kontaktów.',
    };
    this.tabLimitMessage.set(messages[reason] ?? 'Osiągnąłeś limit aktywnych kontaktów.');
    setTimeout(() => this.tabLimitMessage.set(null), 5_000);
  }
}
