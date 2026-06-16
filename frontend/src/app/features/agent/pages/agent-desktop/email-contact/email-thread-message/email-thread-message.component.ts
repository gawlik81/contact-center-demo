import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  ViewChild,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EmailAttachment, EmailMessage, EmailService } from '../../../../services/email.service';

@Component({
  selector: 'cc-email-thread-message',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe],
  templateUrl: './email-thread-message.component.html',
  styleUrl: './email-thread-message.component.scss',
})
export class EmailThreadMessageComponent {
  message = input.required<EmailMessage>();

  private readonly transloco = inject(TranslocoService);
  private readonly emailService = inject(EmailService);
  private readonly destroyRef = inject(DestroyRef);

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
    const noContentLabel = this.transloco.translate('agent.emailThread.noContent');
    const content =
      html ||
      (text
        ? `<pre style="white-space:pre-wrap;word-break:break-word">${text.replace(/</g, '&lt;')}</pre>`
        : `<p style="color:#64748b;font-style:italic">${noContentLabel}</p>`);
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

  protected downloadAttachment(attachment: EmailAttachment): void {
    this.emailService
      .getAttachmentDownloadUrl(attachment.s3Key)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (resp) => {
          window.open(resp.url, '_blank', 'noopener,noreferrer');
        },
        error: () => {
          // non-critical — fail silently; user can retry
        },
      });
  }

  protected formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }
}
