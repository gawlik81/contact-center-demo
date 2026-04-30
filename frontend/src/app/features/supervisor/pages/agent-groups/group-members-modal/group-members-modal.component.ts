import { TranslocoModule } from '@jsverse/transloco';
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
import { FormsModule } from '@angular/forms';
import { catchError, finalize, forkJoin, of } from 'rxjs';
import { AgentGroupService } from '../../../../../core/services/agent-group.service';
import { UserService } from '../../../services/user.service';
import { AgentGroup, AgentGroupMember } from '../../../../../core/models/agent-group.model';
import { UserResponse } from '../../../models/user.model';

@Component({
  selector: 'app-group-members-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,FormsModule],
  templateUrl: './group-members-modal.component.html',
  styleUrl: './group-members-modal.component.scss',
})
export class GroupMembersModalComponent implements OnInit {
  private readonly agentGroupService = inject(AgentGroupService);
  private readonly userService = inject(UserService);
  private readonly destroyRef = inject(DestroyRef);

  readonly group = input.required<AgentGroup>();

  readonly saved = output<void>();
  readonly cancelled = output<void>();

  readonly currentMembers = signal<AgentGroupMember[]>([]);
  readonly availableAgents = signal<UserResponse[]>([]);
  readonly selectedAgentIds = signal<Set<string>>(new Set());
  readonly loading = signal<boolean>(false);
  readonly saving = signal<boolean>(false);
  readonly searchQuery = signal<string>('');

  readonly filteredAgents = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    if (!query) return this.availableAgents();
    return this.availableAgents().filter(
      (agent) =>
        this.getAgentFullName(agent).toLowerCase().includes(query) ||
        agent.email.toLowerCase().includes(query),
    );
  });

  ngOnInit(): void {
    this.loading.set(true);
    forkJoin({
      members: this.agentGroupService
        .getGroupMembers(this.group().groupId)
        .pipe(catchError(() => of({ groupId: '', groupName: '', members: [] }))),
      agents: this.userService.getUsers({ page: 0, size: 200, role: 'AGENT' }).pipe(
        catchError(() =>
          of({
            content: [],
            totalElements: 0,
            totalPages: 0,
            page: 0,
            size: 200,
            first: true,
            last: true,
          }),
        ),
      ),
    })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe(({ members, agents }) => {
        this.currentMembers.set(members.members);
        this.availableAgents.set(agents.content);
        const preselected = new Set(members.members.map((m) => m.agentId));
        this.selectedAgentIds.set(preselected);
      });
  }

  isSelected(agentId: string): boolean {
    return this.selectedAgentIds().has(agentId);
  }

  toggleAgent(agentId: string): void {
    this.selectedAgentIds.update((set) => {
      const next = new Set(set);
      if (next.has(agentId)) {
        next.delete(agentId);
      } else {
        next.add(agentId);
      }
      return next;
    });
  }

  getAgentFullName(agent: UserResponse): string {
    return `${agent.firstName} ${agent.lastName}`.trim();
  }

  onSubmit(): void {
    if (this.saving()) return;
    this.saving.set(true);

    const agentIds = [...this.selectedAgentIds()];

    this.agentGroupService
      .replaceGroupMembers(this.group().groupId, agentIds)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => of(null)),
        finalize(() => this.saving.set(false)),
      )
      .subscribe((result) => {
        if (result) {
          this.saved.emit();
        }
      });
  }

  onCancel(): void {
    this.cancelled.emit();
  }

  trackByAgentId(_index: number, agent: UserResponse): string {
    return agent.id;
  }
}
