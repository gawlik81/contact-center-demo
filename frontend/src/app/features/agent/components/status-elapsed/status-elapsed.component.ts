import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoModule } from '@jsverse/transloco';
import { interval, map, startWith } from 'rxjs';
import { AgentStatusService } from '../../services/agent-status.service';

/**
 * Elapsed time of the current agent status (shown next to the status pill during a break).
 *
 * The origin is `AgentStatusService.statusSince` (server value, client-clock fallback), so the
 * count survives a page reload. Clock skew: the server instant is compared with the browser
 * clock; a browser clock that is behind would give a negative value, which is clamped to 0:00,
 * and one that is ahead adds a constant offset. Both are accepted (agent PCs are NTP-synced, the
 * display is informational) instead of adding a server-time offset mechanism.
 *
 * Accessibility: `role="timer"` has an implicit `aria-live="off"`, so the per-second ticks are
 * never announced; the screen reader reads the label + value only when the agent navigates to it.
 */
@Component({
  selector: 'cc-status-elapsed',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  template: `
    @if (text(); as value) {
      <span
        class="status-elapsed"
        role="timer"
        aria-live="off"
        [attr.title]="'agent.status.breakDuration' | transloco"
      >
        <span class="status-elapsed__sr">{{ 'agent.status.breakDuration' | transloco }}: </span>
        <time class="status-elapsed__value" [attr.datetime]="isoDuration()">{{ value }}</time>
      </span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    .status-elapsed {
      font-size: var(--fs-xs);
      font-variant-numeric: tabular-nums;
      color: var(--text-2);
      white-space: nowrap;
    }

    .status-elapsed__sr {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
      white-space: nowrap;
    }
  `,
})
export class StatusElapsedComponent {
  private readonly statusService = inject(AgentStatusService);

  /** One tick per second; only drives the visible text, never a live region. */
  private readonly now = toSignal(
    interval(1000).pipe(
      startWith(0),
      map(() => Date.now()),
    ),
    { initialValue: Date.now() },
  );

  protected readonly text = computed(() => {
    const ms = this.elapsedMs();
    return ms === null ? null : formatElapsed(ms);
  });

  protected readonly isoDuration = computed(() => {
    const ms = this.elapsedMs();
    return ms === null ? null : toIsoDuration(ms);
  });

  /** Elapsed ms, never negative (clock skew); null while the origin is unknown/invalid. */
  private readonly elapsedMs = computed(() => {
    const since = this.statusService.statusSince();
    if (since === null || !Number.isFinite(since)) return null;
    return Math.max(0, this.now() - since);
  });
}

/** `m:ss` below one hour, `h:mm:ss` from one hour. */
export function formatElapsed(ms: number): string {
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = s.toString().padStart(2, '0');
  return h > 0 ? `${h}:${m.toString().padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

/** ISO 8601 duration, e.g. `PT1H2M5S`, for the `datetime` attribute of `<time>`. */
export function toIsoDuration(ms: number): string {
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return `PT${h > 0 ? `${h}H` : ''}${h > 0 || m > 0 ? `${m}M` : ''}${s}S`;
}
