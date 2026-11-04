import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, of, throwError } from 'rxjs';

import { SocialContactComponent } from './social-contact.component';
import { SocialContactService } from '../../../services/social-contact.service';
import { WebSocketService } from '../../../../../core/services/websocket.service';
import { ContactDraftStore } from '../../../services/contact-draft.store';
import { ACCEPT_RETRY_BASE_MS } from '../../../services/async-contact-accept.service';
import { ContactService } from '../../../services/contact.service';
import { SocialMessage } from '../../../models/social-message.model';

const SENT: SocialMessage = {
  messageId: 'm1',
  platform: 'FACEBOOK',
  direction: 'OUTBOUND',
  content: 'wyslane',
  attachments: [],
  sentAt: '2026-03-25T10:00:00Z',
  senderExternalId: 'a',
};

interface Access {
  replyText: { (): string; set: (v: string) => void };
  sendMessage: () => void;
}

const access = (f: ComponentFixture<SocialContactComponent>) =>
  f.componentInstance as unknown as Access;

describe('SocialContactComponent drafts', () => {
  let drafts: ContactDraftStore;
  let contactService: { acceptContact: ReturnType<typeof vi.fn> };
  let service: { getMessages: ReturnType<typeof vi.fn>; sendMessage: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    contactService = { acceptContact: vi.fn().mockReturnValue(of(null)) };
    service = {
      getMessages: vi.fn().mockReturnValue(of({ content: [], totalPages: 1, totalElements: 0 })),
      sendMessage: vi.fn().mockReturnValue(of(SENT)),
    };
    await TestBed.configureTestingModule({
      imports: [
        SocialContactComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl: {} },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: SocialContactService, useValue: service },
        { provide: ContactService, useValue: contactService },
        { provide: WebSocketService, useValue: { events$: new Subject() } },
      ],
    }).compileComponents();
    drafts = TestBed.inject(ContactDraftStore);
  });

  function mount(contactId = 'c1'): ComponentFixture<SocialContactComponent> {
    const f = TestBed.createComponent(SocialContactComponent);
    f.componentRef.setInput('contactId', contactId);
    f.detectChanges();
    return f;
  }

  it('accepts the contact once on the first mount, not on a remount (FE-148)', () => {
    mount().destroy();
    mount();
    expect(contactService.acceptContact).toHaveBeenCalledTimes(1);
    expect(contactService.acceptContact).toHaveBeenCalledWith('c1', { silent: true });
  });

  it('does not accept again when the live path already did', () => {
    drafts.markAccepted('c1');
    mount();
    expect(contactService.acceptContact).not.toHaveBeenCalled();
  });

  it('retries on the next mount after a failed accept that exhausted the retries', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval'] });
    try {
      contactService.acceptContact.mockReturnValueOnce(throwError(() => ({ status: 503 })));
      mount().destroy();
      await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 3);
      mount();
      expect(contactService.acceptContact).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('a 4xx accept is final: the next mount does not retry', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    contactService.acceptContact.mockReturnValueOnce(throwError(() => ({ status: 409 })));
    mount().destroy();
    mount();
    expect(contactService.acceptContact).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('reply text survives unmount and remount', async () => {
    const first = mount();
    access(first).replyText.set('pol odpowiedzi');
    first.detectChanges();
    await first.whenStable();
    first.destroy();

    const second = mount();
    expect(access(second).replyText()).toBe('pol odpowiedzi');
  });

  it('draft is removed after the message is sent', async () => {
    const f = mount();
    access(f).replyText.set('hej');
    f.detectChanges();
    await f.whenStable();
    expect(drafts.hasUnsentDraft('c1')).toBe(true);

    access(f).sendMessage();
    f.detectChanges();
    await f.whenStable();

    expect(service.sendMessage).toHaveBeenCalled();
    expect(drafts.hasUnsentDraft('c1')).toBe(false);
  });

  it('keeps the draft when sending fails', async () => {
    service.sendMessage.mockReturnValueOnce(
      new (await import('rxjs')).Observable((s) => s.error(new Error('x'))),
    );
    const f = mount();
    access(f).replyText.set('hej');
    f.detectChanges();
    await f.whenStable();
    access(f).sendMessage();
    f.detectChanges();
    await f.whenStable();

    expect(drafts.socialReply('c1')).toBe('hej');
  });
});

describe('SocialContactComponent read-only state', () => {
  let service: { getMessages: ReturnType<typeof vi.fn>; sendMessage: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    service = {
      getMessages: vi.fn().mockReturnValue(of({ content: [], totalPages: 1, totalElements: 0 })),
      sendMessage: vi.fn().mockReturnValue(of(SENT)),
    };
    await TestBed.configureTestingModule({
      imports: [
        SocialContactComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl: {} },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: SocialContactService, useValue: service },
        { provide: WebSocketService, useValue: { events$: new Subject() } },
      ],
    }).compileComponents();
  });

  function mount(status: 'ACTIVE' | 'HOLD' | 'WRAPPING'): ComponentFixture<SocialContactComponent> {
    const f = TestBed.createComponent(SocialContactComponent);
    f.componentRef.setInput('contactId', 'c1');
    f.componentRef.setInput('contactStatus', status);
    f.detectChanges();
    return f;
  }

  const textarea = (f: ComponentFixture<SocialContactComponent>) =>
    f.nativeElement.querySelector('textarea') as HTMLTextAreaElement;

  it('WRAPPING contact is read-only: disabled field, explanation, nothing sent', () => {
    const f = mount('WRAPPING');
    const note = f.nativeElement.querySelector('.social-reply__readonly-banner') as HTMLElement;
    expect(textarea(f).disabled).toBe(true);
    expect(note).not.toBeNull();
    expect(textarea(f).getAttribute('aria-describedby')).toBe(note.id);

    access(f).replyText.set('hej');
    f.detectChanges();
    access(f).sendMessage();
    expect(service.sendMessage).not.toHaveBeenCalled();
    expect((f.nativeElement.querySelector('.send-btn') as HTMLButtonElement).disabled).toBe(true);
  });

  it('ACTIVE contact is editable and can send', () => {
    const f = mount('ACTIVE');
    expect(textarea(f).disabled).toBe(false);
    expect(f.nativeElement.querySelector('.social-reply__readonly-banner')).toBeNull();
    expect(textarea(f).hasAttribute('aria-describedby')).toBe(false);

    access(f).replyText.set('hej');
    access(f).sendMessage();
    expect(service.sendMessage).toHaveBeenCalled();
  });
});

describe('SocialContactComponent CONTACT_MESSAGE_RECEIVED refresh (FE-130/135)', () => {
  const msg = (id: string, direction: 'INBOUND' | 'OUTBOUND' = 'INBOUND'): SocialMessage => ({
    ...SENT,
    messageId: id,
    direction,
    content: 'tresc ' + id,
  });
  let events$: Subject<unknown>;
  let getMessages: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    events$ = new Subject();
    getMessages = vi
      .fn()
      .mockReturnValue(of({ content: [msg('m1')], totalPages: 1, totalElements: 1 }));
    await TestBed.configureTestingModule({
      imports: [
        SocialContactComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl: {} },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: SocialContactService, useValue: { getMessages, sendMessage: vi.fn() } },
        { provide: ContactService, useValue: { acceptContact: vi.fn().mockReturnValue(of(null)) } },
        { provide: WebSocketService, useValue: { events$ } },
      ],
    }).compileComponents();
  });

  const fire = (contactId: string, channel = 'SOCIAL') =>
    events$.next({
      eventType: 'CONTACT_MESSAGE_RECEIVED',
      payload: { contactId, channel, messageId: 'x', receivedAt: 'y' },
    });
  const bubbles = (f: ComponentFixture<SocialContactComponent>) =>
    f.nativeElement.textContent as string;

  const at = (id: string, sentAt: string): SocialMessage => ({ ...msg(id), sentAt });
  const idsOf = (f: ComponentFixture<SocialContactComponent>) =>
    (f.componentInstance as unknown as { messages: () => SocialMessage[] })
      .messages()
      .map((m) => m.messageId);
  const mount = () => {
    const f = TestBed.createComponent(SocialContactComponent);
    f.componentRef.setInput('contactId', 'c1');
    f.detectChanges();
    return f;
  };

  it('merges several new messages chronologically (API lists newest first)', () => {
    getMessages.mockReturnValue(
      of({ content: [at('m1', '2026-11-04T09:00:00Z')], totalPages: 1, totalElements: 1 }),
    );
    const f = mount();
    getMessages.mockReturnValue(
      of({
        content: [
          at('m3', '2026-11-04T09:03:00Z'),
          at('m2', '2026-11-04T09:02:00Z'),
          at('m1', '2026-11-04T09:00:00Z'),
        ],
        totalPages: 1,
        totalElements: 3,
      }),
    );
    fire('c1');
    expect(getMessages).toHaveBeenLastCalledWith('c1', 0, 20);
    expect(idsOf(f)).toEqual(['m1', 'm2', 'm3']);
  });

  it('"load older" after a refresh does not duplicate the shifted message', () => {
    getMessages.mockReturnValue(
      of({
        content: [at('m3', '2026-11-04T09:03:00Z'), at('m2', '2026-11-04T09:02:00Z')],
        totalPages: 2,
        totalElements: 4,
      }),
    );
    const f = mount();
    getMessages.mockReturnValue(
      of({
        content: [at('m4', '2026-11-04T09:04:00Z'), at('m3', '2026-11-04T09:03:00Z')],
        totalPages: 2,
        totalElements: 5,
      }),
    );
    fire('c1'); // offset shift: m2 moves to page 1 now
    getMessages.mockReturnValue(
      of({
        content: [at('m2', '2026-11-04T09:02:00Z'), at('m1', '2026-11-04T09:00:00Z')],
        totalPages: 2,
        totalElements: 5,
      }),
    );
    (f.componentInstance as unknown as { loadMoreMessages(): void }).loadMoreMessages();
    expect(idsOf(f)).toEqual(['m1', 'm2', 'm3', 'm4']);
  });

  it('does not drop an event that arrives during the initial load', () => {
    const initial = new Subject<unknown>();
    getMessages.mockReturnValueOnce(initial);
    const f = mount();
    fire('c1');
    expect(getMessages).toHaveBeenCalledTimes(1);

    getMessages.mockReturnValue(
      of({
        content: [at('m2', '2026-11-04T09:02:00Z'), at('m1', '2026-11-04T09:00:00Z')],
        totalPages: 1,
        totalElements: 2,
      }),
    );
    initial.next({
      content: [at('m1', '2026-11-04T09:00:00Z')],
      totalPages: 1,
      totalElements: 1,
    });
    initial.complete();
    expect(getMessages).toHaveBeenCalledTimes(2);
    expect(idsOf(f)).toEqual(['m1', 'm2']);
  });

  it('serializes refreshes: events during a refresh trigger one follow-up run', () => {
    const f = mount();
    const inflight = new Subject<unknown>();
    getMessages.mockReturnValueOnce(inflight);
    fire('c1');
    fire('c1');
    fire('c1');
    expect(getMessages).toHaveBeenCalledTimes(2);

    getMessages.mockReturnValue(of({ content: [msg('m1')], totalPages: 1, totalElements: 1 }));
    inflight.next({ content: [msg('m1')], totalPages: 1, totalElements: 1 });
    inflight.complete();
    expect(getMessages).toHaveBeenCalledTimes(3);
    expect(idsOf(f)).toEqual(['m1']);
  });

  it('re-reads the newest page and appends only unseen messages (dedupe by id)', () => {
    const f = TestBed.createComponent(SocialContactComponent);
    f.componentRef.setInput('contactId', 'c1');
    f.detectChanges();
    expect(getMessages).toHaveBeenCalledTimes(1);

    getMessages.mockReturnValue(
      of({ content: [msg('m1'), msg('m2')], totalPages: 1, totalElements: 2 }),
    );
    fire('c1');
    f.detectChanges();

    expect(getMessages).toHaveBeenLastCalledWith('c1', 0, 20);
    expect(bubbles(f)).toContain('tresc m2');
    expect(bubbles(f).split('tresc m1').length - 1).toBe(1);
  });

  it('ignores events for another contact or channel, and survives a failing refresh', () => {
    const f = TestBed.createComponent(SocialContactComponent);
    f.componentRef.setInput('contactId', 'c1');
    f.detectChanges();

    fire('other');
    fire('c1', 'EMAIL');
    expect(getMessages).toHaveBeenCalledTimes(1);

    getMessages.mockReturnValue(throwError(() => new Error('boom')));
    fire('c1');
    f.detectChanges();
    expect(bubbles(f)).toContain('tresc m1');
  });

  describe('scrolling and ordering details', () => {
    let height = 0;
    let scrollTop = 0;
    let heightSpy: ReturnType<typeof vi.spyOn>;
    let topSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
      height = 300;
      scrollTop = 0;
      heightSpy = vi
        .spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
        .mockImplementation(() => height);
      topSpy = vi
        .spyOn(HTMLElement.prototype, 'scrollTop', 'get')
        .mockImplementation(() => scrollTop);
      vi.spyOn(HTMLElement.prototype, 'scrollTop', 'set').mockImplementation((v: number) => {
        scrollTop = v;
      });
    });

    afterEach(() => {
      heightSpy.mockRestore();
      topSpy.mockRestore();
      vi.restoreAllMocks();
    });

    const loadOlder = (f: ComponentFixture<SocialContactComponent>) =>
      (f.componentInstance as unknown as { loadMoreMessages(): void }).loadMoreMessages();

    it('starts at the bottom after the initial load (newest visible)', () => {
      getMessages.mockReturnValue(
        of({
          content: [at('m2', '2026-11-04T09:02:00Z'), at('m1', '2026-11-04T09:00:00Z')],
          totalPages: 2,
          totalElements: 3,
        }),
      );
      const f = mount();
      f.detectChanges();
      expect(scrollTop).toBe(300);
    });

    it('keeps the reading position when older messages are prepended', () => {
      getMessages.mockReturnValue(
        of({ content: [at('m2', '2026-11-04T09:02:00Z')], totalPages: 2, totalElements: 2 }),
      );
      const f = mount();
      f.detectChanges();
      scrollTop = 0; // the agent scrolled to the top and pressed "load older"

      getMessages.mockReturnValue(
        of({ content: [at('m1', '2026-11-04T09:00:00Z')], totalPages: 2, totalElements: 2 }),
      );
      loadOlder(f);
      height = 500; // the prepended message made the list 200 px taller
      f.detectChanges();
      expect(scrollTop).toBe(200);
    });

    it('orders equal times by id and unparsable times last', () => {
      getMessages.mockReturnValue(
        of({
          content: [at('b', '2026-11-04T09:00:00Z'), at('z', ''), at('a', '2026-11-04T09:00:00Z')],
          totalPages: 1,
          totalElements: 3,
        }),
      );
      expect(idsOf(mount())).toEqual(['a', 'b', 'z']);
    });

    it('after destroy no refresh starts, even with an event pending', () => {
      const f = mount();
      const inflight = new Subject<unknown>();
      getMessages.mockReturnValueOnce(inflight);
      fire('c1');
      fire('c1'); // pending
      const calls = getMessages.mock.calls.length;
      f.destroy();
      expect(getMessages.mock.calls.length).toBe(calls);
    });
  });
});
