import { signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { NotificationService } from '../../../core/services/notification.service';
import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { NEVER, Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActiveContactResponse } from '../models/active-contact.model';
import {
  AgentRecoveryService,
  CALL_NOT_RESTORED_POLL_MS,
  RECOVERY_TIMEOUT_MS,
} from './agent-recovery.service';
import { ACCEPT_RETRY_BASE_MS } from './async-contact-accept.service';
import { AuthService } from '../../../core/services/auth.service';
import { ContactConflictService } from './contact-conflict.service';
import { ContactDraftStore } from './contact-draft.store';
import { AgentStatusService } from './agent-status.service';
import { ContactTabStore } from './contact-tab.store';
import { SoftphoneService } from './softphone.service';

function tabInput(id: string, type: 'EMAIL' | 'SOCIAL' | 'PHONE') {
  return {
    id,
    type,
    contactId: 'c-' + id,
    customerName: 'Klient ' + id,
    customerIdentifier: id + '@example.com',
    status: 'ACTIVE' as const,
    startedAt: new Date(),
  };
}

function contact(over: Partial<ActiveContactResponse> & { contactId: string }) {
  return {
    channel: 'EMAIL',
    direction: 'INBOUND',
    status: 'ACTIVE',
    phase: 'IN_PROGRESS',
    customerIdentifier: 'k@x.pl',
    customerName: 'Klient ' + over.contactId,
    queueName: 'Support',
    ...over,
  } as ActiveContactResponse;
}

describe('AgentRecoveryService (FE-136)', () => {
  let service: AgentRecoveryService;
  let tabs: ContactTabStore;
  let drafts: ContactDraftStore;
  let softphone: SoftphoneService;
  let get: ReturnType<typeof vi.fn>;
  let post: ReturnType<typeof vi.fn>;
  let agentStatus: ReturnType<typeof signal<string>>;
  let changeStatus: ReturnType<typeof vi.fn>;
  let statusEpisode: ReturnType<typeof signal<number>>;
  const notify = { info: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() };

  const respond = (list: ActiveContactResponse[]) => get.mockReturnValue(of(list));

  beforeEach(() => {
    get = vi.fn();
    Object.values(notify).forEach((f) => f.mockReset());
    post = vi.fn().mockReturnValue(of(null));
    agentStatus = signal('OFFLINE');
    changeStatus = vi.fn().mockReturnValue(of(undefined));
    statusEpisode = signal(0);
    TestBed.configureTestingModule({
      providers: [
        { provide: HttpClient, useValue: { get, post } },
        { provide: NotificationService, useValue: notify },
        { provide: TranslocoService, useValue: { translate: (k: string) => k } },
        {
          provide: AgentStatusService,
          useValue: { currentStatus: agentStatus, statusEpisode, changeStatus },
        },
      ],
    });
    tabs = TestBed.inject(ContactTabStore);
    tabs.reset();
    drafts = TestBed.inject(ContactDraftStore);
    softphone = TestBed.inject(SoftphoneService);
    softphone.session.set(null);
    service = TestBed.inject(AgentRecoveryService);
  });

  afterEach(() => vi.useRealTimers());

  it('calls the new endpoint once and restores every phase from that single answer', async () => {
    respond([
      contact({ contactId: 'ring', channel: 'PHONE', phase: 'RINGING', status: 'ASSIGNED' }),
      contact({ contactId: 'mail', channel: 'EMAIL', phase: 'IN_PROGRESS' }),
      contact({ contactId: 'soc', channel: 'SOCIAL', phase: 'IN_PROGRESS' }),
    ]);
    await service.recoverAfterReconnect();

    expect(get).toHaveBeenCalledTimes(1);
    expect(get.mock.calls[0][0]).toContain('/agent/me/active-contacts');
    expect(tabs.tabs().map((t) => t.contactId)).toEqual(['ring', 'mail', 'soc']);
    expect(softphone.session()?.state).toBe('RINGING');
    expect(service.overflow()).toEqual([]);
  });

  it('keeps the backend order (assignedAt)', async () => {
    respond([
      contact({ contactId: 'b', channel: 'SOCIAL' }),
      contact({ contactId: 'a', channel: 'EMAIL' }),
    ]);
    await service.recoverAfterReconnect();
    expect(tabs.tabs().map((t) => t.contactId)).toEqual(['b', 'a']);
  });

  it('RINGING e-mail is accepted once; IN_PROGRESS e-mail is never accepted again', async () => {
    respond([
      contact({ contactId: 'new', phase: 'RINGING', status: 'ASSIGNED' }),
      contact({ contactId: 'old', phase: 'IN_PROGRESS', status: 'ACTIVE' }),
    ]);
    await service.recoverAfterReconnect();

    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][0]).toContain('/contacts/new/accept');
    // the card's own first-mount accept (FE-131 guard) is already spent for both
    expect(drafts.markAccepted('new')).toBe(false);
    expect(drafts.markAccepted('old')).toBe(false);
  });

  it('does not accept a RINGING SOCIAL contact again after the live path already did', async () => {
    drafts.markAccepted('soc'); // what AsyncContactAcceptService does on the live path
    respond([
      contact({ contactId: 'soc', channel: 'SOCIAL', phase: 'RINGING', status: 'ASSIGNED' }),
    ]);
    await service.recoverAfterReconnect();
    expect(post).not.toHaveBeenCalled();
  });

  it('live path then recovery sends one accept in total (no manual mark)', async () => {
    tabs.openFromContactAssigned({
      contactId: 'soc',
      type: 'SOCIAL',
      customerName: 'K',
      customerIdentifier: 'k',
    } as never);
    expect(post).toHaveBeenCalledTimes(1);
    tabs.tabs.set([]); // the card is gone (e.g. WS reconnect) but the accept was already sent
    respond([
      contact({ contactId: 'soc', channel: 'SOCIAL', phase: 'RINGING', status: 'ASSIGNED' }),
    ]);
    await service.recoverAfterReconnect();
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('accepts a RINGING SOCIAL contact exactly once over repeated recovery runs', async () => {
    respond([
      contact({ contactId: 'soc', channel: 'SOCIAL', phase: 'RINGING', status: 'ASSIGNED' }),
    ]);
    await service.recoverAfterReconnect();
    tabs.reset();
    await service.recoverAfterReconnect();
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][0]).toContain('/contacts/soc/accept');
  });

  it('reverts the accepted flag when the accept request fails', async () => {
    vi.useFakeTimers();
    post.mockReturnValue(throwError(() => ({ status: 503 })));
    respond([contact({ contactId: 'new', phase: 'RINGING', status: 'ASSIGNED' })]);
    await service.recoverAfterReconnect();
    await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 3);
    expect(drafts.markAccepted('new')).toBe(true);
  });

  it('restores a PHONE call in progress as a card without a live call', async () => {
    respond([contact({ contactId: 'p', channel: 'PHONE', direction: 'OUTBOUND' })]);
    await service.recoverAfterReconnect();

    const tab = tabs.tabs()[0];
    expect(tab.type).toBe('PHONE');
    expect(tab.status).toBe('ACTIVE');
    expect(tab.direction).toBe('OUTBOUND');
    expect(tab.callNotRestored).toBe(true);
    expect(softphone.session()).toBeNull();
    expect(post).not.toHaveBeenCalled();
  });

  it('restores WRAP_UP as an ACW card whose timer starts at wrapUpStartedAt', async () => {
    const at = '2026-10-31T10:00:00Z';
    respond([
      contact({
        contactId: 'w',
        channel: 'PHONE',
        phase: 'WRAP_UP',
        status: 'COMPLETED',
        wrapUpStartedAt: at,
        endedAt: at,
      }),
    ]);
    await service.recoverAfterReconnect();

    const tab = tabs.tabs()[0];
    expect(tab.status).toBe('WRAPPING');
    expect(tab.wrappingSince).toBe(Date.parse(at));
    expect(tabs.acwTabs().map((t) => t.contactId)).toEqual(['w']);
    expect(tab.callNotRestored).toBeUndefined();
    expect(softphone.session()).toBeNull();
  });

  it('an ended call turns an already open "not restored" PHONE card into ACW', async () => {
    respond([contact({ contactId: 'p', channel: 'PHONE' })]);
    await service.recoverAfterReconnect();
    respond([contact({ contactId: 'p', channel: 'PHONE', phase: 'WRAP_UP', status: 'COMPLETED' })]);
    await service.recoverAfterReconnect();

    expect(tabs.tabs()).toHaveLength(1);
    expect(tabs.tabs()[0].status).toBe('WRAPPING');
    expect(tabs.tabs()[0].callNotRestored).toBeUndefined();
  });

  it('does not duplicate cards that are already open', async () => {
    respond([contact({ contactId: 'mail' })]);
    await service.recoverAfterReconnect();
    await service.recoverAfterReconnect();
    expect(tabs.tabs()).toHaveLength(1);
  });

  it('a live softphone session wins over a recovered call', async () => {
    softphone.incomingCall({
      contactId: 'live',
      customerName: 'x',
      customerPhone: '1',
      queueName: '',
    });
    respond([
      contact({ contactId: 'other', channel: 'PHONE', phase: 'RINGING', status: 'ASSIGNED' }),
    ]);
    await service.recoverAfterReconnect();
    expect(tabs.tabs()).toEqual([]);
    expect(softphone.session()?.contactId).toBe('live');
  });

  describe('tab limits', () => {
    it('lists contacts over the strip capacity instead of dropping them', async () => {
      respond(['a', 'b', 'c', 'd', 'e'].map((id) => contact({ contactId: id, channel: 'EMAIL' })));
      await service.recoverAfterReconnect();

      // 4 slots, one stays reserved for PHONE: three async cards, the rest waits in the list
      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['a', 'b', 'c']);
      expect(service.overflow().map((o) => [o.contactId, o.reason])).toEqual([
        ['d', 'MAX_TOTAL'],
        ['e', 'MAX_TOTAL'],
      ]);
      // IN_PROGRESS contacts are already accepted by the backend: nothing is sent for them
      expect(post).not.toHaveBeenCalled();
    });

    it('a RINGING contact without a free slot is accepted anyway (never left to the monitor)', async () => {
      respond([
        ...['a', 'b', 'c'].map((id) => contact({ contactId: id })),
        contact({ contactId: 'late', phase: 'RINGING', status: 'ASSIGNED' }),
      ]);
      await service.recoverAfterReconnect();
      expect(service.overflow().map((o) => o.contactId)).toEqual(['late']);
      expect(post).toHaveBeenCalledTimes(1);
      expect(post.mock.calls[0][0]).toContain('/contacts/late/accept');
    });

    it('SOCIAL counts like EMAIL and CHAT', async () => {
      respond([
        contact({ contactId: 'a', channel: 'EMAIL' }),
        contact({ contactId: 'b', channel: 'CHAT' }),
        contact({ contactId: 's', channel: 'SOCIAL' }),
        contact({ contactId: 's2', channel: 'SOCIAL' }),
      ]);
      await service.recoverAfterReconnect();
      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['a', 'b', 's']);
      expect(service.overflow().map((o) => [o.contactId, o.reason])).toEqual([['s2', 'MAX_TOTAL']]);
    });

    it('an open call gets the single PHONE slot before an older ACW, cards keep backend order', async () => {
      respond([
        contact({ contactId: 'acw', channel: 'PHONE', phase: 'WRAP_UP', status: 'COMPLETED' }),
        contact({ contactId: 'mail', channel: 'EMAIL' }),
        contact({ contactId: 'ring', channel: 'PHONE', phase: 'RINGING', status: 'ASSIGNED' }),
      ]);
      await service.recoverAfterReconnect();

      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['mail', 'ring']);
      expect(service.overflow().map((o) => [o.contactId, o.phase, o.reason])).toEqual([
        ['acw', 'WRAP_UP', 'MAX_PHONE'],
      ]);
    });

    it('"check again" opens the overflow once a slot is free and clears the list', async () => {
      respond(['a', 'b', 'c', 'd'].map((id) => contact({ contactId: id })));
      await service.recoverAfterReconnect();
      expect(service.overflow()).toHaveLength(1);

      // 'a' was returned to the queue, so the backend no longer lists it
      tabs.closeByContactId('a');
      respond(['b', 'c', 'd'].map((id) => contact({ contactId: id })));
      await service.recoverAfterReconnect();
      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['b', 'c', 'd']);
      expect(service.overflow()).toEqual([]);
    });
  });

  describe('parked contacts: backend is the source of truth (FE-163)', () => {
    const assigned = (id: string, type: 'EMAIL' | 'SOCIAL' | 'CHAT' = 'EMAIL') =>
      ({
        contactId: id,
        type,
        customerName: 'Klient ' + id,
        customerIdentifier: id + '@x.pl',
        queueName: 'Support',
      }) as never;

    const settle = async () => {
      for (let i = 0; i < 8; i++) await Promise.resolve();
      TestBed.tick();
    };

    const fill = (n: number) => {
      for (let i = 0; i < n; i++) tabs.openTab(tabInput('t' + i, 'EMAIL'));
    };

    it('agent in two queues with limit 3 (6 async): none rejected, none given back by the front', async () => {
      const ids = ['q1a', 'q1b', 'q1c', 'q2a', 'q2b', 'q2c'];
      const types = ['EMAIL', 'CHAT', 'SOCIAL', 'EMAIL', 'SOCIAL', 'CHAT'] as const;
      ids.forEach((id, i) => {
        const reason = tabs.openFromContactAssigned(assigned(id, types[i]));
        if (reason !== null) service.parkAssigned(assigned(id, types[i]), reason);
      });
      TestBed.tick();

      expect(tabs.tabs()).toHaveLength(3); // layout: 4 slots, one kept for a call
      expect(service.overflow()).toHaveLength(3);
      expect(
        [
          ...tabs.tabs().map((t) => t.contactId),
          ...service.overflow().map((o) => o.contactId),
        ].sort(),
      ).toEqual([...ids].sort());
      // all six confirmed to the backend exactly once; nothing released
      const urls = post.mock.calls.map((c) => c[0] as string);
      expect(urls.filter((u) => u.endsWith('/accept'))).toHaveLength(6);
      expect(urls.some((u) => u.endsWith('/release'))).toBe(false);
    });

    it('parkAssigned is idempotent: a re-delivery adds no row and no second accept', () => {
      service.parkAssigned(assigned('x'), 'MAX_TOTAL');
      service.parkAssigned(assigned('x'), 'MAX_TOTAL');
      expect(service.overflow()).toHaveLength(1);
      expect(post).toHaveBeenCalledTimes(1);
      expect(service.isParked('x')).toBe(true);
    });

    it('promotes the first parked contact into a card when a slot frees up (list order)', async () => {
      fill(3);
      service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
      service.parkAssigned(assigned('p2', 'SOCIAL'), 'MAX_TOTAL');
      TestBed.tick();
      expect(service.overflow()).toHaveLength(2);

      tabs.closeTab('t0');
      TestBed.tick();
      await settle();

      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['c-t1', 'c-t2', 'p1']);
      expect(service.overflow().map((o) => o.contactId)).toEqual(['p2']);
      // not a new accept: it was confirmed when it was parked
      expect(post.mock.calls.filter((c) => (c[0] as string).endsWith('/p1/accept'))).toHaveLength(
        1,
      );
      expect(notify.info).toHaveBeenCalledWith('agent.recoveryOverflow.promoted');
    });

    it('a promoted card never steals the active tab', async () => {
      fill(3);
      tabs.setActiveTab('t1');
      service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
      tabs.closeTab('t2');
      TestBed.tick();
      await settle();
      expect(tabs.activeTab()?.id).toBe('t1');
      expect(tabs.tabs().some((t) => t.contactId === 'p1')).toBe(true);
    });

    it('does not promote while no slot is free', () => {
      fill(3);
      service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
      TestBed.tick();
      expect(service.overflow()).toHaveLength(1);
      expect(tabs.tabs()).toHaveLength(3);
    });

    it('"return to queue" releases the contact and removes the row', async () => {
      service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
      post.mockClear();
      await service.returnToQueue('p1');
      expect(post).toHaveBeenCalledTimes(1);
      expect(post.mock.calls[0][0]).toContain('/contacts/p1/release');
      expect(service.overflow()).toEqual([]);
      expect(notify.success).toHaveBeenCalledWith('agent.desktop.tabClose.returned');
      // a recovery answer that left before the release must not bring it back
      respond([contact({ contactId: 'p1' })]);
      expect(tabs.closedSince(0).has('p1')).toBe(true);
    });

    it('keeps the row and says so when the release fails (5xx)', async () => {
      service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
      post.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      await service.returnToQueue('p1');
      expect(service.overflow().map((o) => o.contactId)).toEqual(['p1']);
      expect(notify.error).toHaveBeenCalledWith('agent.recoveryOverflow.returnError');
      expect(service.returningIds().size).toBe(0);
    });

    it("drops the row with an explanation on 409 (no longer this agent's)", async () => {
      service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
      post.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409 })));
      await service.returnToQueue('p1');
      expect(service.overflow()).toEqual([]);
      expect(notify.success).toHaveBeenCalledWith('agent.desktop.tabClose.notYours');
    });

    it('re-opens (or parks) a contact the backend handed back during the release', async () => {
      fill(3);
      service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
      const release = new Subject<null>();
      post.mockReturnValue(release);
      const done = service.returnToQueue('p1');
      // the same contact is assigned again while the release is pending
      expect(tabs.noteAssignedDuringRelease(assigned('p1'))).toBe(true);
      release.next(null);
      release.complete();
      await done;
      expect(service.overflow().map((o) => o.contactId)).toEqual(['p1']);
    });

    it('a recovery run keeps contacts that still have no slot and accepts a ringing one', async () => {
      fill(3);
      respond([contact({ contactId: 'r', phase: 'RINGING', status: 'ASSIGNED' })]);
      await service.recoverAfterReconnect();
      expect(service.overflow().map((o) => o.contactId)).toEqual(['r']);
      expect(post.mock.calls.some((c) => (c[0] as string).endsWith('/r/accept'))).toBe(true);
    });
  });

  describe('failures never block the desktop', () => {
    it('keeps the desktop and resolves on a server error', async () => {
      get.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      await expect(service.recoverAfterReconnect()).resolves.toBeUndefined();
      expect(tabs.tabs()).toEqual([]);
      expect(service.loading()).toBe(false);
    });

    it('gives up after the timeout', async () => {
      vi.useFakeTimers();
      vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      get.mockReturnValue(NEVER);
      const done = service.recoverAfterReconnect();
      await vi.advanceTimersByTimeAsync(RECOVERY_TIMEOUT_MS + 1);
      await done;
      expect(service.loading()).toBe(false);
      expect(tabs.tabs()).toEqual([]);
    });

    it('on 404 falls back to assigned-contact (RINGING PHONE)', async () => {
      get.mockImplementation((url: string) =>
        url.includes('active-contacts')
          ? throwError(() => new HttpErrorResponse({ status: 404 }))
          : of(
              new HttpResponse({
                status: 200,
                body: {
                  contactId: 'legacy',
                  channel: 'PHONE',
                  customerIdentifier: '+48123',
                  customerName: 'Anna',
                },
              }),
            ),
      );
      await service.recoverAfterReconnect();

      expect(get).toHaveBeenCalledTimes(2);
      expect(get.mock.calls[1][0]).toContain('/agent/me/assigned-contact');
      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['legacy']);
      expect(softphone.session()?.state).toBe('RINGING');
    });

    it('fallback 204 means nothing to restore', async () => {
      get.mockImplementation((url: string) =>
        url.includes('active-contacts')
          ? throwError(() => new HttpErrorResponse({ status: 404 }))
          : of(new HttpResponse({ status: 204, body: null })),
      );
      await service.recoverAfterReconnect();
      expect(tabs.tabs()).toEqual([]);
    });
  });

  describe('session boundaries (logout -> login without a page reload)', () => {
    it('reset() discards an answer that arrives after the session ended', async () => {
      const answer = new Subject<ActiveContactResponse[]>();
      get.mockReturnValue(answer);
      const pending = service.recoverAfterReconnect();
      service.reset();
      answer.next([contact({ contactId: 'old-agent' })]);
      answer.complete();
      await pending;
      expect(tabs.tabs()).toEqual([]);
    });

    it('after reset() a new recovery runs again and restores the cards', async () => {
      respond([contact({ contactId: 'mail' })]);
      await service.recoverAfterReconnect();
      tabs.reset();
      service.reset();
      await service.recoverAfterReconnect();
      expect(get).toHaveBeenCalledTimes(2);
      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['mail']);
    });
  });

  it('writes nothing to browser storage', async () => {
    const local = vi.spyOn(Storage.prototype, 'setItem');
    respond([contact({ contactId: 'mail' }), contact({ contactId: 'p', channel: 'PHONE' })]);
    await service.recoverAfterReconnect();
    expect(local).not.toHaveBeenCalled();
    local.mockRestore();
  });

  // ── FE-136 round 2 ─────────────────────────────────────────────────────────

  it('concurrent calls share one request', async () => {
    respond([contact({ contactId: 'mail' })]);
    await Promise.all([service.recoverAfterReconnect(), service.recoverAfterReconnect()]);
    expect(get).toHaveBeenCalledTimes(1);
  });

  it('maps SOCIAL_* channels to SOCIAL', async () => {
    respond([contact({ contactId: 's', channel: 'SOCIAL_FACEBOOK' })]);
    await service.recoverAfterReconnect();
    expect(tabs.tabs()[0].type).toBe('SOCIAL');
  });

  it('does not reopen nor accept a contact whose release is in flight', async () => {
    tabs.beginRelease('m');
    respond([contact({ contactId: 'm', phase: 'RINGING', status: 'ASSIGNED' })]);
    await service.recoverAfterReconnect();
    expect(tabs.tabs()).toEqual([]);
    expect(post).not.toHaveBeenCalled();
    expect(tabs.endRelease('m')?.contactId).toBe('m');
  });

  describe('nothing is dropped silently', () => {
    it('an unknown channel goes to the overflow with its own reason', async () => {
      vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      respond([contact({ contactId: 'u', channel: 'FAX' })]);
      await service.recoverAfterReconnect();
      expect(tabs.tabs()).toEqual([]);
      expect(service.overflow()).toEqual([
        expect.objectContaining({ contactId: 'u', type: null, reason: 'UNSUPPORTED_CHANNEL' }),
      ]);
    });

    it('a PHONE contact next to a live call of another contact goes to the overflow', async () => {
      softphone.incomingCall({
        contactId: 'live',
        customerName: 'x',
        customerPhone: '1',
        queueName: '',
      });
      respond([contact({ contactId: 'orphan', channel: 'PHONE', phase: 'IN_PROGRESS' })]);
      await service.recoverAfterReconnect();
      expect(service.overflow().map((o) => [o.contactId, o.reason])).toEqual([
        ['orphan', 'OTHER_CALL_ACTIVE'],
      ]);
    });
  });

  describe('slot priority', () => {
    it('a PHONE contact (even in ACW) beats e-mail/social for the last total slot', async () => {
      tabs.openTab({ ...tabInput('a', 'EMAIL') });
      tabs.openTab({ ...tabInput('b', 'EMAIL') });
      tabs.openTab({ ...tabInput('c', 'SOCIAL') });
      respond([
        contact({ contactId: 'x', channel: 'EMAIL' }),
        contact({ contactId: 'p', channel: 'PHONE', phase: 'WRAP_UP', status: 'COMPLETED' }),
        contact({ contactId: 'c-a' }),
        contact({ contactId: 'c-b' }),
        contact({ contactId: 'c-c', channel: 'SOCIAL' }),
      ]);
      await service.recoverAfterReconnect();

      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['c-a', 'c-b', 'c-c', 'p']);
      expect(service.overflow().map((o) => [o.contactId, o.reason])).toEqual([['x', 'MAX_TOTAL']]);
    });

    it('the ACW takes the reserved PHONE slot before another e-mail; the e-mail follows once a card closes', async () => {
      for (const id of ['a', 'b']) tabs.openTab(tabInput(id, 'EMAIL'));
      tabs.openTab(tabInput('s', 'SOCIAL'));
      const backend = () => [
        contact({ contactId: 'x', channel: 'EMAIL' }),
        contact({ contactId: 'p', channel: 'PHONE', phase: 'WRAP_UP', status: 'COMPLETED' }),
        contact({ contactId: 'c-a' }),
        contact({ contactId: 'c-b' }),
        contact({ contactId: 'c-s', channel: 'SOCIAL' }),
      ];
      respond(backend());
      await service.recoverAfterReconnect();
      expect(tabs.tabs().map((t) => t.contactId)).toContain('p');
      expect(service.overflow().map((o) => o.contactId)).toEqual(['x']);

      tabs.closeByContactId('c-a');
      respond(backend().filter((c) => c.contactId !== 'c-a'));
      await service.recoverAfterReconnect();
      expect(tabs.tabs().map((t) => t.contactId)).toContain('x');
      expect(service.overflow()).toEqual([]);
    });
  });

  describe('stale snapshot', () => {
    it('does not bring back a contact the agent closed while the request was in flight', async () => {
      const answer = new Subject<ActiveContactResponse[]>();
      get.mockReturnValue(answer);
      tabs.openTab({ ...tabInput('r', 'EMAIL') });
      const pending = service.recoverAfterReconnect();
      tabs.closeByContactId('c-r'); // e.g. returned to the queue / disposition saved / rejected
      answer.next([contact({ contactId: 'c-r', phase: 'RINGING', status: 'ASSIGNED' })]);
      answer.complete();
      await pending;

      expect(tabs.tabs()).toEqual([]);
      expect(post).not.toHaveBeenCalled();
    });

    it('a contact closed before the request started is still restored if the backend has it', async () => {
      tabs.openTab(tabInput('r', 'EMAIL'));
      tabs.closeByContactId('c-r');
      respond([contact({ contactId: 'c-r' })]);
      await service.recoverAfterReconnect();
      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['c-r']);
    });
  });

  describe('old backend', () => {
    it('falls back to assigned-contact on 404 (BE-161)', async () => {
      vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      get.mockImplementation((url: string) =>
        url.includes('active-contacts')
          ? throwError(() => new HttpErrorResponse({ status: 404 }))
          : of(
              new HttpResponse({
                status: 200,
                body: {
                  contactId: 'legacy',
                  channel: 'EMAIL',
                  customerIdentifier: 'a@b.c',
                  customerName: 'A',
                },
              }),
            ),
      );
      await service.recoverAfterReconnect();
      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['legacy']);
    });

    it('does not fall back on a 500: real error, desktop untouched, run reported as failed', async () => {
      vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      get.mockImplementation((url: string) =>
        url.includes('active-contacts')
          ? throwError(() => new HttpErrorResponse({ status: 500 }))
          : of(new HttpResponse({ status: 200, body: { contactId: 'legacy', channel: 'EMAIL' } })),
      );
      await expect(service.recoverAfterReconnect()).resolves.toBeUndefined();
      expect(get).toHaveBeenCalledTimes(1);
      expect(get.mock.calls[0][0]).toContain('active-contacts');
      expect(tabs.tabs()).toEqual([]);
      expect(service.lastRun()?.ok).toBe(false);
    });

    it('stays silent and non-blocking when the fallback fails too', async () => {
      vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      get.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      await expect(service.recoverAfterReconnect()).resolves.toBeUndefined();
      expect(service.lastRun()?.ok).toBe(false);
    });

    it('an incomplete (legacy) answer does not erase the overflow found by a full run', async () => {
      respond(['a', 'b', 'c', 'd'].map((id) => contact({ contactId: id })));
      await service.recoverAfterReconnect();
      expect(service.overflow().map((o) => o.contactId)).toEqual(['d']);

      get.mockImplementation((url: string) =>
        url.includes('active-contacts')
          ? throwError(() => new HttpErrorResponse({ status: 404 }))
          : of(new HttpResponse({ status: 204, body: null })),
      );
      await service.recoverAfterReconnect();
      expect(service.overflow().map((o) => o.contactId)).toEqual(['d']);
    });
  });

  describe('ACW entered from "call not restored"', () => {
    const wrapAt = '2026-10-31T09:00:00Z';
    const phoneWrap = () =>
      contact({
        contactId: 'p',
        channel: 'PHONE',
        phase: 'WRAP_UP',
        status: 'COMPLETED',
        wrapUpStartedAt: wrapAt,
        endedAt: wrapAt,
      });

    it('stamps the timer from wrapUpStartedAt, like a freshly opened ACW card', async () => {
      respond([contact({ contactId: 'p', channel: 'PHONE' })]);
      await service.recoverAfterReconnect();
      respond([phoneWrap()]);
      await service.recoverAfterReconnect();
      expect(tabs.tabs()[0].wrappingSince).toBe(Date.parse(wrapAt));
    });

    it('moves an AVAILABLE agent to AFTER_CONTACT on both ACW paths, leaves BREAK alone', async () => {
      agentStatus.set('AVAILABLE');
      respond([phoneWrap()]);
      await service.recoverAfterReconnect();
      expect(changeStatus).toHaveBeenCalledWith('AFTER_CONTACT', { silent: true });

      changeStatus.mockClear();
      tabs.reset();
      respond([contact({ contactId: 'p', channel: 'PHONE' })]);
      await service.recoverAfterReconnect();
      expect(changeStatus).not.toHaveBeenCalled(); // an open call is not ACW
      agentStatus.set('AVAILABLE');
      respond([phoneWrap()]);
      await service.recoverAfterReconnect();
      expect(changeStatus).toHaveBeenCalledWith('AFTER_CONTACT', { silent: true });

      changeStatus.mockClear();
      tabs.reset();
      agentStatus.set('BREAK');
      respond([phoneWrap()]);
      await service.recoverAfterReconnect();
      expect(changeStatus).not.toHaveBeenCalled();
    });

    it('leaves a card with a live session of the same contact alone', async () => {
      respond([contact({ contactId: 'p', channel: 'PHONE' })]);
      await service.recoverAfterReconnect();
      softphone.incomingCall({
        contactId: 'p',
        customerName: 'x',
        customerPhone: '1',
        queueName: '',
      });
      respond([phoneWrap()]);
      await service.recoverAfterReconnect();
      expect(tabs.tabs()[0].status).toBe('ACTIVE');
    });

    describe('polling', () => {
      beforeEach(() => {
        vi.useFakeTimers();
        vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      });

      it('asks again while such a card exists, moves it to ACW and stops polling', async () => {
        respond([contact({ contactId: 'p', channel: 'PHONE' })]);
        await service.recoverAfterReconnect();
        TestBed.tick();
        expect(get).toHaveBeenCalledTimes(1);

        respond([phoneWrap()]);
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS + 1);
        expect(get).toHaveBeenCalledTimes(2);
        expect(tabs.tabs()[0].status).toBe('WRAPPING');
        TestBed.tick();

        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS * 3);
        expect(get).toHaveBeenCalledTimes(2);
      });

      it('does not poll without such a card, and stops after logout', async () => {
        respond([contact({ contactId: 'mail' })]);
        await service.recoverAfterReconnect();
        TestBed.tick();
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS * 2);
        expect(get).toHaveBeenCalledTimes(1);

        respond([contact({ contactId: 'p', channel: 'PHONE' })]);
        await service.recoverAfterReconnect();
        TestBed.tick();
        tabs.reset();
        service.reset();
        TestBed.tick();
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS * 2);
        expect(get).toHaveBeenCalledTimes(2);
      });
    });
  });

  describe('run summary', () => {
    it('reports opened / remaining / not-restored cards and who asked', async () => {
      respond([
        ...['a', 'b', 'c', 'd'].map((id) => contact({ contactId: id })),
        contact({ contactId: 'p', channel: 'PHONE' }),
      ]);
      await service.checkAgain('overflow');
      expect(service.lastRun()).toEqual(
        expect.objectContaining({
          ok: true,
          opened: 4,
          remaining: 1,
          callNotRestored: 1,
          source: 'overflow',
        }),
      );
      await service.recoverAfterReconnect();
      expect(service.lastRun()?.source).toBeNull();
    });
  });

  // ── FE-136 round 3 ─────────────────────────────────────────────────────────

  describe('polling timer', () => {
    beforeEach(() => {
      vi.useFakeTimers();
      vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    });

    it('is not restarted by an unrelated tab change (19 s in)', async () => {
      respond([contact({ contactId: 'p', channel: 'PHONE' })]);
      await service.recoverAfterReconnect();
      tabs.openTab(tabInput('m', 'EMAIL'));
      TestBed.tick();
      expect(get).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(19_000);
      tabs.updateTabCustomerInfo('c-m', 'Nowa nazwa'); // any change of the tabs array
      tabs.setActiveTab('m');
      TestBed.tick();
      respond([contact({ contactId: 'p', channel: 'PHONE' })]);
      await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS - 19_000 + 1);
      expect(get).toHaveBeenCalledTimes(2);
    });
  });

  describe('a hidden "call not restored" card', () => {
    const open = () => contact({ contactId: 'p', channel: 'PHONE' });

    it('is not reopened while the call is still IN_PROGRESS', async () => {
      respond([open()]);
      await service.recoverAfterReconnect();
      service.dismissNotRestored('p');
      tabs.closeByContactId('p');

      respond([open()]);
      await service.recoverAfterReconnect();
      await service.checkAgain('overflow');
      expect(tabs.tabs()).toEqual([]);
      expect(service.overflow()).toEqual([]);
    });

    it('comes back as an ACW card as soon as the call has ended, once', async () => {
      respond([open()]);
      await service.recoverAfterReconnect();
      service.dismissNotRestored('p');
      tabs.closeByContactId('p');

      respond([
        contact({ contactId: 'p', channel: 'PHONE', phase: 'WRAP_UP', status: 'COMPLETED' }),
      ]);
      await service.recoverAfterReconnect();
      expect(tabs.tabs()[0].status).toBe('WRAPPING');

      // after the disposition the agent can get a new call-not-restored card for it? No: the set
      // entry was released, a later IN_PROGRESS of the same contact would be shown again.
      tabs.closeByContactId('p');
      respond([open()]);
      await service.recoverAfterReconnect();
      expect(tabs.tabs()).toHaveLength(1);
    });

    describe('polling for a hidden card', () => {
      beforeEach(() => {
        vi.useFakeTimers();
        vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      });

      it('keeps asking after the card is hidden and brings the ACW card back at WRAP_UP', async () => {
        respond([open()]);
        await service.recoverAfterReconnect();
        service.dismissNotRestored('p');
        tabs.closeByContactId('p');
        TestBed.tick();
        expect(get).toHaveBeenCalledTimes(1);

        respond([open()]); // still in progress: stays hidden
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS + 1);
        expect(get).toHaveBeenCalledTimes(2);
        expect(tabs.tabs()).toEqual([]);

        respond([
          contact({ contactId: 'p', channel: 'PHONE', phase: 'WRAP_UP', status: 'COMPLETED' }),
        ]);
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS + 1);
        expect(tabs.tabs()[0].status).toBe('WRAPPING');
        TestBed.tick();

        // the hidden set is empty now: polling stops
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS * 3);
        expect(get).toHaveBeenCalledTimes(3);
      });

      it('stops when a complete answer no longer lists the hidden contact', async () => {
        service.dismissNotRestored('p');
        TestBed.tick();
        respond([]); // the call is gone from the backend
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS + 1);
        expect(get).toHaveBeenCalledTimes(1);
        TestBed.tick();
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS * 3);
        expect(get).toHaveBeenCalledTimes(1);
      });

      it('an incomplete (old endpoint) answer does not drop the hidden contact', async () => {
        service.dismissNotRestored('p');
        TestBed.tick();
        get.mockImplementation((url: string) =>
          url.includes('active-contacts')
            ? throwError(() => new HttpErrorResponse({ status: 404 }))
            : of(new HttpResponse({ status: 204, body: null })),
        );
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS + 1);
        TestBed.tick();
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS + 1);
        expect(get.mock.calls.filter((c) => String(c[0]).includes('active-contacts')).length).toBe(
          2,
        );
      });

      it('stops polling on logout', async () => {
        service.dismissNotRestored('p');
        TestBed.tick();
        service.reset();
        TestBed.tick();
        await vi.advanceTimersByTimeAsync(CALL_NOT_RESTORED_POLL_MS * 3);
        expect(get).not.toHaveBeenCalled();
      });
    });

    it('is forgotten after logout (reset)', async () => {
      service.dismissNotRestored('p');
      service.reset();
      respond([open()]);
      await service.recoverAfterReconnect();
      expect(tabs.tabs()).toHaveLength(1);
    });
  });

  describe('degraded answer', () => {
    it('is not reported as a normal result when only the old endpoint answered', async () => {
      vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      get.mockImplementation((url: string) =>
        url.includes('active-contacts')
          ? throwError(() => new HttpErrorResponse({ status: 404 }))
          : of(new HttpResponse({ status: 204, body: null })),
      );
      await service.checkAgain('card');
      expect(service.lastRun()).toEqual(expect.objectContaining({ ok: true, degraded: true }));
    });

    it('a full answer is not degraded', async () => {
      respond([]);
      await service.recoverAfterReconnect();
      expect(service.lastRun()?.degraded).toBe(false);
    });
  });

  describe('agent status on ACW', () => {
    const wrap = () =>
      contact({ contactId: 'p', channel: 'PHONE', phase: 'WRAP_UP', status: 'COMPLETED' });

    it('is not touched when the agent changed it while the request was in flight', async () => {
      agentStatus.set('AVAILABLE');
      const answer = new Subject<ActiveContactResponse[]>();
      get.mockReturnValue(answer);
      const pending = service.recoverAfterReconnect();
      statusEpisode.update((n) => n + 1); // the agent pressed AVAILABLE meanwhile
      answer.next([wrap()]);
      answer.complete();
      await pending;

      expect(tabs.tabs()[0].status).toBe('WRAPPING');
      expect(changeStatus).not.toHaveBeenCalled();
    });

    it('is changed silently (no error toast for something the agent did not ask for)', async () => {
      agentStatus.set('AVAILABLE');
      respond([wrap()]);
      await service.recoverAfterReconnect();
      expect(changeStatus).toHaveBeenCalledWith('AFTER_CONTACT', { silent: true });
    });
  });

  describe('review fixes: live parks, 409, revalidation (FE-163 CR)', () => {
    const assigned = (id: string, over: Record<string, unknown> = {}) =>
      ({
        contactId: id,
        type: 'EMAIL',
        customerName: 'Klient ' + id,
        customerIdentifier: id + '@x.pl',
        queueName: 'Support',
        ...over,
      }) as never;
    const fill = (n: number) => {
      for (let i = 0; i < n; i++) tabs.openTab(tabInput('t' + i, 'EMAIL'));
    };
    const settle = async () => {
      for (let i = 0; i < 10; i++) await Promise.resolve();
      TestBed.tick();
    };
    /** active-contacts and GET /contacts/{id} answer separately; unrouted calls give []. */
    const route = (active: () => unknown, byId: (id: string) => unknown = () => ({})) =>
      get.mockImplementation((url: string) => {
        const m = /\/contacts\/([^/]+)$/.exec(url);
        return m ? (byId(m[1]) as never) : (active() as never);
      });
    const detail = (over: Record<string, unknown>) =>
      of({ contactId: 'x', agentId: 'me', status: 'ACTIVE', ...over });
    const writeText = vi.fn().mockResolvedValue(undefined);

    beforeEach(() => {
      writeText.mockClear();
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText },
        configurable: true,
      });
    });

    describe('recovery answer vs. entries newer than the request', () => {
      it('keeps a contact parked live while the request was in flight (complete answer without it)', async () => {
        const answer = new Subject<ActiveContactResponse[]>();
        route(() => answer);
        fill(3);
        const run = service.recoverAfterReconnect();
        // CONTACT_ASSIGNED arrives after the request left, the contact is accepted and parked
        service.parkAssigned(assigned('late'), 'MAX_TOTAL');
        answer.next([]); // the snapshot was taken before the assignment
        answer.complete();
        await run;

        expect(service.overflow().map((o) => o.contactId)).toEqual(['late']);
        expect(post.mock.calls.some((c) => (c[0] as string).endsWith('/late/accept'))).toBe(true);
        // and a later answer that already lists it simply confirms it (no duplicate)
        route(() => of([contact({ contactId: 'late' })]));
        await service.recoverAfterReconnect();
        expect(service.overflow().map((o) => o.contactId)).toEqual(['late']);
      });

      it('still drops a parked entry that an older run knew and the backend no longer lists', async () => {
        fill(3);
        service.parkAssigned(assigned('old'), 'MAX_TOTAL');
        route(() => of([]));
        await service.recoverAfterReconnect();
        // parked before this run started: the answer is newer, so it wins
        expect(service.overflow()).toEqual([]);
      });

      it('keeps a row whose release is in flight, and keeps it after a 5xx', async () => {
        const answer = new Subject<ActiveContactResponse[]>();
        route(() => answer);
        fill(3);
        service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
        const release = new Subject<null>();
        post.mockReturnValue(release);
        const run = service.recoverAfterReconnect();
        const returning = service.returnToQueue('p1');
        answer.next([]); // snapshot from before the release started
        answer.complete();
        await run;
        expect(service.overflow().map((o) => o.contactId)).toEqual(['p1']);

        release.error(new HttpErrorResponse({ status: 500 }));
        await returning;
        expect(service.overflow().map((o) => o.contactId)).toEqual(['p1']);
        expect(notify.error).toHaveBeenCalledWith('agent.recoveryOverflow.returnError');
      });
    });

    it('a failed release with a free slot promotes the row at once (no unrelated change needed)', async () => {
      route(
        () => of([]),
        () => detail({ contactId: 'p1' }),
      );
      fill(3);
      service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
      TestBed.tick();
      const release = new Subject<null>();
      post.mockReturnValue(release);
      const returning = service.returnToQueue('p1');
      tabs.closeTab('t0'); // a slot frees up while the row is being returned
      TestBed.tick();
      await settle();
      expect(tabs.tabs().some((t) => t.contactId === 'p1')).toBe(false); // not while returning

      release.error(new HttpErrorResponse({ status: 500 }));
      await returning;
      await settle(); // runs the effect that the freed row woke up
      await settle(); // and the check that it started
      expect(tabs.tabs().some((t) => t.contactId === 'p1')).toBe(true);
      expect(service.overflow()).toEqual([]);
    });

    describe('409: reconcile the card with the backend', () => {
      const conflict = () => TestBed.inject(ContactConflictService);

      it("closes the card of a contact that is no longer the agent's, notes go to the clipboard", async () => {
        TestBed.inject(AuthService); // real service: currentUserId is null, so only the status counts
        route(
          () => of([]),
          () => detail({ status: 'COMPLETED', agentId: null }),
        );
        tabs.openTab(tabInput('e', 'EMAIL'));
        drafts.setSocialReply('c-e', 'Moja odpowiedz');
        const before = get.mock.calls.length;

        const verdict = await conflict().handle('agent.contactConflict.abandon', 'c-e');

        expect(verdict).toBe('gone');
        expect(get.mock.calls.length).toBe(before + 1);
        expect(get.mock.calls[before][0]).toContain('/contacts/c-e');
        expect(tabs.tabs()).toEqual([]);
        expect(writeText).toHaveBeenCalledWith('Moja odpowiedz');
        expect(notify.warning).toHaveBeenCalledTimes(1);
        expect(notify.warning).toHaveBeenCalledWith('agent.contactConflict.goneNotesCopied');
      });

      it('says so without the clipboard sentence when there was nothing to copy', async () => {
        route(
          () => of([]),
          () => detail({ status: 'ABANDONED' }),
        );
        tabs.openTab(tabInput('e', 'EMAIL'));
        await conflict().handle('agent.contactConflict.abandon', 'c-e');
        expect(writeText).not.toHaveBeenCalled();
        expect(notify.warning).toHaveBeenCalledWith('agent.contactConflict.gone');
        expect(tabs.tabs()).toEqual([]);
      });

      it("a contact still the agent's keeps its card and gets the caller's message", async () => {
        route(
          () => of([]),
          () => detail({ status: 'ACTIVE' }),
        );
        tabs.openTab(tabInput('e', 'EMAIL'));
        drafts.setSocialReply('c-e', 'tekst');
        const verdict = await conflict().handle('agent.contactConflict.abandon', 'c-e');
        expect(verdict).toBe('mine');
        expect(tabs.tabs().map((t) => t.id)).toEqual(['e']);
        expect(drafts.socialReply('c-e')).toBe('tekst');
        expect(notify.warning).toHaveBeenCalledWith('agent.contactConflict.abandon');
      });

      it('a finished call turns its card into ACW (the existing card changes)', async () => {
        route(
          () => of([]),
          () =>
            detail({ status: 'COMPLETED', dispositionCode: null, endedAt: '2026-11-08T10:00:00Z' }),
        );
        tabs.openTab(tabInput('p', 'PHONE'));
        await conflict().handle('agent.contactConflict.disposition', 'c-p');
        expect(tabs.tabs()[0].status).toBe('WRAPPING');
        expect(notify.warning).toHaveBeenCalledWith('agent.contactConflict.disposition');
      });

      it('a disposition already saved elsewhere closes the ACW card: no 409 loop', async () => {
        route(
          () => of([]),
          () => detail({ status: 'COMPLETED', dispositionCode: 'SOLD' }),
        );
        tabs.openTab(tabInput('p', 'PHONE'));
        tabs.updateTabStatus('p', 'WRAPPING');
        await conflict().handle('agent.contactConflict.disposition', 'c-p');
        expect(tabs.tabs()).toEqual([]);
      });

      it('a backend that cannot be asked changes nothing and says so', async () => {
        route(
          () => of([]),
          () => throwError(() => new HttpErrorResponse({ status: 503 })),
        );
        tabs.openTab(tabInput('e', 'EMAIL'));
        const verdict = await conflict().handle('agent.contactConflict.abandon', 'c-e');
        expect(verdict).toBe('unknown');
        expect(tabs.tabs()).toHaveLength(1);
        expect(notify.warning).toHaveBeenCalledWith('agent.contactConflict.unverified');
      });

      it('does not join an older run: it asks about that contact itself', async () => {
        const slow = new Subject<ActiveContactResponse[]>();
        route(
          () => slow,
          () => detail({ status: 'COMPLETED' }),
        );
        tabs.openTab(tabInput('e', 'EMAIL'));
        void service.recoverAfterReconnect(); // started before the conflict, still waiting
        await conflict().handle('agent.contactConflict.abandon', 'c-e');
        expect(tabs.tabs()).toEqual([]); // reflected although the older run never answered
      });
    });

    describe('promotion revalidates the contact', () => {
      it('a dead entry leaves the list with one short notice instead of becoming a dead card', async () => {
        route(
          () => of([]),
          () => throwError(() => new HttpErrorResponse({ status: 404 })),
        );
        fill(3);
        service.parkAssigned(assigned('dead'), 'MAX_TOTAL');
        tabs.closeTab('t0');
        TestBed.tick();
        await settle();

        expect(tabs.tabs().some((t) => t.contactId === 'dead')).toBe(false);
        expect(service.overflow()).toEqual([]);
        expect(notify.info).toHaveBeenCalledWith('agent.recoveryOverflow.vanished');
        expect(notify.info).not.toHaveBeenCalledWith('agent.recoveryOverflow.promoted');
        // a recovery answer that left earlier must not bring it back
        expect(tabs.closedSince(0).has('dead')).toBe(true);
      });

      it('a contact taken by another agent is dropped the same way', async () => {
        route(
          () => of([]),
          () => detail({ agentId: 'someone-else', status: 'ACTIVE' }),
        );
        TestBed.inject(AuthService); // currentUserId null in this harness: status decides, so use one
        fill(3);
        service.parkAssigned(assigned('taken'), 'MAX_TOTAL');
        route(
          () => of([]),
          () => detail({ status: 'QUEUED', agentId: null }), // handed back to the queue
        );
        tabs.closeTab('t0');
        TestBed.tick();
        await settle();
        expect(service.overflow()).toEqual([]);
        expect(tabs.tabs().some((t) => t.contactId === 'taken')).toBe(false);
      });

      it('a live contact gets its card with the assignment time, not the promotion time', async () => {
        route(
          () => of([]),
          () => detail({ status: 'ACTIVE' }),
        );
        fill(3);
        service.parkAssigned(
          assigned('p1', { assignedAt: '2026-11-08T08:00:00.000Z' }),
          'MAX_TOTAL',
        );
        tabs.closeTab('t0');
        TestBed.tick();
        await settle();
        const card = tabs.tabs().find((t) => t.contactId === 'p1');
        expect(card?.startedAt.toISOString()).toBe('2026-11-08T08:00:00.000Z');
      });

      it('recovery rows keep their assignedAt and queueId for the later card', async () => {
        fill(3);
        route(() =>
          of([
            contact({
              contactId: 'r',
              assignedAt: '2026-11-08T07:00:00Z',
              queueId: 'q1',
              phase: 'RINGING',
            }),
          ]),
        );
        await service.recoverAfterReconnect();
        expect(service.overflow()[0]).toMatchObject({
          queueId: 'q1',
          assignedAt: Date.parse('2026-11-08T07:00:00Z'),
        });
      });

      it('a batch of promotions gives one toast, not one per contact', async () => {
        route(
          () => of([]),
          () => detail({ status: 'ACTIVE' }),
        );
        fill(3);
        service.parkAssigned(assigned('p1'), 'MAX_TOTAL');
        service.parkAssigned(assigned('p2'), 'MAX_TOTAL');
        tabs.closeTab('t0');
        tabs.closeTab('t1');
        TestBed.tick();
        await settle();
        expect(tabs.tabs().filter((t) => t.contactId.startsWith('p'))).toHaveLength(2);
        expect(notify.info).toHaveBeenCalledTimes(1);
        expect(notify.info).toHaveBeenCalledWith('agent.recoveryOverflow.promotedMany');
      });
    });

    it('a PHONE entry in the overflow is neither promoted nor released', async () => {
      // another call is live in this browser, so the recovered call is parked
      softphone.session.set({ state: 'ACTIVE', contactId: 'live' } as never);
      route(() => of([contact({ contactId: 'ph', channel: 'PHONE', phase: 'RINGING' })]));
      await service.recoverAfterReconnect();
      expect(service.overflow().map((o) => o.contactId)).toEqual(['ph']);

      get.mockClear();
      post.mockClear();
      tabs.openTab(tabInput('a', 'EMAIL'));
      tabs.closeTab('a'); // slots churn
      TestBed.tick();
      await settle();

      expect(tabs.tabs().some((t) => t.contactId === 'ph')).toBe(false);
      expect(service.overflow().map((o) => o.contactId)).toEqual(['ph']);
      expect(post.mock.calls.some((c) => (c[0] as string).endsWith('/release'))).toBe(false);
      expect(get).not.toHaveBeenCalled(); // not even asked about
      expect(service.returningIds().size).toBe(0);
    });

    it('2 queues x limit 3 with queueId in the events: all 6 kept, 3 as cards, 3 listed, queues preserved', () => {
      const ids = [
        ['a1', 'q1'],
        ['b1', 'q2'],
        ['a2', 'q1'],
        ['b2', 'q2'],
        ['a3', 'q1'],
        ['b3', 'q2'],
      ];
      for (const [id, queueId] of ids) {
        const payload = assigned(id, { queueId, queueName: queueId.toUpperCase() });
        const reason = tabs.openFromContactAssigned(payload);
        if (reason !== null) service.parkAssigned(payload, reason);
      }
      TestBed.tick();

      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['a1', 'b1', 'a2']);
      expect(service.overflow().map((o) => [o.contactId, o.queueId])).toEqual([
        ['b2', 'q2'],
        ['a3', 'q1'],
        ['b3', 'q2'],
      ]);
      const urls = post.mock.calls.map((c) => c[0] as string);
      expect(urls.filter((u) => u.endsWith('/accept'))).toHaveLength(6);
      expect(urls.some((u) => u.endsWith('/release'))).toBe(false);
    });
  });
});
