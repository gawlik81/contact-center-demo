import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, of, throwError } from 'rxjs';

import { EmailContactComponent } from './email-contact.component';
import { EmailService, EmailMessage } from '../../../services/email.service';
import { WebSocketService } from '../../../../../core/services/websocket.service';

const message = (id: string): EmailMessage => ({
  id,
  messageIdHeader: '<' + id + '>',
  threadRootMessageId: 'root-1',
  fromAddress: 'k@example.com',
  toAddresses: [],
  ccAddresses: [],
  subject: 'Temat',
  bodyHtml: '<p>tresc ' + id + '</p>',
  direction: 'INBOUND',
  createdAt: '2026-11-04T09:00:00Z',
});

const page = (content: EmailMessage[]) => ({
  content,
  page: 0,
  size: 20,
  totalElements: content.length,
  totalPages: 1,
  first: true,
  last: true,
});

describe('EmailContactComponent CONTACT_MESSAGE_RECEIVED refresh (FE-130/135)', () => {
  let events$: Subject<unknown>;
  let emailService: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    events$ = new Subject();
    emailService = {
      getMessage: vi.fn().mockReturnValue(of(message('m1'))),
      getThread: vi.fn().mockReturnValue(of(page([message('m1')]))),
      getTemplates: vi.fn().mockReturnValue(of(page([]))),
    };
    await TestBed.configureTestingModule({
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
        { provide: EmailService, useValue: emailService },
        { provide: WebSocketService, useValue: { events$ } },
      ],
    }).compileComponents();
  });

  const mount = () => {
    const f = TestBed.createComponent(EmailContactComponent);
    f.componentRef.setInput('contactId', 'm1');
    f.detectChanges();
    return f;
  };
  const threadOf = (f: ReturnType<typeof mount>) =>
    (f.componentInstance as unknown as { thread: () => EmailMessage[] }).thread();
  const fire = (contactId: string, channel = 'EMAIL') =>
    events$.next({
      eventType: 'CONTACT_MESSAGE_RECEIVED',
      payload: { contactId, channel, messageId: 'x', receivedAt: 'y' },
    });

  /** Fake thread API over `total` messages m1..mN, oldest first, 20 per page. */
  const fakeThread = (getTotal: () => number) =>
    vi.fn().mockImplementation((_root: string, p: number, size: number) => {
      const total = getTotal();
      const all = Array.from({ length: total }, (_, i) => message('m' + (i + 1)));
      return of({
        content: all.slice(p * size, p * size + size),
        page: p,
        size,
        totalElements: total,
        totalPages: Math.ceil(total / size),
        first: p === 0,
        last: (p + 1) * size >= total,
      });
    });

  it('refreshes with ONE request for the newest page and appends the news (25 messages)', () => {
    let total = 25;
    emailService['getThread'] = fakeThread(() => total);
    TestBed.overrideProvider(EmailService, { useValue: emailService });
    const f = mount();
    expect(threadOf(f)).toHaveLength(25); // short newest page: page 0 was joined
    total = 26;
    emailService['getThread'].mockClear();
    fire('m1');
    expect(emailService['getThread']).toHaveBeenCalledTimes(1);
    expect(emailService['getThread']).toHaveBeenCalledWith('root-1', 1, 20);
    expect(threadOf(f).at(-1)!.id).toBe('m26');
    fire('m1'); // same answer: no duplicates
    expect(threadOf(f)).toHaveLength(26);
  });

  it('a message landing on a shifting page boundary is not lost (40 -> 41 between reads)', () => {
    let total = 40;
    emailService['getThread'] = fakeThread(() => total);
    TestBed.overrideProvider(EmailService, { useValue: emailService });
    const f = mount();
    expect(threadOf(f).map((m) => m.id)[0]).toBe('m21');
    expect(threadOf(f)).toHaveLength(20);
    // m41 opens page 2 right before page 1 is read: the answer reports 3 pages, so page 2 is read too
    const original = emailService['getThread'].getMockImplementation() as (
      ...args: unknown[]
    ) => unknown;
    emailService['getThread'].mockImplementation((r: string, p: number, s: number) => {
      total = 41;
      return original(r, p, s);
    });
    emailService['getThread'].mockClear();
    fire('m1');
    expect(emailService['getThread']).toHaveBeenCalledTimes(2);
    expect(threadOf(f).at(-1)!.id).toBe('m41');
    expect(threadOf(f)).toHaveLength(21);
  });

  it('keeps a long thread consistent: newest pages merge without a continuity check (45)', () => {
    emailService['getThread'] = fakeThread(() => 45);
    TestBed.overrideProvider(EmailService, { useValue: emailService });
    const f = mount();
    emailService['getThread'].mockClear();
    fire('m1');
    expect(emailService['getThread']).toHaveBeenCalledTimes(1);
    expect(threadOf(f)).toHaveLength(25); // m21..m45
    expect(threadOf(f).at(-1)!.id).toBe('m45');
  });

  it('after destroy no refresh starts, even with an event pending', () => {
    const f = mount();
    const probe = new Subject<ReturnType<typeof page>>();
    emailService['getThread'].mockReturnValueOnce(probe);
    fire('m1');
    fire('m1'); // pending
    const calls = emailService['getThread'].mock.calls.length;
    f.destroy();
    expect(emailService['getThread'].mock.calls.length).toBe(calls);
  });

  it('reads page 0 for a short thread', () => {
    mount();
    fire('m1');
    expect(emailService['getThread']).toHaveBeenLastCalledWith('root-1', 0, 20);
  });

  it('falls back to the opened message when the initial thread read fails', () => {
    emailService['getThread'].mockReturnValue(throwError(() => new Error('boom')));
    const f = mount();
    expect(threadOf(f).map((m) => m.id)).toEqual(['m1']);
  });

  it('does not drop an event that arrives during the initial load: one refresh after it', () => {
    const initial = new Subject<ReturnType<typeof page>>();
    emailService['getThread'].mockReturnValueOnce(initial);
    const f = mount();
    fire('m1');
    expect(emailService['getThread']).toHaveBeenCalledTimes(1); // nothing started yet

    emailService['getThread'].mockReturnValue(of(page([message('m1'), message('m2')])));
    initial.next(page([message('m1')]));
    initial.complete();
    expect(threadOf(f).map((m) => m.id)).toEqual(['m1', 'm2']);
  });

  it('serializes refreshes: events during a refresh produce one more run, not parallel ones', () => {
    const f = mount();
    const probe = new Subject<ReturnType<typeof page>>();
    emailService['getThread'].mockReturnValueOnce(probe);
    fire('m1');
    fire('m1');
    fire('m1');
    expect(emailService['getThread']).toHaveBeenCalledTimes(2); // initial + first refresh only

    emailService['getThread'].mockReturnValue(of(page([message('m1'), message('m2')])));
    probe.next(page([message('m1')]));
    probe.complete();
    // first run finished, then exactly one follow-up run (probe + page)
    expect(emailService['getThread']).toHaveBeenCalledTimes(2 + 1);
    expect(threadOf(f).map((m) => m.id)).toEqual(['m1', 'm2']);
  });

  it('"load older" does not duplicate messages already shown (boundary overlap)', () => {
    emailService['getThread'] = fakeThread(() => 45);
    TestBed.overrideProvider(EmailService, { useValue: emailService });
    const f = mount();
    const impl = emailService['getThread'].getMockImplementation() as (
      ...args: unknown[]
    ) => ReturnType<typeof of>;
    // page 0 answered with an extra, already shown message at its end
    emailService['getThread'].mockImplementation((r: string, p: number, sz: number) =>
      p === 0 ? of({ ...page([message('m1'), message('m21')]), totalPages: 2 }) : impl(r, p, sz),
    );
    (f.componentInstance as unknown as { loadMoreMessages(): void }).loadMoreMessages();
    expect(threadOf(f).map((m) => m.id)).toEqual([
      'm1',
      ...Array.from({ length: 25 }, (_, i) => 'm' + (21 + i)),
    ]);
  });

  it('ignores another contact or channel and survives a failing refresh', () => {
    const f = mount();
    const calls = emailService['getThread'].mock.calls.length;
    fire('other');
    fire('m1', 'SOCIAL');
    expect(emailService['getThread'].mock.calls.length).toBe(calls);

    emailService['getThread'].mockReturnValue(throwError(() => new Error('boom')));
    fire('m1');
    expect(threadOf(f).map((m) => m.id)).toEqual(['m1']);
  });
});
