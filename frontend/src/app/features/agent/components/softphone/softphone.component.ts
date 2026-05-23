import {
  ChangeDetectionStrategy,
  Component,
  Input,
  OnInit,
  OnDestroy,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';
import { SoftphoneService } from '../../services/softphone.service';
import { ContactTab } from '../../models/contact-tab.model';
import { CallSession, TransferMode, TransferTargetType } from '../../models/call-session.model';
import { ScheduleInboundCallbackModalComponent } from '../schedule-inbound-callback-modal/schedule-inbound-callback-modal.component';
import { ScheduledCallbackDto } from '../../models/callback.model';
import { ContactTabStore } from '../../services/contact-tab.store';
import { TransferAgentListComponent } from '../transfer-agent-list/transfer-agent-list.component';
import { TransferQueueListComponent } from '../transfer-queue-list/transfer-queue-list.component';

@Component({
  selector: 'app-softphone',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TranslocoModule,
    ScheduleInboundCallbackModalComponent,
    TransferAgentListComponent,
    TransferQueueListComponent,
  ],
  templateUrl: './softphone.component.html',
  styleUrl: './softphone.component.scss',
})
export class SoftphoneComponent implements OnInit, OnDestroy {
  @Input({ required: true }) tab!: ContactTab;

  protected readonly softphone = inject(SoftphoneService);
  private readonly tabStore = inject(ContactTabStore);

  protected readonly session = this.softphone.session;

  protected readonly transferMode = signal<TransferMode>('BLIND');
  protected readonly transferTarget = signal<string>('');
  protected readonly attendedConnected = signal<boolean>(false);
  protected readonly transferTargetType = signal<TransferTargetType>('PHONE');
  /** True while an HTTP transfer request is in flight — disables transfer buttons */
  protected readonly isTransferring = signal<boolean>(false);

  protected readonly transferTargetTabs = computed<TransferTargetType[]>(() => {
    // Queue transfer only makes sense for calls that arrived through a queue.
    // Outbound calls have no queueName; also guard against direction being temporarily wrong
    // when CONTACT_ASSIGNED creates the tab before CALL_OUTBOUND corrects it.
    const s = this.session();
    const hasQueue = s != null && s.queueName != null && s.queueName.length > 0;
    return hasQueue ? ['PHONE', 'AGENT', 'QUEUE'] : ['PHONE', 'AGENT'];
  });

  protected readonly transferTargetValid = computed(
    () => this.transferTarget().replace(/[\s+]/g, '').length >= 3,
  );

  protected readonly formattedDuration = computed(() => {
    const s = this.session();
    if (!s) return '00:00';
    return this.formatSeconds(s.duration);
  });

  protected readonly formattedHoldDuration = computed(() => {
    const s = this.session();
    if (!s || !s.holdStartedAt) return '00:00';
    const elapsed = Math.floor((new Date().getTime() - s.holdStartedAt.getTime()) / 1000);
    return this.formatSeconds(elapsed);
  });

  protected readonly customerInitial = computed(() => {
    const s = this.session();
    return s ? s.customerName.charAt(0).toUpperCase() : '?';
  });

  private holdTickInterval: ReturnType<typeof setInterval> | null = null;
  private consultAnsweredSub: Subscription | null = null;

  ngOnInit(): void {
    // Force re-render of hold timer tick
    this.holdTickInterval = setInterval(() => {
      // Reading the signal will not re-render by itself when ON_HOLD;
      // we need a small auxiliary signal to trigger CD.
      this._holdTick.update((v) => v + 1);
    }, 1000);

    // Set attendedConnected only when consultation target actually answers (WS event),
    // NOT when the HTTP initiation request returns.
    this.consultAnsweredSub = this.softphone.consultAnswered$.subscribe(() => {
      this.attendedConnected.set(true);
    });
  }

  ngOnDestroy(): void {
    if (this.holdTickInterval !== null) {
      clearInterval(this.holdTickInterval);
    }
    this.consultAnsweredSub?.unsubscribe();
  }

  // auxiliary tick to force hold-timer re-evaluation in OnPush
  protected readonly _holdTick = signal(0);

  protected answer(): void {
    this.softphone.answerCall();
  }

  protected reject(): void {
    this.softphone.rejectCall();
  }

  protected hangup(): void {
    this.softphone.hangupCall();
  }

  protected toggleMute(): void {
    this.softphone.toggleMute();
  }

  protected toggleHold(): void {
    this.softphone.toggleHold();
  }

  protected setTransferMode(mode: TransferMode): void {
    this.transferMode.set(mode);
    this.transferTarget.set('');
    this.attendedConnected.set(false);
  }

  protected setTransferTargetType(type: TransferTargetType): void {
    this.transferTargetType.set(type);
    this.transferTarget.set('');
    this.attendedConnected.set(false);
  }

  protected onTransferTargetChange(value: string): void {
    this.transferTarget.set(value);
  }

  protected submitTransfer(): void {
    const target = this.transferTarget();
    if (!this.transferTargetValid() || this.isTransferring()) return;
    this.isTransferring.set(true);
    if (this.transferMode() === 'BLIND') {
      this.softphone.initiateBlindTransfer(target, () => this.isTransferring.set(false));
    } else {
      this.softphone.initiateAttendedTransfer(target, () => {
        this.isTransferring.set(false);
      });
    }
  }

  protected completeAttended(): void {
    if (this.isTransferring()) return;
    this.isTransferring.set(true);
    this.softphone.completeAttendedTransfer(() => this.isTransferring.set(false));
  }

  protected cancelTransfer(): void {
    this.softphone.cancelTransfer();
    this.transferTarget.set('');
    this.attendedConnected.set(false);
    this.isTransferring.set(false);
    // After cancelling attended, close the transfer panel so the main ACTIVE view shows
    this._showTransferPanel.set(false);
  }

  protected openTransferView(): void {
    const s = this.session();
    if (!s || s.state !== 'ACTIVE') return;
    // temporarily set state via service (no direct mutation)
    // We show the transfer panel by setting a local signal;
    // actual state change happens only when the user confirms
    this._showTransferPanel.set(true);
  }

  protected closeTransferPanel(): void {
    this._showTransferPanel.set(false);
    this.transferTarget.set('');
    this.attendedConnected.set(false);
    this.transferTargetType.set('PHONE');
  }

  protected readonly _showTransferPanel = signal(false);
  protected readonly _showNotePanel = signal(false);

  protected readonly currentNote = computed(
    () => this.tabStore.tabs().find((t) => t.id === this.tab.id)?.note ?? '',
  );

  /** Whether the schedule-callback modal is open */
  protected readonly _showCallbackModal = signal(false);

  /** Set after a callback is successfully scheduled – shows a badge */
  protected readonly _callbackScheduled = signal(false);

  /**
   * True when active PHONE tab is a call (INBOUND or OUTBOUND) that can have a callback scheduled.
   * The same ScheduleInboundCallbackModalComponent handles both directions.
   */
  protected readonly canScheduleCallback = computed(
    () =>
      this.tab.type === 'PHONE' &&
      (this.tab.direction === 'INBOUND' || this.tab.direction === 'OUTBOUND'),
  );

  protected openNotePanel(): void {
    this._showNotePanel.set(true);
  }

  protected closeNotePanel(): void {
    this._showNotePanel.set(false);
  }

  protected onNoteChange(value: string): void {
    this.tabStore.updateTabNote(this.tab.id, value);
  }

  protected openCallbackModal(): void {
    this._showCallbackModal.set(true);
    this.softphone.callbackModalOpen.set(true);
  }

  protected onCallbackScheduled(_dto: ScheduledCallbackDto): void {
    this._showCallbackModal.set(false);
    this._callbackScheduled.set(true);
    this.softphone.callbackModalOpen.set(false);
    this.wrapIfCallEnded();
  }

  protected onCallbackCancelled(): void {
    this._showCallbackModal.set(false);
    this.softphone.callbackModalOpen.set(false);
    this.wrapIfCallEnded();
  }

  /**
   * If the call has already ended while the callback modal was open,
   * trigger the ACW (wrapping) phase now that the modal is closed.
   */
  private wrapIfCallEnded(): void {
    const s = this.softphone.session();
    if (!s || s.state === 'ENDED') {
      this.tabStore.markAsWrapping(this.tab.id);
    }
  }

  protected onAgentSelected(event: { agentId: string; mode: TransferMode }): void {
    const session = this.session();
    if (!session || this.isTransferring()) return;
    this.isTransferring.set(true);
    if (event.mode === 'BLIND') {
      this.softphone.initiateBlindTransferToAgent(session.contactId, event.agentId, () =>
        this.isTransferring.set(false),
      );
    } else {
      this.softphone.initiateAttendedTransferToAgent(session.contactId, event.agentId, () => {
        this.isTransferring.set(false);
      });
    }
  }

  protected onQueueSelected(event: { queueId: string }): void {
    const session = this.session();
    if (!session || this.isTransferring()) return;
    this.isTransferring.set(true);
    this.softphone.initiateBlindTransferToQueue(session.contactId, event.queueId, () =>
      this.isTransferring.set(false),
    );
  }

  protected formatSeconds(total: number): string {
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    if (h > 0) {
      return `${this.pad(h)}:${this.pad(m)}:${this.pad(s)}`;
    }
    return `${this.pad(m)}:${this.pad(s)}`;
  }

  private pad(n: number): string {
    return n.toString().padStart(2, '0');
  }

  protected trackSession(_: number, s: CallSession): string {
    return s.contactId;
  }
}
