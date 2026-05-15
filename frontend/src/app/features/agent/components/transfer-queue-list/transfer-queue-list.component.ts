import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SoftphoneService } from '../../services/softphone.service';
import { TransferQueueItem } from '../../models/call-session.model';

@Component({
  selector: 'app-transfer-queue-list',
  standalone: true,
  imports: [],
  templateUrl: './transfer-queue-list.component.html',
  styleUrl: './transfer-queue-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TransferQueueListComponent implements OnInit {
  private readonly softphoneService = inject(SoftphoneService);
  private readonly destroyRef = inject(DestroyRef);

  queueSelected = output<{ queueId: string }>();

  protected readonly queues = signal<TransferQueueItem[]>([]);
  protected readonly loadState = signal<'loading' | 'loaded' | 'error'>('loading');

  ngOnInit(): void {
    this.softphoneService
      .fetchTransferQueues()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.queues.set(data);
          this.loadState.set('loaded');
        },
        error: () => this.loadState.set('error'),
      });
  }

  protected selectQueue(queue: TransferQueueItem): void {
    this.queueSelected.emit({ queueId: queue.queueId });
  }
}
