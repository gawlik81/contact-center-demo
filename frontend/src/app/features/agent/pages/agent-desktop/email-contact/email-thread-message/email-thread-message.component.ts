import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  ViewChild,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { buildSafeEmailSrcdoc } from '../../../../../../shared/utils/email-srcdoc.util';
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
  private readonly sanitizer = inject(DomSanitizer);

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

  /**
   * Inbound HTML is untrusted: the body goes through Angular's sanitizer first, the document
   * carries a CSP that blocks every remote resource (tracking pixels) and the iframe is
   * sandboxed without allow-scripts. Computed on the message input, so the SafeHtml keeps its
   * identity (no iframe reload) until the content changes.
   */
  protected readonly srcdoc = computed((): SafeHtml => {
    const m = this.message();
    const label = this.transloco.translate('agent.emailThread.noContent');
    return buildSafeEmailSrcdoc(this.sanitizer, m.bodyHtml, m.bodyText, label);
  });

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
