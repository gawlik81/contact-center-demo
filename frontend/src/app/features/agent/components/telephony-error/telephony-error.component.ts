import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { SoftphoneService } from '../../services/softphone.service';

/**
 * Shows the telephony (Twilio Device) failure to the agent with a retry action.
 * The text is resolved in the template through the transloco pipe so it follows language changes.
 */
@Component({
  selector: 'app-telephony-error',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './telephony-error.component.html',
  styleUrl: './telephony-error.component.scss',
})
export class TelephonyErrorComponent {
  private readonly softphone = inject(SoftphoneService);

  protected readonly error = this.softphone.twilioDeviceError;
  protected readonly retrying = this.softphone.twilioDeviceInitializing;
  /** Re-initializing the Device would drop a live call, so retry waits until it ends. */
  protected readonly retryBlocked = computed(
    () => this.retrying() || this.softphone.callInProgress(),
  );

  /** True after the agent pressed retry and until the error is gone (success or logout). */
  private readonly retryAttempted = signal(false);

  /**
   * Polite status for the second live region. The error key does not change when a retry fails,
   * so the alert is not re-read; this line announces "retrying" and "still unavailable".
   */
  protected readonly statusKey = computed<string | null>(() => {
    if (this.retrying()) return 'agent.softphone.errors.retrying';
    if (this.retryAttempted() && this.error()) return 'agent.softphone.errors.stillUnavailable';
    return null;
  });

  constructor() {
    effect(() => {
      if (!this.error()) this.retryAttempted.set(false);
    });
  }

  protected retry(): void {
    // aria-disabled (not `disabled`) keeps keyboard focus on the button, so the guard lives here.
    if (this.retryBlocked()) return;
    this.retryAttempted.set(true);
    void this.softphone.retryTwilioDevice();
  }
}
