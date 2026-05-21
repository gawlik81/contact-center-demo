import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
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

@Component({
  selector: 'app-campaign-info',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
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
  private readonly transloco = inject(TranslocoService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly contactCount = signal<number | null>(null);
  readonly contactCountLoading = signal(true);


  ngOnInit(): void {
    this.transloco.langChanges$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.cdr.markForCheck();
    });
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
    const keyMap: Record<string, string> = {
      DRAFT: 'supervisor.campaigns.statusDraft',
      SCHEDULED: 'supervisor.campaigns.statusScheduled',
      RUNNING: 'supervisor.campaigns.statusRunning',
      PAUSED: 'supervisor.campaigns.statusPaused',
      STOPPED: 'supervisor.campaigns.statusStopped',
      COMPLETED: 'supervisor.campaigns.statusCompleted',
    };
    const key = keyMap[status];
    return key ? this.transloco.translate(key) : status;
  }

  formatType(type: string): string {
    switch (type) {
      case 'OUTBOUND_VOICE':
        return this.transloco.translate('supervisor.campaigns.typeOutboundVoice');
      case 'OUTBOUND_EMAIL':
        return this.transloco.translate('supervisor.campaigns.typeOutboundEmail');
      default:
        return type;
    }
  }

  formatDialerType(dialerType: string): string {
    switch (dialerType) {
      case 'PROGRESSIVE':
        return this.transloco.translate('supervisor.campaigns.dialerProgressive');
      case 'PREDICTIVE':
        return this.transloco.translate('supervisor.campaigns.dialerPredictive');
      case 'MANUAL':
        return this.transloco.translate('supervisor.campaigns.dialerManual');
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
    return days.map((d) => this.transloco.translate(`agent.calendar.days.${d}`)).join(', ');
  }

  formatActiveHours(hours: { from: string; to: string } | undefined): string {
    if (!hours) return '—';
    return `${hours.from}–${hours.to}`;
  }
}
