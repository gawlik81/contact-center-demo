import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import pl from '../../../../../public/i18n/pl.json';
import type { EmailPreviewMessage } from '../../../core/models/contact.model';
import { EmailThreadComponent } from './email-thread.component';

function msg(over: Partial<EmailPreviewMessage>): EmailPreviewMessage {
  return {
    messageId: 'm1',
    from: 'a@x.pl',
    to: 'b@x.pl',
    cc: null,
    subject: 'Temat',
    bodyHtml: '<p>hej</p>',
    bodyText: null,
    receivedAt: null,
    messageAt: '2026-05-01T10:00:00Z',
    direction: 'INBOUND',
    attachments: [],
    emlAvailable: true,
    ...over,
  };
}

const TWO = [
  msg({ messageId: 'old', from: 'old@x.pl', messageAt: '2026-05-01T10:00:00Z' }),
  msg({ messageId: 'new', from: 'new@x.pl', messageAt: '2026-05-02T10:00:00Z' }),
];

describe('EmailThreadComponent', () => {
  let fixture: ComponentFixture<EmailThreadComponent>;

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const srcdocs = (): Record<string, unknown> =>
    (fixture.componentInstance as unknown as { srcdocs: () => Record<string, unknown> }).srcdocs();

  function render(
    messages: EmailPreviewMessage[],
    emlStates: Record<string, 'idle' | 'loading' | 'error'> = {},
  ): void {
    fixture.componentRef.setInput('messages', messages);
    fixture.componentRef.setInput('emlStates', emlStates);
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
    });
    fixture = TestBed.createComponent(EmailThreadComponent);
  });

  it('expands only the newest message by default', () => {
    render(TWO);
    const toggles = el().querySelectorAll<HTMLButtonElement>('.email-preview__toggle');
    expect(toggles[0].getAttribute('aria-expanded')).toBe('false');
    expect(toggles[1].getAttribute('aria-expanded')).toBe('true');
    expect(el().querySelectorAll('iframe').length).toBe(1);
  });

  it('renders a single message expanded and without a toggle', () => {
    render([msg({})]);
    expect(el().querySelector('.email-preview__toggle')).toBeNull();
    expect(el().querySelectorAll('iframe').length).toBe(1);
  });

  it('toggles a message and updates aria-expanded and aria-controls target', () => {
    render(TWO);
    const toggle = el().querySelector<HTMLButtonElement>('.email-preview__toggle')!;
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(el().querySelector('#' + toggle.getAttribute('aria-controls'))).not.toBeNull();
    expect(el().querySelectorAll('iframe').length).toBe(2);

    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(el().querySelectorAll('iframe').length).toBe(1);
  });

  it('keeps SafeHtml identity of open messages when another message is toggled', () => {
    render(TWO);
    const before = srcdocs()['new'];
    expect(before).toBeDefined();

    el().querySelector<HTMLButtonElement>('.email-preview__toggle')!.click();
    fixture.detectChanges();
    expect(srcdocs()['new']).toBe(before);
    const oldDoc = srcdocs()['old'];
    expect(oldDoc).toBeDefined();

    el().querySelectorAll<HTMLButtonElement>('.email-preview__toggle')[0].click();
    fixture.detectChanges();
    expect(srcdocs()['new']).toBe(before);
  });

  it('rebuilds the document when the message content changes', () => {
    render([msg({ bodyHtml: '<p>one</p>' })]);
    const before = srcdocs()['m1'];
    render([msg({ bodyHtml: '<p>two</p>' })]);
    expect(srcdocs()['m1']).not.toBe(before);
  });

  it('names the EML button by state, containing the visible label', () => {
    const cases: ['idle' | 'loading' | 'error', string][] = [
      ['idle', pl.contactDetailModal.downloadEml],
      ['loading', pl.contactDetailModal.loadingEml],
      ['error', pl.contactDetailModal.retry],
    ];
    for (const [state, visible] of cases) {
      render([msg({})], { m1: state });
      const btn = el().querySelector<HTMLButtonElement>('.email-preview__eml')!;
      const label = btn.getAttribute('aria-label') ?? '';
      expect(btn.textContent?.trim()).toBe(visible);
      expect(label).toContain(visible);
      expect(label).toContain('a@x.pl');
      expect(btn.getAttribute('aria-busy')).toBe(String(state === 'loading'));
    }
  });

  it('emits downloadEml but not while the message is loading', () => {
    const emitted: string[] = [];
    fixture.componentInstance.downloadEml.subscribe((m) => emitted.push(m.messageId));
    render([msg({})], { m1: 'loading' });
    el().querySelector<HTMLButtonElement>('.email-preview__eml')!.click();
    expect(emitted).toEqual([]);

    render([msg({})], { m1: 'idle' });
    el().querySelector<HTMLButtonElement>('.email-preview__eml')!.click();
    expect(emitted).toEqual(['m1']);
  });

  it('opens links in a new sandboxed window: base target, allow-popups, never escape or scripts', () => {
    render([msg({ bodyHtml: '<a href="https://evil.example/">go</a>' })]);
    const frame = el().querySelector('iframe')!;
    const sandbox = (frame.getAttribute('sandbox') ?? '').split(/\s+/);
    expect(sandbox).toContain('allow-popups');
    expect(sandbox).not.toContain('allow-popups-to-escape-sandbox');
    expect(sandbox).not.toContain('allow-scripts');

    const doc = frame.getAttribute('srcdoc') ?? '';
    expect(doc).toContain('<base target="_blank">');
    expect(doc).toContain('rel="noopener noreferrer nofollow"');
    expect(doc).toContain("base-uri 'none'");
    expect(doc).toContain("form-action 'none'");
  });

  it('escapes ampersands, angle brackets and quotes in plain-text bodies', () => {
    render([msg({ bodyHtml: null, bodyText: `a & b <i> "q" 'r' &amp;` })]);
    const doc = el().querySelector('iframe')!.getAttribute('srcdoc') ?? '';
    expect(doc).toContain('a &amp; b &lt;i&gt; &quot;q&quot; &#39;r&#39; &amp;amp;');
    expect(doc).not.toContain('<i>');
  });
});
