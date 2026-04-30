import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { catchError, EMPTY } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { CallbackService } from '../../../agent/services/callback.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { CallbackListItem } from '../../../agent/models/callback.model';

export interface AgentOption {
  id: string;
  name: string;
}

/** Custom validator: date must be in the future */
function futureDateValidator(control: AbstractControl): ValidationErrors | null {
  if (!control.value) return null;
  const selected = new Date(control.value);
  const now = new Date();
  return selected > now ? null : { pastDate: true };
}

@Component({
  selector: 'app-edit-callback-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,ReactiveFormsModule],
  templateUrl: './edit-callback-modal.component.html',
  styleUrl: './edit-callback-modal.component.scss',
})
export class EditCallbackModalComponent implements OnInit {
  readonly callback = input.required<CallbackListItem>();
  readonly agents = input<AgentOption[]>([]);

  readonly saved = output<CallbackListItem>();
  readonly cancelled = output<void>();

  private readonly callbackService = inject(CallbackService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly form = this.fb.group({
    phone: ['', [Validators.required, Validators.pattern(/^\+\d{7,15}$/)]],
    firstName: [''],
    lastName: [''],
    scheduledAt: ['', [Validators.required, futureDateValidator]],
    notes: ['', [Validators.maxLength(500)]],
    agentId: [null as string | null],
  });

  ngOnInit(): void {
    const cb = this.callback();
    const d = new Date(cb.scheduledAt);
    const pad = (n: number) => n.toString().padStart(2, '0');
    const local =
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
      `T${pad(d.getHours())}:${pad(d.getMinutes())}`;

    this.form.patchValue({
      phone: cb.phone,
      firstName: cb.firstName ?? '',
      lastName: cb.lastName ?? '',
      scheduledAt: local,
      notes: cb.notes ?? '',
      agentId: cb.agentId ?? null,
    });

    this.dialogRef().nativeElement.showModal();
  }

  protected cancel(): void {
    this.dialogRef().nativeElement.close();
    this.cancelled.emit();
  }

  protected submit(): void {
    if (this.form.invalid || this.loading()) return;
    this.errorMessage.set(null);
    this.loading.set(true);

    const raw = this.form.getRawValue();

    this.callbackService
      .updateCallback(this.callback().callbackId, {
        phone: raw.phone ?? undefined,
        firstName: raw.firstName?.trim() || undefined,
        lastName: raw.lastName?.trim() || undefined,
        scheduledAt: new Date(raw.scheduledAt!).toISOString(),
        notes: raw.notes?.trim() || undefined,
        agentId: raw.agentId ?? null,
      })
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this.loading.set(false);
          if (err.status === 409) {
            this.errorMessage.set(this.transloco.translate('supervisor.editCallback.errorSave'));
          } else if (err.status === 403) {
            this.errorMessage.set(this.transloco.translate('supervisor.editCallback.errorSave'));
          } else if (err.status === 404) {
            this.errorMessage.set(this.transloco.translate('supervisor.editCallback.errorSave'));
          } else {
            this.errorMessage.set(this.transloco.translate('supervisor.editCallback.errorSave'));
          }
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.loading.set(false);
        this.notifications.success(this.transloco.translate('supervisor.callbacks.successCancel'));
        this.dialogRef().nativeElement.close();
        this.saved.emit(updated);
      });
  }
}
