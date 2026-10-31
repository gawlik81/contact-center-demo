import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, of } from 'rxjs';

import { SocialContactComponent } from './social-contact.component';
import { SocialContactService } from '../../../services/social-contact.service';
import { WebSocketService } from '../../../../../core/services/websocket.service';
import { ContactDraftStore } from '../../../services/contact-draft.store';
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
    drafts = TestBed.inject(ContactDraftStore);
  });

  function mount(contactId = 'c1'): ComponentFixture<SocialContactComponent> {
    const f = TestBed.createComponent(SocialContactComponent);
    f.componentRef.setInput('contactId', contactId);
    f.detectChanges();
    return f;
  }

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
