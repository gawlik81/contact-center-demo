import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval, switchMap, catchError, of } from 'rxjs';
import { CampaignService } from '../../../services/campaign.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { Campaign, CampaignStatus, PagedResponse } from '../../../models/campaign.model';
import { CampaignFormComponent } from '../campaign-form/campaign-form.component';
import { CampaignImportComponent } from '../campaign-import/campaign-import.component';
import { CampaignContactsComponent } from '../campaign-contacts/campaign-contacts.component';

const POLLING_INTERVAL_MS = 10_000;

@Component({
  selector: 'app-campaign-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CampaignFormComponent, CampaignImportComponent, CampaignContactsComponent],
  templateUrl: './campaign-list.component.html',
  styleUrl: './campaign-list.component.scss',
})
export class CampaignListComponent implements OnInit {
  private readonly campaignService = inject(CampaignService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly campaigns = signal<Campaign[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly currentPage = signal(0);
  readonly pageSize = 20;

  readonly showFormModal = signal(false);
  readonly selectedCampaign = signal<Campaign | null>(null);
  readonly isEditMode = signal(false);

  readonly actionInProgress = signal<Set<string>>(new Set());

  readonly showImportModal = signal(false);
  readonly importCampaign = signal<Campaign | null>(null);

  readonly showContactsModal = signal(false);
  readonly contactsCampaign = signal<Campaign | null>(null);

  ngOnInit(): void {
    this.loadCampaigns();
    this.initPolling();
  }

  private initPolling(): void {
    interval(POLLING_INTERVAL_MS)
      .pipe(
        switchMap(() =>
          this.campaignService
            .getCampaigns(this.currentPage(), this.pageSize)
            .pipe(catchError(() => of(null))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        if (response) {
          this.campaigns.set(response.content);
          this.totalElements.set(response.totalElements);
          this.totalPages.set(response.totalPages);
        }
      });
  }

  loadCampaigns(): void {
    this.loading.set(true);
    this.campaignService
      .getCampaigns(this.currentPage(), this.pageSize)
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udalo sie pobrac listy kampanii. Sprobuj ponownie.');
          const empty: PagedResponse<Campaign> = {
            content: [],
            page: 0,
            size: this.pageSize,
            totalElements: 0,
            totalPages: 0,
            first: true,
            last: true,
          };
          return of(empty);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.campaigns.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
      });
  }

  openCreateModal(): void {
    this.selectedCampaign.set(null);
    this.isEditMode.set(false);
    this.showFormModal.set(true);
  }

  closeFormModal(): void {
    this.showFormModal.set(false);
    this.selectedCampaign.set(null);
  }

  onFormSaved(): void {
    this.closeFormModal();
    this.loadCampaigns();
  }

  canImport(status: CampaignStatus): boolean {
    return status === 'DRAFT' || status === 'SCHEDULED';
  }

  openImportModal(campaign: Campaign): void {
    this.importCampaign.set(campaign);
    this.showImportModal.set(true);
  }

  onImportClosed(success: boolean): void {
    this.showImportModal.set(false);
    this.importCampaign.set(null);
    if (success) {
      this.notifications.success('Import kontaktow zostal zakonczony pomyslnie.');
      this.loadCampaigns();
    }
  }

  openContactsModal(campaign: Campaign): void {
    this.contactsCampaign.set(campaign);
    this.showContactsModal.set(true);
  }

  onContactsClosed(): void {
    this.showContactsModal.set(false);
    this.contactsCampaign.set(null);
  }

  canStart(status: CampaignStatus): boolean {
    return status === 'DRAFT' || status === 'SCHEDULED' || status === 'PAUSED';
  }

  canPause(status: CampaignStatus): boolean {
    return status === 'RUNNING';
  }

  canStop(status: CampaignStatus): boolean {
    return status === 'RUNNING';
  }

  private setActionInProgress(id: string, inProgress: boolean): void {
    this.actionInProgress.update((set) => {
      const next = new Set(set);
      if (inProgress) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  onStart(campaign: Campaign): void {
    const id = campaign.campaignId;
    this.setActionInProgress(id, true);
    this.campaignService
      .startCampaign(id)
      .pipe(
        catchError(() => {
          this.notifications.error(`Nie udalo sie uruchomic kampanii "${campaign.name}".`);
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(`Kampania "${campaign.name}" zostala uruchomiona.`);
          this.updateCampaignInList(updated);
        }
      });
  }

  onPause(campaign: Campaign): void {
    const id = campaign.campaignId;
    if (!confirm(`Czy na pewno chcesz wstrzymac kampanie "${campaign.name}"?`)) return;

    this.setActionInProgress(id, true);
    this.campaignService
      .pauseCampaign(id)
      .pipe(
        catchError(() => {
          this.notifications.error(`Nie udalo sie wstrzymac kampanii "${campaign.name}".`);
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(`Kampania "${campaign.name}" zostala wstrzymana.`);
          this.updateCampaignInList(updated);
        }
      });
  }

  onStop(campaign: Campaign): void {
    const id = campaign.campaignId;
    if (
      !confirm(
        `Czy na pewno chcesz zatrzymac kampanie "${campaign.name}"? Tej operacji nie mozna cofnac.`,
      )
    )
      return;

    this.setActionInProgress(id, true);
    this.campaignService
      .stopCampaign(id)
      .pipe(
        catchError(() => {
          this.notifications.error(`Nie udalo sie zatrzymac kampanii "${campaign.name}".`);
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(`Kampania "${campaign.name}" zostala zatrzymana.`);
          this.updateCampaignInList(updated);
        }
      });
  }

  private updateCampaignInList(updated: Campaign): void {
    this.campaigns.update((list) =>
      list.map((c) => (c.campaignId === updated.campaignId ? updated : c)),
    );
  }

  onPrevPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((p) => p - 1);
      this.loadCampaigns();
    }
  }

  onNextPage(): void {
    if (this.currentPage() + 1 < this.totalPages()) {
      this.currentPage.update((p) => p + 1);
      this.loadCampaigns();
    }
  }

  readonly firstItemIndex = (): number => this.currentPage() * this.pageSize + 1;
  readonly lastItemIndex = (): number =>
    Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());

  formatScheduleDates(campaign: Campaign): string {
    const s = campaign.schedule;
    if (!s?.start_date && !s?.end_date) return '—';
    const parts: string[] = [];
    if (s.start_date) parts.push(this.formatDate(s.start_date));
    if (s.end_date) parts.push(this.formatDate(s.end_date));
    return parts.join(' – ');
  }

  private formatDate(dateStr: string): string {
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

  trackByCampaignId(_index: number, campaign: Campaign): string {
    return campaign.campaignId;
  }
}
