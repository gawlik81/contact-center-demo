import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';

import { EmailThreadMessageComponent } from './email-thread-message.component';
import { EmailMessage } from '../../../../services/email.service';

const message = (overrides: Partial<EmailMessage>): EmailMessage => ({
  id: 'm1',
  messageIdHeader: '<m1>',
  threadRootMessageId: 'root-1',
  fromAddress: 'k@example.com',
  toAddresses: [],
  ccAddresses: [],
  subject: 'Temat',
  bodyHtml: '',
  direction: 'INBOUND',
  createdAt: '2026-11-04T09:00:00Z',
  ...overrides,
});

describe('EmailThreadMessageComponent body rendering (FE-157)', () => {
  const render = (m: EmailMessage) => {
    TestBed.configureTestingModule({
      imports: [
        EmailThreadMessageComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl: {} },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const f = TestBed.createComponent(EmailThreadMessageComponent);
    f.componentRef.setInput('message', m);
    f.detectChanges();
    return f;
  };
  const srcdocOf = (f: ReturnType<typeof render>) =>
    (f.nativeElement.querySelector('iframe') as HTMLIFrameElement).srcdoc;

  it('blocks remote resources with a CSP and strips scripts', () => {
    const f = render(
      message({
        bodyHtml:
          '<p>Hej</p><img src="https://track.example.com/p.gif"><script>alert(1)</script>' +
          '<img src="x" onerror="alert(2)"><a href="https://example.com">link</a>',
      }),
    );
    const doc = srcdocOf(f);
    expect(doc).toContain('Content-Security-Policy');
    expect(doc).toContain("default-src 'none'");
    expect(doc).toContain('img-src data:');
    expect(doc).toContain('<base target="_blank">');
    expect(doc).not.toContain('<script');
    expect(doc).not.toContain('onerror');
    expect(doc).toContain('rel="noopener noreferrer nofollow"');
  });

  it('sandbox allows popups but never scripts', () => {
    const f = render(message({ bodyHtml: '<p>x</p>' }));
    const sandbox = f.nativeElement.querySelector('iframe').getAttribute('sandbox');
    expect(sandbox).toBe('allow-same-origin allow-popups');
  });

  it('renders plain text and the empty placeholder escaped', () => {
    expect(srcdocOf(render(message({ bodyText: '<b>x</b> & y' })))).toContain(
      '&lt;b&gt;x&lt;/b&gt; &amp; y',
    );
  });

  it('forces every link to a new window, whatever target the mail set', () => {
    const f = render(
      message({
        bodyHtml:
          '<a href="https://a.example" target="_self">a</a>' +
          '<a href="https://b.example" target="_top">b</a>' +
          '<a href="https://c.example">c</a>',
      }),
    );
    const doc = new DOMParser().parseFromString(srcdocOf(f), 'text/html');
    const links = Array.from(doc.querySelectorAll('a'));
    expect(links).toHaveLength(3);
    for (const a of links) {
      expect(a.getAttribute('target')).toBe('_blank');
      expect(a.getAttribute('rel')).toBe('noopener noreferrer nofollow');
    }
    expect(srcdocOf(f)).not.toContain('_self');
    expect(srcdocOf(f)).not.toContain('_top');
  });

  it('neutralises javascript: links', () => {
    const f = render(message({ bodyHtml: '<a href="javascript:alert(1)">x</a>' }));
    expect(srcdocOf(f)).not.toMatch(/href="javascript:/i);
  });

  it('keeps the same srcdoc reference while the message is unchanged (no iframe reload)', () => {
    const f = render(message({ bodyHtml: '<p>x</p>' }));
    const access = f.componentInstance as unknown as { srcdoc: () => unknown };
    const first = access.srcdoc();
    f.detectChanges();
    expect(access.srcdoc()).toBe(first);
  });
});
