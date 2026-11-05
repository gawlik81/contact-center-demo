import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Observable, Subject, of, throwError } from 'rxjs';

import { EmailContactComponent } from './email-contact.component';
import { EmailService, EmailMessage } from '../../../services/email.service';
import { WebSocketService } from '../../../../../core/services/websocket.service';

const message = (n: number): EmailMessage => ({
  id: 'm' + n,
  messageIdHeader: '<m' + n + '>',
  threadRootMessageId: 'root-1',
  fromAddress: 'k@example.com',
  toAddresses: [],
  ccAddresses: [],
  subject: 'Temat',
  bodyHtml: '<p>tresc ' + n + '</p>',
  direction: 'INBOUND',
  createdAt: '2026-11-04T09:00:00Z',
});

interface Access {
  thread: () => EmailMessage[];
  hasMore: () => boolean;
  loadingMore: () => boolean;
  error: () => string | null;
  loadMoreMessages(): void;
}

describe('EmailContactComponent reverse thread paging (FE-150)', () => {
  let getThread: ReturnType<typeof vi.fn>;
  let events$: Subject<unknown>;
  let total = 0;

  /** Spring Page contract: an empty thread has totalPages 0. */
  const answer = (p: number, size: number) => {
    const all = Array.from({ length: total }, (_, i) => message(i + 1));
    return {
      content: all.slice(p * size, p * size + size),
      page: p,
      size,
      totalElements: total,
      totalPages: Math.ceil(total / size),
      first: p === 0,
      last: (p + 1) * size >= total,
    };
  };

  const setup = (initialTotal: number) => {
    total = initialTotal;
    events$ = new Subject();
    getThread = vi
      .fn()
      .mockImplementation((_r: string, p: number, size: number) => of(answer(p, size)));
    TestBed.configureTestingModule({
      imports: [
        EmailContactComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl: {} },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: EmailService,
          useValue: {
            getMessage: vi.fn().mockReturnValue(of(message(1))),
            getThread,
            getTemplates: vi.fn().mockReturnValue(of({ content: [] })),
          },
        },
        { provide: WebSocketService, useValue: { events$ } },
      ],
    });
    const f = TestBed.createComponent(EmailContactComponent);
    f.componentRef.setInput('contactId', 'm1');
    f.detectChanges();
    return { f, c: f.componentInstance as unknown as Access };
  };
  const ids = (c: Access) => c.thread().map((m) => Number(m.id.slice(1)));
  const range = (from: number, to: number) =>
    Array.from({ length: to - from + 1 }, (_, i) => from + i);
  const fire = () =>
    events$.next({
      eventType: 'CONTACT_MESSAGE_RECEIVED',
      payload: { contactId: 'm1', channel: 'EMAIL', messageId: 'x', receivedAt: 'y' },
    });

  it('20 messages: one request, everything shown, nothing older', () => {
    const { c } = setup(20);
    expect(getThread).toHaveBeenCalledTimes(1);
    expect(ids(c)).toEqual(range(1, 20));
    expect(c.hasMore()).toBe(false);
  });

  it('21 messages: page 0 is reused as the previous page, no extra request', () => {
    const { c } = setup(21);
    expect(getThread).toHaveBeenCalledTimes(2); // page 0 + page 1
    expect(ids(c)).toEqual(range(1, 21));
    expect(c.hasMore()).toBe(false);
  });

  it('40 messages: opens on the full newest page 21..40, older page available', () => {
    const { c } = setup(40);
    expect(getThread).toHaveBeenCalledTimes(2);
    expect(ids(c)).toEqual(range(21, 40));
    expect(c.hasMore()).toBe(true);
    c.loadMoreMessages();
    expect(ids(c)).toEqual(range(1, 40));
    expect(c.hasMore()).toBe(false);
  });

  it('41 messages: opens on 21..41 (pages 1+2), then the oldest page in order', () => {
    const { c } = setup(41);
    expect(getThread).toHaveBeenCalledTimes(3); // page 0 probe + 2 + 1
    expect(ids(c)).toEqual(range(21, 41));
    expect(c.hasMore()).toBe(true);
    c.loadMoreMessages();
    expect(ids(c)).toEqual(range(1, 41));
    expect(c.hasMore()).toBe(false);
    c.loadMoreMessages(); // nothing older: no request
    expect(getThread).toHaveBeenCalledTimes(4);
  });

  it('empty thread (totalPages 0): falls back to the opened message, nothing older', () => {
    const { c } = setup(0);
    expect(c.thread().map((m) => m.id)).toEqual(['m1']);
    expect(c.hasMore()).toBe(false);
  });

  it('a shrinking thread (40 -> 39, then +1) is still refreshed from the real last page', () => {
    const { c } = setup(40); // pages 0,1; newest page = 1
    total = 20; // anonymisation removed messages: only page 0 is left
    getThread.mockClear();
    fire();
    expect(getThread).toHaveBeenCalledTimes(2); // page 1 (empty, totalPages 1) then page 0
    expect(getThread).toHaveBeenLastCalledWith('root-1', 0, 20);
    expect(ids(c)).toEqual(range(1, 20)); // reset to the real tail, removed messages gone
    expect(c.hasMore()).toBe(false);
    total = 21; // a new message lands on page 1 again
    getThread.mockClear();
    fire();
    expect(getThread).toHaveBeenCalledWith('root-1', 0, 20);
    expect(c.thread().at(-1)!.id).toBe('m21');
    expect(new Set(c.thread().map((m) => m.id)).size).toBe(c.thread().length);
  });

  it('after a failed first load the refresh is bounded: only the newest pages are read', () => {
    total = 200; // 10 pages
    events$ = new Subject();
    getThread = vi
      .fn()
      .mockReturnValueOnce(throwError(() => new Error('boom')))
      .mockImplementation((_r: string, p: number, size: number) => of(answer(p, size)));
    TestBed.configureTestingModule({
      imports: [
        EmailContactComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl: {} },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: EmailService,
          useValue: {
            getMessage: vi.fn().mockReturnValue(of(message(1))),
            getThread,
            getTemplates: vi.fn().mockReturnValue(of({ content: [] })),
          },
        },
        { provide: WebSocketService, useValue: { events$ } },
      ],
    });
    const f = TestBed.createComponent(EmailContactComponent);
    f.componentRef.setInput('contactId', 'm1');
    f.detectChanges();
    const c = f.componentInstance as unknown as Access;
    expect(c.thread().map((m) => m.id)).toEqual(['m1']);
    getThread.mockClear();
    fire();
    // page 0 + the two newest pages (8, 9): no fan-out over pages 1..9
    expect(getThread).toHaveBeenCalledTimes(3);
    expect(ids(c)).toEqual(range(161, 200));
    expect(c.hasMore()).toBe(true);
  });

  it('a failing "older" load keeps the range, shows the error and re-enables the button', () => {
    const { c } = setup(40);
    getThread.mockReturnValue(throwError(() => new Error('boom')));
    c.loadMoreMessages();
    expect(ids(c)).toEqual(range(21, 40));
    expect(c.hasMore()).toBe(true);
    expect(c.loadingMore()).toBe(false);
    expect(c.error()).not.toBeNull();
    getThread.mockImplementation((_r: string, p: number, size: number) => of(answer(p, size)));
    c.loadMoreMessages(); // retry works
    expect(ids(c)).toEqual(range(1, 40));
  });

  it('"older" during a refresh: no duplicates, range stays correct', () => {
    const { c } = setup(40);
    const older = new Subject<ReturnType<typeof answer>>();
    const refresh = new Subject<ReturnType<typeof answer>>();
    getThread.mockReset();
    getThread.mockReturnValueOnce(older as Observable<unknown>);
    c.loadMoreMessages(); // in flight (page 0)
    getThread.mockReturnValueOnce(refresh as Observable<unknown>);
    total = 41;
    fire(); // refresh in flight (page 1)
    getThread.mockImplementation((_r: string, p: number, size: number) => of(answer(p, size)));
    refresh.next(answer(1, 20)); // reports 3 pages -> reads page 2 as well
    refresh.complete();
    older.next(answer(0, 20));
    older.complete();
    expect(ids(c)).toEqual(range(1, 41));
    expect(c.hasMore()).toBe(false);
  });

  describe('pin to the newest message (first open only)', () => {
    let callbacks: (() => void)[];
    let observed: Element[];
    const original = globalThis.ResizeObserver;

    beforeEach(() => {
      callbacks = [];
      observed = [];
      globalThis.ResizeObserver = class {
        constructor(cb: () => void) {
          callbacks.push(cb);
        }
        observe = (el: Element) => observed.push(el);
        unobserve = vi.fn();
        disconnect = vi.fn();
      } as unknown as typeof ResizeObserver;
    });
    afterEach(() => {
      globalThis.ResizeObserver = original;
    });

    /** jsdom has no layout: fake a 300px list whose newest message starts at `lastTop`. */
    const layout = (f: ReturnType<typeof setup>['f'], lastTop: number) => {
      const host = f.nativeElement as HTMLElement;
      const list = host.querySelector('.email-thread__messages') as HTMLElement;
      const items = host.querySelectorAll('cc-email-thread-message');
      Object.defineProperty(list, 'clientHeight', { value: 300, configurable: true });
      Object.defineProperty(list, 'scrollHeight', { value: 5000, configurable: true });
      list.getBoundingClientRect = () => ({ top: 0 }) as DOMRect;
      items[items.length - 1].getBoundingClientRect = () => ({ top: lastTop }) as DOMRect;
      return list;
    };
    const resize = () => callbacks.forEach((cb) => cb());

    it('re-pins the newest message whenever message heights change', () => {
      const { f } = setup(21);
      f.detectChanges();
      const list = layout(f, 4000);
      resize();
      expect(list.scrollTop).toBe(4000);
      layout(f, 600); // iframes above grew: viewport-relative top, scrollTop is 4000
      resize();
      expect(list.scrollTop).toBe(4600);
    });

    it('leaves a list that fits untouched', () => {
      const { f } = setup(21);
      f.detectChanges();
      const list = layout(f, 100);
      Object.defineProperty(list, 'scrollHeight', { value: 300, configurable: true });
      resize();
      expect(list.scrollTop).toBe(0);
    });

    it('stops pinning after the first user interaction', () => {
      const { f } = setup(21);
      f.detectChanges();
      const list = layout(f, 4000);
      resize();
      list.dispatchEvent(new Event('wheel'));
      layout(f, 4600);
      resize();
      expect(list.scrollTop).toBe(4000);
    });

    it('still pins on a very late size change (background tab, ~9 s)', () => {
      vi.useFakeTimers();
      try {
        const { f } = setup(21);
        f.detectChanges();
        const list = layout(f, 4000);
        vi.advanceTimersByTime(9000);
        resize();
        expect(list.scrollTop).toBe(4000);
        vi.advanceTimersByTime(60000);
        list.scrollTop = 0;
        layout(f, 4500);
        resize();
        expect(list.scrollTop).toBe(4500); // no time limit at all
      } finally {
        vi.useRealTimers();
      }
    });

    it('follows messages added by a refresh while still pinned', () => {
      const { f, c } = setup(21);
      f.detectChanges();
      const list = layout(f, 4000);
      total = 22;
      fire();
      f.detectChanges();
      expect(c.thread().at(-1)!.id).toBe('m22');
      const items = (f.nativeElement as HTMLElement).querySelectorAll('cc-email-thread-message');
      expect(items).toHaveLength(22);
      expect(observed).toContain(items[21]); // the new message is observed too
      list.scrollTop = 0;
      layout(f, 4700);
      resize();
      expect(list.scrollTop).toBe(4700);
    });

    it('re-pins when the list itself is resized by its surroundings', () => {
      const { f } = setup(21);
      f.detectChanges();
      const list = layout(f, 4000);
      expect(observed).toContain(list);
      Object.defineProperty(list, 'clientHeight', { value: 200, configurable: true });
      layout(f, 4300);
      resize();
      expect(list.scrollTop).toBe(4300);
    });

    it('does not pin after "older" is loaded', () => {
      const { f, c } = setup(40);
      f.detectChanges();
      const list = layout(f, 4000);
      c.loadMoreMessages();
      f.detectChanges();
      layout(f, 4600);
      resize();
      expect(list.scrollTop).toBe(0);
    });

    it('does not pin on a refresh', () => {
      const { f } = setup(21);
      f.detectChanges();
      const list = layout(f, 4000);
      list.dispatchEvent(new Event('keydown')); // pin window over
      total = 22;
      fire();
      f.detectChanges();
      layout(f, 4600);
      resize();
      expect(list.scrollTop).toBe(0);
    });
  });
});
