import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
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
import { CampaignInfoComponent } from '../campaign-info/campaign-info.component';
import { CampaignAssignmentModalComponent } from '../campaign-assignment-modal/campaign-assignment-modal.component';
import { ConfirmDialogComponent } from '../../../../../shared/components/confirm-dialog/confirm-dialog.component';

const POLLING_INTERVAL_MS = 10_000;
const CLOSED_STATUSES = new Set<CampaignStatus>(['STOPPED', 'COMPLETED']);

@Component({
  selector: 'app-campaign-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,
    CampaignFormComponent,
    CampaignImportComponent,
    CampaignContactsComponent,
    CampaignInfoComponent,
    CampaignAssignmentModalComponent,
    ConfirmDialogComponent,
  ],
  templateUrl: './campaign-list.component.html',
  styleUrl: './campaign-list.component.scss',
})
export class CampaignListComponent implements OnInit {
  private readonly campaignService = inject(CampaignService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly campaigns = signal<Campaign[]>([]);
  readonly hideClosedCampaigns = signal(true);
  readonly filteredCampaigns = computed(() =>
    this.hideClosedCampaigns()
      ? this.campaigns().filter((c) => !CLOSED_STATUSES.has(c.status))
      : this.campaigns(),
  );
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

  readonly showInfoModal = signal(false);
  readonly infoCampaign = signal<Campaign | null>(null);

  readonly assignmentCampaign = signal<Campaign | null>(null);

  readonly showConfirmDialog = signal(false);
  readonly confirmDialogMessage = signal('');
  readonly confirmDialogDanger = signal(false);
  private readonly pendingConfirmAction = signal<(() => void) | null>(null);

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
          this.notifications.error(
            this.transloco.translate('supervisor.campaigns.errors.loadFailed'),
          );
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

  canEdit(status: CampaignStatus): boolean {
    return status === 'DRAFT';
  }

  openEditModal(campaign: Campaign): void {
    this.selectedCampaign.set(campaign);
    this.isEditMode.set(true);
    this.showFormModal.set(true);
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
      this.notifications.success(this.transloco.translate('supervisor.campaigns.importSuccess'));
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

  openInfoModal(campaign: Campaign): void {
    this.infoCampaign.set(campaign);
    this.showInfoModal.set(true);
  }

  onInfoClosed(): void {
    this.showInfoModal.set(false);
    this.infoCampaign.set(null);
  }

  openAssignment(campaign: Campaign, event: Event): void {
    event.stopPropagation();
    this.assignmentCampaign.set(campaign);
  }

  canStart(status: CampaignStatus): boolean {
    return status === 'DRAFT' || status === 'PAUSED';
  }

  canRevertToDraft(status: CampaignStatus): boolean {
    return status === 'SCHEDULED';
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

  private requestConfirm(message: string, danger: boolean, action: () => void): void {
    this.confirmDialogMessage.set(message);
    this.confirmDialogDanger.set(danger);
    this.pendingConfirmAction.set(action);
    this.showConfirmDialog.set(true);
  }

  onConfirmDialogConfirmed(): void {
    this.showConfirmDialog.set(false);
    this.pendingConfirmAction()?.();
    this.pendingConfirmAction.set(null);
  }

  onConfirmDialogCancelled(): void {
    this.showConfirmDialog.set(false);
    this.pendingConfirmAction.set(null);
  }

  onPause(campaign: Campaign): void {
    this.requestConfirm(
      `${this.transloco.translate('supervisor.campaigns.confirmPause')} "${campaign.name}"?`,
      false,
      () => this.executePause(campaign),
    );
  }

  private executePause(campaign: Campaign): void {
    const id = campaign.campaignId;
    this.setActionInProgress(id, true);
    this.campaignService
      .pauseCampaign(id)
      .pipe(
        catchError(() => {
          this.notifications.error(
            `${this.transloco.translate('supervisor.campaigns.errorPause')} "${campaign.name}".`,
          );
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(
            `"${campaign.name}" ${this.transloco.translate('supervisor.campaigns.successPause')}`,
          );
          this.updateCampaignInList(updated);
        }
      });
  }

  onStop(campaign: Campaign): void {
    this.requestConfirm(
      `${this.transloco.translate('supervisor.campaigns.confirmStop')} "${campaign.name}"? ${this.transloco.translate('supervisor.campaigns.confirmStopWarning')}`,
      true,
      () => this.executeStop(campaign),
    );
  }

  private executeStop(campaign: Campaign): void {
    const id = campaign.campaignId;
    this.setActionInProgress(id, true);
    this.campaignService
      .stopCampaign(id)
      .pipe(
        catchError(() => {
          this.notifications.error(
            `${this.transloco.translate('supervisor.campaigns.errorStop')} "${campaign.name}".`,
          );
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(
            `"${campaign.name}" ${this.transloco.translate('supervisor.campaigns.successStop')}`,
          );
          this.updateCampaignInList(updated);
        }
      });
  }

  onRevertToDraft(campaign: Campaign): void {
    this.requestConfirm(
      `${this.transloco.translate('supervisor.campaigns.confirmRevert')} "${campaign.name}" ${this.transloco.translate('supervisor.campaigns.confirmRevertSuffix')}`,
      false,
      () => this.executeRevertToDraft(campaign),
    );
  }

  private executeRevertToDraft(campaign: Campaign): void {
    const id = campaign.campaignId;
    this.setActionInProgress(id, true);
    this.campaignService
      .revertToDraft(id)
      .pipe(
        catchError(() => {
          this.notifications.error(
            `${this.transloco.translate('supervisor.campaigns.errorRevert')} "${campaign.name}" ${this.transloco.translate('supervisor.campaigns.errorRevertSuffix')}`,
          );
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(
            `"${campaign.name}" ${this.transloco.translate('supervisor.campaigns.successRevert')}`,
          );
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
        return this.transloco.translate('supervisor.campaigns.dialerProgressive');
      case 'PREDICTIVE':
        return this.transloco.translate('supervisor.campaigns.dialerPredictive');
      case 'MANUAL':
        return this.transloco.translate('supervisor.campaigns.dialerManual');
      default:
        return dialerType;
    }
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

  trackByCampaignId(_index: number, campaign: Campaign): string {
    return campaign.campaignId;
  }
}
