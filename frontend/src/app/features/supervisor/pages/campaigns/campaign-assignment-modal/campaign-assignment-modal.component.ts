import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, forkJoin, of } from 'rxjs';
import { CampaignService } from '../../../services/campaign.service';
import { UserService } from '../../../services/user.service';
import { AgentGroupService } from '../../../../../core/services/agent-group.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import {
  Campaign,
  CampaignAssignment,
  UpdateCampaignAssignmentRequest,
} from '../../../models/campaign.model';
import { AgentGroup, AgentGroupSummary } from '../../../../../core/models/agent-group.model';
import { UserResponse } from '../../../models/user.model';
import { PagedResponse } from '../../../../../core/models/paged-response.model';

@Component({
  selector: 'app-campaign-assignment-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './campaign-assignment-modal.component.html',
  styleUrl: './campaign-assignment-modal.component.scss',
})
export class CampaignAssignmentModalComponent implements OnInit {
  readonly campaign = input.required<Campaign>();
  readonly closed = output<void>();

  private readonly campaignService = inject(CampaignService);
  private readonly userService = inject(UserService);
  private readonly agentGroupService = inject(AgentGroupService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  readonly assignment = signal<CampaignAssignment | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);

  readonly allAgents = signal(false);
  readonly selectedAgentIds = signal<string[]>([]);
  readonly selectedGroupIds = signal<string[]>([]);

  readonly availableAgents = signal<UserResponse[]>([]);
  readonly availableGroups = signal<AgentGroupSummary[]>([]);

  readonly showEmptyWarning = computed(
    () =>
      !this.allAgents() &&
      this.selectedAgentIds().length === 0 &&
      this.selectedGroupIds().length === 0,
  );

  readonly unselectedAgents = computed(() =>
    this.availableAgents().filter((a) => !this.selectedAgentIds().includes(a.id)),
  );

  readonly unselectedGroups = computed(() =>
    this.availableGroups().filter((g) => !this.selectedGroupIds().includes(g.groupId)),
  );

  readonly selectedAgents = computed(() =>
    this.availableAgents().filter((a) => this.selectedAgentIds().includes(a.id)),
  );

  readonly selectedGroups = computed(() =>
    this.availableGroups().filter((g) => this.selectedGroupIds().includes(g.groupId)),
  );

  ngOnInit(): void {
    const emptyPage = <T>(): PagedResponse<T> => ({
      content: [],
      page: 0,
      size: 0,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    });

    forkJoin({
      assignment: this.campaignService
        .getCampaignAssignment(this.campaign().campaignId)
        .pipe(catchError(() => of(null))),
      agents: this.userService
        .getUsers({ page: 0, size: 200, role: 'AGENT' })
        .pipe(catchError(() => of(emptyPage<UserResponse>()))),
      groups: this.agentGroupService
        .listGroups({ page: 0, size: 100 })
        .pipe(catchError(() => of(emptyPage<AgentGroup>()))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(({ assignment, agents, groups }) => {
        this.availableAgents.set(agents.content);
        this.availableGroups.set(
          groups.content.map((g) => ({
            groupId: g.groupId,
            name: g.name,
            memberCount: g.memberCount,
          })),
        );

        if (assignment) {
          this.assignment.set(assignment);
          this.allAgents.set(assignment.allAgents);
          this.selectedAgentIds.set(assignment.directAgents.map((a) => a.agentId));
          this.selectedGroupIds.set(assignment.groups.map((g) => g.groupId));
        }

        this.loading.set(false);
      });
  }

  addAgent(agentId: string): void {
    if (!this.selectedAgentIds().includes(agentId)) {
      this.selectedAgentIds.update((ids) => [...ids, agentId]);
    }
  }

  removeAgent(agentId: string): void {
    this.selectedAgentIds.update((ids) => ids.filter((id) => id !== agentId));
  }

  addGroup(groupId: string): void {
    if (!this.selectedGroupIds().includes(groupId)) {
      this.selectedGroupIds.update((ids) => [...ids, groupId]);
    }
  }

  removeGroup(groupId: string): void {
    this.selectedGroupIds.update((ids) => ids.filter((id) => id !== groupId));
  }

  onAddAgentFromSelect(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const agentId = select.value;
    if (agentId) {
      this.addAgent(agentId);
      select.value = '';
    }
  }

  onAddGroupFromSelect(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const groupId = select.value;
    if (groupId) {
      this.addGroup(groupId);
      select.value = '';
    }
  }

  save(): void {
    if (this.saving()) return;
    this.saving.set(true);

    const req: UpdateCampaignAssignmentRequest = {
      allAgents: this.allAgents(),
      directAgentIds: this.allAgents() ? [] : this.selectedAgentIds(),
      groupIds: this.allAgents() ? [] : this.selectedGroupIds(),
    };

    this.campaignService
      .updateCampaignAssignment(this.campaign().campaignId, req)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.assignment.set(updated);
          this.saving.set(false);
          this.notifications.success(
            this.transloco.translate('supervisor.campaigns.campaignAssignment.successToast'),
          );
          this.closed.emit();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          if (err.status === 400 && err.error?.message) {
            this.notifications.error(err.error.message);
          } else {
            this.notifications.error(
              this.transloco.translate('supervisor.campaigns.campaignAssignment.errorSave'),
            );
          }
        },
      });
  }

  onClose(): void {
    this.closed.emit();
  }
}
