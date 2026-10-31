import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, of } from 'rxjs';

import { EmailContactComponent } from './email-contact.component';
import { EmailService, EmailMessage } from '../../../services/email.service';
import { ContactService } from '../../../services/contact.service';
import { ContactDraftStore } from '../../../services/contact-draft.store';
import { ContactTabStore } from '../../../services/contact-tab.store';
import { NotificationService } from '../../../../../core/services/notification.service';

const MESSAGE: EmailMessage = {
  id: 'msg-001',
  messageIdHeader: '<m@x>',
  threadRootMessageId: 'root-001',
  fromAddress: 'klient@example.com',
  toAddresses: ['agent@firma.pl'],
  ccAddresses: [],
  subject: 'Pytanie',
  bodyHtml: '<p>Hej</p>',
  direction: 'INBOUND',
  status: 'ACTIVE',
  createdAt: '2026-03-25T10:00:00Z',
  receivedAt: '2026-03-25T10:00:05Z',
};

interface Access {
  sending: () => boolean;
  canSend: () => boolean;
  error: () => string | null;
  replyHtml: { (): string; set: (v: string) => void };
  replySubject: () => string;
  onSubjectInput: (v: string) => void;
  sendReply: () => void;
  cancelReply: () => void;
}

describe('EmailContactComponent drafts', () => {
  let emailService: Record<string, ReturnType<typeof vi.fn>>;
  let contactService: {
    acceptContact: ReturnType<typeof vi.fn>;
    abandonContact: ReturnType<typeof vi.fn>;
  };
  let drafts: ContactDraftStore;
  let tabs: ContactTabStore;
  const notifications = { success: vi.fn(), error: vi.fn() };

  beforeEach(async () => {
    emailService = {
      getMessage: vi.fn().mockReturnValue(of(MESSAGE)),
      getThread: vi.fn().mockReturnValue(
        of({
          content: [MESSAGE],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
          first: true,
          last: true,
        }),
      ),
      getTemplates: vi.fn().mockReturnValue(of({ content: [], first: true })),
      sendReply: vi.fn().mockReturnValue(of(MESSAGE)),
      previewTemplate: vi.fn(),
    };
    contactService = {
      acceptContact: vi.fn().mockReturnValue(of(null)),
      abandonContact: vi.fn().mockReturnValue(of(null)),
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
        { provide: EmailService, useValue: emailService },
        { provide: ContactService, useValue: contactService },
        { provide: NotificationService, useValue: notifications },
      ],
    }).compileComponents();
    drafts = TestBed.inject(ContactDraftStore);
    tabs = TestBed.inject(ContactTabStore);
    tabs.reset();
    notifications.success.mockClear();
  });

  function mount(contactId = 'msg-001'): ComponentFixture<EmailContactComponent> {
    const fixture = TestBed.createComponent(EmailContactComponent);
    fixture.componentRef.setInput('contactId', contactId);
    fixture.detectChanges();
    return fixture;
  }

  const access = (f: ComponentFixture<EmailContactComponent>) =>
    f.componentInstance as unknown as Access;

  it('draft survives unmount and remount (tab switch), body restored into the editor', async () => {
    const first = mount();
    access(first).replyHtml.set('<p>Moja odpowiedz</p>');
    access(first).onSubjectInput('Re: Zmieniony');
    first.detectChanges();
    await first.whenStable();
    first.destroy();

    const second = mount();
    second.detectChanges();
    const editor = second.nativeElement.querySelector('.email-reply__editor') as HTMLElement;

    expect(access(second).replyHtml()).toBe('<p>Moja odpowiedz</p>');
    expect(access(second).replySubject()).toBe('Re: Zmieniony');
    expect(editor.innerHTML).toBe('<p>Moja odpowiedz</p>');
    expect(drafts.hasUnsentDraft('msg-001')).toBe(true);
  });

  it('calls acceptContact once for two mounts of the same contact', async () => {
    const first = mount();
    await first.whenStable();
    first.destroy();
    const second = mount();
    await second.whenStable();

    expect(contactService.acceptContact).toHaveBeenCalledTimes(1);
  });

  it('retries acceptContact on the next mount after a failure', async () => {
    contactService.acceptContact.mockReturnValueOnce(
      new (await import('rxjs')).Observable((s) => s.error(new Error('500'))),
    );
    mount().destroy();
    mount();
    expect(contactService.acceptContact).toHaveBeenCalledTimes(2);
  });

  it('removes the draft after a successful send and does not resurrect it', async () => {
    const f = mount();
    access(f).replyHtml.set('<p>Odp</p>');
    f.detectChanges();
    await f.whenStable();
    expect(drafts.hasUnsentDraft('msg-001')).toBe(true);

    access(f).sendReply();
    f.detectChanges();
    await f.whenStable();

    expect(emailService['sendReply']).toHaveBeenCalled();
    expect(drafts.hasUnsentDraft('msg-001')).toBe(false);
    expect(drafts.emailDraft('msg-001')).toBeNull();
  });

  it('removes the draft when returning the contact to the queue', async () => {
    const f = mount();
    access(f).replyHtml.set('<p>Odp</p>');
    f.detectChanges();
    await f.whenStable();

    access(f).cancelReply();
    f.detectChanges();
    await f.whenStable();

    expect(contactService.abandonContact).toHaveBeenCalledWith('msg-001');
    expect(drafts.emailDraft('msg-001')).toBeNull();
  });

  it('keeps drafts of different contacts separate', async () => {
    const a = mount('msg-001');
    access(a).replyHtml.set('<p>A</p>');
    a.detectChanges();
    await a.whenStable();
    a.destroy();

    const b = mount('msg-002');
    expect(access(b).replyHtml()).toBe('');
  });

  function openTab(contactId = 'msg-001'): void {
    tabs.openTab({
      id: 'tab-' + contactId,
      type: 'EMAIL',
      contactId,
      customerName: 'Jan',
      customerIdentifier: 'klient@example.com',
      status: 'ACTIVE',
      startedAt: new Date(),
    });
  }

  it('tab switch during sending: no second send, blocked after remount, finishes while unmounted', async () => {
    const pending = new Subject<EmailMessage>();
    emailService['sendReply'].mockReturnValue(pending);
    openTab();

    const first = mount();
    access(first).replyHtml.set('<p>Odp</p>');
    first.detectChanges();
    await first.whenStable();
    access(first).sendReply();
    first.destroy(); // agent switches to another tab mid-request

    const second = mount();
    second.detectChanges();
    expect(access(second).sending()).toBe(true);
    expect(access(second).canSend()).toBe(false);
    access(second).sendReply();
    expect(emailService['sendReply']).toHaveBeenCalledTimes(1);

    second.destroy(); // still unmounted when the server answers
    pending.next(MESSAGE);
    pending.complete();

    expect(drafts.hasUnsentDraft('msg-001')).toBe(false);
    expect(drafts.isSending('msg-001')).toBe(false);
    expect(tabs.tabs().length).toBe(0);
    expect(notifications.success).toHaveBeenCalledTimes(1);
  });

  it('a send that fails while unmounted is reported when the card is shown again', async () => {
    const pending = new Subject<EmailMessage>();
    emailService['sendReply'].mockReturnValue(pending);
    openTab();
    const first = mount();
    access(first).replyHtml.set('<p>Odp</p>');
    first.detectChanges();
    await first.whenStable();
    access(first).sendReply();
    first.destroy();

    pending.error(new Error('500'));

    const second = mount();
    second.detectChanges();
    await second.whenStable();
    expect(access(second).error()).not.toBeNull();
    expect(access(second).sending()).toBe(false);
    expect(access(second).replyHtml()).toBe('<p>Odp</p>');
    expect(tabs.tabs().length).toBe(1);
  });

  it('sanitizes a restored draft before it reaches the editor', async () => {
    drafts.setEmailDraft('msg-001', {
      bodyHtml: '<p>ok</p><img src=x onerror="alert(1)"><script>alert(2)</script>',
      subject: 'Re: x',
      subjectEdited: false,
      template: null,
      templateVariables: {},
      showVariableForm: false,
      attachments: [],
    });
    const f = mount();
    f.detectChanges();
    const editor = f.nativeElement.querySelector('.email-reply__editor') as HTMLElement;
    expect(editor.innerHTML).toContain('<p>ok</p>');
    expect(editor.innerHTML).not.toContain('onerror');
    expect(editor.innerHTML).not.toContain('script');
    expect(access(f).replyHtml()).not.toContain('onerror');
  });

  it('a template chosen without body text survives a tab switch', async () => {
    const tpl = {
      id: 't1',
      name: 'Powitanie',
      subjectTemplate: '',
      bodyHtml: '',
      variables: ['a'],
    };
    const first = mount();
    (
      first.componentInstance as unknown as { onTemplateSelected: (t: typeof tpl) => void }
    ).onTemplateSelected(tpl);
    first.detectChanges();
    await first.whenStable();
    first.destroy();

    expect(drafts.emailDraft('msg-001')?.template).toEqual(tpl);
    expect(drafts.hasUnsentDraft('msg-001')).toBe(false);
  });
});
