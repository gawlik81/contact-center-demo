import { DestroyRef, Injectable, computed, effect, inject, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { filter } from 'rxjs';
import { TranslocoService } from '@jsverse/transloco';
import { WebSocketService } from '../../../core/services/websocket.service';
import { NotificationService } from '../../../core/services/notification.service';
import { currentUrlSignal } from '../../../shared/utils/router-url.util';
import { AgentStatusService } from './agent-status.service';
import { ContactTabStore } from './contact-tab.store';
import { IncomingCallAlertService } from './incoming-call-alert.service';
import { SoftphoneService } from './softphone.service';
import {
  WsEvent,
  CallHangupPayload,
  CallBridgeCompletePayload,
  CallConsultCancelledPayload,
  CallConsultAnsweredPayload,
} from '../models/ws-event.model';

/**
 * Owns everything that must happen to a call regardless of which agent page is showing:
 * session-state side effects (ACTIVE -> BUSY, ENDED -> AFTER_CONTACT + ACW tab), call-related
 * WebSocket events, and the single implementation of answer / reject / hang up used by the
 * incoming-call banner, the softphone buttons and the keyboard shortcuts.
 *
 * Root-provided and created by AgentShellComponent, so behaviour is identical with or without
 * AgentDesktopComponent mounted. (SoftphoneService clears an ENDED session after 2 s; the effects
 * below react within the same tick, so ACW is always entered.)
 */
@Injectable({ providedIn: 'root' })
export class CallLifecycleService {
  private readonly ws = inject(WebSocketService);
  private readonly statusService = inject(AgentStatusService);
  private readonly tabStore = inject(ContactTabStore);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly softphoneService = inject(SoftphoneService);
  private readonly alerts = inject(IncomingCallAlertService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly url = currentUrlSignal();

  /**
   * Derived signal that emits only the session state string (or null), so the effects below do
   * not fire on every duration tick.
   */
  private readonly sessionState = computed(() => this.softphoneService.session()?.state ?? null);

  /**
   * Monitors SoftphoneService session state.
   * When a call transitions to ENDED, puts the active PHONE tab into WRAPPING state
   * so the disposition panel appears automatically, and sets agent status to AFTER_CONTACT.
   * Uses sessionState (not session()) to avoid re-triggering on every duration tick.
   * tabStore.tabs() is read inside untracked() to prevent tab closure from re-triggering.
   */
  private readonly endedEffect = effect(() => {
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
   * When a call transitions to ACTIVE (agent answers), sets the agent status to BUSY
   * so the backend dialer skips this agent during poll for available agents.
   */
  private readonly activeEffect = effect(() => {
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

  constructor() {
    this.subscribeToCallEvents();
  }

  // ── Public API ─────────────────────────────────────────────────────────────

  /**
   * Answers the ringing call from anywhere in the agent surface. SoftphoneService is a root
   * singleton that owns the Twilio Device, so this works while SoftphoneComponent is not mounted.
   *
   * Navigation to the desktop would destroy unsaved work in an open modal (e.g. an e-mail draft),
   * so when a modal dialog is open the call is answered WITHOUT navigating and the banner turns
   * into a persistent "call in progress" indicator instead. Returns false when nothing rings.
   */
  answerRingingCall(): boolean {
    const session = this.softphoneService.session();
    if (session?.state !== 'RINGING') return false;

    const modalOpen = document.querySelector('dialog[open]') !== null;
    this.softphoneService.answerCall();
    const tab = this.findPhoneTab(session.contactId);
    if (tab) this.tabStore.setActiveTab(tab.id);

    if (this.isOnDesktop()) {
      this.alerts.dismissAlert();
    } else if (modalOpen) {
      this.alerts.markCallInProgress();
    } else {
      this.alerts.dismissAlert();
      this.router.navigate(['/agent/desktop']);
    }
    return true;
  }

  /**
   * Rejects the ringing call and drops its PHONE tab, so a rejected call never leaves a tab
   * occupying the single phone slot. Used by the banner, the softphone button and the hotkey.
   */
  rejectRingingCall(): boolean {
    const session = this.softphoneService.session();
    if (session?.state !== 'RINGING') return false;

    this.softphoneService.rejectCall();
    const tab = this.findPhoneTab(session.contactId);
    if (tab) this.tabStore.closeTab(tab.id);
    this.alerts.dismissAlert();
    return true;
  }

  /** Hangs up an active / held call, or rejects a ringing one. Returns false with no call. */
  hangupOrReject(): boolean {
    const state = this.softphoneService.session()?.state;
    if (state === 'RINGING') return this.rejectRingingCall();
    if (state === 'ACTIVE' || state === 'ON_HOLD') {
      this.softphoneService.hangupCall();
      return true;
    }
    return false;
  }

  /** Mutes / unmutes during an active or held call. Returns false otherwise. */
  toggleMute(): boolean {
    const state = this.softphoneService.session()?.state;
    if (state !== 'ACTIVE' && state !== 'ON_HOLD') return false;
    this.softphoneService.toggleMute();
    return true;
  }

  private isOnDesktop(): boolean {
    return this.url().startsWith('/agent/desktop');
  }

  private findPhoneTab(contactId: string) {
    return this.tabStore.tabs().find((t) => t.type === 'PHONE' && t.contactId === contactId);
  }

  // ── Call-related WebSocket events ──────────────────────────────────────────

  private subscribeToCallEvents(): void {
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
            '[CallLifecycle] CALL_HANGUP dla nogi konsultacji (transfer nieudany) – zachowuję główną sesję:',
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
              '[CallLifecycle] CALL_HANGUP ignorowany — nie dotyczy aktywnej sesji:',
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
        console.warn('[CallLifecycle] CALL_CONSULT_CANCELLED – anulowanie konsultacji:', payload);

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
        console.warn('[CallLifecycle] CALL_CONSULT_ANSWERED – konsultacja odebrana:', payload);
        this.softphoneService.markConsultAnswered();
      });
  }
}
