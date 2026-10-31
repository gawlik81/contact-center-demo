import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { catchError, of, timeout } from 'rxjs';
import { GdprService } from '../services/gdpr.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import {
  AnonymizePreviewResponse,
  GDPR_PREVIEW_COUNT_LABEL_KEYS,
  GDPR_PREVIEW_COUNT_ORDER,
} from '../gdpr.model';

type PreviewState = 'loading' | 'loaded' | 'error';

/** Podgląd D9 musi się załadować zanim potwierdzenie zostanie odblokowane — zawieszony request
 *  (nie tylko jawny błąd HTTP) też ma zablokować potwierdzenie nieodwracalnej operacji. */
const PREVIEW_TIMEOUT_MS = 15000;

interface PreviewRow {
  key: string;
  labelKey: string;
  count: number;
}

@Component({
  selector: 'app-gdpr-anonymize-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, FormsModule],
  templateUrl: './gdpr-anonymize-modal.component.html',
  styleUrl: './gdpr-anonymize-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class GdprAnonymizeModalComponent implements OnInit, AfterViewInit {
  private readonly gdprService = inject(GdprService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  readonly customerId = input.required<string>();
  readonly customerName = input.required<string>();

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly confirmText = signal('');
  readonly isLoading = signal(false);

  readonly previewState = signal<PreviewState>('loading');
  readonly preview = signal<AnonymizePreviewResponse | null>(null);

  readonly hasIdentifierMatches = computed(() => (this.preview()?.matchedByIdentifier ?? 0) > 0);

  readonly previewRows = computed<PreviewRow[]>(() => {
    const counts = this.preview()?.counts ?? {};
    const keys = Object.keys(counts);
    keys.sort((a, b) => {
      const ia = GDPR_PREVIEW_COUNT_ORDER.indexOf(a);
      const ib = GDPR_PREVIEW_COUNT_ORDER.indexOf(b);
      if (ia === -1 && ib === -1) return a.localeCompare(b);
      if (ia === -1) return 1;
      if (ib === -1) return -1;
      return ia - ib;
    });
    return keys.map((key) => ({
      key,
      labelKey: GDPR_PREVIEW_COUNT_LABEL_KEYS[key] ?? key,
      count: counts[key],
    }));
  });

  /** Expected confirmation phrase in the active UI language (re-emits on language change). */
  readonly confirmPhrase = toSignal(
    this.transloco.selectTranslate<string>('supervisor.gdprAnonymize.confirmPhrase'),
    { initialValue: '' },
  );

  /** Typed text matches the phrase (case-insensitive, trimmed); never true for an empty phrase. */
  readonly isPhraseMatching = computed(() => {
    const phrase = this.confirmPhrase().trim().toLocaleLowerCase();
    return phrase.length > 0 && this.confirmText().trim().toLocaleLowerCase() === phrase;
  });

  readonly isConfirmEnabled = () =>
    this.isPhraseMatching() && !this.isLoading() && this.previewState() === 'loaded';

  ngOnInit(): void {
    this.loadPreview();
  }

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  /** Woła GET .../gdpr/anonymize/preview (D9 = A) — błąd lub timeout blokuje potwierdzenie. */
  loadPreview(): void {
    this.previewState.set('loading');
    this.preview.set(null);

    this.gdprService
      .previewAnonymize(this.customerId())
      .pipe(
        timeout(PREVIEW_TIMEOUT_MS),
        catchError(() => of(null)),
      )
      .subscribe((preview) => {
        if (preview) {
          this.preview.set(preview);
          this.previewState.set('loaded');
        } else {
          this.previewState.set('error');
        }
      });
  }

  onEscapeKey(event: Event): void {
    if (this.isLoading()) {
      event.preventDefault();
      return;
    }
    event.preventDefault();
    this.onCancel();
  }

  onConfirm(): void {
    if (!this.isConfirmEnabled()) return;

    this.isLoading.set(true);
    this.gdprService.anonymize(this.customerId()).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.notifications.success(
          this.transloco.translate('supervisor.gdprAnonymize.successAnonymize'),
        );
        this.confirmed.emit();
      },
      error: () => {
        this.isLoading.set(false);
        this.notifications.error(
          this.transloco.translate('supervisor.gdprAnonymize.errorAnonymize'),
        );
      },
    });
  }

  onCancel(): void {
    if (this.isLoading()) return;
    this.cancelled.emit();
  }
}
