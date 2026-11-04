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
