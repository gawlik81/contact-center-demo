import {
  ChangeDetectionStrategy,
  Component,
  SecurityContext,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { EmailPreviewMessage } from '../../../core/models/contact.model';
import { buildEmailSrcdoc } from '../../utils/email-srcdoc.util';

export type EmlDownloadState = 'idle' | 'loading' | 'error';

/** Iframe body is capped; longer messages scroll inside the frame. */
const MAX_BODY_HEIGHT_PX = 480;

/**
 * Read-only, chronological e-mail conversation. Message bodies are untrusted inbound HTML:
 * they are rendered in a sandboxed iframe (no scripts) from a document whose body was first run
 * through Angular's HTML sanitizer and which carries a CSP blocking remote resources. The host
 * DOM never receives message HTML.
 * Older messages are collapsed (and their iframes not created) until expanded.
 */
@Component({
  selector: 'app-email-thread',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, NgTemplateOutlet, TranslocoModule],
  templateUrl: './email-thread.component.html',
  styleUrl: './email-thread.component.scss',
})
export class EmailThreadComponent {
  /** Messages in chronological order (oldest first). */
  readonly messages = input.required<EmailPreviewMessage[]>();
  /** Per-message EML download state keyed by messageId; missing means idle. */
  readonly emlStates = input<Record<string, EmlDownloadState>>({});

  readonly downloadEml = output<EmailPreviewMessage>();
  readonly downloadAttachment = output<string>();

  private readonly sanitizer = inject(DomSanitizer);
  private readonly transloco = inject(TranslocoService);

  /** User overrides of the default collapsed state, keyed by messageId. */
  private readonly toggled = signal<Record<string, boolean>>({});
  private readonly heights = signal<Record<string, number>>({});

  protected readonly collapsible = computed(() => this.messages().length > 1);

  /** Per-message memo so a SafeHtml keeps its identity (no iframe reload) while content is unchanged. */
  private readonly srcdocCache = new Map<
    string,
    { html: string | null; text: string | null; safe: SafeHtml }
  >();

  /** Srcdoc documents, built only for expanded messages. */
  protected readonly srcdocs = computed((): Record<string, SafeHtml> => {
    const out: Record<string, SafeHtml> = {};
    const live = new Set<string>();
    for (const m of this.messages()) {
      if (!this.isExpanded(m)) continue;
      live.add(m.messageId);
      const cached = this.srcdocCache.get(m.messageId);
      if (cached && cached.html === m.bodyHtml && cached.text === m.bodyText) {
        out[m.messageId] = cached.safe;
        continue;
      }
      const safe = this.buildSrcdoc(m);
      this.srcdocCache.set(m.messageId, { html: m.bodyHtml, text: m.bodyText, safe });
      out[m.messageId] = safe;
    }
    for (const id of this.srcdocCache.keys()) {
      if (!live.has(id)) this.srcdocCache.delete(id);
    }
    return out;
  });

  private buildSrcdoc(m: EmailPreviewMessage): SafeHtml {
    const label = this.transloco.translate('contactDetailModal.emailNoContent');
    let safeBody = m.bodyHtml ? this.sanitizer.sanitize(SecurityContext.HTML, m.bodyHtml) : null;
    // Links open in a new, sandboxed window (base target) without referrer or opener.
    safeBody = safeBody?.replace(/<a\s/gi, '<a rel="noopener noreferrer nofollow" ') ?? null;
    const doc = buildEmailSrcdoc(safeBody, m.bodyText, label, {
      blockRemoteResources: true,
      linksInNewTab: true,
    });
    // The whole document is trusted only because the body inside was sanitized above, the
    // iframe is sandboxed without allow-scripts and the CSP meta blocks remote resources.
    return this.sanitizer.bypassSecurityTrustHtml(doc);
  }

  protected isExpanded(message: EmailPreviewMessage): boolean {
    if (!this.collapsible()) return true;
    const override = this.toggled()[message.messageId];
    if (override !== undefined) return override;
    const list = this.messages();
    return list[list.length - 1]?.messageId === message.messageId;
  }

  protected toggle(message: EmailPreviewMessage): void {
    const next = !this.isExpanded(message);
    this.toggled.update((t) => ({ ...t, [message.messageId]: next }));
  }

  protected emlState(messageId: string): EmlDownloadState {
    return this.emlStates()[messageId] ?? 'idle';
  }

  protected emlActionKey(messageId: string): string {
    const state = this.emlState(messageId);
    if (state === 'loading') return 'contactDetailModal.loadingEml';
    if (state === 'error') return 'contactDetailModal.retry';
    return 'contactDetailModal.downloadEml';
  }

  protected emlAriaLabel(message: EmailPreviewMessage, date: string): string {
    return this.transloco.translate('contactDetailModal.emlActionFor', {
      action: this.transloco.translate(this.emlActionKey(message.messageId)),
      sender: message.from,
      date,
    });
  }

  protected onEmlClick(message: EmailPreviewMessage): void {
    if (this.emlState(message.messageId) === 'loading') return;
    this.downloadEml.emit(message);
  }

  protected frameHeight(messageId: string): number {
    return this.heights()[messageId] ?? 160;
  }

  protected onFrameLoad(messageId: string, event: Event): void {
    const iframe = event.target as HTMLIFrameElement;
    try {
      const doc = iframe.contentDocument;
      // body (not documentElement): the root is never shorter than the frame, so it cannot shrink.
      const h = doc?.body?.scrollHeight || doc?.documentElement.scrollHeight;
      if (h) {
        const height = Math.min(MAX_BODY_HEIGHT_PX, Math.max(80, h + 8));
        this.heights.update((m) => ({ ...m, [messageId]: height }));
      }
    } catch {
      // frame not readable - keep default height
    }
  }

  protected formatAttachmentSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }
}
