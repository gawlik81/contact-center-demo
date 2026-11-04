import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  OnInit,
  ViewChild,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { filter, finalize } from 'rxjs';

import { SocialContactService } from '../../../services/social-contact.service';
import { WebSocketService } from '../../../../../core/services/websocket.service';
import { SocialMessage, SocialPlatform } from '../../../models/social-message.model';
import { ContactMessageReceivedPayload, WsEvent } from '../../../models/ws-event.model';
import { ContactTabStatus } from '../../../models/contact-tab.model';
import { AsyncContactAcceptService } from '../../../services/async-contact-accept.service';
import { ContactDraftStore } from '../../../services/contact-draft.store';

@Component({
  selector: 'cc-social-contact',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe],
  templateUrl: './social-contact.component.html',
  styleUrl: './social-contact.component.scss',
})
export class SocialContactComponent implements OnInit {
  contactId = input.required<string>();
  contactStatus = input<ContactTabStatus>('ACTIVE');

  @ViewChild('messagesContainer') private messagesContainerRef?: ElementRef<HTMLDivElement>;

  private readonly socialService = inject(SocialContactService);
  private readonly ws = inject(WebSocketService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly transloco = inject(TranslocoService);
  private readonly drafts = inject(ContactDraftStore);
  private readonly asyncAccept = inject(AsyncContactAcceptService);

  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly sendError = signal<string | null>(null);

  protected readonly messages = signal<SocialMessage[]>([]);
  protected readonly hasMore = signal(false);
  protected readonly currentPage = signal(0);

  protected readonly replyText = signal('');
  private draftRestored = false;

  /**
   * A tab is WRAPPING (ACW) once the contact has ended: replying is no longer possible.
   * ContactTabStatus has no 'COMPLETED' value; WRAPPING is the real "contact finished" state.
   */
  protected readonly isReadOnly = computed(() => this.contactStatus() === 'WRAPPING');
  protected readonly readOnlyNoteId = computed(() => `social-readonly-${this.contactId()}`);

  protected readonly canSend = computed(
    () => this.replyText().trim().length > 0 && !this.sending() && !this.isReadOnly(),
  );

  private destroyed = false;

  constructor() {
    this.destroyRef.onDestroy(() => (this.destroyed = true));
    // Keep the reply draft in the store so it survives switching to another tab.
    effect(() => {
      const text = this.replyText();
      if (this.draftRestored) this.drafts.setSocialReply(this.contactId(), text);
    });
  }

  ngOnInit(): void {
    // The agent has the contact: ASSIGNED -> ACTIVE so ContactAssignmentMonitor stops re-queueing
    // it. Safety net, not a duplicate: normally the tab-open path has accepted already and this
    // is a no-op (guard); it matters for a tab opened by a path that did not.
    this.asyncAccept.accept(this.contactId());
    this.replyText.set(this.drafts.socialReply(this.contactId()));
    this.draftRestored = true;
    this.loadMessages(0, true);
    this.subscribeToWsEvents();
  }

  private loadMessages(page: number, initial: boolean): void {
    if (initial) {
      this.loading.set(true);
      this.error.set(null);
    } else {
      this.loadingMore.set(true);
    }

    this.socialService
      .getMessages(this.contactId(), page, 20)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          // The API lists newest first (offset pages); the view is chronological and deduplicated,
          // because a refresh may have shifted the offset (a message seen on two pages).
          const container = this.messagesContainerRef?.nativeElement;
          const heightBefore = container?.scrollHeight ?? 0;
          this.messages.update((current) =>
            mergeChronological(initial ? [] : current, response.content),
          );
          if (initial) {
            // Chronological list: the newest messages are at the bottom, so start there.
            this.scrollToBottom();
          } else if (container) {
            // Older messages are prepended above the viewport: keep what the agent was reading.
            afterNextRender(() => (container.scrollTop += container.scrollHeight - heightBefore), {
              injector: this.injector,
            });
          }
          this.hasMore.set(page < response.totalPages - 1);
          this.currentPage.set(page);
          this.loading.set(false);
          this.loadingMore.set(false);
          if (initial) this.flushPendingRefresh();
        },
        error: () => {
          this.error.set(this.transloco.translate('agent.socialContact.errorLoad'));
          this.loading.set(false);
          this.loadingMore.set(false);
        },
      });
  }

  /**
   * CONTACT_MESSAGE_RECEIVED carries no content: the newest page is re-read from the API and only
   * messages not yet shown (by `messageId`) are merged in. An event during the initial load or
   * another refresh is not dropped: it sets `refreshPending` and one refresh runs afterwards
   * (the query of the load may have started before the message was committed).
   */
  private subscribeToWsEvents(): void {
    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CONTACT_MESSAGE_RECEIVED'),
      )
      .subscribe((e) => {
        const payload = e.payload as ContactMessageReceivedPayload;
        if (payload.contactId === this.contactId() && payload.channel === 'SOCIAL') {
          this.requestRefresh();
        }
      });
  }

  private refreshInFlight = false;
  private refreshPending = false;

  private requestRefresh(): void {
    if (this.destroyed) return;
    if (this.loading() || this.refreshInFlight) {
      this.refreshPending = true;
      return;
    }
    this.refreshPending = false;
    this.refreshInFlight = true;
    this.socialService
      .getMessages(this.contactId(), 0, 20)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.refreshInFlight = false;
          this.flushPendingRefresh();
        }),
      )
      .subscribe({
        next: (response) => {
          const known = new Set(this.messages().map((m) => m.messageId));
          if (response.content.every((m) => known.has(m.messageId))) return;
          // Keep the reading position unless the agent is already at (near) the bottom.
          const atBottom = this.isNearBottom();
          this.messages.update((current) => mergeChronological(current, response.content));
          if (atBottom) this.scrollToBottom();
        },
        // Silent: the event is a hint, the agent keeps the list they already have.
        error: () => undefined,
      });
  }

  private flushPendingRefresh(): void {
    if (this.refreshPending) this.requestRefresh();
  }

  private isNearBottom(): boolean {
    const c = this.messagesContainerRef?.nativeElement;
    if (!c) return true;
    return c.scrollHeight - c.scrollTop - c.clientHeight < 80;
  }

  protected loadMoreMessages(): void {
    if (this.loadingMore() || !this.hasMore()) return;
    this.loadMessages(this.currentPage() + 1, false);
  }

  protected onReplyInput(event: Event): void {
    const textarea = event.target as HTMLTextAreaElement;
    this.replyText.set(textarea.value);
  }

  protected sendMessage(): void {
    if (!this.canSend()) return;

    this.sending.set(true);
    this.sendError.set(null);

    this.socialService
      .sendMessage(this.contactId(), {
        content: this.replyText(),
        attachmentUrls: [],
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sent) => {
          this.messages.update((current) => [...current, sent]);
          this.replyText.set('');
          this.sending.set(false);
          this.scrollToBottom();
        },
        error: () => {
          this.sending.set(false);
          this.sendError.set(this.transloco.translate('agent.socialContact.errorSend'));
        },
      });
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (this.isReadOnly()) return;
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  protected getPlatformLabel(platform: SocialPlatform): string {
    switch (platform) {
      case 'FACEBOOK':
        return 'FB';
      case 'INSTAGRAM':
        return 'IG';
      case 'WHATSAPP':
        return 'WA';
    }
  }

  protected getPlatformColor(platform: SocialPlatform): string {
    switch (platform) {
      case 'FACEBOOK':
        return '#1877f2';
      case 'INSTAGRAM':
        return '#c13584';
      case 'WHATSAPP':
        return '#25d366';
    }
  }

  private scrollToBottom(): void {
    afterNextRender(
      () => {
        const container = this.messagesContainerRef?.nativeElement;
        if (container) container.scrollTop = container.scrollHeight;
      },
      { injector: this.injector },
    );
  }

  protected trackByMessageId(_index: number, msg: SocialMessage): string {
    return msg.messageId;
  }
}

/**
 * Union by `messageId` in chronological order (oldest first), whatever order the API used. Ties
 * and unparsable times are ordered deterministically (id; unparsable last), never by API order.
 */
function mergeChronological(current: SocialMessage[], incoming: SocialMessage[]): SocialMessage[] {
  const byId = new Map<string, SocialMessage>();
  for (const m of [...current, ...incoming]) if (!byId.has(m.messageId)) byId.set(m.messageId, m);
  const time = (m: SocialMessage) => {
    const t = Date.parse(m.sentAt);
    return Number.isNaN(t) ? Number.POSITIVE_INFINITY : t;
  };
  return [...byId.values()].sort((a, b) => {
    const ta = time(a);
    const tb = time(b);
    if (ta !== tb) return ta < tb ? -1 : 1;
    return a.messageId < b.messageId ? -1 : a.messageId > b.messageId ? 1 : 0;
  });
}
