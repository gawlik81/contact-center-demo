import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
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
  selector: 'app-customer-edit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule],
  templateUrl: './customer-edit.component.html',
  styleUrl: './customer-edit.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class CustomerEditComponent implements OnInit, AfterViewInit {
  readonly customer = input.required<CustomerResponse>();

  readonly saved = output<void>();
  readonly cancelled = output<void>();

  private readonly customerService = inject(CustomerService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly saving = signal(false);

  readonly customerName = computed(() => {
    const c = this.customer();
    const first = c.firstName ?? '';
    const last = c.lastName ?? '';
    const full = `${first} ${last}`.trim();
    return full || c.phone[0] || c.email[0] || c.customerId;
  });

  readonly form = this.fb.group({
    firstName: ['', [Validators.maxLength(100)]],
    lastName: ['', [Validators.maxLength(100)]],
    externalId: ['', [Validators.maxLength(255)]],
    phones: this.fb.array<string>([]),
    emails: this.fb.array<string>([]),
    customFields: this.fb.array<CustomFieldFormGroup>([]),
    gdprConsent: this.fb.group({
      consent_given: [false],
      marketing_consent: [false],
    }),
  });

  get phonesArray(): FormArray {
    return this.form.get('phones') as FormArray;
  }

  get emailsArray(): FormArray {
    return this.form.get('emails') as FormArray;
  }

  get customFieldsArray(): FormArray {
    return this.form.get('customFields') as FormArray;
  }

  ngOnInit(): void {
    this.populateForm(this.customer());
  }

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  onEscapeKey(event: Event): void {
    event.preventDefault();
    this.onCancel();
  }

  private populateForm(customer: CustomerResponse): void {
    this.form.patchValue({
      firstName: customer.firstName ?? '',
      lastName: customer.lastName ?? '',
      externalId: customer.externalId ?? '',
      gdprConsent: {
        consent_given: customer.gdprConsent?.['consent_given'] ?? false,
        marketing_consent: customer.gdprConsent?.['marketing_consent'] ?? false,
      },
    });

    this.phonesArray.clear();
    customer.phone.forEach((phone) => {
      this.phonesArray.push(
        this.fb.control(phone, [Validators.required, Validators.maxLength(30)]),
      );
    });
    if (this.phonesArray.length === 0) {
      this.addPhone();
    }

    this.emailsArray.clear();
    customer.email.forEach((email) => {
      this.emailsArray.push(this.fb.control(email, [Validators.email, Validators.maxLength(200)]));
    });

    this.customFieldsArray.clear();
    Object.entries(customer.customFields ?? {}).forEach(([key, value]) => {
      this.customFieldsArray.push(this.createCustomFieldGroup(key, String(value)));
    });
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

  addPhone(): void {
    this.phonesArray.push(this.fb.control('', [Validators.required, Validators.maxLength(30)]));
  }

  removePhone(index: number): void {
    if (this.phonesArray.length > 1) {
      this.phonesArray.removeAt(index);
    }
  }

  addEmail(): void {
    this.emailsArray.push(this.fb.control('', [Validators.email, Validators.maxLength(200)]));
  }

  removeEmail(index: number): void {
    this.emailsArray.removeAt(index);
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

    const id = this.customer().customerId;
    const raw = this.form.getRawValue();
    const phones = (raw.phones as string[]).map((p) => p.trim()).filter((p) => p.length > 0);
    const emails = (raw.emails as string[]).map((e) => e.trim()).filter((e) => e.length > 0);
    const customFields = Object.fromEntries(
      raw.customFields
        .map((f) => [f.key.trim(), f.value.trim()] as [string, string])
        .filter(([key]) => key.length > 0),
    );

    this.saving.set(true);
    this.customerService
      .updateCustomer(id, {
        firstName: raw.firstName?.trim() || undefined,
        lastName: raw.lastName?.trim() || undefined,
        externalId: raw.externalId?.trim() ?? undefined,
        phone: phones,
        email: emails,
        customFields,
        gdprConsent: {
          ...(this.customer().gdprConsent ?? {}),
          consent_given: raw.gdprConsent.consent_given,
          marketing_consent: raw.gdprConsent.marketing_consent,
        },
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((err: HttpErrorResponse) => {
          if (err.status === 409 && err.error?.detail) {
            this.notifications.error(err.error.detail);
          } else {
            this.notifications.error(this.transloco.translate('supervisor.customerEdit.errorSave'));
          }
          return of(null);
        }),
        finalize(() => this.saving.set(false)),
      )
      .subscribe((result) => {
        if (result) {
          this.notifications.success(
            this.transloco.translate('supervisor.customerEdit.successSave'),
          );
          this.saved.emit();
        }
      });
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
