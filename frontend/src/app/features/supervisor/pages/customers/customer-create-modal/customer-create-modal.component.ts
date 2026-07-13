import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormArray,
  FormBuilder,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, finalize, of } from 'rxjs';
import { CustomerService } from '../services/customer.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { CustomerResponse } from '../../../models/customer.model';

type CustomFieldFormGroup = FormGroup<{
  key: FormControl<string>;
  value: FormControl<string>;
}>;

@Component({
  selector: 'app-customer-create-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule],
  templateUrl: './customer-create-modal.component.html',
  styleUrl: './customer-create-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class CustomerCreateModalComponent {
  readonly customerCreated = output<CustomerResponse>();

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');
  private readonly customerService = inject(CustomerService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly saving = signal(false);
  readonly visible = signal(false);

  readonly form = this.fb.group({
    firstName: ['', [Validators.maxLength(100)]],
    lastName: ['', [Validators.maxLength(100)]],
    externalId: ['', [Validators.maxLength(255)]],
    phones: [''],
    emails: [''],
    customFields: this.fb.array<CustomFieldFormGroup>([]),
    gdprConsent: this.fb.group({
      consent_given: [false],
      marketing_consent: [false],
    }),
  });

  get customFieldsArray(): FormArray {
    return this.form.get('customFields') as FormArray;
  }

  open(): void {
    this.form.reset();
    this.customFieldsArray.clear();
    this.visible.set(true);
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  close(): void {
    this.visible.set(false);
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && dialog.open) {
      dialog.close();
    }
  }

  onEscapeKey(event: Event): void {
    if (this.visible()) {
      event.preventDefault();
      this.close();
    }
  }

  onBackdropClick(event: MouseEvent): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (event.target === dialog) {
      this.close();
    }
  }

  private createCustomFieldGroup(key = '', value = ''): CustomFieldFormGroup {
    return this.fb.group({
      key: this.fb.control(key, { nonNullable: true, validators: [Validators.maxLength(100)] }),
      value: this.fb.control(value, {
        nonNullable: true,
        validators: [Validators.maxLength(500)],
      }),
    });
  }

  addCustomField(): void {
    this.customFieldsArray.push(this.createCustomFieldGroup());
  }

  removeCustomField(index: number): void {
    this.customFieldsArray.removeAt(index);
  }

  getControl(array: FormArray, index: number): AbstractControl {
    return array.at(index);
  }

  onSubmit(): void {
    if (this.form.invalid || this.saving()) return;

    const raw = this.form.getRawValue();

    const phone = this.parseCommaSeparated(raw.phones ?? '');
    const email = this.parseCommaSeparated(raw.emails ?? '');
    const customFields = Object.fromEntries(
      raw.customFields
        .map((f) => [f.key.trim(), f.value.trim()] as [string, string])
        .filter(([key]) => key.length > 0),
    );

    const payload = {
      firstName: raw.firstName?.trim() || undefined,
      lastName: raw.lastName?.trim() || undefined,
      externalId: raw.externalId?.trim() || undefined,
      phone,
      email,
      customFields,
      gdprConsent: {
        consent_given: raw.gdprConsent.consent_given,
        marketing_consent: raw.gdprConsent.marketing_consent,
      },
    };

    this.saving.set(true);

    this.customerService
      .createCustomer(payload)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((err: HttpErrorResponse) => {
          if (err.status === 409 && err.error?.detail) {
            this.notifications.error(err.error.detail);
          } else {
            this.notifications.error(
              this.transloco.translate('supervisor.customerCreate.errorCreate'),
            );
          }
          return of(null);
        }),
        finalize(() => this.saving.set(false)),
      )
      .subscribe((result) => {
        if (result !== null) {
          this.notifications.success(
            this.transloco.translate('supervisor.customerCreate.successCreate'),
          );
          this.customerCreated.emit(result);
          this.close();
        }
      });
  }

  onCancel(): void {
    this.close();
  }

  private parseCommaSeparated(value: string): string[] {
    return value
      .split(',')
      .map((v) => v.trim())
      .filter((v) => v.length > 0);
  }
}
