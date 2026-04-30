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
import { catchError, of } from 'rxjs';
import { AgentGroupService } from '../../../../../core/services/agent-group.service';
import { QueueAssignment } from '../../../../../core/models/agent-group.model';
import { Queue } from '../../../models/queue.model';

@Component({
  selector: 'app-queue-agents-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,],
  templateUrl: './queue-agents-modal.component.html',
  styleUrl: './queue-agents-modal.component.scss',
})
export class QueueAgentsModalComponent implements OnInit {
  private readonly agentGroupService = inject(AgentGroupService);
  private readonly destroyRef = inject(DestroyRef);

  readonly queue = input.required<Queue>();
  readonly closed = output<void>();

  readonly assignment = signal<QueueAssignment | null>(null);
  readonly loading = signal(true);
  readonly notConfigured = signal(false);

  readonly totalAgentCount = computed(() => {
    const a = this.assignment();
    if (!a || a.allAgents) return null;
    const fromGroups = a.groups.reduce((sum, g) => sum + g.memberCount, 0);
    return a.directAgents.length + fromGroups;
  });

  ngOnInit(): void {
    this.agentGroupService
      .getQueueAssignment(this.queue().queueId)
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        this.loading.set(false);
        if (!result) {
          this.notConfigured.set(true);
        } else {
          this.assignment.set(result);
        }
      });
  }

  onClose(): void {
    this.closed.emit();
  }
}
