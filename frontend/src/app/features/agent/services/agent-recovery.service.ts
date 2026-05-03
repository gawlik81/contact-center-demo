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
 * Recovers agent desktop state after a WebSocket reconnect.
 *
 * When the WS connection drops and re-establishes, events sent during the
 * outage are lost. This service queries the backend for any contact
 * in ASSIGNED state (ringing/pending for the agent) and reconstructs
 * the desktop state as if the CONTACT_ASSIGNED WS event had just arrived.
 *
 * For EMAIL and CHAT contacts it also calls POST /api/contacts/{id}/accept
 * to transition the contact from ASSIGNED to ACTIVE in the backend, stopping
 * ContactAssignmentMonitor from re-queuing the contact.
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

    if (!contact) return;

    if (contact.channel === 'PHONE') {
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
      return;
    }

    if (contact.channel === 'EMAIL' || contact.channel === 'CHAT') {
      const type = contact.channel as 'EMAIL' | 'CHAT';

      // Check if this tab is already open (e.g. component already mounted)
      const alreadyOpen = this.tabStore.tabs().some((t) => t.contactId === contact.contactId);
      if (alreadyOpen) return;

      const payload: ContactAssignedPayload = {
        contactId: contact.contactId,
        type,
        customerName: contact.customerName || contact.customerIdentifier,
        customerIdentifier: contact.customerIdentifier,
        queueName: contact.queueName,
        customerId: contact.customerId,
      };

      const reason = this.tabStore.openFromContactAssigned(payload);
      if (reason === null) {
        // Notify ContactAssignmentMonitor that the contact was picked up.
        // Fire-and-forget — failure is non-critical; monitor will retry via WS.
        this.http
          .post(`${environment.apiUrl}/contacts/${contact.contactId}/accept`, {})
          .pipe(catchError(() => of(null)))
          .subscribe();

        console.warn(
          '[AgentRecovery] Recovered pending',
          type,
          'contact after WS reconnect:',
          contact.contactId,
        );
      }
    }
  }
}
