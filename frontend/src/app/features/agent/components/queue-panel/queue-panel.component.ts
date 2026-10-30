import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { QueueItem } from '../../models/queue-item.model';
import { QueueStateService } from '../../services/queue-state.service';

export const TYPE_ICONS: Record<QueueItem['type'], string> = {
  PHONE:
    'M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z',
  CHAT: 'M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 12H6v-2h12v2zm0-3H6V9h12v2zm0-3H6V6h12v2z',
  EMAIL:
    'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z',
  SOCIAL:
    'M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z',
};

/** Waiting-contacts list in the agent desktop sidebar (extracted from AgentDesktopComponent, FE-124). */
@Component({
  selector: 'app-queue-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './queue-panel.component.html',
  styleUrl: './queue-panel.component.scss',
})
export class QueuePanelComponent {
  private readonly transloco = inject(TranslocoService);

  protected readonly items = inject(QueueStateService).queueItems;
  protected readonly now = signal(Date.now());
  private readonly lang = toSignal(this.transloco.langChanges$, {
    initialValue: this.transloco.getActiveLang(),
  });

  /** "3 oczekujących": the plural form comes from Intl.PluralRules, the text from the i18n key. */
  protected readonly badgeLabel = computed(() => {
    const count = this.items().length;
    const category = new Intl.PluralRules(this.lang()).select(count);
    return this.transloco.translate(`agent.desktop.queueWaitingCount_${category}`, { count });
  });

  constructor() {
    // The wait-time ticker only runs while someone is waiting; torn down on destroy by the effect.
    const hasWaiting = computed(() => this.items().length > 0);
    effect((onCleanup) => {
      if (!hasWaiting()) return;
      untracked(() => this.now.set(Date.now()));
      const timer = setInterval(() => this.now.set(Date.now()), 1000);
      onCleanup(() => clearInterval(timer));
    });
  }

  protected getWaitingTime(waitingSince: Date): string {
    const diffMs = this.now() - new Date(waitingSince).getTime();
    const minutes = Math.floor(diffMs / 60_000);
    const seconds = Math.floor((diffMs % 60_000) / 1_000);
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }

  protected getQueueTypeIcon(item: QueueItem): string {
    return TYPE_ICONS[item.type];
  }

  protected readonly trackByQueueId = (_i: number, item: QueueItem) => item.id;
}
