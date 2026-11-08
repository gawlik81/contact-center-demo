import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { AgentStatusService } from '../../services/agent-status.service';

/**
 * Explains an automatic OFFLINE (FE-164): the server switched the agent because the connection
 * was lost for too long. It never moves the agent back by itself; the button is the conscious
 * way back to AVAILABLE. The strip disappears as soon as the agent leaves OFFLINE (by this
 * button or the status switcher). After a successful resume focus moves to the status switcher in the top bar. The status
 * region is always in the DOM so its text is announced politely, without taking focus.
 */
@Component({
  selector: 'cc-presence-offline-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './presence-offline-notice.component.html',
  styleUrl: './presence-offline-notice.component.scss',
})
export class PresenceOfflineNoticeComponent {
  private readonly status = inject(AgentStatusService);

  protected readonly visible = this.status.autoOffline;
  protected readonly changing = this.status.isChanging;
  protected readonly cause = this.status.autoOfflineCause;
  private readonly document = inject(DOCUMENT);

  protected resume(): void {
    if (this.changing()) return;
    this.status.resumeAvailable().subscribe({
      // Completes also after a failed PATCH (the error toast is raised by the service): move
      // focus only when the strip is really going away, otherwise the button keeps it.
      complete: () => {
        if (!this.status.autoOffline()) this.focusStatusSwitcher();
      },
    });
  }

  /** The strip (with the focused button) disappears; focus must not fall back to <body>. */
  private focusStatusSwitcher(): void {
    this.document.querySelector<HTMLElement>('[data-agent-status-trigger]')?.focus();
  }
}
