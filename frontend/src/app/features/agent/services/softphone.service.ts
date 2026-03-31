import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, of } from 'rxjs';
import { CallSession } from '../models/call-session.model';
import { CallIncomingPayload, ContactAssignedPayload } from '../models/ws-event.model';
import { environment } from '../../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class SoftphoneService {
  private readonly http = inject(HttpClient);

  readonly session = signal<CallSession | null>(null);

  private durationInterval: ReturnType<typeof setInterval> | null = null;
  private cleanupTimeout: ReturnType<typeof setTimeout> | null = null;
  private transferTimeout: ReturnType<typeof setTimeout> | null = null;

  incomingCall(payload: CallIncomingPayload | ContactAssignedPayload): void {
    const customerPhone = 'customerPhone' in payload
      ? payload.customerPhone
      : payload.customerIdentifier;
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
    this.answerCallHttp(s.contactId).pipe(catchError(() => of(null))).subscribe();
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
    this.hangupCallHttp(s.contactId).pipe(catchError(() => of(null))).subscribe();
    this.cleanupTimeout = setTimeout(() => {
      this.session.set(null);
    }, 2000);
  }

  rejectCall(): void {
    const s = this.session();
    if (!s || s.state !== 'RINGING') return;
    this.clearTimers();
    this.session.set(null);
    this.hangupCallHttp(s.contactId).pipe(catchError(() => of(null))).subscribe();
  }

  toggleMute(): void {
    const s = this.session();
    if (!s || (s.state !== 'ACTIVE' && s.state !== 'ON_HOLD')) return;
    const newMuted = !s.isMuted;
    this.session.set({ ...s, isMuted: newMuted });
    this.muteCallHttp(s.contactId, newMuted).pipe(catchError(() => of(null))).subscribe();
  }

  toggleHold(): void {
    const s = this.session();
    if (!s) return;
    if (s.state === 'ACTIVE') {
      this.stopDurationTimer();
      this.session.set({ ...s, state: 'ON_HOLD', holdStartedAt: new Date() });
      this.holdCallHttp(s.contactId, true).pipe(catchError(() => of(null))).subscribe();
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
      this.holdCallHttp(s.contactId, false).pipe(catchError(() => of(null))).subscribe();
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
    }, 2000);
  }

  cancelTransfer(): void {
    const s = this.session();
    if (!s || s.state !== 'TRANSFERRING') return;
    this.session.set({ ...s, state: 'ACTIVE', transferTarget: null });
    this.startDurationTimer();
  }

  // ── Telephony HTTP API ──────────────────────────────────────────────────────

  /** Answer an incoming call. callId is the Twilio Call SID (CA...). */
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
