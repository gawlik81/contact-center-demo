import { TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { ContactTab } from '../models/contact-tab.model';
import { ContactDraftStore } from './contact-draft.store';
import { ContactTabStore, MAX_PHONE_TABS, MAX_TOTAL_TABS } from './contact-tab.store';

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
    TestBed.configureTestingModule({
      providers: [{ provide: HttpClient, useValue: { post: () => of(null) } }],
    });
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

    it('never refuses an async contact for its own limit, only for the strip capacity', () => {
      // MAX_TOTAL_TABS - MAX_PHONE_TABS async cards fit; one slot stays free for a call
      const asyncCapacity = MAX_TOTAL_TABS - MAX_PHONE_TABS;
      const types = ['EMAIL', 'CHAT', 'SOCIAL', 'EMAIL', 'SOCIAL'] as const;
      for (let i = 0; i < asyncCapacity; i++) {
        expect(store.openTab(tabInput('a' + i, types[i]))).toBeNull();
      }
      expect(store.openTab(tabInput('over', 'EMAIL'))).toBe('MAX_TOTAL');
      expect(store.openTab(tabInput('over-s', 'SOCIAL'))).toBe('MAX_TOTAL');
      // the reserved slot still takes a call
      expect(store.openTab(tabInput('call', 'PHONE'))).toBeNull();
      expect(store.tabs()).toHaveLength(MAX_TOTAL_TABS);
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
    TestBed.configureTestingModule({
      providers: [{ provide: HttpClient, useValue: { post: () => of(null) } }],
    });
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

describe('ContactTabStore close log (FE-136)', () => {
  it('reset() forgets what the previous agent closed', () => {
    TestBed.configureTestingModule({
      providers: [{ provide: HttpClient, useValue: { post: () => of(null) } }],
    });
    const store = TestBed.inject(ContactTabStore);
    store.reset();
    const mark = store.closeMark();
    store.openTab(tabInput('a', 'EMAIL'));
    store.closeByContactId('c-a');
    expect(store.closedSince(mark).has('c-a')).toBe(true);
    store.reset();
    expect(store.closedSince(0).size).toBe(0);
    expect(store.closeMark()).toBe(0);
  });
});

describe('ContactTabStore ACW state (FE-122)', () => {
  let store: ContactTabStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: HttpClient, useValue: { post: () => of(null) } }],
    });
    store = TestBed.inject(ContactTabStore);
    store.reset();
  });

  it('stamps the start of ACW once and lists the ACW tabs even when they are not active', () => {
    store.openTab(tabInput('m', 'EMAIL'));
    store.openTab(tabInput('p', 'PHONE'));
    expect(store.acwTabs()).toEqual([]);

    store.markAsWrapping('p');
    const since = store.tabs().find((t) => t.id === 'p')!.wrappingSince;
    expect(since).toBeTypeOf('number');
    expect(store.activeTab()?.id).toBe('m');
    expect(store.acwTabs().map((t) => t.id)).toEqual(['p']);
    expect(store.wrappingTab()).toBeNull(); // only the ACTIVE tab counts as "in front"

    store.markAsWrapping('p'); // repeated call must not restart the timer
    expect(store.tabs().find((t) => t.id === 'p')!.wrappingSince).toBe(since);
  });

  it('hands a focus request to the form exactly once and only to its tab', () => {
    store.requestAcwFocus('p');
    expect(store.consumeAcwFocus('other')).toBe(false);
    expect(store.consumeAcwFocus('p')).toBe(true);
    expect(store.consumeAcwFocus('p')).toBe(false);
  });

  it('closing the ACW tab drops its disposition draft through the single cleanup point', () => {
    const drafts = TestBed.inject(ContactDraftStore);
    store.openTab(tabInput('p', 'PHONE'));
    store.markAsWrapping('p');
    drafts.setDispositionDraft('c-p', { code: 'SOLD', notes: '' });
    store.closeTab('p');
    expect(drafts.dispositionDraft('c-p')).toBeNull();
  });

  it('drops wrappingSince when the tab leaves WRAPPING, so a later ACW starts from zero', () => {
    vi.useFakeTimers();
    try {
      store.openTab(tabInput('p', 'PHONE'));
      vi.setSystemTime(1_000_000);
      store.markAsWrapping('p');
      expect(store.tabs()[0].wrappingSince).toBe(1_000_000);

      store.updateTabStatus('p', 'ACTIVE');
      expect(store.tabs()[0].wrappingSince).toBeUndefined();

      vi.setSystemTime(5_000_000);
      store.markAsWrapping('p');
      expect(store.tabs()[0].wrappingSince).toBe(5_000_000);
    } finally {
      vi.useRealTimers();
    }
  });

  it('lists ACW tabs longest-waiting first and shares the calendar flag, reset on logout', () => {
    store.openTab(tabInput('a', 'PHONE'));
    store.tabs.update((t) => [
      ...t,
      {
        ...t[0],
        id: 'b',
        contactId: 'c-b',
        status: 'WRAPPING',
        wrappingSince: 100,
        isActive: false,
      },
    ]);
    store.tabs.update((t) =>
      t.map((x) => (x.id === 'a' ? { ...x, status: 'WRAPPING', wrappingSince: 500 } : x)),
    );
    expect(store.acwTabs().map((t) => t.id)).toEqual(['b', 'a']);

    store.calendarOpen.set(true);
    store.reset();
    expect(store.calendarOpen()).toBe(false);
  });
});

describe('ContactTabStore unread state (FE-135)', () => {
  let store: ContactTabStore;
  const msg = (contactId: string, channel: 'EMAIL' | 'SOCIAL' = 'EMAIL') => ({
    contactId,
    channel,
    messageId: 'm',
    receivedAt: '2026-11-04T09:00:00Z',
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: HttpClient, useValue: { post: () => of(null) } }],
    });
    store = TestBed.inject(ContactTabStore);
    store.reset();
    store.openTab(tabInput('a', 'EMAIL'));
    store.openTab(tabInput('b', 'EMAIL'));
    store.openTab(tabInput('s', 'SOCIAL'));
    store.openTab(tabInput('p', 'PHONE'));
  });

  it('flags an inactive e-mail / social tab', () => {
    store.noteMessageReceived(msg('c-b'));
    store.noteMessageReceived(msg('c-s', 'SOCIAL'));
    expect([...store.unreadContactIds()].sort()).toEqual(['c-b', 'c-s']);
  });

  it('ignores the active tab, unknown contacts, PHONE tabs and a mismatching channel', () => {
    store.noteMessageReceived(msg('c-a'));
    store.noteMessageReceived(msg('c-zzz'));
    store.noteMessageReceived(msg('c-p'));
    store.noteMessageReceived(msg('c-s', 'EMAIL'));
    expect(store.unreadContactIds().size).toBe(0);
  });

  it('clears the flag when the tab is activated', () => {
    store.noteMessageReceived(msg('c-b'));
    store.setActiveTab('b');
    expect(store.unreadContactIds().size).toBe(0);
    // a later message in the (now active) tab is not flagged either
    store.noteMessageReceived(msg('c-b'));
    expect(store.unreadContactIds().size).toBe(0);
    // ...but after switching away it can be flagged again
    store.setActiveTab('a');
    store.noteMessageReceived(msg('c-b'));
    expect(store.unreadContactIds().has('c-b')).toBe(true);
  });

  it('clears the flag when the tab closes', () => {
    store.noteMessageReceived(msg('c-b'));
    store.closeTab('b');
    expect(store.unreadContactIds().size).toBe(0);
  });

  it('closing the active tab activates its neighbour, which clears that neighbour flag only', () => {
    store.noteMessageReceived(msg('c-b'));
    store.noteMessageReceived(msg('c-s', 'SOCIAL'));
    store.closeTab('a'); // 'b' becomes active
    expect([...store.unreadContactIds()]).toEqual(['c-s']);
  });

  it('closing an unrelated inactive tab keeps the flag', () => {
    store.noteMessageReceived(msg('c-s', 'SOCIAL'));
    store.closeTab('p');
    expect(store.unreadContactIds().has('c-s')).toBe(true);
  });

  it('is dropped by reset()', () => {
    store.noteMessageReceived(msg('c-b'));
    store.reset();
    expect(store.unreadContactIds().size).toBe(0);
  });

  it('flags the active tab while the calendar covers it and clears it on return', () => {
    store.calendarOpen.set(true);
    store.noteMessageReceived(msg('c-a')); // 'a' is the active tab
    expect(store.unreadContactIds().has('c-a')).toBe(true);

    TestBed.tick();
    expect(store.unreadContactIds().has('c-a')).toBe(true); // still hidden: stays flagged

    store.calendarOpen.set(false);
    TestBed.tick();
    expect(store.unreadContactIds().size).toBe(0);
  });

  it('keeps an inactive tab flag when the calendar closes', () => {
    store.calendarOpen.set(true);
    store.noteMessageReceived(msg('c-b'));
    store.calendarOpen.set(false);
    TestBed.tick();
    expect(store.unreadContactIds().has('c-b')).toBe(true);
  });
});
