import { Injectable, inject, signal, OnDestroy } from '@angular/core';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import {
  Observable,
  Subject,
  catchError,
  of,
  interval,
  switchMap,
  Subscription,
  firstValueFrom,
  EMPTY,
} from 'rxjs';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';
import { Device, Call as TwilioCall } from '@twilio/voice-sdk';
import { CallSession, TransferAgentItem, TransferQueueItem } from '../models/call-session.model';
import { CallIncomingPayload, ContactAssignedPayload } from '../models/ws-event.model';
import { environment } from '../../../../environments/environment';

interface VoiceTokenResponse {
  token: string;
  identity: string;
}

@Injectable({ providedIn: 'root' })
export class SoftphoneService implements OnDestroy {
  private readonly http = inject(HttpClient);

  readonly session = signal<CallSession | null>(null);

  /**
   * True while the schedule-callback modal is open inside SoftphoneComponent.
   * When a call ends (ENDED) and this flag is true, the disposition panel (ACW)
   * is deferred until the modal is closed (confirmed or cancelled).
   */
  readonly callbackModalOpen = signal(false);

  /**
   * Emits once when the consultation target answers the call (CALL_CONSULT_ANSWERED WS event).
   * SoftphoneComponent subscribes to this to set attendedConnected=true only at the right moment.
   */
  readonly consultAnswered$ = new Subject<void>();

  /**
   * Called by AgentDesktopComponent when it receives a CALL_CONSULT_ANSWERED WS event.
   * Notifies SoftphoneComponent that the consultation target has answered — the "Przekaż"
   * (complete transfer) button should now become active.
   */
  markConsultAnswered(): void {
    this.consultAnswered$.next();
  }

  // ── Twilio Voice SDK state ─────────────────────────────────────────────────
  private twilioDevice: Device | null = null;
  private activeCall: TwilioCall | null = null;
  private tokenRefreshSub: Subscription | null = null;

  /** True while Twilio Device is registering or registered */
  readonly twilioDeviceReady = signal<boolean>(false);
  /** Last error from Twilio Device initialization (for diagnostics) */
  readonly twilioDeviceError = signal<string | null>(null);

  // ── UI timers ──────────────────────────────────────────────────────────────
  private durationInterval: ReturnType<typeof setInterval> | null = null;
  private cleanupTimeout: ReturnType<typeof setTimeout> | null = null;
  private transferTimeout: ReturnType<typeof setTimeout> | null = null;

  /**
   * Stores the second-leg call ID returned by the backend after an attended transfer
   * is initiated. Required to call the bridge endpoint when the agent completes the transfer.
   */
  private secondLegCallId: string | null = null;

  // ── Twilio Device lifecycle ────────────────────────────────────────────────

  /**
   * Initializes the Twilio Voice Device by fetching an Access Token with VoiceGrant
   * from the backend and registering the device under the agent's identity.
   *
   * Safe to call multiple times — destroys the previous device before creating a new one.
   */
  async initializeTwilioDevice(): Promise<void> {
    this.twilioDeviceError.set(null);

    let response: VoiceTokenResponse;
    try {
      response = await firstValueFrom(
        this.http.get<VoiceTokenResponse>(`${environment.apiUrl}/telephony/voice-token`, {
          context: new HttpContext().set(SKIP_ERROR_TOAST, true),
        }),
      );
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 404) {
        console.info(
          '[SoftphoneService] Brak konfiguracji Twilio dla tenanta – Voice SDK wyłączony.',
        );
        return;
      }
      const msg = err instanceof Error ? err.message : String(err);
      console.warn(
        '[SoftphoneService] Nie udało się pobrać voice-token — Twilio Device nie zostanie zainicjalizowany.',
        msg,
      );
      this.twilioDeviceError.set(`Brak tokenu: ${msg}`);
      return;
    }

    // Cleanup previous device if exists
    this.destroyTwilioDevice();

    try {
      const device = new Device(response.token, {
        logLevel: 1,
        codecPreferences: [TwilioCall.Codec.Opus, TwilioCall.Codec.PCMU],
      });

      device.on('incoming', (call: TwilioCall) => this.handleIncomingCall(call));

      device.on('registered', () => {
        console.log('[SoftphoneService] Twilio Device zarejestrowany jako:', response.identity);
        this.twilioDeviceReady.set(true);
      });

      device.on('unregistered', () => {
        console.log('[SoftphoneService] Twilio Device wyrejestrowany.');
        this.twilioDeviceReady.set(false);
      });

      device.on('error', (error: Error) => {
        console.error('[SoftphoneService] Twilio Device error:', error.message);
        this.twilioDeviceError.set(error.message);
        this.twilioDeviceReady.set(false);
      });

      await device.register();
      this.twilioDevice = device;

      this.startTokenRefreshSchedule();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      console.error('[SoftphoneService] Błąd inicjalizacji Twilio Device:', msg);
      this.twilioDeviceError.set(msg);
      this.twilioDeviceReady.set(false);
    }
  }

  /**
   * Handles an incoming Twilio Voice call leg directed at this agent's identity
   * (e.g. client:agent-{UUID}).
   *
   * Auto-accepts if the agent already has an active softphone session in state RINGING
   * or ACTIVE (i.e. they already clicked "Odbierz" in the UI), otherwise rejects.
   */
  private handleIncomingCall(call: TwilioCall): void {
    this.activeCall = call;

    const session = this.session();

    if (session === null) {
      // Give WS event time to arrive (race: SDK may fire before CALL_TRANSFER_CONSULT WS event)
      setTimeout(() => {
        const currentSession = this.session();
        if (currentSession === null) {
          console.warn(
            '[SoftphoneService] Incoming Twilio call received but no active softphone session — rejecting.',
          );
          if (this.activeCall === call) {
            call.reject();
            this.activeCall = null;
          }
        } else if (currentSession.state === 'ACTIVE') {
          if (this.activeCall === call) {
            call.accept();
          }
        }
        // state RINGING: wait for answerCall() to call acceptIncomingCall()
      }, 1500);
      return;
    }

    if (session.state === 'ACTIVE') {
      // Agent clicked "Odbierz" before Twilio call arrived — accept immediately
      console.log(
        '[SoftphoneService] Auto-accepting Twilio incoming call for contact:',
        session.contactId,
      );
      call.accept();
    }
    // state === 'RINGING': store the call and wait for answerCall() to call acceptIncomingCall()
  }

  /**
   * Accepts the stored Twilio incoming call leg.
   * Called indirectly through answerCall() after HTTP acknowledge.
   */
  acceptIncomingCall(): void {
    if (!this.activeCall) {
      console.warn('[SoftphoneService] acceptIncomingCall: brak aktywnego połączenia Twilio.');
      return;
    }
    this.activeCall.accept();
  }

  /**
   * Rejects the stored Twilio incoming call leg.
   */
  rejectIncomingCall(): void {
    if (!this.activeCall) {
      return;
    }
    this.activeCall.reject();
    this.activeCall = null;
  }

  // ── Call state machine ─────────────────────────────────────────────────────

  /**
   * Returns the second-leg call ID stored during attended transfer initiation.
   * Used by AgentDesktop to filter out CALL_HANGUP events for the consultation leg
   * so they do not prematurely end the main session.
   */
  getSecondLegCallId(): string | null {
    return this.secondLegCallId;
  }

  /**
   * Updates the contactId in the current session.
   * Called when attended transfer bridge completes – Agent2 gets a proper contact UUID
   * to replace the Twilio CA... SID that was used during consultation.
   */
  updateContactId(newContactId: string): void {
    const s = this.session();
    if (!s) return;
    this.session.set({ ...s, contactId: newContactId });
  }

  /**
   * Updates the session after an attended transfer bridge completes on Agent2's side.
   * Clears the consultation prefix from customerName and resets queueName.
   * Called by AgentDesktop on CALL_BRIDGE_COMPLETE.
   */
  updateSessionAfterBridge(newContactId: string, customerName: string, queueName: string): void {
    const s = this.session();
    if (!s) return;
    this.session.set({ ...s, contactId: newContactId, customerName, queueName });
  }

  updateCustomerName(customerName: string): void {
    const s = this.session();
    if (!s) return;
    this.session.set({ ...s, customerName });
  }

  incomingCall(payload: CallIncomingPayload | ContactAssignedPayload): void {
    const customerPhone =
      'customerPhone' in payload ? payload.customerPhone : payload.customerIdentifier;
    const queueName = 'queueName' in payload ? payload.queueName : '';
    this.clearTimers();
    this.session.set({
      contactId: payload.contactId,
      customerName: payload.customerName,
      customerPhone,
      queueName: queueName ?? '',
      state: 'RINGING',
      startedAt: null,
      duration: 0,
      isMuted: false,
      holdStartedAt: null,
      holdDuration: 0,
      transferTarget: null,
    });
  }

  answerCall(): void {
    const s = this.session();
    if (!s || s.state !== 'RINGING') return;
    // Optimistically update UI state; fire-and-forget the HTTP request
    const now = new Date();
    this.session.set({ ...s, state: 'ACTIVE', startedAt: now });
    this.startDurationTimer();

    // If a Twilio incoming call is already waiting (race condition: arrived before answerCall),
    // accept it now. If it has not arrived yet, handleIncomingCall() will auto-accept it.
    if (this.activeCall) {
      this.acceptIncomingCall();
    }

    this.answerCallHttp(s.contactId)
      .pipe(catchError(() => of(null)))
      .subscribe();
  }

  hangupCall(): void {
    const s = this.session();
    if (!s || s.state === 'ENDED' || s.state === 'RINGING') {
      this.clearTimers();
      this.session.set(null);
      return;
    }
    // Optimistically update UI state; fire-and-forget the HTTP request
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'ENDED' });
    this.hangupCallHttp(s.contactId)
      .pipe(catchError((err) => { console.error('[SoftphoneService] hangupCall HTTP error:', err); return of(null); }))
      .subscribe();
    this.cleanupTimeout = setTimeout(() => {
      this.session.set(null);
      this.activeCall = null;
    }, 2000);
  }

  /**
   * Handles a remote hangup initiated by the customer (CALL_HANGUP WebSocket event).
   *
   * Unlike hangupCall() (agent-initiated), this does NOT fire an HTTP hangup request
   * because Twilio has already ended the call. It only transitions the local session
   * to ENDED so the disposition panel (ACW) appears automatically.
   *
   * Safe to call when session is null or already ENDED (no-op in those cases).
   */
  remoteHangup(): void {
    const s = this.session();
    if (!s || s.state === 'ENDED') {
      return;
    }
    this.stopDurationTimer();
    if (s.state === 'RINGING') {
      if (this.activeCall) {
        try {
          this.activeCall.reject();
        } catch {
          // ignore
        }
        this.activeCall = null;
      }
      this.clearTimers();
      this.session.set(null);
      return;
    }
    // Disconnect the Twilio call leg on the agent side if still active.
    // disconnectAll() covers outbound calls where activeCall reference may be null
    // (the outbound leg is owned by the Device, not stored in activeCall).
    if (this.twilioDevice) {
      try {
        this.twilioDevice.disconnectAll();
      } catch {
        // ignore — device may already be in a disconnected state
      }
    } else if (this.activeCall) {
      try {
        this.activeCall.disconnect();
      } catch {
        // ignore — call may already be disconnected
      }
    }
    this.session.set({ ...s, state: 'ENDED' });
    this.cleanupTimeout = setTimeout(() => {
      this.session.set(null);
      this.activeCall = null;
    }, 2000);
  }

  /**
   * Anuluje sesję konsultacji bez przechodzenia do ACW (After Contact Work).
   *
   * Wywoływane gdy Agent1 anuluje attended transfer przed wykonaniem bridge.
   * W odróżnieniu od remoteHangup() NIE ustawia stanu ENDED (który wyzwala ACW
   * w softphoneEndedEffect) — zamiast tego natychmiast czyści sesję do null,
   * co sprawia że effect nie przejdzie do AFTER_CONTACT.
   *
   * Backend już ustawił status Agent2 na AVAILABLE — frontend nie powinien
   * wywoływać API zmiany statusu.
   */
  cancelConsultSession(): void {
    const s = this.session();
    if (!s) return;

    this.clearTimers();

    // Rozłącz Twilio jeśli aktywne
    if (this.twilioDevice) {
      try {
        this.twilioDevice.disconnectAll();
      } catch {
        // ignore — device może być już rozłączony
      }
    } else if (this.activeCall) {
      try {
        this.activeCall.disconnect();
      } catch {
        // ignore
      }
    }

    // Wyczyść sesję bezpośrednio do null – omijamy stan ENDED żeby nie wyzwolić ACW
    this.session.set(null);
    this.activeCall = null;
  }

  /**
   * Przywraca sesję do stanu ACTIVE po tym, jak cel konsultacji (Agent1) był niedostępny
   * (busy / no-answer) i backend wysłał CALL_CONSULT_CANCELLED.
   *
   * Różni się od cancelConsultSession() tym, że:
   * - NIE rozłącza urządzenia Twilio — agent-inicjator nadal uczestniczy w konferencji z klientem.
   * - NIE czyści sesji do null — zamiast tego wraca do stanu ACTIVE.
   * - Kasuje secondLegCallId — noga konsultacyjna jest już zakończona server-side.
   *
   * Wywoływana wyłącznie gdy sesja jest w stanie TRANSFERRING (agent jest inicjatorem
   * konsultacji). Dla agenta będącego celem konsultacji używaj cancelConsultSession().
   */
  restoreToActiveAfterConsultCancel(): void {
    const s = this.session();
    if (!s || s.state !== 'TRANSFERRING') return;
    this.secondLegCallId = null;
    this.session.set({ ...s, state: 'ACTIVE', transferTarget: null });
    this.startDurationTimer();
  }

  rejectCall(): void {
    const s = this.session();
    if (!s || s.state !== 'RINGING') return;
    this.clearTimers();
    this.session.set(null);
    this.rejectIncomingCall();
    this.hangupCallHttp(s.contactId)
      .pipe(catchError(() => of(null)))
      .subscribe();
  }

  toggleMute(): void {
    const s = this.session();
    if (!s || (s.state !== 'ACTIVE' && s.state !== 'ON_HOLD')) return;
    const newMuted = !s.isMuted;
    this.session.set({ ...s, isMuted: newMuted });
    this.muteCallHttp(s.contactId, newMuted)
      .pipe(catchError(() => of(null)))
      .subscribe();
  }

  toggleHold(): void {
    const s = this.session();
    if (!s) return;
    if (s.state === 'ACTIVE') {
      this.stopDurationTimer();
      this.session.set({ ...s, state: 'ON_HOLD', holdStartedAt: new Date() });
      this.holdCallHttp(s.contactId, true)
        .pipe(catchError(() => of(null)))
        .subscribe();
    } else if (s.state === 'ON_HOLD') {
      const holdMs = s.holdStartedAt ? new Date().getTime() - s.holdStartedAt.getTime() : 0;
      const addedHoldSec = Math.floor(holdMs / 1000);
      this.session.set({
        ...s,
        state: 'ACTIVE',
        holdStartedAt: null,
        holdDuration: s.holdDuration + addedHoldSec,
      });
      this.startDurationTimer();
      this.holdCallHttp(s.contactId, false)
        .pipe(catchError(() => of(null)))
        .subscribe();
    }
  }

  initiateBlindTransfer(target: string, onSettled?: () => void): void {
    const s = this.session();
    if (!s || s.state !== 'ACTIVE') {
      onSettled?.();
      return;
    }
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'TRANSFERRING', transferTarget: target });
    this.http
      .post(`${environment.apiUrl}/telephony/calls/${encodeURIComponent(s.contactId)}/transfer`, {
        transferType: 'BLIND',
        targetType: 'PHONE',
        phoneNumber: target,
      })
      .pipe(catchError(() => of(null)))
      .subscribe(() => {
        onSettled?.();
        this.transferTimeout = setTimeout(() => {
          const current = this.session();
          if (current) {
            this.session.set({ ...current, state: 'ENDED' });
          }
          this.cleanupTimeout = setTimeout(() => {
            this.session.set(null);
            this.activeCall = null;
          }, 2000);
        }, 1500);
      });
  }

  initiateAttendedTransfer(target: string, onSettled?: () => void): void {
    const s = this.session();
    if (!s || s.state !== 'ACTIVE') {
      onSettled?.();
      return;
    }
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'TRANSFERRING', transferTarget: target });
    this.http
      .post<{ secondLegCallId?: string }>(
        `${environment.apiUrl}/telephony/calls/${encodeURIComponent(s.contactId)}/transfer`,
        {
          transferType: 'ATTENDED',
          targetType: 'PHONE',
          phoneNumber: target,
        },
      )
      .pipe(
        catchError(() => {
          const current = this.session();
          if (current) {
            this.session.set({ ...current, state: 'ACTIVE', transferTarget: null });
          }
          this.secondLegCallId = null;
          this.startDurationTimer();
          onSettled?.();
          return EMPTY;
        }),
      )
      .subscribe((resp) => {
        if (resp?.secondLegCallId) {
          this.secondLegCallId = resp.secondLegCallId;
        }
        onSettled?.();
      });
  }

  completeAttendedTransfer(onSettled?: () => void): void {
    const s = this.session();
    if (!s || s.state !== 'TRANSFERRING') {
      onSettled?.();
      return;
    }
    if (!this.secondLegCallId) {
      console.warn('[SoftphoneService] completeAttendedTransfer: brak secondLegCallId, anulowanie');
      onSettled?.();
      return;
    }
    this.http
      .post(
        `${environment.apiUrl}/telephony/calls/${encodeURIComponent(s.contactId)}/bridge/${encodeURIComponent(this.secondLegCallId)}`,
        {},
      )
      .pipe(catchError(() => of(null)))
      .subscribe(() => {
        onSettled?.();
        this.secondLegCallId = null;
        this.session.set({ ...s, state: 'ENDED' });
        this.cleanupTimeout = setTimeout(() => {
          this.session.set(null);
          this.activeCall = null;
        }, 2000);
      });
  }

  cancelTransfer(): void {
    const s = this.session();
    if (!s || s.state !== 'TRANSFERRING') return;
    const secondLegId = this.secondLegCallId;
    this.secondLegCallId = null;
    this.session.set({ ...s, state: 'ACTIVE', transferTarget: null });
    this.startDurationTimer();
    if (secondLegId) {
      this.hangupCallHttp(secondLegId)
        .pipe(catchError(() => of(null)))
        .subscribe();
    }
  }

  // ── Transfer to AGENT ──────────────────────────────────────────────────────

  initiateBlindTransferToAgent(
    callId: string,
    agentId: string,
    displayName: string,
    onSettled?: () => void,
  ): void {
    const s = this.session();
    if (!s || s.state !== 'ACTIVE') {
      onSettled?.();
      return;
    }
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'TRANSFERRING', transferTarget: displayName });
    this.http
      .post(`${environment.apiUrl}/telephony/calls/${encodeURIComponent(callId)}/transfer`, {
        transferType: 'BLIND',
        targetType: 'AGENT',
        agentId,
      })
      .pipe(catchError(() => of(null)))
      .subscribe(() => {
        onSettled?.();
        this.transferTimeout = setTimeout(() => {
          const current = this.session();
          if (current) {
            this.session.set({ ...current, state: 'ENDED' });
          }
          this.cleanupTimeout = setTimeout(() => {
            this.session.set(null);
            this.activeCall = null;
          }, 2000);
        }, 1500);
      });
  }

  initiateAttendedTransferToAgent(
    callId: string,
    agentId: string,
    displayName: string,
    onSettled?: () => void,
  ): void {
    const s = this.session();
    if (!s || s.state !== 'ACTIVE') {
      onSettled?.();
      return;
    }
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'TRANSFERRING', transferTarget: displayName });
    this.http
      .post<{ secondLegCallId?: string }>(
        `${environment.apiUrl}/telephony/calls/${encodeURIComponent(callId)}/transfer`,
        {
          transferType: 'ATTENDED',
          targetType: 'AGENT',
          agentId,
        },
      )
      .pipe(
        catchError(() => {
          const current = this.session();
          if (current) {
            this.session.set({ ...current, state: 'ACTIVE', transferTarget: null });
          }
          this.secondLegCallId = null;
          this.startDurationTimer();
          onSettled?.();
          return EMPTY;
        }),
      )
      .subscribe((resp) => {
        if (resp?.secondLegCallId) {
          this.secondLegCallId = resp.secondLegCallId;
        }
        onSettled?.();
      });
  }

  // ── Transfer to QUEUE ──────────────────────────────────────────────────────

  initiateBlindTransferToQueue(
    callId: string,
    queueId: string,
    displayName: string,
    onSettled?: () => void,
  ): void {
    const s = this.session();
    if (!s || s.state !== 'ACTIVE') {
      onSettled?.();
      return;
    }
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'TRANSFERRING', transferTarget: displayName });
    this.http
      .post(`${environment.apiUrl}/telephony/calls/${encodeURIComponent(callId)}/transfer`, {
        transferType: 'BLIND',
        targetType: 'QUEUE',
        queueId,
      })
      .pipe(catchError(() => of(null)))
      .subscribe(() => {
        onSettled?.();
        this.transferTimeout = setTimeout(() => {
          const current = this.session();
          if (current) {
            this.session.set({ ...current, state: 'ENDED' });
          }
          this.cleanupTimeout = setTimeout(() => {
            this.session.set(null);
            this.activeCall = null;
          }, 2000);
        }, 1500);
      });
  }

  // ── Transfer list endpoints ────────────────────────────────────────────────

  /**
   * Fetches the list of available agents for transfer panel selection.
   */
  fetchTransferAgents(): Observable<TransferAgentItem[]> {
    return this.http.get<TransferAgentItem[]>(`${environment.apiUrl}/telephony/transfer/agents`);
  }

  /**
   * Fetches the list of available queues for transfer panel selection.
   */
  fetchTransferQueues(): Observable<TransferQueueItem[]> {
    return this.http.get<TransferQueueItem[]>(`${environment.apiUrl}/telephony/transfer/queues`);
  }

  // ── Telephony HTTP API ─────────────────────────────────────────────────────

  /** Answer an incoming call. callId is the contact ID. */
  answerCallHttp(callId: string): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/telephony/calls/${encodeURIComponent(callId)}/answer`,
      null,
    );
  }

  /** Hang up an active or ringing call. */
  hangupCallHttp(callId: string): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/telephony/calls/${encodeURIComponent(callId)}/hangup`,
      null,
    );
  }

  /** Place a call on hold or take it off hold. */
  holdCallHttp(callId: string, hold: boolean): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/telephony/calls/${encodeURIComponent(callId)}/hold`,
      null,
      { params: { hold: String(hold) } },
    );
  }

  /** Mute or unmute the microphone for a call. */
  muteCallHttp(callId: string, mute: boolean): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/telephony/calls/${encodeURIComponent(callId)}/mute`,
      null,
      { params: { mute: String(mute) } },
    );
  }

  // ── Token refresh ──────────────────────────────────────────────────────────

  /**
   * Schedules a token refresh every 55 minutes (token TTL is 3600s).
   * The Device.updateToken() call re-registers without destroying the device.
   */
  private startTokenRefreshSchedule(): void {
    this.tokenRefreshSub?.unsubscribe();
    // 3300000 ms = 55 minutes
    this.tokenRefreshSub = interval(3_300_000)
      .pipe(
        switchMap(() =>
          this.http.get<VoiceTokenResponse>(`${environment.apiUrl}/telephony/voice-token`).pipe(
            catchError((err) => {
              console.warn('[SoftphoneService] Token refresh failed:', err);
              return of(null);
            }),
          ),
        ),
      )
      .subscribe((response) => {
        if (response && this.twilioDevice) {
          this.twilioDevice.updateToken(response.token);
          console.log('[SoftphoneService] Twilio Device token odświeżony.');
        }
      });
  }

  // ── Cleanup ────────────────────────────────────────────────────────────────

  private destroyTwilioDevice(): void {
    this.clearTimers();
    this.tokenRefreshSub?.unsubscribe();
    this.tokenRefreshSub = null;
    if (this.twilioDevice) {
      try {
        this.twilioDevice.destroy();
      } catch {
        // ignore errors during cleanup
      }
      this.twilioDevice = null;
      this.twilioDeviceReady.set(false);
    }
    this.activeCall = null;
  }

  ngOnDestroy(): void {
    this.destroyTwilioDevice();
    this.clearTimers();
  }

  // ── Internal helpers ───────────────────────────────────────────────────────

  private startDurationTimer(): void {
    this.stopDurationTimer();
    this.durationInterval = setInterval(() => {
      const s = this.session();
      if (s && s.state === 'ACTIVE') {
        this.session.set({ ...s, duration: s.duration + 1 });
      }
    }, 1000);
  }

  private stopDurationTimer(): void {
    if (this.durationInterval !== null) {
      clearInterval(this.durationInterval);
      this.durationInterval = null;
    }
  }

  private clearTimers(): void {
    this.stopDurationTimer();
    if (this.cleanupTimeout !== null) {
      clearTimeout(this.cleanupTimeout);
      this.cleanupTimeout = null;
    }
    if (this.transferTimeout !== null) {
      clearTimeout(this.transferTimeout);
      this.transferTimeout = null;
    }
  }
}
