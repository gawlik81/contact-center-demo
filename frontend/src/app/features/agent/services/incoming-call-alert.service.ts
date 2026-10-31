import { normalizeContactName } from '../models/contact-name';
import { Injectable, OnDestroy, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Subject } from 'rxjs';
import { currentUrlSignal } from '../../../shared/utils/router-url.util';
import { filter, take, takeUntil } from 'rxjs/operators';
import { TranslocoService } from '@jsverse/transloco';
import { WebSocketService } from '../../../core/services/websocket.service';
import { NotificationService } from '../../../core/services/notification.service';
import { ContactTabStore, TabLimitReason } from './contact-tab.store';
import { SoftphoneService } from './softphone.service';
import { CustomerLookupService } from './customer-lookup.service';
import { CallDirection, ContactType } from '../models/contact-tab.model';
import {
  CallIncomingPayload,
  CallOutboundPayload,
  CallTransferConsultPayload,
  ContactAssignedPayload,
} from '../models/ws-event.model';

/**
 * PHONE: the softphone session is RINGING and the agent can answer / reject it
 *        (incoming call, outbound leg ringing for the agent, attended-transfer consult).
 * ASYNC: a non-phone contact (email / chat / social) was assigned; nothing rings.
 * IN_CALL: the PHONE call was answered without leaving the page (a modal was open); the banner
 *          stays as a persistent pointer to the desktop.
 */
export type IncomingCallAlertKind = 'PHONE' | 'ASYNC' | 'IN_CALL';

export interface IncomingCallAlert {
  kind: IncomingCallAlertKind;
  /** Channel of an ASYNC alert; undefined for PHONE alerts. */
  channel?: ContactType;
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
  private readonly url = currentUrlSignal();

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

  /**
   * The ringing call was answered while navigating to the desktop would destroy unsaved work
   * (an open modal). Silences the ringtone but keeps the banner as a persistent, non-dismissable
   * "call in progress" indicator until the call ends or the agent reaches the desktop.
   */
  markCallInProgress(): void {
    const alert = this.pendingAlert();
    if (alert?.kind !== 'PHONE') return;
    this.stopAudio();
    this.closeSystemNotification();
    this.pendingAlert.set({ ...alert, kind: 'IN_CALL' });
  }

  private isOnDesktop(): boolean {
    return this.url().startsWith('/agent/desktop');
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
            e.eventType === 'CALL_CONSULT_CANCELLED' ||
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
        } else if (event.eventType === 'CALL_CONSULT_CANCELLED') {
          // Agent1 anulował konsultację – wyczyść banner i audio (jeśli alert nadal aktywny)
          this.dismissAlert();
        } else if (event.eventType === 'CONTACT_ASSIGNED') {
          const payload = event.payload as ContactAssignedPayload;
          if (payload.type === 'PHONE') {
            this.handleContactAssigned(payload);
          } else {
            this.handleAsyncContactAssigned(payload);
          }
        }
      });
  }

  private handleCallIncoming(payload: CallIncomingPayload): void {
    this.lookupService.evict(payload.customerPhone);

    // If a PHONE tab for this contactId already exists, this is the enriched event from
    // CallEventEnricher (arrives after CLI lookup, ~100-500ms after the unenriched relay event).
    // Update the existing tab and session instead of attempting to open a duplicate.
    if (this.applyEnrichedDataIfTabExists(payload.contactId, payload.customerName)) return;

    const reason = this.tabStore.openFromCallIncoming(payload);

    if (reason !== null) {
      this.notifications.warning(this.transloco.translate(LIMIT_MESSAGE_KEYS[reason]));
      return;
    }

    this.softphoneService.incomingCall(payload);
    this.lookupAndUpdateTabName(payload.customerPhone, payload.contactId);

    const alert: IncomingCallAlert = {
      kind: 'PHONE',
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

    // If a tab already exists (CONTACT_ASSIGNED arrived first), only correct the direction.
    // Do NOT overwrite customerName: the HTTP lookup fired at tab-creation time already
    // set the definitive name (or will update it shortly via lookupAndUpdateTabName).
    const existing = this.tabStore
      .tabs()
      .find((t) => t.contactId === payload.contactId && t.type === 'PHONE');
    if (existing) {
      // Always update: direction may need correction, and the enriched CALL_OUTBOUND from
      // CallEventEnricher arrives as a second event with customerId from CLI lookup.
      this.tabStore.updateTabCustomerInfo(
        existing.contactId,
        payload.customerName,
        'OUTBOUND',
        payload.customerId,
      );
      return;
    }

    const reason = this.tabStore.openFromCallOutbound(payload);

    if (reason !== null) {
      this.notifications.warning(this.transloco.translate(LIMIT_MESSAGE_KEYS[reason]));
      return;
    }

    this.softphoneService.incomingCall(payload);
    this.lookupAndUpdateTabName(payload.customerPhone, payload.contactId);

    const alert: IncomingCallAlert = {
      kind: 'PHONE',
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
   * Returns true and updates tab/session/alert if a PHONE tab with the given contactId exists.
   * Used to handle enriched CLI events and CALL_OUTBOUND arriving after CONTACT_ASSIGNED.
   * Pass `direction` to also correct the tab's direction (used by CALL_OUTBOUND handler).
   */
  private applyEnrichedDataIfTabExists(
    contactId: string,
    customerName: string,
    direction?: CallDirection,
  ): boolean {
    const existing = this.tabStore
      .tabs()
      .find((t) => t.contactId === contactId && t.type === 'PHONE');
    if (!existing) return false;

    this.tabStore.updateTabCustomerInfo(contactId, customerName, direction);
    this.softphoneService.updateCustomerName(customerName);
    const alert = this.pendingAlert();
    if (alert?.contactId === contactId) {
      this.pendingAlert.set({ ...alert, customerName });
    }
    return true;
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

    // HTTP lookup by customer phone — updates tab name from "[Konsultacja] Nieznany"
    // to "[Konsultacja] Paweł Miernik" once the API responds.
    if (payload.customerPhone) {
      this.lookupService
        .lookupByPhone(payload.customerPhone)
        .pipe(take(1))
        .subscribe((profile) => {
          if (!profile) return;
          const resolved = `[Konsultacja] ${`${profile.firstName ?? ''} ${profile.lastName ?? ''}`.trim()}`;
          this.tabStore.updateTabCustomerInfo(payload.secondLegCallId, resolved);
          this.softphoneService.updateCustomerName(resolved);
        });
    }

    const alert: IncomingCallAlert = {
      kind: 'PHONE',
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
    this.lookupAndUpdateTabName(payload.customerIdentifier, payload.contactId);

    const alert: IncomingCallAlert = {
      kind: 'PHONE',
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

  /**
   * Non-phone contact (email / chat / social): nothing rings, so no audio and no system
   * notification — only a banner when the agent is outside the desktop, with a shortcut to the
   * contact. This service is the single owner of opening non-phone tabs, because the desktop
   * component is not mounted on the other agent routes. On the desktop itself the new tab is
   * visible, so only a toast is shown.
   */
  private handleAsyncContactAssigned(raw: ContactAssignedPayload): void {
    const payload = { ...raw, customerName: normalizeContactName(raw.customerName) };
    // Re-assigned while the agent is returning it: the tab is re-opened after the release ends.
    if (this.tabStore.noteAssignedDuringRelease(payload)) return;
    const onDesktop = this.isOnDesktop();
    const exists = this.tabStore.tabs().some((t) => t.contactId === payload.contactId);
    if (exists) return; // re-delivery (e.g. WS reconnect) of a contact that is already open

    const reason = this.tabStore.openFromContactAssigned(payload);
    if (reason !== null) {
      this.notifications.warning(this.transloco.translate(LIMIT_MESSAGE_KEYS[reason]));
      return;
    }

    // On the desktop the new tab is right there (and the banner is hidden): no alert needed.
    // Never replace a ringing phone alert: the call must stay answerable.
    if (onDesktop) {
      this.notifications.info(payload.customerName);
      return;
    }
    if (this.pendingAlert() !== null) return;

    this.pendingAlert.set({
      kind: 'ASYNC',
      channel: payload.type,
      contactId: payload.contactId,
      customerName: payload.customerName,
      customerPhone: payload.customerIdentifier,
      queueName: payload.queueName ?? '',
      receivedAt: new Date(),
    });
  }

  /**
   * Fires an HTTP customer lookup by phone number and updates the tab, session and alert
   * with the resolved full name. This is the authoritative name source — independent of
   * WS event ordering or CLI enricher availability.
   */
  private lookupAndUpdateTabName(phoneNumber: string, contactId: string): void {
    if (!phoneNumber) return;
    this.lookupService
      .lookupByPhone(phoneNumber)
      .pipe(take(1))
      .subscribe((profile) => {
        if (!profile) return;
        const name = `${profile.firstName ?? ''} ${profile.lastName ?? ''}`.trim();
        if (!name) return;
        this.tabStore.updateTabCustomerInfo(contactId, name);
        this.softphoneService.updateCustomerName(name);
        const alert = this.pendingAlert();
        if (alert?.contactId === contactId) {
          this.pendingAlert.set({ ...alert, customerName: name });
        }
      });
  }

  // ── Auto-dismiss effect ────────────────────────────────────────────────────

  private setupAutoDismissEffect(): void {
    effect(() => {
      const session = this.softphoneService.session();
      const alert = this.pendingAlert();

      if (alert?.kind === 'ASYNC') {
        // Nothing to answer — drop the alert once its tab is gone, or once the agent is on the
        // desktop with that contact's tab in front of them.
        const tab = this.tabStore.tabs().find((t) => t.contactId === alert.contactId);
        const seen = this.isOnDesktop() && tab?.isActive === true;
        if (!tab || seen) this.dismissAlert();
        return;
      }

      if (alert?.kind === 'IN_CALL') {
        // Persistent until the call is over or the agent reaches the desktop.
        const callOver = session === null || session.state === 'ENDED';
        if (callOver || this.isOnDesktop()) this.dismissAlert();
        return;
      }

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
