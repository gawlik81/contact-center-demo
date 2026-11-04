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

  it('probes the total, then reads the LAST page and appends it when contiguous (25 messages)', () => {
    const first20 = Array.from({ length: 20 }, (_, i) => message('m' + (i + 1)));
    emailService['getThread'].mockReturnValue(of(page(first20)));
    const f = mount();
    emailService['getThread'].mockImplementation((_root: string, p: number, size: number) =>
      of({
        ...page(size === 1 ? [message('x')] : [message('m20'), message('m25')]),
        page: p,
        totalElements: 25,
        totalPages: 2,
      }),
    );
    fire('m1');
    expect(emailService['getThread']).toHaveBeenCalledWith('root-1', 0, 1);
    expect(emailService['getThread']).toHaveBeenLastCalledWith('root-1', 1, 20);
    expect(threadOf(f)).toHaveLength(21);
    expect(threadOf(f).at(-1)!.id).toBe('m25');

    fire('m1'); // same answer: no duplicates
    expect(threadOf(f)).toHaveLength(21);
  });

  it('does not append the last page of a long thread (45 messages): it would hide a gap', () => {
    const f = mount();
    emailService['getThread'].mockImplementation((_root: string, p: number, size: number) =>
      of({
        ...page(size === 1 ? [message('x')] : [message('m41'), message('m45')]),
        page: p,
        totalElements: 45,
        totalPages: 3,
      }),
    );
    fire('m1');
    expect(emailService['getThread']).toHaveBeenLastCalledWith('root-1', 2, 20);
    expect(threadOf(f).map((m) => m.id)).toEqual(['m1']);
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
    expect(emailService['getThread']).toHaveBeenCalledTimes(2); // initial + first probe only

    emailService['getThread'].mockReturnValue(of(page([message('m1'), message('m2')])));
    probe.next(page([message('m1')]));
    probe.complete();
    // first run finished, then exactly one follow-up run (probe + page)
    expect(emailService['getThread']).toHaveBeenCalledTimes(2 + 1 + 2);
    expect(threadOf(f).map((m) => m.id)).toEqual(['m1', 'm2']);
  });

  it('"load older" does not duplicate messages already shown', () => {
    const f = mount();
    emailService['getThread'].mockReturnValue(of(page([message('m1'), message('m2')])));
    fire('m1');
    emailService['getThread'].mockReturnValue(of(page([message('m0'), message('m2')])));
    (f.componentInstance as unknown as { loadMoreMessages(): void }).loadMoreMessages();
    expect(threadOf(f).map((m) => m.id)).toEqual(['m0', 'm1', 'm2']);
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
