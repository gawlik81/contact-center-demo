import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Input,
  OnInit,
  Output,
  EventEmitter,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { CampaignService } from '../../../services/campaign.service';
import { Campaign, ActiveDay } from '../../../models/campaign.model';

const DAY_LABELS: Record<ActiveDay, string> = {
  MON: 'Pon',
  TUE: 'Wt',
  WED: 'Sr',
  THU: 'Czw',
  FRI: 'Pt',
  SAT: 'Sob',
  SUN: 'Nie',
};

@Component({
  selector: 'app-campaign-info',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  templateUrl: './campaign-info.component.html',
  styleUrl: './campaign-info.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class CampaignInfoComponent implements OnInit, AfterViewInit {
  @Input({ required: true }) campaign!: Campaign;
  @Output() closed = new EventEmitter<void>();

  private readonly campaignService = inject(CampaignService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly contactCount = signal<number | null>(null);
  readonly contactCountLoading = signal(true);

  ngOnInit(): void {
    this.campaignService
      .getCampaignContacts(this.campaign.campaignId, 0, 1)
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.contactCount.set(response?.totalElements ?? null);
        this.contactCountLoading.set(false);
      });
  }

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  onEscapeKey(event: Event): void {
    event.preventDefault();
    this.close();
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
    this.closed.emit();
  }

  formatStatus(status: string): string {
    switch (status) {
      case 'DRAFT':
        return 'Szkic';
      case 'SCHEDULED':
        return 'Zaplanowana';
      case 'RUNNING':
        return 'Aktywna';
      case 'PAUSED':
        return 'Wstrzymana';
      case 'STOPPED':
        return 'Zatrzymana';
      case 'COMPLETED':
        return 'Zakonczona';
      default:
        return status;
    }
  }

  formatType(type: string): string {
    switch (type) {
      case 'OUTBOUND_VOICE':
        return 'Wychodzace glosy';
      case 'OUTBOUND_EMAIL':
        return 'Wychodzace email';
      default:
        return type;
    }
  }

  formatDialerType(dialerType: string): string {
    switch (dialerType) {
      case 'PROGRESSIVE':
        return 'Progresywny';
      case 'PREDICTIVE':
        return 'Predyktywny';
      case 'MANUAL':
        return 'Manualny';
      default:
        return dialerType;
    }
  }

  formatDate(dateStr: string | undefined): string {
    if (!dateStr) return '—';
    try {
      return new Date(dateStr).toLocaleDateString('pl-PL', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
      });
    } catch {
      return dateStr;
    }
  }

  formatActiveDays(days: ActiveDay[] | undefined): string {
    if (!days || days.length === 0) return '—';
    return days.map((d) => DAY_LABELS[d] ?? d).join(', ');
  }

  formatActiveHours(hours: { from: string; to: string } | undefined): string {
    if (!hours) return '—';
    return `${hours.from}–${hours.to}`;
  }
}
