import { Injectable, inject, signal, OnDestroy } from '@angular/core';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import {
  Observable,
  catchError,
  of,
  interval,
  switchMap,
  Subscription,
  firstValueFrom,
} from 'rxjs';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';
import { Device, Call as TwilioCall } from '@twilio/voice-sdk';
import { CallSession } from '../models/call-session.model';
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
      console.warn(
        '[SoftphoneService] Incoming Twilio call received but no active softphone session — rejecting.',
      );
      call.reject();
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
      .pipe(catchError(() => of(null)))
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

  initiateBlindTransfer(target: string): void {
    const s = this.session();
    if (!s || s.state !== 'ACTIVE') return;
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'TRANSFERRING', transferTarget: target });
    // Blind transfer ends the call on this leg after a short delay
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
  }

  initiateAttendedTransfer(target: string): void {
    const s = this.session();
    if (!s || s.state !== 'ACTIVE') return;
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'TRANSFERRING', transferTarget: target });
  }

  completeAttendedTransfer(): void {
    const s = this.session();
    if (!s || s.state !== 'TRANSFERRING') return;
    this.session.set({ ...s, state: 'ENDED' });
    this.cleanupTimeout = setTimeout(() => {
      this.session.set(null);
      this.activeCall = null;
    }, 2000);
  }

  cancelTransfer(): void {
    const s = this.session();
    if (!s || s.state !== 'TRANSFERRING') return;
    this.session.set({ ...s, state: 'ACTIVE', transferTarget: null });
    this.startDurationTimer();
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
