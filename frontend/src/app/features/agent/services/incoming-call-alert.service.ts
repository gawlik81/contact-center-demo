import { Injectable, OnDestroy, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Subject } from 'rxjs';
import { filter, takeUntil } from 'rxjs/operators';
import { TranslocoService } from '@jsverse/transloco';
import { WebSocketService } from '../../../core/services/websocket.service';
import { NotificationService } from '../../../core/services/notification.service';
import { ContactTabStore, TabLimitReason } from './contact-tab.store';
import { SoftphoneService } from './softphone.service';
import { CustomerLookupService } from './customer-lookup.service';
import {
  CallIncomingPayload,
  CallOutboundPayload,
  CallTransferConsultPayload,
  ContactAssignedPayload,
} from '../models/ws-event.model';

export interface IncomingCallAlert {
  contactId: string;
  customerName: string;
  customerPhone: string;
  queueName: string;
  receivedAt: Date;
}

const LIMIT_MESSAGE_KEYS: Record<Exclude<TabLimitReason, null>, string> = {
  MAX_PHONE: 'agent.incomingCall.limitMaxPhone',
  MAX_ASYNC: 'agent.incomingCall.limitMaxAsync',
  MAX_TOTAL: 'agent.incomingCall.limitMaxTotal',
};

@Injectable({ providedIn: 'root' })
export class IncomingCallAlertService implements OnDestroy {
  private readonly ws = inject(WebSocketService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly tabStore = inject(ContactTabStore);
  private readonly softphoneService = inject(SoftphoneService);
  private readonly lookupService = inject(CustomerLookupService);
  private readonly router = inject(Router);

  private readonly destroy$ = new Subject<void>();

  /** Aktywne oczekujące połączenie przychodzące (null gdy brak). */
  readonly pendingAlert = signal<IncomingCallAlert | null>(null);

  private audio: HTMLAudioElement | null = null;
  private systemNotification: Notification | null = null;
  private notificationCloseTimeout: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.initAudio();
    this.requestNotificationPermission();
    this.subscribeToWsEvents();
    this.setupAutoDismissEffect();
  }

  // ── Public API ─────────────────────────────────────────────────────────────

  /**
   * Clears the pending alert, stops the ringtone and closes the system notification.
   * Does NOT disconnect the call.
   */
  dismissAlert(): void {
    this.pendingAlert.set(null);
    this.stopAudio();
    this.closeSystemNotification();
  }

  // ── Initialization ─────────────────────────────────────────────────────────

  private initAudio(): void {
    try {
      this.audio = new Audio('sounds/ringtone.mp3');
      this.audio.loop = true;
    } catch {
      this.audio = null;
    }
  }

  private requestNotificationPermission(): void {
    if (typeof Notification === 'undefined') return;
    if (Notification.permission === 'default') {
      // eslint-disable-next-line @typescript-eslint/no-empty-function
      Notification.requestPermission().catch(() => {});
    }
  }

  // ── WebSocket event handling ───────────────────────────────────────────────

  private subscribeToWsEvents(): void {
    this.ws.events$
      .pipe(
        filter(
          (e) =>
            e.eventType === 'CALL_INCOMING' ||
            e.eventType === 'CALL_OUTBOUND' ||
            e.eventType === 'CALL_TRANSFER_CONSULT' ||
            e.eventType === 'CONTACT_ASSIGNED',
        ),
        takeUntil(this.destroy$),
      )
      .subscribe((event) => {
        if (event.eventType === 'CALL_INCOMING') {
          this.handleCallIncoming(event.payload as CallIncomingPayload);
        } else if (event.eventType === 'CALL_OUTBOUND') {
          this.handleCallOutbound(event.payload as CallOutboundPayload);
        } else if (event.eventType === 'CALL_TRANSFER_CONSULT') {
          this.handleCallTransferConsult(event.payload as CallTransferConsultPayload);
        } else if (event.eventType === 'CONTACT_ASSIGNED') {
          const payload = event.payload as ContactAssignedPayload;
          if (payload.type === 'PHONE') {
            this.handleContactAssigned(payload);
          }
        }
      });
  }

  private handleCallIncoming(payload: CallIncomingPayload): void {
    this.lookupService.evict(payload.customerPhone);

    const reason = this.tabStore.openFromCallIncoming(payload);

    if (reason !== null) {
      this.notifications.warning(this.transloco.translate(LIMIT_MESSAGE_KEYS[reason]));
      return;
    }

    this.softphoneService.incomingCall(payload);

    const alert: IncomingCallAlert = {
      contactId: payload.contactId,
      customerName: payload.customerName,
      customerPhone: payload.customerPhone,
      queueName: payload.queueName,
      receivedAt: new Date(),
    };
    this.pendingAlert.set(alert);
    this.playAudio();
    this.showSystemNotification(alert);
  }

  private handleCallOutbound(payload: CallOutboundPayload): void {
    this.lookupService.evict(payload.customerPhone);

    const reason = this.tabStore.openFromCallOutbound(payload);

    if (reason !== null) {
      this.notifications.warning(this.transloco.translate(LIMIT_MESSAGE_KEYS[reason]));
      return;
    }

    this.softphoneService.incomingCall(payload);

    const alert: IncomingCallAlert = {
      contactId: payload.contactId,
      customerName: payload.customerName,
      customerPhone: payload.customerPhone,
      queueName: payload.queueName,
      receivedAt: new Date(),
    };
    this.pendingAlert.set(alert);
    this.playAudio();
    this.showSystemNotification(alert);
  }

  /**
   * Obsługuje przychodzącą konsultację attended transfer (CALL_TRANSFER_CONSULT).
   *
   * Agent2 dostaje ten event gdy Agent1 inicjuje konsultację. Otwieramy zakładkę
   * PHONE (bez niej SoftphoneComponent nie renderuje się — agent-desktop pokazuje
   * softphone tylko dla aktywnej zakładki type='PHONE'), a następnie inicjalizujemy
   * sesję softphonu (state=RINGING) aby Twilio Device nie odrzucił przychodzącego
   * połączenia SDK z powodu braku aktywnej sesji (handleIncomingCall guard).
   *
   * contactId sesji ustawiamy na secondLegCallId (CA_...) — to właśnie ten callSid
   * jest używany przez backend do identyfikacji nogi konsultacji.
   */
  private handleCallTransferConsult(payload: CallTransferConsultPayload): void {
    const customerName = `[Konsultacja] ${payload.customerName}`;

    // Otwórz zakładkę PHONE – bez niej SoftphoneComponent nie renderuje się
    // (agent-desktop pokazuje softphone tylko dla aktywnej zakładki type='PHONE').
    const reason = this.tabStore.openTab({
      id: crypto.randomUUID(),
      type: 'PHONE',
      contactId: payload.secondLegCallId,
      customerName,
      customerIdentifier: payload.customerPhone,
      status: 'ACTIVE',
      startedAt: new Date(),
      direction: 'INBOUND',
      originalContactId: payload.originalContactId,
    });

    if (reason !== null) {
      this.notifications.warning(this.transloco.translate(LIMIT_MESSAGE_KEYS[reason]));
      return;
    }

    // Inicjalizuj sesję softphonu tak aby handleIncomingCall() nie odrzucił połączenia.
    // Używamy secondLegCallId jako contactId — backend identyfikuje nogę konsultacji
    // po tym callSid, nie po originalContactId.
    this.softphoneService.incomingCall({
      contactId: payload.secondLegCallId,
      customerName,
      customerPhone: payload.customerPhone,
      queueName: '',
    });

    const alert: IncomingCallAlert = {
      contactId: payload.secondLegCallId,
      customerName,
      customerPhone: payload.customerPhone,
      queueName: '',
      receivedAt: new Date(),
    };

    this.pendingAlert.set(alert);
    this.playAudio();
    this.showSystemNotification(alert);
  }

  private handleContactAssigned(payload: ContactAssignedPayload): void {
    const reason = this.tabStore.openFromContactAssigned(payload);

    if (reason !== null) {
      this.notifications.warning(this.transloco.translate('agent.incomingCall.limitGeneral'));
      return;
    }

    this.softphoneService.incomingCall(payload);

    const alert: IncomingCallAlert = {
      contactId: payload.contactId,
      customerName: payload.customerName,
      customerPhone: payload.customerIdentifier,
      queueName: payload.queueName ?? '',
      receivedAt: new Date(),
    };
    this.pendingAlert.set(alert);
    this.playAudio();
    this.showSystemNotification(alert);
  }

  // ── Auto-dismiss effect ────────────────────────────────────────────────────

  private setupAutoDismissEffect(): void {
    effect(() => {
      const session = this.softphoneService.session();
      const alert = this.pendingAlert();

      if (session === null && alert !== null) {
        this.dismissAlert();
        return;
      }

      if (session !== null && (session.state === 'ACTIVE' || session.state === 'ENDED')) {
        this.dismissAlert();
      }
    });
  }

  // ── Audio ──────────────────────────────────────────────────────────────────

  private playAudio(): void {
    if (!this.audio) return;
    try {
      this.audio.currentTime = 0;
      // eslint-disable-next-line @typescript-eslint/no-empty-function
      this.audio.play().catch(() => {});
    } catch {
      // ignore — AudioContext may require a user gesture
    }
  }

  private stopAudio(): void {
    if (!this.audio) return;
    try {
      this.audio.pause();
      this.audio.currentTime = 0;
    } catch {
      // ignore
    }
  }

  // ── System Notifications ───────────────────────────────────────────────────

  private showSystemNotification(alert: IncomingCallAlert): void {
    if (typeof Notification === 'undefined') return;
    if (Notification.permission !== 'granted') return;

    try {
      this.closeSystemNotification();

      const notification = new Notification(
        this.transloco.translate('agent.incomingCall.systemNotificationTitle'),
        {
          body: `${alert.customerName} (${alert.customerPhone}) — ${alert.queueName}`,
          icon: 'favicon.ico',
        },
      );

      notification.onclick = () => {
        window.focus();
        this.router.navigate(['/agent/desktop']);
        notification.close();
      };

      this.notificationCloseTimeout = setTimeout(() => {
        notification.close();
      }, 15_000);

      this.systemNotification = notification;
    } catch {
      // ignore — Notification API may not be available in all environments
    }
  }

  private closeSystemNotification(): void {
    if (this.notificationCloseTimeout !== null) {
      clearTimeout(this.notificationCloseTimeout);
      this.notificationCloseTimeout = null;
    }
    if (this.systemNotification) {
      try {
        this.systemNotification.close();
      } catch {
        // ignore
      }
      this.systemNotification = null;
    }
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.stopAudio();
    this.closeSystemNotification();
  }
}
