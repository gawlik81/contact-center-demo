import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, finalize, of } from 'rxjs';
import { AgentGroupService } from '../../../../../core/services/agent-group.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { AgentGroup } from '../../../../../core/models/agent-group.model';
import { CreateEditGroupModalComponent } from '../create-edit-group-modal/create-edit-group-modal.component';
import { GroupMembersModalComponent } from '../group-members-modal/group-members-modal.component';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
  selector: 'app-agent-groups-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CreateEditGroupModalComponent, GroupMembersModalComponent],
  templateUrl: './agent-groups-page.component.html',
  styleUrl: './agent-groups-page.component.scss',
})
export class AgentGroupsPageComponent implements OnInit {
  private readonly agentGroupService = inject(AgentGroupService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  readonly groups = signal<AgentGroup[]>([]);
  readonly total = signal<number>(0);
  readonly loading = signal<boolean>(false);

  readonly showCreateEditModal = signal<boolean>(false);
  readonly editingGroup = signal<AgentGroup | null>(null);

  readonly showMembersModal = signal<boolean>(false);
  readonly managingMembersGroup = signal<AgentGroup | null>(null);

  readonly showDeleteModal = signal<boolean>(false);
  readonly deletingGroup = signal<AgentGroup | null>(null);
  readonly deleteLoading = signal<boolean>(false);

  ngOnInit(): void {
    this.loadGroups();
  }

  loadGroups(): void {
    this.loading.set(true);
    this.agentGroupService
      .listGroups({ page: 0, size: 100 })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error('Nie udało się pobrać listy grup agentów. Spróbuj ponownie.');
          return of({
            content: [],
            totalElements: 0,
            totalPages: 1,
            page: 0,
            size: 200,
            first: true,
            last: true,
          });
        }),
        finalize(() => this.loading.set(false)),
      )
      .subscribe((response) => {
        this.groups.set(response.content);
        this.total.set(response.totalElements);
      });
  }

  openCreateModal(): void {
    this.editingGroup.set(null);
    this.showCreateEditModal.set(true);
  }

  openEditModal(group: AgentGroup): void {
    this.editingGroup.set(group);
    this.showCreateEditModal.set(true);
  }

  closeCreateEditModal(): void {
    this.showCreateEditModal.set(false);
    this.editingGroup.set(null);
  }

  onGroupSaved(group: AgentGroup): void {
    this.closeCreateEditModal();
    const existing = this.groups().find((g) => g.groupId === group.groupId);
    if (existing) {
      this.groups.update((list) => list.map((g) => (g.groupId === group.groupId ? group : g)));
      this.notifications.success('Grupa zaktualizowana.');
    } else {
      this.groups.update((list) => [group, ...list]);
      this.total.update((t) => t + 1);
      this.notifications.success('Grupa utworzona.');
    }
  }

  openMembersModal(group: AgentGroup): void {
    this.managingMembersGroup.set(group);
    this.showMembersModal.set(true);
  }

  closeMembersModal(): void {
    this.showMembersModal.set(false);
    this.managingMembersGroup.set(null);
  }

  onMembersSaved(): void {
    this.closeMembersModal();
    this.loadGroups();
    this.notifications.success('Skład grupy zaktualizowany.');
  }

  openDeleteModal(group: AgentGroup): void {
    this.deletingGroup.set(group);
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    this.deletingGroup.set(null);
  }

  confirmDelete(): void {
    const group = this.deletingGroup();
    if (!group) return;
    this.deleteLoading.set(true);

    this.agentGroupService
      .deleteGroup(group.groupId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((err: HttpErrorResponse) => {
          if (err.status === 409) {
            this.notifications.error(
              'Nie można usunąć grupy przypisanej do kolejki. Usuń najpierw powiązanie z kolejką.',
            );
          } else {
            this.notifications.error('Nie udało się usunąć grupy. Spróbuj ponownie.');
          }
          return of(null);
        }),
        finalize(() => {
          this.deleteLoading.set(false);
          this.closeDeleteModal();
        }),
      )
      .subscribe((result) => {
        if (result !== null) {
          this.groups.update((list) => list.filter((g) => g.groupId !== group.groupId));
          this.total.update((t) => t - 1);
          this.notifications.success('Grupa usunięta.');
        }
      });
  }

  trackByGroupId(_index: number, group: AgentGroup): string {
    return group.groupId;
  }
}
