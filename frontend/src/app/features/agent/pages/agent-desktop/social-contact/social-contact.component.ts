import { TranslocoModule } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  ViewChild,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { filter } from 'rxjs';

import { SocialContactService } from '../../../services/social-contact.service';
import { WebSocketService } from '../../../../../core/services/websocket.service';
import {
  SocialMessage,
  SocialPlatform,
  SocialMessageReceivedPayload,
} from '../../../models/social-message.model';
import { WsEvent } from '../../../models/ws-event.model';

@Component({
  selector: 'cc-social-contact',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe],
  templateUrl: './social-contact.component.html',
  styleUrl: './social-contact.component.scss',
})
export class SocialContactComponent implements OnInit {
  contactId = input.required<string>();
  contactStatus = input<string>('ACTIVE');

  @ViewChild('messagesContainer') private messagesContainerRef?: ElementRef<HTMLDivElement>;

  private readonly socialService = inject(SocialContactService);
  private readonly ws = inject(WebSocketService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly sendError = signal<string | null>(null);

  protected readonly messages = signal<SocialMessage[]>([]);
  protected readonly hasMore = signal(false);
  protected readonly currentPage = signal(0);

  protected readonly replyText = signal('');

  protected readonly isReadOnly = computed(() => this.contactStatus() === 'COMPLETED');

  protected readonly canSend = computed(
    () => this.replyText().trim().length > 0 && !this.sending() && !this.isReadOnly(),
  );

  ngOnInit(): void {
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
          if (initial) {
            this.messages.set(response.content);
          } else {
            // Older messages prepended at the top
            this.messages.update((current) => [...response.content, ...current]);
          }
          this.hasMore.set(page < response.totalPages - 1);
          this.currentPage.set(page);
          this.loading.set(false);
          this.loadingMore.set(false);
        },
        error: () => {
          this.error.set('Nie udalo sie zaladowac historii wiadomosci.');
          this.loading.set(false);
          this.loadingMore.set(false);
        },
      });
  }

  private subscribeToWsEvents(): void {
    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'SOCIAL_MESSAGE_RECEIVED'),
      )
      .subscribe((e) => {
        const payload = e.payload as SocialMessageReceivedPayload;
        if (payload.contactId === this.contactId()) {
          this.messages.update((current) => [...current, payload.message]);
          this.scrollToBottom();
        }
      });
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
          this.sendError.set('Nie udalo sie wyslac wiadomosci. Sprobuj ponownie.');
        },
      });
  }

  protected onKeydown(event: KeyboardEvent): void {
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
    setTimeout(() => {
      const container = this.messagesContainerRef?.nativeElement;
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    }, 0);
  }

  protected trackByMessageId(_index: number, msg: SocialMessage): string {
    return msg.messageId;
  }
}
