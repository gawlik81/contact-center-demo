import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';

import { EmailContactComponent } from './email-contact.component';
import {
  EmailService,
  EmailMessage,
  EmailTemplate,
} from '../../../services/email.service';
import { PagedResponse } from '../../../../../core/models/paged-response.model';

// ── Helpers ──────────────────────────────────────────────────────────────────

const MOCK_MESSAGE: EmailMessage = {
  id: 'msg-001',
  messageIdHeader: '<msg001@example.com>',
  threadRootMessageId: 'root-001',
  fromAddress: 'klient@example.com',
  toAddresses: ['agent@firma.pl'],
  ccAddresses: [],
  subject: 'Pytanie o zamowienie',
  bodyHtml: '<p>Dzien dobry.</p>',
  direction: 'INBOUND',
  status: 'ACTIVE',
  createdAt: '2026-03-25T10:00:00Z',
  receivedAt: '2026-03-25T10:00:05Z',
};

const MOCK_THREAD_RESPONSE: PagedResponse<EmailMessage> = {
  content: [MOCK_MESSAGE],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
};

const MOCK_TEMPLATE: EmailTemplate = {
  id: 'tpl-001',
  name: 'Powitanie standardowe',
  subjectTemplate: 'Re: {{subject}}',
  bodyHtml: '<p>Dziekujemy za kontakt, {{customerName}}.</p>',
  variables: ['customerName'],
};

const MOCK_TEMPLATES_RESPONSE: PagedResponse<EmailTemplate> = {
  content: [MOCK_TEMPLATE],
  page: 0,
  size: 100,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
};

// Helper type to access protected/private members in tests
type EmailContactAccess = {
  replyHtml: { (): string; set: (v: string) => void };
  replySubject: { (): string; set: (v: string) => void };
  sending: { (): boolean };
  error: { (): string | null };
  thread: { (): EmailMessage[] };
  showVariableForm: { (): boolean };
  templateVariables: { (): Record<string, string> };
  canSend: { (): boolean };
  onTemplateSelected: (t: EmailTemplate) => void;
  sendReply: () => void;
  cancelReply: () => void;
};

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('EmailContactComponent', () => {
  let fixture: ComponentFixture<EmailContactComponent>;
  let component: EmailContactComponent;
  let access: EmailContactAccess;

  let emailServiceMock: {
    getMessage: ReturnType<typeof vi.fn>;
    getThread: ReturnType<typeof vi.fn>;
    sendReply: ReturnType<typeof vi.fn>;
    getTemplates: ReturnType<typeof vi.fn>;
    previewTemplate: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    emailServiceMock = {
      getMessage: vi.fn().mockReturnValue(of(MOCK_MESSAGE)),
      getThread: vi.fn().mockReturnValue(of(MOCK_THREAD_RESPONSE)),
      sendReply: vi.fn().mockReturnValue(of(MOCK_MESSAGE)),
      getTemplates: vi.fn().mockReturnValue(of(MOCK_TEMPLATES_RESPONSE)),
      previewTemplate: vi
        .fn()
        .mockReturnValue(of({ subject: 'Re: Temat', bodyHtml: '<p>Preview</p>' })),
    };

    await TestBed.configureTestingModule({
      imports: [EmailContactComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EmailService, useValue: emailServiceMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(EmailContactComponent);
    component = fixture.componentInstance;
    access = component as unknown as EmailContactAccess;

    // Provide required input
    fixture.componentRef.setInput('contactId', 'msg-001');
    fixture.detectChanges();
  });

  // ── Test: component creates and loads data ──────────────────────────────────

  it('should create the component', () => {
    expect(component).toBeTruthy();
  });

  it('should call getMessage and getThread on init', () => {
    expect(emailServiceMock.getMessage).toHaveBeenCalledWith('msg-001');
    expect(emailServiceMock.getThread).toHaveBeenCalledWith('root-001', 0, 20);
  });

  // ── Test: header renders correctly ─────────────────────────────────────────

  it('should render email subject in the header', () => {
    const el: HTMLElement = fixture.nativeElement;
    const title = el.querySelector('.email-header__title');
    expect(title?.textContent?.trim()).toContain('Pytanie o zamowienie');
  });

  it('should render from address in the header', () => {
    const el: HTMLElement = fixture.nativeElement;
    const from = el.querySelector('.email-header__from');
    expect(from?.textContent).toContain('klient@example.com');
  });

  // ── Test: thread renders ────────────────────────────────────────────────────

  it('should render thread messages after loading', () => {
    const el: HTMLElement = fixture.nativeElement;
    const messages = el.querySelectorAll('cc-email-thread-message');
    expect(messages.length).toBe(1);
  });

  it('should populate thread signal after load', () => {
    expect(access.thread().length).toBe(1);
    expect(access.thread()[0].id).toBe('msg-001');
  });

  // ── Test: reply subject auto-populated ─────────────────────────────────────

  it('should auto-populate reply subject with "Re:" prefix', () => {
    expect(access.replySubject()).toBe('Re: Pytanie o zamowienie');
  });

  it('should NOT add double "Re:" if subject already starts with "Re:"', async () => {
    // Create a new component instance with message already having "Re:" subject
    const reMsg: EmailMessage = { ...MOCK_MESSAGE, subject: 'Re: Pytanie' };
    const getMsgSpy = vi.fn().mockReturnValue(of(reMsg));
    emailServiceMock.getMessage = getMsgSpy;
    emailServiceMock.getThread.mockReturnValue(of(MOCK_THREAD_RESPONSE));

    const fixture2 = TestBed.createComponent(EmailContactComponent);
    fixture2.componentRef.setInput('contactId', 'msg-002');
    fixture2.detectChanges();

    const access2 = fixture2.componentInstance as unknown as EmailContactAccess;
    expect(access2.replySubject()).toBe('Re: Pytanie');
  });

  // ── Test: canSend computed ──────────────────────────────────────────────────

  it('should have canSend = false when replyHtml is empty', () => {
    access.replyHtml.set('');
    fixture.detectChanges();
    expect(access.canSend()).toBe(false);
  });

  it('should have canSend = true when replyHtml has content', () => {
    access.replyHtml.set('<p>Tresc odpowiedzi</p>');
    fixture.detectChanges();
    expect(access.canSend()).toBe(true);
  });

  // ── Test: selecting a template fills editor ─────────────────────────────────

  it('should fill replyHtml after selecting a template without variables', () => {
    const noVarTemplate: EmailTemplate = {
      ...MOCK_TEMPLATE,
      id: 'tpl-no-var',
      variables: [],
      bodyHtml: '<p>Szablon bez zmiennych</p>',
    };

    access.onTemplateSelected(noVarTemplate);
    fixture.detectChanges();

    expect(access.replyHtml()).toBe('<p>Szablon bez zmiennych</p>');
    expect(access.showVariableForm()).toBe(false);
  });

  it('should show variable form when template has variables', () => {
    access.onTemplateSelected(MOCK_TEMPLATE);
    fixture.detectChanges();

    expect(access.showVariableForm()).toBe(true);
    expect(Object.keys(access.templateVariables())).toContain('customerName');
  });

  // ── Test: sendReply calls EmailService and emits replySent ─────────────────

  it('should call EmailService.sendReply and emit replySent on successful send', () => {
    let replySentFired = false;
    component.replySent.subscribe(() => (replySentFired = true));

    access.replyHtml.set('<p>Odpowiedz testowa</p>');
    access.replySubject.set('Re: Pytanie o zamowienie');
    fixture.detectChanges();

    access.sendReply();
    fixture.detectChanges();

    expect(emailServiceMock.sendReply).toHaveBeenCalledOnce();
    expect(emailServiceMock.sendReply).toHaveBeenCalledWith(
      'msg-001',
      expect.objectContaining({
        bodyHtml: '<p>Odpowiedz testowa</p>',
        subject: 'Re: Pytanie o zamowienie',
      }),
    );
    expect(replySentFired).toBe(true);
  });

  it('should set error signal and NOT emit replySent when sendReply fails', () => {
    emailServiceMock.sendReply.mockReturnValue(throwError(() => new Error('Network error')));

    let replySentFired = false;
    component.replySent.subscribe(() => (replySentFired = true));

    access.replyHtml.set('<p>Odpowiedz</p>');
    fixture.detectChanges();

    access.sendReply();
    fixture.detectChanges();

    expect(access.error()).not.toBeNull();
    expect(replySentFired).toBe(false);
  });

  // ── Test: canSend = false while sending ────────────────────────────────────

  it('should not send when replyHtml is whitespace only', () => {
    access.replyHtml.set('   ');
    fixture.detectChanges();

    access.sendReply();

    expect(emailServiceMock.sendReply).not.toHaveBeenCalled();
  });

  // ── Test: cancelReply emits replySent ──────────────────────────────────────

  it('should emit replySent when cancelReply is called', () => {
    let replySentFired = false;
    component.replySent.subscribe(() => (replySentFired = true));

    access.cancelReply();

    expect(replySentFired).toBe(true);
  });
});
