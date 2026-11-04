import { DestroyRef, Injectable, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';
import { WebSocketService } from '../../../core/services/websocket.service';
import { ContactMessageReceivedPayload, WsEvent } from '../models/ws-event.model';
import { ContactTabStore } from './contact-tab.store';

/**
 * Feeds CONTACT_MESSAGE_RECEIVED into the "unread" state of the contact cards (FE-135).
 *
 * Root singleton: created once by the agent shell and alive until the app closes (its DestroyRef
 * is the root injector's, so the subscription is never torn down and is made exactly once). That
 * is intended: the store is emptied by `ContactTabStore.reset()` on logout, so a later session
 * starts without stale flags, and an event for a contact with no open card is ignored. A message
 * for the ACTIVE card only counts while the calendar covers it; a hidden browser tab or another
 * route is not detected (documented debt). An old backend never sends the event: nothing happens.
 */
@Injectable({ providedIn: 'root' })
export class ContactMessageListenerService {
  private readonly ws = inject(WebSocketService);
  private readonly tabStore = inject(ContactTabStore);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CONTACT_MESSAGE_RECEIVED'),
      )
      .subscribe((e) =>
        this.tabStore.noteMessageReceived(e.payload as ContactMessageReceivedPayload),
      );
  }
}
