import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
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
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, forkJoin, of } from 'rxjs';
import { AgentGroupService } from '../../../../../core/services/agent-group.service';
import { UserService } from '../../../services/user.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import {
  AgentGroup,
  AgentGroupSummary,
  QueueAssignment,
} from '../../../../../core/models/agent-group.model';
import { UserResponse } from '../../../models/user.model';
import { PagedResponse } from '../../../../../core/models/paged-response.model';

@Component({
  selector: 'app-queue-assignment-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './queue-assignment-panel.component.html',
  styleUrl: './queue-assignment-panel.component.scss',
})
export class QueueAssignmentPanelComponent implements OnInit {
  readonly queueId = input.required<string>();

  private readonly agentGroupService = inject(AgentGroupService);
  private readonly userService = inject(UserService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  readonly assignment = signal<QueueAssignment | null>(null);
  readonly allAgents = signal<boolean>(true);
  readonly availableGroups = signal<AgentGroupSummary[]>([]);
  readonly availableAgents = signal<UserResponse[]>([]);
  readonly selectedGroupIds = signal<string[]>([]);
  readonly selectedAgentIds = signal<string[]>([]);
  readonly saving = signal<boolean>(false);
  readonly loading = signal<boolean>(false);

  readonly totalAgentsCount = computed(() => {
    const groupCount = this.selectedGroupIds().length;
    const agentCount = this.selectedAgentIds().length;
    return `${groupCount} grup + ${agentCount} agentów indywidualnych`;
  });

  readonly hasNoAssignment = computed(
    () =>
      !this.allAgents() &&
      this.selectedGroupIds().length === 0 &&
      this.selectedAgentIds().length === 0,
  );

  ngOnInit(): void {
    this.loading.set(true);

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
      assignment: this.agentGroupService
        .getQueueAssignment(this.queueId())
        .pipe(catchError(() => of(null))),
      groups: this.agentGroupService
        .listGroups({ page: 0, size: 100 })
        .pipe(catchError(() => of(emptyPage<AgentGroup>()))),
      agents: this.userService
        .getUsers({ page: 0, size: 200, role: 'AGENT' })
        .pipe(catchError(() => of(emptyPage<UserResponse>()))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(({ assignment, groups, agents }) => {
        this.availableGroups.set(
          groups.content.map((g) => ({
            groupId: g.groupId,
            name: g.name,
            memberCount: g.memberCount,
          })),
        );
        this.availableAgents.set(agents.content);

        if (assignment !== null) {
          this.assignment.set(assignment);
          this.allAgents.set(assignment.allAgents);
          this.selectedGroupIds.set(assignment.groups.map((g) => g.groupId));
          this.selectedAgentIds.set(assignment.directAgents.map((a) => a.agentId));
        }

        this.loading.set(false);
      });
  }

  toggleGroup(groupId: string): void {
    this.selectedGroupIds.update((ids) =>
      ids.includes(groupId) ? ids.filter((id) => id !== groupId) : [...ids, groupId],
    );
  }

  toggleAgent(agentId: string): void {
    this.selectedAgentIds.update((ids) =>
      ids.includes(agentId) ? ids.filter((id) => id !== agentId) : [...ids, agentId],
    );
  }

  onSave(): void {
    if (this.saving()) return;
    this.saving.set(true);

    this.agentGroupService
      .updateQueueAssignment(this.queueId(), {
        allAgents: this.allAgents(),
        groupIds: this.selectedGroupIds(),
        directAgentIds: this.selectedAgentIds(),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.assignment.set(updated);
          this.saving.set(false);
          this.notifications.success(
            this.transloco.translate('supervisor.queueAssignment.successSave'),
          );
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          if (err.status === 400 && err.error?.message) {
            this.notifications.error(err.error.message);
          } else {
            this.notifications.error(
              this.transloco.translate('supervisor.queueAssignment.errorSave'),
            );
          }
        },
      });
  }
}
