import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnChanges,
  SimpleChanges,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, LowerCasePipe } from '@angular/common';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CustomerLookupService } from '../../services/customer-lookup.service';
import { AuthService } from '../../../../core/services/auth.service';
import {
  CustomerProfile,
  ContactHistoryItem,
} from '../../../../core/models/customer-profile.model';

type PanelState = 'loading' | 'known' | 'unknown' | 'empty' | 'error';

@Component({
  selector: 'cc-customer-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, LowerCasePipe],
  templateUrl: './customer-panel.component.html',
  styleUrl: './customer-panel.component.scss',
})
export class CustomerPanelComponent implements OnChanges {
  /** Phone number (CLI) of the active contact. Pass empty string when no contact is active. */
  readonly cli = input<string>('');

  private readonly lookupService = inject(CustomerLookupService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly authService = inject(AuthService);

  protected readonly state = signal<PanelState>('empty');
  protected readonly profile = signal<CustomerProfile | null>(null);

  /** Tracks the in-flight lookup subscription so it can be cancelled on the next CLI change. */
  private lookupSub: Subscription | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['cli']) return;

    const phone = this.cli();
    if (!phone) {
      this.lookupSub?.unsubscribe();
      this.lookupSub = null;
      this.state.set('empty');
      this.profile.set(null);
      return;
    }

    // Cancel any in-flight request for the previous CLI
    this.lookupSub?.unsubscribe();

    this.state.set('loading');
    this.profile.set(null);

    this.lookupSub = this.lookupService
      .lookupByPhone(phone)
      .pipe(
        catchError((err: HttpErrorResponse) => {
          // 404 is handled inside CustomerLookupService (returns null).
          // Any other error reaching here is a 5xx or network failure.
          this.state.set('error');
          return throwError(() => err);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (result) => {
          this.profile.set(result);
          this.state.set(result ? 'known' : 'unknown');
        },
        error: () => {
          // Error already handled in catchError above; suppress unhandled error.
        },
      });
  }

  protected getChannelIcon(channel: ContactHistoryItem['channel']): string {
    switch (channel) {
      case 'PHONE':
        return 'phone';
      case 'EMAIL':
        return 'email';
      case 'CHAT':
        return 'chat';
      case 'SOCIAL':
        return 'share';
    }
  }

  protected getChannelLabel(channel: ContactHistoryItem['channel']): string {
    switch (channel) {
      case 'PHONE':
        return 'Telefon';
      case 'EMAIL':
        return 'Email';
      case 'CHAT':
        return 'Chat';
      case 'SOCIAL':
        return 'Social';
    }
  }

  protected navigateToFullProfile(): void {
    const p = this.profile();
    if (p) {
      this.router.navigate(['/supervisor/customers', p.id]);
    }
  }

  protected navigateToCreateProfile(): void {
    this.router.navigate(['/supervisor/customers/new'], {
      queryParams: { phone: this.cli() },
    });
  }

  protected readonly trackByContactId = (_i: number, item: ContactHistoryItem) => item.id;
}
