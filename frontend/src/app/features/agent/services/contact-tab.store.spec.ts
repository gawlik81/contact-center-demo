import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpResponse } from '@angular/common/http';
import { of } from 'rxjs';
import { ContactTab } from '../models/contact-tab.model';
import { AgentRecoveryService } from './agent-recovery.service';
import {
  ContactTabStore,
  MAX_ASYNC_TABS,
  MAX_PHONE_TABS,
  MAX_TOTAL_TABS,
} from './contact-tab.store';
import { SoftphoneService } from './softphone.service';

function tabInput(id: string, type: ContactTab['type'], isActive?: boolean) {
  return {
    id,
    type,
    contactId: 'c-' + id,
    customerName: 'Klient ' + id,
    customerIdentifier: id + '@example.com',
    status: 'ACTIVE' as const,
    startedAt: new Date(),
    ...(isActive === undefined ? {} : { isActive }),
  };
}

describe('ContactTabStore (FE-133: a new contact does not take the active tab)', () => {
  let store: ContactTabStore;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [{ provide: HttpClient, useValue: {} }] });
    store = TestBed.inject(ContactTabStore);
    store.reset();
  });

  const activeIds = () =>
    store
      .tabs()
      .filter((t) => t.isActive)
      .map((t) => t.id);

  it('makes the very first tab active', () => {
    store.openTab(tabInput('a', 'EMAIL'));
    expect(activeIds()).toEqual(['a']);
  });

  it('adds a further contact inactive and keeps the current active tab', () => {
    store.openTab(tabInput('a', 'EMAIL'));
    store.openTab(tabInput('b', 'EMAIL'));
    store.openTab(tabInput('c', 'PHONE'));
    expect(store.tabs().map((t) => t.id)).toEqual(['a', 'b', 'c']);
    expect(activeIds()).toEqual(['a']);
    expect(store.activeTab()?.id).toBe('a');
  });

  it('keeps an explicitly chosen active tab when new contacts arrive', () => {
    store.openTab(tabInput('a', 'EMAIL'));
    store.openTab(tabInput('b', 'EMAIL'));
    store.setActiveTab('b');
    store.openFromContactAssigned({
      contactId: 'c-new',
      type: 'SOCIAL',
      customerName: 'Nowy',
      customerIdentifier: 'x',
    } as never);
    expect(activeIds()).toEqual(['b']);
  });

  it('activates a tab only when the caller explicitly asks for it (agent action)', () => {
    store.openTab(tabInput('a', 'EMAIL'));
    store.openTab(tabInput('b', 'EMAIL', true));
    expect(activeIds()).toEqual(['b']);
  });

  it('opens incoming and outbound calls inactive (ringing activation is done by the desktop)', () => {
    store.openTab(tabInput('a', 'EMAIL'));
    store.openFromCallIncoming({
      contactId: 'call-1',
      customerName: 'Jan',
      customerPhone: '+48123',
    } as never);
    expect(activeIds()).toEqual(['a']);
  });

  it('activates the neighbouring tab when the active one is closed', () => {
    store.openTab(tabInput('a', 'EMAIL'));
    store.openTab(tabInput('b', 'EMAIL'));
    store.openTab(tabInput('c', 'SOCIAL'));
    store.setActiveTab('b');
    store.closeTab('b');
    expect(activeIds()).toEqual(['c']);
    store.closeTab('c');
    expect(activeIds()).toEqual(['a']);
  });

  it('keeps the active tab when another one is closed', () => {
    store.openTab(tabInput('a', 'EMAIL'));
    store.openTab(tabInput('b', 'EMAIL'));
    store.closeTab('b');
    expect(activeIds()).toEqual(['a']);
  });

  describe('limits still apply', () => {
    it('rejects a second PHONE tab', () => {
      for (let i = 0; i < MAX_PHONE_TABS; i++) store.openTab(tabInput('p' + i, 'PHONE'));
      expect(store.openTab(tabInput('p-extra', 'PHONE'))).toBe('MAX_PHONE');
      expect(store.tabs()).toHaveLength(MAX_PHONE_TABS);
    });

    it('rejects a fourth e-mail/chat tab', () => {
      for (let i = 0; i < MAX_ASYNC_TABS; i++) store.openTab(tabInput('e' + i, 'EMAIL'));
      expect(store.openTab(tabInput('e-extra', 'CHAT'))).toBe('MAX_ASYNC');
    });

    it('rejects anything over the total limit', () => {
      store.openTab(tabInput('p', 'PHONE'));
      store.openTab(tabInput('e1', 'EMAIL'));
      store.openTab(tabInput('e2', 'EMAIL'));
      store.openTab(tabInput('s1', 'SOCIAL'));
      expect(store.tabs()).toHaveLength(MAX_TOTAL_TABS);
      expect(store.openTab(tabInput('s2', 'SOCIAL'))).toBe('MAX_TOTAL');
    });
  });
});

describe('ContactTabStore contact names and release tracking', () => {
  let store: ContactTabStore;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [{ provide: HttpClient, useValue: {} }] });
    store = TestBed.inject(ContactTabStore);
    store.reset();
  });

  it('normalises the name on every open path and on later updates', () => {
    store.openFromContactAssigned({
      contactId: 'c1',
      type: 'EMAIL',
      customerName: '"Anna Test" <anna@example.test>',
      customerIdentifier: 'anna@example.test',
    } as never);
    store.openTab(tabInput('x', 'EMAIL'));
    expect(store.tabs()[0].customerName).toBe('Anna Test');
    store.updateTabCustomerInfo('c1', '<anna@example.test>');
    expect(store.tabs()[0].customerName).toBe('anna@example.test');
  });

  it('keeps a re-assignment that arrives during a release and hands it back at the end', () => {
    const payload = { contactId: 'c1', type: 'EMAIL', customerName: 'A', customerIdentifier: 'a' };
    expect(store.noteAssignedDuringRelease(payload as never)).toBe(false);
    store.beginRelease('c1');
    expect(store.noteAssignedDuringRelease(payload as never)).toBe(true);
    expect(store.endRelease('c1')).toEqual(payload);
    expect(store.endRelease('c1')).toBeNull();
  });

  it('returns null at the end of a release without any re-assignment', () => {
    store.beginRelease('c1');
    expect(store.endRelease('c1')).toBeNull();
  });
});

describe('AgentRecoveryService with the inactive-by-default tabs', () => {
  let store: ContactTabStore;
  let recovery: AgentRecoveryService;
  let post: ReturnType<typeof vi.fn>;
  let get: ReturnType<typeof vi.fn>;

  function assigned(channel: string, id: string): void {
    get.mockReturnValue(
      of(
        new HttpResponse({
          status: 200,
          body: { contactId: id, channel, customerIdentifier: 'k@x.pl', customerName: 'Klient' },
        }),
      ),
    );
  }

  beforeEach(() => {
    post = vi.fn().mockReturnValue(of(null));
    get = vi.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: HttpClient, useValue: { get, post } }],
    });
    store = TestBed.inject(ContactTabStore);
    store.reset();
    TestBed.inject(SoftphoneService).session.set(null);
    recovery = TestBed.inject(AgentRecoveryService);
  });

  it('restores an e-mail contact as an inactive tab and accepts it', async () => {
    store.openTab(tabInput('a', 'EMAIL'));
    assigned('EMAIL', 'c-recovered');
    await recovery.recoverAfterReconnect();

    expect(store.tabs().map((t) => t.contactId)).toEqual(['c-a', 'c-recovered']);
    expect(store.activeTab()?.id).toBe('a');
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('makes a recovered contact the active tab when there was none', async () => {
    assigned('EMAIL', 'c-recovered');
    await recovery.recoverAfterReconnect();
    expect(store.activeTab()?.contactId).toBe('c-recovered');
  });

  it('does not open nor accept a contact over the limit', async () => {
    for (let i = 0; i < MAX_ASYNC_TABS; i++) store.openTab(tabInput('e' + i, 'EMAIL'));
    assigned('EMAIL', 'c-over');
    await recovery.recoverAfterReconnect();

    expect(store.tabs()).toHaveLength(MAX_ASYNC_TABS);
    expect(post).not.toHaveBeenCalled();
  });
});
