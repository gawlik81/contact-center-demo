import { TranslocoModule } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  forwardRef,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { catchError, of } from 'rxjs';
import { TwilioConfigService, TwilioPhoneNumberDto } from '../../services/twilio-config.service';

type LoadState = 'loading' | 'error-502' | 'no-config' | 'empty' | 'ready';

@Component({
  selector: 'app-twilio-phone-number-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, FormsModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => TwilioPhoneNumberSelectComponent),
      multi: true,
    },
  ],
  templateUrl: './twilio-phone-number-select.component.html',
  styleUrl: './twilio-phone-number-select.component.scss',
})
export class TwilioPhoneNumberSelectComponent implements ControlValueAccessor, OnInit {
  readonly placeholder = input<string>('');
  readonly required = input<boolean>(false);
  readonly allowNull = input<boolean>(false);
  readonly nullLabel = input<string>('');
  readonly disabledNumbers = input<string[]>([]);

  private readonly twilioConfigService = inject(TwilioConfigService);
  private readonly destroyRef = inject(DestroyRef);

  readonly loadState = signal<LoadState>('loading');
  readonly phoneNumbers = signal<TwilioPhoneNumberDto[]>([]);
  readonly value = signal<string | null>(null);
  readonly isDisabled = signal(false);

  readonly selectId = `twilio-phone-select-${Math.random().toString(36).slice(2, 7)}`;

  readonly options = computed<{ value: string | null; label: string; disabled: boolean }[]>(() => {
    const numbers = this.phoneNumbers();
    const disabled = new Set(this.disabledNumbers());
    const base: { value: string | null; label: string; disabled: boolean }[] = this.allowNull()
      ? [{ value: null, label: this.nullLabel() || '— Domyślny —', disabled: false }]
      : [];
    return [
      ...base,
      ...numbers.map((n) => ({
        value: n.phoneNumber,
        label: `${n.friendlyName} — ${n.phoneNumber}`,
        disabled: disabled.has(n.phoneNumber),
      })),
    ];
  });

  // eslint-disable-next-line @typescript-eslint/no-empty-function
  private onChange: (value: string | null) => void = () => {};
  // eslint-disable-next-line @typescript-eslint/no-empty-function
  private onTouched: () => void = () => {};

  ngOnInit(): void {
    this.fetchPhoneNumbers();
  }

  fetchPhoneNumbers(): void {
    this.loadState.set('loading');
    this.twilioConfigService
      .getPhoneNumbers()
      .pipe(
        catchError((err: { status?: number }) => {
          if (err?.status === 404) {
            this.loadState.set('no-config');
          } else {
            this.loadState.set('error-502');
          }
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((numbers) => {
        if (numbers === null) return;
        this.phoneNumbers.set(numbers);
        this.loadState.set(numbers.length === 0 ? 'empty' : 'ready');
      });
  }

  onSelectChange(val: string): void {
    const resolved = val === '' ? null : val;
    this.value.set(resolved);
    this.onChange(resolved);
    this.onTouched();
  }

  writeValue(val: string | null): void {
    this.value.set(val ?? null);
  }

  registerOnChange(fn: (value: string | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.isDisabled.set(isDisabled);
  }
}
