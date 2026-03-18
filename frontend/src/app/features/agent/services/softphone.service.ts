import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, of } from 'rxjs';
import { CallSession } from '../models/call-session.model';
import { CallIncomingPayload } from '../models/ws-event.model';
import { AuthService } from '../../../core/services/auth.service';
import { environment } from '../../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class SoftphoneService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  readonly session = signal<CallSession | null>(null);

  private durationInterval: ReturnType<typeof setInterval> | null = null;
  private cleanupTimeout: ReturnType<typeof setTimeout> | null = null;
  private transferTimeout: ReturnType<typeof setTimeout> | null = null;

  incomingCall(payload: CallIncomingPayload): void {
    this.clearTimers();
    this.session.set({
      contactId: payload.contactId,
      customerName: payload.customerName,
      customerPhone: payload.customerPhone,
      queueName: payload.queueName,
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
    this.simulateAction('ANSWER', s.contactId);
    const now = new Date();
    this.session.set({ ...s, state: 'ACTIVE', startedAt: now });
    this.startDurationTimer();
  }

  hangupCall(): void {
    const s = this.session();
    if (!s || s.state === 'ENDED' || s.state === 'RINGING') {
      this.clearTimers();
      this.session.set(null);
      return;
    }
    this.simulateAction('HANGUP', s.contactId);
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'ENDED' });
    this.cleanupTimeout = setTimeout(() => {
      this.session.set(null);
    }, 2000);
  }

  rejectCall(): void {
    const s = this.session();
    if (!s || s.state !== 'RINGING') return;
    this.simulateAction('HANGUP', s.contactId);
    this.clearTimers();
    this.session.set(null);
  }

  toggleMute(): void {
    const s = this.session();
    if (!s || (s.state !== 'ACTIVE' && s.state !== 'ON_HOLD')) return;
    this.session.set({ ...s, isMuted: !s.isMuted });
  }

  toggleHold(): void {
    const s = this.session();
    if (!s) return;
    if (s.state === 'ACTIVE') {
      this.simulateAction('HOLD', s.contactId);
      this.stopDurationTimer();
      this.session.set({ ...s, state: 'ON_HOLD', holdStartedAt: new Date() });
    } else if (s.state === 'ON_HOLD') {
      this.simulateAction('UNHOLD', s.contactId);
      const holdMs = s.holdStartedAt ? new Date().getTime() - s.holdStartedAt.getTime() : 0;
      const addedHoldSec = Math.floor(holdMs / 1000);
      this.session.set({
        ...s,
        state: 'ACTIVE',
        holdStartedAt: null,
        holdDuration: s.holdDuration + addedHoldSec,
      });
      this.startDurationTimer();
    }
  }

  initiateBlindTransfer(target: string): void {
    const s = this.session();
    if (!s || s.state !== 'ACTIVE') return;
    this.simulateAction('BLIND_TRANSFER', s.contactId, target);
    this.stopDurationTimer();
    this.session.set({ ...s, state: 'TRANSFERRING', transferTarget: target });
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

  private simulateAction(action: string, callId: string, to?: string): void {
    const tenantId = this.auth.currentTenantId();
    if (!tenantId) return;
    const body: Record<string, unknown> = { action, callId, tenantId };
    if (to) body['to'] = to;
    this.http
      .post(`${environment.apiUrl}/dev/telephony/simulate`, body)
      .pipe(catchError(() => of(null)))
      .subscribe();
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
