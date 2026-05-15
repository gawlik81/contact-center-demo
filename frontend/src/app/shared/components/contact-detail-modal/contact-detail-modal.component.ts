import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
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
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { catchError, of } from 'rxjs';
import {
  ContactEventResponse,
  ContactResponse,
  EmailPreviewResponse,
  RecordingUrlResponse,
  RelatedItem,
} from '../../../core/models/contact.model';
import { ContactService } from '../../../features/agent/services/contact.service';
import { NotificationService } from '../../../core/services/notification.service';
import { AudioPlayerComponent } from '../audio-player/audio-player.component';

type ModalLoadState = 'idle' | 'loading' | 'loaded' | 'error';
type RecordingState = 'idle' | 'loading' | 'loaded' | 'error';

@Component({
  selector: 'app-contact-detail-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, AudioPlayerComponent, TranslocoModule],
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
  private readonly sanitizer = inject(DomSanitizer);
  private readonly transloco = inject(TranslocoService);

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

  readonly events = signal<ContactEventResponse[]>([]);
  readonly eventsState = signal<'idle' | 'loading' | 'loaded' | 'error'>('idle');

  readonly headerSubtitle = computed(() => {
    const c = this.contact();
    if (!c) return '';
    const channel = c.channel === 'EMAIL' ? 'Email' : 'Telefon';
    const dir = c.direction === 'OUTBOUND' ? 'wychodzący' : 'przychodzący';
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
    if (c.channel === 'EMAIL') return !!c.recordingUrl;
    return c.channel === 'PHONE' && !!c.recordingUrl;
  });

  readonly isEmailRecording = computed(() => {
    const c = this.contact();
    return c?.channel === 'EMAIL';
  });

  readonly safeEmailHtml = computed((): SafeHtml | null => {
    const ep = this.emailPreview();
    if (!ep?.bodyHtml) return null;
    return this.sanitizer.bypassSecurityTrustHtml(ep.bodyHtml);
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
    this.loadState.set('loading');
    this.contact.set(null);
    this.recordingState.set('idle');
    this.recordingUrl.set(null);
    this.relatedContacts.set([]);
    this.emailPreviewState.set('idle');
    this.emailPreview.set(null);
    this.events.set([]);
    this.eventsState.set('idle');

    this.contactService
      .getContact(id)
      .pipe(
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
    this.contactService
      .getEmailPreview(id)
      .pipe(
        catchError(() => {
          this.emailPreviewState.set('error');
          this.notifications.error(
            this.transloco.translate('contactDetailModal.errorEmailPreview'),
          );
          return of(null);
        }),
      )
      .subscribe((preview) => {
        if (preview) {
          this.emailPreview.set(preview);
          this.emailPreviewState.set('loaded');
        }
      });
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

  protected getStageContext(event: ContactEventResponse): string {
    const m = event.metadata;
    if (event.stage === 'TRANSFER' || event.stage === 'CONSULTING') {
      const who = m['target_agent_name'] ?? m['target'] ?? '';
      const type = m['transfer_type'] ? ` (${m['transfer_type']})` : '';
      return who + type;
    }
    return m['ivr_tree_name'] ?? m['queue_name'] ?? m['agent_name'] ?? '';
  }

  private reset(): void {
    this.loadState.set('idle');
    this.contact.set(null);
    this.recordingState.set('idle');
    this.recordingUrl.set(null);
    this.relatedContacts.set([]);
    this.relatedLoading.set(false);
    this.emailPreviewState.set('idle');
    this.emailPreview.set(null);
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
    return this.transloco.translate(`contactDetailModal.statusLabels.${status}`);
  }

  getDirectionLabel(direction: string): string {
    return this.transloco.translate(`contactDetailModal.directionLabels.${direction}`);
  }

  getCallbackStatusLabel(status: string): string {
    return this.transloco.translate(`contactDetailModal.callbackStatusLabels.${status}`);
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
