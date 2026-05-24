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
import { TranslocoModule } from '@jsverse/transloco';
import { SoftphoneService } from '../../services/softphone.service';
import { TransferAgentItem, TransferMode } from '../../models/call-session.model';

@Component({
  selector: 'app-transfer-agent-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, TranslocoModule],
  templateUrl: './transfer-agent-list.component.html',
  styleUrl: './transfer-agent-list.component.scss',
})
export class TransferAgentListComponent implements OnInit {
  private readonly softphoneService = inject(SoftphoneService);
  private readonly destroyRef = inject(DestroyRef);

  transferMode = input.required<TransferMode>();
  isTransferring = input<boolean>(false);
  agentSelected = output<{ agentId: string; displayName: string; mode: TransferMode }>();

  private readonly agents = signal<TransferAgentItem[]>([]);
  protected readonly searchQuery = signal('');
  protected readonly loadState = signal<'loading' | 'loaded' | 'error'>('loading');

  protected readonly filteredAgents = computed(() => {
    const q = this.searchQuery().toLowerCase().trim();
    if (!q) return this.agents();
    return this.agents().filter((a) => `${a.firstName} ${a.lastName}`.toLowerCase().includes(q));
  });

  ngOnInit(): void {
    this.softphoneService
      .fetchTransferAgents()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.agents.set(data);
          this.loadState.set('loaded');
        },
        error: () => this.loadState.set('error'),
      });
  }

  protected selectAgent(agent: TransferAgentItem): void {
    this.agentSelected.emit({ agentId: agent.agentId, displayName: `${agent.firstName} ${agent.lastName}`, mode: this.transferMode() });
  }
}
