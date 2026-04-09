import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GdprService } from '../services/gdpr.service';
import { NotificationService } from '../../../../../core/services/notification.service';

@Component({
  selector: 'app-gdpr-anonymize-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './gdpr-anonymize-modal.component.html',
  styleUrl: './gdpr-anonymize-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class GdprAnonymizeModalComponent implements AfterViewInit {
  private readonly gdprService = inject(GdprService);
  private readonly notifications = inject(NotificationService);

  readonly customerId = input.required<string>();
  readonly customerName = input.required<string>();

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly confirmText = signal('');
  readonly isLoading = signal(false);

  readonly isConfirmEnabled = () => this.confirmText() === 'ANONIMIZUJ' && !this.isLoading();

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
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
        this.notifications.success('Dane klienta zostały zanonimizowane.');
        this.confirmed.emit();
      },
      error: (err: { status?: number }) => {
        this.isLoading.set(false);
        if (err.status === 403) {
          this.notifications.error('Brak uprawnień do wykonania tej operacji.');
        } else {
          this.notifications.error('Nie udało się zanonimizować danych klienta.');
        }
      },
    });
  }

  onCancel(): void {
    if (this.isLoading()) return;
    this.cancelled.emit();
  }
}
