import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, catchError, of } from 'rxjs';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { SoftphoneService } from './softphone.service';
import { ContactTabStore } from './contact-tab.store';
import { ContactAssignedPayload } from '../models/ws-event.model';

export interface AssignedContactResponse {
  contactId: string;
  /** Contact channel: 'PHONE' | 'CHAT' | 'EMAIL' | 'SOCIAL' */
  channel: string;
  customerIdentifier: string;
  customerName: string;
  queueName?: string;
  customerId?: string;
}

/**
 * Recovers softphone state after a WebSocket reconnect.
 *
 * When the WS connection drops and re-establishes, events sent during the
 * outage are lost. This service queries the backend for any PHONE contact
 * in ASSIGNED state (i.e., ringing but not yet answered) and reconstructs
 * the RINGING softphone state as if the CONTACT_ASSIGNED WS event had just
 * arrived.
 *
 * Called once per WS connect event from AgentDesktopComponent.
 */
@Injectable({ providedIn: 'root' })
export class AgentRecoveryService {
  private readonly http = inject(HttpClient);
  private readonly softphoneService = inject(SoftphoneService);
  private readonly tabStore = inject(ContactTabStore);

  async recoverAfterReconnect(): Promise<void> {
    // Fast-path: softphone already has an active session — WS event arrived first
    if (this.softphoneService.session() !== null) return;

    // catchError returns null on any HTTP or network error — never throws
    const contact = await firstValueFrom(
      this.http
        .get<AssignedContactResponse>(`${environment.apiUrl}/agent/me/assigned-contact`, {
          observe: 'response',
        })
        .pipe(
          map((resp) => (resp.status === 200 ? resp.body : null)),
          catchError(() => of(null)),
        ),
    );

    if (!contact || contact.channel !== 'PHONE') return;

    // Double-check: WS event may have arrived while we were awaiting HTTP
    if (this.softphoneService.session() !== null) return;

    // Reconstruct the payload that would have arrived via CONTACT_ASSIGNED WS event
    const payload: ContactAssignedPayload = {
      contactId: contact.contactId,
      type: 'PHONE',
      customerName: contact.customerName || contact.customerIdentifier,
      customerIdentifier: contact.customerIdentifier,
      queueName: contact.queueName,
      customerId: contact.customerId,
    };

    const reason = this.tabStore.openFromContactAssigned(payload);
    if (reason === null) {
      this.softphoneService.incomingCall(payload);
      console.warn(
        '[AgentRecovery] Recovered pending PHONE contact after WS reconnect:',
        contact.contactId,
      );
    }
  }
}
