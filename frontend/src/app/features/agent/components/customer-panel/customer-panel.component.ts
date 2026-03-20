import {
  ChangeDetectionStrategy,
  Component,
  OnChanges,
  SimpleChanges,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe, LowerCasePipe } from '@angular/common';
import { Router } from '@angular/router';
import { CustomerLookupService } from '../../services/customer-lookup.service';
import {
  CustomerProfile,
  ContactHistoryItem,
} from '../../../../core/models/customer-profile.model';

type PanelState = 'loading' | 'known' | 'unknown' | 'empty';

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

  protected readonly state = signal<PanelState>('empty');
  protected readonly profile = signal<CustomerProfile | null>(null);

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['cli']) return;

    const phone = this.cli();
    if (!phone) {
      this.state.set('empty');
      this.profile.set(null);
      return;
    }

    this.state.set('loading');
    this.profile.set(null);

    this.lookupService.lookupByPhone(phone).subscribe((result) => {
      this.profile.set(result);
      this.state.set(result ? 'known' : 'unknown');
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
