import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  ViewChild,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { EmailMessage } from '../../../../services/email.service';

@Component({
  selector: 'cc-email-thread-message',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  templateUrl: './email-thread-message.component.html',
  styleUrl: './email-thread-message.component.scss',
})
export class EmailThreadMessageComponent {
  message = input.required<EmailMessage>();

  @ViewChild('iframe') iframeRef?: ElementRef<HTMLIFrameElement>;

  protected readonly iframeHeight = signal<number>(200);

  protected onIframeLoad(event: Event): void {
    const iframe = event.target as HTMLIFrameElement;
    try {
      const doc = iframe.contentDocument || iframe.contentWindow?.document;
      if (doc) {
        const height = doc.documentElement.scrollHeight || doc.body.scrollHeight;
        this.iframeHeight.set(Math.max(100, height + 16));
      }
    } catch {
      // cross-origin frame – keep default height
    }
  }

  protected getSrcdoc(html: string | null | undefined, text?: string | null): string {
    const content = html || (text ? `<pre style="white-space:pre-wrap;word-break:break-word">${text.replace(/</g, '&lt;')}</pre>` : '<p style="color:#64748b;font-style:italic">Brak treści wiadomości</p>');
    return `<!DOCTYPE html><html><head><meta charset="utf-8">
<style>
  * { box-sizing: border-box; }
  body { margin: 0; padding: 8px 12px; font-family: sans-serif; font-size: 14px; line-height: 1.5; color: #1e293b; word-break: break-word; }
  img { max-width: 100%; height: auto; }
  a { color: #1a56db; }
  blockquote { border-left: 3px solid #cbd5e1; margin: 0; padding-left: 1em; color: #64748b; }
</style>
</head><body>${content}</body></html>`;
  }
}
