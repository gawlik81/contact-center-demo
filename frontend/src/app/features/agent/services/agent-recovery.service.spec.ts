import { signal } from '@angular/core';
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

  const respond = (list: ActiveContactResponse[]) => get.mockReturnValue(of(list));

  beforeEach(() => {
    get = vi.fn();
    post = vi.fn().mockReturnValue(of(null));
    agentStatus = signal('OFFLINE');
    changeStatus = vi.fn().mockReturnValue(of(undefined));
    statusEpisode = signal(0);
    TestBed.configureTestingModule({
      providers: [
        { provide: HttpClient, useValue: { get, post } },
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
    it('lists contacts over the limit instead of dropping them', async () => {
      respond(['a', 'b', 'c', 'd', 'e'].map((id) => contact({ contactId: id, channel: 'EMAIL' })));
      await service.recoverAfterReconnect();

      expect(tabs.tabs().map((t) => t.contactId)).toEqual(['a', 'b', 'c']);
      expect(service.overflow().map((o) => [o.contactId, o.reason])).toEqual([
        ['d', 'MAX_ASYNC'],
        ['e', 'MAX_ASYNC'],
      ]);
      // nothing is accepted for contacts without a card
      expect(drafts.markAccepted('d')).toBe(true);
    });

    it('SOCIAL is limited by the total only, so it can use the fourth card', async () => {
      respond([
        ...['a', 'b', 'c'].map((id) => contact({ contactId: id, channel: 'EMAIL' })),
        contact({ contactId: 's', channel: 'SOCIAL' }),
        contact({ contactId: 's2', channel: 'SOCIAL' }),
      ]);
      await service.recoverAfterReconnect();
      expect(tabs.tabs()).toHaveLength(4);
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

    it('after a slot is freed "check again" opens the ACW, not another e-mail', async () => {
      for (const id of ['a', 'b', 'c']) tabs.openTab(tabInput(id, 'EMAIL'));
      tabs.openTab(tabInput('s', 'SOCIAL'));
      const backend = () => [
        contact({ contactId: 'x', channel: 'EMAIL' }),
        contact({ contactId: 'p', channel: 'PHONE', phase: 'WRAP_UP', status: 'COMPLETED' }),
        contact({ contactId: 'c-b' }),
        contact({ contactId: 'c-c' }),
        contact({ contactId: 'c-s', channel: 'SOCIAL' }),
      ];
      respond(backend());
      await service.recoverAfterReconnect();
      expect(
        service
          .overflow()
          .map((o) => o.contactId)
          .sort(),
      ).toEqual(['p', 'x']);

      tabs.closeByContactId('c-a');
      respond(backend());
      await service.recoverAfterReconnect();
      expect(tabs.tabs().map((t) => t.contactId)).toContain('p');
      expect(service.overflow().map((o) => o.contactId)).toEqual(['x']);
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
});
