import { TestBed } from '@angular/core/testing';
import { Subject, defer, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ACCEPT_RETRY_BASE_MS,
  ACCEPT_RETRY_COUNT,
  AsyncContactAcceptService,
} from './async-contact-accept.service';
import { ContactDraftStore } from './contact-draft.store';
import { ContactService } from './contact.service';

/** Cold request: every (re)subscription is one HTTP attempt, answered from `answers` in order. */
function attempts(answers: ({ status: number } | null)[]) {
  const counter = { n: 0 };
  const request = defer(() => {
    const answer = answers[Math.min(counter.n, answers.length - 1)];
    counter.n++;
    return answer ? throwError(() => answer) : of(null);
  });
  return { counter, request };
}

describe('AsyncContactAcceptService (FE-148)', () => {
  let service: AsyncContactAcceptService;
  let drafts: ContactDraftStore;
  let acceptContact: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    acceptContact = vi.fn().mockReturnValue(of(null));
    TestBed.configureTestingModule({
      providers: [{ provide: ContactService, useValue: { acceptContact } }],
    });
    service = TestBed.inject(AsyncContactAcceptService);
    drafts = TestBed.inject(ContactDraftStore);
  });

  it('sends one request per contact however many callers ask', () => {
    service.accept('c1');
    service.accept('c1');
    service.accept('c2');
    expect(acceptContact).toHaveBeenCalledTimes(2);
  });

  it('does not send a second request while the first is still in flight', () => {
    acceptContact.mockReturnValue(new Subject());
    service.accept('c1');
    service.accept('c1');
    expect(acceptContact).toHaveBeenCalledTimes(1);
  });

  it('respects a contact already marked by recovery (IN_PROGRESS)', () => {
    drafts.markAccepted('c1');
    service.accept('c1');
    expect(acceptContact).not.toHaveBeenCalled();
  });

  it('sends silently (no global error toast)', () => {
    service.accept('c1');
    expect(acceptContact).toHaveBeenCalledWith('c1', { silent: true });
  });

  it('acceptOpened skips PHONE and accepts every other channel', () => {
    service.acceptOpened('PHONE', 'p');
    service.acceptOpened('EMAIL', 'e');
    service.acceptOpened('SOCIAL', 's');
    service.acceptOpened('CHAT', 'c');
    expect(acceptContact.mock.calls.map((c) => c[0])).toEqual(['e', 's', 'c']);
  });

  describe('failures', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('retries a 5xx / network error with backoff and succeeds without releasing the mark', async () => {
      const { counter, request } = attempts([{ status: 500 }, { status: 0 }, null]);
      acceptContact.mockReturnValue(request);
      service.accept('c1');
      expect(counter.n).toBe(1);
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS);
      expect(counter.n).toBe(2);
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 2);
      expect(counter.n).toBe(3);
      expect(drafts.markAccepted('c1')).toBe(false);
    });

    it('reverts the mark after the retries are exhausted, so the next trigger retries', async () => {
      const { counter, request } = attempts([{ status: 503 }]);
      acceptContact.mockReturnValue(request);
      service.accept('c1');
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 3);
      expect(counter.n).toBe(1 + ACCEPT_RETRY_COUNT);

      service.accept('c1');
      expect(counter.n).toBe(2 + ACCEPT_RETRY_COUNT);
    });

    it('logout during the backoff: no further POST', async () => {
      const { counter, request } = attempts([{ status: 503 }]);
      acceptContact.mockReturnValue(request);
      service.accept('c1');
      drafts.clearAll();
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 5);
      expect(counter.n).toBe(1);
      expect(drafts.isAccepted('c1')).toBe(false);
    });

    it('release during the backoff: no further POST and no revert or warning', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      const { counter, request } = attempts([{ status: 503 }]);
      acceptContact.mockReturnValue(request);
      service.accept('c1');
      drafts.clear('c1'); // the tab was returned to the queue
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 5);
      expect(counter.n).toBe(1);
      expect(warn).not.toHaveBeenCalled();
      warn.mockRestore();
    });

    it('a new assignment after a failure keeps its own mark; the old retry loop stays out', async () => {
      const old = attempts([{ status: 503 }]);
      acceptContact.mockReturnValueOnce(old.request);
      service.accept('c1');
      drafts.clear('c1'); // released, then assigned again
      const fresh = attempts([null]);
      acceptContact.mockReturnValueOnce(fresh.request);
      service.accept('c1');
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 5);

      expect(old.counter.n).toBe(1); // no retry for the old assignment
      expect(fresh.counter.n).toBe(1);
      expect(drafts.isAccepted('c1')).toBe(true);
    });

    it('the final revert of an old failure does not clear a newer mark', async () => {
      const old = attempts([{ status: 503 }]);
      acceptContact.mockReturnValueOnce(old.request);
      service.accept('c1');
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS); // first retry fires and fails
      drafts.clear('c1');
      drafts.markAccepted('c1'); // e.g. recovery marked the new assignment
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 5);
      expect(drafts.isAccepted('c1')).toBe(true);
    });

    it.each([400, 404, 409])('keeps the mark and warns once on %i (no retry)', async (status) => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      const { counter, request } = attempts([{ status }]);
      acceptContact.mockReturnValue(request);
      service.accept('c1');
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 5);
      service.accept('c1');
      expect(counter.n).toBe(1);
      expect(warn).toHaveBeenCalledTimes(1);
      warn.mockRestore();
    });
  });
});
