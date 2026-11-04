import { TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { Subject, of } from 'rxjs';
import { WebSocketService } from '../../../core/services/websocket.service';
import { WsEvent } from '../models/ws-event.model';
import { ContactMessageListenerService } from './contact-message-listener.service';
import { ContactTabStore } from './contact-tab.store';

describe('ContactMessageListenerService (FE-135)', () => {
  let events$: Subject<WsEvent>;
  let store: ContactTabStore;

  beforeEach(() => {
    events$ = new Subject<WsEvent>();
    TestBed.configureTestingModule({
      providers: [
        { provide: WebSocketService, useValue: { events$ } },
        { provide: HttpClient, useValue: { post: () => of(null) } },
      ],
    });
    store = TestBed.inject(ContactTabStore);
    store.reset();
    for (const id of ['a', 'b']) {
      store.openTab({
        id,
        type: 'EMAIL',
        contactId: 'c-' + id,
        customerName: id,
        customerIdentifier: id,
        status: 'ACTIVE',
        startedAt: new Date(),
      });
    }
    TestBed.inject(ContactMessageListenerService);
  });

  it('flags the inactive tab on CONTACT_MESSAGE_RECEIVED', () => {
    events$.next({
      eventType: 'CONTACT_MESSAGE_RECEIVED',
      payload: { contactId: 'c-b', channel: 'EMAIL', messageId: 'm', receivedAt: 'x' },
    });
    expect(store.unreadContactIds().has('c-b')).toBe(true);
  });

  it('ignores other events (old backend: nothing arrives, nothing fails)', () => {
    events$.next({ eventType: 'PONG', payload: null });
    expect(store.unreadContactIds().size).toBe(0);
  });

  it('ignores a message of another channel than the open card', () => {
    events$.next({
      eventType: 'CONTACT_MESSAGE_RECEIVED',
      payload: { contactId: 'c-b', channel: 'SOCIAL', messageId: 'm', receivedAt: 'x' },
    });
    expect(store.unreadContactIds().size).toBe(0);
  });

  it('subscribes once even when injected again (shell re-created after logout)', () => {
    const before = events$.observers.length;
    TestBed.inject(ContactMessageListenerService);
    expect(events$.observers.length).toBe(before);
    expect(before).toBe(1);
  });
});
