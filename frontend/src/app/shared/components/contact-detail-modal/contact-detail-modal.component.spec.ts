import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, of, throwError } from 'rxjs';
import pl from '../../../../../public/i18n/pl.json';
import { NotificationService } from '../../../core/services/notification.service';
import { ContactService } from '../../../features/agent/services/contact.service';
import { ContactDetailModalComponent } from './contact-detail-modal.component';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import type { EmailPreviewMessage } from '../../../core/models/contact.model';

function msg(over: Partial<EmailPreviewMessage>): EmailPreviewMessage {
  return {
    messageId: 'm1',
    from: 'a@x.pl',
    to: 'b@x.pl',
    cc: null,
    subject: 'Temat',
    bodyHtml: null,
    bodyText: 'tekst',
    receivedAt: '2026-05-01T10:00:00Z',
    messageAt: '2026-05-01T10:00:00Z',
    direction: 'INBOUND',
    attachments: [],
    emlAvailable: true,
    ...over,
  };
}

describe('ContactDetailModalComponent', () => {
  let fixture: ComponentFixture<ContactDetailModalComponent>;
  const getContact = vi.fn();
  const getEmailPreview = vi.fn();
  const getEmailMessageEmlUrl = vi.fn();
  const getRecordingUrl = vi.fn();
  const error = vi.fn();
  let clickSpy: ReturnType<typeof vi.spyOn>;
  let clicked: HTMLAnchorElement[];

  const base = {
    contactId: 'c1',
    status: 'COMPLETED',
    direction: 'INBOUND',
    startedAt: '2026-05-01T10:00:00Z',
    remoteAddress: 'a@x.pl',
  };

  function open(contact: Record<string, unknown>): void {
    getContact.mockReturnValue(of({ ...base, ...contact }));
    fixture.componentRef.setInput('contactId', 'c1');
    fixture.detectChanges();
    fixture.detectChanges();
  }

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    vi.clearAllMocks();
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    };
    clicked = [];
    clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push(this);
    });
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: NotificationService, useValue: { error, success: vi.fn(), warning: vi.fn() } },
        { provide: HttpClient, useValue: { get: vi.fn() } },
        {
          provide: ContactService,
          useValue: {
            getContact,
            getEmailPreview,
            getEmailMessageEmlUrl,
            getRecordingUrl,
            getRelatedContacts: () => of([]),
            getContactEvents: () => of([]),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(ContactDetailModalComponent);
  });

  afterEach(() => clickSpy.mockRestore());

  it('renders all messages chronologically even when delivered out of order', () => {
    getEmailPreview.mockReturnValue(
      of({
        messages: [
          msg({ messageId: 'm2', from: 'second@x.pl', messageAt: '2026-05-02T10:00:00Z' }),
          msg({ messageId: 'm1', from: 'first@x.pl', messageAt: '2026-05-01T10:00:00Z' }),
        ],
        totalMessages: 2,
        truncated: false,
      }),
    );
    open({ channel: 'EMAIL' });

    const senders = Array.from(el().querySelectorAll('.email-preview__sender')).map((n) =>
      n.textContent?.trim(),
    );
    expect(senders).toEqual(['first@x.pl', 'second@x.pl']);
    expect(el().querySelectorAll('ol.email-thread > li').length).toBe(2);
    expect(el().querySelectorAll('h3.email-preview__title').length).toBe(2);
  });

  it('shows the EML button only for messages with emlAvailable and names it with sender', () => {
    getEmailPreview.mockReturnValue(
      of({
        messages: [
          msg({ messageId: 'm1', from: 'with@x.pl', emlAvailable: true }),
          msg({ messageId: 'm2', from: 'without@x.pl', emlAvailable: false }),
        ],
        totalMessages: 2,
        truncated: false,
      }),
    );
    open({ channel: 'EMAIL' });

    const buttons = el().querySelectorAll('.email-preview__eml');
    expect(buttons.length).toBe(1);
    const label = buttons[0].getAttribute('aria-label') ?? '';
    expect(label).toContain('with@x.pl');
    // Label in name: the visible text is part of the accessible name.
    expect(label).toContain(buttons[0].textContent!.trim());
  });

  it('downloads a message EML via its presigned URL', () => {
    getEmailPreview.mockReturnValue(
      of({ messages: [msg({ messageId: 'm1' })], totalMessages: 1, truncated: false }),
    );
    getEmailMessageEmlUrl.mockReturnValue(
      of({ presignedUrl: 'https://s3/m1.eml', expiresAt: 'x', fileName: 'm1.eml' }),
    );
    open({ channel: 'EMAIL' });

    (el().querySelector('.email-preview__eml') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(getEmailMessageEmlUrl).toHaveBeenCalledWith('c1', 'm1');
    expect(clickSpy).toHaveBeenCalledTimes(1);
    const a = clicked[0];
    expect(a.getAttribute('href')).toBe('https://s3/m1.eml');
    expect(a.target).toBe('_blank');
    expect(a.rel).toBe('noopener noreferrer');
    expect(a.getAttribute('download')).toBe('m1.eml');
    expect(document.body.contains(a)).toBe(false);
  });

  it('one failing EML download does not break the others', () => {
    getEmailPreview.mockReturnValue(
      of({
        messages: [
          msg({ messageId: 'm1', messageAt: '2026-05-01T10:00:00Z' }),
          msg({ messageId: 'm2', messageAt: '2026-05-02T10:00:00Z' }),
        ],
        totalMessages: 2,
        truncated: false,
      }),
    );
    getEmailMessageEmlUrl.mockImplementation((_c: string, id: string) =>
      id === 'm1'
        ? throwError(() => new Error('404'))
        : of({ presignedUrl: 'https://s3/m2.eml', expiresAt: 'x', fileName: 'm2.eml' }),
    );
    open({ channel: 'EMAIL' });

    const [b1, b2] = Array.from(el().querySelectorAll<HTMLButtonElement>('.email-preview__eml'));
    b1.click();
    fixture.detectChanges();
    expect(error).toHaveBeenCalledTimes(1);
    expect(el().querySelectorAll('.email-preview__error').length).toBe(1);
    expect(b1.getAttribute('aria-label')).toContain(pl.contactDetailModal.retry);
    expect(b2.getAttribute('aria-disabled')).toBe('false');

    b2.click();
    fixture.detectChanges();
    expect(clickSpy).toHaveBeenCalledTimes(1);
    // m1 stays in its error state, retry is offered
    expect(el().querySelectorAll('.email-preview__error').length).toBe(1);
  });

  it('disables the button while a download is loading', () => {
    const pending = new Subject<never>();
    getEmailPreview.mockReturnValue(
      of({ messages: [msg({ messageId: 'm1' })], totalMessages: 1, truncated: false }),
    );
    getEmailMessageEmlUrl.mockReturnValue(pending);
    open({ channel: 'EMAIL' });

    const b = el().querySelector<HTMLButtonElement>('.email-preview__eml')!;
    b.click();
    fixture.detectChanges();
    expect(b.getAttribute('aria-disabled')).toBe('true');
    expect(b.getAttribute('aria-busy')).toBe('true');
    expect(b.getAttribute('aria-label')).toContain(pl.contactDetailModal.loadingEml);
  });

  it('announces truncation in an always-mounted live region naming the latest messages', () => {
    getEmailPreview.mockReturnValue(
      of({ messages: [msg({})], totalMessages: 150, truncated: true }),
    );
    getContact.mockReturnValue(of({ ...base, channel: 'EMAIL' }));
    fixture.componentRef.setInput('contactId', 'c1');
    fixture.detectChanges();
    fixture.detectChanges();

    const notice = el().querySelector('p.email-thread-notice[role="status"]')!;
    expect(notice.textContent?.trim()).toBe(
      'Wyświetlono najnowsze 1 z 150 wiadomości; starsze wiadomości nie są wyświetlane.',
    );
  });

  it('keeps the live region mounted but empty when nothing is truncated', () => {
    getEmailPreview.mockReturnValue(
      of({ messages: [msg({})], totalMessages: 1, truncated: false }),
    );
    open({ channel: 'EMAIL' });
    const notice = el().querySelector('p.email-thread-notice[role="status"]')!;
    expect(notice.textContent?.trim()).toBe('');
  });

  it('renders bodies only inside sandboxed iframes and never injects message HTML into the host DOM', () => {
    getEmailPreview.mockReturnValue(
      of({
        messages: [
          msg({
            messageId: 'm1',
            bodyHtml:
              '<p>hi</p><img src="x" onerror="alert(1)"><script>alert(2)</script><style>body{display:none}</style>',
          }),
        ],
        totalMessages: 1,
        truncated: false,
      }),
    );
    open({ channel: 'EMAIL' });

    const host = el();
    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('script')).toBeNull();
    expect(host.querySelector('[onerror]')).toBeNull();
    expect(host.querySelector('.email-preview__body')).toBeNull();
    const frame = host.querySelector('iframe')!;
    const sandbox = frame.getAttribute('sandbox') ?? '';
    expect(sandbox).not.toContain('allow-scripts');
    const srcdoc = frame.getAttribute('srcdoc') ?? '';
    expect(srcdoc).toContain('<p>hi</p>');
    expect(srcdoc).not.toContain('onerror');
    expect(srcdoc).not.toContain('<script');
    expect(srcdoc).not.toContain('display:none');
    expect(srcdoc).toContain('Content-Security-Policy');
    expect(srcdoc).toContain("default-src 'none'");
  });

  it('falls back to escaped plain text and shows attachments (tracked by index)', () => {
    getEmailPreview.mockReturnValue(
      of({
        messages: [
          msg({
            messageId: 'm2',
            bodyText: '<b>plain</b>',
            attachments: [
              { filename: 'f.pdf', contentType: 'a', sizeBytes: 2048, s3Key: 'k' },
              { filename: 'g.pdf', contentType: 'a', sizeBytes: 10, s3Key: 'k' },
            ],
          }),
        ],
        totalMessages: 1,
        truncated: false,
      }),
    );
    open({ channel: 'EMAIL' });

    const srcdoc = el().querySelector('iframe')!.getAttribute('srcdoc') ?? '';
    expect(srcdoc).toContain('&lt;b&gt;plain&lt;/b&gt;');
    const links = el().querySelectorAll('.email-preview__attachment-link');
    expect(links.length).toBe(2);
    expect(links[0].textContent).toContain('f.pdf');
  });

  it('collapses older messages by default and expands on demand', () => {
    getEmailPreview.mockReturnValue(
      of({
        messages: [
          msg({ messageId: 'm1', from: 'old@x.pl', messageAt: '2026-05-01T10:00:00Z' }),
          msg({ messageId: 'm2', from: 'new@x.pl', messageAt: '2026-05-02T10:00:00Z' }),
        ],
        totalMessages: 2,
        truncated: false,
      }),
    );
    open({ channel: 'EMAIL' });

    expect(el().querySelectorAll('iframe').length).toBe(1);
    const toggles = el().querySelectorAll<HTMLButtonElement>('.email-preview__toggle');
    expect(toggles[0].getAttribute('aria-expanded')).toBe('false');
    expect(toggles[1].getAttribute('aria-expanded')).toBe('true');

    toggles[0].click();
    fixture.detectChanges();
    expect(el().querySelectorAll('iframe').length).toBe(2);
    expect(toggles[0].getAttribute('aria-expanded')).toBe('true');
  });

  it('single-message EMAIL contact is expanded without a toggle and hides contact-level recording', () => {
    getEmailPreview.mockReturnValue(
      of({ messages: [msg({})], totalMessages: 1, truncated: false }),
    );
    open({ channel: 'EMAIL', recordingUrl: 's3://x.eml' });

    expect(el().querySelectorAll('.email-thread__item').length).toBe(1);
    expect(el().querySelector('.email-preview__toggle')).toBeNull();
    expect(el().querySelector('iframe')).not.toBeNull();
    expect(el().querySelector('#contact-section-recording')).toBeNull();
    expect(el().querySelector('h2#contact-section-email')).not.toBeNull();
  });

  it('shows a dedicated empty state when the contact has no messages', () => {
    getEmailPreview.mockReturnValue(of({ messages: [], totalMessages: 0, truncated: false }));
    open({ channel: 'EMAIL' });
    expect(el().querySelector('.email-empty')?.textContent).toContain(
      pl.contactDetailModal.emailNoMessages,
    );
  });

  it('shows an error with retry for a 500 and recovers on retry', () => {
    getEmailPreview.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    open({ channel: 'EMAIL' });
    expect(el().querySelector('.inline-error')).not.toBeNull();

    getEmailPreview.mockReturnValue(
      of({ messages: [msg({})], totalMessages: 1, truncated: false }),
    );
    el().querySelector<HTMLButtonElement>('.inline-error .btn-link')!.click();
    fixture.detectChanges();
    expect(el().querySelectorAll('.email-thread__item').length).toBe(1);
  });

  it.each([404, 409])('does not offer retry for a %i preview error', (status) => {
    getEmailPreview.mockReturnValue(throwError(() => new HttpErrorResponse({ status })));
    open({ channel: 'EMAIL' });
    expect(el().querySelector('.inline-error')).not.toBeNull();
    expect(el().querySelector('.inline-error .btn-link')).toBeNull();
  });

  it('ignores a late preview response after the contact was closed and reopened', () => {
    const first = new Subject<unknown>();
    const second = new Subject<unknown>();
    getEmailPreview.mockReturnValueOnce(first).mockReturnValueOnce(second);
    open({ channel: 'EMAIL' });

    fixture.componentRef.setInput('contactId', null);
    fixture.detectChanges();
    fixture.componentRef.setInput('contactId', 'c1');
    fixture.detectChanges();
    fixture.detectChanges();

    first.next({ messages: [msg({ from: 'late@x.pl' })], totalMessages: 1, truncated: false });
    fixture.detectChanges();
    expect(el().textContent).not.toContain('late@x.pl');

    second.next({ messages: [msg({ from: 'fresh@x.pl' })], totalMessages: 1, truncated: false });
    fixture.detectChanges();
    expect(el().textContent).toContain('fresh@x.pl');
  });

  it('does not start a download from an EML response that arrives after reopening the contact', () => {
    getEmailPreview.mockReturnValue(
      of({ messages: [msg({})], totalMessages: 1, truncated: false }),
    );
    const eml = new Subject<unknown>();
    getEmailMessageEmlUrl.mockReturnValue(eml);
    open({ channel: 'EMAIL' });
    el().querySelector<HTMLButtonElement>('.email-preview__eml')!.click();

    fixture.componentRef.setInput('contactId', null);
    fixture.detectChanges();
    fixture.componentRef.setInput('contactId', 'c1');
    fixture.detectChanges();
    fixture.detectChanges();

    eml.next({ presignedUrl: 'https://s3/late.eml', expiresAt: 'x', fileName: 'late.eml' });
    expect(clicked.length).toBe(0);
  });

  it('PHONE contact keeps the recording section and never calls email-preview', () => {
    open({ channel: 'PHONE', recordingUrl: 's3://rec.mp3' });

    expect(getEmailPreview).not.toHaveBeenCalled();
    expect(el().querySelector('#contact-section-recording')).not.toBeNull();
    expect(el().querySelector('#contact-section-email')).toBeNull();
  });
});
