import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnChanges,
  SimpleChanges,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { Subject, catchError, of, takeUntil } from 'rxjs';
import {
  ContactEventResponse,
  ContactResponse,
  EmailPreviewMessage,
  EmailPreviewResponse,
  RecordingUrlResponse,
  RelatedItem,
} from '../../../core/models/contact.model';
import { ContactService } from '../../../features/agent/services/contact.service';
import { NotificationService } from '../../../core/services/notification.service';
import { EmailThreadComponent } from '../email-thread/email-thread.component';
import { AudioPlayerComponent } from '../audio-player/audio-player.component';
import { ResizableDialogDirective } from '../../directives/resizable-dialog.directive';

type ModalLoadState = 'idle' | 'loading' | 'loaded' | 'error';
type RecordingState = 'idle' | 'loading' | 'loaded' | 'error';
type EmlState = 'idle' | 'loading' | 'error';

@Component({
  selector: 'app-contact-detail-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    AudioPlayerComponent,
    EmailThreadComponent,
    TranslocoModule,
    ResizableDialogDirective,
  ],
  templateUrl: './contact-detail-modal.component.html',
  styleUrl: './contact-detail-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class ContactDetailModalComponent implements AfterViewInit, OnChanges {
  readonly contactId = input<string | null>(null);
  readonly closed = output<void>();

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');
  private readonly contactService = inject(ContactService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  /** Emits whenever the displayed contact changes or the modal closes; cancels in-flight calls. */
  private readonly contactChange$ = new Subject<void>();

  /** Wewnętrzny sygnał aktualnie wyświetlanego kontaktu — umożliwia nawigację między powiązanymi. */
  private readonly currentContactId = signal<string | null>(null);

  readonly loadState = signal<ModalLoadState>('idle');
  readonly contact = signal<ContactResponse | null>(null);
  readonly recordingState = signal<RecordingState>('idle');
  readonly recordingUrl = signal<RecordingUrlResponse | null>(null);

  readonly relatedContacts = signal<RelatedItem[]>([]);
  readonly relatedLoading = signal(false);

  readonly emailPreviewState = signal<'idle' | 'loading' | 'loaded' | 'error'>('idle');
  readonly emailPreview = signal<EmailPreviewResponse | null>(null);
  /** HTTP status of the last failed preview request (0 for network errors). */
  private readonly emailPreviewErrorStatus = signal<number | null>(null);

  /** Per-message EML download state, keyed by messageId. Missing key means idle. */
  readonly emlStates = signal<Record<string, EmlState>>({});

  readonly events = signal<ContactEventResponse[]>([]);
  readonly eventsState = signal<'idle' | 'loading' | 'loaded' | 'error'>('idle');

  readonly headerSubtitle = computed(() => {
    const c = this.contact();
    if (!c) return '';
    const channel = this.transloco.translate(`contactDetailModal.channelLabels.${c.channel}`);
    const dir = this.transloco.translate(`contactDetailModal.directionLabels.${c.direction}`);
    const date = c.startedAt
      ? new Date(c.startedAt).toLocaleDateString('pl-PL', {
          day: '2-digit',
          month: '2-digit',
          year: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '';
    return [c.remoteAddress, `${channel} ${dir}`, date].filter(Boolean).join(' · ');
  });

  readonly isEmailChannel = computed(() => this.contact()?.channel === 'EMAIL');

  readonly hasRecording = computed(() => {
    const c = this.contact();
    if (!c) return false;
    // EMAIL has per-message EML downloads in the conversation section instead.
    return c.channel === 'PHONE' && !!c.recordingUrl;
  });

  /** Messages in chronological order (oldest first); backend order is re-asserted defensively. */
  readonly emailMessages = computed((): EmailPreviewMessage[] => {
    const messages = this.emailPreview()?.messages ?? [];
    return [...messages].sort((x, y) => Date.parse(x.messageAt) - Date.parse(y.messageAt));
  });

  /** Retrying makes no sense for 4xx answers such as 404/409 (except timeouts and rate limits). */
  readonly emailPreviewRetryable = computed(() => {
    const status = this.emailPreviewErrorStatus();
    if (status === null || status < 400 || status >= 500) return true;
    return status === 408 || status === 429;
  });

  /** Text of the always-mounted live region; empty when nothing is truncated. */
  readonly truncatedNotice = computed(() => {
    const ep = this.emailPreview();
    if (!ep?.truncated) return '';
    return this.transloco.translate('contactDetailModal.emailTruncated', {
      shown: ep.messages.length,
      total: ep.totalMessages,
    });
  });

  readonly durationFormatted = computed(() => {
    const c = this.contact();
    if (!c) return '—';
    if (c.durationSeconds !== undefined && c.durationSeconds >= 0) {
      return this.formatDurationSeconds(c.durationSeconds);
    }
    if (c.startedAt && c.endedAt) {
      const diffS = Math.floor(
        (new Date(c.endedAt).getTime() - new Date(c.startedAt).getTime()) / 1000,
      );
      return diffS >= 0 ? this.formatDurationSeconds(diffS) : '—';
    }
    return '—';
  });

  ngAfterViewInit(): void {
    this.syncDialogState();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if ('contactId' in changes) {
      this.syncDialogState();
    }
  }

  private syncDialogState(): void {
    const id = this.contactId();
    const dialog = this.dialogRef()?.nativeElement;
    if (!dialog) return;

    if (id) {
      if (!dialog.open) {
        dialog.showModal();
      }
      // ngOnChanges and ngAfterViewInit both land here on first open; load only once.
      if (this.currentContactId() === id) return;
      this.currentContactId.set(id);
      this.fetchContact(id);
    } else {
      if (dialog.open) {
        dialog.close();
      }
      this.reset();
    }
  }

  private fetchContact(id: string): void {
    this.contactChange$.next();
    this.loadState.set('loading');
    this.contact.set(null);
    this.recordingState.set('idle');
    this.recordingUrl.set(null);
    this.relatedContacts.set([]);
    this.emailPreviewState.set('idle');
    this.emailPreview.set(null);
    this.emailPreviewErrorStatus.set(null);
    this.emlStates.set({});
    this.events.set([]);
    this.eventsState.set('idle');

    this.contactService
      .getContact(id)
      .pipe(
        takeUntil(this.contactChange$),
        catchError(() => {
          this.loadState.set('error');
          this.notifications.error(this.transloco.translate('contactDetailModal.errorLoad'));
          return of(null);
        }),
      )
      .subscribe((c) => {
        if (c) {
          this.contact.set(c);
          this.loadState.set('loaded');
          if (c.channel === 'EMAIL') this.loadEmailPreview();
          this.loadRelatedContacts(id);
          this.loadEvents(id);
        }
      });
  }

  private loadEvents(id: string): void {
    this.eventsState.set('loading');
    this.contactService
      .getContactEvents(id)
      .pipe(catchError(() => of([])))
      .subscribe((list) => {
        this.events.set(list);
        this.eventsState.set('loaded');
      });
  }

  private loadRelatedContacts(id: string): void {
    this.relatedLoading.set(true);

    this.contactService
      .getRelatedContacts(id)
      .pipe(catchError(() => of([])))
      .subscribe((related) => {
        this.relatedContacts.set(related);
        this.relatedLoading.set(false);
      });
  }

  openRelatedContact(relatedId: string): void {
    this.currentContactId.set(relatedId);
    this.fetchContact(relatedId);
  }

  loadRecording(): void {
    const id = this.currentContactId();
    if (!id || this.recordingState() === 'loading') return;
    this.recordingState.set('loading');

    this.contactService
      .getRecordingUrl(id)
      .pipe(
        catchError(() => {
          this.recordingState.set('error');
          this.notifications.error(this.transloco.translate('contactDetailModal.errorRecording'));
          return of(null);
        }),
      )
      .subscribe((res) => {
        if (res) {
          this.recordingUrl.set(res);
          this.recordingState.set('loaded');
        }
      });
  }

  loadEmailPreview(): void {
    const id = this.currentContactId();
    if (!id || this.emailPreviewState() === 'loading') return;
    this.emailPreviewState.set('loading');
    this.emailPreviewErrorStatus.set(null);
    this.contactService
      .getEmailPreview(id)
      .pipe(takeUntil(this.contactChange$), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (preview) => {
          this.emailPreview.set(preview);
          this.emailPreviewState.set('loaded');
        },
        error: (err: unknown) => {
          this.emailPreviewErrorStatus.set(err instanceof HttpErrorResponse ? err.status : 0);
          this.emailPreviewState.set('error');
          this.notifications.error(
            this.transloco.translate('contactDetailModal.errorEmailPreview'),
          );
        },
      });
  }

  protected emlState(messageId: string): EmlState {
    return this.emlStates()[messageId] ?? 'idle';
  }

  /** Fetches the presigned EML URL of one message and starts the download. Isolated per message. */
  protected downloadMessageEml(message: EmailPreviewMessage): void {
    const contactId = this.currentContactId();
    const messageId = message.messageId;
    if (!contactId || this.emlState(messageId) === 'loading') return;
    this.setEmlState(messageId, 'loading');

    this.contactService
      .getEmailMessageEmlUrl(contactId, messageId)
      .pipe(takeUntil(this.contactChange$), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.setEmlState(messageId, 'idle');
          this.triggerDownload(res.presignedUrl, res.fileName);
        },
        error: () => {
          this.setEmlState(messageId, 'error');
          this.notifications.error(this.transloco.translate('contactDetailModal.errorEml'));
        },
      });
  }

  private setEmlState(messageId: string, state: EmlState): void {
    this.emlStates.update((m) => ({ ...m, [messageId]: state }));
  }

  private triggerDownload(url: string, fileName?: string): void {
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName ?? '';
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    a.hidden = true;
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  protected getStageLabel(stage: ContactEventResponse['stage']): string {
    return this.transloco.translate(`contactDetailModal.stage${stage}`);
  }

  protected formatDuration(seconds: number | null): string {
    if (seconds === null || seconds === undefined) return '—';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return m > 0 ? `${m}m ${s}s` : `${s}s`;
  }

  protected getEventDuration(event: ContactEventResponse): number | null {
    if (event.durationSeconds != null) return event.durationSeconds;
    if (event.endedAt && event.startedAt) {
      return Math.floor(
        (new Date(event.endedAt).getTime() - new Date(event.startedAt).getTime()) / 1000,
      );
    }
    return null;
  }

  protected getStageContext(event: ContactEventResponse): string {
    const m = event.metadata;
    if (event.stage === 'TRANSFER' || event.stage === 'CONSULTING') {
      const targetType = m['target_type'] as string | undefined;
      const transferType = m['transfer_type'] ? ` (${m['transfer_type']})` : '';

      if (targetType === 'QUEUE') {
        const name = (m['target_queue_name'] ?? m['target_queue_id'] ?? '') as string;
        return name + transferType;
      }
      if (targetType === 'AGENT') {
        const name = (m['target_agent_name'] ?? m['target'] ?? '') as string;
        return name + transferType;
      }
      // PHONE lub brak target_type
      const phone = (m['target'] ?? '') as string;
      return phone + transferType;
    }
    return m['ivr_tree_name'] ?? m['queue_name'] ?? m['agent_name'] ?? '';
  }

  private reset(): void {
    this.contactChange$.next();
    this.loadState.set('idle');
    this.contact.set(null);
    this.recordingState.set('idle');
    this.recordingUrl.set(null);
    this.relatedContacts.set([]);
    this.relatedLoading.set(false);
    this.emailPreviewState.set('idle');
    this.emailPreview.set(null);
    this.emailPreviewErrorStatus.set(null);
    this.emlStates.set({});
    this.events.set([]);
    this.eventsState.set('idle');
    this.currentContactId.set(null);
  }

  onEscapeKey(event: Event): void {
    if (this.contactId() !== null) {
      event.preventDefault();
      this.close();
    }
  }

  onBackdropClick(event: MouseEvent): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && event.target === dialog) {
      this.close();
    }
  }

  close(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog?.open) {
      dialog.close();
    }
    this.reset();
    this.closed.emit();
  }

  getChannelLabel(channel: string): string {
    return this.transloco.translate(`contactDetailModal.channelLabels.${channel}`);
  }

  getStatusLabel(status: string): string {
    const key = `supervisor.customerDetail.contactStatusLabels.${status}`;
    const translated = this.transloco.translate(key);
    return translated === key ? status : translated;
  }

  getDispositionLabel(label: string | null, code: string | null): string {
    if (label) return label;
    if (!code) return '—';
    const key = `common.dispositionLabels.${code}`;
    const translated = this.transloco.translate(key);
    return translated === key ? code : translated;
  }

  getDirectionLabel(direction: string): string {
    return this.transloco.translate(`contactDetailModal.directionLabels.${direction}`);
  }

  getCallbackStatusLabel(status: string): string {
    return this.transloco.translate(`contactDetailModal.callbackStatusLabels.${status}`);
  }

  /**
   * Returns the i18n key for a related-contact badge.
   * Transfer detection relies on the status of the related item (PARENT with TRANSFERRED status)
   * or the currently displayed contact (CHILD when current contact is TRANSFERRED).
   */
  getRelatedContactBadgeKey(item: RelatedItem): string {
    if (item.itemType === 'CONTACT') {
      if (item.relationType === 'PARENT' && item.status === 'TRANSFERRED') {
        return 'contactDetailModal.transferSource';
      }
      if (item.relationType === 'CHILD' && this.contact()?.status === 'TRANSFERRED') {
        return 'contactDetailModal.transferTarget';
      }
      return item.relationType === 'PARENT'
        ? 'contactDetailModal.parentContact'
        : 'contactDetailModal.childContact';
    }
    // CALLBACK items — label handled in template via separate translation key
    return 'agent.rescheduleCallback.title';
  }

  protected downloadEmailAttachment(s3Key: string): void {
    const params = new HttpParams().set('s3Key', s3Key);
    this.http.get<{ url: string }>('/api/email/attachments/download', { params }).subscribe({
      next: (resp) => window.open(resp.url, '_blank', 'noopener,noreferrer'),
      error: () =>
        this.notifications.error(this.transloco.translate('contactDetailModal.errorAttachment')),
    });
  }

  private formatDurationSeconds(totalSeconds: number): string {
    const h = Math.floor(totalSeconds / 3600);
    const m = Math.floor((totalSeconds % 3600) / 60);
    const s = totalSeconds % 60;
    if (h > 0) {
      return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
    }
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  }
}
