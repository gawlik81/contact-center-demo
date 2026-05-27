import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  ViewChild,
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
import {
  WsEvent,
  ContactAssignedPayload,
  CallHangupPayload,
  CallBridgeCompletePayload,
  CallConsultCancelledPayload,
  CallConsultAnsweredPayload,
} from '../../models/ws-event.model';

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
  @ViewChild(AgentCalendarComponent) private calendarRef?: AgentCalendarComponent;

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

  /** Right panel (customer profile) collapse state */
  protected readonly customerPanelCollapsed = signal(false);

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
    //
    // WAŻNE: Filtrujemy po contactId/callId aby nie czyścić sesji konsultacji (RINGING)
    // gdy nadchodzi CALL_HANGUP dla innego połączenia (np. drugiej nogi attended transfer
    // która była odrzucona przez agenta docelowego). Bez tego filtra race condition powoduje,
    // że CALL_HANGUP wysłany do Agent1 (docelowego) czyści jego sesję RINGING zanim
    // zdąży odebrać połączenie konsultacyjne.
    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CALL_HANGUP'),
      )
      .subscribe((e: WsEvent) => {
        const hangup = e.payload as CallHangupPayload;
        const session = this.softphoneService.session();

        // BUG #2 fix: jeśli CALL_HANGUP dotyczy nogi konsultacji (np. busy/unavailable),
        // NIE kończymy głównej sesji agenta – tylko odrzucamy ten event.
        const secondLegId = this.softphoneService.getSecondLegCallId();
        if (secondLegId && hangup.callId === secondLegId) {
          console.warn(
            '[AgentDesktop] CALL_HANGUP dla nogi konsultacji (transfer nieudany) – zachowuję główną sesję:',
            hangup.callId,
          );
          return;
        }

        // Jeśli mamy aktywną sesję, sprawdź czy CALL_HANGUP dotyczy jej.
        // session.contactId może być UUID kontaktu (normalne połączenie) lub CA... SID
        // (druga noga konsultacji — ustawiony przez handleCallTransferConsult).
        // hangup.contactId to UUID z DB (może być null), hangup.callId to Twilio SID.
        // Pasuje jeśli którykolwiek identyfikator się zgadza.
        if (session !== null) {
          const matchesContact = hangup.contactId != null && hangup.contactId === session.contactId;
          const matchesCallId = hangup.callId != null && hangup.callId === session.contactId;
          if (!matchesContact && !matchesCallId) {
            // Ten hangup dotyczy innego połączenia (np. drugiej nogi konsultacji
            // zwróconej przez Twilio przed odebraniem) — ignoruj.
            console.log(
              '[AgentDesktop] CALL_HANGUP ignorowany — nie dotyczy aktywnej sesji:',
              hangup.callId,
              '/ sesja:',
              session.contactId,
            );
            return;
          }
        }

        const wasRinging = session?.state === 'RINGING';
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

    // When attended transfer bridge completes, Agent2's session gets a proper contact UUID.
    // Update session.contactId, customerName (strip "[Konsultacja] " prefix) and the PHONE tab.
    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CALL_BRIDGE_COMPLETE'),
      )
      .subscribe((e: WsEvent) => {
        const payload = e.payload as CallBridgeCompletePayload;

        // Find the PHONE tab with the consultation SID so we can read the customerName.
        const phoneTab = this.tabStore
          .tabs()
          .find(
            (t) =>
              t.type === 'PHONE' &&
              (t.contactId === payload.secondLegCallId || t.originalContactId !== undefined),
          );

        // BUG #1/#3 fix: strip "[Konsultacja] " prefix from customerName and
        // use the tab's queueName (if any) when updating the softphone session.
        const CONSULT_PREFIX = '[Konsultacja] ';
        const rawName =
          phoneTab?.customerName ?? this.softphoneService.session()?.customerName ?? '';
        const cleanName = rawName.startsWith(CONSULT_PREFIX)
          ? rawName.slice(CONSULT_PREFIX.length)
          : rawName;
        // Prefer queueName from the bridge event (copied from original contact by backend).
        // Fall back to current session only when backend doesn't send it (old version).
        const queueName = payload.queueName ?? this.softphoneService.session()?.queueName ?? '';

        // Update softphone session: new contactId + clean customerName + queueName.
        this.softphoneService.updateSessionAfterBridge(payload.newContactId, cleanName, queueName);

        // Update the PHONE tab: new contactId + clean customerName.
        if (phoneTab) {
          this.tabStore.updateTabContactId(phoneTab.id, payload.newContactId, cleanName);
        }
      });

    // Agent1 anulował konsultację (attended transfer) przed wykonaniem bridge.
    // Agent2 powinien wrócić do stanu AVAILABLE bez ekranu dyspozycji (ACW).
    // Backend już ustawia status Agent2 na AVAILABLE – frontend NIE wywołuje API zmiany statusu.
    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CALL_CONSULT_CANCELLED'),
      )
      .subscribe((e: WsEvent) => {
        const payload = e.payload as CallConsultCancelledPayload;
        console.warn('[AgentDesktop] CALL_CONSULT_CANCELLED – anulowanie konsultacji:', payload);

        // Wybierz strategię w zależności od roli agenta w konsultacji:
        //   TRANSFERRING → jesteśmy inicjatorem; cel był niedostępny (busy/no-answer).
        //     Noga konsultacyjna zakończona server-side — NIE rozłączamy urządzenia,
        //     bo agent nadal uczestniczy w konferencji z klientem.
        //   Inne stany → jesteśmy celem konsultacji; inicjator anulował przed bridge.
        //     Brak aktywnego połączenia z klientem — czyścimy sesję bez ACW.
        const currentSession = this.softphoneService.session();
        if (currentSession?.state === 'TRANSFERRING') {
          this.softphoneService.restoreToActiveAfterConsultCancel();
        } else {
          this.softphoneService.cancelConsultSession();
        }

        // Zamknij zakładkę PHONE reprezentującą konsultację.
        // Identyfikujemy ją po contactId = secondLegCallId (CA_...) lub originalContactId.
        const consultTab = this.tabStore
          .tabs()
          .find(
            (t) =>
              t.type === 'PHONE' &&
              (t.contactId === payload.callId ||
                t.contactId === payload.contactId ||
                t.originalContactId !== undefined),
          );

        if (consultTab) {
          this.tabStore.closeTab(consultTab.id);
        } else {
          // Fallback: zamknij dowolną zakładkę PHONE jeśli match nie znaleziony
          const phoneTab = this.tabStore.tabs().find((t) => t.type === 'PHONE');
          if (phoneTab) {
            this.tabStore.closeTab(phoneTab.id);
          }
        }

        // Krótkie powiadomienie dla agenta.
        this.notifications.info(this.transloco.translate('agent.desktop.consultCancelled'));
      });

    // Cel konsultacji odebrał połączenie — odblokuj przycisk "Przekaż" u inicjatora.
    // Emitujemy do SoftphoneService zamiast bezpośrednio do komponentu, żeby nie
    // tworzyć zależności between desktop a softphone.component.
    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CALL_CONSULT_ANSWERED'),
      )
      .subscribe((e: WsEvent) => {
        const payload = e.payload as CallConsultAnsweredPayload;
        console.warn('[AgentDesktop] CALL_CONSULT_ANSWERED – konsultacja odebrana:', payload);
        this.softphoneService.markConsultAnswered();
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
