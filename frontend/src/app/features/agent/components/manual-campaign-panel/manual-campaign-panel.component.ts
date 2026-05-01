import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, catchError, finalize, of, switchMap, timer } from 'rxjs';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { DialerService } from '../../services/dialer.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { AgentStatus } from '../../models/agent-status.model';
import {
  ManualCampaignGroup,
  ManualCampaignRecord,
} from '../../../supervisor/models/campaign.model';

interface CampaignRow {
  group: ManualCampaignGroup;
  expanded: boolean;
}

const POLL_INTERVAL_MS = 30_000;

@Component({
  selector: 'app-manual-campaign-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './manual-campaign-panel.component.html',
  styleUrl: './manual-campaign-panel.component.scss',
})
export class ManualCampaignPanelComponent implements OnInit {
  readonly agentStatus = input.required<AgentStatus>();

  private readonly dialerService = inject(DialerService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly campaignRows = signal<CampaignRow[]>([]);
  protected readonly callingRecordId = signal<string | null>(null);

  protected readonly isAvailable = computed(() => this.agentStatus() === 'AVAILABLE');

  protected readonly totalPending = computed(() =>
    this.campaignRows().reduce((sum, row) => sum + row.group.records.length, 0),
  );

  ngOnInit(): void {
    // Initial load + 30s polling
    timer(0, POLL_INTERVAL_MS)
      .pipe(
        switchMap(() => {
          this.loading.set(true);
          this.error.set(null);
          return this.loadCampaigns();
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((groups) => {
        if (!groups) return;
        this.applyCampaignGroups(groups);
      });
  }

  protected refresh(): void {
    this.error.set(null);
    this.loading.set(true);
    this.loadCampaigns()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((groups) => {
        if (!groups) return;
        this.applyCampaignGroups(groups);
      });
  }

  private loadCampaigns(): Observable<ManualCampaignGroup[] | null> {
    return this.dialerService.getManualCampaignRecords().pipe(
      finalize(() => this.loading.set(false)),
      catchError(() => {
        this.error.set(this.transloco.translate('agent.manualCampaign.errorLoad'));
        return of(null);
      }),
    );
  }

  private applyCampaignGroups(groups: ManualCampaignGroup[]): void {
    // Preserve expanded state for existing campaigns
    const existing = this.campaignRows();
    const rows: CampaignRow[] = groups.map((group) => {
      const prev = existing.find((r) => r.group.campaignId === group.campaignId);
      return { group, expanded: prev?.expanded ?? true };
    });
    this.campaignRows.set(rows);
  }

  protected toggleExpanded(campaignId: string): void {
    this.campaignRows.update((rows) =>
      rows.map((r) => (r.group.campaignId === campaignId ? { ...r, expanded: !r.expanded } : r)),
    );
  }

  protected callRecord(group: ManualCampaignGroup, record: ManualCampaignRecord): void {
    if (this.callingRecordId() !== null) return;
    if (!this.isAvailable()) return;

    this.callingRecordId.set(record.recordId);
    this.dialerService
      .manualCall(group.campaignId, record.recordId)
      .pipe(
        finalize(() => this.callingRecordId.set(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          // Optimistic update: remove called record from list
          this.campaignRows.update((rows) =>
            rows.map((r) =>
              r.group.campaignId === group.campaignId
                ? {
                    ...r,
                    group: {
                      ...r.group,
                      records: r.group.records.filter((rec) => rec.recordId !== record.recordId),
                    },
                  }
                : r,
            ),
          );
          const name = [record.firstName, record.lastName].filter(Boolean).join(' ');
          const displayName = name || record.phone;
          this.notifications.success(
            `${this.transloco.translate('agent.manualCampaign.callInitiating')} ${displayName} (${record.phone})`,
          );
        },
        error: (err) => {
          if (err.status === 409) {
            const msg: string = err.error?.message ?? '';
            this.notifications.error(
              msg || this.transloco.translate('agent.manualCampaign.callError'),
            );
          } else if (err.status === 404) {
            this.notifications.error(
              this.transloco.translate('agent.manualCampaign.callRecordNotFound'),
            );
          } else {
            this.notifications.error(this.transloco.translate('agent.manualCampaign.callError'));
          }
        },
      });
  }

  protected readonly trackByCampaignId = (_i: number, row: CampaignRow) => row.group.campaignId;

  protected readonly trackByRecordId = (_i: number, rec: ManualCampaignRecord) => rec.recordId;
}
